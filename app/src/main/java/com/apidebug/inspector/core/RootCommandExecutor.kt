package com.apidebug.inspector.core

import java.util.concurrent.TimeUnit

data class CommandResult(
    val output: String,
    val exitCode: Int,
    val error: String?,
    val timedOut: Boolean = false
)

class RootCommandExecutor {
    private val lock = Any()

    @Volatile
    private var activeProcess: Process? = null

    fun execute(command: String, timeoutSeconds: Long? = null): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val readerThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> output.appendLine(line) }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            synchronized(lock) {
                activeProcess = process
            }

            val finished = if (timeoutSeconds != null) {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } else {
                process.waitFor()
                true
            }

            if (!finished) {
                process.destroy()
                process.destroyForcibly()
                readerThread.join(1000)
                return CommandResult(
                    output = output.toString(),
                    exitCode = -1,
                    error = "Command timed out after ${timeoutSeconds ?: 0}s",
                    timedOut = true
                )
            }

            readerThread.join(1000)
            val exitCode = process.exitValue()
            CommandResult(output = output.toString(), exitCode = exitCode, error = null, timedOut = false)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CommandResult(output = "", exitCode = -1, error = "Command interrupted")
        } catch (e: Exception) {
            CommandResult(output = "", exitCode = -1, error = e.message)
        } finally {
            synchronized(lock) {
                activeProcess = null
            }
        }
    }

    fun cancelCurrentCommand() {
        synchronized(lock) {
            activeProcess?.destroy()
            activeProcess?.destroyForcibly()
            activeProcess = null
        }
    }

    fun isRootAvailable(): Boolean {
        val result = execute("id", timeoutSeconds = 5)
        return result.exitCode == 0 && result.output.contains("uid=0")
    }
}
