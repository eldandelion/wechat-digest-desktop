package com.wechatdigest.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wechatdigest.app.AppState
import com.wechatdigest.app.Screen
import com.wechatdigest.core.model.GroupChat
import com.wechatdigest.core.model.GroupConfig
import com.wechatdigest.core.model.SyncCheckpoint

/**
 * Screen listing discovered WeChat chatrooms with monitoring toggles, rule configuration, and sync checkpoint reset.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupsScreen(appState: AppState) {
    val settings by appState.settings.collectAsState()
    val rooms by appState.availableRooms.collectAsState()
    val isLoading by appState.isLoadingRooms.collectAsState()
    val groupConfigs by appState.groupConfigs.collectAsState()
    val syncCheckpoints by appState.syncCheckpoints.collectAsState()

    val query by appState.groupSearchQuery.collectAsState()
    var showSelectedOnly by remember { mutableStateOf(false) }
    var editingRoom by remember { mutableStateOf<GroupChat?>(null) }

    val filteredRooms = remember(rooms, query, showSelectedOnly, settings.monitoredRoomIds) {
        val baseRooms = if (showSelectedOnly) {
            rooms.filter { settings.monitoredRoomIds.contains(it.roomId) }
        } else {
            rooms
        }
        if (query.isBlank()) baseRooms else {
            baseRooms.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                it.roomId.contains(query, ignoreCase = true)
            }
        }
    }

    val canScan = !isLoading && settings.wechatDataDir.isNotBlank()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp, top = 16.dp, end = 24.dp)
        ) {
                PillSearchBar(
                    query = query,
                    onQueryChange = { appState.groupSearchQuery.value = it },
                    placeholder = "Search groups",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(14.dp))

                // Data dir warning banner if not set
                if (settings.wechatDataDir.isBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "WeChat directory not set",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Configure your WeChat data storage directory in Settings to discover chatrooms.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            TextButton(onClick = { appState.selectScreen(Screen.SETTINGS) }) {
                                Text("Settings", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // Selection Filter Chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !showSelectedOnly,
                        onClick = { showSelectedOnly = false },
                        label = { Text("All (${rooms.size})") }
                    )
                    FilterChip(
                        selected = showSelectedOnly,
                        onClick = { showSelectedOnly = true },
                        label = { Text("Selected (${settings.monitoredRoomIds.size})") }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // List or Loading / Empty
                if (isLoading && rooms.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (filteredRooms.isEmpty()) {
                    GroupsEmptyState(
                        roomsEmpty = rooms.isEmpty(),
                        showSelectedOnly = showSelectedOnly,
                        queryBlank = query.isBlank()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredRooms, key = { it.roomId }) { room ->
                            GroupRoomCard(
                                room = room,
                                isChecked = settings.monitoredRoomIds.contains(room.roomId),
                                config = groupConfigs[room.roomId],
                                checkpoint = syncCheckpoints[room.roomId],
                                onToggleMonitored = { appState.toggleRoomMonitored(room.roomId) },
                                onResetCheckpoint = { appState.resetGroupCheckpoint(room.roomId) },
                                onConfigureRules = { editingRoom = room }
                            )
                        }
                    }
                }
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }

        ExtendedFloatingActionButton(
            onClick = { if (canScan) appState.refreshRooms() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            containerColor = if (canScan) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (canScan) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            icon = {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Scan Groups")
                }
            },
            text = { Text(if (isLoading) "Scanning..." else "Scan Groups") }
        )

        editingRoom?.let { targetRoom ->
            val rememberedLoadMembers = remember(targetRoom.roomId) {
                suspend { appState.loadRoomMembers(targetRoom.roomId) }
            }
            GroupRuleDialog(
                room = targetRoom,
                initialConfig = groupConfigs[targetRoom.roomId],
                loadMembers = rememberedLoadMembers,
                onSave = {
                    appState.saveGroupConfig(it)
                    editingRoom = null
                },
                onDelete = {
                    appState.deleteGroupConfig(targetRoom.roomId)
                    editingRoom = null
                },
                onDismiss = { editingRoom = null }
            )
        }
    }
}

@Composable
private fun GroupsEmptyState(
    roomsEmpty: Boolean,
    showSelectedOnly: Boolean,
    queryBlank: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Group,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    roomsEmpty -> "No chatrooms found"
                    showSelectedOnly && queryBlank -> "No groups selected yet"
                    else -> "No matching chatrooms"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    roomsEmpty -> "Ensure your WeChat contact.db exists at the configured path."
                    showSelectedOnly && queryBlank -> "Switch to 'All' to pick groups for monitoring."
                    else -> "Try a different search term or filter."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupRoomCard(
    room: GroupChat,
    isChecked: Boolean,
    config: GroupConfig?,
    checkpoint: SyncCheckpoint?,
    onToggleMonitored: () -> Unit,
    onResetCheckpoint: () -> Unit,
    onConfigureRules: () -> Unit
) {
    val hasCustomPrompt = config?.customPrompt?.isNotBlank() == true
    val hasCustomPersona = config?.customPersona?.isNotBlank() == true
    val senderCount = config?.allowedSenderIds?.size ?: 0
    val hasAnyRule = hasCustomPrompt || hasCustomPersona || senderCount > 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleMonitored),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isChecked) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggleMonitored() }
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = room.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (hasAnyRule) {
                        val badgeLabel = buildList {
                            if (hasCustomPrompt) add("Custom Rule")
                            if (hasCustomPersona) add("Persona")
                            if (senderCount > 0) add("$senderCount sender(s)")
                        }.joinToString(" • ")
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = badgeLabel,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (checkpoint != null && checkpoint.lastLocalId > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "Synced: #${checkpoint.lastLocalId}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Text(
                    text = room.roomId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (checkpoint != null && checkpoint.lastLocalId > 0) {
                IconButton(onClick = onResetCheckpoint) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Re-scan group history",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            IconButton(onClick = onConfigureRules) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Configure Rules",
                    tint = if (hasAnyRule) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
