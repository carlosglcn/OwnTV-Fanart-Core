package tv.own.owntv.core.network

import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * glcn: what certificate a server shows — for "Test connection" when the check failed, so a self-signed
 * certificate, one for another name or a chain without its intermediate can be told apart. Only the TLS
 * handshake runs (nothing is sent after it), with a trust manager that accepts anything because nothing
 * is trusted here: the chain is only read and described.
 */
internal object CertProbe {
    private const val TIMEOUT_MS = 6_000

    fun describe(host: String, port: Int): String? = runCatching {
        val acceptAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(acceptAll), null) }
        (ctx.socketFactory.createSocket() as SSLSocket).use { s ->
            s.connect(java.net.InetSocketAddress(host, port), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            runCatching {
                val params = s.sslParameters
                params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                s.sslParameters = params
            }
            s.startHandshake()
            val chain = s.session.peerCertificates.filterIsInstance<X509Certificate>()
            val leaf = chain.firstOrNull() ?: return@use null
            fun cn(p: javax.security.auth.x500.X500Principal) =
                p.name.split(",").firstOrNull { it.trim().startsWith("CN=") }?.trim()?.removePrefix("CN=") ?: p.name.take(60)
            val names = runCatching { leaf.subjectAlternativeNames?.mapNotNull { it.getOrNull(1) as? String } }.getOrNull().orEmpty()
            val aia = AiaChainCompleter.caIssuersUrl(leaf)
            buildString {
                append("cert ${chain.size}: ")
                append(chain.joinToString(" ← ") { cn(it.subjectX500Principal) })
                append(" · issuer ").append(cn(chain.last().issuerX500Principal))
                if (names.isNotEmpty()) append(" · names ").append(names.take(4).joinToString(","))
                append(" · until ").append(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(leaf.notAfter))
                append(" · AIA ").append(aia ?: "-")
            }
        }
    }.getOrNull()
}
