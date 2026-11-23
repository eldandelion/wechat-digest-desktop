package com.wechatdigest.core.llm

import com.wechatdigest.core.model.EventCard
import java.lang.Character.UnicodeScript
import java.util.Locale

/** Result of deterministic target-language compliance verification on an [EventCard]. */
data class LanguageComplianceResult(
    val isCompliant: Boolean,
    val nonCompliantFields: List<String> = emptyList()
)

/**
 * Resolves ISO language codes (e.g., `"en"`, `"zh-CN"`, `"ja"`) into full English names
 * and performs fast deterministic Unicode script verification on extracted [EventCard]s.
 */
object LanguageHelper {

    private const val MAX_COMPLIANCE_TEXT_LENGTH = 8_192

    private enum class ScriptProfile {
        LATIN,
        CHINESE,
        JAPANESE,
        KOREAN,
        CYRILLIC,
        ARABIC,
        THAI
    }

    private val urlRegex = Regex("https?://[^\\s<>]{1,512}", RegexOption.IGNORE_CASE)
    private val emailRegex = Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}")
    private val timestampRegex = Regex("\\[\\d{4}-\\d{2}-\\d{2}[^\\]]{0,32}\\]")
    // Strips isolated short technical codes (e.g. PDF, AI, CSE) without masking consecutive ALL-CAPS English phrases.
    private val shortUpperAcronymRegex = Regex("(?<![A-Za-z]\\s)\\b[A-Z0-9]{2,5}\\b(?!\\s+[A-Z]{2,})")
    private val sourceLineSenderRegex = Regex("(?m)^\\[[^\\]]{1,32}\\]\\s+([^:\\r\\n]{2,64}):")
    private val asciiWordRegex = Regex("^[A-Za-z0-9_.-]+$")
    private val unsafeTargetCharsRegex = Regex("[^A-Za-z0-9 ()_-]+")
    private val identifierSplitRegex = Regex("""[\s,;/()]+""")

    private val knownLanguages = mapOf(
        "en" to "English",
        "en-us" to "English",
        "en-gb" to "English",
        "en-au" to "English",
        "english" to "English",
        "zh" to "Simplified Chinese",
        "zh-cn" to "Simplified Chinese",
        "zh-hans" to "Simplified Chinese",
        "zh-sg" to "Simplified Chinese",
        "simplified chinese" to "Simplified Chinese",
        "chinese" to "Simplified Chinese",
        "zh-tw" to "Traditional Chinese",
        "zh-hant" to "Traditional Chinese",
        "zh-hk" to "Traditional Chinese",
        "zh-mo" to "Traditional Chinese",
        "traditional chinese" to "Traditional Chinese",
        "ja" to "Japanese",
        "japanese" to "Japanese",
        "ko" to "Korean",
        "korean" to "Korean",
        "fr" to "French",
        "french" to "French",
        "de" to "German",
        "german" to "German",
        "es" to "Spanish",
        "spanish" to "Spanish",
        "ru" to "Russian",
        "russian" to "Russian",
        "ar" to "Arabic",
        "arabic" to "Arabic",
        "pt" to "Portuguese",
        "pt-br" to "Portuguese",
        "portuguese" to "Portuguese",
        "it" to "Italian",
        "italian" to "Italian",
        "vi" to "Vietnamese",
        "vietnamese" to "Vietnamese",
        "th" to "Thai",
        "thai" to "Thai"
    )

    private val scriptProfiles = mapOf(
        "English" to ScriptProfile.LATIN,
        "French" to ScriptProfile.LATIN,
        "German" to ScriptProfile.LATIN,
        "Spanish" to ScriptProfile.LATIN,
        "Portuguese" to ScriptProfile.LATIN,
        "Italian" to ScriptProfile.LATIN,
        "Vietnamese" to ScriptProfile.LATIN,
        "Simplified Chinese" to ScriptProfile.CHINESE,
        "Traditional Chinese" to ScriptProfile.CHINESE,
        "Japanese" to ScriptProfile.JAPANESE,
        "Korean" to ScriptProfile.KOREAN,
        "Russian" to ScriptProfile.CYRILLIC,
        "Arabic" to ScriptProfile.ARABIC,
        "Thai" to ScriptProfile.THAI
    )

    /**
     * Resolves an ISO code or user-entered language name to a safe, prompt-ready English language label.
     */
    fun resolveTargetLanguageName(codeOrName: String): String {
        val trimmed = codeOrName.trim()
        if (trimmed.isBlank() || trimmed.contains('\n') || trimmed.contains('\r') || trimmed.contains(':')) {
            return "English"
        }

        val normalized = trimmed.lowercase(Locale.ROOT).replace('_', '-')
        knownLanguages[normalized]?.let { return it }

        val looksLikeLanguageTag = normalized.matches(Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$"))
        if (looksLikeLanguageTag) {
            val parts = normalized.split('-')
            val base = parts.first()
            if (base == "zh") {
                if (parts.any { it == "hant" || it == "tw" || it == "hk" || it == "mo" }) {
                    return "Traditional Chinese"
                }
                if (parts.any { it == "hans" || it == "cn" || it == "sg" }) {
                    return "Simplified Chinese"
                }
            }
            knownLanguages[base]?.let { return it }

            val display = Locale.forLanguageTag(normalized).getDisplayLanguage(Locale.ENGLISH)
            if (display.isNotBlank() && !display.equals(normalized, ignoreCase = true)) {
                return display
            }
        }

        val sanitized = trimmed.replace(unsafeTargetCharsRegex, " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .take(40)
        return sanitized.ifBlank { "English" }
    }

    /**
     * Checks deterministically whether [card] title, summary, and sections match [targetLanguage]
     * using JVM `Character.UnicodeScript` heuristics after stripping URLs, emails, timestamps, and sender names.
     */
    fun checkCardCompliance(card: EventCard, targetLanguage: String): LanguageComplianceResult {
        val profile = scriptProfiles[resolveTargetLanguageName(targetLanguage)]
            ?: return LanguageComplianceResult(isCompliant = true)

        val exemptPatterns = buildExemptSenderPatterns(card)
        val flagged = mutableListOf<String>()

        if (!isTextCompliant(card.title, profile, exemptPatterns)) {
            flagged.add("title")
        }
        val effectiveSummary = card.translatedSummary.ifBlank { card.summary }
        if (!isTextCompliant(effectiveSummary, profile, exemptPatterns)) {
            flagged.add("summary")
        }
        card.sections.forEachIndexed { idx, section ->
            if (section.heading.isNotBlank() && !isTextCompliant(section.heading, profile, exemptPatterns)) {
                flagged.add("section[$idx].heading")
            }
            if (section.content.isNotBlank() && !isTextCompliant(section.content, profile, exemptPatterns)) {
                flagged.add("section[$idx].content")
            }
            section.items.forEachIndexed { itemIdx, item ->
                if (item.label.isNotBlank() && !isTextCompliant(item.label, profile, exemptPatterns)) {
                    flagged.add("section[$idx].item[$itemIdx].label")
                }
                val checkValue = item.value.isNotBlank() &&
                    !(profile != ScriptProfile.LATIN && isShortAsciiIdentifier(item.value, exemptPatterns))
                if (checkValue && !isTextCompliant(item.value, profile, exemptPatterns)) {
                    flagged.add("section[$idx].item[$itemIdx].value")
                }
            }
            section.bullets.forEachIndexed { bulletIdx, bullet ->
                if (bullet.isNotBlank() && !isTextCompliant(bullet, profile, exemptPatterns)) {
                    flagged.add("section[$idx].bullet[$bulletIdx]")
                }
            }
        }

        return LanguageComplianceResult(
            isCompliant = flagged.isEmpty(),
            nonCompliantFields = flagged.toList()
        )
    }

    private fun buildExemptSenderPatterns(card: EventCard): List<Regex> {
        val senders = linkedSetOf<String>()
        card.originalSender.trim().takeIf { it.length >= 3 }?.let { senders.add(it) }
        if (card.sourceMessages.isNotBlank()) {
            sourceLineSenderRegex.findAll(card.sourceMessages.take(MAX_COMPLIANCE_TEXT_LENGTH))
                .take(16)
                .forEach { match ->
                    match.groupValues[1].trim().takeIf { it.length >= 3 }?.let { senders.add(it) }
                }
        }
        return senders.map { sender ->
            if (asciiWordRegex.matches(sender)) {
                Regex("\\b${Regex.escape(sender)}\\b", RegexOption.IGNORE_CASE)
            } else {
                Regex(Regex.escape(sender), RegexOption.IGNORE_CASE)
            }
        }
    }

    private fun isShortAsciiIdentifier(value: String, exemptPatterns: List<Regex>): Boolean {
        val cleaned = stripNonLinguisticTokens(value.take(MAX_COMPLIANCE_TEXT_LENGTH), exemptPatterns, stripShortAcronyms = true)
        if (cleaned.isBlank()) return true
        if (!cleaned.all { it.code < 128 }) return false
        val words = cleaned.split(identifierSplitRegex).filter { token -> token.any { it.isLetter() } }
        return words.size <= 2
    }

    private fun isTextCompliant(
        text: String,
        profile: ScriptProfile,
        exemptPatterns: List<Regex>
    ): Boolean {
        val cleaned = stripNonLinguisticTokens(
            text = text.take(MAX_COMPLIANCE_TEXT_LENGTH),
            exemptPatterns = exemptPatterns,
            stripShortAcronyms = profile != ScriptProfile.LATIN
        )
        if (cleaned.isBlank()) return true

        val counts = countScripts(cleaned)
        if (counts.total < 2) return true

        return when (profile) {
            ScriptProfile.LATIN -> {
                val foreign = counts.total - counts.latin
                foreign == 0 || (counts.latin > 0 && foreign <= 12 && foreign.toDouble() / counts.total <= 0.12)
            }
            ScriptProfile.CHINESE -> {
                val otherNonLatin = counts.kana + counts.hangul + counts.cyrillic + counts.arabic + counts.thai + counts.other
                otherNonLatin < 3 && counts.han > 0 && counts.han >= counts.latin && counts.han.toDouble() / counts.total >= 0.45
            }
            ScriptProfile.JAPANESE -> {
                val other = counts.hangul + counts.cyrillic + counts.arabic + counts.thai + counts.other
                val hasKana = counts.kana > 0
                val mostlyLatin = counts.latin > counts.han + counts.kana && counts.latin.toDouble() / counts.total > 0.45
                val pureHanCompound = !hasKana && counts.han >= 5
                other < 3 && !mostlyLatin && !pureHanCompound && (hasKana || counts.han >= counts.latin)
            }
            ScriptProfile.KOREAN -> counts.hangul > 0 && counts.hangul.toDouble() / counts.total >= 0.50
            ScriptProfile.CYRILLIC -> counts.cyrillic.toDouble() / counts.total >= 0.50
            ScriptProfile.ARABIC -> counts.arabic.toDouble() / counts.total >= 0.50
            ScriptProfile.THAI -> counts.thai.toDouble() / counts.total >= 0.50
        }
    }

    private fun stripNonLinguisticTokens(
        text: String,
        exemptPatterns: List<Regex>,
        stripShortAcronyms: Boolean
    ): String {
        var stripped = text
            .replace(urlRegex, " ")
            .replace(emailRegex, " ")
            .replace(timestampRegex, " ")
        for (pattern in exemptPatterns) {
            stripped = stripped.replace(pattern, " ")
        }
        if (stripShortAcronyms) {
            stripped = stripped.replace(shortUpperAcronymRegex, " ")
        }
        return stripped.trim()
    }

    private data class ScriptCounts(
        val latin: Int = 0,
        val han: Int = 0,
        val kana: Int = 0,
        val hangul: Int = 0,
        val cyrillic: Int = 0,
        val arabic: Int = 0,
        val thai: Int = 0,
        val other: Int = 0
    ) {
        val total: Int get() = latin + han + kana + hangul + cyrillic + arabic + thai + other
    }

    private fun countScripts(text: String): ScriptCounts {
        var latin = 0
        var han = 0
        var kana = 0
        var hangul = 0
        var cyrillic = 0
        var arabic = 0
        var thai = 0
        var other = 0

        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue

            when (UnicodeScript.of(cp)) {
                UnicodeScript.LATIN -> latin++
                UnicodeScript.HAN -> han++
                UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA -> kana++
                UnicodeScript.HANGUL -> hangul++
                UnicodeScript.CYRILLIC -> cyrillic++
                UnicodeScript.ARABIC -> arabic++
                UnicodeScript.THAI -> thai++
                UnicodeScript.COMMON, UnicodeScript.INHERITED -> Unit
                else -> other++
            }
        }
        return ScriptCounts(
            latin = latin,
            han = han,
            kana = kana,
            hangul = hangul,
            cyrillic = cyrillic,
            arabic = arabic,
            thai = thai,
            other = other
        )
    }
}
