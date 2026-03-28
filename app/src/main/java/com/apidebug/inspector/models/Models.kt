package com.apidebug.inspector.models

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import com.apidebug.inspector.capture.VpnRoutingMode

enum class InspectorTab(val title: String, val icon: ImageVector) {
    Capture("Capture", Icons.Rounded.Storage),
    Inspect("Inspect", Icons.Rounded.Visibility),
    Modify("Modify", Icons.Rounded.Tune),
    Send("Send", Icons.Rounded.Send),
    Settings("Settings", Icons.Rounded.Settings)
}

data class AppSettings(
    val darkTheme: Boolean = true,
    val sessionActive: Boolean = false,
    val requestTimeoutMs: Int = 20_000,
    val lastExportPath: String = "",
    val vpnRoutingMode: VpnRoutingMode = VpnRoutingMode.OBSERVE_ONLY
)

data class RequestDraft(
    val url: String = "https://httpbin.org/get",
    val method: String = "GET",
    val headersText: String = "Accept: application/json",
    val bodyText: String = ""
)

enum class TrafficOutcome {
    SUCCESS,
    MOCKED,
    BLOCKED,
    FAILED
}

data class TrafficRecordDraft(
    val timestamp: Long = System.currentTimeMillis(),
    val requestUrl: String,
    val requestMethod: String,
    val requestHeaders: String,
    val requestBody: String,
    val responseStatus: Int? = null,
    val responseHeaders: String = "",
    val responseBody: String = "",
    val durationMs: Long = 0L,
    val outcome: TrafficOutcome,
    val errorMessage: String? = null,
    val matchedRules: List<String> = emptyList(),
    val source: String = "send",
    val contentType: String = ""
)

data class TrafficRecord(
    val id: Long,
    val timestamp: Long,
    val requestUrl: String,
    val requestMethod: String,
    val requestHeaders: String,
    val requestBodyPreview: String,
    val requestBodyPath: String?,
    val responseStatus: Int?,
    val responseHeaders: String,
    val responseBodyPreview: String,
    val responseBodyPath: String?,
    val durationMs: Long,
    val outcome: TrafficOutcome,
    val errorMessage: String?,
    val matchedRules: List<String>,
    val source: String,
    val contentType: String
)

enum class RuleActionType {
    REWRITE_REQUEST,
    MOCK_RESPONSE,
    BLOCK_REQUEST,
    DELAY_REQUEST
}

data class RuleDefinition(
    val id: Long = 0L,
    val name: String = "New rule",
    val enabled: Boolean = true,
    val hostContains: String = "",
    val pathContains: String = "",
    val method: String = "ANY",
    val headerName: String = "",
    val headerValueContains: String = "",
    val bodyContains: String = "",
    val actionType: RuleActionType = RuleActionType.REWRITE_REQUEST,
    val rewriteUrl: String = "",
    val rewriteMethod: String = "",
    val rewriteHeadersText: String = "",
    val rewriteBodyText: String = "",
    val mockStatus: Int = 200,
    val mockHeadersText: String = "Content-Type: application/json",
    val mockBodyText: String = "{\n  \"ok\": true\n}",
    val delayMs: Long = 0L,
    val stopOnMatch: Boolean = true
)

data class RuleSetV1(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val rules: List<RuleDefinition>
)

data class RequestExecutionResult(
    val finalUrl: String,
    val finalMethod: String,
    val finalHeadersText: String,
    val finalBodyText: String,
    val responseStatus: Int?,
    val responseHeadersText: String,
    val responseBodyText: String,
    val durationMs: Long,
    val outcome: TrafficOutcome,
    val matchedRules: List<String>,
    val errorMessage: String? = null
)
