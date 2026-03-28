package com.apidebug.inspector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.apidebug.inspector.models.RequestDraft
import com.apidebug.inspector.models.TrafficOutcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RequestBreakpointDialog(
    initialDraft: RequestDraft,
    onDismiss: () -> Unit,
    onResume: (RequestDraft) -> Unit
) {
    var method by remember(initialDraft) { mutableStateOf(initialDraft.method) }
    var url by remember(initialDraft) { mutableStateOf(initialDraft.url) }
    var headers by remember(initialDraft) { mutableStateOf(initialDraft.headersText) }
    var body by remember(initialDraft) { mutableStateOf(initialDraft.bodyText) }

    Dialog(onDismissRequest = onDismiss) {
        BreakpointSurface(
            title = "Request Breakpoint",
            subtitle = "Edit the request before it is sent to the network."
        ) {
            DropdownSelector(
                label = "Method",
                selected = method,
                options = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"),
                onSelected = { method = it }
            )
            Spacer(modifier = Modifier.height(12.dp))
            LabeledTextField(
                label = "URL",
                value = url,
                onValueChange = { url = it }
            )
            Spacer(modifier = Modifier.height(12.dp))
            LabeledTextField(
                label = "Headers",
                value = headers,
                minLines = 6,
                onValueChange = { headers = it }
            )
            Spacer(modifier = Modifier.height(12.dp))
            LabeledTextField(
                label = "Body",
                value = body,
                minLines = 8,
                onValueChange = { body = it }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onResume(
                            RequestDraft(
                                url = url,
                                method = method,
                                headersText = headers,
                                bodyText = body
                            )
                        )
                    }
                ) {
                    Text("Resume")
                }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
fun ResponseBreakpointDialog(
    initialState: ResponseBreakpointState,
    onDismiss: () -> Unit,
    onCommit: (ResponseBreakpointState) -> Unit
) {
    var status by remember(initialState) { mutableStateOf(initialState.responseStatusText) }
    var headers by remember(initialState) { mutableStateOf(initialState.responseHeadersText) }
    var body by remember(initialState) { mutableStateOf(initialState.responseBodyText) }

    Dialog(onDismissRequest = onDismiss) {
        BreakpointSurface(
            title = "Response Breakpoint",
            subtitle = "Adjust the result before it is committed to history."
        ) {
            MetricLine("URL", initialState.finalUrl)
            MetricLine("Method", initialState.finalMethod)
            MetricLine("Duration", "${initialState.durationMs} ms")
            Spacer(modifier = Modifier.height(12.dp))
            LabeledTextField(
                label = "Status",
                value = status,
                onValueChange = { status = it }
            )
            Spacer(modifier = Modifier.height(12.dp))
            LabeledTextField(
                label = "Headers",
                value = headers,
                minLines = 6,
                onValueChange = { headers = it }
            )
            Spacer(modifier = Modifier.height(12.dp))
            LabeledTextField(
                label = "Body",
                value = body,
                minLines = 8,
                onValueChange = { body = it }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onCommit(
                            initialState.copy(
                                responseStatusText = status,
                                responseHeadersText = headers,
                                responseBodyText = body
                            )
                        )
                    }
                ) {
                    Text("Commit")
                }
                OutlinedButton(onClick = onDismiss) { Text("Drop") }
            }
        }
    }
}

@Composable
fun BreakpointSurface(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun InspectBlock(title: String, content: String) {
    Text(title, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(4.dp))
    SelectionContainer {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = content,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    minLines: Int = 1
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines
    )
}

@Composable
fun DropdownSelector(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.ifBlank { "Select" })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun ToggleLine(
    modifier: Modifier,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun MetricLine(label: String, value: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SelectionContainer {
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
fun StatusTag(
    text: String,
    tone: Color
) {
    Surface(
        color = tone.copy(alpha = 0.18f),
        contentColor = tone,
        shape = MaterialTheme.shapes.large
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

fun toneForOutcome(outcome: TrafficOutcome): Color = when (outcome) {
    TrafficOutcome.SUCCESS -> Color(0xFF0C8A5E)
    TrafficOutcome.MOCKED -> Color(0xFF8A5A00)
    TrafficOutcome.BLOCKED -> Color(0xFF8C1D18)
    TrafficOutcome.FAILED -> Color(0xFF9B174C)
}

@Composable
fun toneForRoot(rootAvailability: RootAvailability): Color = when (rootAvailability) {
    RootAvailability.CHECKING -> MaterialTheme.colorScheme.secondary
    RootAvailability.AVAILABLE -> MaterialTheme.colorScheme.tertiary
    RootAvailability.UNAVAILABLE -> MaterialTheme.colorScheme.error
}

fun formatTimestamp(timestamp: Long): String = SimpleDateFormat(
    "dd MMM HH:mm:ss",
    Locale.getDefault()
).format(Date(timestamp))
