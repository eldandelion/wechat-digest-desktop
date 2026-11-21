package com.wechatdigest.core.model

import kotlinx.serialization.Serializable

/**
 * Per-group filtering rules, persona override, and deterministic sender allowlist stored in [GroupConfigStore].
 */
@Serializable
data class GroupConfig(
    val roomId: String,
    val customPrompt: String = "",
    val customPersona: String = "",
    val allowedSenderIds: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis() / 1000
)
