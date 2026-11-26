package com.wechatdigest.core.attachment

import com.wechatdigest.core.model.EventAttachment

/**
 * Backward-compatible facade for WeChat `appmsg` `<type>6</type>` file attachment parsing.
 * Delegates all XXE-hardened DOM parsing and sanitization to [WeChatXmlMessageParser].
 */
object WeChatFileMessageParser {

    const val BASE_TYPE_APPMSG = WeChatXmlMessageParser.BASE_TYPE_APPMSG
    const val APPMSG_SUBTYPE_FILE = WeChatXmlMessageParser.APPMSG_SUBTYPE_FILE
    const val MAX_XML_LENGTH = WeChatXmlMessageParser.MAX_XML_LENGTH

    fun isCandidateFileMessage(
        localType: Long,
        content: String,
        allowLegacyXmlFallback: Boolean = false
    ): Boolean = WeChatXmlMessageParser.isCandidateFileMessage(localType, content, allowLegacyXmlFallback)

    fun parseFileMessage(rawContent: String, timestampSeconds: Long): EventAttachment? =
        WeChatXmlMessageParser.parseFileMessage(rawContent, timestampSeconds)

    fun formatAttachmentMarker(attachment: EventAttachment): String =
        WeChatXmlMessageParser.formatAttachmentMarker(attachment)

    fun formatByteSize(bytes: Long): String =
        WeChatXmlMessageParser.formatByteSize(bytes)

    internal fun sanitizeFileName(rawTitle: String): String? =
        WeChatXmlMessageParser.sanitizeFileName(rawTitle)
}
