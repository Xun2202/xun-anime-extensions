package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Private trust is confined to one explicitly configured HTTPS origin. */
internal class NasConnection(
    server: String,
    caPem: String,
    expectedFingerprint: String,
    private val token: () -> String,
) {
    val endpoint: HttpUrl = parseEndpoint(server)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .apply {
            if (caPem.isNotBlank()) {
                val ca = parseCa(caPem, expectedFingerprint)
                val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    setCertificateEntry("nas-only", ca)
                }
                val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
                val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
                val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
                sslSocketFactory(ssl.socketFactory, manager)
            } else {
                if (expectedFingerprint.isNotBlank()) throw NasConfigurationException(ConfigurationIssue.CA_PAIR)
            }
        }
        .addInterceptor { chain ->
            val request = chain.request()
            if (!sameOrigin(request.url)) throw IOException("已阻止向其他服务器或明文地址发送 NAS 请求")
            val isLogin = request.url.encodedPath == endpoint.encodedPath.trimEnd('/') + "/Users/AuthenticateByName"
            val session = if (isLogin) "" else token()
            val authorization = CLIENT + if (session.isEmpty()) "" else ", Token=\"${safeToken(session)}\""
            val secured = request.newBuilder()
                .removeHeader("X-Emby-Token")
                .header("Authorization", authorization)
                .build()
            val response = chain.proceed(secured)
            if (response.isRedirect) {
                response.close()
                throw IOException("NAS 返回了重定向；为保护会话，请直接配置最终 HTTPS 地址")
            }
            response
        }
        .build()

    fun sameOrigin(url: HttpUrl): Boolean = url.scheme == "https" && url.host == endpoint.host && url.port == endpoint.port &&
        (endpoint.encodedPath == "/" || url.encodedPath.startsWith(endpoint.encodedPath))

    fun url(vararg segments: String): HttpUrl = endpoint.newBuilder().apply {
        for (segment in segments) addPathSegment(segment)
    }.build()

    companion object {
        private const val CLIENT = "MediaBrowser Client=\"Jellyfin Xun\", Device=\"Anikku\", DeviceId=\"jellyfin-xun\", Version=\"1\""

        fun parseEndpoint(value: String): HttpUrl {
            val parsed = value.trim().toHttpUrlOrNull()
            if (parsed == null || parsed.scheme != "https") throw NasConfigurationException(ConfigurationIssue.URL_FORMAT)
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) throw NasConfigurationException(ConfigurationIssue.URL_SENSITIVE)
            return parsed.newBuilder().encodedPath(parsed.encodedPath.trimEnd('/') + "/").build()
        }

        fun parseCa(pem: String, expected: String): X509Certificate {
            val normalized = normalizeFingerprint(expected)
            val certificates = try {
                CertificateFactory.getInstance("X.509").generateCertificates(normalizePem(pem).byteInputStream())
            } catch (_: Exception) {
                throw NasConfigurationException(ConfigurationIssue.CA_PEM_FORMAT)
            }
            if (certificates.size != 1) throw NasConfigurationException(ConfigurationIssue.CA_PEM_FORMAT)
            val ca = certificates.single() as X509Certificate
            ca.checkValidity()
            if (ca.basicConstraints < 0) throw NasConfigurationException(ConfigurationIssue.CA_NOT_CA)
            if (ca.keyUsage?.getOrNull(5) == false) throw NasConfigurationException(ConfigurationIssue.CA_USAGE)
            if (!MessageDigest.isEqual(fingerprint(ca).toByteArray(), normalized.toByteArray())) throw NasConfigurationException(ConfigurationIssue.CA_FINGERPRINT_MISMATCH)
            return ca
        }

        fun normalizeFingerprint(value: String): String {
            val result = value.filterNot { it.isWhitespace() || it == ':' }.lowercase()
            if (!result.matches(Regex("[0-9a-f]{64}"))) throw NasConfigurationException(ConfigurationIssue.CA_FINGERPRINT_FORMAT)
            return result
        }

        fun normalizePem(value: String): String {
            if (value.length > 16_384 || value.contains("PRIVATE KEY")) throw NasConfigurationException(ConfigurationIssue.CA_PEM_FORMAT)
            val cleaned = value.trim { it.isWhitespace() || it == '\uFEFF' }
            val match = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----").matchEntire(cleaned)
                ?: throw NasConfigurationException(ConfigurationIssue.CA_PEM_FORMAT)
            val body = match.groupValues[1].filterNot(Char::isWhitespace)
            if (!body.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) throw NasConfigurationException(ConfigurationIssue.CA_PEM_FORMAT)
            return "-----BEGIN CERTIFICATE-----\n" + body.chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
        }

        fun fingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun safeToken(value: String): String {
            require(value.matches(Regex("[A-Za-z0-9._~-]+"))) { "NAS 会话格式不正确，请重新登录" }
            return value
        }
    }
}
