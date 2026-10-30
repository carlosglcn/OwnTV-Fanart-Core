package tv.own.owntv.core.netshare

import com.emc.ecs.nfsclient.nfs.NfsDirectoryPlusEntry
import com.emc.ecs.nfsclient.nfs.NfsType
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import java.util.concurrent.ConcurrentHashMap

/**
 * glcn: the NFS side of [NetShare] (NFS v3, pure Java). The export is the folder the NAS shares ("/volume1/video" on a
 * Synology); paths are inside it. A phone or TV box cannot use a port below 1024, so the NAS must allow "non-privileged
 * ports" for this client (Synology: the NFS rule's "Allow connections from non-privileged ports").
 */
internal object NfsAccess {

    private val mounts = ConcurrentHashMap<String, Nfs3>()

    /** The user field may hold "uid" or "uid:gid" (what the NAS maps us to); none means 0 (root, often squashed). */
    fun ids(user: String): Pair<Int, Int> {
        val uid = user.substringBefore(':').trim().toIntOrNull() ?: 0
        val gid = user.substringAfter(':', "").trim().toIntOrNull() ?: uid
        return uid to gid
    }

    fun mount(host: String, export: String, user: String): Nfs3 {
        val (uid, gid) = ids(user)
        val key = "$host|$export|$uid:$gid"
        mounts[key]?.let { return it }
        synchronized(this) {
            mounts[key]?.let { return it }
            val nfs = Nfs3(host, export, CredentialUnix(uid, gid, null), RETRIES)
            mounts[key] = nfs
            return nfs
        }
    }

    fun forget(host: String, export: String, user: String) {
        val (uid, gid) = ids(user)
        mounts.remove("$host|$export|$uid:$gid")
    }

    private fun abs(path: String) = "/" + path.replace('\\', '/').trim('/')

    fun file(nfs: Nfs3, path: String) = Nfs3File(nfs, abs(path))

    fun list(nfs: Nfs3, path: String): List<SmbAccess.Entry> {
        val dir = file(nfs, path)
        val entries = ArrayList<NfsDirectoryPlusEntry>()
        var cookie = 0L
        var verf = 0L
        var guard = 0
        while (guard++ < MAX_PAGES) {
            val r = dir.readdirplus(cookie, verf, DIR_COUNT, MAX_COUNT, entries)
            cookie = r.cookie
            verf = r.cookieverf
            if (r.isEof) break
        }
        return entries.mapNotNull { e ->
            val name = e.fileName ?: return@mapNotNull null
            if (name == "." || name == ".." || name.startsWith(".")) return@mapNotNull null
            val a = e.attributes
            val folder = a != null && a.type == NfsType.NFS_DIR
            SmbAccess.Entry(name, folder, a?.size ?: 0L, a?.mtime?.timeInMillis ?: 0L)
        }
    }

    fun folderExists(nfs: Nfs3, path: String): Boolean = runCatching { file(nfs, path).let { it.exists() && it.isDirectory } }.getOrDefault(false)

    private const val RETRIES = 3
    private const val DIR_COUNT = 8_192
    private const val MAX_COUNT = 65_536
    private const val MAX_PAGES = 200
}
