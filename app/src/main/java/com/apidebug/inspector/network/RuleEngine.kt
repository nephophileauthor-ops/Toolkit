package com.apidebug.inspector.network

import com.apidebug.inspector.models.RequestDraft
import com.apidebug.inspector.models.RequestExecutionResult
import com.apidebug.inspector.models.RuleActionType
import com.apidebug.inspector.models.RuleDefinition
import com.apidebug.inspector.models.TrafficOutcome
import java.net.URL
import java.util.Locale

data class PreparedRequest(
    var url: String,
    var method: String,
    val headers: LinkedHashMap<String, String>,
    var body: String,
    val matchedRules: MutableList<String> = mutableListOf(),
    var accumulatedDelayMs: Long = 0L
)

sealed interface RuleResolution {
    data class Proceed(val request: PreparedRequest) : RuleResolution
    data class Mock(val request: PreparedRequest, val response: RequestExecutionResult) : RuleResolution
    data class Block(val request: PreparedRequest, val reason: String) : RuleResolution
}

object RuleEngine {
    fun applyRules(draft: RequestDraft, rules: List<RuleDefinition>): RuleResolution {
        val request = PreparedRequest(
            url = draft.url.trim(),
            method = draft.method.trim().uppercase(Locale.US),
            headers = parseHeadersText(draft.headersText),
            body = draft.bodyText
        )

        for (rule in rules.filter { it.enabled }) {
            if (!matches(rule, request)) continue

            request.matchedRules += rule.name

            when (rule.actionType) {
                RuleActionType.REWRITE_REQUEST -> applyRewrite(rule, request)
                RuleActionType.MOCK_RESPONSE -> {
                    val response = RequestExecutionResult(
                        finalUrl = request.url,
                        finalMethod = request.method,
                        finalHeadersText = headersToText(request.headers),
                        finalBodyText = request.body,
                        responseStatus = rule.mockStatus,
                        responseHeadersText = normalizeHeaderText(rule.mockHeadersText),
                        responseBodyText = rule.mockBodyText,
                        durationMs = request.accumulatedDelayMs,
                        outcome = TrafficOutcome.MOCKED,
                        matchedRules = request.matchedRules.toList(),
                        errorMessage = null
                    )
                    return RuleResolution.Mock(request, response)
                }

                RuleActionType.BLOCK_REQUEST -> {
                    return RuleResolution.Block(request, "Blocked by rule '${rule.name}'")
                }

                RuleActionType.DELAY_REQUEST -> {
                    request.accumulatedDelayMs += rule.delayMs.coerceAtLeast(0L)
                }
            }

            if (rule.stopOnMatch) break
        }

        return RuleResolution.Proceed(request)
    }

    fun parseHeadersText(text: String): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { line ->
                val delimiter = line.indexOf(':')
                if (delimiter <= 0) return@forEach
                val name = line.substring(0, delimiter).trim()
                val value = line.substring(delimiter + 1).trim()
                result[name] = value
            }
        return LinkedHashMap(result)
    }

    fun headersToText(headers: Map<String, String>): String = headers.entries.joinToString("\n") { (name, value) ->
        "$name: $value"
    }

    private fun matches(rule: RuleDefinition, request: PreparedRequest): Boolean {
        val url = runCatching { URL(request.url) }.getOrNull()
        val host = url?.host.orEmpty()
        val path = url?.path.orEmpty()

        if (rule.hostContains.isNotBlank() && !host.contains(rule.hostContains, ignoreCase = true)) return false
        if (rule.pathContains.isNotBlank() && !path.contains(rule.pathContains, ignoreCase = true)) return false
        if (rule.method != "ANY" && !request.method.equals(rule.method, ignoreCase = true)) return false
        if (rule.bodyContains.isNotBlank() && !request.body.contains(rule.bodyContains, ignoreCase = true)) return false

        if (rule.headerName.isNotBlank()) {
            val matchingHeader = request.headers.entries.firstOrNull {
                it.key.equals(rule.headerName, ignoreCase = true)
            } ?: return false

            if (rule.headerValueContains.isNotBlank() &&
                !matchingHeader.value.contains(rule.headerValueContains, ignoreCase = true)
            ) {
                return false
            }
        }

        return true
    }

    private fun applyRewrite(rule: RuleDefinition, request: PreparedRequest) {
        if (rule.rewriteUrl.isNotBlank()) {
            request.url = rule.rewriteUrl.trim()
        }
        if (rule.rewriteMethod.isNotBlank()) {
            request.method = rule.rewriteMethod.trim().uppercase(Locale.US)
        }
        if (rule.rewriteHeadersText.isNotBlank()) {
            parseHeadersText(rule.rewriteHeadersText).forEach { (name, value) ->
                request.headers[name] = value
            }
        }
        if (rule.rewriteBodyText.isNotBlank()) {
            request.body = rule.rewriteBodyText
        }
    }

    private fun normalizeHeaderText(headersText: String): String = headersToText(parseHeadersText(headersText))
}
