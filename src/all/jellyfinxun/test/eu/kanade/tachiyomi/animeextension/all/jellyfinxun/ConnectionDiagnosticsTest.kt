package eu.kanade.tachiyomi.animeextension.all.jellyfinxun

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class ConnectionDiagnosticsTest {
    @Test
    fun copiedPemWithFlattenedWhitespaceStillRequiresTheExactFingerprint() {
        val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("Synthetic paste test CA").build()
        val fingerprint = NasConnection.fingerprint(ca.certificate)
        val flattened = ca.certificatePem().replace("\n", " ")
        val copiedFingerprint = fingerprint.chunked(16).joinToString(": \n\t").uppercase()
        assertEquals(fingerprint, NasConnection.fingerprint(NasConnection.parseCa(flattened, copiedFingerprint)))
        try {
            NasConnection.parseCa(flattened, "0".repeat(64))
            fail("Mismatched fingerprint accepted")
        } catch (error: NasConfigurationException) {
            assertEquals(ConfigurationIssue.CA_FINGERPRINT_MISMATCH, error.issue)
        }
    }

    @Test
    fun privateKeyAndMultipleCertificatesAreRejectedDuringPasteNormalization() {
        val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("Synthetic paste test CA").build()
        for (invalid in listOf(ca.privateKeyPkcs8Pem(), ca.certificatePem() + ca.certificatePem(), "-----BEGIN CERTIFICATE-----not base64!-----END CERTIFICATE-----")) {
            try {
                NasConnection.normalizePem(invalid)
                fail("Invalid public certificate text accepted")
            } catch (error: NasConfigurationException) {
                assertEquals(ConfigurationIssue.CA_PEM_FORMAT, error.issue)
            }
        }
    }

    @Test
    fun diagnosticNeverIncludesEndpointOrCredentialsFromAnException() {
        val sensitive = "synthetic-test-password-and-token"
        val report = ConnectionDiagnostics.report("14.4", LoginStage.HTTPS, true, true, true, IOException("https://example.invalid:8443/private/$sensitive?token=$sensitive Password=$sensitive Authorization=$sensitive"))
        assertFalse(report.contains(sensitive))
        assertFalse(report.contains("private/"))
        assertFalse(report.contains("token="))
        assertFalse(report.contains("example.invalid"))
        assertTrue(report.contains("UNEXPECTED_IOException"))
    }

    @Test
    fun trustChainHostnameAndNetworkFailuresHaveDifferentCodes() {
        val trust = SSLHandshakeException("not exposed").apply { initCause(CertPathValidatorException("not exposed")) }
        assertEquals("TLS_CERTIFICATE", ConnectionDiagnostics.failure(LoginStage.HTTPS, trust).code)
        assertEquals("TLS_IDENTITY", ConnectionDiagnostics.failure(LoginStage.HTTPS, SSLPeerUnverifiedException("not exposed")).code)
        assertEquals("NETWORK_TIMEOUT", ConnectionDiagnostics.failure(LoginStage.HTTPS, SocketTimeoutException("not exposed")).code)
    }

    @Test
    fun tlsFailureCanIdentifyProtocolOrInterruptedConnectionWithoutExposingProviderText() {
        val wrongEndpoint = SSLHandshakeException("wrong version number near synthetic-private-text")
        val report = ConnectionDiagnostics.report("14.3", LoginStage.HTTPS, true, true, true, wrongEndpoint)
        assertTrue(report.contains("TLS_NOT_HTTPS"))
        assertTrue(report.contains("连接 · 直连"))
        assertFalse(report.contains("synthetic-private-text"))

        val reset = SSLHandshakeException("Connection closed by peer")
        assertEquals("TLS_INTERRUPTED", ConnectionDiagnostics.failure(LoginStage.HTTPS, reset).code)
    }

    @Test
    fun mediaLibraryPermissionFailureIsSeparateFromLoginAuthentication() {
        val login = ConnectionDiagnostics.report("14.2", LoginStage.AUTHENTICATION, true, true, true, NasHttpException(401))
        val library = ConnectionDiagnostics.report("14.2", LoginStage.LIBRARIES, true, true, true, NasHttpException(403))
        assertTrue(login.contains("账号认证"))
        assertTrue(login.contains("HTTP_401"))
        assertTrue(library.contains("媒体库读取"))
        assertTrue(library.contains("有权访问媒体库"))
    }

    @Test
    fun localConfigurationFailureIsIdentifiedBeforeAnyNetworkDiagnosis() {
        val failure = ConnectionDiagnostics.failure(LoginStage.CONFIGURATION, NasConfigurationException(ConfigurationIssue.CA_FINGERPRINT_MISMATCH))
        assertEquals("CA_FINGERPRINT_MISMATCH", failure.code)
        assertTrue(failure.advice.contains("证书与指纹不匹配"))
    }

    @Test
    fun englishReportUsesTheSameDiagnosticCode() {
        val report = ConnectionDiagnostics.report("14.4", LoginStage.HTTPS, false, false, true, SocketTimeoutException(), chinese = false)
        assertTrue(report.contains("Stage · HTTPS and server"))
        assertTrue(report.contains("System proxy settings"))
        assertTrue(report.contains("NETWORK_TIMEOUT"))
    }
}
