package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

internal enum class LoginStage(val label: String) {
    CONFIGURATION("本地配置与 CA 指纹"),
    HTTPS("HTTPS 连接与服务器识别"),
    AUTHENTICATION("播放账户认证"),
    LIBRARIES("媒体库读取"),
}

internal enum class ConfigurationIssue(val advice: String) {
    URL_FORMAT("服务器地址必须是 https://主机:端口"),
    URL_SENSITIVE("服务器地址不能带账号、密码、查询参数或页面片段"),
    CA_PAIR("私有 CA 公钥证书与 SHA-256 指纹需要同时填写"),
    CA_PEM_FORMAT("CA 公钥证书无法读取，请重新复制完整 BEGIN CERTIFICATE 到 END CERTIFICATE"),
    CA_FINGERPRINT_FORMAT("CA 指纹必须包含完整的 64 个十六进制数字"),
    CA_NOT_CA("导入的不是 CA 公钥证书"),
    CA_USAGE("CA 证书不能用于验证服务器签名"),
    CA_FINGERPRINT_MISMATCH("CA 证书与指纹不匹配，请分别从 NAS 可信文件重新复制"),
    USERNAME_EMPTY("请先填写播放账户，例如 watcher"),
}

internal class NasConfigurationException(val issue: ConfigurationIssue) : IllegalArgumentException(issue.advice)
internal class NasHttpException(val status: Int) : IOException("NAS 请求返回 HTTP $status")

/** Diagnostics never render exception messages, response bodies, credentials or URLs with user info. */
internal object ConnectionDiagnostics {
    data class Failure(val code: String, val advice: String)

    fun failure(stage: LoginStage, error: Throwable): Failure {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = generateSequence(error) { it.cause }.takeWhile(seen::add).take(12).toList()
        causes.filterIsInstance<NasConfigurationException>().firstOrNull()?.let { return Failure(it.issue.name, it.issue.advice) }
        causes.filterIsInstance<NasHttpException>().firstOrNull()?.let {
            val advice = when (it.status) {
                401 -> "账户或密码未通过认证，请核对 watcher 的密码"
                403 -> if (stage == LoginStage.AUTHENTICATION) "服务器拒绝此账户登录，请检查是否误用了管理员账户或账户被停用" else "登录已通过，但服务器不允许读取这部分内容"
                404 -> "接口不存在，请检查服务器地址；通常只填 https://IP:端口，不带 /web 页面地址"
                else -> "服务器返回 HTTP ${it.status}，请保留此状态码排查"
            }
            return Failure("HTTP_${it.status}", advice)
        }
        return when {
            causes.any { it is CertificateExpiredException || it is CertificateNotYetValidException } -> Failure("CERTIFICATE_TIME", "证书不在有效期内，请核对手机日期时间和证书有效期")
            causes.any { it is SSLPeerUnverifiedException } -> Failure("TLS_IDENTITY", "服务器证书与当前 IP 或主机名不匹配，请核对地址和服务器证书")
            causes.any { it is CertificateException || it is CertPathValidatorException } -> Failure("TLS_CERTIFICATE", "服务器证书链验证失败，请核对 CA、指纹及手机时间")
            causes.any { it is SSLException } -> tlsFailure(causes)
            causes.any { it is SocketTimeoutException } -> Failure("NETWORK_TIMEOUT", "连接或读取超时；家里先测试 NAS 内网 HTTPS 地址，外网再检查端口转发")
            causes.any { it is NoRouteToHostException } -> Failure("NETWORK_UNREACHABLE", "手机当前网络无法到达这个地址，请检查 Wi-Fi 隔离或 VPN 分流")
            causes.any { it is ConnectException } -> Failure("NETWORK_REFUSED", "连接未建立；请检查 IP、端口、Wi-Fi 隔离或端口转发")
            causes.any { it is UnknownHostException } -> Failure("DNS_LOOKUP", "地址解析失败，请检查服务器地址或 DNS")
            error is IllegalStateException && error.message == "请改用普通播放账户" -> Failure("ADMIN_ACCOUNT", "请使用 watcher 等普通播放账户")
            error is IllegalStateException && error.message == "配置已变化，请重新登录" -> Failure("CONFIGURATION_CHANGED", "连接过程中设置发生变化，请重新登录")
            error is IllegalStateException && error.message == "目标不是已初始化的 Jellyfin HTTPS 服务" -> Failure("NOT_JELLYFIN", "该地址没有返回已初始化的 Jellyfin 服务，请检查地址与端口")
            error is IOException && error.message == "NAS 返回格式不正确" -> Failure("RESPONSE_FORMAT", "接口没有返回预期 JSON，请检查服务器地址是否带了网页路径")
            error is IllegalStateException && error.message == "NAS 未返回会话" -> Failure("SESSION_MISSING", "登录响应没有返回会话，请复制最近连接诊断排查接口兼容性")
            else -> Failure("UNEXPECTED_${error.javaClass.simpleName}", "请复制最近连接诊断；诊断不会包含密码、会话或服务器响应正文")
        }
    }

    private fun tlsFailure(causes: List<Throwable>): Failure {
        // Match known TLS symptoms without displaying provider messages, which may include input.
        val messages = causes.mapNotNull { it.message?.lowercase() }
        fun mentions(vararg terms: String) = messages.any { message -> terms.any(message::contains) }
        return when {
            mentions("trust anchor", "unknown ca", "unknown_ca", "certificate verify failed") ->
                Failure("TLS_CERTIFICATE", "服务器证书链验证失败，请核对 CA、指纹及手机时间")
            mentions("wrong version number", "unrecognized ssl message", "unexpected record type", "not an ssl/tls record") ->
                Failure("TLS_NOT_HTTPS", "当前连接返回的内容无法作为 HTTPS 解析，请检查地址、端口或网络代理")
            mentions("protocol_version", "protocol version", "no_application_protocol") ->
                Failure("TLS_PROTOCOL", "客户端和服务器未能协商 HTTPS 协议，请复制诊断供排查")
            causes.any { it is EOFException || (it is SocketException && it !is ConnectException) } ||
                mentions("closed by peer", "remote host terminated", "connection reset", "unexpected eof", "end of stream") ->
                Failure("TLS_INTERRUPTED", "HTTPS 握手时连接中断，请检查手机网络、VPN 或代理分流")
            else -> Failure("TLS_HANDSHAKE", "HTTPS 握手失败，请复制最近连接诊断供排查")
        }
    }

    fun safeTarget(server: String): String {
        val url = server.trim().toHttpUrlOrNull() ?: return "地址格式不正确"
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "含账号信息的地址已隐藏"
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return "${url.scheme}://$host:${url.port}"
    }

    fun report(version: String, stage: LoginStage, server: String, caPresent: Boolean, fingerprintPresent: Boolean, usernamePresent: Boolean, error: Throwable? = null, success: Boolean = false): String = buildString {
        append("Jellyfin Xun $version\n")
        append("阶段：${stage.label}\n")
        append("目标：${safeTarget(server)}\n")
        append("连接方式：${if (caPresent) "NAS 直连" else "沿用系统代理设置"}\n")
        append("CA：${if (caPresent) "已填写" else "未填写（使用系统信任）"}\n")
        append("CA 指纹：${if (fingerprintPresent) "已填写" else "未填写"}\n")
        append("播放账户：${if (usernamePresent) "已填写" else "未填写"}\n")
        when {
            error != null -> {
                val failed = failure(stage, error)
                append("结果：${failed.code}\n${failed.advice}")
            }
            success -> append("结果：成功")
            else -> append("结果：正在检查")
        }
    }
}
