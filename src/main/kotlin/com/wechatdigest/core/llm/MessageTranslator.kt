package com.wechatdigest.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class TranslationResponse(
    @SerialName("translated_text") val translatedText: String = ""
)

/**
 * Translates raw WeChat source message transcripts on demand using the configured [LlmClient].
 */
class MessageTranslator(private val llmClient: LlmClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun translateMessages(sourceMessages: String, targetLanguage: String): Result<String> {
        if (sourceMessages.isBlank()) {
            return Result.failure(IllegalArgumentException("No source messages to translate."))
        }

        val targetLangName = LanguageHelper.resolveTargetLanguageName(targetLanguage)
        val systemPrompt = buildSystemPrompt(targetLangName)
        val userPrompt = "Translate the following group chat messages completely into $targetLangName:\n\n$sourceMessages"

        return try {
            val rawResponse = llmClient.chatCompletion(systemPrompt, userPrompt)
            val parsed = parseTranslationOutput(rawResponse)
            if (parsed.isBlank()) {
                Result.failure(IllegalStateException("LLM returned an empty translation."))
            } else {
                Result.success(parsed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun buildSystemPrompt(targetLangName: String): String {
        return """
You are a precise translator for WeChat group chat messages.
Translate the provided message transcript faithfully and completely into $targetLangName.

REQUIREMENTS:
- Translate EVERY message line completely into $targetLangName without summarizing or omitting details.
- Preserve [YYYY-MM-DD HH:MM:SS] timestamps, speaker names, URLs, room numbers, codes, and numerical details.
- Preserve line breaks between separate messages.
- Return a pure JSON object matching this exact schema:
{
  "translated_text": "Full faithful translation into $targetLangName"
}
        """.trimIndent()
    }

    private fun parseTranslationOutput(raw: String): String {
        var unwrapped = raw.trim()
        if (unwrapped.startsWith("```json")) {
            unwrapped = unwrapped.removePrefix("```json")
        } else if (unwrapped.startsWith("```")) {
            unwrapped = unwrapped.removePrefix("```")
        }
        if (unwrapped.endsWith("```")) {
            unwrapped = unwrapped.removeSuffix("```")
        }
        unwrapped = unwrapped.trim()
        if (unwrapped.isBlank()) return ""

        val startIdx = unwrapped.indexOf('{')
        val endIdx = unwrapped.lastIndexOf('}')
        if (startIdx != -1 && endIdx > startIdx) {
            val jsonCandidate = unwrapped.substring(startIdx, endIdx + 1)
            val decoded = runCatching {
                json.decodeFromString<TranslationResponse>(jsonCandidate)
            }.getOrNull()
            if (decoded != null) {
                return decoded.translatedText.trim()
            }
        }

        return unwrapped
    }
}
