package com.wechatdigest.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SyncCheckpoint(
    val roomId: String,
    val lastLocalId: Long,
    val lastSyncTimestamp: Long,
    val updatedAt: Long
)
