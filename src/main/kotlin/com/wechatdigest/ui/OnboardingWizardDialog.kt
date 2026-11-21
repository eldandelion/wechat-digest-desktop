package com.wechatdigest.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.model.GroupChat
import com.wechatdigest.core.repository.WeChatContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class WizardStep {
    WECHAT_PATH,
    LLM_ENGINE,
    CHATROOMS
}

@Composable
fun OnboardingWizardDialog(
    appState: AppState,
    onDismiss: () -> Unit
) {
    val currentSettings by appState.settings.collectAsState()

    var currentStep by remember { mutableStateOf(WizardStep.WECHAT_PATH) }

    // Step 1 state
    var selectedPath by remember(currentSettings) { mutableStateOf(currentSettings.wechatDataDir) }
    var candidatePaths by remember { mutableStateOf<List<DiscoveredPath>>(emptyList()) }
    var isScanningPaths by remember { mutableStateOf(false) }

    // Step 2 state
    var providerType by remember(currentSettings) { mutableStateOf(currentSettings.llm.providerType) }
    var endpointUrl by remember(currentSettings) { mutableStateOf(currentSettings.llm.endpointUrl) }
    var apiKey by remember(currentSettings) { mutableStateOf(currentSettings.llm.apiKey) }
    var modelName by remember(currentSettings) { mutableStateOf(currentSettings.llm.modelName) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isModelDropdownOpen by remember { mutableStateOf(false) }

    val detectedOllamaModels by appState.detectedOllamaModels.collectAsState()
    val isDetectingOllama by appState.isDetectingOllama.collectAsState()
    val ollamaStatusMessage by appState.ollamaStatusMessage.collectAsState()

    // Step 3 state
    var discoveredRooms by remember { mutableStateOf<List<GroupChat>>(emptyList()) }
    var selectedRoomIds by remember(currentSettings) { mutableStateOf(currentSettings.monitoredRoomIds) }
    var isLoadingRooms by remember { mutableStateOf(false) }

    // Auto scan candidate paths on open
    LaunchedEffect(Unit) {
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
            val dir = File(selectedPath)
            if (dir.exists() && dir.isDirectory) {
                try {
                    val rooms = withContext(Dispatchers.IO) {
                        val driver = WeChatDatabaseDriver(dir)
                        val repo = WeChatContactRepository(driver)
                        repo.listAllChatrooms()
                    }
                    discoveredRooms = rooms
                    if (selectedRoomIds.isEmpty() && rooms.isNotEmpty()) {
                        selectedRoomIds = setOf(rooms.first().roomId)
                    }
                } catch (e: Exception) {
                    System.err.println("[OnboardingWizard] Failed to list rooms: ${e.message}")
                }
            }
            isLoadingRooms = false
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
                            singleLine = true
                        )
                    }

                    WizardStep.LLM_ENGINE -> {
                        Text(
                            text = "Choose your LLM completion provider. WeChat Digest uses structured JSON output to extract events and priorities.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = providerType == LlmProviderType.OPENAI_COMPATIBLE,
                                onClick = {
                                    providerType = LlmProviderType.OPENAI_COMPATIBLE
                                    if (endpointUrl == "http://localhost:11434") {
                                        endpointUrl = "https://api.openai.com/v1"
                                    }
                                },
                                label = { Text("OpenAI / Cloud") }
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
                            onValueChange = { endpointUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Endpoint Base URL") },
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
                                items(discoveredRooms) { room ->
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
                        Button(
                            onClick = { currentStep = WizardStep.CHATROOMS },
                            enabled = endpointUrl.isNotBlank()
                        ) {
                            Text("Next: Monitored Groups")
                        }
                    }
                    WizardStep.CHATROOMS -> {
                        Button(
                            onClick = {
                                val updatedSettings = currentSettings.copy(
                                    wechatDataDir = selectedPath.trim(),
                                    llm = currentSettings.llm.copy(
                                        providerType = providerType,
                                        endpointUrl = endpointUrl.trim(),
                                        apiKey = apiKey.trim(),
                                        modelName = modelName.trim()
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
