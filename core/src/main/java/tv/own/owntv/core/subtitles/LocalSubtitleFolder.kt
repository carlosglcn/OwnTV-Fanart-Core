package tv.own.owntv.core.subtitles

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The subtitle files in the folder chosen for "Find in folder": a plain path (the TV's own storage
 * browser) or a `content://` document tree the phone was granted with the system folder picker.
 *
 * A folder is listed in one pass — for a document tree one children query per folder, never a lookup per
 * file, which is what makes a folder of a couple of thousand files list in well under a second — and the
 * listing is kept for [CACHE_MS], so typing in the search box or the next episode's automatic match does
 * not walk it again. Subfolders are included down to [MAX_DEPTH] levels (one per film is a common layout).
 */
class LocalSubtitleFolder(private val context: Context) {

    /** One subtitle file: [location] is a path, or a document Uri for a document tree. */
    data class SubtitleFile(val name: String, val size: Long, val location: String)

    private val lock = Mutex()
    private var cached: Triple<String, Long, List<SubtitleFile>>? = null

    suspend fun list(folder: String): List<SubtitleFile> = lock.withLock {
        val now = SystemClock.elapsedRealtime()
        cached?.takeIf { it.first == folder && now - it.second < CACHE_MS }?.let { return@withLock it.third }
        val files = withContext(Dispatchers.IO) {
            if (folder.startsWith(CONTENT_PREFIX)) listTree(folder.toUri()) else listPath(File(folder))
        }
        cached = Triple(folder, now, files)
        files
    }

    /** The file's bytes, refused past [MAX_BYTES] — a subtitle is tens of kilobytes, a mislabelled film is not. */
    suspend fun read(file: SubtitleFile): ByteArray = withContext(Dispatchers.IO) {
        if (file.size > MAX_BYTES) throw IOException("subtitle too large")
        val bytes = if (file.location.startsWith(CONTENT_PREFIX)) {
            context.contentResolver.openInputStream(file.location.toUri())?.use { it.readBytes() }
        } else {
            File(file.location).takeIf { it.isFile }?.readBytes()
        }
        bytes?.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES } ?: throw IOException("unreadable subtitle")
    }

    private fun listPath(root: File): List<SubtitleFile> =
        root.walkTopDown().maxDepth(MAX_DEPTH + 1)
            .filter { it.isFile && isSubtitle(it.name) }
            .take(MAX_FILES)
            .map { SubtitleFile(it.name, it.length(), it.absolutePath) }
            .toList()

    private fun listTree(tree: Uri): List<SubtitleFile> {
        val out = ArrayList<SubtitleFile>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to 0))
        while (pending.isNotEmpty() && out.size < MAX_FILES) {
            val (parentId, depth) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            context.contentResolver.query(children, COLUMNS, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_FILES) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH) pending.add(id to depth + 1)
                    } else if (isSubtitle(name)) {
                        val size = if (c.isNull(2)) 0L else c.getLong(2)
                        out.add(SubtitleFile(name, size, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()))
                    }
                }
            }
        }
        return out
    }

    private fun isSubtitle(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    private companion object {
        const val CONTENT_PREFIX = "content://"
        const val CACHE_MS = 60_000L
        const val MAX_DEPTH = 3
        const val MAX_FILES = 20_000
        const val MAX_BYTES = 5L * 1024 * 1024
        val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "webvtt")
        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
    }
}
