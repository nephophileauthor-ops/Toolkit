package com.apidebug.inspector.nativebridge

import android.os.ParcelFileDescriptor

data class Tun2SocksConfig(
    val mtu: Int,
    val socks5Address: String,
    val httpProxyAddress: String = "",
    val udpEnabled: Boolean = true,
    val dnsResolver: String = "1.1.1.1:53"
)

class Tun2SocksBridge {
    private var socketProtector: (Int) -> Boolean = { false }

    private val nativeLoaded: Boolean by lazy {
        loadNativeLibraries()
    }
    private val realBackendLoaded: Boolean by lazy {
        nativeLoaded && runCatching { nativeHasRealBackend() }.getOrDefault(false)
    }

    fun isNativeAvailable(): Boolean = nativeLoaded

    fun isRealBackendAvailable(): Boolean = realBackendLoaded

    fun isStubFallbackLoaded(): Boolean = nativeLoaded && !realBackendLoaded

    fun start(tunInterface: ParcelFileDescriptor, config: Tun2SocksConfig): Boolean {
        if (!nativeLoaded) return false
        val duplicated = tunInterface.dup()
        return nativeStart(
            tunFd = duplicated.detachFd(),
            mtu = config.mtu,
            socks5Address = config.socks5Address,
            httpProxyAddress = config.httpProxyAddress,
            udpEnabled = config.udpEnabled,
            dnsResolver = config.dnsResolver
        )
    }

    fun stop() {
        if (nativeLoaded) {
            nativeStop()
        }
    }

    fun updateSocketProtector(protector: (Int) -> Boolean) {
        socketProtector = protector
    }

    @Suppress("unused")
    fun protectSocket(socketFd: Int): Boolean = socketProtector(socketFd)

    private external fun nativeStart(
        tunFd: Int,
        mtu: Int,
        socks5Address: String,
        httpProxyAddress: String,
        udpEnabled: Boolean,
        dnsResolver: String
    ): Boolean

    private external fun nativeStop()

    private external fun nativeHasRealBackend(): Boolean

    companion object {
        private const val GO_LIB_NAME = "tunbridge"
        private const val JNI_LIB_NAME = "tun2socks_bridge"

        private fun loadNativeLibraries(): Boolean {
            // When the real Go bridge is present, load it first so the JNI wrapper resolves
            // its native dependencies immediately. If the APK was built against the local
            // stub fallback, `tunbridge` may be absent and `tun2socks_bridge` can still load.
            runCatching { System.loadLibrary(GO_LIB_NAME) }

            return runCatching {
                System.loadLibrary(JNI_LIB_NAME)
                true
            }.getOrDefault(false)
        }
    }
}
