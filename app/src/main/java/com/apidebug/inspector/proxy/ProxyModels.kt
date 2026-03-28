package com.apidebug.inspector.proxy

data class LocalProxyState(
    val active: Boolean = false,
    val port: Int = DEFAULT_PROXY_PORT,
    val tlsMitmEnabled: Boolean = true,
    val interceptedRequests: Long = 0,
    val lastRequestLine: String = "",
    val lastError: String? = null
) {
    companion object {
        const val DEFAULT_PROXY_PORT = 8877
    }
}
