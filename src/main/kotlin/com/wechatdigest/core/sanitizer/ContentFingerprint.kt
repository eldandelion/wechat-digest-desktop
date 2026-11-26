package com.wechatdigest.core.sanitizer

import com.wechatdigest.core.model.WeChatMessage
import java.security.MessageDigest
import java.util.Locale

/**
 * Deterministic normalized content fingerprint used to suppress duplicate forwarded notices
 * across monitored chat rooms before invoking the LLM.
 */
object ContentFingerprint {

    private const val MIN_NORMALIZED_CHARS = 20

    private val LINE_HEADER_REGEX = Regex(
        "^(?:\\[id:\\d+]\\s*)?\\[\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}]\\s+[^:\\n]+:\\s*"
    )
    private val CONTEXT_PREFIX_REGEX = Regex("(?m)^\\[Context:[^\\n]*?]\\s*")
    private val STATUS_CARRYING_CODEPOINTS = setOf(0x2705, 0x274C, 0x26A0, 0x1F6AB)
    private val MENTION_REGEX = Regex(
        "(?:^|(?<=[\\s\\u2005\\u00A0]))@(?:所有人|全体成员|All)(?=$|[\\s\\u2005\\u00A0,.!?;:，。！？；：])",
        RegexOption.IGNORE_CASE
    )
    private val COLLAPSE_NOISE_REGEX = Regex("[\\s\\u2005\\u00A0\\u200B-\\u200F\\uFEFF,.!?;:，。！？；：、～~\"'“”‘’…()（）\\[\\]【】]+")

    /**
     * Computes a SHA-256 fingerprint over normalized [textContent] and sorted [attachmentMd5s],
     * stripping per-room timestamps, sender headers, `@All` mentions, emojis, and whitespace.
     * Returns an empty string when both normalized text is below [MIN_NORMALIZED_CHARS] and [attachmentMd5s] is empty.
     */
    fun compute(textContent: String, attachmentMd5s: List<String> = emptyList()): String {
        val cleanMd5s = attachmentMd5s
            .asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

        val normalizedText = normalizeBody(textContent)
        if (normalizedText.length < MIN_NORMALIZED_CHARS && cleanMd5s.isEmpty()) {
            return ""
        }

        val payload = "${normalizedText}|${cleanMd5s.joinToString(",")}"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Computes a combined normalized fingerprint across all [messages] backing an extracted card. */
    fun computeForMessages(messages: List<WeChatMessage>): String {
        if (messages.isEmpty()) return ""
        val combinedText = messages.joinToString("\n") { it.textContent }
        val md5s = messages.flatMap { msg ->
            msg.attachments.mapNotNull { att -> att.md5.takeIf { it.isNotBlank() } }
        }
        return compute(combinedText, md5s)
    }

    internal fun normalizeBody(rawText: String): String {
        if (rawText.isBlank()) return ""
        val strippedLines = rawText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { line ->
                line.replace(CONTEXT_PREFIX_REGEX, "")
                    .replace(LINE_HEADER_REGEX, "")
                    .replace(CONTEXT_PREFIX_REGEX, "")
                    .replace(MENTION_REGEX, " ")
            }
            .joinToString(" ")

        val noEmojis = buildString(strippedLines.length) {
            var i = 0
            while (i < strippedLines.length) {
                val cp = strippedLines.codePointAt(i)
                val charCount = Character.charCount(cp)
                if (!isEmojiOrModifier(cp)) {
                    appendCodePoint(cp)
                }
                i += charCount
            }
        }

        return noEmojis
            .replace(COLLAPSE_NOISE_REGEX, "")
            .lowercase(Locale.ROOT)
    }

    private fun isEmojiOrModifier(cp: Int): Boolean {
        if (cp in STATUS_CARRYING_CODEPOINTS) return false
        return cp in 0x1F300..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x1F1E6..0x1F1FF ||
            cp in 0x1F3FB..0x1F3FF ||
            cp == 0x20E3 ||
            cp == 0xFE0E ||
            cp == 0xFE0F ||
            cp == 0x200D
    }
}
