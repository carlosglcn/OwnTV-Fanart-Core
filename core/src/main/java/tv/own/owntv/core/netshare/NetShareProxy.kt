package tv.own.owntv.core.netshare

import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * glcn: plays a file from a home-network share. Neither player speaks SMB, both speak HTTP: this serves the share's
 * files on a loopback address (`http://127.0.0.1:<port>/smb/host/share/path`), with byte ranges, so the player can
 * seek and read only what it shows. Started the first time a share file is played; never reachable from outside
 * the box.
 */
object NetShareProxy {

    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "netshare").apply { isDaemon = true } }

    /** What the player should open: a share file's loopback address, anything else untouched. */
    fun playable(url: String): String {
        if (!NetShare.isShare(url)) return url
        val port = runCatching { ensureStarted() }.getOrElse {
            Log.w(TAG, "proxy did not start", it)
            return url
        }
        return "http://127.0.0.1:$port$PREFIX" + url.trim().substring(NetShare.SCHEME.length).substringAfterLast('@').substringBefore('?')
    }

    fun isProxied(url: String): Boolean = url.startsWith("http://127.0.0.1:") && url.contains(PREFIX)

    @Synchronized
    private fun ensureStarted(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: continue
                pool.execute { serve(client) }
            }
        }, "netshare-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    private fun serve(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = CLIENT_TIMEOUT_MS
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                val c = h.indexOf(':')
                if (c > 0) headers[h.substring(0, c).trim().lowercase()] = h.substring(c + 1).trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val target = parts.getOrNull(1).orEmpty()
            if ((method != "GET" && method != "HEAD") || !target.startsWith(PREFIX)) return status(out, 400, "Bad Request")
            val ref = NetShare.fileRef(NetShare.SCHEME + target.removePrefix(PREFIX)) ?: return status(out, 404, "Not Found")
            val login = NetShare.credentialsFor(ref.host, ref.port, ref.share)
            try {
                SmbAccess.withShare(ref.host, ref.port, ref.share, login) { share ->
                    SmbAccess.openRead(share, ref.path).use { file ->
                        val size = file.fileInformation.standardInformation.endOfFile
                        val range = parseRange(headers["range"], size)
                        val start = range?.first ?: 0L
                        val end = range?.second ?: (size - 1)
                        if (range != null && (start >= size || start > end)) {
                            write(out, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            return@withShare
                        }
                        val length = end - start + 1
                        val head = StringBuilder()
                            .append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                            .append("Content-Type: ").append(mimeOf(ref.path)).append("\r\n")
                            .append("Accept-Ranges: bytes\r\n")
                            .append("Content-Length: ").append(length).append("\r\n")
                        if (range != null) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n")
                        head.append("Connection: close\r\n\r\n")
                        write(out, head.toString())
                        if (method == "HEAD") return@withShare
                        val chunk = SmbAccess.readChunk(share)
                        val buf = ByteArray(chunk)
                        var pos = start
                        while (pos <= end) {
                            val want = minOf(chunk.toLong(), end - pos + 1).toInt()
                            val n = file.read(buf, pos, 0, want)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            pos += n
                        }
                        out.flush()
                    }
                }
            } catch (e: SmbAccess.AuthFailed) {
                runCatching { status(out, 403, "Forbidden") }
            } catch (e: java.net.SocketException) {
                // The player let go (a seek, the end of playback): nothing to say.
            } catch (e: Exception) {
                Log.w(TAG, "serving ${ref.host}/${ref.share} failed: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { status(out, 502, "Bad Gateway") }
            }
        }
    }

    /** `bytes=a-b`, `bytes=a-`, `bytes=-n` → the first and last byte; null with no (or an unreadable) range. */
    private fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
        val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(6)?.substringBefore(',') ?: return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        return when {
            a.isEmpty() -> {
                val n = b.toLongOrNull() ?: return null
                (size - n).coerceAtLeast(0) to size - 1
            }
            else -> {
                val from = a.toLongOrNull() ?: return null
                val to = b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                from to to
            }
        }
    }

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "mpg", "mpeg", "vob" -> "video/mpeg"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        else -> "application/octet-stream"
    }

    private fun status(out: OutputStream, code: Int, text: String) =
        write(out, "HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

    private fun write(out: OutputStream, s: String) {
        out.write(s.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > MAX_LINE) return null
            sb.append(c.toChar())
        }
    }

    private const val TAG = "NetShareProxy"
    private const val PREFIX = "/smb/"
    private const val BACKLOG = 16
    private const val CLIENT_TIMEOUT_MS = 30_000
    private const val MAX_LINE = 8_192
}
