package com.apidebug.inspector.proxy

import com.apidebug.inspector.data.SettingsRepository
import com.apidebug.inspector.models.RequestDraft
import com.apidebug.inspector.network.RequestEngine
import com.apidebug.inspector.tls.DeveloperCertificateAuthority
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalDebugProxyServer(
    private val requestEngine: RequestEngine,
    private val settingsRepository: SettingsRepository,
    private val certificateAuthority: DeveloperCertificateAuthority
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LocalProxyState())
    val state: StateFlow<LocalProxyState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    suspend fun start(port: Int = LocalProxyState.DEFAULT_PROXY_PORT) = withContext(Dispatchers.IO) {
        if (_state.value.active) return@withContext

        certificateAuthority.ensureCertificateAuthority()
        serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        _state.value = _state.value.copy(
            active = true,
            port = port,
            lastError = null
        )

        acceptJob = scope.launch {
            while (isActive) {
                val client = runCatching { serverSocket?.accept() }.getOrNull() ?: break
                launch { handleClient(client) }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _state.value = _state.value.copy(active = false)
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { rawSocket ->
            val input = BufferedInputStream(rawSocket.getInputStream())
            val output = BufferedOutputStream(rawSocket.getOutputStream())
            val initialRequest = HttpWireParser.readRequest(input) ?: return

            _state.value = _state.value.copy(
                lastRequestLine = "${initialRequest.method} ${initialRequest.target}",
                lastError = null
            )

            if (initialRequest.method.equals("CONNECT", ignoreCase = true)) {
                handleConnectRequest(rawSocket, output, initialRequest)
            } else {
                forwardRequest(
                    request = initialRequest,
                    output = output,
                    sourceLabel = "proxy-http"
                )
            }
        }
    }

    private suspend fun handleConnectRequest(
        rawSocket: Socket,
        output: BufferedOutputStream,
        connectRequest: HttpWireRequest
    ) {
        val authority = connectRequest.target
        val host = authority.substringBefore(':')
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()

        val sslContext = certificateAuthority.createServerSslContext(host)
        val layered = sslContext.socketFactory.createSocket(
            rawSocket,
            rawSocket.inetAddress.hostAddress,
            rawSocket.port,
            false
        ) as SSLSocket

        layered.use { secureSocket ->
            secureSocket.useClientMode = false
            secureSocket.startHandshake()

            val tlsInput = BufferedInputStream(secureSocket.inputStream)
            val tlsOutput = BufferedOutputStream(secureSocket.outputStream)
            val tlsRequest = HttpWireParser.readRequest(
                input = tlsInput,
                schemeHint = "https",
                authorityHint = authority
            ) ?: return

            forwardRequest(
                request = tlsRequest,
                output = tlsOutput,
                sourceLabel = "proxy-https"
            )
        }
    }

    private suspend fun forwardRequest(
        request: HttpWireRequest,
        output: BufferedOutputStream,
        sourceLabel: String
    ) {
        val draft = RequestDraft(
            url = request.url,
            method = request.method,
            headersText = request.headersAsText(),
            bodyText = request.bodyText
        )
        val timeoutMs = settingsRepository.settings.value.requestTimeoutMs
        val result = requestEngine.execute(
            draft = draft,
            timeoutMs = timeoutMs,
            persistTraffic = false
        )
        requestEngine.persistExecutionResult(
            result = result,
            source = sourceLabel,
            contentType = headerValue(result.responseHeadersText, "Content-Type")
        )
        HttpWireParser.writeResponse(output, result)

        _state.value = _state.value.copy(
            interceptedRequests = _state.value.interceptedRequests + 1,
            lastRequestLine = "${request.method} ${request.url}",
            lastError = result.errorMessage
        )
    }

    private fun headerValue(headersText: String, name: String): String {
        return headersText.lines()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
    }
}

private data class HttpWireRequest(
    val method: String,
    val target: String,
    val url: String,
    val headers: LinkedHashMap<String, String>,
    val bodyText: String
) {
    fun headersAsText(): String = headers.entries.joinToString("\n") { (name, value) -> "$name: $value" }
}

private object HttpWireParser {
    fun readRequest(
        input: InputStream,
        schemeHint: String? = null,
        authorityHint: String? = null
    ): HttpWireRequest? {
        val requestLine = input.readAsciiLine() ?: return null
        if (requestLine.isBlank()) return null

        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val method = parts[0].trim()
        val target = parts[1].trim()
        val headers = linkedMapOf<String, String>()

        while (true) {
            val line = input.readAsciiLine() ?: break
            if (line.isBlank()) break
            val delimiter = line.indexOf(':')
            if (delimiter <= 0) continue
            headers[line.substring(0, delimiter).trim()] = line.substring(delimiter + 1).trim()
        }

        val bodyLength = headers.entries.firstOrNull {
            it.key.equals("Content-Length", ignoreCase = true)
        }?.value?.toIntOrNull() ?: 0
        val bodyBytes = if (bodyLength > 0) input.readExact(bodyLength) else ByteArray(0)
        val bodyText = bodyBytes.toString(StandardCharsets.UTF_8)
        val url = resolveUrl(target, headers, schemeHint, authorityHint)

        headers.remove("Proxy-Connection")
        headers.remove("Connection")
        headers.remove("Content-Length")

        return HttpWireRequest(
            method = method,
            target = target,
            url = url,
            headers = LinkedHashMap(headers),
            bodyText = bodyText
        )
    }

    fun writeResponse(
        output: BufferedOutputStream,
        result: com.apidebug.inspector.models.RequestExecutionResult
    ) {
        val statusCode = result.responseStatus ?: if (result.outcome.name == "BLOCKED") 403 else 502
        val body = result.responseBodyText.ifBlank { result.errorMessage.orEmpty() }
        val existingHeaders = parseHeaders(result.responseHeadersText)
            .filterKeys { !it.equals("Transfer-Encoding", ignoreCase = true) && !it.equals("Content-Length", ignoreCase = true) }
            .toMutableMap()

        existingHeaders["Connection"] = "close"
        existingHeaders["Content-Length"] = body.toByteArray(StandardCharsets.UTF_8).size.toString()
        if (body.isNotBlank() && existingHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            existingHeaders["Content-Type"] = "text/plain; charset=utf-8"
        }

        output.write("HTTP/1.1 $statusCode ${reasonPhrase(statusCode)}\r\n".toByteArray(StandardCharsets.UTF_8))
        existingHeaders.forEach { (name, value) ->
            output.write("$name: $value\r\n".toByteArray(StandardCharsets.UTF_8))
        }
        output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
        if (body.isNotEmpty()) {
            output.write(body.toByteArray(StandardCharsets.UTF_8))
        }
        output.flush()
    }

    private fun parseHeaders(headersText: String): Map<String, String> {
        return headersText.lines()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { line ->
                val delimiter = line.indexOf(':')
                if (delimiter <= 0) {
                    null
                } else {
                    line.substring(0, delimiter).trim() to line.substring(delimiter + 1).trim()
                }
            }
            .toMap(LinkedHashMap())
    }

    private fun resolveUrl(
        target: String,
        headers: Map<String, String>,
        schemeHint: String?,
        authorityHint: String?
    ): String {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target
        }

        val authority = authorityHint ?: headers.entries.firstOrNull {
            it.key.equals("Host", ignoreCase = true)
        }?.value.orEmpty()
        val scheme = schemeHint ?: "http"
        return "$scheme://$authority$target"
    }

    private fun InputStream.readAsciiLine(): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val next = read()
            if (next == -1) {
                return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
            }
            if (next == '\n'.code) {
                return buffer.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
            }
            buffer.write(next)
        }
    }

    private fun InputStream.readExact(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(buffer, offset, size - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == size) buffer else buffer.copyOf(offset)
    }

    private fun reasonPhrase(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        408 -> "Request Timeout"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Proxy Response"
    }
}
