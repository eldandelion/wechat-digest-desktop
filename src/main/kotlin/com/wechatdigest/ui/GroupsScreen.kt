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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.semantics.Role
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
        filterChatrooms(rooms, query, showSelectedOnly, settings.monitoredRoomIds)
    }
    val canScan = !isLoading && settings.wechatDataDir.isNotBlank()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(start = 24.dp, top = 8.dp, end = 24.dp)) {
            PillSearchBar(query = query, onQueryChange = { appState.groupSearchQuery.value = it }, placeholder = "Search groups", modifier = Modifier.fillMaxWidth())
            if (settings.wechatDataDir.isBlank()) {
                Spacer(Modifier.height(8.dp))
                MissingDirectoryBanner(onOpenSettings = { appState.selectScreen(Screen.SETTINGS) })
            }
            // FilterChip has 8.dp built-in vertical touch padding above and below its pill, so 8.dp spacers give 16.dp visual gaps on both sides.
            Spacer(Modifier.height(8.dp))
            GroupsFilterChipsRow(rooms.size, settings.monitoredRoomIds.size, showSelectedOnly) { showSelectedOnly = it }
            Spacer(Modifier.height(8.dp))
            GroupsListContent(
                isLoading = isLoading,
                roomsEmpty = rooms.isEmpty(),
                showSelectedOnly = showSelectedOnly,
                queryBlank = query.isBlank(),
                filteredRooms = filteredRooms,
                monitoredIds = settings.monitoredRoomIds,
                groupConfigs = groupConfigs,
                syncCheckpoints = syncCheckpoints,
                onToggleMonitored = { appState.toggleRoomMonitored(it) },
                onResetCheckpoint = { appState.resetGroupCheckpoint(it) },
                onConfigureRules = { editingRoom = it }
            )
        }
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        GroupsScanFab(isLoading = isLoading, canScan = canScan, onScan = { appState.refreshRooms() }, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp))
        editingRoom?.let { targetRoom ->
            val rememberedLoadMembers = remember(targetRoom.roomId) { suspend { appState.loadRoomMembers(targetRoom.roomId) } }
            GroupRuleDialog(
                room = targetRoom,
                initialConfig = groupConfigs[targetRoom.roomId],
                loadMembers = rememberedLoadMembers,
                onSave = { appState.saveGroupConfig(it); editingRoom = null },
                onDelete = { appState.deleteGroupConfig(targetRoom.roomId); editingRoom = null },
                onDismiss = { editingRoom = null }
            )
        }
    }
}

private fun filterChatrooms(
    rooms: List<GroupChat>,
    query: String,
    showSelectedOnly: Boolean,
    monitoredIds: Set<String>
): List<GroupChat> {
    val baseRooms = if (showSelectedOnly) rooms.filter { it.roomId in monitoredIds } else rooms
    if (query.isBlank()) return baseRooms
    return baseRooms.filter { it.displayName.contains(query, ignoreCase = true) || it.roomId.contains(query, ignoreCase = true) }
}

@Composable
private fun MissingDirectoryBanner(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("WeChat directory not set", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    "Configure your WeChat data storage directory in Settings to discover chatrooms.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            TextButton(onClick = onOpenSettings) { Text("Settings", color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupsFilterChipsRow(totalRooms: Int, selectedCount: Int, showSelectedOnly: Boolean, onSelectMode: (Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !showSelectedOnly,
            onClick = { onSelectMode(false) },
            leadingIcon = { Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text("All ($totalRooms)") }
        )
        FilterChip(
            selected = showSelectedOnly,
            onClick = { onSelectMode(true) },
            leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text("Selected ($selectedCount)") }
        )
    }
}

@Composable
private fun GroupsListContent(
    isLoading: Boolean,
    roomsEmpty: Boolean,
    showSelectedOnly: Boolean,
    queryBlank: Boolean,
    filteredRooms: List<GroupChat>,
    monitoredIds: Set<String>,
    groupConfigs: Map<String, GroupConfig>,
    syncCheckpoints: Map<String, SyncCheckpoint>,
    onToggleMonitored: (String) -> Unit,
    onResetCheckpoint: (String) -> Unit,
    onConfigureRules: (GroupChat) -> Unit
) {
    if (isLoading && roomsEmpty) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (filteredRooms.isEmpty()) {
        GroupsEmptyState(roomsEmpty = roomsEmpty, showSelectedOnly = showSelectedOnly, queryBlank = queryBlank)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(filteredRooms, key = { _, room -> room.roomId }) { index, room ->
            val onToggle = remember(room.roomId, onToggleMonitored) { { onToggleMonitored(room.roomId) } }
            val onReset = remember(room.roomId, onResetCheckpoint) { { onResetCheckpoint(room.roomId) } }
            val onConfigure = remember(room, onConfigureRules) { { onConfigureRules(room) } }
            GroupRoomCard(
                room = room,
                shape = segmentedItemShape(index = index, totalCount = filteredRooms.size),
                isChecked = monitoredIds.contains(room.roomId),
                config = groupConfigs[room.roomId],
                checkpoint = syncCheckpoints[room.roomId],
                onToggleMonitored = onToggle,
                onResetCheckpoint = onReset,
                onConfigureRules = onConfigure
            )
        }
    }
}

@Composable
private fun GroupsScanFab(isLoading: Boolean, canScan: Boolean, onScan: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = { if (canScan) onScan() },
        modifier = modifier,
        containerColor = if (canScan) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (canScan) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        icon = {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null)
            }
        },
        text = { Text(if (isLoading) "Scanning..." else "Scan Groups") }
    )
}

@Composable
private fun GroupsEmptyState(roomsEmpty: Boolean, showSelectedOnly: Boolean, queryBlank: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
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

@Composable
private fun GroupRoomCard(
    room: GroupChat,
    shape: RoundedCornerShape,
    isChecked: Boolean,
    config: GroupConfig?,
    checkpoint: SyncCheckpoint?,
    onToggleMonitored: () -> Unit,
    onResetCheckpoint: () -> Unit,
    onConfigureRules: () -> Unit
) {
    val badgeLabel = remember(config) { buildGroupRuleBadgeLabel(config) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClick = onToggleMonitored),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isChecked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            GroupRoomMetadataColumn(room = room, badgeLabel = badgeLabel, checkpoint = checkpoint, modifier = Modifier.weight(1f))
            if (checkpoint != null && checkpoint.lastLocalId > 0) {
                IconButton(onClick = onResetCheckpoint) {
                    Icon(Icons.Default.Refresh, contentDescription = "Re-scan group history", tint = MaterialTheme.colorScheme.outline)
                }
            }
            IconButton(onClick = onConfigureRules) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Configure Rules",
                    tint = if (badgeLabel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

private fun buildGroupRuleBadgeLabel(config: GroupConfig?): String? {
    val hasCustomPrompt = config?.customPrompt?.isNotBlank() == true
    val hasCustomPersona = config?.customPersona?.isNotBlank() == true
    val senderCount = config?.allowedSenderIds?.size ?: 0
    if (!hasCustomPrompt && !hasCustomPersona && senderCount == 0) return null
    return buildList {
        if (hasCustomPrompt) add("Custom Rule")
        if (hasCustomPersona) add("Persona")
        if (senderCount > 0) add("$senderCount sender(s)")
    }.joinToString(" • ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupRoomMetadataColumn(
    room: GroupChat,
    badgeLabel: String?,
    checkpoint: SyncCheckpoint?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = room.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            badgeLabel?.let { label ->
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                    Text(text = label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            if (checkpoint != null && checkpoint.lastLocalId > 0) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f), shape = RoundedCornerShape(4.dp)) {
                    Text(text = "Synced: #${checkpoint.lastLocalId}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Medium)
                }
            }
        }
        Text(text = room.roomId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}
