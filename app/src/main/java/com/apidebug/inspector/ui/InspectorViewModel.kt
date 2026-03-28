package com.apidebug.inspector.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apidebug.inspector.AppContainer
import com.apidebug.inspector.capture.VpnRoutingMode
import com.apidebug.inspector.capture.VpnCaptureState
import com.apidebug.inspector.data.SettingsRepository
import com.apidebug.inspector.core.RootCommandExecutor
import com.apidebug.inspector.core.RootRedirectState
import com.apidebug.inspector.models.RequestDraft
import com.apidebug.inspector.models.RequestExecutionResult
import com.apidebug.inspector.models.RuleActionType
import com.apidebug.inspector.models.RuleDefinition
import com.apidebug.inspector.models.TrafficOutcome
import com.apidebug.inspector.models.TrafficRecord
import com.apidebug.inspector.proxy.LocalProxyState
import com.apidebug.inspector.service.CaptureSessionService
import com.apidebug.inspector.tls.DeveloperCaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

enum class RootAvailability {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE
}

sealed interface InspectorUiAction {
    data object RequestVpnPermission : InspectorUiAction
}

data class RequestBreakpointState(
    val draft: RequestDraft,
    val pauseAfterResponse: Boolean
)

data class ResponseBreakpointState(
    val finalUrl: String,
    val finalMethod: String,
    val finalHeadersText: String,
    val finalBodyText: String,
    val responseStatusText: String,
    val responseHeadersText: String,
    val responseBodyText: String,
    val durationMs: Long,
    val outcome: TrafficOutcome,
    val matchedRules: List<String>,
    val errorMessage: String?
) {
    fun toExecutionResult(): RequestExecutionResult = RequestExecutionResult(
        finalUrl = finalUrl,
        finalMethod = finalMethod,
        finalHeadersText = finalHeadersText,
        finalBodyText = finalBodyText,
        responseStatus = responseStatusText.toIntOrNull(),
        responseHeadersText = responseHeadersText,
        responseBodyText = responseBodyText,
        durationMs = durationMs,
        outcome = if (responseStatusText.toIntOrNull() != null) TrafficOutcome.SUCCESS else outcome,
        matchedRules = matchedRules,
        errorMessage = errorMessage
    )

    fun contentType(): String {
        return responseHeadersText.lines()
            .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
    }

    companion object {
        fun from(result: RequestExecutionResult): ResponseBreakpointState = ResponseBreakpointState(
            finalUrl = result.finalUrl,
            finalMethod = result.finalMethod,
            finalHeadersText = result.finalHeadersText,
            finalBodyText = result.finalBodyText,
            responseStatusText = result.responseStatus?.toString().orEmpty(),
            responseHeadersText = result.responseHeadersText,
            responseBodyText = result.responseBodyText,
            durationMs = result.durationMs,
            outcome = result.outcome,
            matchedRules = result.matchedRules,
            errorMessage = result.errorMessage
        )
    }
}

class InspectorViewModel(
    application: Application,
    private val container: AppContainer
) : AndroidViewModel(application) {
    private val rootCommandExecutor = RootCommandExecutor()
    private val settingsRepository: SettingsRepository = container.settingsRepository

    val settings = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = settingsRepository.settings.value
    )
    val rules = container.ruleRepository.rules.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = container.ruleRepository.snapshot()
    )
    val records = container.trafficRepository.records.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = container.trafficRepository.snapshot()
    )
    val proxyState = container.localDebugProxyServer.state
    val vpnState = container.vpnCaptureController.state
    val developerCaState = container.developerCertificateAuthority.state
    val rootRedirectState = container.rootRedirectManager.state

    private val _sendDraft = MutableStateFlow(RequestDraft())
    val sendDraft: StateFlow<RequestDraft> = _sendDraft.asStateFlow()

    private val _lastExecution = MutableStateFlow<RequestExecutionResult?>(null)
    val lastExecution: StateFlow<RequestExecutionResult?> = _lastExecution.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _requestBreakpoint = MutableStateFlow<RequestBreakpointState?>(null)
    val requestBreakpoint: StateFlow<RequestBreakpointState?> = _requestBreakpoint.asStateFlow()

    private val _responseBreakpoint = MutableStateFlow<ResponseBreakpointState?>(null)
    val responseBreakpoint: StateFlow<ResponseBreakpointState?> = _responseBreakpoint.asStateFlow()

    private val _rootAvailability = MutableStateFlow(RootAvailability.CHECKING)
    val rootAvailability: StateFlow<RootAvailability> = _rootAvailability.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages = _messages.asSharedFlow()

    private val _uiActions = MutableSharedFlow<InspectorUiAction>(extraBufferCapacity = 4)
    val uiActions = _uiActions.asSharedFlow()

    init {
        refreshRootAvailability()
    }

    fun updateDraftUrl(value: String) {
        _sendDraft.value = _sendDraft.value.copy(url = value)
    }

    fun updateDraftMethod(value: String) {
        _sendDraft.value = _sendDraft.value.copy(method = value)
    }

    fun updateDraftHeaders(value: String) {
        _sendDraft.value = _sendDraft.value.copy(headersText = value)
    }

    fun updateDraftBody(value: String) {
        _sendDraft.value = _sendDraft.value.copy(bodyText = value)
    }

    fun clearDraft() {
        _sendDraft.value = RequestDraft()
        _lastExecution.value = null
    }

    fun executeRequest(
        pauseBeforeSend: Boolean = false,
        pauseAfterResponse: Boolean = false
    ) {
        if (_isExecuting.value) return
        if (pauseBeforeSend) {
            _requestBreakpoint.value = RequestBreakpointState(
                draft = _sendDraft.value,
                pauseAfterResponse = pauseAfterResponse
            )
            _messages.tryEmit("Request paused at breakpoint")
            return
        }

        performRequest(_sendDraft.value, pauseAfterResponse)
    }

    fun resumeRequestBreakpoint(
        editedDraft: RequestDraft,
        pauseAfterResponse: Boolean
    ) {
        _requestBreakpoint.value = null
        _sendDraft.value = editedDraft
        performRequest(editedDraft, pauseAfterResponse)
    }

    fun cancelRequestBreakpoint() {
        _requestBreakpoint.value = null
        _messages.tryEmit("Request breakpoint dismissed")
    }

    fun commitResponseBreakpoint(editedState: ResponseBreakpointState) {
        viewModelScope.launch {
            val result = editedState.toExecutionResult()
            container.requestEngine.persistExecutionResult(
                result = result,
                source = "send",
                contentType = editedState.contentType()
            )
            _lastExecution.value = result
            _responseBreakpoint.value = null
            _messages.emit("Response committed from breakpoint")
        }
    }

    fun discardResponseBreakpoint() {
        _responseBreakpoint.value = null
        _messages.tryEmit("Response breakpoint dismissed without saving")
    }

    fun setDarkTheme(enabled: Boolean) {
        settingsRepository.setDarkTheme(enabled)
    }

    fun setRequestTimeout(timeoutMs: Int) {
        settingsRepository.setRequestTimeout(timeoutMs.coerceIn(5_000, 120_000))
    }

    fun toggleSession(active: Boolean) {
        settingsRepository.setSessionActive(active)
        if (active) {
            CaptureSessionService.start(getApplication())
            _messages.tryEmit("Capture session armed")
        } else {
            CaptureSessionService.stop(getApplication())
            _messages.tryEmit("Capture session stopped")
        }
    }

    fun refreshRootAvailability() {
        viewModelScope.launch(Dispatchers.IO) {
            _rootAvailability.value = RootAvailability.CHECKING
            _rootAvailability.value = if (rootCommandExecutor.isRootAvailable()) {
                RootAvailability.AVAILABLE
            } else {
                RootAvailability.UNAVAILABLE
            }
        }
    }

    fun saveRule(rule: RuleDefinition) {
        viewModelScope.launch {
            container.ruleRepository.upsert(rule.normalize())
            _messages.emit("Rule saved")
        }
    }

    fun deleteRule(ruleId: Long) {
        if (ruleId == 0L) return

        viewModelScope.launch {
            container.ruleRepository.delete(ruleId)
            _messages.emit("Rule deleted")
        }
    }

    fun clearTraffic() {
        viewModelScope.launch {
            container.trafficRepository.clear()
            _messages.emit("Traffic history cleared")
        }
    }

    fun exportHar() {
        viewModelScope.launch {
            val file = container.sessionExporter.exportHar()
            markLastExport(file)
            _messages.emit("HAR exported to ${file.absolutePath}")
        }
    }

    fun exportRules() {
        viewModelScope.launch {
            val file = container.sessionExporter.exportRules()
            markLastExport(file)
            _messages.emit("Rules exported to ${file.absolutePath}")
        }
    }

    fun ensureDeveloperCertificateAuthority() {
        viewModelScope.launch {
            val state = container.developerCertificateAuthority.ensureCertificateAuthority()
            _messages.emit("Developer CA ready: ${state.commonName}")
        }
    }

    fun exportDeveloperCertificateAuthority() {
        viewModelScope.launch {
            val file = container.developerCertificateAuthority.exportCertificateForInstall()
            _messages.emit("Developer CA exported to ${file.absolutePath}")
        }
    }

    fun toggleLocalProxy(active: Boolean) {
        viewModelScope.launch {
            if (active) {
                container.localDebugProxyServer.start()
                _messages.emit("Local proxy listening on 127.0.0.1:${container.localDebugProxyServer.state.value.port}")
            } else {
                container.localDebugProxyServer.stop()
                _messages.emit("Local proxy stopped")
            }
        }
    }

    fun setVpnRoutingMode(mode: VpnRoutingMode) {
        container.vpnCaptureController.setRoutingMode(mode)
        val message = when (mode) {
            VpnRoutingMode.OBSERVE_ONLY -> "VPN mode set to observe-only packet intake"
            VpnRoutingMode.HTTP_PROXY -> "VPN mode set to proxy-aware capture for apps that honor the system HTTP proxy"
            VpnRoutingMode.NATIVE_TUN2SOCKS -> "VPN mode set to native tun2socks bridge for transparent TCP forwarding"
        }
        _messages.tryEmit(
            if (vpnState.value.active) {
                "$message. Restart VPN capture to apply the new path."
            } else {
                message
            }
        )
    }

    fun toggleVpnCapture(active: Boolean) {
        if (active) {
            viewModelScope.launch {
                val routingMode = settings.value.vpnRoutingMode
                if (routingMode != VpnRoutingMode.OBSERVE_ONLY && !container.localDebugProxyServer.state.value.active) {
                    container.localDebugProxyServer.start()
                    _messages.emit("Local proxy started for ${routingMode.name.lowercase()} mode")
                }
                if (vpnState.value.prepared) {
                    container.vpnCaptureController.startService()
                    _messages.emit("Starting VPN capture")
                } else {
                    _uiActions.emit(InspectorUiAction.RequestVpnPermission)
                }
            }
        } else {
            container.vpnCaptureController.stopService()
            _messages.tryEmit("Stopping VPN capture")
        }
    }

    fun toggleRootRedirect(active: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (active) {
                if (!container.localDebugProxyServer.state.value.active) {
                    container.localDebugProxyServer.start()
                }
                val success = container.rootRedirectManager.enable()
                _messages.emit(
                    if (success) {
                        "Root redirect enabled to local proxy"
                    } else {
                        "Root redirect failed: ${container.rootRedirectManager.state.value.lastError ?: "unknown error"}"
                    }
                )
            } else {
                val success = container.rootRedirectManager.disable()
                _messages.emit(
                    if (success) {
                        "Root redirect disabled"
                    } else {
                        "Root redirect cleanup finished with warnings"
                    }
                )
            }
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        container.vpnCaptureController.markPrepared(granted)
        if (granted) {
            container.vpnCaptureController.startService()
            _messages.tryEmit("VPN permission granted")
        } else {
            _messages.tryEmit("VPN permission denied")
        }
    }

    fun importHar(uri: Uri) {
        viewModelScope.launch {
            val count = container.sessionExporter.importHar(uri)
            _messages.emit("Imported $count HAR entries")
        }
    }

    fun loadRecordIntoComposer(record: TrafficRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            val requestBody = container.trafficRepository.loadBodyText(record.requestBodyPath)
                .ifBlank { record.requestBodyPreview }
            _sendDraft.value = RequestDraft(
                url = record.requestUrl,
                method = record.requestMethod,
                headersText = record.requestHeaders,
                bodyText = requestBody
            )
            _messages.emit("Request loaded into Send tab")
        }
    }

    suspend fun loadBodyText(path: String?): String = withContext(Dispatchers.IO) {
        container.trafficRepository.loadBodyText(path)
    }

    fun buildMockRuleSeed(
        record: TrafficRecord,
        requestBody: String,
        responseBody: String
    ): RuleDefinition {
        val parsedUrl = runCatching { URL(record.requestUrl) }.getOrNull()
        val suggestedPath = parsedUrl?.path.orEmpty().ifBlank { "/" }
        val suggestedName = buildString {
            append("Mock ")
            append(parsedUrl?.host ?: "captured-endpoint")
            append(suggestedPath)
        }.take(72)

        return RuleDefinition(
            name = suggestedName,
            hostContains = parsedUrl?.host.orEmpty(),
            pathContains = suggestedPath,
            method = record.requestMethod,
            bodyContains = requestBody.take(80),
            actionType = RuleActionType.MOCK_RESPONSE,
            mockStatus = record.responseStatus ?: 200,
            mockHeadersText = record.responseHeaders.ifBlank {
                "Content-Type: ${record.contentType.ifBlank { "application/json" }}"
            },
            mockBodyText = responseBody.ifBlank { "{\n  \"mock\": true\n}" }
        )
    }

    private fun markLastExport(file: File) {
        settingsRepository.setLastExportPath(file.absolutePath)
    }

    private fun performRequest(
        draft: RequestDraft,
        pauseAfterResponse: Boolean
    ) {
        viewModelScope.launch {
            _isExecuting.value = true
            try {
                val result = container.requestEngine.execute(
                    draft = draft,
                    timeoutMs = settings.value.requestTimeoutMs,
                    persistTraffic = !pauseAfterResponse
                )
                if (pauseAfterResponse) {
                    _responseBreakpoint.value = ResponseBreakpointState.from(result)
                    _messages.emit("Response paused at breakpoint")
                } else {
                    _lastExecution.value = result
                    _messages.emit(
                        when (result.responseStatus) {
                            null -> "${result.finalMethod} ${result.outcome.name.lowercase()} recorded"
                            else -> "${result.finalMethod} ${result.responseStatus} captured"
                        }
                    )
                }
            } catch (e: Exception) {
                _messages.emit("Request failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _isExecuting.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        container.rootRedirectManager.disable()
        container.localDebugProxyServer.close()
        container.vpnCaptureController.close()
    }

    companion object {
        fun factory(
            application: Application,
            container: AppContainer
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(InspectorViewModel::class.java))
                return InspectorViewModel(application, container) as T
            }
        }
    }
}

private fun RuleDefinition.normalize(): RuleDefinition {
    return copy(
        name = name.ifBlank { "New rule" },
        hostContains = hostContains.trim(),
        pathContains = pathContains.trim(),
        method = method.trim().ifBlank { "ANY" },
        headerName = headerName.trim(),
        headerValueContains = headerValueContains.trim(),
        bodyContains = bodyContains.trim(),
        rewriteUrl = rewriteUrl.trim(),
        rewriteMethod = rewriteMethod.trim(),
        rewriteHeadersText = rewriteHeadersText.trim(),
        rewriteBodyText = rewriteBodyText,
        mockHeadersText = mockHeadersText.trim(),
        mockBodyText = mockBodyText,
        delayMs = delayMs.coerceAtLeast(0L)
    )
}
