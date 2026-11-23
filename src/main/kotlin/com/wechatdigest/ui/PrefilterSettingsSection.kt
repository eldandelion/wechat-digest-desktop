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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wechatdigest.core.sanitizer.MessageSanitizer
import com.wechatdigest.core.sanitizer.PrefilterConfig
import com.wechatdigest.core.sanitizer.PrefilterMatchType
import com.wechatdigest.core.sanitizer.PrefilterRule
import java.util.Locale

/**
 * Settings card allowing users to configure static message prefiltering thresholds and rules.
 */
@Composable
fun PrefilterSettingsSection(
    minLengthText: String,
    onMinLengthChange: (String) -> Unit,
    rules: List<PrefilterRule>,
    onRulesChange: (List<PrefilterRule>) -> Unit,
    onRestoreDefaults: () -> Unit,
    onMarkDirty: () -> Unit
) {
    var rulePendingDelete by remember { mutableStateOf<PrefilterRule?>(null) }
    val previewMinLen = minLengthText.toIntOrNull() ?: PrefilterConfig.DEFAULT_MIN_MESSAGE_LENGTH

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrefilterHeaderRow(onRestoreDefaults = onRestoreDefaults)
            PrefilterMinLengthField(minLengthText = minLengthText, previewMinLen = previewMinLen) { next ->
                onMinLengthChange(next); onMarkDirty()
            }
            AddPrefilterRuleRow { newRule -> onRulesChange(rules + newRule); onMarkDirty() }
            PrefilterRulesList(
                rules = rules,
                onToggleRule = { ruleId, enabled ->
                    onRulesChange(rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
                    onMarkDirty()
                },
                onRequestDeleteRule = { rulePendingDelete = it }
            )
        }
    }

    rulePendingDelete?.let { target ->
        DeleteConfirmationDialog(
            title = "Delete Prefilter Rule?",
            message = "Remove the ${target.matchType.displayName.lowercase(Locale.ROOT)} rule \"${target.pattern}\"?",
            onConfirm = { onRulesChange(rules.filterNot { it.id == target.id }); onMarkDirty(); rulePendingDelete = null },
            onDismiss = { rulePendingDelete = null }
        )
    }
}

@Composable
private fun PrefilterHeaderRow(onRestoreDefaults: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Message Prefiltering Rules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = "Statically ignore short noise, confirmations, and chat tags before sending messages to the AI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        OutlinedButton(onClick = onRestoreDefaults) { Text("Restore Defaults") }
    }
}

@Composable
private fun PrefilterMinLengthField(minLengthText: String, previewMinLen: Int, onDigitsChange: (String) -> Unit) {
    OutlinedTextField(
        value = minLengthText,
        onValueChange = { if (it.all(Char::isDigit)) onDigitsChange(it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Minimum Message Length (characters, 0 to disable)") },
        supportingText = {
            Text(if (previewMinLen > 0) "Messages shorter than $previewMinLen characters are ignored." else "Minimum length filtering is disabled.")
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPrefilterRuleRow(onAddRule: (PrefilterRule) -> Unit) {
    var newPattern by remember { mutableStateOf("") }
    var selectedMatchType by remember { mutableStateOf(PrefilterMatchType.WHOLE_MESSAGE) }
    var isMatchTypeDropdownOpen by remember { mutableStateOf(false) }
    val patternError = remember(newPattern, selectedMatchType) {
        if (newPattern.isBlank()) null else MessageSanitizer.validateRulePattern(newPattern, selectedMatchType)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "Add Ignore Rule", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newPattern,
                onValueChange = { newPattern = it },
                modifier = Modifier.weight(1f, fill = true).align(Alignment.CenterVertically),
                label = { Text("Word or pattern") },
                placeholder = { Text("e.g. received, 收到, [Sticker]") },
                isError = patternError != null,
                singleLine = true
            )
            MatchTypeDropdown(selectedMatchType, isMatchTypeDropdownOpen, { isMatchTypeDropdownOpen = it }, { selectedMatchType = it }, Modifier.align(Alignment.CenterVertically))
            Button(
                modifier = Modifier.align(Alignment.CenterVertically),
                enabled = newPattern.isNotBlank() && patternError == null,
                onClick = {
                    val trimmed = newPattern.trim()
                    if (MessageSanitizer.validateRulePattern(trimmed, selectedMatchType) == null) {
                        onAddRule(PrefilterRule(pattern = trimmed, matchType = selectedMatchType, enabled = true))
                        newPattern = ""
                    }
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }
        if (patternError != null) {
            Text(text = patternError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MatchTypeDropdown(
    selectedMatchType: PrefilterMatchType,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (PrefilterMatchType) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        OutlinedButton(onClick = { onExpandedChange(true) }) {
            Text(selectedMatchType.displayName)
            Spacer(Modifier.width(4.dp))
            Icon(imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            PrefilterMatchType.entries.forEach { matchType ->
                DropdownMenuItem(text = { Text(matchType.displayName) }, onClick = { onSelect(matchType); onExpandedChange(false) })
            }
        }
    }
}

@Composable
private fun PrefilterRulesList(
    rules: List<PrefilterRule>,
    onToggleRule: (String, Boolean) -> Unit,
    onRequestDeleteRule: (PrefilterRule) -> Unit
) {
    if (rules.isEmpty()) {
        Text(text = "No prefiltering rules configured.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rules.forEachIndexed { index, rule ->
            key(rule.id) {
                PrefilterRuleRow(
                    rule = rule,
                    shape = segmentedItemShape(index = index, totalCount = rules.size),
                    onToggle = { enabled -> onToggleRule(rule.id, enabled) },
                    onDelete = { onRequestDeleteRule(rule) }
                )
            }
        }
    }
}

@Composable
private fun PrefilterRuleRow(
    rule: PrefilterRule,
    shape: RoundedCornerShape,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrefilterRuleMetadata(rule = rule, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = "Toggle prefilter rule ${rule.pattern}" }
                )
                IconButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Delete prefilter rule", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PrefilterRuleMetadata(rule: PrefilterRule, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (rule.enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = rule.matchType.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rule.enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text(text = rule.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
