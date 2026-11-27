package com.wechatdigest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wechatdigest.core.db.KeyExtractionState
import com.wechatdigest.core.db.KeyPreflightStatus
import com.wechatdigest.core.db.WeChatCodeSignState

/**
 * Interactive macOS WeChat database decryption assistant shared across Onboarding Wizard and Settings.
 * Displays live preflight checks (`WeChat.app` Hardened Runtime status, `lldb`, key verification),
 * guides Touch ID/Admin extraction, and offers a safe copyable Terminal fallback.
 */
@Composable
internal fun KeyExtractionGuideDialog(
    preflightStatus: KeyPreflightStatus?,
    extractionState: KeyExtractionState,
    manualCommand: String?,
    onRecheckPreflight: () -> Unit,
    onInstallXcodeTools: () -> Unit,
    onStartExtraction: (resignWeChat: Boolean) -> Unit,
    onCancelExtraction: () -> Unit,
    onCommandCopied: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var allowResign by remember(preflightStatus?.needsResign) {
        mutableStateOf(preflightStatus?.needsResign ?: true)
    }
    var showTerminalFallback by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRecheckPreflight()
    }

    val isRunning = extractionState is KeyExtractionState.Running

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = "WeChat Database Key Setup",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "WeChat encrypts its local SQLite databases with SQLCipher 4. Follow these prerequisites on macOS to unlock and verify your keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                PreflightChecklistCard(
                    preflight = preflightStatus,
                    isRunning = isRunning,
                    onInstallXcodeTools = onInstallXcodeTools,
                    onRecheckPreflight = onRecheckPreflight
                )

                if (preflightStatus?.needsResign == true) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "macOS Hardened Runtime Notice",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Apple blocks memory inspection on stock WeChat builds. Granting admin permission will locally ad-hoc re-sign WeChat.app and restart it so keys can be derived. Future WeChat app updates will restore Apple's signature.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = allowResign,
                                    onCheckedChange = { if (!isRunning) allowResign = it },
                                    enabled = !isRunning
                                )
                                Text(
                                    text = "Automatically re-sign & restart WeChat.app during extraction",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                ExtractionStatusPanel(extractionState = extractionState)

                if (manualCommand != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { showTerminalFallback = !showTerminalFallback }) {
                            Text(if (showTerminalFallback) "Hide Manual Terminal Command" else "Prefer Terminal? Show Manual Command")
                        }
                        if (showTerminalFallback) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = manualCommand,
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(manualCommand))
                                                onCommandCopied()
                                            }
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Copy Command")
                                        }
                                        OutlinedButton(onClick = onRecheckPreflight, enabled = !isRunning) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("I Ran It — Re-check")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canStart = preflightStatus != null &&
                    preflightStatus.isMacOs &&
                    preflightStatus.wechatDataDir != null &&
                    preflightStatus.hasPython3 &&
                    preflightStatus.hasLldb &&
                    preflightStatus.codeSignState != WeChatCodeSignState.APP_NOT_FOUND &&
                    (!preflightStatus.needsResign || allowResign)
                if (isRunning) {
                    OutlinedButton(onClick = onCancelExtraction) {
                        Text("Cancel Extraction")
                    }
                } else if (extractionState is KeyExtractionState.Succeeded || preflightStatus?.isReadyToSync == true) {
                    if (preflightStatus?.isEncryptedDb == true && extractionState !is KeyExtractionState.Succeeded) {
                        OutlinedButton(
                            onClick = { onStartExtraction(allowResign) },
                            enabled = canStart
                        ) {
                            Text("Re-extract Keys")
                        }
                    }
                    Button(onClick = onDismiss) {
                        Text("Done")
                    }
                } else {
                    Button(
                        onClick = { onStartExtraction(allowResign) },
                        enabled = canStart
                    ) {
                        Text("Start Guided Extraction")
                    }
                }
            }
        },
        dismissButton = {
            if (!isRunning && extractionState !is KeyExtractionState.Succeeded && preflightStatus?.isReadyToSync != true) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun PreflightChecklistCard(
    preflight: KeyPreflightStatus?,
    isRunning: Boolean,
    onInstallXcodeTools: () -> Unit,
    onRecheckPreflight: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Environment Checklist",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onRecheckPreflight, enabled = !isRunning) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Re-check")
                }
            }
            if (preflight == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Inspecting macOS prerequisites...", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                ChecklistRow(
                    ok = preflight.wechatDataDir != null,
                    label = if (preflight.wechatDataDir != null) {
                        "WeChat folder: ${preflight.wechatDataDir.name}"
                    } else {
                        "WeChat data directory not found"
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ChecklistRow(
                        ok = preflight.hasLldb,
                        label = if (preflight.hasLldb) {
                            "Xcode Command Line Tools (lldb) ready"
                        } else {
                            "Xcode Command Line Tools (lldb) required"
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (!preflight.hasLldb) {
                        TextButton(onClick = onInstallXcodeTools, enabled = !isRunning) {
                            Text("Install Tools")
                        }
                    }
                }
                val signOk = preflight.codeSignState == WeChatCodeSignState.ADHOC_OR_DEBUGGABLE
                val signLabel = when (preflight.codeSignState) {
                    WeChatCodeSignState.ADHOC_OR_DEBUGGABLE -> "WeChat.app signature ready for key capture"
                    WeChatCodeSignState.HARDENED_RUNTIME -> "WeChat.app${preflight.wechatVersion?.let { " ($it)" }.orEmpty()} uses Hardened Runtime (re-sign needed)"
                    WeChatCodeSignState.APP_NOT_FOUND -> "WeChat.app not found in /Applications"
                    WeChatCodeSignState.UNKNOWN -> "WeChat.app signature status unknown"
                }
                ChecklistRow(ok = signOk, warnOnly = preflight.codeSignState == WeChatCodeSignState.HARDENED_RUNTIME, label = signLabel)

                val keyReport = preflight.verificationReport
                if (!preflight.isEncryptedDb && preflight.wechatDataDir != null) {
                    ChecklistRow(ok = true, label = "Databases are already plaintext")
                } else if (keyReport != null) {
                    val keyLabel = if (keyReport.allVerified) {
                        "Keys verified: ${keyReport.verifiedDbs}/${keyReport.totalEncryptedDbs} databases unlocked"
                    } else {
                        "Keys verified: ${keyReport.verifiedDbs}/${keyReport.totalEncryptedDbs} (${keyReport.missingOrInvalidDbs.size} need extraction)"
                    }
                    ChecklistRow(ok = keyReport.allVerified, warnOnly = !keyReport.allVerified, label = keyLabel)
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    ok: Boolean,
    label: String,
    warnOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    val icon = when {
        ok -> Icons.Default.CheckCircle
        warnOnly -> Icons.Default.WarningAmber
        else -> Icons.Default.ErrorOutline
    }
    val tint: Color = when {
        ok -> Color(0xFF2E7D32)
        warnOnly -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExtractionStatusPanel(extractionState: KeyExtractionState) {
    when (extractionState) {
        KeyExtractionState.Idle -> Unit
        is KeyExtractionState.Running -> {
            val isWaitingLogin = extractionState.stage == "lldb_wait"
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isWaitingLogin) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = if (isWaitingLogin) "Action Required in WeChat" else "Extracting Database Keys",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isWaitingLogin) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = extractionState.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isWaitingLogin) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        is KeyExtractionState.Succeeded -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Keys Extracted & Verified (${extractionState.verifiedCount}/${extractionState.totalCount})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Saved to ${extractionState.keysFile.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        is KeyExtractionState.Failed -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Extraction Not Completed",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = extractionState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        KeyExtractionState.Cancelled -> {
            Text(
                text = "Key extraction cancelled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
