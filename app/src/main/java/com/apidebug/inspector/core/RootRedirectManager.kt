package com.apidebug.inspector.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RootRedirectState(
    val active: Boolean = false,
    val proxyPort: Int,
    val appUid: Int,
    val lastOutput: String = "",
    val lastError: String? = null
)

class RootRedirectManager(
    context: Context,
    private val executor: RootCommandExecutor,
    private val proxyPort: Int
) {
    private val appUid = context.applicationInfo.uid
    private val _state = MutableStateFlow(
        RootRedirectState(
            active = false,
            proxyPort = proxyPort,
            appUid = appUid
        )
    )
    val state: StateFlow<RootRedirectState> = _state.asStateFlow()

    fun enable(): Boolean {
        val script = buildEnableScript(appUid, proxyPort)
        val result = executor.execute(script, timeoutSeconds = 15)
        val success = result.exitCode == 0
        _state.value = _state.value.copy(
            active = success,
            lastOutput = result.output,
            lastError = result.error
        )
        return success
    }

    fun disable(): Boolean {
        val script = buildDisableScript()
        val result = executor.execute(script, timeoutSeconds = 15)
        val success = result.exitCode == 0
        _state.value = _state.value.copy(
            active = false,
            lastOutput = result.output,
            lastError = result.error
        )
        return success
    }

    private fun buildEnableScript(uid: Int, port: Int): String = """
        iptables -t nat -N $CHAIN_NAME 2>/dev/null || true
        iptables -t nat -F $CHAIN_NAME
        iptables -t nat -A $CHAIN_NAME -m owner --uid-owner $uid -j RETURN
        iptables -t nat -A $CHAIN_NAME -p tcp --dport 80 -j REDIRECT --to-ports $port
        iptables -t nat -A $CHAIN_NAME -p tcp --dport 443 -j REDIRECT --to-ports $port
        iptables -t nat -C OUTPUT -j $CHAIN_NAME 2>/dev/null || iptables -t nat -A OUTPUT -j $CHAIN_NAME
        ip6tables -t nat -N $CHAIN_NAME 2>/dev/null || true
        ip6tables -t nat -F $CHAIN_NAME
        ip6tables -t nat -A $CHAIN_NAME -m owner --uid-owner $uid -j RETURN
        ip6tables -t nat -A $CHAIN_NAME -p tcp --dport 80 -j REDIRECT --to-ports $port
        ip6tables -t nat -A $CHAIN_NAME -p tcp --dport 443 -j REDIRECT --to-ports $port
        ip6tables -t nat -C OUTPUT -j $CHAIN_NAME 2>/dev/null || ip6tables -t nat -A OUTPUT -j $CHAIN_NAME
    """.trimIndent()

    private fun buildDisableScript(): String = """
        iptables -t nat -D OUTPUT -j $CHAIN_NAME 2>/dev/null || true
        iptables -t nat -F $CHAIN_NAME 2>/dev/null || true
        iptables -t nat -X $CHAIN_NAME 2>/dev/null || true
        ip6tables -t nat -D OUTPUT -j $CHAIN_NAME 2>/dev/null || true
        ip6tables -t nat -F $CHAIN_NAME 2>/dev/null || true
        ip6tables -t nat -X $CHAIN_NAME 2>/dev/null || true
    """.trimIndent()

    companion object {
        private const val CHAIN_NAME = "API_DEBUG_INSPECTOR"
    }
}
