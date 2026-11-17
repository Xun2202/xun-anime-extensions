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
                require(expectedFingerprint.isBlank()) { "请同时填写 CA 公钥证书与 SHA-256 指纹" }
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
            require(parsed != null && parsed.scheme == "https") { "服务器地址必须是 https://主机:端口" }
            require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null) {
                "服务器地址不能包含账号、密码、查询参数或片段"
            }
            return parsed.newBuilder().encodedPath(parsed.encodedPath.trimEnd('/') + "/").build()
        }

        fun parseCa(pem: String, expected: String): X509Certificate {
            val normalized = expected.replace(":", "").replace(" ", "").lowercase()
            require(normalized.matches(Regex("[0-9a-f]{64}"))) { "请填写从 NAS 可信文件取得的完整 SHA-256 指纹" }
            val certificates = try {
                CertificateFactory.getInstance("X.509").generateCertificates(pem.trim().byteInputStream())
            } catch (_: Exception) {
                throw IllegalArgumentException("无法读取 CA 公钥证书，请粘贴完整 PEM 内容")
            }
            require(certificates.size == 1) { "请只导入当前 NAS 的一个 CA 公钥证书" }
            val ca = certificates.single() as X509Certificate
            ca.checkValidity()
            require(ca.basicConstraints >= 0) { "导入的不是 CA 公钥证书" }
            require(ca.keyUsage?.getOrNull(5) != false) { "CA 证书不能用于验证签名" }
            require(MessageDigest.isEqual(fingerprint(ca).toByteArray(), normalized.toByteArray())) { "CA 指纹不匹配，连接已停止" }
            return ca
        }

        fun fingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun safeToken(value: String): String {
            require(value.matches(Regex("[A-Za-z0-9._~-]+"))) { "NAS 会话格式不正确，请重新登录" }
            return value
        }
    }
}
