package com.wechatdigest.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.wechatdigest.app.AppState
import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.detector.DiscoveredPath
import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.llm.local.LocalModelMirror
import com.wechatdigest.core.model.GroupChat
import com.wechatdigest.core.repository.WeChatContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class WizardStep {
    WECHAT_PATH,
    LLM_ENGINE,
    CHATROOMS
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingWizardDialog(
    appState: AppState,
    onDismiss: () -> Unit
) {
    val currentSettings by appState.settings.collectAsState()

    var currentStep by remember { mutableStateOf(WizardStep.WECHAT_PATH) }

    // Step 1 state (initialized once on wizard open so in-wizard model downloads/imports don't reset edits)
    var selectedPath by remember { mutableStateOf(currentSettings.wechatDataDir) }
    var candidatePaths by remember { mutableStateOf<List<DiscoveredPath>>(emptyList()) }
    var isScanningPaths by remember { mutableStateOf(false) }

    // Step 2 state
    var providerType by remember { mutableStateOf(currentSettings.llm.providerType) }
    var endpointUrl by remember { mutableStateOf(currentSettings.llm.endpointUrl) }
    var apiKey by remember { mutableStateOf(currentSettings.llm.apiKey) }
    var modelName by remember { mutableStateOf(currentSettings.llm.modelName) }
    var localModelId by remember { mutableStateOf(currentSettings.llm.localModelId) }
    var localModelMirror by remember { mutableStateOf(LocalModelMirror.fromKey(currentSettings.llm.localModelMirror)) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isModelDropdownOpen by remember { mutableStateOf(false) }

    val detectedOllamaModels by appState.detectedOllamaModels.collectAsState()
    val isDetectingOllama by appState.isDetectingOllama.collectAsState()
    val ollamaStatusMessage by appState.ollamaStatusMessage.collectAsState()
    val isTestingConnection by appState.isTestingLlmConnection.collectAsState()
    val connectionStatusMessage by appState.llmConnectionStatusMessage.collectAsState()
    val downloadedLocalModelIds by appState.downloadedLocalModelIds.collectAsState()
    val importedLocalModels by appState.importedLocalModels.collectAsState()
    val localModelDownloadStates by appState.localModelDownloadStates.collectAsState()
    val localRuntimeState by appState.localRuntimeState.collectAsState()
    val localServerState by appState.localServerState.collectAsState()

    val isSelectedLocalModelReady = remember(localModelId, downloadedLocalModelIds, importedLocalModels) {
        localModelId.isNotBlank() && appState.localModelStore.resolveModel(localModelId) != null
    }

    // Step 3 state
    var discoveredRooms by remember { mutableStateOf<List<GroupChat>>(emptyList()) }
    var selectedRoomIds by remember { mutableStateOf(currentSettings.monitoredRoomIds) }
    var isLoadingRooms by remember { mutableStateOf(false) }

    // Auto scan candidate paths & reset stale connection probe on open
    LaunchedEffect(Unit) {
        appState.clearConnectionStatus()
        isScanningPaths = true
        val detected = withContext(Dispatchers.IO) {
            appState.autoDetectWeChatCandidates()
        }
        candidatePaths = detected
        if (selectedPath.isBlank() && detected.isNotEmpty()) {
            selectedPath = detected.first().path.absolutePath
        }
        isScanningPaths = false
    }

    // Auto detect Ollama when arriving at step 2 if Ollama is selected
    LaunchedEffect(currentStep, providerType) {
        if (currentStep == WizardStep.LLM_ENGINE && providerType == LlmProviderType.OLLAMA) {
            if (detectedOllamaModels.isEmpty() && !isDetectingOllama) {
                appState.detectLocalOllama(endpointUrl) { detectedEndpoint, firstModel ->
                    endpointUrl = detectedEndpoint
                    if (modelName.isBlank() || modelName == "gpt-4o-mini") {
                        firstModel?.let { modelName = it }
                    }
                }
            }
        }
    }

    // Load rooms when arriving at step 3
    LaunchedEffect(currentStep, selectedPath) {
        if (currentStep == WizardStep.CHATROOMS && selectedPath.isNotBlank()) {
            isLoadingRooms = true
            try {
                val dir = com.wechatdigest.core.detector.WeChatPathDetector.resolveUserPath(selectedPath)
                if (dir != null && !java.nio.file.Files.isSymbolicLink(dir.toPath()) && dir.exists() && dir.isDirectory) {
                    val rooms = withContext(Dispatchers.IO) {
                        val defaultCacheDir = File(System.getProperty("user.home"), "Library/Application Support/WeChatDigest/decrypted")
                        val driver = WeChatDatabaseDriver(baseDir = dir, cacheDir = defaultCacheDir)
                        val repo = WeChatContactRepository(driver)
                        repo.listAllChatrooms()
                    }
                    discoveredRooms = rooms
                    if (selectedRoomIds.isEmpty() && rooms.isNotEmpty()) {
                        selectedRoomIds = setOf(rooms.first().roomId)
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("[OnboardingWizard] Failed to list rooms: ${com.wechatdigest.core.llm.LlmJsonUtils.sanitizeLogMessage(e.message)}")
            } finally {
                isLoadingRooms = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = when (currentStep) {
                        WizardStep.WECHAT_PATH -> "Welcome to WeChat Digest (1/3)"
                        WizardStep.LLM_ENGINE -> "Configure AI Engine (2/3)"
                        WizardStep.CHATROOMS -> "Select Monitored Groups (3/3)"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (currentStep) {
                    WizardStep.WECHAT_PATH -> {
                        Text(
                            text = "Locate your local WeChat SQLite database directory. WeChat Digest operates in strict read-only mode with zero ban risk.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (isScanningPaths) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Scanning common macOS WeChat storage paths...", style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (candidatePaths.isNotEmpty()) {
                            Text(
                                text = "Discovered WeChat Databases:",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                candidatePaths.forEach { candidate ->
                                    val isSelected = selectedPath == candidate.path.absolutePath
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedPath = candidate.path.absolutePath },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = candidate.label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = candidate.path.absolutePath,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = selectedPath,
                            onValueChange = { selectedPath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("WeChat Data Directory Path") },
                            placeholder = { Text("~/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files") },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        DesktopPickers.openDirectoryPicker("Select WeChat Data Directory") { dir ->
                                            selectedPath = dir.absolutePath
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Browse WeChat directory")
                                }
                            },
                            singleLine = true
                        )
                    }

                    WizardStep.LLM_ENGINE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 440.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Choose a built-in local model (no external setup needed), local Ollama, or an OpenAI-compatible API.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val builtInSelected = providerType == LlmProviderType.BUILT_IN_LOCAL
                                FilterChip(
                                    selected = builtInSelected,
                                    onClick = {
                                        providerType = LlmProviderType.BUILT_IN_LOCAL
                                        appState.clearConnectionStatus()
                                        if (localModelId.isBlank()) {
                                            localModelId = downloadedLocalModelIds.firstOrNull()
                                                ?: importedLocalModels.firstOrNull()?.id
                                                ?: ""
                                        }
                                    },
                                    label = { Text("Built-in Local") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (builtInSelected) Icons.Filled.Memory else Icons.Outlined.Memory,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )

                                val ollamaSelected = providerType == LlmProviderType.OLLAMA
                                FilterChip(
                                    selected = ollamaSelected,
                                    onClick = {
                                        providerType = LlmProviderType.OLLAMA
                                        appState.clearConnectionStatus()
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
                                    },
                                    label = { Text("Local Ollama") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (ollamaSelected) Icons.Filled.Computer else Icons.Outlined.Computer,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )

                                val openAiSelected = providerType == LlmProviderType.OPENAI_COMPATIBLE
                                FilterChip(
                                    selected = openAiSelected,
                                    onClick = {
                                        providerType = LlmProviderType.OPENAI_COMPATIBLE
                                        appState.clearConnectionStatus()
                                        if (endpointUrl == "http://localhost:11434") {
                                            endpointUrl = "https://api.openai.com/v1"
                                        }
                                    },
                                    label = { Text("OpenAI / Cloud") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (openAiSelected) Icons.Filled.Cloud else Icons.Outlined.Cloud,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }

                            if (providerType == LlmProviderType.BUILT_IN_LOCAL) {
                                LocalModelSettingsSection(
                                    hardwareInfo = appState.systemHardwareInfo,
                                    catalogModels = appState.localCatalogModels,
                                    downloadedModelIds = downloadedLocalModelIds,
                                    importedModels = importedLocalModels,
                                    downloadStates = localModelDownloadStates,
                                    runtimeState = localRuntimeState,
                                    serverState = localServerState,
                                    activeModelId = localModelId,
                                    selectedMirror = localModelMirror,
                                    onSelectMirror = { mirror -> localModelMirror = mirror },
                                    onSelectModel = { id -> localModelId = id },
                                    onDownloadModel = { id ->
                                        appState.downloadLocalModel(
                                            modelId = id,
                                            mirrorOverride = localModelMirror,
                                            autoSelectIfNone = false,
                                            onDownloaded = { spec ->
                                                if (localModelId.isBlank() || appState.localModelStore.resolveModel(localModelId) == null) {
                                                    localModelId = spec.id
                                                }
                                            }
                                        )
                                    },
                                    onCancelDownload = { id -> appState.cancelLocalModelDownload(id) },
                                    onDeleteDownloadedModel = { id ->
                                        appState.deleteDownloadedLocalModel(id)
                                        if (localModelId == id) {
                                            localModelId = ""
                                        }
                                    },
                                    onImportGgufFile = { file ->
                                        appState.importLocalGgufFile(file, autoSelect = false) { imported ->
                                            localModelId = imported.id
                                        }
                                    },
                                    onRemoveImportedModel = { id ->
                                        appState.removeImportedLocalModel(id)
                                        if (localModelId == id) {
                                            localModelId = ""
                                        }
                                    },
                                    onUnloadServer = { appState.unloadLocalModel() }
                                )
                                if (!isSelectedLocalModelReady) {
                                    Text(
                                        text = "Download or import a .gguf model and select it to continue.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
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

                                val insecureRemoteUrl = remember(endpointUrl, providerType) {
                                    val trimmed = endpointUrl.trim().lowercase(Locale.ROOT)
                                    trimmed.startsWith("http://") && !EventExtractor.isLocalEndpoint(trimmed)
                                }
                                OutlinedTextField(
                                    value = endpointUrl,
                                    onValueChange = {
                                        endpointUrl = it
                                        appState.clearConnectionStatus()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Endpoint Base URL") },
                                    isError = insecureRemoteUrl,
                                    supportingText = if (insecureRemoteUrl) {
                                        { Text("Remote endpoints must use https:// to protect your API key and chat contents.") }
                                    } else null,
                                    singleLine = true
                                )

                                if (providerType == LlmProviderType.OPENAI_COMPATIBLE) {
                                    OutlinedTextField(
                                        value = apiKey,
                                        onValueChange = { apiKey = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("API Key") },
                                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                                Icon(
                                                    imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = null
                                                )
                                            }
                                        },
                                        singleLine = true
                                    )
                                }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = modelName,
                                        onValueChange = { modelName = it },
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
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            LlmConnectionTestRow(
                                isTesting = isTestingConnection,
                                statusMessage = connectionStatusMessage,
                                onTestConnection = {
                                    appState.testLlmConnection(
                                        overrideConfig = currentSettings.llm.copy(
                                            providerType = providerType,
                                            endpointUrl = endpointUrl.trim(),
                                            apiKey = apiKey.trim(),
                                            modelName = modelName.trim(),
                                            localModelId = localModelId.trim(),
                                            localModelMirror = localModelMirror.name
                                        )
                                    )
                                }
                            )
                        }
                    }

                    WizardStep.CHATROOMS -> {
                        Text(
                            text = "Select which WeChat group chats to monitor for announcements, deadlines, and events:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (isLoadingRooms) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else if (discoveredRooms.isEmpty()) {
                            Text(
                                text = "No chatrooms found in selected directory. You can also configure them later under the 'Groups' tab.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(discoveredRooms, key = { it.roomId }) { room ->
                                    val isChecked = selectedRoomIds.contains(room.roomId)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedRoomIds = if (isChecked) {
                                                    selectedRoomIds - room.roomId
                                                } else {
                                                    selectedRoomIds + room.roomId
                                                }
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                selectedRoomIds = if (checked) {
                                                    selectedRoomIds + room.roomId
                                                } else {
                                                    selectedRoomIds - room.roomId
                                                }
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = room.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = room.roomId,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
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
                when (currentStep) {
                    WizardStep.WECHAT_PATH -> {
                        Button(
                            onClick = { currentStep = WizardStep.LLM_ENGINE },
                            enabled = selectedPath.isNotBlank()
                        ) {
                            Text("Next: AI Engine")
                        }
                    }
                    WizardStep.LLM_ENGINE -> {
                        val canProceed = if (providerType == LlmProviderType.BUILT_IN_LOCAL) {
                            isSelectedLocalModelReady
                        } else {
                            endpointUrl.isNotBlank()
                        }
                        Button(
                            onClick = { currentStep = WizardStep.CHATROOMS },
                            enabled = canProceed
                        ) {
                            Text("Next: Monitored Groups")
                        }
                    }
                    WizardStep.CHATROOMS -> {
                        Button(
                            onClick = {
                                val resolvedLocalName = appState.localModelStore.resolveModel(localModelId)?.displayName
                                val effectiveModelName = if (providerType == LlmProviderType.BUILT_IN_LOCAL && !resolvedLocalName.isNullOrBlank()) {
                                    resolvedLocalName
                                } else {
                                    modelName.trim()
                                }
                                val updatedSettings = currentSettings.copy(
                                    wechatDataDir = com.wechatdigest.core.detector.WeChatPathDetector.resolveUserPath(selectedPath)?.absolutePath
                                        ?: selectedPath.trim(),
                                    llm = currentSettings.llm.copy(
                                        providerType = providerType,
                                        endpointUrl = endpointUrl.trim(),
                                        apiKey = apiKey.trim(),
                                        modelName = effectiveModelName,
                                        localModelId = localModelId.trim(),
                                        localModelMirror = localModelMirror.name
                                    ),
                                    monitoredRoomIds = selectedRoomIds
                                )
                                appState.updateSettings(updatedSettings)
                                if (selectedRoomIds.isNotEmpty()) {
                                    appState.triggerSync()
                                }
                                onDismiss()
                            }
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Finish & Start Syncing")
                        }
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (currentStep) {
                    WizardStep.WECHAT_PATH -> {
                        OutlinedButton(onClick = onDismiss) {
                            Text("Skip Setup")
                        }
                    }
                    WizardStep.LLM_ENGINE -> {
                        OutlinedButton(onClick = { currentStep = WizardStep.WECHAT_PATH }) {
                            Text("Back")
                        }
                    }
                    WizardStep.CHATROOMS -> {
                        OutlinedButton(onClick = { currentStep = WizardStep.LLM_ENGINE }) {
                            Text("Back")
                        }
                    }
                }
            }
        }
    )
}
