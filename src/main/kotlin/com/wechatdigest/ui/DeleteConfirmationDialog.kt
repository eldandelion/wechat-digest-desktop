package com.wechatdigest.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.SourceMessageParser
import com.wechatdigest.core.sanitizer.MessageSanitizer
import com.wechatdigest.core.sanitizer.PrefilterMatchType

private enum class DismissActionMode {
    DISMISS_ONLY,
    MUTE_SENDER,
    ADD_PREFILTER_RULE
}

/**
 * Reusable confirmation dialog for destructive item deletions across screens.
 */
@Composable
fun DeleteConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Trust-loop dismissal dialog letting users dismiss a card once, mute its sender in the group,
 * or promote a recurring phrase into a static [PrefilterMatchType] noise-filter rule.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DismissCardDialog(
    card: EventCard,
    onDismissOnly: () -> Unit,
    onDismissAndMuteSender: () -> Unit,
    onDismissAndAddFilter: (pattern: String, matchType: PrefilterMatchType) -> Unit,
    onCancel: () -> Unit
) {
    val canMuteSender = card.originalSenderId.isNotBlank()
    val defaultPattern = remember(card.id) {
        SourceMessageParser.parse(card.sourceMessages)
            .firstOrNull()
            ?.body
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(60)
            ?: card.title.take(60)
    }
    var mode by remember(card.id) { mutableStateOf(DismissActionMode.DISMISS_ONLY) }
    var rulePattern by remember(card.id) { mutableStateOf(defaultPattern) }
    var matchType by remember(card.id) { mutableStateOf(PrefilterMatchType.CONTAINS) }
    val patternError = remember(mode, rulePattern, matchType) {
        if (mode == DismissActionMode.ADD_PREFILTER_RULE) {
            MessageSanitizer.validateRulePattern(rulePattern.trim(), matchType)
        } else {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Dismiss Notice") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Remove \"${card.title}\" from your event feed:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { mode = DismissActionMode.DISMISS_ONLY },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == DismissActionMode.DISMISS_ONLY,
                        onClick = { mode = DismissActionMode.DISMISS_ONLY }
                    )
                    Text("Dismiss this notice only", style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canMuteSender) { mode = DismissActionMode.MUTE_SENDER },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == DismissActionMode.MUTE_SENDER,
                        enabled = canMuteSender,
                        onClick = { mode = DismissActionMode.MUTE_SENDER }
                    )
                    Column {
                        Text(
                            text = "Dismiss & mute ${card.originalSender.ifBlank { "sender" }} in this group",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (canMuteSender) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                        if (!canMuteSender) {
                            Text(
                                text = "Sender ID unavailable on legacy card",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { mode = DismissActionMode.ADD_PREFILTER_RULE },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = mode == DismissActionMode.ADD_PREFILTER_RULE,
                        onClick = { mode = DismissActionMode.ADD_PREFILTER_RULE }
                    )
                    Text("Dismiss & block similar messages (prefilter)", style = MaterialTheme.typography.bodyMedium)
                }
                if (mode == DismissActionMode.ADD_PREFILTER_RULE) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = rulePattern,
                            onValueChange = { rulePattern = it },
                            label = { Text("Pattern to drop pre-LLM") },
                            isError = patternError != null,
                            supportingText = patternError?.let { err -> { Text(err) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                PrefilterMatchType.CONTAINS to "Contains",
                                PrefilterMatchType.WHOLE_MESSAGE to "Exact message",
                                PrefilterMatchType.REGEX to "Regex"
                            ).forEach { (candidateType, label) ->
                                FilterChip(
                                    selected = matchType == candidateType,
                                    onClick = { matchType = candidateType },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = patternError == null,
                onClick = {
                    when (mode) {
                        DismissActionMode.DISMISS_ONLY -> onDismissOnly()
                        DismissActionMode.MUTE_SENDER -> onDismissAndMuteSender()
                        DismissActionMode.ADD_PREFILTER_RULE -> onDismissAndAddFilter(rulePattern.trim(), matchType)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(
                    when (mode) {
                        DismissActionMode.DISMISS_ONLY -> "Dismiss"
                        DismissActionMode.MUTE_SENDER -> "Dismiss & Mute"
                        DismissActionMode.ADD_PREFILTER_RULE -> "Dismiss & Filter"
                    }
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}
