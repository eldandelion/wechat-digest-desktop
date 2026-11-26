package com.wechatdigest.core.attachment

import com.wechatdigest.core.model.EventAttachment
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Structured outcome of parsing a WeChat XML / system message payload. */
sealed interface ParsedWeChatMessage {
    data class FileAttachment(
        val attachment: EventAttachment,
        val markerText: String
    ) : ParsedWeChatMessage

    data class QuoteReply(
        val replyText: String,
        val quoteSummary: String,
        val quotedSender: String,
        val quotedServerId: Long?
    ) : ParsedWeChatMessage

    data class LinkCard(
        val title: String,
        val description: String,
        val url: String?,
        val sourceName: String,
        val displayText: String
    ) : ParsedWeChatMessage

    data class MiniProgram(
        val appName: String,
        val title: String,
        val description: String,
        val pagePath: String,
        val displayText: String
    ) : ParsedWeChatMessage

    data class ForwardedRecord(
        val title: String,
        val formattedItems: String,
        val displayText: String
    ) : ParsedWeChatMessage

    data class GroupAnnouncement(
        val body: String,
        val displayText: String
    ) : ParsedWeChatMessage

    data class RevokeSysMsg(
        val recalledServerId: Long?
    ) : ParsedWeChatMessage

    data object IgnoredSysMsg : ParsedWeChatMessage

    /** Resolved message-level fields extracted from a [ParsedWeChatMessage]. */
    data class ResolvedXmlFields(
        val textContent: String,
        val attachments: List<EventAttachment> = emptyList(),
        val isSystemMessage: Boolean = false,
        val recalledServerId: Long? = null,
        val quoteContext: String = "",
        val extractedUrls: List<String> = emptyList()
    )

    fun toMessageFields(fallbackText: String): ResolvedXmlFields = when (this) {
        is FileAttachment -> ResolvedXmlFields(textContent = markerText, attachments = listOf(attachment))
        is QuoteReply -> ResolvedXmlFields(textContent = replyText, quoteContext = quoteSummary)
        is LinkCard -> ResolvedXmlFields(textContent = displayText, extractedUrls = listOfNotNull(url))
        is MiniProgram -> ResolvedXmlFields(textContent = displayText)
        is ForwardedRecord -> ResolvedXmlFields(textContent = displayText)
        is GroupAnnouncement -> ResolvedXmlFields(textContent = displayText)
        is RevokeSysMsg -> ResolvedXmlFields(textContent = fallbackText, isSystemMessage = true, recalledServerId = recalledServerId)
        IgnoredSysMsg -> ResolvedXmlFields(textContent = fallbackText, isSystemMessage = true)
    }
}

/**
 * Unified XXE-hardened WeChat XML & system-message parser handling `appmsg` subtypes
 * `6` (file), `57` (quote-reply), `5` (article/link), `33`/`36` (mini-program),
 * `19` (forwarded record), `87` (group announcement), plus `10000`/`10002` sysmsgs & recalls.
 */
object WeChatXmlMessageParser {

    const val BASE_TYPE_APPMSG = 49L
    const val APPMSG_SUBTYPE_LINK = 5L
    const val APPMSG_SUBTYPE_FILE = 6L
    const val APPMSG_SUBTYPE_FORWARD = 19L
    const val APPMSG_SUBTYPE_MINI_PROGRAM = 33L
    const val APPMSG_SUBTYPE_MINI_PROGRAM_ALT = 36L
    const val APPMSG_SUBTYPE_QUOTE = 57L
    const val APPMSG_SUBTYPE_GROUP_NOTICE = 87L
    const val LOCAL_TYPE_SYSMSG = 10000L
    const val LOCAL_TYPE_REVOKE_SYSMSG = 10002L
    const val MAX_XML_LENGTH = 65_536

    private const val MAX_RECORD_DEPTH = 2
    private const val MAX_FORWARDED_ITEMS = 12
    private const val MAX_FORWARDED_CHARS = 2_000
    private const val MAX_QUOTE_SNIPPET_CHARS = 180
    private const val MAX_INLINE_TEXT_CHARS = 500

    private val SUPPORTED_APPMSG_SUBTYPES = setOf(
        APPMSG_SUBTYPE_LINK,
        APPMSG_SUBTYPE_FILE,
        APPMSG_SUBTYPE_FORWARD,
        APPMSG_SUBTYPE_MINI_PROGRAM,
        APPMSG_SUBTYPE_MINI_PROGRAM_ALT,
        APPMSG_SUBTYPE_QUOTE,
        APPMSG_SUBTYPE_GROUP_NOTICE
    )

    private val UNSAFE_CONTROL_OR_BIDI_REGEX = Regex("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]")
    private val MULTI_WHITESPACE_REGEX = Regex("\\s+")

    /** Checks whether a row may carry an attachment or rich XML subtype parsed by [parseMessage]. */
    fun isCandidateFileMessage(
        localType: Long,
        content: String,
        allowLegacyXmlFallback: Boolean = false
    ): Boolean {
        val baseType = localType and 0xFFFFFFFFL
        val subType = localType ushr 32
        if (baseType == BASE_TYPE_APPMSG) {
            return subType == APPMSG_SUBTYPE_FILE || subType == 0L
        }
        if (!allowLegacyXmlFallback) return false
        val trimmed = content.trimStart()
        return (trimmed.startsWith("<msg") || trimmed.startsWith("<?xml")) &&
            trimmed.contains("<appmsg", ignoreCase = true) &&
            trimmed.contains("<type>6</type>", ignoreCase = true)
    }

    /** Parses a `type=6` file message into [EventAttachment], returning `null` for all other subtypes. */
    fun parseFileMessage(rawContent: String, timestampSeconds: Long): EventAttachment? {
        return (parseAppMsgInternal(rawContent, localType = BASE_TYPE_APPMSG, timestampSeconds = timestampSeconds, allowLegacyXmlFallback = true, depth = 0, fileOnly = true)
            as? ParsedWeChatMessage.FileAttachment)?.attachment
    }

    /**
     * Parses WeChat `10000`/`10002` sysmsgs or `local_type=49` rich XML payloads into a structured
     * [ParsedWeChatMessage], returning `null` for regular plain text or unsupported/malformed stubs.
     */
    fun parseMessage(
        rawContent: String,
        localType: Long,
        timestampSeconds: Long,
        allowLegacyXmlFallback: Boolean = false
    ): ParsedWeChatMessage? {
        val baseType = localType and 0xFFFFFFFFL
        if (baseType == LOCAL_TYPE_SYSMSG) {
            return ParsedWeChatMessage.IgnoredSysMsg
        }
        if (baseType == LOCAL_TYPE_REVOKE_SYSMSG) {
            return parseSysMsg(rawContent) ?: ParsedWeChatMessage.IgnoredSysMsg
        }
        if (baseType != BASE_TYPE_APPMSG && !allowLegacyXmlFallback) {
            return null
        }
        val trimmed = rawContent.trimStart()
        if (allowLegacyXmlFallback && trimmed.startsWith("<sysmsg", ignoreCase = true)) {
            return parseSysMsg(trimmed) ?: ParsedWeChatMessage.IgnoredSysMsg
        }
        return parseAppMsgInternal(
            rawContent = rawContent,
            localType = localType,
            timestampSeconds = timestampSeconds,
            allowLegacyXmlFallback = allowLegacyXmlFallback,
            depth = 0,
            fileOnly = false
        )
    }

    private fun parseSysMsg(rawContent: String): ParsedWeChatMessage? {
        val xmlSlice = extractXmlDocument(rawContent, rootTag = "sysmsg") ?: return null
        if (xmlSlice.length > MAX_XML_LENGTH) return ParsedWeChatMessage.IgnoredSysMsg
        val doc = parseXmlDocument(xmlSlice) ?: return ParsedWeChatMessage.IgnoredSysMsg
        val root = doc.documentElement
        if (!root.nodeName.equals("sysmsg", ignoreCase = true)) {
            return ParsedWeChatMessage.IgnoredSysMsg
        }
        val sysType = root.getAttribute("type").trim().lowercase(Locale.ROOT)
        if (sysType == "revokemsg") {
            val revokeEl = firstDirectChild(root, "revokemsg") ?: firstDescendant(root, "revokemsg")
            val newMsgId = revokeEl?.let { firstDirectChild(it, "newmsgid")?.textContent?.trim()?.toLongOrNull() }
            return ParsedWeChatMessage.RevokeSysMsg(recalledServerId = newMsgId)
        }
        return ParsedWeChatMessage.IgnoredSysMsg
    }

    private fun parseAppMsgInternal(
        rawContent: String,
        localType: Long,
        timestampSeconds: Long,
        allowLegacyXmlFallback: Boolean,
        depth: Int,
        fileOnly: Boolean
    ): ParsedWeChatMessage? {
        if (depth > MAX_RECORD_DEPTH) return null
        val xmlSlice = extractXmlDocument(rawContent, rootTag = "msg") ?: return null
        if (xmlSlice.length > MAX_XML_LENGTH) return null
        val doc = parseXmlDocument(xmlSlice) ?: return null
        val root = doc.documentElement

        val appMsgElement = firstDirectChild(root, "appmsg")
            ?: firstDescendant(root, "appmsg")
            ?: return null

        val explicitXmlType = firstDirectChild(appMsgElement, "type")?.textContent?.trim()?.toLongOrNull()
        val packedSubType = (localType ushr 32).takeIf { (localType and 0xFFFFFFFFL) == BASE_TYPE_APPMSG && it > 0L }
        val resolvedSubType = explicitXmlType ?: if (!fileOnly && !allowLegacyXmlFallback) packedSubType else null

        if (fileOnly) {
            if (resolvedSubType != APPMSG_SUBTYPE_FILE) return null
            val attachment = buildAttachmentFromAppMsg(root, appMsgElement, timestampSeconds) ?: return null
            return ParsedWeChatMessage.FileAttachment(attachment, formatAttachmentMarker(attachment))
        }

        if (resolvedSubType == null || resolvedSubType !in SUPPORTED_APPMSG_SUBTYPES) {
            return null
        }

        return when (resolvedSubType) {
            APPMSG_SUBTYPE_FILE -> {
                val attachment = buildAttachmentFromAppMsg(root, appMsgElement, timestampSeconds) ?: return null
                ParsedWeChatMessage.FileAttachment(attachment, formatAttachmentMarker(attachment))
            }
            APPMSG_SUBTYPE_QUOTE -> parseQuoteReply(appMsgElement, timestampSeconds, depth)
            APPMSG_SUBTYPE_LINK -> parseLinkCard(appMsgElement)
            APPMSG_SUBTYPE_MINI_PROGRAM, APPMSG_SUBTYPE_MINI_PROGRAM_ALT -> parseMiniProgram(appMsgElement)
            APPMSG_SUBTYPE_FORWARD -> parseForwardedRecord(appMsgElement, depth)
            APPMSG_SUBTYPE_GROUP_NOTICE -> parseGroupAnnouncement(root, appMsgElement)
            else -> null
        }
    }

    private fun parseQuoteReply(
        appMsgElement: Element,
        timestampSeconds: Long,
        depth: Int
    ): ParsedWeChatMessage.QuoteReply? {
        val replyText = sanitizeMultiline(firstDirectChild(appMsgElement, "title")?.textContent.orEmpty(), maxChars = 4_000)
        if (replyText.isBlank()) return null

        val referMsg = firstDirectChild(appMsgElement, "refermsg")
        val quotedSender = referMsg?.let { firstDirectChild(it, "displayname")?.textContent }
            ?.let { sanitizeInline(it, 64) }
            .orEmpty()
        val quotedServerId = referMsg?.let { firstDirectChild(it, "svrid")?.textContent?.trim()?.toLongOrNull() }
        val rawQuotedContent = referMsg?.let { firstDirectChild(it, "content")?.textContent }.orEmpty().trim()
        val referType = referMsg?.let { firstDirectChild(it, "type")?.textContent?.trim()?.toLongOrNull() } ?: 1L

        val snippet = summarizeQuotedContent(rawQuotedContent, referType, timestampSeconds, depth + 1)
        val quoteSummary = buildString {
            if (quotedSender.isNotBlank() && snippet.isNotBlank()) {
                append("Replying to $quotedSender: $snippet")
            } else if (snippet.isNotBlank()) {
                append("Replying to: $snippet")
            } else if (quotedSender.isNotBlank()) {
                append("Replying to $quotedSender")
            }
        }
        return ParsedWeChatMessage.QuoteReply(
            replyText = replyText,
            quoteSummary = quoteSummary,
            quotedSender = quotedSender,
            quotedServerId = quotedServerId
        )
    }

    private fun summarizeQuotedContent(
        rawQuotedContent: String,
        referType: Long,
        timestampSeconds: Long,
        nextDepth: Int
    ): String {
        if (rawQuotedContent.isBlank()) {
            return when (referType) {
                3L -> "[Image]"
                34L -> "[Voice]"
                43L -> "[Video]"
                47L -> "[Sticker]"
                else -> ""
            }
        }
        val trimmed = rawQuotedContent.trimStart()
        if (nextDepth <= MAX_RECORD_DEPTH && (trimmed.startsWith("<msg", ignoreCase = true) || trimmed.startsWith("<?xml", ignoreCase = true))) {
            val nested = parseAppMsgInternal(
                rawContent = trimmed,
                localType = BASE_TYPE_APPMSG,
                timestampSeconds = timestampSeconds,
                allowLegacyXmlFallback = true,
                depth = nextDepth,
                fileOnly = false
            )
            val nestedSummary = when (nested) {
                is ParsedWeChatMessage.FileAttachment -> nested.markerText
                is ParsedWeChatMessage.LinkCard -> "[Link] ${nested.title}"
                is ParsedWeChatMessage.MiniProgram -> "[MiniProgram] ${nested.title}"
                is ParsedWeChatMessage.ForwardedRecord -> "[Forwarded] ${nested.title}"
                is ParsedWeChatMessage.GroupAnnouncement -> nested.body
                is ParsedWeChatMessage.QuoteReply -> nested.replyText
                else -> ""
            }
            if (nestedSummary.isNotBlank()) {
                return sanitizeInline(nestedSummary, MAX_QUOTE_SNIPPET_CHARS)
            }
        }
        return sanitizeInline(rawQuotedContent, MAX_QUOTE_SNIPPET_CHARS)
    }

    private fun parseLinkCard(appMsgElement: Element): ParsedWeChatMessage.LinkCard? {
        val title = sanitizeInline(firstDirectChild(appMsgElement, "title")?.textContent.orEmpty(), 200)
        val rawDes = sanitizeInline(firstDirectChild(appMsgElement, "des")?.textContent.orEmpty(), 300)
        val description = if (rawDes.equals(title, ignoreCase = true)) "" else rawDes
        val rawUrl = firstDirectChild(appMsgElement, "url")?.textContent?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: firstDirectChild(appMsgElement, "lowurl")?.textContent?.trim()
        val safeUrl = sanitizeHttpUrl(rawUrl)
        val sourceName = sanitizeInline(firstDirectChild(appMsgElement, "sourcedisplayname")?.textContent.orEmpty(), 80)

        if (title.isBlank() && safeUrl == null) return null

        val displayText = buildString {
            append("[Link")
            if (sourceName.isNotBlank()) append(": $sourceName")
            append("]")
            if (title.isNotBlank()) append(" $title")
            if (description.isNotBlank()) append(" — $description")
            if (safeUrl != null) append(" ($safeUrl)")
        }.trim()

        return ParsedWeChatMessage.LinkCard(
            title = title,
            description = description,
            url = safeUrl,
            sourceName = sourceName,
            displayText = displayText
        )
    }

    private fun parseMiniProgram(appMsgElement: Element): ParsedWeChatMessage.MiniProgram? {
        val appName = sanitizeInline(firstDirectChild(appMsgElement, "sourcedisplayname")?.textContent.orEmpty(), 80)
        val title = sanitizeInline(firstDirectChild(appMsgElement, "title")?.textContent.orEmpty(), 200)
        val rawDes = sanitizeInline(firstDirectChild(appMsgElement, "des")?.textContent.orEmpty(), 300)
        val description = if (rawDes.equals(title, ignoreCase = true)) "" else rawDes
        val weappInfo = firstDirectChild(appMsgElement, "weappinfo")
        val pagePath = weappInfo?.let { sanitizeInline(firstDirectChild(it, "pagepath")?.textContent.orEmpty(), 200) }.orEmpty()

        if (title.isBlank() && description.isBlank() && appName.isBlank()) return null

        val displayText = buildString {
            append("[MiniProgram")
            if (appName.isNotBlank()) append(": $appName")
            append("]")
            if (title.isNotBlank()) append(" $title")
            if (description.isNotBlank()) append(" — $description")
        }.trim()

        return ParsedWeChatMessage.MiniProgram(
            appName = appName,
            title = title,
            description = description,
            pagePath = pagePath,
            displayText = displayText
        )
    }

    private fun parseForwardedRecord(appMsgElement: Element, depth: Int): ParsedWeChatMessage.ForwardedRecord? {
        val title = sanitizeInline(firstDirectChild(appMsgElement, "title")?.textContent.orEmpty(), 160)
            .ifBlank { "Forwarded Chat History" }
        val rawRecordItem = firstDirectChild(appMsgElement, "recorditem")?.textContent?.trim().orEmpty()
        val parsedItems = if (rawRecordItem.isNotBlank() && depth < MAX_RECORD_DEPTH) {
            parseRecordItemXml(rawRecordItem)
        } else {
            emptyList()
        }

        val itemsBody = if (parsedItems.isNotEmpty()) {
            parsedItems.joinToString("\n")
        } else {
            sanitizeMultiline(firstDirectChild(appMsgElement, "des")?.textContent.orEmpty(), MAX_FORWARDED_CHARS)
        }
        if (itemsBody.isBlank() && title == "Forwarded Chat History") return null

        val displayText = buildString {
            append("[Forwarded: $title]")
            if (itemsBody.isNotBlank()) {
                append('\n').append(itemsBody)
            }
        }
        return ParsedWeChatMessage.ForwardedRecord(
            title = title,
            formattedItems = itemsBody,
            displayText = displayText
        )
    }

    private fun parseRecordItemXml(rawRecordItem: String): List<String> {
        val startIdx = rawRecordItem.indexOf("<recordinfo")
        if (startIdx < 0) return emptyList()
        val endTagIdx = rawRecordItem.lastIndexOf("</recordinfo>")
        val slice = if (endTagIdx > startIdx) {
            rawRecordItem.substring(startIdx, endTagIdx + "</recordinfo>".length)
        } else {
            rawRecordItem.substring(startIdx)
        }.trim()
        if (slice.length > MAX_XML_LENGTH) return emptyList()
        val doc = parseXmlDocument(slice) ?: return emptyList()
        val nodes = doc.getElementsByTagName("dataitem")
        val items = mutableListOf<String>()
        var totalChars = 0
        for (i in 0 until minOf(nodes.length, MAX_FORWARDED_ITEMS)) {
            val itemEl = nodes.item(i) as? Element ?: continue
            val sender = sanitizeInline(firstDirectChild(itemEl, "sourcename")?.textContent.orEmpty(), 48)
            val time = sanitizeInline(firstDirectChild(itemEl, "sourcetime")?.textContent.orEmpty(), 32)
            val desc = sanitizeInline(firstDirectChild(itemEl, "datadesc")?.textContent.orEmpty(), 360)
            val subTitle = sanitizeInline(firstDirectChild(itemEl, "datatitle")?.textContent.orEmpty(), 120)
            val contentPart = desc.ifBlank { subTitle }
            if (contentPart.isBlank()) continue
            val line = buildString {
                if (time.isNotBlank()) append("[$time] ")
                if (sender.isNotBlank()) append("$sender: ")
                append(contentPart)
            }
            if (totalChars + line.length > MAX_FORWARDED_CHARS) break
            items.add(line)
            totalChars += line.length + 1
        }
        return items
    }

    private fun parseGroupAnnouncement(root: Element?, appMsgElement: Element): ParsedWeChatMessage.GroupAnnouncement? {
        val rawNotice = firstDirectChild(appMsgElement, "textannouncement")?.textContent?.takeIf { it.isNotBlank() }
            ?: root?.let { firstDescendant(it, "textannouncement")?.textContent?.takeIf { s -> s.isNotBlank() } }
            ?: firstDirectChild(appMsgElement, "announcement")?.textContent?.takeIf { it.isNotBlank() }
            ?: ""
        val fullNotice = sanitizeMultiline(rawNotice, maxChars = 4_000)
        val fallbackTitle = sanitizeMultiline(firstDirectChild(appMsgElement, "title")?.textContent.orEmpty(), maxChars = 4_000)
        val body = fullNotice.ifBlank { fallbackTitle }.trim()
        if (body.isBlank()) return null
        return ParsedWeChatMessage.GroupAnnouncement(
            body = body,
            displayText = "[Group Announcement]\n$body"
        )
    }

    private fun buildAttachmentFromAppMsg(
        rootElement: Element?,
        appMsgElement: Element,
        timestampSeconds: Long
    ): EventAttachment? {
        val rawTitle = firstDirectChild(appMsgElement, "title")?.textContent?.trim().orEmpty()
        val safeFileName = sanitizeFileName(rawTitle) ?: return null

        val appAttachElement = firstDirectChild(appMsgElement, "appattach")
        val rawExt = appAttachElement?.let { firstDirectChild(it, "fileext")?.textContent?.trim() }.orEmpty()
        val resolvedExt = rawExt
            .removePrefix(".")
            .replace(Regex("[^A-Za-z0-9]"), "")
            .lowercase(Locale.ROOT)
            .ifBlank { safeFileName.substringAfterLast('.', "").lowercase(Locale.ROOT) }

        val sizeBytes = appAttachElement?.let { firstDirectChild(it, "totallen")?.textContent?.trim()?.toLongOrNull() }
            ?.coerceAtLeast(0L)
            ?: 0L
        val md5 = firstDirectChild(appMsgElement, "md5")?.textContent?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("^[0-9a-f]{32}$")) }
            .orEmpty()
        val expireAt = firstDescendant(rootElement, "media_expire_at")
            ?.textContent
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

        return EventAttachment(
            fileName = safeFileName,
            fileExtension = resolvedExt,
            fileSizeBytes = sizeBytes,
            md5 = md5,
            localPath = null,
            messageTimestampSeconds = timestampSeconds,
            mediaExpireAtSeconds = expireAt
        )
    }

    fun formatAttachmentMarker(attachment: EventAttachment): String {
        return if (attachment.fileSizeBytes > 0L) {
            "[Shared File: ${attachment.fileName} (${formatByteSize(attachment.fileSizeBytes)})]"
        } else {
            "[Shared File: ${attachment.fileName}]"
        }
    }

    fun formatByteSize(bytes: Long): String {
        if (bytes <= 0L) return "Unknown size"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    internal fun sanitizeFileName(rawTitle: String): String? {
        val cleaned = rawTitle
            .replace(UNSAFE_CONTROL_OR_BIDI_REGEX, "")
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return null
        return cleaned
    }

    internal fun sanitizeHttpUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.replace(UNSAFE_CONTROL_OR_BIDI_REGEX, "")?.trim() ?: return null
        if (trimmed.isBlank()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return trimmed
    }

    private fun sanitizeInline(raw: String, maxChars: Int = MAX_INLINE_TEXT_CHARS): String {
        val collapsed = raw
            .replace(UNSAFE_CONTROL_OR_BIDI_REGEX, " ")
            .replace(MULTI_WHITESPACE_REGEX, " ")
            .trim()
        return if (collapsed.length <= maxChars) collapsed else collapsed.take(maxChars - 3).trimEnd() + "..."
    }

    private fun sanitizeMultiline(raw: String, maxChars: Int): String {
        val cleaned = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u202A-\\u202E\\u2066-\\u2069]"), "")
            .trim()
        return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars - 3).trimEnd() + "..."
    }

    private fun parseXmlDocument(xmlSlice: String): Document? = try {
        createHardenedDocumentBuilder().parse(InputSource(StringReader(xmlSlice)))
    } catch (_: Exception) {
        null
    }

    private fun extractXmlDocument(rawContent: String, rootTag: String): String? {
        val declIndex = rawContent.indexOf("<?xml")
        val rootIndex = rawContent.indexOf("<$rootTag")
        val start = when {
            rootIndex < 0 -> return null
            declIndex in 0..rootIndex -> declIndex
            else -> rootIndex
        }
        val closing = "</$rootTag>"
        val endTag = rawContent.lastIndexOf(closing)
        return if (endTag > start) {
            rawContent.substring(start, endTag + closing.length)
        } else {
            rawContent.substring(start)
        }.trim()
    }

    private fun createHardenedDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().apply {
        setErrorHandler(object : org.xml.sax.ErrorHandler {
            override fun warning(exception: org.xml.sax.SAXParseException) = Unit
            override fun error(exception: org.xml.sax.SAXParseException) = throw exception
            override fun fatalError(exception: org.xml.sax.SAXParseException) = throw exception
        })
    }

    private fun firstDirectChild(parent: Element?, tagName: String): Element? {
        if (parent == null) return null
        var child = parent.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName.equals(tagName, ignoreCase = true)) {
                return child as Element
            }
            child = child.nextSibling
        }
        return null
    }

    private fun firstDescendant(parent: Element?, tagName: String): Element? {
        if (parent == null) return null
        val nodes = parent.getElementsByTagName(tagName)
        if (nodes.length > 0) {
            val first = nodes.item(0)
            if (first is Element) return first
        }
        return null
    }
}
