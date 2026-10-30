package tv.own.owntv.core.netshare

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * glcn: the SMB side of [NetShare] — one client for the app, one kept connection per share. A share that stopped
 * answering (the NAS slept, the network changed) is dropped and opened again once.
 */
internal object SmbAccess {

    /** An entry of a folder: a sub-folder or a file with its size and when it last changed. */
    data class Entry(val name: String, val folder: Boolean, val size: Long, val changedMs: Long)

    class AuthFailed(cause: Throwable?) : java.io.IOException("SMB login refused", cause)

    private val client by lazy {
        SMBClient(
            SmbConfig.builder()
                .withSecurityProvider(com.hierynomus.security.bc.BCSecurityProvider())
                .withMultiProtocolNegotiate(true)
                .withTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .withSoTimeout(SO_TIMEOUT_S, TimeUnit.SECONDS)
                .withReadBufferSize(BUFFER)
                .build(),
        )
    }

    private val shares = ConcurrentHashMap<String, DiskShare>()

    private fun key(host: String, port: Int, share: String, login: NetShare.Login?) =
        "$host:$port/$share|${login?.domain}\\${login?.user}|${login?.password.hashCode()}"

    private fun open(host: String, port: Int, shareName: String, login: NetShare.Login?): DiskShare {
        val k = key(host, port, shareName, login)
        shares[k]?.let { if (it.isConnected) return it else shares.remove(k) }
        synchronized(this) {
            shares[k]?.let { if (it.isConnected) return it }
            val connection = client.connect(host, port)
            val auth = when {
                login == null || login.user.isBlank() -> AuthenticationContext.guest()
                else -> AuthenticationContext(login.user, login.password.toCharArray(), login.domain.ifBlank { null })
            }
            val session = try {
                connection.authenticate(auth)
            } catch (e: com.hierynomus.mssmb2.SMBApiException) {
                runCatching { connection.close() }
                throw AuthFailed(e)
            }
            val share = session.connectShare(shareName) as? DiskShare
                ?: throw java.io.IOException("Not a disk share: $shareName")
            shares[k] = share
            return share
        }
    }

    /** Runs [block] on the share, opening it again once if the kept connection had gone. */
    fun <T> withShare(host: String, port: Int, share: String, login: NetShare.Login?, block: (DiskShare) -> T): T {
        val first = open(host, port, share, login)
        return try {
            block(first)
        } catch (e: AuthFailed) {
            throw e
        } catch (e: Exception) {
            // Only the share's own connection going away is worth a second try; anything else (a missing file,
            // the player hanging up on the loopback side) is the caller's.
            val gone = when (e) {
                is com.hierynomus.mssmb2.SMBApiException -> e.isConnectionGone()
                is com.hierynomus.protocol.transport.TransportException -> true
                is com.hierynomus.smbj.common.SMBRuntimeException -> true
                else -> false
            }
            if (!gone) throw e
            shares.remove(key(host, port, share, login))
            runCatching { first.close() }
            block(open(host, port, share, login))
        }
    }

    private fun com.hierynomus.mssmb2.SMBApiException.isConnectionGone(): Boolean {
        val s = status
        return s == com.hierynomus.mserref.NtStatus.STATUS_NETWORK_NAME_DELETED ||
            s == com.hierynomus.mserref.NtStatus.STATUS_USER_SESSION_DELETED
    }

    fun list(share: DiskShare, path: String): List<Entry> =
        share.list(smbPath(path)).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            val folder = EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
            val hidden = EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN)
            if (hidden) return@mapNotNull null
            Entry(name, folder, info.endOfFile, info.lastWriteTime.toEpochMillis())
        }

    fun folderExists(share: DiskShare, path: String): Boolean = runCatching { share.folderExists(smbPath(path)) }.getOrDefault(false)

    fun openRead(share: DiskShare, path: String): File = share.openFile(
        smbPath(path),
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null,
    )

    /** How much one read may ask for on this share (SMB 2.0 servers allow only 64 KB). */
    fun readChunk(share: DiskShare): Int =
        runCatching { share.treeConnect.session.connection.negotiatedProtocol.maxReadSize }.getOrDefault(FALLBACK_READ)
            .coerceIn(FALLBACK_READ, MAX_READ)

    private fun smbPath(path: String) = path.replace('/', '\\').trim('\\')

    private const val TIMEOUT_S = 20L
    private const val SO_TIMEOUT_S = 30L
    private const val BUFFER = 1 shl 20
    private const val FALLBACK_READ = 64 * 1024
    private const val MAX_READ = 1 shl 20
}
