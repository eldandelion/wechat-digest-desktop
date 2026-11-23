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
        includeAttachmentPreviews: Boolean
    ): String {
        return buildString {
            for (msg in messages) {
                appendFormattedMessage(
                    sb = this,
                    msg = msg,
                    timeFormatter = timeFormatter,
                    maxSingleMessageChars = maxSingleMessageChars,
                    includeIds = includeIds,
                    sanitizeForPrompt = sanitizeForPrompt,
                    includeAttachmentPreviews = includeAttachmentPreviews
                )
            }
        }.trim()
    }

    private fun appendFormattedMessage(
        sb: StringBuilder,
        msg: WeChatMessage,
        timeFormatter: DateTimeFormatter,
        maxSingleMessageChars: Int,
        includeIds: Boolean,
        sanitizeForPrompt: Boolean,
        includeAttachmentPreviews: Boolean
    ) {
        val timeStr = timeFormatter.format(Instant.ofEpochSecond(msg.timestampSeconds))
        val safeSender = ExtractionPrompts.sanitizeInline(msg.senderDisplayName, 64).ifBlank { "Unknown" }
        val body = if (sanitizeForPrompt) {
            ExtractionPrompts.sanitizeTranscriptForPrompt(msg.textContent.take(maxSingleMessageChars))
        } else {
            msg.textContent
        }
        if (includeIds) sb.append("[id:${msg.localId}] ")
        sb.append("[$timeStr] $safeSender: $body")
        if (includeAttachmentPreviews && msg.attachmentPreviews.isNotEmpty()) {
            appendAttachmentPreviews(sb, msg, sanitizeForPrompt)
        }
        sb.append("\n\n")
    }

    private fun appendAttachmentPreviews(
        sb: StringBuilder,
        msg: WeChatMessage,
        sanitizeForPrompt: Boolean
    ) {
        for (attachment in msg.attachments) {
            val preview = msg.attachmentPreviews[attachment.previewKey()] ?: continue
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
