package com.wechatdigest.core.sanitizer

import com.wechatdigest.core.model.WeChatMessage
import java.util.Collections

object MessageSanitizer {

    private const val MAX_REGEX_CACHE_SIZE = 256
    private const val MAX_REGEX_INPUT_LENGTH = 8_192
    private const val MAX_REGEX_CHAR_ACCESSES = 200_000

    private val EDGE_PUNCTUATION = setOf(
        '.', ',', '!', '?', ';', ':', '~',
        '。', '，', '！', '？', '；', '：', '、', '～',
        '"', '\'', '“', '”', '‘', '’', '…'
    )

    // Bounded LRU cache for compiled regex patterns to avoid unbounded growth and repeated compilation.
    private val regexCache: MutableMap<String, Result<Regex>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Result<Regex>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<Regex>>?): Boolean {
                return size > MAX_REGEX_CACHE_SIZE
            }
        }
    )

    private val STANDALONE_MENTION_REGEX = Regex(
        "(?:^|(?<=[\\s\\u2005\\u00A0]))@(?:所有人|全体成员|All)(?=$|[\\s\\u2005\\u00A0,.!?;:，。！？；：])",
        RegexOption.IGNORE_CASE
    )

    /**
     * Determines whether a message content has substantive information worth submitting to the LLM
     * according to the active [PrefilterConfig].
     */
    fun isSubstantive(content: String, config: PrefilterConfig = PrefilterConfig()): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false

        // Strip emojis and bare @All/@所有人 mentions when judging residual substance and length
        val residual = stripDecorations(trimmed)
        if (residual.isEmpty()) return false

        if (config.minMessageLength > 0 && residual.length < config.minMessageLength) {
            return false
        }

        val normalizedRaw = normalizeWholeMessage(trimmed)
        val normalizedResidual = normalizeWholeMessage(residual)
        if (config.rules.any { rule ->
                matchesRule(trimmed, normalizedRaw, rule) ||
                    (residual != trimmed && matchesRule(residual, normalizedResidual, rule))
            }
        ) {
            return false
        }

        return true
    }

    private fun stripDecorations(text: String): String {
        val withoutMentions = text.replace(STANDALONE_MENTION_REGEX, " ")
        val sb = StringBuilder(withoutMentions.length)
        var i = 0
        while (i < withoutMentions.length) {
            val cp = withoutMentions.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (!isEmojiOrModifier(cp)) {
                sb.appendCodePoint(cp)
            }
            i += charCount
        }
        return sb.toString().trim()
    }

    private fun isEmojiOrModifier(cp: Int): Boolean {
        return cp in 0x1F300..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x1F1E6..0x1F1FF ||
            cp in 0x1F3FB..0x1F3FF ||
            cp == 0x20E3 ||
            cp == 0xFE0E ||
            cp == 0xFE0F ||
            cp == 0x200D
    }

    /**
     * Validates a candidate rule pattern before saving; returns null if valid or a user-facing error message.
     */
    fun validateRulePattern(pattern: String, matchType: PrefilterMatchType): String? {
        if (pattern.isBlank()) {
            return "Pattern cannot be empty."
        }
        if (matchType == PrefilterMatchType.REGEX) {
            val error = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.exceptionOrNull()
            if (error != null) {
                return "Invalid regex: ${error.message ?: "syntax error"}"
            }
        }
        return null
    }

    /**
     * Cleans internal message text by trimming surrounding whitespaces and normalizing line breaks.
     */
    fun cleanContent(rawText: String): String {
        return rawText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
    }

    /**
     * Filters a list of messages, stripping noise and returning only substantive announcements and queries.
     */
    fun filterSubstantiveMessages(
        messages: List<WeChatMessage>,
        config: PrefilterConfig = PrefilterConfig()
    ): List<WeChatMessage> {
        return messages.filter { msg ->
            msg.attachments.isNotEmpty() || isSubstantive(msg.textContent, config)
        }
    }

    private fun normalizeWholeMessage(text: String): String {
        return text.trim().trim { ch -> ch.isWhitespace() || ch in EDGE_PUNCTUATION }
    }

    private fun matchesRule(
        trimmed: String,
        normalizedWhole: String,
        rule: PrefilterRule
    ): Boolean {
        if (!rule.enabled || rule.pattern.isBlank()) return false

        return when (rule.matchType) {
            PrefilterMatchType.WHOLE_MESSAGE -> {
                val target = normalizeWholeMessage(rule.pattern)
                normalizedWhole.isNotEmpty() &&
                    target.isNotEmpty() &&
                    normalizedWhole.equals(target, ignoreCase = true)
            }
            PrefilterMatchType.CONTAINS -> {
                trimmed.contains(rule.pattern.trim(), ignoreCase = true)
            }
            PrefilterMatchType.STARTS_WITH -> {
                trimmed.startsWith(rule.pattern.trim(), ignoreCase = true)
            }
            PrefilterMatchType.REGEX -> {
                val compiled = synchronized(regexCache) {
                    regexCache.getOrPut(rule.pattern) {
                        runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }
                    }
                }
                val regex = compiled.getOrNull() ?: return false
                val boundedInput = if (trimmed.length > MAX_REGEX_INPUT_LENGTH) trimmed.take(MAX_REGEX_INPUT_LENGTH) else trimmed
                runCatching { regex.matches(StepLimitedCharSequence(boundedInput, MAX_REGEX_CHAR_ACCESSES)) }
                    .getOrDefault(false)
            }
        }
    }

    private class StepLimitExceededException : RuntimeException()

    private class StepLimitedCharSequence(
        private val delegate: CharSequence,
        private val maxAccesses: Int
    ) : CharSequence {
        private var accesses = 0

        override val length: Int get() = delegate.length

        override fun get(index: Int): Char {
            if (++accesses > maxAccesses) throw StepLimitExceededException()
            return delegate[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            return StepLimitedCharSequence(delegate.subSequence(startIndex, endIndex), maxAccesses - accesses)
        }

        override fun toString(): String = delegate.toString()
    }
}
