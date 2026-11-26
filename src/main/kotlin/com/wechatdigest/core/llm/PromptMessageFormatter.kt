package com.wechatdigest.core.llm

import com.wechatdigest.core.model.WeChatMessage
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Formats chat transcripts and optional bounded attachment previews for LLM prompt submission
 * and clean `EventCard.sourceMessages` persistence.
 */
internal object PromptMessageFormatter {

    fun formatMessages(
        messages: List<WeChatMessage>,
        timeFormatter: DateTimeFormatter,
        maxSingleMessageChars: Int,
        includeIds: Boolean,
        sanitizeForPrompt: Boolean,
        includeAttachmentPreviews: Boolean,
        truncateWithEllipsis: Boolean = false,
        maxTotalEntryChars: Int? = null
    ): String {
        return buildString {
            for (msg in messages) {
                val entry = buildString {
                    appendFormattedMessage(
                        sb = this,
                        msg = msg,
                        timeFormatter = timeFormatter,
                        maxSingleMessageChars = maxSingleMessageChars,
                        includeIds = includeIds,
                        sanitizeForPrompt = sanitizeForPrompt,
                        includeAttachmentPreviews = includeAttachmentPreviews,
                        truncateWithEllipsis = truncateWithEllipsis
                    )
                }.trimEnd()
                val bounded = if (maxTotalEntryChars != null && entry.length > maxTotalEntryChars) {
                    truncateWithEllipsis(entry, maxTotalEntryChars)
                } else {
                    entry
                }
                append(bounded).append("\n\n")
            }
        }.trim()
    }

    private fun truncateWithEllipsis(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        if (maxChars <= 3) return text.take(maxChars)
        return text.take(maxChars - 3).trimEnd() + "..."
    }

    private fun appendFormattedMessage(
        sb: StringBuilder,
        msg: WeChatMessage,
        timeFormatter: DateTimeFormatter,
        maxSingleMessageChars: Int,
        includeIds: Boolean,
        sanitizeForPrompt: Boolean,
        includeAttachmentPreviews: Boolean,
        truncateWithEllipsis: Boolean
    ) {
        val timeStr = timeFormatter.format(Instant.ofEpochSecond(msg.timestampSeconds))
        val sanitizedBaseSender = ExtractionPrompts.sanitizeInline(msg.senderDisplayName, 56)
            .replace(Regex("(?i)\\[\\s*owner\\s*\\]"), "")
            .replace("[", "(")
            .replace("]", ")")
            .trim()
            .ifBlank { "Unknown" }
        val senderHeader = if (msg.isOwner) {
            ExtractionPrompts.sanitizeInline("$sanitizedBaseSender [Owner]", 64)
        } else {
            sanitizedBaseSender
        }
        val quotePrefix = if (sanitizeForPrompt && msg.quoteContext.isNotBlank()) {
            "[Context: ${ExtractionPrompts.sanitizeInline(msg.quoteContext, 200)}]\n"
        } else if (!sanitizeForPrompt && msg.quoteContext.isNotBlank()) {
            "[${msg.quoteContext}]\n"
        } else {
            ""
        }
        val combinedRawBody = quotePrefix + msg.textContent
        val sliced = if (truncateWithEllipsis) {
            truncateWithEllipsis(combinedRawBody, maxSingleMessageChars)
        } else {
            combinedRawBody.take(maxSingleMessageChars)
        }
        val body = if (sanitizeForPrompt) {
            ExtractionPrompts.sanitizeTranscriptForPrompt(sliced)
        } else {
            sliced
        }
        if (includeIds) sb.append("[id:${msg.localId}] ")
        sb.append("[$timeStr] $senderHeader: $body")
        if (includeAttachmentPreviews && msg.attachmentPreviews.isNotEmpty()) {
            appendAttachmentPreviews(sb, msg, sanitizeForPrompt, maxSingleMessageChars, truncateWithEllipsis)
        }
    }

    private fun appendAttachmentPreviews(
        sb: StringBuilder,
        msg: WeChatMessage,
        sanitizeForPrompt: Boolean,
        maxPreviewChars: Int,
        truncateWithEllipsis: Boolean
    ) {
        for (attachment in msg.attachments) {
            val rawPreview = msg.attachmentPreviews[attachment.previewKey()] ?: continue
            val preview = if (truncateWithEllipsis) truncateWithEllipsis(rawPreview, maxPreviewChars) else rawPreview
            val safeName = ExtractionPrompts.sanitizeInline(attachment.fileName, 120)
                .replace("\"", "'")
                .replace("<", "")
                .replace(">", "")
            val safePreview = if (sanitizeForPrompt) {
                ExtractionPrompts.sanitizeTranscriptForPrompt(preview)
            } else {
                preview
            }
            sb.append("\n<attachment_content file=\"$safeName\">\n$safePreview\n</attachment_content>")
        }
    }
}
