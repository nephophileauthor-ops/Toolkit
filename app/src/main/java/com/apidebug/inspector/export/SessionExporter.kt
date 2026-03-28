package com.apidebug.inspector.export

import android.content.Context
import android.net.Uri
import com.apidebug.inspector.models.TrafficOutcome
import com.apidebug.inspector.models.TrafficRecordDraft
import com.apidebug.inspector.data.RuleRepository
import com.apidebug.inspector.data.TrafficRepository
import com.apidebug.inspector.models.RuleSetV1
import com.apidebug.inspector.models.TrafficRecord
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

class SessionExporter(
    private val context: Context,
    private val gson: Gson,
    private val trafficRepository: TrafficRepository,
    private val ruleRepository: RuleRepository
) {
    suspend fun exportHar(): File = withContext(Dispatchers.IO) {
        val file = createExportFile(prefix = "session", extension = "har")
        val payload = HarRoot(
            log = HarLog(
                entries = trafficRepository.snapshot()
                    .sortedBy { it.timestamp }
                    .map(::toHarEntry)
            )
        )
        file.writeText(gson.toJson(payload))
        file
    }

    suspend fun exportRules(): File = withContext(Dispatchers.IO) {
        val file = createExportFile(prefix = "rules", extension = "json")
        val payload = RuleSetV1(rules = ruleRepository.snapshot())
        file.writeText(gson.toJson(payload))
        file
    }

    suspend fun importHar(uri: Uri): Int = withContext(Dispatchers.IO) {
        val importedRoot = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            gson.fromJson(reader, ImportedHarRoot::class.java)
        } ?: return@withContext 0

        val drafts = importedRoot.log.entries.map { entry ->
            TrafficRecordDraft(
                timestamp = parseImportedTimestamp(entry.startedDateTime),
                requestUrl = entry.request.url,
                requestMethod = entry.request.method,
                requestHeaders = entry.request.headers.joinToString("\n") { "${it.name}: ${it.value}" },
                requestBody = entry.request.postData?.text.orEmpty(),
                responseStatus = entry.response.status,
                responseHeaders = entry.response.headers.joinToString("\n") { "${it.name}: ${it.value}" },
                responseBody = entry.response.content?.text.orEmpty(),
                durationMs = entry.time.roundToLong(),
                outcome = if (entry.response.status in 100..599) {
                    TrafficOutcome.SUCCESS
                } else {
                    TrafficOutcome.FAILED
                },
                matchedRules = emptyList(),
                source = "har-import",
                contentType = entry.response.content?.mimeType.orEmpty()
            )
        }

        trafficRepository.importRecords(drafts)
        drafts.size
    }

    private fun createExportFile(prefix: String, extension: String): File {
        val fileName = "${prefix}_${fileStampFormat().format(Date())}.$extension"
        return File(trafficRepository.exportDirectory(), fileName)
    }

    private fun toHarEntry(record: TrafficRecord): HarEntry {
        val requestHeaders = parseHeaders(record.requestHeaders)
        val responseHeaders = parseHeaders(record.responseHeaders)
        val requestBody = trafficRepository.loadBodyText(record.requestBodyPath)
            .ifBlank { record.requestBodyPreview }
        val responseBody = trafficRepository.loadBodyText(record.responseBodyPath)
            .ifBlank { record.responseBodyPreview }
        val requestUri = Uri.parse(record.requestUrl)
        val requestMimeType = headerValue(requestHeaders, "Content-Type").ifBlank { "text/plain" }
        val responseMimeType = headerValue(responseHeaders, "Content-Type")
            .ifBlank { record.contentType.ifBlank { "text/plain" } }

        return HarEntry(
            startedDateTime = iso8601Format().format(Date(record.timestamp)),
            time = record.durationMs.toDouble(),
            request = HarRequest(
                method = record.requestMethod,
                url = record.requestUrl,
                httpVersion = "HTTP/1.1",
                headers = requestHeaders,
                queryString = requestUri.queryParameterNames.flatMap { name ->
                    requestUri.getQueryParameters(name).map { value ->
                        HarNameValue(name = name, value = value)
                    }
                },
                headersSize = -1,
                bodySize = requestBody.toByteArray().size,
                postData = requestBody.takeIf { it.isNotBlank() }?.let {
                    HarPostData(
                        mimeType = requestMimeType,
                        text = it
                    )
                }
            ),
            response = HarResponse(
                status = record.responseStatus ?: 0,
                statusText = record.outcome.name,
                httpVersion = "HTTP/1.1",
                headers = responseHeaders,
                cookies = emptyList(),
                content = HarContent(
                    size = responseBody.toByteArray().size,
                    mimeType = responseMimeType,
                    text = responseBody
                ),
                redirectURL = headerValue(responseHeaders, "Location"),
                headersSize = -1,
                bodySize = responseBody.toByteArray().size
            ),
            cache = HarCache(),
            timings = HarTimings(wait = record.durationMs.toDouble())
        )
    }

    private fun parseHeaders(headersText: String): List<HarNameValue> {
        return headersText.lines()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { line ->
                val delimiter = line.indexOf(':')
                if (delimiter <= 0) {
                    null
                } else {
                    HarNameValue(
                        name = line.substring(0, delimiter).trim(),
                        value = line.substring(delimiter + 1).trim()
                    )
                }
            }
    }

    private fun headerValue(headers: List<HarNameValue>, name: String): String {
        return headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value.orEmpty()
    }

    private fun iso8601Format(): SimpleDateFormat = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun fileStampFormat(): SimpleDateFormat = SimpleDateFormat(
        "yyyyMMdd_HHmmss",
        Locale.US
    )

    private fun parseImportedTimestamp(rawValue: String): Long {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )

        patterns.forEach { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(rawValue)?.time
            }.getOrNull()?.let { return it }
        }

        return System.currentTimeMillis()
    }
}

private data class HarRoot(
    val log: HarLog
)

private data class HarLog(
    val version: String = "1.2",
    val creator: HarCreator = HarCreator(),
    val entries: List<HarEntry>
)

private data class HarCreator(
    val name: String = "API Debug Inspector",
    val version: String = "1.0.0"
)

private data class HarEntry(
    val startedDateTime: String,
    val time: Double,
    val request: HarRequest,
    val response: HarResponse,
    val cache: HarCache,
    val timings: HarTimings
)

private data class HarRequest(
    val method: String,
    val url: String,
    val httpVersion: String,
    val headers: List<HarNameValue>,
    val queryString: List<HarNameValue>,
    val cookies: List<HarNameValue> = emptyList(),
    val headersSize: Int,
    val bodySize: Int,
    val postData: HarPostData? = null
)

private data class HarResponse(
    val status: Int,
    val statusText: String,
    val httpVersion: String,
    val headers: List<HarNameValue>,
    val cookies: List<HarNameValue>,
    val content: HarContent,
    val redirectURL: String,
    val headersSize: Int,
    val bodySize: Int
)

private data class HarPostData(
    val mimeType: String,
    val text: String
)

private data class HarContent(
    val size: Int,
    val mimeType: String,
    val text: String
)

private data class HarCache(
    val beforeRequest: Any? = null,
    val afterRequest: Any? = null
)

private data class HarTimings(
    val send: Double = 0.0,
    val wait: Double,
    val receive: Double = 0.0
)

private data class HarNameValue(
    val name: String,
    val value: String
)

private data class ImportedHarRoot(
    val log: ImportedHarLog = ImportedHarLog()
)

private data class ImportedHarLog(
    val entries: List<ImportedHarEntry> = emptyList()
)

private data class ImportedHarEntry(
    val startedDateTime: String = "",
    val time: Double = 0.0,
    val request: ImportedHarRequest = ImportedHarRequest(),
    val response: ImportedHarResponse = ImportedHarResponse()
)

private data class ImportedHarRequest(
    val method: String = "GET",
    val url: String = "",
    val headers: List<HarNameValue> = emptyList(),
    val postData: ImportedHarPostData? = null
)

private data class ImportedHarResponse(
    val status: Int = 0,
    val headers: List<HarNameValue> = emptyList(),
    val content: ImportedHarContent? = null
)

private data class ImportedHarContent(
    val mimeType: String? = null,
    val text: String? = null
)

private data class ImportedHarPostData(
    val text: String? = null
)
