package com.wechatdigest.core.sanitizer

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Matching mode used by a static message prefilter rule.
 */
@Serializable
enum class PrefilterMatchType(val displayName: String) {
    WHOLE_MESSAGE("Whole message"),
    CONTAINS("Contains"),
    STARTS_WITH("Starts with"),
    REGEX("Regex")
}

/**
 * Single configurable static rule determining whether a message should be skipped before LLM extraction.
 */
@Serializable
data class PrefilterRule(
    val id: String = UUID.randomUUID().toString(),
    val pattern: String,
    val matchType: PrefilterMatchType = PrefilterMatchType.WHOLE_MESSAGE,
    val enabled: Boolean = true,
    val label: String = ""
)

/**
 * Global static prefiltering configuration persisted inside [com.wechatdigest.app.AppSettings].
 */
@Serializable
data class PrefilterConfig(
    val minMessageLength: Int = DEFAULT_MIN_MESSAGE_LENGTH,
    val rules: List<PrefilterRule> = defaultRules()
) {
    companion object {
        const val DEFAULT_MIN_MESSAGE_LENGTH = 4

        /**
         * Returns the seeded built-in rules covering XML payloads, WeChat bracket tags, and short confirmations.
         */
        fun defaultRules(): List<PrefilterRule> = listOf(
            PrefilterRule(
                id = "builtin-xml-msg",
                pattern = "<msg",
                matchType = PrefilterMatchType.STARTS_WITH,
                label = "WeChat XML card payloads"
            ),
            PrefilterRule(
                id = "builtin-xml-header",
                pattern = "<?xml",
                matchType = PrefilterMatchType.STARTS_WITH,
                label = "Raw XML payloads"
            ),
            PrefilterRule(
                id = "builtin-zstd-prefix",
                pattern = "~se",
                matchType = PrefilterMatchType.STARTS_WITH,
                label = "Compressed binary prefixes"
            ),
            PrefilterRule(
                id = "builtin-bracket-tag",
                pattern = "^\\[[^\\]]+\\]$",
                matchType = PrefilterMatchType.REGEX,
                label = "Bracket tags & stickers (e.g. [Sticker], [强])"
            ),
            PrefilterRule(
                id = "builtin-banter-confirmation",
                pattern = "^(?:[+＋\\d收到好的谢感谢辛苦老师OK👍🙏👏👌握手强庆祝\\s]+|完成了|已完成|已提交|已填写|填好了|交了|done|completed)[.,!?;:~。，！？；：、～…\\s]*$",
                matchType = PrefilterMatchType.REGEX,
                label = "Common Chinese, English & emoji confirmations"
            ),
            PrefilterRule(
                id = "builtin-word-received",
                pattern = "received",
                matchType = PrefilterMatchType.WHOLE_MESSAGE,
                label = "Short confirmation: received"
            ),
            PrefilterRule(
                id = "builtin-word-noted",
                pattern = "noted",
                matchType = PrefilterMatchType.WHOLE_MESSAGE,
                label = "Short confirmation: noted"
            ),
            PrefilterRule(
                id = "builtin-word-shoudao",
                pattern = "收到",
                matchType = PrefilterMatchType.WHOLE_MESSAGE,
                label = "Short confirmation: 收到"
            )
        )
    }
}
