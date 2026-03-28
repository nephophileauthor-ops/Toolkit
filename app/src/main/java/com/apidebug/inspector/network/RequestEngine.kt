package com.apidebug.inspector.network

import android.util.Base64
import com.apidebug.inspector.data.RuleRepository
import com.apidebug.inspector.data.TrafficRepository
import com.apidebug.inspector.models.RequestDraft
import com.apidebug.inspector.models.RequestExecutionResult
import com.apidebug.inspector.models.TrafficOutcome
import com.apidebug.inspector.models.TrafficRecordDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RequestEngine(
    private val trafficRepository: TrafficRepository,
    private val ruleRepository: RuleRepository,
    private val vpnSocketProtector: VpnSocketProtector
) {
    suspend fun execute(
        draft: RequestDraft,
        timeoutMs: Int,
        persistTraffic: Boolean = true
    ): RequestExecutionResult = withContext(Dispatchers.IO) {
        val resolution = RuleEngine.applyRules(draft, ruleRepository.snapshot())

        when (resolution) {
            is RuleResolution.Block -> {
                val result = RequestExecutionResult(
                    finalUrl = resolution.request.url,
                    finalMethod = resolution.request.method,
                    finalHeadersText = RuleEngine.headersToText(resolution.request.headers),
                    finalBodyText = resolution.request.body,
                    responseStatus = null,
                    responseHeadersText = "",
                    responseBodyText = "",
                    durationMs = 0L,
                    outcome = TrafficOutcome.BLOCKED,
                    matchedRules = resolution.request.matchedRules.toList(),
                    errorMessage = resolution.reason
                )
                if (persistTraffic) {
                    persistExecutionResult(result)
                }
                result
            }

            is RuleResolution.Mock -> {
                if (resolution.request.accumulatedDelayMs > 0L) {
                    Thread.sleep(resolution.request.accumulatedDelayMs)
                }
                val result = resolution.response.copy(durationMs = resolution.request.accumulatedDelayMs)
                if (persistTraffic) {
                    persistExecutionResult(result)
                }
                result
            }

            is RuleResolution.Proceed -> executeNetwork(resolution.request, timeoutMs, persistTraffic)
        }
    }

    suspend fun persistExecutionResult(
        result: RequestExecutionResult,
        source: String = "send",
        contentType: String = ""
    ) {
        trafficRepository.append(
            TrafficRecordDraft(
                requestUrl = result.finalUrl,
                requestMethod = result.finalMethod,
                requestHeaders = result.finalHeadersText,
                requestBody = result.finalBodyText,
                responseHeaders = result.responseHeadersText,
                responseBody = result.responseBodyText,
                responseStatus = result.responseStatus,
                durationMs = result.durationMs,
                outcome = result.outcome,
                errorMessage = result.errorMessage,
                matchedRules = result.matchedRules,
                source = source,
                contentType = contentType
            )
        )
    }

    private suspend fun executeNetwork(
        request: PreparedRequest,
        timeoutMs: Int,
        persistTraffic: Boolean
    ): RequestExecutionResult {
        if (request.accumulatedDelayMs > 0L) {
            Thread.sleep(request.accumulatedDelayMs)
        }

        val startTime = System.currentTimeMillis()
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .socketFactory(ProtectedSocketFactory(vpnSocketProtector))
            .build()

        return try {
            val requestBody = if (request.method in BODYLESS_METHODS) {
                null
            } else {
                val contentType = request.headers.entries.firstOrNull {
                    it.key.equals("Content-Type", ignoreCase = true)
                }?.value ?: "application/json; charset=utf-8"
                request.body.toRequestBody(contentType.toMediaTypeOrNull())
            }

            val call = client.newCall(
                Request.Builder()
                    .url(request.url)
                    .headers(request.headers.toOkHttpHeaders())
                    .method(request.method, requestBody)
                    .build()
            )

            val response = call.execute()
            val responseBody = response.body
            val responseBytes = responseBody?.bytes() ?: ByteArray(0)
            val responseContentType = responseBody?.contentType()?.toString()
                ?: response.header("Content-Type").orEmpty()

            val result = RequestExecutionResult(
                finalUrl = request.url,
                finalMethod = request.method,
                finalHeadersText = RuleEngine.headersToText(request.headers),
                finalBodyText = request.body,
                responseStatus = response.code,
                responseHeadersText = response.headers.toMultimap()
                    .entries
                    .joinToString("\n") { (name, values) -> "$name: ${values.joinToString(", ")}" },
                responseBodyText = bytesToDisplayString(responseBytes, responseContentType),
                durationMs = System.currentTimeMillis() - startTime,
                outcome = TrafficOutcome.SUCCESS,
                matchedRules = request.matchedRules.toList(),
                errorMessage = null
            )

            if (persistTraffic) {
                persistExecutionResult(result, contentType = responseContentType)
            }
            result
        } catch (e: Exception) {
            val result = RequestExecutionResult(
                finalUrl = request.url,
                finalMethod = request.method,
                finalHeadersText = RuleEngine.headersToText(request.headers),
                finalBodyText = request.body,
                responseStatus = null,
                responseHeadersText = "",
                responseBodyText = "",
                durationMs = System.currentTimeMillis() - startTime,
                outcome = TrafficOutcome.FAILED,
                matchedRules = request.matchedRules.toList(),
                errorMessage = e.message ?: e.javaClass.simpleName
            )
            if (persistTraffic) {
                persistExecutionResult(result)
            }
            result
        }
    }

    private fun bytesToDisplayString(bytes: ByteArray, contentType: String): String {
        if (bytes.isEmpty()) return ""
        val charset = extractCharset(contentType)
        val decoded = runCatching { bytes.toString(charset) }.getOrDefault(bytes.toString(StandardCharsets.UTF_8))
        val printableRatio = decoded.count { !it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }
            .toFloat() / decoded.length.coerceAtLeast(1)

        if (contentType.contains("json", ignoreCase = true) ||
            contentType.contains("xml", ignoreCase = true) ||
            contentType.contains("text", ignoreCase = true) ||
            contentType.contains("html", ignoreCase = true) ||
            printableRatio > 0.85f
        ) {
            return decoded
        }

        return buildString {
            append("[binary payload, ")
            append(bytes.size)
            append(" bytes]\n")
            append(Base64.encodeToString(bytes.copyOfRange(0, minOf(bytes.size, 256)), Base64.NO_WRAP))
        }
    }

    private fun extractCharset(contentType: String): Charset {
        val match = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.getOrNull(1)
        return runCatching { Charset.forName(match ?: StandardCharsets.UTF_8.name()) }
            .getOrDefault(StandardCharsets.UTF_8)
    }

    companion object {
        private val BODYLESS_METHODS = setOf("GET", "HEAD")
    }
}

private fun Map<String, String>.toOkHttpHeaders(): Headers {
    val builder = Headers.Builder()
    forEach { (name, value) -> builder.addUnsafeNonAscii(name, value) }
    return builder.build()
}
