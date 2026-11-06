package tv.own.owntv.core.emby

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * glcn: the seek-bar thumbnails an Emby or Jellyfin server has made for the film playing ("seek previews" /
 * trickplay), read from its stream URL like [EmbyPlayback].
 *
 * - Jellyfin keeps them as JPEG mosaics: the item's `Trickplay` field gives the grid, and each mosaic is
 *   `/Videos/{id}/Trickplay/{width}/{n}.jpg`. Only the one thumbnail needed is decoded out of it.
 * - Emby keeps one Roku BIF file per item (`/Videos/{id}/index.bif?Width=…`): downloaded once to the cache and
 *   read by offset, one small JPEG at a time.
 *
 * Nothing is fetched until the seek bar asks ([Source.prepare]); a server without thumbnails costs one small
 * request. Few thumbnails are kept in memory.
 */
object EmbyTrickplay {

    private const val TAG = "EmbyTrickplay"
    private val URL_RE = Regex("""^(https?://.+?)/Videos/([0-9A-Za-z]+)/stream[^?]*\?(?:.*&)?api_key=([^&]+)""")
    private const val WANT_WIDTH = 320
    private const val KEEP_BITMAPS = 12
    private const val KEEP_TILES = 2
    private const val BIF_HEADER = 64
    private val BIF_MAGIC = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0D, 0x0A, 0x1A, 0x0A)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        tv.own.owntv.core.network.SharedHttp.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    private val _current = MutableStateFlow<Source?>(null)
    /** The thumbnails of the Emby/Jellyfin film playing; null for anything else. */
    val current: StateFlow<Source?> = _current.asStateFlow()

    /** The player's stream changed (null = stopped). Called from [EmbyPlayback.onUrlChanged]. */
    internal fun onUrlChanged(url: String?) {
        val m = url?.let { URL_RE.find(it) }
        val key = m?.let { it.groupValues[1] + "|" + it.groupValues[2] }
        if (key != null && _current.value?.key == key) return
        _current.value?.close()
        _current.value = m?.let { Source(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
    }

    /** One item's thumbnails. */
    class Source internal constructor(private val api: String, private val itemId: String, private val token: String) {
        internal val key = "$api|$itemId"
        private val bitmaps = LruCache<Int, Bitmap>(KEEP_BITMAPS)
        @Volatile private var closed = false
        @Volatile private var loaded: Thumbs? = null
        private val loading: Deferred<Thumbs?> = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runCatching {
                // Emby answers on an "/emby" prefix, Jellyfin on none: ask the likelier one first.
                if (api.endsWith("/emby", ignoreCase = true)) bif() ?: jellyfin() else jellyfin() ?: bif()
            }.onFailure { Log.w(TAG, "trickplay unavailable: ${it.javaClass.simpleName}") }.getOrNull()
                ?.also { t -> if (closed) t.close() else loaded = t }
        }

        /** Start fetching what the thumbnails need (the grid, or the BIF file), without waiting for it. */
        fun prepare() { loading.start() }

        /** The thumbnail for [positionMs], or null when the server has none (or it cannot be read). */
        suspend fun thumbAt(positionMs: Long): Bitmap? {
            val t = loading.await() ?: return null
            val i = t.indexAt(positionMs)
            bitmaps.get(i)?.let { return it }
            return withContext(Dispatchers.IO) {
                if (closed) null else runCatching { t.decode(i) }.getOrNull()?.also { bitmaps.put(i, it) }
            }
        }

        internal fun close() {
            closed = true
            loading.cancel()
            loaded?.let { t -> runCatching { t.close() } }
            loaded = null
            bitmaps.evictAll()
        }

        private fun get(path: String): okhttp3.Response =
            client.newCall(
                Request.Builder().url("$api$path${if ('?' in path) '&' else '?'}api_key=$token")
                    .header("X-Emby-Token", token).get().build(),
            ).execute()

        /** Jellyfin 10.9+: the item's `Trickplay` grid, then mosaics on demand. */
        private fun jellyfin(): Thumbs? {
            val json = get("/Items/$itemId?Fields=Trickplay").use { r ->
                if (!r.isSuccessful) return null
                JSONObject(r.body?.string().orEmpty())
            }
            val all = json.optJSONObject("Trickplay") ?: return null
            val sourceId = all.keys().asSequence().firstOrNull { it == itemId } ?: all.keys().asSequence().firstOrNull() ?: return null
            val widths = all.optJSONObject(sourceId) ?: return null
            val width = widths.keys().asSequence().mapNotNull { it.toIntOrNull() }
                .minByOrNull { kotlin.math.abs(it - WANT_WIDTH) } ?: return null
            val info = widths.optJSONObject(width.toString()) ?: return null
            val w = info.optInt("Width"); val h = info.optInt("Height")
            val tw = info.optInt("TileWidth"); val th = info.optInt("TileHeight")
            val count = info.optInt("ThumbnailCount"); val interval = info.optLong("Interval")
            if (w <= 0 || h <= 0 || tw <= 0 || th <= 0 || count <= 0 || interval <= 0) return null
            return Mosaic(sourceId, width, w, h, tw, th, count, interval)
        }

        /** Emby: the BIF file, saved to the cache and read by offset. */
        private fun bif(): Thumbs? {
            val file = File(System.getProperty("java.io.tmpdir") ?: return null, "glcn_bif_$itemId.bif")
            get("/Videos/$itemId/index.bif?Width=$WANT_WIDTH").use { r ->
                if (!r.isSuccessful) return null
                val body = r.body ?: return null
                file.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            return Bif.open(file) ?: run { file.delete(); null }
        }

        private interface Thumbs {
            fun indexAt(positionMs: Long): Int
            fun decode(index: Int): Bitmap?
            fun close() {}
        }

        private inner class Mosaic(
            private val sourceId: String,
            private val width: Int,
            private val w: Int,
            private val h: Int,
            private val tw: Int,
            private val th: Int,
            private val count: Int,
            private val intervalMs: Long,
        ) : Thumbs {
            private val tiles = LruCache<Int, ByteArray>(KEEP_TILES)

            override fun indexAt(positionMs: Long): Int = (positionMs / intervalMs).toInt().coerceIn(0, count - 1)

            override fun decode(index: Int): Bitmap? {
                val perTile = tw * th
                val tile = index / perTile
                val r = index % perTile
                val bytes = tiles.get(tile) ?: get("/Videos/$itemId/Trickplay/$width/$tile.jpg?MediaSourceId=$sourceId").use { resp ->
                    if (!resp.isSuccessful) return null
                    resp.body?.bytes()
                }?.also { tiles.put(tile, it) } ?: return null
                val x = (r % tw) * w
                val y = (r / tw) * h
                val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
                } else {
                    @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                } ?: return null
                return try {
                    val rect = Rect(x, y, minOf(x + w, decoder.width), minOf(y + h, decoder.height))
                    if (rect.isEmpty) null else decoder.decodeRegion(rect, null)
                } finally {
                    decoder.recycle()
                }
            }
        }

        /** A Roku BIF file: 64-byte header, then (timestamp, offset) pairs, then the JPEGs. */
        private class Bif(private val file: File, private val raf: RandomAccessFile, private val stamps: LongArray, private val offsets: LongArray) : Thumbs {

            override fun indexAt(positionMs: Long): Int {
                // The last thumbnail at or before the position.
                var lo = 0
                var hi = stamps.size - 1
                while (lo < hi) {
                    val mid = (lo + hi + 1) ushr 1
                    if (stamps[mid] <= positionMs) lo = mid else hi = mid - 1
                }
                return lo
            }

            override fun decode(index: Int): Bitmap? {
                val from = offsets[index]
                val len = (offsets[index + 1] - from).toInt()
                if (len <= 0) return null
                val bytes = ByteArray(len)
                synchronized(raf) {
                    raf.seek(from)
                    raf.readFully(bytes)
                }
                return BitmapFactory.decodeByteArray(bytes, 0, len)
            }

            override fun close() {
                runCatching { raf.close() }
                runCatching { file.delete() }
            }

            companion object {
                fun open(file: File): Bif? {
                    val raf = RandomAccessFile(file, "r")
                    try {
                        if (raf.length() < BIF_HEADER) return null.also { raf.close() }
                        val head = ByteArray(BIF_HEADER)
                        raf.readFully(head)
                        for (i in BIF_MAGIC.indices) if (head[i] != BIF_MAGIC[i]) return null.also { raf.close() }
                        val count = u32(head, 12).toInt()
                        val separation = u32(head, 16).takeIf { it > 0 } ?: 1000L
                        if (count <= 0) return null.also { raf.close() }
                        val table = ByteArray((count + 1) * 8)
                        raf.readFully(table)
                        val stamps = LongArray(count)
                        val offsets = LongArray(count + 1)
                        for (i in 0..count) {
                            if (i < count) stamps[i] = u32(table, i * 8) * separation
                            offsets[i] = u32(table, i * 8 + 4)
                        }
                        return Bif(file, raf, stamps, offsets)
                    } catch (e: Exception) {
                        raf.close()
                        return null
                    }
                }

                private fun u32(d: ByteArray, at: Int): Long =
                    (d[at].toLong() and 0xFF) or ((d[at + 1].toLong() and 0xFF) shl 8) or
                        ((d[at + 2].toLong() and 0xFF) shl 16) or ((d[at + 3].toLong() and 0xFF) shl 24)
            }
        }
    }
}
