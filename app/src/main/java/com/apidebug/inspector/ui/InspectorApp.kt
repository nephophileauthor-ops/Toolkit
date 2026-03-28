package com.apidebug.inspector.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apidebug.inspector.models.InspectorTab
import com.apidebug.inspector.models.RuleDefinition
import com.apidebug.inspector.models.TrafficOutcome
import com.apidebug.inspector.models.TrafficRecord
import java.util.Locale

enum class InspectFilter {
    ALL,
    SUCCESS,
    MOCKED,
    BLOCKED,
    FAILED;

    fun matches(record: TrafficRecord): Boolean = when (this) {
        ALL -> true
        SUCCESS -> record.outcome == TrafficOutcome.SUCCESS
        MOCKED -> record.outcome == TrafficOutcome.MOCKED
        BLOCKED -> record.outcome == TrafficOutcome.BLOCKED
        FAILED -> record.outcome == TrafficOutcome.FAILED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorApp(viewModel: InspectorViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val sendDraft by viewModel.sendDraft.collectAsStateWithLifecycle()
    val lastExecution by viewModel.lastExecution.collectAsStateWithLifecycle()
    val rootAvailability by viewModel.rootAvailability.collectAsStateWithLifecycle()
    val isExecuting by viewModel.isExecuting.collectAsStateWithLifecycle()
    val requestBreakpoint by viewModel.requestBreakpoint.collectAsStateWithLifecycle()
    val responseBreakpoint by viewModel.responseBreakpoint.collectAsStateWithLifecycle()
    val proxyState by viewModel.proxyState.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val developerCaState by viewModel.developerCaState.collectAsStateWithLifecycle()
    val rootRedirectState by viewModel.rootRedirectState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val importHarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importHar(uri)
        }
    }

    var selectedTabIndex by rememberSaveable { mutableIntStateOf(InspectorTab.Capture.ordinal) }
    var inspectQuery by rememberSaveable { mutableStateOf("") }
    var inspectFilterName by rememberSaveable { mutableStateOf(InspectFilter.ALL.name) }
    var selectedRecordId by rememberSaveable { mutableLongStateOf(0L) }
    var seededRule by remember { mutableStateOf<RuleDefinition?>(null) }
    var pauseBeforeSend by rememberSaveable { mutableStateOf(false) }
    var pauseAfterResponse by rememberSaveable { mutableStateOf(false) }

    val currentTab = InspectorTab.entries[selectedTabIndex]
    val inspectFilter = InspectFilter.valueOf(inspectFilterName)
    val filteredRecords = remember(records, inspectQuery, inspectFilter) {
        records.filter { inspectFilter.matches(it) && recordMatchesQuery(it, inspectQuery) }
    }

    LaunchedEffect(filteredRecords) {
        if (filteredRecords.none { it.id == selectedRecordId }) {
            selectedRecordId = filteredRecords.firstOrNull()?.id ?: 0L
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    requestBreakpoint?.let { breakpoint ->
        RequestBreakpointDialog(
            initialDraft = breakpoint.draft,
            onDismiss = viewModel::cancelRequestBreakpoint,
            onResume = { editedDraft ->
                viewModel.resumeRequestBreakpoint(
                    editedDraft = editedDraft,
                    pauseAfterResponse = breakpoint.pauseAfterResponse
                )
            }
        )
    }

    responseBreakpoint?.let { breakpoint ->
        ResponseBreakpointDialog(
            initialState = breakpoint,
            onDismiss = viewModel::discardResponseBreakpoint,
            onCommit = viewModel::commitResponseBreakpoint
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column {
                        Text("API Debug Inspector")
                        Text(currentTab.title)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                InspectorTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { selectedTabIndex = tab.ordinal },
                        icon = { androidx.compose.material3.Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentTab) {
                InspectorTab.Capture -> CaptureScreen(
                    settings = settings,
                    rootAvailability = rootAvailability,
                    proxyState = proxyState,
                    vpnState = vpnState,
                    developerCaState = developerCaState,
                    rootRedirectState = rootRedirectState,
                    recordCount = records.size,
                    ruleCount = rules.size,
                    recentRecords = records.take(8),
                    onToggleSession = viewModel::toggleSession,
                    onRefreshRoot = viewModel::refreshRootAvailability,
                    onExportHar = viewModel::exportHar,
                    onImportHar = { importHarLauncher.launch(arrayOf("*/*")) },
                    onExportRules = viewModel::exportRules,
                    onClearTraffic = viewModel::clearTraffic,
                    onToggleProxy = viewModel::toggleLocalProxy,
                    onEnsureDeveloperCa = viewModel::ensureDeveloperCertificateAuthority,
                    onExportDeveloperCa = viewModel::exportDeveloperCertificateAuthority,
                    onToggleVpnCapture = viewModel::toggleVpnCapture,
                    onSetVpnRoutingMode = viewModel::setVpnRoutingMode,
                    onToggleRootRedirect = viewModel::toggleRootRedirect,
                    onOpenRecord = { record ->
                        selectedRecordId = record.id
                        selectedTabIndex = InspectorTab.Inspect.ordinal
                    }
                )

                InspectorTab.Inspect -> InspectScreen(
                    records = filteredRecords,
                    selectedRecordId = selectedRecordId,
                    query = inspectQuery,
                    filter = inspectFilter,
                    onQueryChange = { inspectQuery = it },
                    onFilterChange = { inspectFilterName = it.name },
                    onSelectRecord = { selectedRecordId = it.id },
                    loadBodyText = viewModel::loadBodyText,
                    onReplayRecord = { record ->
                        viewModel.loadRecordIntoComposer(record)
                        selectedTabIndex = InspectorTab.Send.ordinal
                    },
                    onSeedRule = { record, requestBody, responseBody ->
                        seededRule = viewModel.buildMockRuleSeed(record, requestBody, responseBody)
                        selectedTabIndex = InspectorTab.Modify.ordinal
                    }
                )

                InspectorTab.Modify -> ModifyScreen(
                    rules = rules,
                    seededRule = seededRule,
                    onSeedConsumed = { seededRule = null },
                    onSaveRule = viewModel::saveRule,
                    onDeleteRule = viewModel::deleteRule
                )

                InspectorTab.Send -> SendScreen(
                    sendDraft = sendDraft,
                    isExecuting = isExecuting,
                    lastExecution = lastExecution,
                    pauseBeforeSend = pauseBeforeSend,
                    pauseAfterResponse = pauseAfterResponse,
                    onPauseBeforeSendChange = { pauseBeforeSend = it },
                    onPauseAfterResponseChange = { pauseAfterResponse = it },
                    onMethodChanged = viewModel::updateDraftMethod,
                    onUrlChanged = viewModel::updateDraftUrl,
                    onHeadersChanged = viewModel::updateDraftHeaders,
                    onBodyChanged = viewModel::updateDraftBody,
                    onExecute = {
                        viewModel.executeRequest(
                            pauseBeforeSend = pauseBeforeSend,
                            pauseAfterResponse = pauseAfterResponse
                        )
                    },
                    onClear = viewModel::clearDraft
                )

                InspectorTab.Settings -> SettingsScreen(
                    settings = settings,
                    rootAvailability = rootAvailability,
                    proxyState = proxyState,
                    vpnState = vpnState,
                    developerCaState = developerCaState,
                    rootRedirectState = rootRedirectState,
                    onToggleDarkTheme = viewModel::setDarkTheme,
                    onTimeoutChanged = viewModel::setRequestTimeout,
                    onRefreshRoot = viewModel::refreshRootAvailability,
                    onToggleProxy = viewModel::toggleLocalProxy,
                    onEnsureDeveloperCa = viewModel::ensureDeveloperCertificateAuthority,
                    onExportDeveloperCa = viewModel::exportDeveloperCertificateAuthority,
                    onToggleVpnCapture = viewModel::toggleVpnCapture,
                    onSetVpnRoutingMode = viewModel::setVpnRoutingMode,
                    onToggleRootRedirect = viewModel::toggleRootRedirect
                )
            }
        }
    }
}

fun recordMatchesQuery(record: TrafficRecord, query: String): Boolean {
    if (query.isBlank()) return true

    val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return tokens.all { token ->
        val separator = token.indexOf(':')
        if (separator > 0) {
            val key = token.substring(0, separator).lowercase(Locale.US)
            val value = token.substring(separator + 1)
            matchesStructuredToken(record, key, value)
        } else {
            val needle = token.lowercase(Locale.US)
            listOf(
                record.requestUrl,
                record.requestMethod,
                record.requestHeaders,
                record.requestBodyPreview,
                record.responseHeaders,
                record.responseBodyPreview,
                record.responseStatus?.toString().orEmpty(),
                record.outcome.name,
                record.source,
                record.contentType
            ).any { it.lowercase(Locale.US).contains(needle) }
        }
    }
}

private fun matchesStructuredToken(record: TrafficRecord, key: String, value: String): Boolean {
    val needle = value.lowercase(Locale.US)
    return when (key) {
        "method" -> record.requestMethod.lowercase(Locale.US).contains(needle)
        "status" -> record.responseStatus?.toString()?.contains(needle) == true
        "host" -> record.requestUrl.extractHost().contains(needle)
        "path" -> record.requestUrl.extractPath().contains(needle)
        "body" -> (record.requestBodyPreview + " " + record.responseBodyPreview).lowercase(Locale.US).contains(needle)
        "header" -> (record.requestHeaders + " " + record.responseHeaders).lowercase(Locale.US).contains(needle)
        "source" -> record.source.lowercase(Locale.US).contains(needle)
        "type" -> record.contentType.lowercase(Locale.US).contains(needle)
        "outcome" -> record.outcome.name.lowercase(Locale.US).contains(needle)
        else -> record.requestUrl.lowercase(Locale.US).contains(needle)
    }
}

private fun String.extractHost(): String {
    return substringAfter("://", this)
        .substringBefore('/')
        .lowercase(Locale.US)
}

private fun String.extractPath(): String {
    return substringAfter("://", this)
        .substringAfter('/', "")
        .lowercase(Locale.US)
}
