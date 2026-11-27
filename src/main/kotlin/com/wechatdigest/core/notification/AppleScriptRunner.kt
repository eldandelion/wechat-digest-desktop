package com.wechatdigest.core.notification

import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Shared macOS AppleScript sanitization and `osascript` stdin subprocess runner.
 * Passing scripts over `stdin` (`osascript -`) avoids exposing private notice text in `argv`.
 */
internal object AppleScriptRunner {

    private const val MAX_OUTPUT_CAPTURE_CHARS = 512

    /** Truncates [text] to at most [maxChars] UTF-16 code units without splitting surrogate pairs. */
    fun truncateCodePointSafe(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        val end = if (Character.isHighSurrogate(text[maxChars - 1])) maxChars - 1 else maxChars
        return text.substring(0, end)
    }

    /** Strips NUL/control characters and escapes backslashes, quotes, and line endings for AppleScript strings. */
    fun escapeString(text: String, replaceNewlinesWithSpace: Boolean): String {
        val cleaned = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                when {
                    ch == '\u0000' -> Unit
                    ch == '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                        append('\n')
                    }
                    ch == '\n' || ch == '\t' -> append(ch)
                    ch.code < 0x20 || ch.code == 0x7F -> Unit
                    else -> append(ch)
                }
                i++
            }
        }
        val escaped = cleaned
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return if (replaceNewlinesWithSpace) {
            escaped.replace('\n', ' ').replace('\t', ' ')
        } else {
            escaped.replace("\n", "\\n")
        }
    }

    /** Executes [script] via pinned `/usr/bin/osascript -` with bounded wait and guaranteed cleanup. */
    fun runScriptViaStdin(script: String, timeoutSeconds: Long = 5L) {
        val os = System.getProperty("os.name")?.lowercase(Locale.ROOT) ?: ""
        if (!os.contains("mac")) {
            throw UnsupportedOperationException("osascript execution is only supported on macOS")
        }
        val osascriptBin = File("/usr/bin/osascript").takeIf { it.canExecute() }?.absolutePath ?: "osascript"
        val proc = ProcessBuilder(osascriptBin, "-")
            .redirectErrorStream(true)
            .start()
        try {
            proc.outputStream.use { osStream ->
                osStream.write(script.toByteArray(Charsets.UTF_8))
                osStream.flush()
            }
            val captured = proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(MAX_OUTPUT_CAPTURE_CHARS)
                val sb = StringBuilder()
                while (sb.length < MAX_OUTPUT_CAPTURE_CHARS) {
                    val read = reader.read(buf, 0, MAX_OUTPUT_CAPTURE_CHARS - sb.length)
                    if (read <= 0) break
                    sb.append(buf, 0, read)
                }
                sb.toString()
            }
            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                throw IllegalStateException("osascript timed out")
            }
            val exitCode = proc.exitValue()
            if (exitCode != 0) {
                // Report only exit code to avoid echoing private chat prose from AppleScript lines.
                throw IllegalStateException("osascript exited with $exitCode (${captured.length} chars output suppressed)")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("osascript execution interrupted", e)
        } finally {
            if (proc.isAlive) {
                proc.destroyForcibly()
            }
            runCatching { proc.inputStream.close() }
        }
    }
}
