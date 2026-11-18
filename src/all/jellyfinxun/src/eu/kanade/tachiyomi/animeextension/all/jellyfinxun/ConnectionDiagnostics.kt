package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

internal enum class LoginStage(val zh: String, val en: String) {
    CONFIGURATION("连接设置", "Connection settings"),
    HTTPS("HTTPS 和服务器", "HTTPS and server"),
    AUTHENTICATION("账号认证", "Account authentication"),
    LIBRARIES("媒体库读取", "Media library access"),
}

internal enum class ConfigurationIssue(val advice: String, val englishAdvice: String) {
    URL_FORMAT("请输入有效的 HTTPS 地址", "Enter a valid HTTPS address."),
    URL_SENSITIVE("请从地址中移除账号、密码、查询参数和页面片段", "Remove credentials, query parameters and fragments from the address."),
    CA_PAIR("请同时填写 CA 公钥证书和 SHA-256 指纹", "Enter both the CA certificate and its SHA-256 fingerprint."),
    CA_PEM_FORMAT("请粘贴完整的 PEM 格式 CA 公钥证书", "Paste the complete CA certificate in PEM format."),
    CA_FINGERPRINT_FORMAT("请输入完整的 64 位十六进制指纹", "Enter all 64 hexadecimal digits of the fingerprint."),
    CA_NOT_CA("请使用签发服务器证书的 CA 公钥证书", "Use the CA certificate that issued the server certificate."),
    CA_USAGE("这张 CA 证书无法验证服务器签名", "This CA certificate cannot verify the server signature."),
    CA_FINGERPRINT_MISMATCH("证书与指纹不匹配，请核对服务器管理员提供的两项信息", "The fingerprint does not match the CA certificate. Verify both with your server administrator."),
    USERNAME_EMPTY("请填写 Jellyfin 播放账号", "Enter your Jellyfin playback username."),
}

internal class NasConfigurationException(val issue: ConfigurationIssue) : IllegalArgumentException(issue.advice)
internal class NasHttpException(val status: Int) : IOException("Jellyfin HTTP $status")

/** Shareable reports include only configuration flags and classified errors. */
internal object ConnectionDiagnostics {
    data class Failure(val code: String, val advice: String)

    fun failure(stage: LoginStage, error: Throwable, chinese: Boolean = true): Failure {
        fun result(code: String, zh: String, en: String) = Failure(code, if (chinese) zh else en)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = generateSequence(error) { it.cause }.takeWhile(seen::add).take(12).toList()
        causes.filterIsInstance<NasConfigurationException>().firstOrNull()?.let {
            return result(it.issue.name, it.issue.advice, it.issue.englishAdvice)
        }
        causes.filterIsInstance<NasHttpException>().firstOrNull()?.let {
            val code = "HTTP_${it.status}"
            return when (it.status) {
                401 -> result(code, "请核对 Jellyfin 账号和密码", "Check your Jellyfin username and password.")
                403 -> if (stage == LoginStage.AUTHENTICATION) {
                    result(code, "请确认这个账号已启用且有播放权限", "Check that the account is enabled and has playback permission.")
                } else {
                    result(code, "请确认这个账号有权访问媒体库", "Check that the account has media library access.")
                }
                404 -> result(code, "请检查服务器地址，移除 /web 等网页路径", "Check the server address and remove page paths such as /web.")
                else -> result(code, "服务器返回 HTTP ${it.status}", "The server returned HTTP ${it.status}.")
            }
        }
        return when {
            causes.any { it is CertificateExpiredException || it is CertificateNotYetValidException } ->
                result("CERTIFICATE_TIME", "请核对手机时间和证书有效期", "Check your phone's clock and the certificate validity dates.")
            causes.any { it is SSLPeerUnverifiedException } ->
                result("TLS_IDENTITY", "请核对地址与证书上的域名或 IP", "Check that the address matches a host or IP on the certificate.")
            causes.any { it is CertificateException || it is CertPathValidatorException } ->
                result("TLS_CERTIFICATE", "证书链验证失败，请检查服务器证书与 CA", "Certificate verification failed. Check the server certificate and CA.")
            causes.any { it is SSLException } -> tlsFailure(causes, chinese)
            causes.any { it is SocketTimeoutException } ->
                result("NETWORK_TIMEOUT", "连接超时，请检查当前网络与服务器端口", "Connection timed out. Check the network and the server port.")
            causes.any { it is NoRouteToHostException } ->
                result("NETWORK_UNREACHABLE", "当前网络无法访问服务器，请检查 Wi-Fi 或 VPN 分流", "The server is unreachable. Check Wi-Fi and VPN routing.")
            causes.any { it is ConnectException } ->
                result("NETWORK_REFUSED", "连接未建立，请检查地址、端口和服务器状态", "Could not connect. Check the address, port and server status.")
            causes.any { it is UnknownHostException } ->
                result("DNS_LOOKUP", "地址解析失败，请检查服务器地址或 DNS", "Could not resolve the server address. Check the hostname and DNS.")
            error is IllegalStateException && error.message == "请改用普通播放账户" ->
                result("ADMIN_ACCOUNT", "请使用普通播放账号", "Sign in with a playback account.")
            error is IllegalStateException && error.message == "配置已变化，请重新登录" ->
                result("CONFIGURATION_CHANGED", "连接设置已更改，请重新登录", "Connection settings changed. Sign in again.")
            error is IllegalStateException && error.message == "目标不是已初始化的 Jellyfin HTTPS 服务" ->
                result("NOT_JELLYFIN", "无法识别 Jellyfin 服务，请检查地址和端口", "Could not identify the Jellyfin server. Check the address and port.")
            error is IOException && error.message == "NAS 返回格式不正确" ->
                result("RESPONSE_FORMAT", "服务器响应格式有误，请检查地址", "Unexpected server response. Check the server address.")
            error is IllegalStateException && error.message == "NAS 未返回会话" ->
                result("SESSION_MISSING", "登录响应缺少会话信息，请查看最近连接诊断", "The login response has no session. Open the connection report for details.")
            else -> result("UNEXPECTED_${error.javaClass.simpleName}", "请查看最近连接诊断", "Open the last connection report for details.")
        }
    }

    private fun tlsFailure(causes: List<Throwable>, chinese: Boolean): Failure {
        fun result(code: String, zh: String, en: String) = Failure(code, if (chinese) zh else en)
        val messages = causes.mapNotNull { it.message?.lowercase() }
        fun mentions(vararg terms: String) = messages.any { message -> terms.any(message::contains) }
        return when {
            mentions("trust anchor", "unknown ca", "unknown_ca", "certificate verify failed") ->
                result("TLS_CERTIFICATE", "证书链验证失败，请检查服务器证书与 CA", "Certificate verification failed. Check the server certificate and CA.")
            mentions("wrong version number", "unrecognized ssl message", "unexpected record type", "not an ssl/tls record") ->
                result("TLS_NOT_HTTPS", "当前端口未返回有效的 HTTPS，请检查地址或代理", "The port did not return valid HTTPS. Check the address and proxy.")
            mentions("protocol_version", "protocol version", "no_application_protocol") ->
                result("TLS_PROTOCOL", "无法协商 HTTPS 协议，请查看最近连接诊断", "Could not negotiate HTTPS. Open the connection report for details.")
            causes.any { it is EOFException || (it is SocketException && it !is ConnectException) } ||
                mentions("closed by peer", "remote host terminated", "connection reset", "unexpected eof", "end of stream") ->
                result("TLS_INTERRUPTED", "HTTPS 连接中断，请检查手机网络、VPN 或代理", "HTTPS was interrupted. Check your network, VPN and proxy.")
            else -> result("TLS_HANDSHAKE", "HTTPS 握手失败，请查看最近连接诊断", "The HTTPS handshake failed. Open the connection report for details.")
        }
    }

    fun report(
        version: String,
        stage: LoginStage,
        caPresent: Boolean,
        fingerprintPresent: Boolean,
        usernamePresent: Boolean,
        error: Throwable? = null,
        chinese: Boolean = true,
    ): String = buildString {
        fun text(zh: String, en: String) = if (chinese) zh else en
        fun configured(value: Boolean) = if (value) text("已填写", "Set") else text("未填写", "Not set")
        appendLine("Jellyfin Xun $version")
        appendLine(text("阶段 · ${stage.zh}", "Stage · ${stage.en}"))
        appendLine(text("连接 · ", "Connection · ") + if (caPresent) text("直连", "Direct") else text("系统代理设置", "System proxy settings"))
        appendLine(text("私有 CA · ", "Private CA · ") + configured(caPresent))
        appendLine(text("CA 指纹 · ", "CA fingerprint · ") + configured(fingerprintPresent))
        appendLine(text("账号 · ", "Username · ") + configured(usernamePresent))
        val failed = error?.let { failure(stage, it, chinese) }
        append(text("结果 · ", "Result · "))
        append(if (failed == null) text("成功", "Success") else "${failed.code}\n${failed.advice}")
    }
}
