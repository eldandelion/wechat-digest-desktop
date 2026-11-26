package com.wechatdigest.core.model

import kotlinx.serialization.Serializable

/**
 * Per-group filtering rules, persona override, deterministic sender allowlist, and muted sender blocklist stored in [GroupConfigStore].
 */
@Serializable
data class GroupConfig(
    val roomId: String,
    val customPrompt: String = "",
    val customPersona: String = "",
    val allowedSenderIds: List<String> = emptyList(),
    val mutedSenderIds: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis() / 1000
)
