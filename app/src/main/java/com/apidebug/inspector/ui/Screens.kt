package com.apidebug.inspector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apidebug.inspector.capture.VpnRoutingMode
import com.apidebug.inspector.core.RootRedirectState
import com.apidebug.inspector.models.AppSettings
import com.apidebug.inspector.models.RequestDraft
import com.apidebug.inspector.models.RequestExecutionResult
import com.apidebug.inspector.models.RuleActionType
import com.apidebug.inspector.models.RuleDefinition
import com.apidebug.inspector.models.TrafficRecord
import com.apidebug.inspector.proxy.LocalProxyState
import com.apidebug.inspector.tls.DeveloperCaState

@Composable
fun CaptureScreen(
    settings: AppSettings,
    rootAvailability: RootAvailability,
    proxyState: LocalProxyState,
    vpnState: com.apidebug.inspector.capture.VpnCaptureState,
    developerCaState: DeveloperCaState,
    rootRedirectState: RootRedirectState,
    recordCount: Int,
    ruleCount: Int,
    recentRecords: List<TrafficRecord>,
    onToggleSession: (Boolean) -> Unit,
    onRefreshRoot: () -> Unit,
    onExportHar: () -> Unit,
    onImportHar: () -> Unit,
    onExportRules: () -> Unit,
    onClearTraffic: () -> Unit,
    onToggleProxy: (Boolean) -> Unit,
    onEnsureDeveloperCa: () -> Unit,
    onExportDeveloperCa: () -> Unit,
    onToggleVpnCapture: (Boolean) -> Unit,
    onSetVpnRoutingMode: (VpnRoutingMode) -> Unit,
    onToggleRootRedirect: (Boolean) -> Unit,
    onOpenRecord: (TrafficRecord) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(
            title = "Android Capture Cockpit",
            subtitle = "HAR workflows, history management, and session control."
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.fillMaxWidth(0.82f)) {
                    Text(
                        text = if (settings.sessionActive) "Capture session armed" else "Capture session idle",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Use this as the operator hub while device-wide VPN/MITM capture lands next.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(checked = settings.sessionActive, onCheckedChange = onToggleSession)
            }

            Spacer(modifier = Modifier.height(12.dp))
            StatusTag(
                text = when (rootAvailability) {
                    RootAvailability.CHECKING -> "Root: checking"
                    RootAvailability.AVAILABLE -> "Root: available"
                    RootAvailability.UNAVAILABLE -> "Root: unavailable"
                },
                tone = toneForRoot(rootAvailability)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExportHar) { Text("Export HAR") }
                OutlinedButton(onClick = onImportHar) { Text("Import HAR") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportRules) { Text("Export rules") }
                OutlinedButton(onClick = onRefreshRoot) { Text("Re-check root") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onClearTraffic) { Text("Clear history") }
        }

        SectionCard(
            title = "Quick Stats",
            subtitle = "Fast context while debugging."
        ) {
            MetricLine("Traffic records", recordCount.toString())
            MetricLine("Rule definitions", ruleCount.toString())
            MetricLine("Last export path", settings.lastExportPath.ifBlank { "No export yet" })
        }

        SectionCard(
            title = "Interception Stack",
            subtitle = "Developer CA, local proxy, and VPN capture foundation."
        ) {
            MetricLine("Developer CA", if (developerCaState.ready) "Ready" else "Not generated")
            MetricLine("Proxy", if (proxyState.active) "127.0.0.1:${proxyState.port}" else "Stopped")
            MetricLine("VPN capture", if (vpnState.active) "Active" else "Stopped")
            MetricLine("Requested mode", vpnModeLabel(settings.vpnRoutingMode))
            MetricLine("Effective mode", vpnModeLabel(vpnState.effectiveRoutingMode))
            MetricLine("Native bridge", nativeBridgeLabel(vpnState))
            MetricLine("Root redirect", if (rootRedirectState.active) "iptables redirect active" else "Inactive")
            if (developerCaState.certificatePath.isNotBlank()) {
                MetricLine("CA export", developerCaState.certificatePath)
            }
            if (vpnState.lastPacketSummary.isNotBlank()) {
                MetricLine("Last packet", vpnState.lastPacketSummary)
                MetricLine("Route decision", vpnState.lastRouteDecision)
            }
            if (vpnState.lastError != null) {
                MetricLine("VPN note", vpnState.lastError)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VpnRoutingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.vpnRoutingMode == mode,
                        onClick = { onSetVpnRoutingMode(mode) },
                        label = { Text(vpnModeShortLabel(mode)) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEnsureDeveloperCa) { Text("Generate CA") }
                OutlinedButton(onClick = onExportDeveloperCa) { Text("Export CA") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onToggleProxy(!proxyState.active) }) {
                    Text(if (proxyState.active) "Stop proxy" else "Start proxy")
                }
                OutlinedButton(onClick = { onToggleVpnCapture(!vpnState.active) }) {
                    Text(if (vpnState.active) "Stop VPN" else "Start VPN")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { onToggleRootRedirect(!rootRedirectState.active) }) {
                Text(if (rootRedirectState.active) "Disable root redirect" else "Enable root redirect")
            }
        }

        SectionCard(
            title = "Recent Activity",
            subtitle = "Tap an exchange to inspect details."
        ) {
            if (recentRecords.isEmpty()) {
                Text(
                    text = "No captured traffic yet. Use Send or import a HAR to seed the workspace.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                recentRecords.forEachIndexed { index, record ->
                    TrafficRecordRow(record = record, selected = false, onClick = { onOpenRecord(record) })
                    if (index != recentRecords.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun InspectScreen(
    records: List<TrafficRecord>,
    selectedRecordId: Long,
    query: String,
    filter: InspectFilter,
    onQueryChange: (String) -> Unit,
    onFilterChange: (InspectFilter) -> Unit,
    onSelectRecord: (TrafficRecord) -> Unit,
    loadBodyText: suspend (String?) -> String,
    onReplayRecord: (TrafficRecord) -> Unit,
    onSeedRule: (TrafficRecord, String, String) -> Unit
) {
    val selectedRecord = records.firstOrNull { it.id == selectedRecordId }
    val requestBody by produceState(initialValue = "", selectedRecord?.requestBodyPath, selectedRecord?.requestBodyPreview) {
        value = selectedRecord?.let {
            loadBodyText(it.requestBodyPath).ifBlank { it.requestBodyPreview }
        }.orEmpty()
    }
    val responseBody by produceState(initialValue = "", selectedRecord?.responseBodyPath, selectedRecord?.responseBodyPreview) {
        value = selectedRecord?.let {
            loadBodyText(it.responseBodyPath).ifBlank { it.responseBodyPreview }
        }.orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(
            title = "Traffic Hunt",
            subtitle = "Use plain text or tokens: method:, status:, host:, path:, body:, header:, source:, type:."
        ) {
            LabeledTextField("Search / filter query", query, onQueryChange)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InspectFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { onFilterChange(item) },
                        label = { Text(item.name.lowercase().replaceFirstChar(Char::uppercase)) }
                    )
                }
            }
        }

        SectionCard(
            title = "Matches",
            subtitle = "${records.size} record(s) matched the current filter."
        ) {
            if (records.isEmpty()) {
                Text(
                    text = "No traffic matches the active query.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                records.forEachIndexed { index, record ->
                    TrafficRecordRow(
                        record = record,
                        selected = record.id == selectedRecordId,
                        onClick = { onSelectRecord(record) }
                    )
                    if (index != records.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }

        SectionCard(
            title = "Selected Exchange",
            subtitle = "Replay requests and convert captured traffic into mock rules."
        ) {
            if (selectedRecord == null) {
                Text(
                    text = "Pick a record to inspect request and response surfaces.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                MetricLine("URL", selectedRecord.requestUrl)
                MetricLine("Method", selectedRecord.requestMethod)
                MetricLine("Status", selectedRecord.responseStatus?.toString() ?: selectedRecord.outcome.name)
                MetricLine("Duration", "${selectedRecord.durationMs} ms")
                MetricLine("Source", selectedRecord.source)
                if (selectedRecord.matchedRules.isNotEmpty()) {
                    MetricLine("Matched rules", selectedRecord.matchedRules.joinToString(", "))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onReplayRecord(selectedRecord) }) { Text("Replay in Send") }
                    OutlinedButton(onClick = { onSeedRule(selectedRecord, requestBody, responseBody) }) {
                        Text("Promote to mock")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                InspectBlock("Request Headers", selectedRecord.requestHeaders.ifBlank { "<none>" })
                InspectBlock("Request Body", requestBody.ifBlank { "<empty>" })
                InspectBlock("Response Headers", selectedRecord.responseHeaders.ifBlank { "<none>" })
                InspectBlock("Response Body", responseBody.ifBlank { "<empty>" })
            }
        }
    }
}

@Composable
fun ModifyScreen(
    rules: List<RuleDefinition>,
    seededRule: RuleDefinition?,
    onSeedConsumed: () -> Unit,
    onSaveRule: (RuleDefinition) -> Unit,
    onDeleteRule: (Long) -> Unit
) {
    var draftRule by remember { mutableStateOf(rules.firstOrNull() ?: RuleDefinition()) }

    LaunchedEffect(seededRule) {
        if (seededRule != null) {
            draftRule = seededRule
            onSeedConsumed()
        }
    }

    LaunchedEffect(rules) {
        if (draftRule.id != 0L && rules.none { it.id == draftRule.id }) {
            draftRule = RuleDefinition()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(
            title = "Rule Library",
            subtitle = "Rewrite/mock/block/delay flows for controlled traffic manipulation."
        ) {
            OutlinedButton(onClick = { draftRule = RuleDefinition() }) { Text("New rule") }
            Spacer(modifier = Modifier.height(12.dp))
            if (rules.isEmpty()) {
                Text(
                    text = "No rules yet. Seed one from Inspect or build one from scratch.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                rules.forEachIndexed { index, rule ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (rule.id == draftRule.id && rule.id != 0L) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { draftRule = rule }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.fillMaxWidth(0.82f)) {
                                Text(rule.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "${rule.actionType.name} | ${rule.method}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { enabled ->
                                    onSaveRule(rule.copy(enabled = enabled))
                                    if (rule.id == draftRule.id) {
                                        draftRule = draftRule.copy(enabled = enabled)
                                    }
                                }
                            )
                        }
                    }
                    if (index != rules.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        RuleEditor(
            rule = draftRule,
            onRuleChange = { draftRule = it },
            onSaveRule = { onSaveRule(draftRule) },
            onDeleteRule = {
                onDeleteRule(draftRule.id)
                draftRule = RuleDefinition()
            }
        )
    }
}

@Composable
fun SendScreen(
    sendDraft: RequestDraft,
    isExecuting: Boolean,
    lastExecution: RequestExecutionResult?,
    pauseBeforeSend: Boolean,
    pauseAfterResponse: Boolean,
    onPauseBeforeSendChange: (Boolean) -> Unit,
    onPauseAfterResponseChange: (Boolean) -> Unit,
    onMethodChanged: (String) -> Unit,
    onUrlChanged: (String) -> Unit,
    onHeadersChanged: (String) -> Unit,
    onBodyChanged: (String) -> Unit,
    onExecute: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(
            title = "Custom Send Client",
            subtitle = "Compose raw HTTP, arm editable breakpoints, and drive manual probing."
        ) {
            DropdownSelector(
                label = "Method",
                selected = sendDraft.method,
                options = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"),
                onSelected = onMethodChanged
            )
            Spacer(modifier = Modifier.height(12.dp))
            LabeledTextField("URL", sendDraft.url, onUrlChanged)
            Spacer(modifier = Modifier.height(12.dp))
            LabeledTextField("Headers", sendDraft.headersText, onHeadersChanged, minLines = 6)
            Spacer(modifier = Modifier.height(12.dp))
            LabeledTextField("Body", sendDraft.bodyText, onBodyChanged, minLines = 10)
            Spacer(modifier = Modifier.height(12.dp))
            ToggleLine(
                modifier = Modifier.fillMaxWidth(),
                label = "Pause before send",
                checked = pauseBeforeSend,
                onCheckedChange = onPauseBeforeSendChange
            )
            Spacer(modifier = Modifier.height(8.dp))
            ToggleLine(
                modifier = Modifier.fillMaxWidth(),
                label = "Pause after response",
                checked = pauseAfterResponse,
                onCheckedChange = onPauseAfterResponseChange
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExecute, enabled = !isExecuting && sendDraft.url.isNotBlank()) {
                    Text(if (isExecuting) "Running..." else "Execute")
                }
                OutlinedButton(onClick = onClear) { Text("Reset") }
            }
        }

        SectionCard(
            title = "Latest Result",
            subtitle = "Status, rules, headers and body from the last completed request."
        ) {
            if (lastExecution == null) {
                Text(
                    text = "No result yet. Execute a request or replay one from Inspect.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                MetricLine("Final URL", lastExecution.finalUrl)
                MetricLine("Method", lastExecution.finalMethod)
                MetricLine("Status", lastExecution.responseStatus?.toString() ?: lastExecution.outcome.name)
                MetricLine("Duration", "${lastExecution.durationMs} ms")
                if (lastExecution.matchedRules.isNotEmpty()) {
                    MetricLine("Matched rules", lastExecution.matchedRules.joinToString(", "))
                }
                lastExecution.errorMessage?.let {
                    MetricLine("Error", it)
                }
                Spacer(modifier = Modifier.height(12.dp))
                InspectBlock("Response Headers", lastExecution.responseHeadersText.ifBlank { "<none>" })
                InspectBlock("Response Body", lastExecution.responseBodyText.ifBlank { "<empty>" })
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    rootAvailability: RootAvailability,
    proxyState: LocalProxyState,
    vpnState: com.apidebug.inspector.capture.VpnCaptureState,
    developerCaState: DeveloperCaState,
    rootRedirectState: RootRedirectState,
    onToggleDarkTheme: (Boolean) -> Unit,
    onTimeoutChanged: (Int) -> Unit,
    onRefreshRoot: () -> Unit,
    onToggleProxy: (Boolean) -> Unit,
    onEnsureDeveloperCa: () -> Unit,
    onExportDeveloperCa: () -> Unit,
    onToggleVpnCapture: (Boolean) -> Unit,
    onSetVpnRoutingMode: (VpnRoutingMode) -> Unit,
    onToggleRootRedirect: (Boolean) -> Unit
) {
    var timeoutSeconds by remember(settings.requestTimeoutMs) {
        mutableFloatStateOf(settings.requestTimeoutMs / 1000f)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionCard(
            title = "Operator Preferences",
            subtitle = "Tuning knobs for your local debugging stack."
        ) {
            ToggleLine(
                modifier = Modifier.fillMaxWidth(),
                label = "Dark theme",
                checked = settings.darkTheme,
                onCheckedChange = onToggleDarkTheme
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("Request timeout: ${timeoutSeconds.toInt()}s")
            Slider(
                value = timeoutSeconds,
                onValueChange = { timeoutSeconds = it },
                onValueChangeFinished = { onTimeoutChanged(timeoutSeconds.toInt() * 1000) },
                valueRange = 5f..120f
            )
            Spacer(modifier = Modifier.height(12.dp))
            MetricLine("Last export path", settings.lastExportPath.ifBlank { "No export yet" })
        }

        SectionCard(
            title = "Runtime Notes",
            subtitle = "Current capability snapshot for researchers."
        ) {
            MetricLine(
                "Root status",
                when (rootAvailability) {
                    RootAvailability.CHECKING -> "Checking"
                    RootAvailability.AVAILABLE -> "Available"
                    RootAvailability.UNAVAILABLE -> "Unavailable"
                }
            )
            MetricLine("Breakpoint scope", "Custom send client")
            MetricLine("Traffic imports", "HAR files append directly into history")
            MetricLine("Proxy state", if (proxyState.active) "Listening on ${proxyState.port}" else "Stopped")
            MetricLine("Developer CA", if (developerCaState.ready) "Ready for install" else "Not generated")
            MetricLine("VPN packets observed", vpnState.observedPackets.toString())
            MetricLine("Requested VPN mode", vpnModeLabel(settings.vpnRoutingMode))
            MetricLine("Effective VPN mode", vpnModeLabel(vpnState.effectiveRoutingMode))
            MetricLine("Native bridge", nativeBridgeLabel(vpnState))
            MetricLine("Root redirect", if (rootRedirectState.active) "iptables redirect active" else "Inactive")
            MetricLine("Device-wide capture", "Observe-only, proxy-aware, ya native tun2socks transparent path")
            if (vpnState.lastError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = vpnState.lastError,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VpnRoutingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.vpnRoutingMode == mode,
                        onClick = { onSetVpnRoutingMode(mode) },
                        label = { Text(vpnModeShortLabel(mode)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefreshRoot) { Text("Refresh root status") }
                OutlinedButton(onClick = onEnsureDeveloperCa) { Text("Ensure CA") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportDeveloperCa) { Text("Export CA") }
                OutlinedButton(onClick = { onToggleProxy(!proxyState.active) }) {
                    Text(if (proxyState.active) "Stop proxy" else "Start proxy")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { onToggleVpnCapture(!vpnState.active) }) {
                Text(if (vpnState.active) "Stop VPN capture" else "Start VPN capture")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { onToggleRootRedirect(!rootRedirectState.active) }) {
                Text(if (rootRedirectState.active) "Disable root redirect" else "Enable root redirect")
            }
        }
    }
}

private fun vpnModeShortLabel(mode: VpnRoutingMode): String = when (mode) {
    VpnRoutingMode.OBSERVE_ONLY -> "Observe"
    VpnRoutingMode.HTTP_PROXY -> "Proxy VPN"
    VpnRoutingMode.NATIVE_TUN2SOCKS -> "tun2socks"
}

private fun vpnModeLabel(mode: VpnRoutingMode): String = when (mode) {
    VpnRoutingMode.OBSERVE_ONLY -> "Observe-only packet inspection"
    VpnRoutingMode.HTTP_PROXY -> "Proxy-aware VPN path"
    VpnRoutingMode.NATIVE_TUN2SOCKS -> "Native transparent tun2socks path"
}

private fun nativeBridgeLabel(state: com.apidebug.inspector.capture.VpnCaptureState): String = when {
    state.nativeBridgeAvailable -> "Real Go backend loaded"
    state.nativeBridgeStubFallback -> "Stub fallback active"
    else -> "Missing"
}

@Composable
private fun RuleEditor(
    rule: RuleDefinition,
    onRuleChange: (RuleDefinition) -> Unit,
    onSaveRule: () -> Unit,
    onDeleteRule: () -> Unit
) {
    SectionCard(
        title = "Rule Editor",
        subtitle = "Define matchers first, then choose the transformation payload."
    ) {
        LabeledTextField(
            label = "Rule name",
            value = rule.name,
            onValueChange = { onRuleChange(rule.copy(name = it)) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleLine(
                modifier = Modifier.fillMaxWidth(),
                label = "Enabled",
                checked = rule.enabled,
                onCheckedChange = { onRuleChange(rule.copy(enabled = it)) }
            )
            ToggleLine(
                modifier = Modifier.fillMaxWidth(),
                label = "Stop on match",
                checked = rule.stopOnMatch,
                onCheckedChange = { onRuleChange(rule.copy(stopOnMatch = it)) }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        DropdownSelector(
            label = "HTTP method",
            selected = rule.method,
            options = listOf("ANY", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"),
            onSelected = { onRuleChange(rule.copy(method = it)) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        DropdownSelector(
            label = "Action",
            selected = rule.actionType.name,
            options = RuleActionType.entries.map { it.name },
            onSelected = { onRuleChange(rule.copy(actionType = RuleActionType.valueOf(it))) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        LabeledTextField(
            label = "Host contains",
            value = rule.hostContains,
            onValueChange = { onRuleChange(rule.copy(hostContains = it)) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        LabeledTextField(
            label = "Path contains",
            value = rule.pathContains,
            onValueChange = { onRuleChange(rule.copy(pathContains = it)) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        LabeledTextField(
            label = "Header name",
            value = rule.headerName,
            onValueChange = { onRuleChange(rule.copy(headerName = it)) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        LabeledTextField(
            label = "Header contains",
            value = rule.headerValueContains,
            onValueChange = { onRuleChange(rule.copy(headerValueContains = it)) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        LabeledTextField(
            label = "Body contains",
            value = rule.bodyContains,
            minLines = 3,
            onValueChange = { onRuleChange(rule.copy(bodyContains = it)) }
        )

        when (rule.actionType) {
            RuleActionType.REWRITE_REQUEST -> {
                Spacer(modifier = Modifier.height(16.dp))
                LabeledTextField(
                    label = "Rewrite URL",
                    value = rule.rewriteUrl,
                    onValueChange = { onRuleChange(rule.copy(rewriteUrl = it)) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                LabeledTextField(
                    label = "Rewrite method",
                    value = rule.rewriteMethod,
                    onValueChange = { onRuleChange(rule.copy(rewriteMethod = it)) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                LabeledTextField(
                    label = "Rewrite headers",
                    value = rule.rewriteHeadersText,
                    minLines = 4,
                    onValueChange = { onRuleChange(rule.copy(rewriteHeadersText = it)) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                LabeledTextField(
                    label = "Rewrite body",
                    value = rule.rewriteBodyText,
                    minLines = 6,
                    onValueChange = { onRuleChange(rule.copy(rewriteBodyText = it)) }
                )
            }

            RuleActionType.MOCK_RESPONSE -> {
                Spacer(modifier = Modifier.height(16.dp))
                LabeledTextField(
                    label = "Mock status",
                    value = rule.mockStatus.toString(),
                    onValueChange = {
                        onRuleChange(rule.copy(mockStatus = it.filter(Char::isDigit).toIntOrNull() ?: 200))
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                LabeledTextField(
                    label = "Mock headers",
                    value = rule.mockHeadersText,
                    minLines = 4,
                    onValueChange = { onRuleChange(rule.copy(mockHeadersText = it)) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                LabeledTextField(
                    label = "Mock body",
                    value = rule.mockBodyText,
                    minLines = 8,
                    onValueChange = { onRuleChange(rule.copy(mockBodyText = it)) }
                )
            }

            RuleActionType.DELAY_REQUEST -> {
                Spacer(modifier = Modifier.height(16.dp))
                LabeledTextField(
                    label = "Delay ms",
                    value = rule.delayMs.toString(),
                    onValueChange = {
                        onRuleChange(rule.copy(delayMs = it.filter(Char::isDigit).toLongOrNull() ?: 0L))
                    }
                )
            }

            RuleActionType.BLOCK_REQUEST -> Unit
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSaveRule) { Text("Save rule") }
            if (rule.id != 0L) {
                OutlinedButton(onClick = onDeleteRule) { Text("Delete") }
            }
        }
    }
}

@Composable
fun TrafficRecordRow(
    record: TrafficRecord,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        ),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${record.requestMethod} ${record.requestUrl}",
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${record.source} | ${record.durationMs} ms | ${formatTimestamp(record.timestamp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (record.matchedRules.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Rules: ${record.matchedRules.joinToString()}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            StatusTag(
                text = record.responseStatus?.toString() ?: record.outcome.name,
                tone = toneForOutcome(record.outcome)
            )
        }
    }
}
