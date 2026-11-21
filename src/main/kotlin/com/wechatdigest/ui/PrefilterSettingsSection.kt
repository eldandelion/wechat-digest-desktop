package com.wechatdigest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wechatdigest.core.sanitizer.MessageSanitizer
import com.wechatdigest.core.sanitizer.PrefilterConfig
import com.wechatdigest.core.sanitizer.PrefilterMatchType
import com.wechatdigest.core.sanitizer.PrefilterRule

/**
 * Settings card allowing users to configure static message prefiltering thresholds and rules.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrefilterSettingsSection(
    minLengthText: String,
    onMinLengthChange: (String) -> Unit,
    rules: List<PrefilterRule>,
    onRulesChange: (List<PrefilterRule>) -> Unit,
    onRestoreDefaults: () -> Unit,
    onMarkDirty: () -> Unit
) {
    var newPattern by remember { mutableStateOf("") }
    var selectedMatchType by remember { mutableStateOf(PrefilterMatchType.WHOLE_MESSAGE) }
    var isMatchTypeDropdownOpen by remember { mutableStateOf(false) }
    var rulePendingDelete by remember { mutableStateOf<PrefilterRule?>(null) }

    val patternValidationError = remember(newPattern, selectedMatchType) {
        if (newPattern.isBlank()) null
        else MessageSanitizer.validateRulePattern(newPattern, selectedMatchType)
    }
    val previewMinLen = minLengthText.toIntOrNull() ?: PrefilterConfig.DEFAULT_MIN_MESSAGE_LENGTH

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Message Prefiltering Rules",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Statically ignore short noise, confirmations, and chat tags before sending messages to the AI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                OutlinedButton(onClick = onRestoreDefaults) {
                    Text("Restore Defaults")
                }
            }

            OutlinedTextField(
                value = minLengthText,
                onValueChange = { next ->
                    if (next.all(Char::isDigit)) {
                        onMinLengthChange(next)
                        onMarkDirty()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Minimum Message Length (characters, 0 to disable)") },
                supportingText = {
                    Text(
                        if (previewMinLen > 0) {
                            "Messages shorter than $previewMinLen characters are ignored."
                        } else {
                            "Minimum length filtering is disabled."
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            // Add new custom prefiltering rule
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Add Ignore Rule",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newPattern,
                        onValueChange = { newPattern = it },
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .align(Alignment.CenterVertically),
                        label = { Text("Word or pattern") },
                        placeholder = { Text("e.g. received, 收到, [Sticker]") },
                        isError = patternValidationError != null,
                        singleLine = true
                    )

                    Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                        OutlinedButton(onClick = { isMatchTypeDropdownOpen = true }) {
                            Text(selectedMatchType.displayName)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = if (isMatchTypeDropdownOpen) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = "Select match type",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = isMatchTypeDropdownOpen,
                            onDismissRequest = { isMatchTypeDropdownOpen = false }
                        ) {
                            PrefilterMatchType.entries.forEach { matchType ->
                                DropdownMenuItem(
                                    text = { Text(matchType.displayName) },
                                    onClick = {
                                        selectedMatchType = matchType
                                        isMatchTypeDropdownOpen = false
                                    }
                                )
                            }
                        }
                    }

                    Button(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        enabled = newPattern.isNotBlank() && patternValidationError == null,
                        onClick = {
                            val trimmed = newPattern.trim()
                            if (MessageSanitizer.validateRulePattern(trimmed, selectedMatchType) == null) {
                                val updated = rules + PrefilterRule(
                                    pattern = trimmed,
                                    matchType = selectedMatchType,
                                    enabled = true
                                )
                                onRulesChange(updated)
                                onMarkDirty()
                                newPattern = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }

                if (patternValidationError != null) {
                    Text(
                        text = patternValidationError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Active & built-in rules list (non-lazy Column inside scrollable Settings screen)
            if (rules.isEmpty()) {
                Text(
                    text = "No prefiltering rules configured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rules.forEach { rule ->
                        PrefilterRuleRow(
                            rule = rule,
                            onToggle = { enabled ->
                                onRulesChange(rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
                                onMarkDirty()
                            },
                            onDelete = {
                                rulePendingDelete = rule
                            }
                        )
                    }
                }
            }
        }
    }

    rulePendingDelete?.let { target ->
        DeleteConfirmationDialog(
            title = "Delete Prefilter Rule?",
            message = "Remove the ${target.matchType.displayName.lowercase()} rule \"${target.pattern}\"? Click Save Settings afterward to persist this change.",
            onConfirm = {
                onRulesChange(rules.filterNot { it.id == target.id })
                onMarkDirty()
                rulePendingDelete = null
            },
            onDismiss = { rulePendingDelete = null }
        )
    }
}

@Composable
private fun PrefilterRuleRow(
    rule: PrefilterRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (rule.enabled) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = rule.matchType.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (rule.enabled) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = rule.pattern,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        fontFamily = if (rule.matchType == PrefilterMatchType.REGEX) FontFamily.Monospace else FontFamily.Default
                    )
                }

                if (rule.label.isNotBlank()) {
                    Text(
                        text = rule.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete prefilter rule",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
