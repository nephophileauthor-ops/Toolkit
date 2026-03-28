package com.apidebug.inspector.tls

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.tls.HeldCertificate
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class DeveloperCertificateAuthority(
    context: Context
) {
    private val appContext = context.applicationContext
    private val secureBlobStore = SecureBlobStore(appContext)
    private val exportsDir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "certificates").apply {
        mkdirs()
    }
    private val _state = MutableStateFlow(readCurrentState())
    val state: StateFlow<DeveloperCaState> = _state.asStateFlow()

    suspend fun ensureCertificateAuthority(): DeveloperCaState = withContext(Dispatchers.IO) {
        val heldCertificate = loadOrCreateCertificateAuthority()
        _state.value = state.value.copy(
            ready = true,
            commonName = heldCertificate.certificate.subjectX500Principal.name,
            lastError = null
        )
        _state.value
    }

    suspend fun exportCertificateForInstall(): File = withContext(Dispatchers.IO) {
        val certificateAuthority = loadOrCreateCertificateAuthority()
        val file = File(exportsDir, "api_debug_inspector_root_ca.pem")
        file.writeText(certificateAuthority.certificatePem())
        _state.value = state.value.copy(
            ready = true,
            certificatePath = file.absolutePath,
            commonName = certificateAuthority.certificate.subjectX500Principal.name,
            lastError = null
        )
        file
    }

    suspend fun createServerSslContext(hostname: String): SSLContext = withContext(Dispatchers.IO) {
        val certificateAuthority = loadOrCreateCertificateAuthority()
        val leaf = HeldCertificate.Builder()
            .commonName(hostname)
            .addSubjectAlternativeName(hostname)
            .signedBy(certificateAuthority)
            .build()

        val password = "changeit".toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        keyStore.setKeyEntry(
            "proxy",
            leaf.keyPair.private,
            password,
            arrayOf(leaf.certificate, certificateAuthority.certificate)
        )

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, null, SecureRandom())

        _state.value = state.value.copy(
            ready = true,
            commonName = certificateAuthority.certificate.subjectX500Principal.name,
            lastIssuedHost = hostname,
            lastError = null
        )
        sslContext
    }

    private fun readCurrentState(): DeveloperCaState {
        val persisted = secureBlobStore.read(CERTIFICATE_STORE_FILE)
        return if (persisted == null) {
            DeveloperCaState()
        } else {
            val certificate = HeldCertificate.decode(persisted)
            DeveloperCaState(
                ready = true,
                commonName = certificate.certificate.subjectX500Principal.name
            )
        }
    }

    private fun loadOrCreateCertificateAuthority(): HeldCertificate {
        secureBlobStore.read(CERTIFICATE_STORE_FILE)?.let { return HeldCertificate.decode(it) }

        val rootCa = HeldCertificate.Builder()
            .commonName("API Debug Inspector Root CA")
            .organizationalUnit("Local API Debugging")
            .certificateAuthority(0)
            .build()

        secureBlobStore.write(
            fileName = CERTIFICATE_STORE_FILE,
            plainText = rootCa.certificatePem() + rootCa.privateKeyPkcs8Pem()
        )
        return rootCa
    }

    companion object {
        private const val CERTIFICATE_STORE_FILE = "developer_ca.enc"
    }
}
