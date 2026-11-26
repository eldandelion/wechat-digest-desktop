package com.wechatdigest.app

import com.wechatdigest.core.attachment.AttachmentLauncher
import com.wechatdigest.core.attachment.WeChatAttachmentResolver
import com.wechatdigest.core.calendar.CalendarLauncher
import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.detector.DiscoveredPath
import com.wechatdigest.core.detector.WeChatPathDetector
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.llm.LocalOllamaModel
import com.wechatdigest.core.llm.MessageTranslator
import com.wechatdigest.core.llm.OllamaDiscoveryResult
import com.wechatdigest.core.llm.OllamaService
import com.wechatdigest.core.llm.SectionCardEnricher
import com.wechatdigest.core.llm.local.ImportedLocalModel
import com.wechatdigest.core.llm.local.LlamaRuntimeManager
import com.wechatdigest.core.llm.local.LlamaServerManager
import com.wechatdigest.core.llm.local.LocalModelCatalog
import com.wechatdigest.core.llm.local.LocalModelDownloadManager
import com.wechatdigest.core.llm.local.LocalModelDownloadState
import com.wechatdigest.core.llm.local.LocalModelMirror
import com.wechatdigest.core.llm.local.LocalModelSpec
import com.wechatdigest.core.llm.local.LocalModelStore
import com.wechatdigest.core.llm.local.LocalRuntimeState
import com.wechatdigest.core.llm.local.LocalServerState
import com.wechatdigest.core.llm.local.ManagedLocalLlmClient
import com.wechatdigest.core.llm.local.SystemHardwareInfo
import com.wechatdigest.core.model.EventAttachment
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventDetailItemParser
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.GroupChat
import com.wechatdigest.core.model.GroupConfig
import com.wechatdigest.core.model.SyncCheckpoint
import com.wechatdigest.core.notification.NotificationDispatcher
import com.wechatdigest.core.repository.WeChatContactRepository
import com.wechatdigest.storage.EventCardStore
import com.wechatdigest.storage.GroupConfigStore
import com.wechatdigest.storage.SettingsStore
import com.wechatdigest.storage.SyncCheckpointStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class Screen {
    EVENTS,
    GROUPS,
    SETTINGS
}

enum class EventSortOrder(val label: String) {
    NEWEST_FIRST("Newest to oldest"),
    OLDEST_FIRST("Oldest to newest")
}

private data class FeedFilterCriteria(
    val priority: EventPriority?,
    val roomId: String?,
    val starredOnly: Boolean,
    val unreadOnly: Boolean,
    val sortOrder: EventSortOrder,
    val query: String
)

class AppState(
    private val settingsStore: SettingsStore,
    private val eventCardStore: EventCardStore,
    private val syncService: SyncService,
    private val groupConfigStore: GroupConfigStore? = null,
    private val calendarLauncher: CalendarLauncher = CalendarLauncher(),
    val wechatPathDetector: WeChatPathDetector = WeChatPathDetector(),
    val notificationDispatcher: NotificationDispatcher = NotificationDispatcher(),
    val ollamaService: OllamaService = OllamaService(),
    val syncCheckpointStore: SyncCheckpointStore? = syncService.syncCheckpointStore,
    private val decryptedCacheDir: File = File(System.getProperty("user.home"), "Library/Application Support/WeChatDigest/decrypted"),
    syncScheduler: SyncScheduler? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val llmClientFactory: ((LlmConfig) -> LlmClient)? = null,
    private val attachmentLauncher: AttachmentLauncher = AttachmentLauncher(),
    val localModelStore: LocalModelStore = LocalModelStore(),
    val localModelDownloadManager: LocalModelDownloadManager = LocalModelDownloadManager(localModelStore),
    val llamaRuntimeManager: LlamaRuntimeManager = LlamaRuntimeManager(),
    llamaServerManager: LlamaServerManager? = null
) {
    private val _currentScreen = MutableStateFlow(Screen.EVENTS)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _events = MutableStateFlow<List<EventCard>>(emptyList())
    val events: StateFlow<List<EventCard>> = _events.asStateFlow()

    val priorityFilter = MutableStateFlow<EventPriority?>(null)
    val groupFilter = MutableStateFlow<String?>(null)
    val starredOnlyFilter = MutableStateFlow(false)
    val unreadOnlyFilter = MutableStateFlow(false)
    val sortOrder = MutableStateFlow(EventSortOrder.NEWEST_FIRST)
    val searchQuery = MutableStateFlow("")
    val groupSearchQuery = MutableStateFlow("")

    private val _selectedEventId = MutableStateFlow<String?>(null)
    val selectedEventId: StateFlow<String?> = _selectedEventId.asStateFlow()

    val llamaServerManager: LlamaServerManager = llamaServerManager ?: LlamaServerManager(
        runtimeManager = llamaRuntimeManager,
        scope = scope
    )

    val systemHardwareInfo: SystemHardwareInfo = localModelStore.detectHardwareInfo()
    val localCatalogModels: List<LocalModelSpec> = LocalModelCatalog.CURATED_MODELS
    val downloadedLocalModelIds: StateFlow<Set<String>> = localModelStore.downloadedModelIds
    val importedLocalModels: StateFlow<List<ImportedLocalModel>> = localModelStore.importedModels
    val localModelDownloadStates: StateFlow<Map<String, LocalModelDownloadState>> = localModelDownloadManager.downloadStates
    val localRuntimeState: StateFlow<LocalRuntimeState> = llamaRuntimeManager.runtimeState
    val localServerState: StateFlow<LocalServerState> = this.llamaServerManager.serverState

    private fun createLlmClient(config: LlmConfig): LlmClient {
        llmClientFactory?.let { return it(config) }
        return when (config.providerType) {
            LlmProviderType.BUILT_IN_LOCAL -> ManagedLocalLlmClient(config, localModelStore, this.llamaServerManager)
            else -> KtorLlmClient(config)
        }
    }

    private val _detectedOllamaModels = MutableStateFlow<List<LocalOllamaModel>>(emptyList())
    val detectedOllamaModels: StateFlow<List<LocalOllamaModel>> = _detectedOllamaModels.asStateFlow()

    private val _isDetectingOllama = MutableStateFlow(false)
    val isDetectingOllama: StateFlow<Boolean> = _isDetectingOllama.asStateFlow()

    private val _ollamaStatusMessage = MutableStateFlow<String?>(null)
    val ollamaStatusMessage: StateFlow<String?> = _ollamaStatusMessage.asStateFlow()

    private val _syncCheckpoints = MutableStateFlow<Map<String, SyncCheckpoint>>(emptyMap())
    val syncCheckpoints: StateFlow<Map<String, SyncCheckpoint>> = _syncCheckpoints.asStateFlow()

    private val toggleFiltersFlow = combine(starredOnlyFilter, unreadOnlyFilter) { starredOnly, unreadOnly ->
        starredOnly to unreadOnly
    }

    private val filterCriteriaFlow = combine(
        priorityFilter,
        groupFilter,
        toggleFiltersFlow,
        sortOrder,
        searchQuery
    ) { priority, roomId, (starredOnly, unreadOnly), order, query ->
        FeedFilterCriteria(priority, roomId, starredOnly, unreadOnly, order, query)
    }

    val visibleEvents: StateFlow<List<EventCard>> = combine(
        _events,
        filterCriteriaFlow
    ) { allEvents, criteria ->
        val filtered = allEvents.filter { card ->
            val matchesPriority = criteria.priority == null || card.priority == criteria.priority
            val matchesGroup = criteria.roomId == null || card.sourceRoomId == criteria.roomId
            val matchesStarred = !criteria.starredOnly || card.isStarred
            val matchesUnread = !criteria.unreadOnly || !card.isRead
            val query = criteria.query
            val matchesQuery = if (query.isBlank()) true else {
                card.title.contains(query, ignoreCase = true) ||
                card.summary.contains(query, ignoreCase = true) ||
                card.translatedSummary.contains(query, ignoreCase = true) ||
                card.originalSender.contains(query, ignoreCase = true) ||
                card.sections.any { section ->
                    section.heading.contains(query, ignoreCase = true) ||
                    section.content.contains(query, ignoreCase = true) ||
                    section.bullets.any { it.contains(query, ignoreCase = true) } ||
                    section.items.any { item ->
                        item.label.contains(query, ignoreCase = true) ||
                        item.value.contains(query, ignoreCase = true)
                    }
                }
            }
            matchesPriority && matchesGroup && matchesStarred && matchesUnread && matchesQuery
        }
        when (criteria.sortOrder) {
            EventSortOrder.NEWEST_FIRST -> filtered.sortedWith(
                compareByDescending<EventCard> { it.timestampSeconds }
                    .thenBy { it.priority.ordinal }
            )
            EventSortOrder.OLDEST_FIRST -> filtered.sortedWith(
                compareBy<EventCard> { it.timestampSeconds }
                    .thenBy { it.priority.ordinal }
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _groupConfigs = MutableStateFlow<Map<String, GroupConfig>>(emptyMap())
    val groupConfigs: StateFlow<Map<String, GroupConfig>> = _groupConfigs.asStateFlow()

    private val _availableRooms = MutableStateFlow<List<GroupChat>>(emptyList())
    val availableRooms: StateFlow<List<GroupChat>> = _availableRooms.asStateFlow()

    private val _isLoadingRooms = MutableStateFlow(false)
    val isLoadingRooms: StateFlow<Boolean> = _isLoadingRooms.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatusText = MutableStateFlow<String?>(null)

    /** Human-readable progress status for the currently active sync run, or `null` when idle. */
    val syncStatusText: StateFlow<String?> = _syncStatusText.asStateFlow()

    private val _translatingCardIds = MutableStateFlow<Set<String>>(emptySet())
    val translatingCardIds: StateFlow<Set<String>> = _translatingCardIds.asStateFlow()

    private val _recheckingAttachmentCardIds = MutableStateFlow<Set<String>>(emptySet())

    /** Card IDs currently re-scanning local WeChat storage for downloaded attachments. */
    val recheckingAttachmentCardIds: StateFlow<Set<String>> = _recheckingAttachmentCardIds.asStateFlow()

    private val _enrichingCardIds = MutableStateFlow<Set<String>>(emptySet())

    /** Card IDs currently converting unstructured bullet sections into structured cards via Pass 4 LLM. */
    val enrichingCardIds: StateFlow<Set<String>> = _enrichingCardIds.asStateFlow()

    private val enrichmentJobs = ConcurrentHashMap<String, Job>()

    private val settingsMutex = Mutex()
    private val syncExecutionMutex = Mutex()
    private val eventsDbMutex = Mutex()
    private val eventsMutationEpoch = AtomicLong(0L)
    @Volatile
    private var loadEventsJob: Job? = null
    @Volatile
    private var activeSyncJob: Job? = null
    private var refreshRoomsJob: Job? = null

    private val _showWizard = MutableStateFlow(false)
    val showWizard: StateFlow<Boolean> = _showWizard.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    val scheduler: SyncScheduler = syncScheduler ?: SyncScheduler(
        syncAction = {
            runExclusiveSync() ?: SyncResult.Success(newEventsCount = 0, totalScannedMessages = 0)
        },
        notificationDispatcher = notificationDispatcher,
        scope = scope
    )

    init {
        loadEvents()
        refreshRooms()
        loadGroupConfigs()
        loadCheckpoints()

        if (_settings.value.wechatDataDir.isBlank()) {
            _showWizard.value = true
        }

        if (_settings.value.autoSyncEnabled) {
            scheduler.start(
                intervalMinutes = _settings.value.syncIntervalMinutes,
                notifyOnNewEvents = _settings.value.notifyOnNewEvents
            )
        }
    }

    fun loadCheckpoints(): Job = scope.launch {
        if (syncCheckpointStore != null) {
            val map = withContext(Dispatchers.IO) {
                syncCheckpointStore.getAll()
            }
            _syncCheckpoints.value = map
        }
    }

    fun resetGroupCheckpoint(roomId: String): Job = scope.launch {
        if (syncCheckpointStore != null) {
            activeSyncJob?.cancelAndJoin()
            syncExecutionMutex.withLock {
                withContext(Dispatchers.IO) {
                    syncCheckpointStore.reset(roomId)
                }
            }
            loadEvents().join()
            loadCheckpoints().join()
            _snackbarMessage.emit("Checkpoint reset for group. Next sync will scan full history.")
        }
    }

    fun clearEventsAndCheckpoints(): Job = scope.launch {
        scheduler.stop()
        loadEventsJob?.cancel()
        activeSyncJob?.cancelAndJoin()
        cancelEnrichmentJobs()
        syncExecutionMutex.withLock {
            eventsDbMutex.withLock {
                eventsMutationEpoch.incrementAndGet()
                withContext(Dispatchers.IO) {
                    eventCardStore.clearAll()
                    syncCheckpointStore?.resetAll()
                }
                _events.value = emptyList()
            }
            _syncCheckpoints.value = emptyMap()
        }
        groupFilter.value = null
        starredOnlyFilter.value = false
        unreadOnlyFilter.value = false
        _selectedEventId.value = null
        val currentSettings = _settings.value
        if (currentSettings.autoSyncEnabled && currentSettings.syncIntervalMinutes > 0) {
            scheduler.start(
                intervalMinutes = currentSettings.syncIntervalMinutes,
                notifyOnNewEvents = currentSettings.notifyOnNewEvents
            )
        }
        _snackbarMessage.emit("Cleared all extracted events and sync checkpoints.")
    }

    fun resetLocalAppDatabase(): Job = scope.launch {
        scheduler.stop()
        localModelDownloadManager.cancelAll()
        loadEventsJob?.cancel()
        val pendingRoomsJob = refreshRoomsJob
        refreshRoomsJob = null
        pendingRoomsJob?.cancelAndJoin()
        activeSyncJob?.cancelAndJoin()
        cancelEnrichmentJobs()
        llamaServerManager.stop()
        syncExecutionMutex.withLock {
            eventsDbMutex.withLock {
                eventsMutationEpoch.incrementAndGet()
                withContext(Dispatchers.IO) {
                    eventCardStore.clearAll()
                    syncCheckpointStore?.resetAll()
                    groupConfigStore?.clearAll()
                    if (!Files.isSymbolicLink(decryptedCacheDir.toPath()) &&
                        decryptedCacheDir.exists() &&
                        decryptedCacheDir.isDirectory &&
                        decryptedCacheDir.name == "decrypted"
                    ) {
                        decryptedCacheDir.deleteRecursively()
                    }
                    localModelStore.refresh()
                }
                settingsMutex.withLock {
                    withContext(Dispatchers.IO) { settingsStore.clear() }
                    _settings.value = AppSettings()
                }
                _events.value = emptyList()
            }
            _syncCheckpoints.value = emptyMap()
        }
        _groupConfigs.value = emptyMap()
        _availableRooms.value = emptyList()
        _detectedOllamaModels.value = emptyList()
        _ollamaStatusMessage.value = null
        priorityFilter.value = null
        groupFilter.value = null
        starredOnlyFilter.value = false
        unreadOnlyFilter.value = false
        sortOrder.value = EventSortOrder.NEWEST_FIRST
        searchQuery.value = ""
        groupSearchQuery.value = ""
        _selectedEventId.value = null
        _snackbarMessage.emit("Local app database reset to defaults. WeChat files and downloaded local models untouched.")
    }

    fun setShowWizard(show: Boolean) {
        _showWizard.value = show
    }

    fun autoDetectWeChatCandidates(): List<DiscoveredPath> {
        return wechatPathDetector.detectCandidates()
    }

    /** Runs WeChat installation auto-detection on [Dispatchers.IO]. */
    suspend fun autoDetectWeChatCandidatesAsync(): List<DiscoveredPath> = withContext(Dispatchers.IO) {
        wechatPathDetector.detectCandidates()
    }

    fun detectLocalOllama(
        preferredUrl: String? = null,
        onDetected: ((endpoint: String, firstModel: String?) -> Unit)? = null
    ): Job = scope.launch {
        _isDetectingOllama.value = true
        _ollamaStatusMessage.value = "Searching for local Ollama..."
        try {
            when (val result = ollamaService.detectLocalOllama(preferredUrl)) {
                is OllamaDiscoveryResult.Success -> {
                    _detectedOllamaModels.value = result.models
                    _ollamaStatusMessage.value = "Connected (${result.models.size} model${if (result.models.size == 1) "" else "s"} found)"
                    onDetected?.invoke(result.endpointUrl, result.models.firstOrNull()?.name)
                }
                is OllamaDiscoveryResult.NoModels -> {
                    _detectedOllamaModels.value = emptyList()
                    _ollamaStatusMessage.value = "Ollama running, but no models installed"
                    onDetected?.invoke(result.endpointUrl, null)
                }
                is OllamaDiscoveryResult.Unreachable -> {
                    _detectedOllamaModels.value = emptyList()
                    _ollamaStatusMessage.value = "Cannot connect to Ollama (${result.reason})"
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _detectedOllamaModels.value = emptyList()
            _ollamaStatusMessage.value = "Detection failed: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
        } finally {
            _isDetectingOllama.value = false
        }
    }

    fun selectScreen(screen: Screen) {
        _currentScreen.value = screen
        if (screen == Screen.GROUPS && _availableRooms.value.isEmpty()) {
            refreshRooms()
        }
    }

    fun loadGroupConfigs(): Job = scope.launch {
        if (groupConfigStore != null) {
            val configs = withContext(Dispatchers.IO) {
                groupConfigStore.getAll()
            }
            _groupConfigs.value = configs
        }
    }

    fun saveGroupConfig(config: GroupConfig): Job = scope.launch {
        if (groupConfigStore != null) {
            withContext(Dispatchers.IO) {
                groupConfigStore.save(config)
                groupConfigStore.getAll()
            }.also {
                _groupConfigs.value = it
            }
            _snackbarMessage.emit("Saved rules for room.")
        }
    }

    fun deleteGroupConfig(roomId: String): Job = scope.launch {
        if (groupConfigStore != null) {
            withContext(Dispatchers.IO) {
                groupConfigStore.delete(roomId)
                groupConfigStore.getAll()
            }.also {
                _groupConfigs.value = it
            }
            _snackbarMessage.emit("Removed rules for room.")
        }
    }

    fun exportToCalendar(card: EventCard): Job = scope.launch {
        val result = withContext(Dispatchers.IO) {
            calendarLauncher.exportAndLaunch(card)
        }
        if (result.isSuccess) {
            _snackbarMessage.emit("Exported '${card.title}' to Calendar.")
        } else {
            val err = LlmJsonUtils.sanitizeLogMessage(result.exceptionOrNull()?.message)
            _snackbarMessage.emit("Failed to export to Calendar: $err")
        }
    }

    fun loadEvents(): Job {
        loadEventsJob?.cancel()
        val job = scope.launch {
            eventsDbMutex.withLock {
                val capturedEpoch = eventsMutationEpoch.get()
                val list = withContext(Dispatchers.IO) {
                    eventCardStore.listActive()
                }
                if (eventsMutationEpoch.get() == capturedEpoch) {
                    _events.value = list
                }
            }
        }
        loadEventsJob = job
        return job
    }

    fun selectEvent(id: String?) {
        _selectedEventId.value = id
        if (id != null) {
            val target = _events.value.firstOrNull { it.id == id }
            if (target != null && !target.isRead) {
                markRead(id)
            }
        }
    }

    fun markRead(id: String): Job = scope.launch {
        eventsMutationEpoch.incrementAndGet()
        _events.update { list -> list.map { if (it.id == id) it.copy(isRead = true) else it } }
        eventsDbMutex.withLock {
            withContext(Dispatchers.IO) { eventCardStore.markRead(id) }
            // Re-apply idempotently under the lock in case an earlier loadEvents() read pre-write SQLite rows.
            _events.update { list -> list.map { if (it.id == id) it.copy(isRead = true) else it } }
            eventsMutationEpoch.incrementAndGet()
        }
    }

    fun markUnread(id: String): Job = scope.launch {
        eventsMutationEpoch.incrementAndGet()
        _events.update { list -> list.map { if (it.id == id) it.copy(isRead = false) else it } }
        eventsDbMutex.withLock {
            withContext(Dispatchers.IO) { eventCardStore.markUnread(id) }
            _events.update { list -> list.map { if (it.id == id) it.copy(isRead = false) else it } }
            eventsMutationEpoch.incrementAndGet()
        }
    }

    fun toggleStarred(id: String): Job = scope.launch {
        eventsDbMutex.withLock {
            val current = _events.value.firstOrNull { it.id == id } ?: return@withLock
            val newStarred = !current.isStarred
            eventsMutationEpoch.incrementAndGet()
            _events.update { list -> list.map { if (it.id == id) it.copy(isStarred = newStarred) else it } }
            withContext(Dispatchers.IO) { eventCardStore.setStarred(id, newStarred) }
            eventsMutationEpoch.incrementAndGet()
        }
    }

    fun dismiss(id: String): Job = scope.launch {
        eventsMutationEpoch.incrementAndGet()
        _selectedEventId.update { currentId -> if (currentId == id) null else currentId }
        _events.update { list -> list.filterNot { it.id == id } }
        eventsDbMutex.withLock {
            withContext(Dispatchers.IO) { eventCardStore.dismiss(id) }
            _events.update { list -> list.filterNot { it.id == id } }
            eventsMutationEpoch.incrementAndGet()
        }
    }

    fun updateSettings(newSettings: AppSettings): Job = transformSettings { newSettings }

    /**
     * Atomically updates [settings] via [transform], persists the latest snapshot to SQLite on IO,
     * refreshes chatrooms when data/key paths change, and updates background sync scheduling.
     */
    fun transformSettings(transform: (AppSettings) -> AppSettings): Job {
        lateinit var previous: AppSettings
        val updated = _settings.updateAndGet { current ->
            previous = current
            transform(current)
        }
        return scope.launch {
            settingsMutex.withLock {
                val latest = _settings.value
                withContext(Dispatchers.IO) {
                    settingsStore.save(latest)
                }
            }
            if (updated.wechatDataDir != previous.wechatDataDir ||
                updated.keysPath != previous.keysPath
            ) {
                refreshRooms().join()
            }

            val schedulerConfigChanged = updated.autoSyncEnabled != previous.autoSyncEnabled ||
                updated.syncIntervalMinutes != previous.syncIntervalMinutes ||
                updated.notifyOnNewEvents != previous.notifyOnNewEvents
            if (schedulerConfigChanged) {
                if (updated.autoSyncEnabled) {
                    scheduler.start(
                        intervalMinutes = updated.syncIntervalMinutes,
                        notifyOnNewEvents = updated.notifyOnNewEvents
                    )
                } else {
                    scheduler.stop()
                }
            }

            val localServerShouldReset = (previous.llm.providerType == LlmProviderType.BUILT_IN_LOCAL &&
                updated.llm.providerType != LlmProviderType.BUILT_IN_LOCAL) ||
                updated.llm.localModelId != previous.llm.localModelId ||
                updated.llm.localContextSize != previous.llm.localContextSize
            if (localServerShouldReset) {
                llamaServerManager.stop()
            }
        }
    }

    fun selectLocalModel(modelId: String): Job {
        return transformSettings { current ->
            val resolvedName = localModelStore.resolveModel(modelId)?.displayName
                ?: LocalModelCatalog.findById(modelId)?.displayName
                ?: current.llm.modelName
            current.copy(
                llm = current.llm.copy(
                    providerType = LlmProviderType.BUILT_IN_LOCAL,
                    localModelId = modelId,
                    modelName = resolvedName
                )
            )
        }
    }

    fun selectLocalMirror(mirror: LocalModelMirror): Job {
        return transformSettings { current ->
            current.copy(llm = current.llm.copy(localModelMirror = mirror.name))
        }
    }

    fun downloadLocalModel(
        modelId: String,
        mirrorOverride: LocalModelMirror? = null,
        autoSelectIfNone: Boolean = true,
        onDownloaded: ((LocalModelSpec) -> Unit)? = null
    ): Job {
        val spec = LocalModelCatalog.findById(modelId) ?: return scope.launch { }
        val mirror = mirrorOverride ?: LocalModelMirror.fromKey(_settings.value.llm.localModelMirror)
        return localModelDownloadManager.startDownload(scope, spec, mirror) { result ->
            result.onSuccess {
                scope.launch {
                    val curLlm = _settings.value.llm
                    if (autoSelectIfNone &&
                        curLlm.providerType == LlmProviderType.BUILT_IN_LOCAL &&
                        localModelStore.resolveModel(curLlm.localModelId) == null
                    ) {
                        selectLocalModel(spec.id)
                    }
                    onDownloaded?.invoke(spec)
                    _snackbarMessage.emit("Downloaded ${spec.displayName}.")
                }
            }.onFailure { err ->
                if (err !is CancellationException) {
                    scope.launch {
                        _snackbarMessage.emit("Download failed for ${spec.displayName}: ${LlmJsonUtils.sanitizeLogMessage(err.message)}")
                    }
                }
            }
        }
    }

    fun cancelLocalModelDownload(modelId: String) {
        localModelDownloadManager.cancelDownload(modelId)
    }

    fun deleteDownloadedLocalModel(modelId: String): Job = scope.launch {
        localModelDownloadManager.cancelDownload(modelId)?.cancelAndJoin()
        val wasActive = _settings.value.llm.localModelId == modelId
        if (wasActive) {
            llamaServerManager.stop()
        }
        val deleted = withContext(Dispatchers.IO) {
            localModelStore.deleteCatalogModel(modelId)
        }
        if (wasActive) {
            fallbackToAvailableLocalModel(modelId)
        }
        if (deleted) {
            _snackbarMessage.emit("Deleted local model files.")
        }
    }

    fun importLocalGgufFile(
        file: File,
        autoSelect: Boolean = true,
        onImported: ((ImportedLocalModel) -> Unit)? = null
    ): Job = scope.launch {
        val result = withContext(Dispatchers.IO) {
            localModelStore.importGgufFile(file)
        }
        result.onSuccess { imported ->
            if (autoSelect) {
                selectLocalModel(imported.id)
            }
            onImported?.invoke(imported)
            _snackbarMessage.emit("Imported ${imported.displayName}.")
        }.onFailure { err ->
            _snackbarMessage.emit("Import failed: ${LlmJsonUtils.sanitizeLogMessage(err.message)}")
        }
    }

    fun removeImportedLocalModel(modelId: String): Job = scope.launch {
        val wasActive = _settings.value.llm.localModelId == modelId
        if (wasActive) {
            llamaServerManager.stop()
        }
        val removed = withContext(Dispatchers.IO) {
            localModelStore.removeImportedModel(modelId)
        }
        if (wasActive) {
            fallbackToAvailableLocalModel(modelId)
        }
        if (removed) {
            _snackbarMessage.emit("Removed imported model from list (original file kept on disk).")
        }
    }

    private suspend fun fallbackToAvailableLocalModel(removedModelId: String) {
        val fallbackId = localModelStore.downloadedModelIds.value.firstOrNull()
            ?: localModelStore.importedModels.value.firstOrNull { localModelStore.resolveModel(it.id) != null }?.id
            ?: ""
        transformSettings { cur ->
            if (cur.llm.localModelId == removedModelId) {
                val resolvedName = localModelStore.resolveModel(fallbackId)?.displayName ?: cur.llm.modelName
                cur.copy(llm = cur.llm.copy(localModelId = fallbackId, modelName = resolvedName))
            } else {
                cur
            }
        }.join()
    }

    fun unloadLocalModel(): Job = scope.launch {
        llamaServerManager.stop()
        _snackbarMessage.emit("Unloaded local model from memory.")
    }

    fun close() {
        scheduler.stop()
        localModelDownloadManager.cancelAll()
        scope.cancel()
        llamaServerManager.shutdownBlocking()
        runCatching { ollamaService.close() }
        runCatching { localModelDownloadManager.close() }
        runCatching { llamaRuntimeManager.close() }
        runCatching { llamaServerManager.close() }
    }

    fun toggleRoomMonitored(roomId: String): Job {
        return transformSettings { current ->
            val updated = if (current.monitoredRoomIds.contains(roomId)) {
                current.monitoredRoomIds - roomId
            } else {
                current.monitoredRoomIds + roomId
            }
            current.copy(monitoredRoomIds = updated)
        }
    }

    fun refreshRooms(): Job {
        refreshRoomsJob?.cancel()
        val job = scope.launch {
            refreshRoomsInternal()
        }
        refreshRoomsJob = job
        return job
    }

    private fun resolveDriverFromSettings(): WeChatDatabaseDriver? {
        val snapshot = _settings.value
        val expandedDir = com.wechatdigest.core.detector.WeChatPathDetector.resolveUserPath(snapshot.wechatDataDir)
            ?: return null
        val canonicalDir = runCatching { expandedDir.canonicalFile }.getOrNull() ?: return null
        if (!canonicalDir.exists() || !canonicalDir.isDirectory) return null
        val keysFile = com.wechatdigest.core.detector.WeChatPathDetector.resolveUserPath(snapshot.keysPath)
            ?.let { runCatching { it.canonicalFile }.getOrNull() }
            ?.takeIf { it.exists() && it.isFile }
        return WeChatDatabaseDriver(
            baseDir = canonicalDir,
            customKeysFile = keysFile,
            cacheDir = decryptedCacheDir
        )
    }

    private suspend fun refreshRoomsInternal() {
        _isLoadingRooms.value = true
        try {
            val driver = withContext(Dispatchers.IO) { resolveDriverFromSettings() }
            if (driver == null) {
                _availableRooms.value = emptyList()
                return
            }
            val rooms = withContext(Dispatchers.IO) {
                WeChatContactRepository(driver).listAllChatrooms()
            }
            _availableRooms.value = rooms
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            System.err.println("[AppState] Failed to load chatrooms: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            _availableRooms.value = emptyList()
        } finally {
            _isLoadingRooms.value = false
        }
    }

    /**
     * Loads resolved group members for a given [roomId] from WeChat's contact.db on IO.
     */
    suspend fun loadRoomMembers(roomId: String): List<com.wechatdigest.core.model.GroupMember> {
        return try {
            withContext(Dispatchers.IO) {
                val driver = resolveDriverFromSettings() ?: return@withContext emptyList()
                WeChatContactRepository(driver).listRoomMembers(roomId)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            System.err.println("[AppState] Failed to load members: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            emptyList()
        }
    }

    internal fun mergeStreamedCard(existing: List<EventCard>, incoming: EventCard): List<EventCard> {
        val withoutDup = existing.filterNot { it.id == incoming.id }
        return (withoutDup + incoming).sortedWith(
            compareByDescending<EventCard> { it.timestampSeconds }
                .thenBy { it.priority.ordinal }
        )
    }

    private fun formatSyncProgress(progress: SyncProgress): String {
        val safeRoom = EventDetailItemParser.sanitizeSingleLine(progress.roomName, 48).ifBlank { "Group ${progress.roomIndex}" }
        val roomPrefix = if (progress.totalRooms > 1) {
            "(${progress.roomIndex}/${progress.totalRooms}) $safeRoom: "
        } else {
            "$safeRoom: "
        }
        val countSuffix = if (progress.newEventsSoFar > 0) " • ${progress.newEventsSoFar} saved" else ""
        return "$roomPrefix${progress.stageLabel}$countSuffix"
    }

    private suspend fun executeSerializedSync(): SyncResult = syncExecutionMutex.withLock {
        val hadStreamedCard = AtomicBoolean(false)
        _syncStatusText.value = "Starting sync..."
        val result = syncService.syncNow(
            settings = _settings.value,
            onCardPersisted = { card ->
                hadStreamedCard.set(true)
                eventsDbMutex.withLock {
                    eventsMutationEpoch.incrementAndGet()
                    _events.update { current -> mergeStreamedCard(current, card) }
                }
            },
            onProgress = { progress ->
                _syncStatusText.value = formatSyncProgress(progress)
            }
        )
        if (hadStreamedCard.get() || (result is SyncResult.Success && result.newEventsCount > 0)) {
            loadEvents().join()
        }
        loadCheckpoints().join()
        result
    }

    private suspend fun runExclusiveSync(): SyncResult? {
        if (!_isSyncing.compareAndSet(expect = false, update = true)) return null
        return try {
            var syncResult: SyncResult? = null
            coroutineScope {
                // Launch the active sync in a dedicated child job so cancelling activeSyncJob
                // (e.g. from resetGroupCheckpoint) aborts this run without cancelling SyncScheduler's loop.
                val syncJob = launch(start = CoroutineStart.LAZY) {
                    syncResult = executeSerializedSync()
                }
                activeSyncJob = syncJob
                syncJob.start()
                syncJob.join()
                if (syncJob.isCancelled) {
                    currentCoroutineContext().ensureActive()
                    syncResult = null
                }
            }
            syncResult
        } finally {
            activeSyncJob = null
            _syncStatusText.value = null
            _isSyncing.value = false
        }
    }

    fun triggerSync(): Job = scope.launch {
        try {
            val result = runExclusiveSync() ?: return@launch
            when (result) {
                is SyncResult.Success -> {
                    val statusMsg = if (result.roomErrors.isNotEmpty()) {
                        "Sync completed with ${result.roomErrors.size} room error(s): ${result.roomErrors.first()} (${result.newEventsCount} new events)."
                    } else {
                        "Sync complete: ${result.newEventsCount} new events from ${result.totalScannedMessages} messages."
                    }
                    _snackbarMessage.emit(statusMsg)
                }
                is SyncResult.Failure -> {
                    _snackbarMessage.emit("Sync failed: ${result.message}")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _snackbarMessage.emit("Sync error: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    fun translateSourceMessages(cardId: String): Job = scope.launch {
        val previous = _translatingCardIds.getAndUpdate { it + cardId }
        if (cardId in previous) return@launch
        val card = _events.value.firstOrNull { it.id == cardId }
        if (card == null || card.sourceMessages.isBlank() || card.translatedSourceMessages.isNotBlank()) {
            _translatingCardIds.update { it - cardId }
            return@launch
        }
        try {
            val currentSettings = _settings.value
            val result = withContext(Dispatchers.IO) {
                createLlmClient(currentSettings.llm).use { client ->
                    MessageTranslator(client).translateMessages(
                        sourceMessages = card.sourceMessages,
                        targetLanguage = currentSettings.targetLanguage
                    )
                }
            }
            result.onSuccess { translated ->
                eventsDbMutex.withLock {
                    withContext(Dispatchers.IO) {
                        eventCardStore.updateTranslatedSourceMessages(card.id, translated)
                    }
                    eventsMutationEpoch.incrementAndGet()
                    _events.update { list ->
                        list.map { existing ->
                            if (existing.id == cardId) existing.copy(translatedSourceMessages = translated) else existing
                        }
                    }
                }
            }.onFailure { err ->
                _snackbarMessage.emit("Message translation failed: ${LlmJsonUtils.sanitizeLogMessage(err.message)}")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _snackbarMessage.emit("Message translation failed: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        } finally {
            _translatingCardIds.update { it - cardId }
        }
    }

    /**
     * Converts unstructured bullet points (or previously merged single cards) inside [cardId]'s
     * sections into tailored 1-to-1 icon detail cards using Pass 4 ([SectionCardEnricher]) and
     * persists the updated sections to SQLite.
     */
    fun enrichSectionCards(cardId: String, targetSectionIndex: Int? = null): Job {
        lateinit var launchedJob: Job
        launchedJob = scope.launch {
            val previous = _enrichingCardIds.getAndUpdate { it + cardId }
            if (cardId in previous) return@launch
            try {
                val card = _events.value.firstOrNull { it.id == cardId } ?: return@launch
                if (card.sections.isEmpty()) return@launch
                val explicitTargets = targetSectionIndex
                    ?.takeIf { it in card.sections.indices }
                    ?.let { idx ->
                        val autoTargets = card.sections.indices.filter {
                            EventDetailItemParser.hasUnstructuredBullets(card.sections[it]) ||
                                EventDetailItemParser.extractAtomicBulletsForEnrichment(card.sections[it]).size >
                                EventDetailItemParser.sanitizeItems(card.sections[it].items).size
                        }
                        (listOf(idx) + autoTargets).distinct()
                    }

                val currentSettings = _settings.value
                val result = withContext(Dispatchers.IO) {
                    createLlmClient(currentSettings.llm).use { client ->
                        SectionCardEnricher(client).enrichCardSections(
                            card = card,
                            targetLanguage = currentSettings.targetLanguage,
                            targetSectionIndices = explicitTargets
                        )
                    }
                }
                val updatedSections = result.getOrElse { err ->
                    _snackbarMessage.emit("Section structuring failed: ${LlmJsonUtils.sanitizeLogMessage(err.message)}")
                    return@launch
                }
                eventsDbMutex.withLock {
                    withContext(Dispatchers.IO) {
                        eventCardStore.updateSectionsOrThrow(card.id, updatedSections)
                    }
                    eventsMutationEpoch.incrementAndGet()
                    _events.update { list ->
                        list.map { existing -> if (existing.id == cardId) existing.copy(sections = updatedSections) else existing }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _snackbarMessage.emit("Section structuring failed: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            } finally {
                _enrichingCardIds.update { it - cardId }
            }
        }
        enrichmentJobs[cardId] = launchedJob
        launchedJob.invokeOnCompletion { enrichmentJobs.remove(cardId, launchedJob) }
        return launchedJob
    }

    private fun cancelEnrichmentJobs() {
        val jobs = enrichmentJobs.values.toList()
        enrichmentJobs.clear()
        jobs.forEach { it.cancel() }
        _enrichingCardIds.value = emptySet()
    }

    /**
     * Opens a downloaded safe document [attachment] in its default viewer, strictly confined
     * under WeChat's verified `msg/file` folder.
     */
    fun openAttachment(attachment: EventAttachment): Job =
        performAttachmentAction(attachment, verb = "open") { file, allowedRoot ->
            attachmentLauncher.openFile(file, allowedRoot = allowedRoot)
        }

    /**
     * Reveals a downloaded [attachment] in Finder, strictly confined under WeChat's `msg/file` folder.
     */
    fun revealAttachmentInFinder(attachment: EventAttachment): Job =
        performAttachmentAction(attachment, verb = "reveal") { file, allowedRoot ->
            attachmentLauncher.revealInFinder(file, allowedRoot = allowedRoot)
        }

    private fun performAttachmentAction(
        attachment: EventAttachment,
        verb: String,
        action: (File, File) -> Result<Unit>
    ): Job = scope.launch {
        val localPath = attachment.localPath?.takeIf { it.isNotBlank() }
        if (localPath == null) {
            _snackbarMessage.emit("File is not downloaded on disk yet. Open it in WeChat first, then click Re-check.")
            return@launch
        }
        val result = withContext(Dispatchers.IO) {
            val driver = resolveDriverFromSettings()
            val explicitAttachmentRoot = com.wechatdigest.core.detector.WeChatPathDetector.resolveUserPath(_settings.value.wechatAttachmentRootDir)
            val allowedRoot = driver?.let {
                WeChatAttachmentResolver(it, explicitAttachmentRootDir = explicitAttachmentRoot).resolveMsgFileRoot()
            } ?: return@withContext Result.failure<Unit>(
                IllegalStateException("WeChat attachments folder could not be verified.")
            )
            action(File(localPath), allowedRoot)
        }
        if (result.isFailure) {
            val err = LlmJsonUtils.sanitizeLogMessage(result.exceptionOrNull()?.message)
            if (err == "WeChat attachments folder could not be verified.") {
                _snackbarMessage.emit(err)
            } else {
                _snackbarMessage.emit("Failed to $verb '${attachment.fileName}': $err")
            }
        }
    }

    /**
     * Re-scans WeChat's `hardlink.db` and `msg/file/` folders on [Dispatchers.IO] to locate newly
     * downloaded attachments for [cardId] and persists updated paths to [EventCardStore].
     */
    fun recheckCardAttachments(cardId: String): Job = scope.launch {
        val previous = _recheckingAttachmentCardIds.getAndUpdate { it + cardId }
        if (cardId in previous) return@launch
        val card = _events.value.firstOrNull { it.id == cardId }
        if (card == null || card.attachments.isEmpty()) {
            _recheckingAttachmentCardIds.update { it - cardId }
            return@launch
        }
        try {
            val (refreshed, foundCount) = eventsDbMutex.withLock {
                withContext(Dispatchers.IO) {
                    val driver = resolveDriverFromSettings() ?: return@withContext null to 0
                    val explicitAttachmentRoot = com.wechatdigest.core.detector.WeChatPathDetector.resolveUserPath(_settings.value.wechatAttachmentRootDir)
                    val updatedList = WeChatAttachmentResolver(driver, explicitAttachmentRootDir = explicitAttachmentRoot)
                        .refreshAttachments(card.attachments)
                    eventCardStore.updateAttachmentsOrThrow(cardId, updatedList)
                    updatedList to updatedList.count { it.isLocalFilePresent() }
                }.also { (updatedList, _) ->
                    if (updatedList != null) {
                        eventsMutationEpoch.incrementAndGet()
                        _events.update { list ->
                            list.map { existing ->
                                if (existing.id == cardId) existing.copy(attachments = updatedList) else existing
                            }
                        }
                    }
                }
            }
            if (refreshed == null) {
                _snackbarMessage.emit("WeChat data directory is not configured or does not exist.")
                return@launch
            }
            if (foundCount > 0) {
                _snackbarMessage.emit("Located $foundCount downloaded attachment${if (foundCount == 1) "" else "s"}.")
            } else {
                _snackbarMessage.emit("Attachment still not found in WeChat's local files.")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _snackbarMessage.emit("Attachment re-check failed: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        } finally {
            _recheckingAttachmentCardIds.update { it - cardId }
        }
    }
}

