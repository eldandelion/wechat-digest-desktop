package com.wechatdigest.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wechatdigest.app.AppSettings
import com.wechatdigest.app.AppThemeMode
import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.llm.LocalOllamaModel
import java.util.Locale

@Composable
internal fun SettingsSectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun ThemeSettingsCard(
    selectedTheme: AppThemeMode,
    onSelectTheme: (AppThemeMode) -> Unit
) {
    SettingsSectionCard {
        Text(text = "Appearance & Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = "Choose how WeChat Digest looks across light and dark modes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppThemeMode.entries.forEach { mode ->
                val isSelected = selectedTheme == mode
                val icon = when (mode) {
                    AppThemeMode.SYSTEM -> if (isSelected) Icons.Filled.SettingsBrightness else Icons.Outlined.SettingsBrightness
                    AppThemeMode.LIGHT -> if (isSelected) Icons.Filled.LightMode else Icons.Outlined.LightMode
                    AppThemeMode.DARK -> if (isSelected) Icons.Filled.DarkMode else Icons.Outlined.DarkMode
                }
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectTheme(mode) },
                    leadingIcon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = { Text(text = mode.displayName, lineHeight = MaterialTheme.typography.labelLarge.fontSize) }
                )
            }
        }
    }
}

@Composable
internal fun WeChatStorageSettingsCard(
    dataDir: String,
    keysPath: String,
    messagesPerSync: String,
    parseAttachmentContents: Boolean,
    isAutoDetecting: Boolean,
    autoDetectFeedback: String?,
    onAutoDetect: () -> Unit,
    onLaunchWizard: () -> Unit,
    onDataDirChange: (String) -> Unit,
    onKeysPathChange: (String) -> Unit,
    onMessagesPerSyncChange: (String) -> Unit,
    onParseAttachmentContentsChange: (Boolean) -> Unit
) {
    SettingsSectionCard {
        Text(text = "WeChat Ingestion & Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAutoDetect, enabled = !isAutoDetecting) {
                Text(if (isAutoDetecting) "Detecting..." else "Auto-Detect WeChat Path")
            }
            OutlinedButton(onClick = onLaunchWizard) { Text("Launch Setup Wizard") }
        }
        autoDetectFeedback?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        OutlinedTextField(
            value = dataDir,
            onValueChange = onDataDirChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("WeChat Data Directory (db_storage or decrypted folder)") },
            placeholder = { Text("~/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files") },
            singleLine = true
        )
        OutlinedTextField(
            value = keysPath,
            onValueChange = onKeysPathChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("all_keys.json Path (Optional - auto-detected if left blank)") },
            placeholder = { Text("~/.wcdb-key-tool/all_keys.json") },
            singleLine = true
        )
        OutlinedTextField(
            value = messagesPerSync,
            onValueChange = { if (it.all(Char::isDigit)) onMessagesPerSyncChange(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Messages per group to scan on Sync") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        SettingsSwitchRow(
            title = "Parse Downloaded Attachments for AI Summary",
            subtitle = "Read bounded previews from downloaded Excel, PDF, and Word files when summarizing notices",
            checked = parseAttachmentContents,
            onCheckedChange = onParseAttachmentContentsChange
        )
    }
}

@Composable
internal fun BackgroundSyncSettingsCard(
    autoSyncEnabled: Boolean,
    syncIntervalMinutes: Int,
    notifyOnNewEvents: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    onSelectInterval: (Int) -> Unit,
    onNotifyChange: (Boolean) -> Unit
) {
    SettingsSectionCard {
        Text(text = "Background Sync & macOS Notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SettingsSwitchRow(
            title = "Automatic Background Sync",
            subtitle = "Periodically scan monitored groups and extract new events in the background",
            checked = autoSyncEnabled,
            onCheckedChange = onAutoSyncChange
        )
        if (autoSyncEnabled) {
            Text("Sync Interval", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours").forEach { (mins, label) ->
                    FilterChip(selected = syncIntervalMinutes == mins, onClick = { onSelectInterval(mins) }, label = { Text(label) })
                }
            }
        }
        SettingsSwitchRow(
            title = "macOS System Notifications",
            subtitle = "Display macOS desktop notification banner when new notices are extracted",
            checked = notifyOnNewEvents,
            onCheckedChange = onNotifyChange
        )
    }
}

@Composable
internal fun LlmEngineSettingsCard(
    providerType: LlmProviderType,
    endpointUrl: String,
    apiKey: String,
    modelName: String,
    detectedOllamaModels: List<LocalOllamaModel>,
    isDetectingOllama: Boolean,
    ollamaStatusMessage: String?,
    onSelectOpenAi: () -> Unit,
    onSelectOllama: () -> Unit,
    onDetectOllama: () -> Unit,
    onEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onSelectOllamaModel: (String) -> Unit
) {
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isModelDropdownOpen by remember { mutableStateOf(false) }
    val insecureRemoteUrl = remember(endpointUrl, providerType) {
        val trimmed = endpointUrl.trim().lowercase(Locale.ROOT)
        trimmed.startsWith("http://") && !EventExtractor.isLocalEndpoint(trimmed)
    }

    SettingsSectionCard {
        Text(text = "AI / LLM Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = providerType == LlmProviderType.OPENAI_COMPATIBLE, onClick = onSelectOpenAi, label = { Text("OpenAI / Cloud Compatible") })
            FilterChip(selected = providerType == LlmProviderType.OLLAMA, onClick = onSelectOllama, label = { Text("Local Ollama") })
        }
        if (providerType == LlmProviderType.OLLAMA) {
            OllamaDetectionRow(isDetecting = isDetectingOllama, statusMessage = ollamaStatusMessage, onDetect = onDetectOllama)
        }
        OutlinedTextField(
            value = endpointUrl,
            onValueChange = onEndpointChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Endpoint Base URL") },
            isError = insecureRemoteUrl,
            supportingText = if (insecureRemoteUrl) {
                { Text("Remote endpoints must use https:// to protect your API key and chat contents.") }
            } else null,
            singleLine = true
        )
        if (providerType == LlmProviderType.OPENAI_COMPATIBLE) {
            ApiKeyField(apiKey = apiKey, visible = isPasswordVisible, onToggleVisibility = { isPasswordVisible = !isPasswordVisible }, onValueChange = onApiKeyChange)
        }
        ModelSelectorField(
            providerType = providerType,
            modelName = modelName,
            detectedModels = detectedOllamaModels,
            isDropdownOpen = isModelDropdownOpen,
            onToggleDropdown = { isModelDropdownOpen = !isModelDropdownOpen },
            onDismissDropdown = { isModelDropdownOpen = false },
            onValueChange = onModelNameChange,
            onSelectModel = { name -> isModelDropdownOpen = false; onSelectOllamaModel(name) }
        )
    }
}

@Composable
private fun OllamaDetectionRow(isDetecting: Boolean, statusMessage: String?, onDetect: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onDetect, enabled = !isDetecting) {
            if (isDetecting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Searching Ollama...")
            } else {
                Text("Auto-Detect Local Ollama")
            }
        }
        statusMessage?.let { status ->
            val statusColor = when {
                status.startsWith("Connected") -> MaterialTheme.colorScheme.primary
                status.startsWith("Cannot") || status.startsWith("Detection failed") -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.secondary
            }
            Text(text = status, style = MaterialTheme.typography.bodySmall, color = statusColor)
        }
    }
}

@Composable
private fun ApiKeyField(apiKey: String, visible: Boolean, onToggleVisibility: () -> Unit, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = apiKey,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("API Key") },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (visible) "Hide key" else "Show key")
            }
        },
        singleLine = true
    )
}

@Composable
private fun ModelSelectorField(
    providerType: LlmProviderType,
    modelName: String,
    detectedModels: List<LocalOllamaModel>,
    isDropdownOpen: Boolean,
    onToggleDropdown: () -> Unit,
    onDismissDropdown: () -> Unit,
    onValueChange: (String) -> Unit,
    onSelectModel: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = modelName,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model Name") },
            placeholder = { Text(if (providerType == LlmProviderType.OPENAI_COMPATIBLE) "gpt-4o-mini" else "llama3.2") },
            trailingIcon = {
                if (providerType == LlmProviderType.OLLAMA && detectedModels.isNotEmpty()) {
                    IconButton(onClick = onToggleDropdown) {
                        Icon(imageVector = if (isDropdownOpen) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, contentDescription = "Select local model")
                    }
                }
            },
            singleLine = true
        )
        if (providerType == LlmProviderType.OLLAMA && detectedModels.isNotEmpty()) {
            DropdownMenu(expanded = isDropdownOpen, onDismissRequest = onDismissDropdown, modifier = Modifier.fillMaxWidth(0.9f)) {
                detectedModels.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(model.name, fontWeight = FontWeight.Medium)
                                if (model.subtitle.isNotBlank()) {
                                    Text(model.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        },
                        onClick = { onSelectModel(model.name) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun PersonaLocalizationCard(
    persona: String,
    targetLanguage: String,
    onPersonaChange: (String) -> Unit,
    onTargetLanguageChange: (String) -> Unit
) {
    SettingsSectionCard {
        Text(text = "Persona & Localization", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = persona,
            onValueChange = onPersonaChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("User Persona Context") },
            placeholder = { Text(AppSettings().userPersona) },
            singleLine = true
        )
        OutlinedTextField(
            value = targetLanguage,
            onValueChange = onTargetLanguageChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Target Language Code (e.g. en, zh, ja)") },
            placeholder = { Text("en") },
            singleLine = true
        )
    }
}

@Composable
internal fun LocalAppDataResetCard(onClearEventsClick: () -> Unit, onFactoryResetClick: () -> Unit) {
    SettingsSectionCard {
        Text(text = "Local App Data & Reset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = "Clear WeChat Digest's own local SQLite database (app.db) and decrypted mirror cache. Your original WeChat messages and files are strictly read-only and will NEVER be modified.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onClearEventsClick) { Text("Clear Events & Checkpoints") }
            OutlinedButton(
                onClick = onFactoryResetClick,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset Local App Database")
            }
        }
    }
}

@Composable
internal fun SettingsResetDialogs(
    showClearEventsDialog: Boolean,
    showFactoryResetDialog: Boolean,
    onDismissClearEvents: () -> Unit,
    onConfirmClearEvents: () -> Unit,
    onDismissFactoryReset: () -> Unit,
    onConfirmFactoryReset: () -> Unit
) {
    if (showClearEventsDialog) {
        AlertDialog(
            onDismissRequest = onDismissClearEvents,
            title = { Text("Clear Events & Checkpoints?") },
            text = { Text("This will delete all extracted event cards and sync checkpoints from WeChat Digest's local database so you can re-scan cleanly. Your WeChat path, AI settings, and custom group rules will be preserved. Original WeChat files will not be touched.") },
            confirmButton = { Button(onClick = onConfirmClearEvents) { Text("Clear Events") } },
            dismissButton = { OutlinedButton(onClick = onDismissClearEvents) { Text("Cancel") } }
        )
    }
    if (showFactoryResetDialog) {
        AlertDialog(
            onDismissRequest = onDismissFactoryReset,
            title = { Text("Reset Local App Database?") },
            text = { Text("This will permanently wipe all WeChat Digest local data (event cards, sync checkpoints, custom group rules, saved settings, and local decrypted cache) and restore factory defaults.\n\nNote: Your original WeChat messages and database container will NEVER be modified or deleted.") },
            confirmButton = {
                Button(onClick = onConfirmFactoryReset, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Reset App Database")
                }
            },
            dismissButton = { OutlinedButton(onClick = onDismissFactoryReset) { Text("Cancel") } }
        )
    }
}
