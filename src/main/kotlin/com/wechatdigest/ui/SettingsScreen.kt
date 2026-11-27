package com.wechatdigest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wechatdigest.app.AppSettings
import com.wechatdigest.app.AppState
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.llm.local.LocalModelMirror
import com.wechatdigest.core.sanitizer.PrefilterConfig
import kotlinx.coroutines.launch

/**
 * Application settings screen with instant auto-save across theme, WeChat storage,
 * background sync, LLM engine, persona/localization, prefilter rules, and reset actions.
 */
@Composable
fun SettingsScreen(appState: AppState) {
    val currentSettings by appState.settings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var dataDir by remember { mutableStateOf(currentSettings.wechatDataDir) }
    var keysPath by remember { mutableStateOf(currentSettings.keysPath) }
    var attachmentRootDir by remember { mutableStateOf(currentSettings.wechatAttachmentRootDir) }
    var endpointUrl by remember { mutableStateOf(currentSettings.llm.endpointUrl) }
    var apiKey by remember { mutableStateOf(currentSettings.llm.apiKey) }
    var modelName by remember { mutableStateOf(currentSettings.llm.modelName) }
    var persona by remember { mutableStateOf(currentSettings.userPersona) }
    var targetLanguage by remember { mutableStateOf(currentSettings.targetLanguage) }
    var messagesPerSync by remember { mutableStateOf(currentSettings.messagesPerSync.toString()) }
    var lastCommittedMessagesPerSync by remember { mutableStateOf(currentSettings.messagesPerSync) }
    var minPrefilterLength by remember { mutableStateOf(currentSettings.prefilter.minMessageLength.toString()) }
    var lastCommittedMinPrefilterLength by remember { mutableStateOf(currentSettings.prefilter.minMessageLength) }

    var isAutoDetectingWeChat by remember { mutableStateOf(false) }
    var autoDetectFeedback by remember { mutableStateOf<String?>(null) }
    var showKeyGuideDialog by remember { mutableStateOf(false) }
    var showClearEventsDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }

    val resetLocalFieldsTo: (AppSettings) -> Unit = { target ->
        dataDir = target.wechatDataDir
        keysPath = target.keysPath
        attachmentRootDir = target.wechatAttachmentRootDir
        endpointUrl = target.llm.endpointUrl
        apiKey = target.llm.apiKey
        modelName = target.llm.modelName
        persona = target.userPersona
        targetLanguage = target.targetLanguage
        lastCommittedMessagesPerSync = target.messagesPerSync
        messagesPerSync = target.messagesPerSync.toString()
        lastCommittedMinPrefilterLength = target.prefilter.minMessageLength
        minPrefilterLength = target.prefilter.minMessageLength.toString()
    }

    LaunchedEffect(currentSettings) {
        if (dataDir.trim() != currentSettings.wechatDataDir) dataDir = currentSettings.wechatDataDir
        if (keysPath.trim() != currentSettings.keysPath) keysPath = currentSettings.keysPath
        if (attachmentRootDir.trim() != currentSettings.wechatAttachmentRootDir) attachmentRootDir = currentSettings.wechatAttachmentRootDir
        if (endpointUrl.trim() != currentSettings.llm.endpointUrl) endpointUrl = currentSettings.llm.endpointUrl
        if (apiKey.trim() != currentSettings.llm.apiKey) apiKey = currentSettings.llm.apiKey
        if (modelName.trim() != currentSettings.llm.modelName) modelName = currentSettings.llm.modelName
        if (persona.trim() != currentSettings.userPersona) persona = currentSettings.userPersona
        if (targetLanguage.trim() != currentSettings.targetLanguage) targetLanguage = currentSettings.targetLanguage
        if (currentSettings.messagesPerSync != lastCommittedMessagesPerSync) {
            lastCommittedMessagesPerSync = currentSettings.messagesPerSync
            messagesPerSync = currentSettings.messagesPerSync.toString()
        }
        if (currentSettings.prefilter.minMessageLength != lastCommittedMinPrefilterLength) {
            lastCommittedMinPrefilterLength = currentSettings.prefilter.minMessageLength
            minPrefilterLength = currentSettings.prefilter.minMessageLength.toString()
        }
    }

    val detectedOllamaModels by appState.detectedOllamaModels.collectAsState()
    val isDetectingOllama by appState.isDetectingOllama.collectAsState()
    val ollamaStatusMessage by appState.ollamaStatusMessage.collectAsState()
    val isTestingLlmConnection by appState.isTestingLlmConnection.collectAsState()
    val llmConnectionStatusMessage by appState.llmConnectionStatusMessage.collectAsState()
    val downloadedLocalModelIds by appState.downloadedLocalModelIds.collectAsState()
    val importedLocalModels by appState.importedLocalModels.collectAsState()
    val localModelDownloadStates by appState.localModelDownloadStates.collectAsState()
    val localRuntimeState by appState.localRuntimeState.collectAsState()
    val localServerState by appState.localServerState.collectAsState()
    val keyPreflightStatus by appState.keyPreflightStatus.collectAsState()
    val keyExtractionState by appState.keyExtractionState.collectAsState()

    LaunchedEffect(currentSettings.wechatDataDir, currentSettings.keysPath) {
        kotlinx.coroutines.delay(250)
        appState.refreshKeyDiagnostics()
    }

    val runDetectOllama: (String) -> Unit = { preferred ->
        appState.detectLocalOllama(preferred) { detectedEndpoint, firstModel ->
            endpointUrl = detectedEndpoint
            appState.transformSettings { cur ->
                val resolvedModel = if (cur.llm.modelName.isBlank() || cur.llm.modelName == "gpt-4o-mini") {
                    firstModel ?: cur.llm.modelName
                } else {
                    cur.llm.modelName
                }
                modelName = resolvedModel
                cur.copy(
                    llm = cur.llm.copy(
                        providerType = LlmProviderType.OLLAMA,
                        endpointUrl = detectedEndpoint.trim(),
                        modelName = resolvedModel.trim()
                    )
                )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        SettingsHeaderRow(
            onResetDefaults = {
                val defaults = AppSettings()
                resetLocalFieldsTo(defaults)
                appState.updateSettings(defaults)
            }
        )
        ThemeSettingsCard(
            selectedTheme = currentSettings.themeMode,
            onSelectTheme = { mode -> appState.transformSettings { it.copy(themeMode = mode) } }
        )
        WeChatStorageSettingsCard(
            dataDir = dataDir,
            keysPath = keysPath,
            attachmentRootDir = attachmentRootDir,
            messagesPerSync = messagesPerSync,
            parseAttachmentContents = currentSettings.parseAttachmentContents,
            enableVisualOcr = currentSettings.enableVisualOcr,
            isAutoDetecting = isAutoDetectingWeChat,
            autoDetectFeedback = autoDetectFeedback,
            keyPreflightStatus = keyPreflightStatus,
            onAutoDetect = {
                if (!isAutoDetectingWeChat) {
                    isAutoDetectingWeChat = true
                    coroutineScope.launch {
                        try {
                            val candidates = appState.autoDetectWeChatCandidatesAsync()
                            if (candidates.isNotEmpty()) {
                                val detectedPath = candidates.first().path.absolutePath
                                dataDir = detectedPath
                                autoDetectFeedback = "Detected: ${candidates.first().label}"
                                appState.transformSettings { it.copy(wechatDataDir = detectedPath) }
                            } else {
                                autoDetectFeedback = "No WeChat database found automatically."
                            }
                        } finally {
                            isAutoDetectingWeChat = false
                        }
                    }
                }
            },
            onLaunchWizard = { appState.setShowWizard(true) },
            onVerifyKeys = { appState.verifyWeChatKeysNow(dataDir, keysPath) },
            onOpenKeyGuide = {
                appState.resetKeyExtractionState()
                showKeyGuideDialog = true
            },
            onBrowseDataDir = {
                DesktopPickers.openDirectoryPicker("Select WeChat Data Directory") { dir ->
                    val path = dir.absolutePath
                    dataDir = path
                    appState.transformSettings { it.copy(wechatDataDir = path) }
                }
            },
            onBrowseKeysPath = {
                DesktopPickers.openExtensionFilePicker("Select all_keys.json", ".json") { file ->
                    val path = file.absolutePath
                    keysPath = path
                    appState.transformSettings { it.copy(keysPath = path) }
                }
            },
            onBrowseAttachmentRootDir = {
                DesktopPickers.openDirectoryPicker("Select WeChat Attachment Root") { dir ->
                    val path = dir.absolutePath
                    attachmentRootDir = path
                    appState.transformSettings { it.copy(wechatAttachmentRootDir = path) }
                }
            },
            onDataDirChange = { next -> dataDir = next; appState.transformSettings { it.copy(wechatDataDir = next.trim()) } },
            onKeysPathChange = { next -> keysPath = next; appState.transformSettings { it.copy(keysPath = next.trim()) } },
            onAttachmentRootDirChange = { next ->
                attachmentRootDir = next
                appState.transformSettings { it.copy(wechatAttachmentRootDir = next.trim()) }
            },
            onMessagesPerSyncChange = { next ->
                next.toIntOrNull()?.let { raw ->
                    val clamped = clampMessagesPerSync(raw)
                    messagesPerSync = clamped.toString()
                    lastCommittedMessagesPerSync = clamped
                    appState.transformSettings { it.copy(messagesPerSync = clamped) }
                } ?: run { messagesPerSync = next }
            },
            onParseAttachmentContentsChange = { enabled -> appState.transformSettings { it.copy(parseAttachmentContents = enabled) } },
            onEnableVisualOcrChange = { enabled -> appState.transformSettings { it.copy(enableVisualOcr = enabled) } }
        )
        BackgroundSyncSettingsCard(
            autoSyncEnabled = currentSettings.autoSyncEnabled,
            syncIntervalMinutes = currentSettings.syncIntervalMinutes,
            notifyOnNewEvents = currentSettings.notifyOnNewEvents,
            notificationThreshold = currentSettings.notificationThreshold,
            notifyOnManualSync = currentSettings.notifyOnManualSync,
            onAutoSyncChange = { enabled -> appState.transformSettings { it.copy(autoSyncEnabled = enabled) } },
            onSelectInterval = { mins -> appState.transformSettings { it.copy(syncIntervalMinutes = mins) } },
            onNotifyChange = { enabled -> appState.transformSettings { it.copy(notifyOnNewEvents = enabled) } },
            onSelectNotificationThreshold = { threshold -> appState.transformSettings { it.copy(notificationThreshold = threshold) } },
            onNotifyOnManualSyncChange = { enabled -> appState.transformSettings { it.copy(notifyOnManualSync = enabled) } },
            onTestNotification = { appState.testNotification() }
        )
        LlmEngineSettingsCard(
            providerType = currentSettings.llm.providerType,
            endpointUrl = endpointUrl,
            apiKey = apiKey,
            modelName = modelName,
            detectedOllamaModels = detectedOllamaModels,
            isDetectingOllama = isDetectingOllama,
            ollamaStatusMessage = ollamaStatusMessage,
            isTestingConnection = isTestingLlmConnection,
            connectionStatusMessage = llmConnectionStatusMessage,
            hardwareInfo = appState.systemHardwareInfo,
            localCatalogModels = appState.localCatalogModels,
            downloadedLocalModelIds = downloadedLocalModelIds,
            importedLocalModels = importedLocalModels,
            localModelDownloadStates = localModelDownloadStates,
            localRuntimeState = localRuntimeState,
            localServerState = localServerState,
            localModelId = currentSettings.llm.localModelId,
            localModelMirror = LocalModelMirror.fromKey(currentSettings.llm.localModelMirror),
            onSelectBuiltInLocal = {
                appState.transformSettings { cur ->
                    val autoSelectedId = cur.llm.localModelId.ifBlank {
                        downloadedLocalModelIds.firstOrNull()
                            ?: importedLocalModels.firstOrNull()?.id
                            ?: ""
                    }
                    cur.copy(
                        llm = cur.llm.copy(
                            providerType = LlmProviderType.BUILT_IN_LOCAL,
                            localModelId = autoSelectedId
                        )
                    )
                }
            },
            onSelectOpenAi = {
                val nextUrl = if (endpointUrl == "http://localhost:11434") "https://api.openai.com/v1" else endpointUrl
                endpointUrl = nextUrl
                appState.transformSettings { cur ->
                    cur.copy(llm = cur.llm.copy(providerType = LlmProviderType.OPENAI_COMPATIBLE, endpointUrl = nextUrl.trim()))
                }
            },
            onSelectOllama = {
                val nextUrl = if (endpointUrl == "https://api.openai.com/v1") "http://localhost:11434" else endpointUrl
                endpointUrl = nextUrl
                appState.transformSettings { cur ->
                    cur.copy(llm = cur.llm.copy(providerType = LlmProviderType.OLLAMA, endpointUrl = nextUrl.trim()))
                }
                if (detectedOllamaModels.isEmpty() && !isDetectingOllama) runDetectOllama(nextUrl)
            },
            onDetectOllama = { runDetectOllama(endpointUrl) },
            onTestConnection = { appState.testLlmConnection() },
            onEndpointChange = { next -> endpointUrl = next; appState.transformSettings { cur -> cur.copy(llm = cur.llm.copy(endpointUrl = next.trim())) } },
            onApiKeyChange = { next -> apiKey = next; appState.transformSettings { cur -> cur.copy(llm = cur.llm.copy(apiKey = next.trim())) } },
            onModelNameChange = { next -> modelName = next; appState.transformSettings { cur -> cur.copy(llm = cur.llm.copy(modelName = next.trim())) } },
            onSelectOllamaModel = { selected -> modelName = selected; appState.transformSettings { cur -> cur.copy(llm = cur.llm.copy(modelName = selected)) } },
            onSelectLocalMirror = { mirror -> appState.selectLocalMirror(mirror) },
            onSelectLocalModel = { id -> appState.selectLocalModel(id) },
            onDownloadLocalModel = { id -> appState.downloadLocalModel(id) },
            onCancelLocalDownload = { id -> appState.cancelLocalModelDownload(id) },
            onDeleteLocalModel = { id -> appState.deleteDownloadedLocalModel(id) },
            onImportGgufFile = { file -> appState.importLocalGgufFile(file) },
            onRemoveImportedModel = { id -> appState.removeImportedLocalModel(id) },
            onUnloadLocalServer = { appState.unloadLocalModel() }
        )
        PersonaLocalizationCard(
            persona = persona,
            targetLanguage = targetLanguage,
            onPersonaChange = { next -> persona = next; appState.transformSettings { it.copy(userPersona = next.trim()) } },
            onTargetLanguageChange = { next -> targetLanguage = next; appState.transformSettings { it.copy(targetLanguage = next.trim()) } }
        )
        PrefilterSettingsSection(
            minLengthText = minPrefilterLength,
            onMinLengthChange = { next ->
                minPrefilterLength = next
                next.toIntOrNull()?.coerceAtLeast(0)?.let { parsed ->
                    lastCommittedMinPrefilterLength = parsed
                    appState.transformSettings { cur -> cur.copy(prefilter = cur.prefilter.copy(minMessageLength = parsed)) }
                }
            },
            rules = currentSettings.prefilter.rules,
            onRulesChange = { updated -> appState.transformSettings { cur -> cur.copy(prefilter = cur.prefilter.copy(rules = updated)) } },
            onRestoreDefaults = {
                lastCommittedMinPrefilterLength = PrefilterConfig.DEFAULT_MIN_MESSAGE_LENGTH
                minPrefilterLength = PrefilterConfig.DEFAULT_MIN_MESSAGE_LENGTH.toString()
                appState.transformSettings { cur ->
                    cur.copy(prefilter = PrefilterConfig(minMessageLength = PrefilterConfig.DEFAULT_MIN_MESSAGE_LENGTH, rules = PrefilterConfig.defaultRules()))
                }
            },
            onMarkDirty = {}
        )
        LocalAppDataResetCard(
            onClearEventsClick = { showClearEventsDialog = true },
            onFactoryResetClick = { showFactoryResetDialog = true }
        )
    }

    SettingsResetDialogs(
        showClearEventsDialog = showClearEventsDialog,
        showFactoryResetDialog = showFactoryResetDialog,
        onDismissClearEvents = { showClearEventsDialog = false },
        onConfirmClearEvents = { appState.clearEventsAndCheckpoints(); showClearEventsDialog = false },
        onDismissFactoryReset = { showFactoryResetDialog = false },
        onConfirmFactoryReset = {
            appState.resetLocalAppDatabase()
            resetLocalFieldsTo(AppSettings())
            autoDetectFeedback = null
            showFactoryResetDialog = false
        }
    )

    var manualCommand by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showKeyGuideDialog, dataDir, keysPath, keyPreflightStatus) {
        manualCommand = if (showKeyGuideDialog) {
            appState.buildManualKeyExtractCommand(dataDirOverride = dataDir, keysPathOverride = keysPath)
        } else {
            null
        }
    }

    if (showKeyGuideDialog) {
        KeyExtractionGuideDialog(
            preflightStatus = keyPreflightStatus,
            extractionState = keyExtractionState,
            manualCommand = manualCommand,
            onRecheckPreflight = { appState.refreshKeyDiagnostics(dataDir, keysPath) },
            onInstallXcodeTools = { appState.triggerXcodeCommandLineToolsInstall() },
            onStartExtraction = { resign ->
                appState.startGuidedKeyExtraction(
                    dataDirOverride = dataDir,
                    keysPathOverride = keysPath,
                    resignWeChat = resign,
                    onKeysExtracted = { resolvedPath -> keysPath = resolvedPath }
                )
            },
            onCancelExtraction = { appState.cancelKeyExtraction() },
            onCommandCopied = { appState.showMessage("Copied extraction command to clipboard.") },
            onDismiss = {
                showKeyGuideDialog = false
                appState.refreshKeyDiagnostics(dataDir, keysPath)
            }
        )
    }
}

@Composable
private fun SettingsHeaderRow(onResetDefaults: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onResetDefaults) { Text("Reset Defaults") }
    }
}
