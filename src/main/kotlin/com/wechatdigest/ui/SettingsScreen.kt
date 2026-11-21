package com.wechatdigest.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wechatdigest.app.AppSettings
import com.wechatdigest.app.AppState
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.sanitizer.PrefilterConfig

@Composable
fun SettingsScreen(appState: AppState) {
    val currentSettings by appState.settings.collectAsState()

    var dataDir by remember(currentSettings) { mutableStateOf(currentSettings.wechatDataDir) }
    var keysPath by remember(currentSettings) { mutableStateOf(currentSettings.keysPath) }
    var providerType by remember(currentSettings) { mutableStateOf(currentSettings.llm.providerType) }
    var endpointUrl by remember(currentSettings) { mutableStateOf(currentSettings.llm.endpointUrl) }
    var apiKey by remember(currentSettings) { mutableStateOf(currentSettings.llm.apiKey) }
    var modelName by remember(currentSettings) { mutableStateOf(currentSettings.llm.modelName) }
    var persona by remember(currentSettings) { mutableStateOf(currentSettings.userPersona) }
    var targetLanguage by remember(currentSettings) { mutableStateOf(currentSettings.targetLanguage) }
    var messagesPerSync by remember(currentSettings) { mutableStateOf(currentSettings.messagesPerSync.toString()) }
    var autoSyncEnabled by remember(currentSettings) { mutableStateOf(currentSettings.autoSyncEnabled) }
    var syncIntervalMinutes by remember(currentSettings) { mutableStateOf(currentSettings.syncIntervalMinutes) }
    var notifyOnNewEvents by remember(currentSettings) { mutableStateOf(currentSettings.notifyOnNewEvents) }
    var minPrefilterLength by remember(currentSettings) {
        mutableStateOf(currentSettings.prefilter.minMessageLength.toString())
    }
    var prefilterRules by remember(currentSettings) { mutableStateOf(currentSettings.prefilter.rules) }

    var isPasswordVisible by remember { mutableStateOf(false) }
    var isSavedNoticeVisible by remember { mutableStateOf(false) }
    var autoDetectFeedback by remember { mutableStateOf<String?>(null) }
    var isModelDropdownOpen by remember { mutableStateOf(false) }
    var showClearEventsDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }

    val detectedOllamaModels by appState.detectedOllamaModels.collectAsState()
    val isDetectingOllama by appState.isDetectingOllama.collectAsState()
    val ollamaStatusMessage by appState.ollamaStatusMessage.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val defaultSettings = AppSettings()
                        appState.updateSettings(defaultSettings)
                    }
                ) {
                    Text("Reset Defaults")
                }

                Button(
                    onClick = {
                        val parsedCount = messagesPerSync.toIntOrNull() ?: 50
                        val parsedMinPrefilterLength = (
                            minPrefilterLength.toIntOrNull() ?: PrefilterConfig.DEFAULT_MIN_MESSAGE_LENGTH
                            ).coerceAtLeast(0)
                        val updated = currentSettings.copy(
                            wechatDataDir = dataDir.trim(),
                            keysPath = keysPath.trim(),
                            llm = currentSettings.llm.copy(
                                providerType = providerType,
                                endpointUrl = endpointUrl.trim(),
                                apiKey = apiKey.trim(),
                                modelName = modelName.trim()
                            ),
                            userPersona = persona.trim(),
                            targetLanguage = targetLanguage.trim(),
                            messagesPerSync = parsedCount,
                            autoSyncEnabled = autoSyncEnabled,
                            syncIntervalMinutes = syncIntervalMinutes,
                            notifyOnNewEvents = notifyOnNewEvents,
                            prefilter = PrefilterConfig(
                                minMessageLength = parsedMinPrefilterLength,
                                rules = prefilterRules
                            )
                        )
                        appState.updateSettings(updated)
                        isSavedNoticeVisible = true
                    }
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isSavedNoticeVisible) "Saved!" else "Save Settings")
                }
            }
        }

        // Section: WeChat Database Ingestion
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "WeChat Ingestion & Storage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val candidates = appState.autoDetectWeChatCandidates()
                            if (candidates.isNotEmpty()) {
                                dataDir = candidates.first().path.absolutePath
                                autoDetectFeedback = "Detected: ${candidates.first().label}"
                            } else {
                                autoDetectFeedback = "No WeChat database found automatically."
                            }
                            isSavedNoticeVisible = false
                        }
                    ) {
                        Text("Auto-Detect WeChat Path")
                    }

                    OutlinedButton(
                        onClick = { appState.setShowWizard(true) }
                    ) {
                        Text("Launch Setup Wizard")
                    }
                }

                autoDetectFeedback?.let { feedback ->
                    Text(
                        text = feedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = dataDir,
                    onValueChange = { dataDir = it; isSavedNoticeVisible = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("WeChat Data Directory (db_storage or decrypted folder)") },
                    placeholder = { Text("~/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = keysPath,
                    onValueChange = { keysPath = it; isSavedNoticeVisible = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("all_keys.json Path (Optional - auto-detected if left blank)") },
                    placeholder = { Text("~/.wcdb-key-tool/all_keys.json") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = messagesPerSync,
                    onValueChange = { messagesPerSync = it; isSavedNoticeVisible = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Messages per group to scan on Sync") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        }

        // Section: Background Sync & Notifications
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Background Sync & macOS Notifications",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Automatic Background Sync", fontWeight = FontWeight.Medium)
                        Text(
                            "Periodically scan monitored groups and extract new events in the background",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = autoSyncEnabled,
                        onCheckedChange = { autoSyncEnabled = it; isSavedNoticeVisible = false }
                    )
                }

                if (autoSyncEnabled) {
                    Text("Sync Interval", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours").forEach { (mins, label) ->
                            FilterChip(
                                selected = syncIntervalMinutes == mins,
                                onClick = { syncIntervalMinutes = mins; isSavedNoticeVisible = false },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("macOS System Notifications", fontWeight = FontWeight.Medium)
                        Text(
                            "Display macOS desktop notification banner when new notices are extracted",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = notifyOnNewEvents,
                        onCheckedChange = { notifyOnNewEvents = it; isSavedNoticeVisible = false }
                    )
                }
            }
        }

        // Section: LLM Engine Configuration
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "AI / LLM Engine",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = providerType == LlmProviderType.OPENAI_COMPATIBLE,
                        onClick = {
                            providerType = LlmProviderType.OPENAI_COMPATIBLE
                            if (endpointUrl == "http://localhost:11434") {
                                endpointUrl = "https://api.openai.com/v1"
                            }
                            isSavedNoticeVisible = false
                        },
                        label = { Text("OpenAI / Cloud Compatible") }
                    )

                    FilterChip(
                        selected = providerType == LlmProviderType.OLLAMA,
                        onClick = {
                            providerType = LlmProviderType.OLLAMA
                            if (endpointUrl == "https://api.openai.com/v1") {
                                endpointUrl = "http://localhost:11434"
                            }
                            if (detectedOllamaModels.isEmpty() && !isDetectingOllama) {
                                appState.detectLocalOllama(endpointUrl) { detectedEndpoint, firstModel ->
                                    endpointUrl = detectedEndpoint
                                    if (modelName.isBlank() || modelName == "gpt-4o-mini") {
                                        firstModel?.let { modelName = it }
                                    }
                                }
                            }
                            isSavedNoticeVisible = false
                        },
                        label = { Text("Local Ollama") }
                    )
                }

                if (providerType == LlmProviderType.OLLAMA) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                appState.detectLocalOllama(endpointUrl) { detectedEndpoint, firstModel ->
                                    endpointUrl = detectedEndpoint
                                    if (modelName.isBlank() || modelName == "gpt-4o-mini") {
                                        firstModel?.let { modelName = it }
                                    }
                                }
                                isSavedNoticeVisible = false
                            },
                            enabled = !isDetectingOllama
                        ) {
                            if (isDetectingOllama) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Searching Ollama...")
                            } else {
                                Text("Auto-Detect Local Ollama")
                            }
                        }

                        ollamaStatusMessage?.let { status ->
                            val statusColor = when {
                                status.startsWith("Connected") -> MaterialTheme.colorScheme.primary
                                status.startsWith("Cannot") || status.startsWith("Detection failed") -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.secondary
                            }
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = endpointUrl,
                    onValueChange = { endpointUrl = it; isSavedNoticeVisible = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Endpoint Base URL") },
                    singleLine = true
                )

                if (providerType == LlmProviderType.OPENAI_COMPATIBLE) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; isSavedNoticeVisible = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isPasswordVisible) "Hide key" else "Show key"
                                )
                            }
                        },
                        singleLine = true
                    )
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it; isSavedNoticeVisible = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model Name") },
                        placeholder = { Text(if (providerType == LlmProviderType.OPENAI_COMPATIBLE) "gpt-4o-mini" else "llama3.2") },
                        trailingIcon = {
                            if (providerType == LlmProviderType.OLLAMA && detectedOllamaModels.isNotEmpty()) {
                                IconButton(onClick = { isModelDropdownOpen = !isModelDropdownOpen }) {
                                    Icon(
                                        imageVector = if (isModelDropdownOpen) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                        contentDescription = "Select local model"
                                    )
                                }
                            }
                        },
                        singleLine = true
                    )

                    if (providerType == LlmProviderType.OLLAMA && detectedOllamaModels.isNotEmpty()) {
                        DropdownMenu(
                            expanded = isModelDropdownOpen,
                            onDismissRequest = { isModelDropdownOpen = false },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            detectedOllamaModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(model.name, fontWeight = FontWeight.Medium)
                                            if (model.subtitle.isNotBlank()) {
                                                Text(
                                                    model.subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        modelName = model.name
                                        isModelDropdownOpen = false
                                        isSavedNoticeVisible = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section: Persona & Localization
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Persona & Localization",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = persona,
                    onValueChange = { persona = it; isSavedNoticeVisible = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("User Persona Context") },
                    placeholder = { Text("international student studying at university") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = targetLanguage,
                    onValueChange = { targetLanguage = it; isSavedNoticeVisible = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Target Language Code (e.g. en, zh, ja)") },
                    placeholder = { Text("en") },
                    singleLine = true
                )
            }
        }

        // Section: Message Prefiltering Rules
        PrefilterSettingsSection(
            minLengthText = minPrefilterLength,
            onMinLengthChange = { minPrefilterLength = it },
            rules = prefilterRules,
            onRulesChange = { prefilterRules = it },
            onRestoreDefaults = {
                minPrefilterLength = PrefilterConfig.DEFAULT_MIN_MESSAGE_LENGTH.toString()
                prefilterRules = PrefilterConfig.defaultRules()
                isSavedNoticeVisible = false
            },
            onMarkDirty = { isSavedNoticeVisible = false }
        )

        // Section: Local App Data & Reset
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Local App Data & Reset",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )

                Text(
                    text = "Clear WeChat Digest's own local SQLite database (app.db) and decrypted mirror cache. Your original WeChat messages and files are strictly read-only and will NEVER be modified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { showClearEventsDialog = true }
                    ) {
                        Text("Clear Events & Checkpoints")
                    }

                    OutlinedButton(
                        onClick = { showFactoryResetDialog = true },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Reset Local App Database")
                    }
                }
            }
        }
    }

    if (showClearEventsDialog) {
        AlertDialog(
            onDismissRequest = { showClearEventsDialog = false },
            title = { Text("Clear Events & Checkpoints?") },
            text = {
                Text("This will delete all extracted event cards and sync checkpoints from WeChat Digest's local database so you can re-scan cleanly. Your WeChat path, AI settings, and custom group rules will be preserved. Original WeChat files will not be touched.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        appState.clearEventsAndCheckpoints()
                        showClearEventsDialog = false
                    }
                ) {
                    Text("Clear Events")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearEventsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showFactoryResetDialog) {
        AlertDialog(
            onDismissRequest = { showFactoryResetDialog = false },
            title = { Text("Reset Local App Database?") },
            text = {
                Text("This will permanently wipe all WeChat Digest local data (event cards, sync checkpoints, custom group rules, saved settings, and local decrypted cache) and restore factory defaults.\n\nNote: Your original WeChat messages and database container will NEVER be modified or deleted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        appState.resetLocalAppDatabase()
                        autoDetectFeedback = null
                        isSavedNoticeVisible = false
                        showFactoryResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Reset App Database")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showFactoryResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
