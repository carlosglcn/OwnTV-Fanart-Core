package tv.own.owntv.core.network

import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * glcn: completes a server chain that arrives without its intermediate certificate, the way browsers do.
 *
 * A home server (a NAS reverse proxy is the usual case) often sends only its own certificate. Chrome and
 * newer phones fetch the missing intermediate from the address the certificate names (Authority
 * Information Access, "CA Issuers"); Android's own check does not, so the TV said "Trust anchor for
 * certification path not found" for a server the phone opened. Only used after the normal check failed;
 * at most [MAX_HOPS] certificates are fetched per chain, each kept in memory once fetched.
 */
internal object AiaChainCompleter {
    private const val MAX_HOPS = 3
    private const val TIMEOUT_MS = 5_000
    private const val MAX_BYTES = 64 * 1024

    /** OID 1.3.6.1.5.5.7.1.1 (Authority Information Access). */
    private const val AIA_OID = "1.3.6.1.5.5.7.1.1"

    /** DER of OID 1.3.6.1.5.5.7.48.2 (id-ad-caIssuers). */
    private val CA_ISSUERS = byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)

    private val cache = ConcurrentHashMap<String, List<X509Certificate>>()

    /** [chain] with the missing issuers appended, or null when nothing could be added. */
    fun complete(chain: Array<X509Certificate>): Array<X509Certificate>? {
        if (chain.isEmpty()) return null
        val out = chain.toMutableList()
        var added = false
        repeat(MAX_HOPS) {
            val last = out.last()
            if (last.subjectX500Principal == last.issuerX500Principal) return@repeat
            if (out.any { it !== last && it.subjectX500Principal == last.issuerX500Principal }) return@repeat
            val url = caIssuersUrl(last) ?: return@repeat
            val fetched = cache[url] ?: fetch(url)?.also { cache[url] = it } ?: return@repeat
            val issuer = fetched.firstOrNull { it.subjectX500Principal == last.issuerX500Principal } ?: return@repeat
            out += issuer
            added = true
        }
        return if (added) out.toTypedArray() else null
    }

    /** The first CA Issuers URI in [cert]'s AIA extension. */
    internal fun caIssuersUrl(cert: X509Certificate): String? {
        val ext = cert.getExtensionValue(AIA_OID) ?: return null
        var i = 0
        while (i <= ext.size - CA_ISSUERS.size) {
            if ((CA_ISSUERS.indices).all { ext[i + it] == CA_ISSUERS[it] }) {
                val tag = i + CA_ISSUERS.size
                // GeneralName uniformResourceIdentifier: [6] IMPLICIT IA5String → tag 0x86, short length.
                if (tag + 1 < ext.size && (ext[tag].toInt() and 0xFF) == 0x86) {
                    val len = ext[tag + 1].toInt() and 0xFF
                    if (len < 0x80 && tag + 2 + len <= ext.size) {
                        val uri = String(ext, tag + 2, len, Charsets.US_ASCII)
                        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
                    }
                }
            }
            i++
        }
        return null
    }

    private fun fetch(url: String): List<X509Certificate>? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            val bytes = conn.inputStream.use { s -> s.readNBytesCompat(MAX_BYTES) }
            // A DER certificate or a PKCS#7 bundle — the factory reads both.
            CertificateFactory.getInstance("X.509").generateCertificates(bytes.inputStream()).map { it as X509Certificate }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buf.size() < max) {
            val n = read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }
}
