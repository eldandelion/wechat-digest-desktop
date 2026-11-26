package com.wechatdigest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wechatdigest.core.model.GroupChat
import com.wechatdigest.core.model.GroupConfig
import com.wechatdigest.core.model.GroupMember

/**
 * Dialog allowing per-group focus instructions, persona override, and deterministic sender allowlist selection.
 */
@Composable
fun GroupRuleDialog(
    room: GroupChat,
    initialConfig: GroupConfig?,
    loadMembers: suspend () -> List<GroupMember> = { emptyList() },
    onSave: (GroupConfig) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var customPrompt by remember(room.roomId, initialConfig) { mutableStateOf(initialConfig?.customPrompt.orEmpty()) }
    var customPersona by remember(room.roomId, initialConfig) { mutableStateOf(initialConfig?.customPersona.orEmpty()) }
    var selectedSenderIds by remember(room.roomId, initialConfig) {
        mutableStateOf(initialConfig?.allowedSenderIds?.toSet().orEmpty())
    }
    val mutedSenderIds = remember(room.roomId, initialConfig) {
        initialConfig?.mutedSenderIds.orEmpty()
    }
    var members by remember(room.roomId) { mutableStateOf<List<GroupMember>>(emptyList()) }
    var isLoadingMembers by remember(room.roomId) { mutableStateOf(true) }
    var memberSearchQuery by remember(room.roomId) { mutableStateOf("") }

    LaunchedEffect(room.roomId) {
        isLoadingMembers = true
        members = try {
            loadMembers()
        } finally {
            isLoadingMembers = false
        }
    }

    val membersById = remember(members) { members.associateBy { it.username } }
    val sortedSelectedIds = remember(selectedSenderIds) { selectedSenderIds.sorted() }
    val filteredMembers = remember(members, memberSearchQuery) {
        val q = memberSearchQuery.trim()
        if (q.isEmpty()) members
        else members.filter {
            it.displayName.contains(q, ignoreCase = true) || it.username.contains(q, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { GroupRuleDialogTitle(room) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "prompt-fields") {
                    GroupRuleTextFields(
                        customPrompt = customPrompt,
                        onPromptChange = { customPrompt = it },
                        customPersona = customPersona,
                        onPersonaChange = { customPersona = it }
                    )
                }

                item(key = "allowlist-header") {
                    SenderAllowlistHeader(
                        selectedIds = sortedSelectedIds,
                        membersCount = members.size,
                        isLoadingMembers = isLoadingMembers,
                        membersById = membersById,
                        searchQuery = memberSearchQuery,
                        onSearchQueryChange = { memberSearchQuery = it },
                        onRemoveSender = { selectedSenderIds = selectedSenderIds - it },
                        onClearAll = { selectedSenderIds = emptySet() }
                    )
                }

                if (isLoadingMembers) {
                    item(key = "members-loading") {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else if (filteredMembers.isEmpty()) {
                    item(key = "members-empty") {
                        val emptyMsg = if (members.isEmpty()) {
                            "No group members found in contact.db for this room."
                        } else {
                            "No members match \"$memberSearchQuery\"."
                        }
                        Text(
                            text = emptyMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    items(filteredMembers, key = { it.username }) { member ->
                        val isChecked = member.username in selectedSenderIds
                        GroupMemberRow(
                            member = member,
                            isChecked = isChecked,
                            onToggle = { checked ->
                                selectedSenderIds = if (checked) {
                                    selectedSenderIds + member.username
                                } else {
                                    selectedSenderIds - member.username
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hasExistingConfig = initialConfig != null && (
                    initialConfig.customPrompt.isNotBlank() ||
                        initialConfig.customPersona.isNotBlank() ||
                        initialConfig.allowedSenderIds.isNotEmpty() ||
                        initialConfig.mutedSenderIds.isNotEmpty()
                    )
                if (hasExistingConfig) {
                    TextButton(onClick = onDelete) {
                        Text("Clear Rules", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = {
                        onSave(
                            GroupConfig(
                                roomId = room.roomId,
                                customPrompt = customPrompt.trim(),
                                customPersona = customPersona.trim(),
                                allowedSenderIds = sortedSelectedIds.map { it.trim() }.filter { it.isNotBlank() },
                                mutedSenderIds = mutedSenderIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                            )
                        )
                    }
                ) {
                    Text("Save Rules")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun GroupRuleDialogTitle(room: GroupChat) {
    Column {
        Text(
            text = "Configure Rules",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = room.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = room.roomId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun GroupRuleTextFields(
    customPrompt: String,
    onPromptChange: (String) -> Unit,
    customPersona: String,
    onPersonaChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = customPrompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Custom Focus / Filtering Instructions") },
            placeholder = {
                Text("e.g. Focus on Master's thesis deadlines. Ignore Bachelor notices and club activities.")
            },
            minLines = 2,
            maxLines = 4
        )
        OutlinedTextField(
            value = customPersona,
            onValueChange = onPersonaChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Persona Override (Optional)") },
            placeholder = { Text("Leave blank to use global persona") },
            singleLine = true
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SenderAllowlistHeader(
    selectedIds: List<String>,
    membersCount: Int,
    isLoadingMembers: Boolean,
    membersById: Map<String, GroupMember>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onRemoveSender: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Follow Only Selected Senders (Optional)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val subtitleText = when {
                    selectedIds.isNotEmpty() -> "${selectedIds.size} sender(s) selected — other members ignored before AI"
                    isLoadingMembers -> "Loading group members..."
                    else -> "Empty = follow all $membersCount members"
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selectedIds.isNotEmpty()) {
                TextButton(onClick = onClearAll) {
                    Text("Clear")
                }
            }
        }

        if (selectedIds.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                selectedIds.forEach { senderId ->
                    val label = membersById[senderId]?.displayName ?: senderId
                    InputChip(
                        selected = true,
                        onClick = { onRemoveSender(senderId) },
                        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove $label",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search group members by name or ID...") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            singleLine = true
        )
    }
}

@Composable
private fun GroupMemberRow(
    member: GroupMember,
    isChecked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .toggleable(
                value = isChecked,
                role = Role.Checkbox,
                onValueChange = onToggle
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = null
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (member.isOwner) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Owner",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Text(
                text = member.username,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
