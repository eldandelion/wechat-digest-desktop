package com.wechatdigest.core.attachment

import com.wechatdigest.core.model.EventAttachment
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Safely parses WeChat `local_type == 49` (appmsg `<type>6</type>`) XML payloads
 * into structured [EventAttachment] descriptors using an XXE-hardened DOM parser.
 */
object WeChatFileMessageParser {

    const val BASE_TYPE_APPMSG = 49L
    const val APPMSG_SUBTYPE_FILE = 6L
    const val MAX_XML_LENGTH = 65_536

    private val UNSAFE_CONTROL_OR_BIDI_REGEX = Regex("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]")

    /**
     * Checks whether a message row may represent an appmsg file share.
     * Supports packed WeChat 4 `local_type` (`(6L shl 32) or 49L`), plain `49L`,
     * and legacy fallback rows whose XML payload embeds `<type>6</type>`.
     */
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

    /**
     * Parses a WeChat `<msg><appmsg>...<type>6</type>` XML payload into an [EventAttachment],
     * or returns `null` if the payload is not a file message or fails safety validation.
     */
    fun parseFileMessage(rawContent: String, timestampSeconds: Long): EventAttachment? {
        val xmlSlice = extractXmlDocument(rawContent) ?: return null
        if (xmlSlice.length > MAX_XML_LENGTH) return null
        val document = parseXmlDocument(xmlSlice) ?: return null

        val appMsgElement = firstDirectChild(document.documentElement, "appmsg")
            ?: firstDescendant(document.documentElement, "appmsg")
            ?: return null
        if (firstDirectChild(appMsgElement, "type")?.textContent?.trim() != APPMSG_SUBTYPE_FILE.toString()) {
            return null
        }
        return buildAttachmentFromAppMsg(document.documentElement, appMsgElement, timestampSeconds)
    }

    private fun parseXmlDocument(xmlSlice: String) = try {
        createHardenedDocumentBuilder().parse(InputSource(StringReader(xmlSlice)))
    } catch (_: Exception) {
        null
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

    /**
     * Produces a clean human- and LLM-readable marker for an extracted file message.
     */
    fun formatAttachmentMarker(attachment: EventAttachment): String {
        return if (attachment.fileSizeBytes > 0L) {
            "[Shared File: ${attachment.fileName} (${formatByteSize(attachment.fileSizeBytes)})]"
        } else {
            "[Shared File: ${attachment.fileName}]"
        }
    }

    /**
     * Formats byte size into a compact human-readable label (e.g., `18 KB`, `1.8 MB`).
     */
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

    private fun extractXmlDocument(rawContent: String): String? {
        val declIndex = rawContent.indexOf("<?xml")
        val msgIndex = rawContent.indexOf("<msg")
        val start = when {
            declIndex >= 0 && (msgIndex < 0 || declIndex <= msgIndex) -> declIndex
            msgIndex >= 0 -> msgIndex
            else -> return null
        }
        val endTag = rawContent.lastIndexOf("</msg>")
        return if (endTag > start) {
            rawContent.substring(start, endTag + "</msg>".length)
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
    }.newDocumentBuilder()

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
