package com.wechatdigest.core.model

/**
 * Represents a contact or user profile from WeChat's contact.db.
 */
data class WeChatContact(
    val username: String,
    val nickname: String,
    val remark: String
) {
    val displayName: String
        get() = remark.ifBlank { nickname.ifBlank { username } }
}

/**
 * Represents a member of a WeChat group chatroom resolved from contact.db.
 */
data class GroupMember(
    val username: String,
    val displayName: String,
    val isOwner: Boolean = false
)

/**
 * Represents a resolved group chatroom.
 */
data class GroupChat(
    val roomId: String,
    val displayName: String,
    val memberCount: Int = 0
)

/**
 * Represents an individual message record extracted from WeChat's message_*.db.
 */
data class WeChatMessage(
    val localId: Long,
    val timestampSeconds: Long,
    val senderId: String,
    val senderDisplayName: String,
    val rawContent: String,
    val textContent: String,
    val isZstdCompressed: Boolean,
    val localType: Long = 1L,
    val attachments: List<EventAttachment> = emptyList(),
    val attachmentPreviews: Map<String, String> = emptyMap()
)
