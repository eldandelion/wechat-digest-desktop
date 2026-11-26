package com.wechatdigest.core.llm

import kotlinx.serialization.json.Json

/**
 * Shared JSON parsing, size bounding, code-fence stripping, lenient LLM syntax repair,
 * and log sanitization utilities for LLM extraction and translation components.
 */
internal object LlmJsonUtils {

    const val MAX_RAW_JSON_CHARS = 512_000
    private const val MAX_LOG_MESSAGE_CHARS = 240

    private val urlWithQueryOrCredentialsRegex = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val localFilesystemPathRegex = Regex("/(?:Users|Volumes|tmp|var|private|home)/[^\\s\"':,)<>]+")
    private val bearerTokenRegex = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val secretKeyPrefixRegex = Regex("""\bsk-[A-Za-z0-9_-]{8,}\b""")
    private val hexKey64Regex = Regex("""\b[0-9a-fA-F]{64}\b""")
    private val controlOrAnsiRegex = Regex("[\\u0000-\\u001F\\u007F]")
    private val trailingCommaRegex = Regex(",(\\s*[}\\]])")
    private val incompleteTrailingObjectKeyRegex = Regex(",\\s*\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"\\s*$")

    val sharedJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Bounds raw payload size, strips surrounding markdown code fences, and slices the outer `{ ... }` JSON object.
     * Preserves truncated JSON tails if the top-level `{` was never closed so [repairMalformedJson] can recover them.
     */
    fun cleanJsonObject(raw: String): String {
        if (raw.length > MAX_RAW_JSON_CHARS) {
            throw IllegalStateException("LLM JSON response exceeded maximum allowed size ($MAX_RAW_JSON_CHARS chars)")
        }
        var str = raw.trim()
        if (str.startsWith("```json")) {
            str = str.removePrefix("```json")
        } else if (str.startsWith("```")) {
            str = str.removePrefix("```")
        }
        if (str.endsWith("```")) {
            str = str.removeSuffix("```")
        }
        val startIdx = str.indexOf('{')
        if (startIdx == -1) return str.trim()

        val topLevelEndIdx = findTopLevelObjectEnd(str, startIdx)
        return if (topLevelEndIdx != -1) {
            str.substring(startIdx, topLevelEndIdx + 1)
        } else {
            str.substring(startIdx).trimEnd()
        }
    }

    private fun findTopLevelObjectEnd(str: String, startIdx: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIdx until str.length) {
            val ch = str[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /**
     * Parses [raw] via [decode]; if initial parsing fails due to unescaped quotes, raw newlines,
     * trailing commas, or truncated closing delimiters, repairs syntax and retries parsing once.
     */
    inline fun <T> decodeWithRepair(
        raw: String,
        noinline onRepaired: ((Exception) -> Unit)? = null,
        decode: (String) -> T
    ): T {
        val cleaned = cleanJsonObject(raw)
        return try {
            decode(cleaned)
        } catch (firstError: Exception) {
            val repaired = repairMalformedJson(cleaned)
            if (repaired == cleaned) throw firstError
            val result = decode(repaired)
            onRepaired?.invoke(firstError)
            result
        }
    }

    /**
     * Repairs common local-LLM JSON syntax errors: raw control characters inside strings,
     * unescaped inner double quotes inside string values, trailing commas before `}` or `]`,
     * and truncated EOF strings/containers.
     */
    fun repairMalformedJson(json: String): String {
        val out = StringBuilder(json.length + 32)
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false

        for (i in json.indices) {
            val ch = json[i]
            if (!inString) {
                when (ch) {
                    '"' -> inString = true
                    '{' -> stack.addLast('}')
                    '[' -> stack.addLast(']')
                    '}', ']' -> if (stack.lastOrNull() == ch) stack.removeLast()
                }
                out.append(ch)
                continue
            }
            if (escaped) {
                out.append(ch)
                escaped = false
                continue
            }
            when (ch) {
                '\\' -> {
                    out.append(ch)
                    escaped = true
                }
                '\n' -> out.append("\\n")
                '\r' -> Unit
                '\t' -> out.append("\\t")
                '"' -> {
                    if (isValidClosingQuote(json, i)) {
                        inString = false
                        out.append(ch)
                    } else {
                        out.append("\\\"")
                    }
                }
                else -> out.append(ch)
            }
        }

        if (stack.isNotEmpty() && inString) {
            if (escaped && out.isNotEmpty()) {
                out.deleteCharAt(out.length - 1)
            }
            out.append('"')
        }

        var result = out.toString().replace(trailingCommaRegex, "$1")
        if (stack.isNotEmpty()) {
            result = closeTruncatedContainers(result, stack)
        }
        return result.replace(trailingCommaRegex, "$1")
    }

    private fun closeTruncatedContainers(partial: String, stack: ArrayDeque<Char>): String {
        var trimmed = partial.trimEnd()
        if (stack.lastOrNull() == '}') {
            val match = incompleteTrailingObjectKeyRegex.find(trimmed)
            if (match != null) {
                trimmed = trimmed.substring(0, match.range.first)
            }
        }
        trimmed = trimmed.trimEnd()
        if (trimmed.endsWith(':')) {
            trimmed = "$trimmed \"\""
        } else if (trimmed.endsWith(',')) {
            trimmed = trimmed.dropLast(1).trimEnd()
        }
        val sb = StringBuilder(trimmed.length + stack.size)
        sb.append(trimmed)
        while (stack.isNotEmpty()) {
            sb.append(stack.removeLast())
        }
        return sb.toString()
    }

    private fun isValidClosingQuote(json: String, quoteIndex: Int): Boolean {
        var j = quoteIndex + 1
        while (j < json.length && json[j].isWhitespace()) j++
        if (j >= json.length) return true
        val next = json[j]
        if (next == '}' || next == ']' || next == ':') return true
        if (next != ',') return false

        // A comma closes a string value cleanly if followed by a JSON object key, '{', '[', ']', or '}'.
        var k = j + 1
        while (k < json.length && json[k].isWhitespace()) k++
        if (k >= json.length) return true
        val afterComma = json[k]
        return afterComma == '"' || afterComma == '{' || afterComma == '[' || afterComma == ']' || afterComma == '}'
    }

    /**
     * Strips control characters and redacts embedded URLs/queries and local filesystem paths
     * from exception messages before logging or UI display.
     */
    fun sanitizeLogMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return "Unknown error"
        val urlRedacted = raw.replace(urlWithQueryOrCredentialsRegex) { match ->
            val host = runCatching { java.net.URI(match.value).host }.getOrNull()
            if (host.isNullOrBlank()) "[redacted-url]" else "https://$host/..."
        }
        val pathRedacted = urlRedacted.replace(localFilesystemPathRegex, "[redacted-path]")
        val secretsRedacted = pathRedacted
            .replace(bearerTokenRegex, "Bearer [redacted-token]")
            .replace(secretKeyPrefixRegex, "[redacted-api-key]")
            .replace(hexKey64Regex, "[redacted-hex-key]")
        return secretsRedacted.replace(controlOrAnsiRegex, " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .take(MAX_LOG_MESSAGE_CHARS)
            .ifBlank { "Unknown error" }
    }
}
