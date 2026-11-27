package com.wechatdigest.app

import com.wechatdigest.core.attachment.AttachmentLauncher
import com.wechatdigest.core.attachment.WeChatAttachmentResolver
import com.wechatdigest.core.calendar.CalendarLauncher
import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.db.WeChatSessionContext
import com.wechatdigest.core.detector.DiscoveredPath
import com.wechatdigest.core.detector.WeChatPathDetector
import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmClient
import com.wechatdigest.core.llm.LlmCompletionOptions
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
import com.wechatdigest.core.model.DeadlineTriage
import com.wechatdigest.core.model.DetailChecklistKeys
import com.wechatdigest.core.model.EventAttachment
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventDetailItemParser
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.GroupChat
import com.wechatdigest.core.model.GroupConfig
import com.wechatdigest.core.model.SourceMessageParser
import com.wechatdigest.core.model.SyncCheckpoint
import com.wechatdigest.core.model.WeChatMessage
import com.wechatdigest.core.notification.NotificationDispatcher
import com.wechatdigest.core.repository.WeChatContactRepository
import com.wechatdigest.core.repository.WeChatMessageRepository
import com.wechatdigest.core.sanitizer.MessageSanitizer
import com.wechatdigest.core.sanitizer.PrefilterMatchType
import com.wechatdigest.core.sanitizer.PrefilterRule
import com.wechatdigest.storage.EventCardStore
import com.wechatdigest.storage.GroupConfigStore
import com.wechatdigest.storage.SettingsStore
import com.wechatdigest.storage.SyncCheckpointStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.isActive
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
    OLDEST_FIRST("Oldest to newest"),
    DEADLINE_SOONEST("Deadline soonest")
}

private data class FeedFilterCriteria(
    val priority: EventPriority?,
    val category: EventCategory?,
    val roomId: String?,
    val starredOnly: Boolean,
    val unreadOnly: Boolean,
    val hideCompleted: Boolean,
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
    val categoryFilter = MutableStateFlow<EventCategory?>(null)
    val groupFilter = MutableStateFlow<String?>(null)
    val starredOnlyFilter = MutableStateFlow(false)
    val unreadOnlyFilter = MutableStateFlow(false)
    val hideCompletedFilter = MutableStateFlow(false)
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

    private val _isTestingLlmConnection = MutableStateFlow(false)
    val isTestingLlmConnection: StateFlow<Boolean> = _isTestingLlmConnection.asStateFlow()

    private val _llmConnectionStatusMessage = MutableStateFlow<String?>(null)
    val llmConnectionStatusMessage: StateFlow<String?> = _llmConnectionStatusMessage.asStateFlow()

    private val _syncCheckpoints = MutableStateFlow<Map<String, SyncCheckpoint>>(emptyMap())
    val syncCheckpoints: StateFlow<Map<String, SyncCheckpoint>> = _syncCheckpoints.asStateFlow()

    private val attributeFiltersFlow = combine(
        priorityFilter,
        categoryFilter,
        groupFilter
    ) { priority, category, roomId ->
        Triple(priority, category, roomId)
    }

    private val toggleFiltersFlow = combine(
        starredOnlyFilter,
        unreadOnlyFilter,
        hideCompletedFilter
    ) { starredOnly, unreadOnly, hideCompleted ->
        Triple(starredOnly, unreadOnly, hideCompleted)
    }

    private val filterCriteriaFlow = combine(
        attributeFiltersFlow,
        toggleFiltersFlow,
        sortOrder,
        searchQuery
    ) { (priority, category, roomId), (starredOnly, unreadOnly, hideCompleted), order, query ->
        FeedFilterCriteria(priority, category, roomId, starredOnly, unreadOnly, hideCompleted, order, query)
    }

    val visibleEvents: StateFlow<List<EventCard>> = combine(
        _events,
        filterCriteriaFlow
    ) { allEvents, criteria ->
        val filtered = allEvents.filter { card ->
            val matchesPriority = criteria.priority == null || card.priority == criteria.priority
            val matchesCategory = criteria.category == null || card.category == criteria.category
            val matchesGroup = criteria.roomId == null || card.sourceRoomId == criteria.roomId
            val matchesStarred = !criteria.starredOnly || card.isStarred
            val matchesUnread = !criteria.unreadOnly || !card.isRead
            val matchesHideCompleted = !criteria.hideCompleted || !card.isCompleted
            val query = criteria.query
            val matchesQuery = if (query.isBlank()) true else {
                card.title.contains(query, ignoreCase = true) ||
                card.summary.contains(query, ignoreCase = true) ||
                card.translatedSummary.contains(query, ignoreCase = true) ||
                card.originalSender.contains(query, ignoreCase = true) ||
                card.sourceRoomName.contains(query, ignoreCase = true) ||
                card.category.name.contains(query, ignoreCase = true) ||
                (card.eventDate?.contains(query, ignoreCase = true) == true) ||
                card.sourceMessages.contains(query, ignoreCase = true) ||
                card.translatedSourceMessages.contains(query, ignoreCase = true) ||
                card.attachments.any { it.fileName.contains(query, ignoreCase = true) } ||
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
            matchesPriority && matchesCategory && matchesGroup && matchesStarred &&
                matchesUnread && matchesHideCompleted && matchesQuery
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
            EventSortOrder.DEADLINE_SOONEST -> filtered.sortedWith(
                compareBy<EventCard> { DeadlineTriage.deadlineSortEpoch(it) }
                    .thenBy { it.priority.ordinal }
                    .thenByDescending { it.timestampSeconds }
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

    private val _reextractingCardIds = MutableStateFlow<Set<String>>(emptySet())

    /** Card IDs currently running single-card LLM re-extraction. */
    val reextractingCardIds: StateFlow<Set<String>> = _reextractingCardIds.asStateFlow()

    private val enrichmentJobs = ConcurrentHashMap<String, Job>()
    private val reextractJobs = ConcurrentHashMap<String, Job>()
    private val lastPendingRecheckMs = AtomicLong(0L)
    private val isPendingRecheckRunning = AtomicBoolean(false)

    private val settingsMutex = Mutex()
    private val settingsSideEffectsMutex = Mutex()
    private val groupConfigMutex = Mutex()
    private val syncExecutionMutex = Mutex()
    private val eventsDbMutex = Mutex()
    private val eventsMutationEpoch = AtomicLong(0L)
    private val checkpointEpoch = AtomicLong(0L)
    @Volatile
    private var loadEventsJob: Job? = null
    @Volatile
    private var activeSyncJob: Job? = null
    @Volatile
    private var activeSyncCompletion: CompletableDeferred<Unit>? = null
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
        scope = scope,
        thresholdProvider = { _settings.value.notificationThreshold }
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
            syncExecutionMutex.withLock {
                val capturedEpoch = checkpointEpoch.get()
                val map = withContext(Dispatchers.IO) {
                    syncCheckpointStore.getAll()
                }
                if (checkpointEpoch.get() == capturedEpoch) {
                    _syncCheckpoints.value = map
                }
            }
        }
    }

    fun resetGroupCheckpoint(roomId: String): Job = scope.launch {
        if (syncCheckpointStore != null) {
            checkpointEpoch.incrementAndGet()
            activeSyncJob?.cancel()
            activeSyncCompletion?.await()
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
        checkpointEpoch.incrementAndGet()
        activeSyncJob?.cancel()
        activeSyncCompletion?.await()
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
        categoryFilter.value = null
        starredOnlyFilter.value = false
        unreadOnlyFilter.value = false
        hideCompletedFilter.value = false
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
        checkpointEpoch.incrementAndGet()
        val pendingRoomsJob = refreshRoomsJob
        refreshRoomsJob = null
        pendingRoomsJob?.cancelAndJoin()
        activeSyncJob?.cancel()
        activeSyncCompletion?.await()
        cancelEnrichmentJobs()
        llamaServerManager.stop()
        syncExecutionMutex.withLock {
            eventsDbMutex.withLock {
                eventsMutationEpoch.incrementAndGet()
                withContext(Dispatchers.IO) {
                    eventCardStore.clearAll()
                    syncCheckpointStore?.resetAll()
                    groupConfigStore?.clearAll()
                    calendarLauncher.clearExportedFiles()
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
        _llmConnectionStatusMessage.value = null
        priorityFilter.value = null
        categoryFilter.value = null
        groupFilter.value = null
        starredOnlyFilter.value = false
        unreadOnlyFilter.value = false
        hideCompletedFilter.value = false
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
            val configs = groupConfigMutex.withLock {
                withContext(Dispatchers.IO) {
                    groupConfigStore.getAll()
                }
            }
            _groupConfigs.value = configs
        }
    }

    fun saveGroupConfig(config: GroupConfig): Job = scope.launch {
        if (groupConfigStore != null) {
            groupConfigMutex.withLock {
                withContext(Dispatchers.IO) {
                    groupConfigStore.save(config)
                    groupConfigStore.getAll()
                }.also {
                    _groupConfigs.value = it
                }
            }
            _snackbarMessage.emit("Saved rules for room.")
        }
    }

    fun deleteGroupConfig(roomId: String): Job = scope.launch {
        if (groupConfigStore != null) {
            groupConfigMutex.withLock {
                withContext(Dispatchers.IO) {
                    groupConfigStore.delete(roomId)
                    groupConfigStore.getAll()
                }.also {
                    _groupConfigs.value = it
                }
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

    /** Exports [card] directly to Apple Reminders on macOS via osascript. */
    fun exportToReminders(card: EventCard): Job = scope.launch {
        val result = withContext(Dispatchers.IO) {
            calendarLauncher.exportToReminders(card)
        }
        if (result.isSuccess) {
            _snackbarMessage.emit("Added '${card.title}' to Reminders.")
        } else {
            val err = LlmJsonUtils.sanitizeLogMessage(result.exceptionOrNull()?.message)
            _snackbarMessage.emit("Failed to add to Reminders: $err")
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

    /** Toggles the user-triaged completion status ([EventCard.isCompleted]) for [id] and persists fail-closed to SQLite. */
    fun toggleCompleted(id: String): Job = scope.launch {
        try {
            eventsDbMutex.withLock {
                val current = _events.value.firstOrNull { it.id == id } ?: return@withLock
                val newCompleted = !current.isCompleted
                withContext(Dispatchers.IO) { eventCardStore.setCompletedOrThrow(id, newCompleted) }
                eventsMutationEpoch.incrementAndGet()
                _events.update { list -> list.map { if (it.id == id) it.copy(isCompleted = newCompleted) else it } }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _snackbarMessage.emit("Failed to update done state: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    /** Toggles completion of a valid checklist key ([DetailChecklistKeys.keyFor]) on [cardId] fail-closed. */
    fun toggleCheckedItem(cardId: String, itemKey: String): Job = scope.launch {
        try {
            eventsDbMutex.withLock {
                val current = _events.value.firstOrNull { it.id == cardId } ?: return@withLock
                val cleanKey = itemKey.trim().take(DetailChecklistKeys.MAX_CHECKED_KEY_CHARS)
                if (cleanKey.isBlank()) return@withLock
                val validKeys = DetailChecklistKeys.validKeysForSections(current.sections)
                if (validKeys.isNotEmpty() && cleanKey !in validKeys) return@withLock
                val rawNext = if (cleanKey in current.checkedItems) {
                    current.checkedItems - cleanKey
                } else {
                    current.checkedItems + cleanKey
                }
                val updatedItems = DetailChecklistKeys.sanitizeCheckedItems(
                    rawNext,
                    sections = current.sections.takeIf { validKeys.isNotEmpty() }
                )
                withContext(Dispatchers.IO) { eventCardStore.setCheckedItemsOrThrow(cardId, updatedItems) }
                eventsMutationEpoch.incrementAndGet()
                _events.update { list -> list.map { if (it.id == cardId) it.copy(checkedItems = updatedItems) else it } }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _snackbarMessage.emit("Failed to save checklist item: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
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
            settingsSideEffectsMutex.withLock {
                if (updated.wechatDataDir != previous.wechatDataDir ||
                    updated.keysPath != previous.keysPath
                ) {
                    refreshRooms().join()
                }
                val latest = _settings.value
                val schedulerConfigChanged = updated.autoSyncEnabled != previous.autoSyncEnabled ||
                    updated.syncIntervalMinutes != previous.syncIntervalMinutes ||
                    updated.notifyOnNewEvents != previous.notifyOnNewEvents
                if (schedulerConfigChanged) {
                    if (latest.autoSyncEnabled) {
                        scheduler.start(
                            intervalMinutes = latest.syncIntervalMinutes,
                            notifyOnNewEvents = latest.notifyOnNewEvents
                        )
                    } else {
                        scheduler.stop()
                    }
                }

                val localServerShouldReset = (previous.llm.providerType == LlmProviderType.BUILT_IN_LOCAL &&
                    latest.llm.providerType != LlmProviderType.BUILT_IN_LOCAL) ||
                    updated.llm.localModelId != previous.llm.localModelId ||
                    updated.llm.localContextSize != previous.llm.localContextSize
                if (localServerShouldReset) {
                    llamaServerManager.stop()
                }
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
        if (Files.isSymbolicLink(expandedDir.toPath())) return null
        val canonicalDir = runCatching { expandedDir.canonicalFile }.getOrNull() ?: return null
        if (!canonicalDir.exists() || !canonicalDir.isDirectory) return null
        val resolvedKeys = com.wechatdigest.core.detector.WeChatPathDetector.resolveUserPath(snapshot.keysPath)
        val keysFile = resolvedKeys
            ?.takeUnless { Files.isSymbolicLink(it.toPath()) }
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
        val withoutDupOrSuperseded = existing.filterNot { card ->
            card.id == incoming.id || (incoming.supersedesCardId != null && card.id == incoming.supersedesCardId)
        }
        return (withoutDupOrSuperseded + incoming).sortedWith(
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
        var syncResult: SyncResult? = null
        try {
            syncResult = syncService.syncNow(
                settings = _settings.value,
                onCardPersisted = { card ->
                    hadStreamedCard.set(true)
                    eventsDbMutex.withLock {
                        eventsMutationEpoch.incrementAndGet()
                        _events.update { current -> mergeStreamedCard(current, card) }
                    }
                },
                onProgress = { progress ->
                    if (currentCoroutineContext().isActive) {
                        _syncStatusText.value = formatSyncProgress(progress)
                    }
                }
            )
            syncResult
        } finally {
            withContext(NonCancellable) {
                if (hadStreamedCard.get() || (syncResult is SyncResult.Success && syncResult.newEventsCount > 0)) {
                    loadEvents().join()
                }
                val capturedEpoch = checkpointEpoch.get()
                if (syncCheckpointStore != null) {
                    val map = withContext(Dispatchers.IO) { syncCheckpointStore.getAll() }
                    if (checkpointEpoch.get() == capturedEpoch) {
                        _syncCheckpoints.value = map
                    }
                }
            }
        }
    }

    private suspend fun runExclusiveSync(): SyncResult? {
        if (!_isSyncing.compareAndSet(expect = false, update = true)) return null
        val completion = CompletableDeferred<Unit>()
        activeSyncCompletion = completion
        return try {
            var syncResult: SyncResult? = null
            coroutineScope {
                // Launch the active sync in a dedicated child job so cancelling activeSyncJob
                // (e.g. from resetGroupCheckpoint or cancelSync) aborts this run without cancelling SyncScheduler's loop.
                val syncJob = launch(start = CoroutineStart.LAZY) {
                    syncResult = executeSerializedSync()
                }
                activeSyncJob = syncJob
                syncJob.start()
                syncJob.join()
                if (syncJob.isCancelled) {
                    currentCoroutineContext().ensureActive()
                    syncResult = SyncResult.Cancelled
                }
            }
            syncResult
        } finally {
            activeSyncJob = null
            _syncStatusText.value = null
            _isSyncing.value = false
            activeSyncCompletion = null
            completion.complete(Unit)
        }
    }

    /** Requests cooperative cancellation of any currently active sync run and waits until state settles. */
    fun cancelSync(): Job = scope.launch {
        val job = activeSyncJob ?: run {
            activeSyncCompletion?.await()
            return@launch
        }
        val completion = activeSyncCompletion
        _syncStatusText.value = "Cancelling sync..."
        job.cancelAndJoin()
        completion?.await()
    }

    /** Clears transient LLM connection probe status text (e.g., on provider change or wizard open). */
    fun clearConnectionStatus() {
        _llmConnectionStatusMessage.value = null
    }

    /** Surfaces a short non-fatal message to the global Snackbar host. */
    fun showMessage(message: String): Job = scope.launch {
        _snackbarMessage.emit(message)
    }

    /**
     * Runs a minimal single-token test prompt against the currently configured LLM provider (or [overrideConfig])
     * and publishes human-readable status via [llmConnectionStatusMessage] and [snackbarMessage].
     */
    fun testLlmConnection(overrideConfig: LlmConfig? = null): Job = scope.launch {
        if (!_isTestingLlmConnection.compareAndSet(false, true)) return@launch
        val config = overrideConfig ?: _settings.value.llm
        _llmConnectionStatusMessage.value = "Testing connection..."
        try {
            if (config.providerType == LlmProviderType.BUILT_IN_LOCAL && _isSyncing.value) {
                val msg = "Wait for active sync to finish before testing local model."
                _llmConnectionStatusMessage.value = msg
                _snackbarMessage.emit(msg)
                return@launch
            }
            if (config.providerType == LlmProviderType.BUILT_IN_LOCAL &&
                llmClientFactory == null &&
                localModelStore.resolveModel(config.localModelId) == null
            ) {
                val msg = "No downloaded or imported local model selected."
                _llmConnectionStatusMessage.value = msg
                _snackbarMessage.emit(msg)
                return@launch
            }
            val reply = withContext(Dispatchers.IO) {
                createLlmClient(config).use { client ->
                    client.chatCompletion(
                        systemPrompt = "Connection check. Reply briefly.",
                        userPrompt = "Reply with OK.",
                        options = LlmCompletionOptions(maxTokens = 8, allowSchemaFallback = true)
                    )
                }
            }
            val rawLabel = config.modelName.ifBlank { config.localModelId.ifBlank { config.providerType.name } }
            val modelLabel = EventDetailItemParser.sanitizeSingleLine(rawLabel, 80).ifBlank { config.providerType.name }
            val msg = if (reply.isNotBlank()) {
                "Connected to $modelLabel."
            } else {
                "Connected to $modelLabel (empty probe response)."
            }
            _llmConnectionStatusMessage.value = msg
            _snackbarMessage.emit(msg)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = "Connection failed: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            _llmConnectionStatusMessage.value = msg
            _snackbarMessage.emit(msg)
        } finally {
            _isTestingLlmConnection.value = false
        }
    }

    /** Triggers an immediate sample notification using [notificationDispatcher] to verify macOS permissions. */
    fun testNotification(): Job = scope.launch {
        val sent = withContext(Dispatchers.IO) {
            notificationDispatcher.sendNotification(
                title = "WeChat Digest",
                body = "Notifications verified (${_settings.value.notificationThreshold.displayName}).",
                subtitle = "Test Alert"
            )
        }
        if (sent) {
            _snackbarMessage.emit("Sent test notification.")
        } else {
            _snackbarMessage.emit("Notification test failed. Check macOS notification permissions.")
        }
    }

    fun triggerSync(): Job = scope.launch {
        try {
            val result = runExclusiveSync() ?: return@launch
            when (result) {
                is SyncResult.Success -> {
                    if (_settings.value.notifyOnManualSync && result.insertedCards.isNotEmpty()) {
                        SyncScheduler.dispatchNotificationIfEligible(
                            result = result,
                            notifyEnabled = true,
                            threshold = _settings.value.notificationThreshold,
                            dispatcher = notificationDispatcher,
                            subtitle = "Manual Sync"
                        )
                    }
                    val statusMsg = if (result.roomErrors.isNotEmpty()) {
                        "Sync completed with ${result.roomErrors.size} room error(s): ${result.roomErrors.first()} (${result.newEventsCount} new events)."
                    } else {
                        "Sync complete: ${result.newEventsCount} new events from ${result.totalScannedMessages} messages."
                    }
                    _snackbarMessage.emit(statusMsg)
                }
                is SyncResult.Cancelled -> {
                    _snackbarMessage.emit("Sync cancelled.")
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
        val currentSettings = _settings.value
        if (_isSyncing.value && EventExtractor.resolveConcurrencyForConfig(currentSettings.llm) == 1) {
            return scope.launch {
                _snackbarMessage.emit("Wait for active local sync to finish before structuring details.")
            }
        }
        val previous = _enrichingCardIds.getAndUpdate { it + cardId }
        if (cardId in previous) return Job().apply { complete() }

        lateinit var launchedJob: Job
        launchedJob = scope.launch {
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
                        list.map { existing ->
                            if (existing.id == cardId) {
                                existing.copy(
                                    sections = updatedSections,
                                    checkedItems = DetailChecklistKeys.sanitizeCheckedItems(existing.checkedItems, updatedSections)
                                )
                            } else {
                                existing
                            }
                        }
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
        launchedJob.invokeOnCompletion {
            enrichmentJobs.remove(cardId, launchedJob)
            _enrichingCardIds.update { it - cardId }
        }
        return launchedJob
    }

    private suspend fun cancelEnrichmentJobs() {
        val jobs = enrichmentJobs.values.toList()
        enrichmentJobs.clear()
        jobs.forEach { it.cancelAndJoin() }
        _enrichingCardIds.value = emptySet()

        val rxJobs = reextractJobs.values.toList()
        reextractJobs.clear()
        rxJobs.forEach { it.cancelAndJoin() }
        _reextractingCardIds.value = emptySet()
    }

    /** Validated ISO-8601 date/datetime check (including fractional seconds and offsets) for quick-edit inputs. */
    internal fun validateQuickEditDate(rawDate: String?): String? {
        val trimmed = rawDate?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (DeadlineTriage.isValidIsoTemporal(trimmed)) null else {
            "Use YYYY-MM-DD or YYYY-MM-DDTHH:MM format (or leave blank)."
        }
    }

    /**
     * Updates user-edited summary header fields ([title], [category], [priority], [eventDate]) for [cardId]
     * in SQLite and live state while preserving read/star/source attribution state.
     */
    fun quickEditCard(
        cardId: String,
        title: String,
        category: EventCategory,
        priority: EventPriority,
        eventDate: String?
    ): Job = scope.launch {
        val cleanTitle = EventDetailItemParser.sanitizeSingleLine(title, EventDetailItemParser.MAX_CARD_TITLE_CHARS)
        if (cleanTitle.isBlank()) {
            _snackbarMessage.emit("Title cannot be empty.")
            return@launch
        }
        val cleanDate = eventDate?.trim()?.takeIf { it.isNotBlank() }
        validateQuickEditDate(cleanDate)?.let { errMsg ->
            _snackbarMessage.emit(errMsg)
            return@launch
        }
        try {
            val updated = eventsDbMutex.withLock {
                withContext(Dispatchers.IO) {
                    eventCardStore.updateCardFieldsOrThrow(cardId, cleanTitle, category, priority, cleanDate)
                }?.also { persisted ->
                    eventsMutationEpoch.incrementAndGet()
                    _events.update { list -> list.map { if (it.id == cardId) persisted else it } }
                }
            }
            if (updated != null) {
                _snackbarMessage.emit("Updated '${updated.title}'.")
            } else {
                _snackbarMessage.emit("Card is no longer available to edit.")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _snackbarMessage.emit("Failed to save card edits: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    /**
     * Re-runs extraction on a single [cardId] using its stored source messages (or DB anchors),
     * replacing its title/summary/sections/category/priority while preserving read/star flags.
     */
    fun reextractCard(cardId: String): Job {
        val currentSettings = _settings.value
        if (_isSyncing.value && EventExtractor.resolveConcurrencyForConfig(currentSettings.llm) == 1) {
            return scope.launch {
                _snackbarMessage.emit("Wait for active local sync to finish before re-extracting.")
            }
        }
        val previous = _reextractingCardIds.getAndUpdate { it + cardId }
        if (cardId in previous) return Job().apply { complete() }

        lateinit var launchedJob: Job
        launchedJob = scope.launch {
            try {
                val card = _events.value.firstOrNull { it.id == cardId } ?: return@launch
                val preTitle = card.title
                val preCategory = card.category
                val prePriority = card.priority
                val preEventDate = card.eventDate
                val messages = withContext(Dispatchers.IO) { resolveMessagesForReextraction(card) }
                if (messages.isEmpty()) {
                    _snackbarMessage.emit("No source messages available to re-extract.")
                    return@launch
                }
                val groupConfig = withContext(Dispatchers.IO) { groupConfigStore?.get(card.sourceRoomId) }
                val effectivePersona = groupConfig?.customPersona?.takeIf { it.isNotBlank() } ?: currentSettings.userPersona
                val effectiveRules = groupConfig?.customPrompt.orEmpty()
                val preparedMessages = if (currentSettings.parseAttachmentContents) {
                    withContext(Dispatchers.IO) {
                        syncService.enrichMessagesWithAttachmentPreviews(
                            messages = messages,
                            focusHints = syncService.extractFocusHints(effectivePersona, effectiveRules),
                            enableVisualOcr = currentSettings.enableVisualOcr
                        )
                    }
                } else {
                    messages
                }

                val extractedResult = withContext(Dispatchers.IO) {
                    createLlmClient(currentSettings.llm).use { client ->
                        EventExtractor(llmClient = client, maxConcurrency = 1, enableFastPath = true).extractEventsResult(
                            messages = preparedMessages,
                            roomId = card.sourceRoomId,
                            userPersona = effectivePersona,
                            targetLanguage = currentSettings.targetLanguage,
                            customRules = effectiveRules,
                            roomDisplayName = card.sourceRoomName
                        )
                    }
                }
                val extractedCards = extractedResult.getOrElse { err ->
                    _snackbarMessage.emit("Re-extraction failed: ${LlmJsonUtils.sanitizeLogMessage(err.message)}")
                    return@launch
                }
                val rawCandidate = extractedCards.firstOrNull()
                if (rawCandidate == null) {
                    _snackbarMessage.emit("Re-extraction found no actionable announcement changes.")
                    return@launch
                }

                val saved = eventsDbMutex.withLock {
                    val latest = _events.value.firstOrNull { it.id == cardId } ?: return@withLock null
                    val editedInFlight = latest.title != preTitle ||
                        latest.category != preCategory ||
                        latest.priority != prePriority ||
                        latest.eventDate != preEventDate
                    val preparedAttachments = preparedMessages.flatMap { it.attachments }
                    val mergedAttachments = com.wechatdigest.core.llm.ExtractionCardConsolidator
                        .mergeAttachmentsByKey(rawCandidate.attachments + preparedAttachments)
                    val candidate = rawCandidate.copy(
                        title = if (editedInFlight) latest.title else rawCandidate.title,
                        category = if (editedInFlight) latest.category else rawCandidate.category,
                        priority = if (editedInFlight) latest.priority else rawCandidate.priority,
                        eventDate = if (editedInFlight) latest.eventDate else rawCandidate.eventDate,
                        attachments = mergedAttachments,
                        status = latest.status,
                        supersedesCardId = latest.supersedesCardId
                    )
                    withContext(Dispatchers.IO) {
                        eventCardStore.replaceExtractedCardOrThrow(card.id, candidate)
                    }?.also { persisted ->
                        eventsMutationEpoch.incrementAndGet()
                        _events.update { list -> list.map { if (it.id == cardId) persisted else it } }
                    }
                }
                if (saved != null) {
                    _snackbarMessage.emit("Re-extracted '${saved.title}'.")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _snackbarMessage.emit("Re-extraction failed: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
            } finally {
                _reextractingCardIds.update { it - cardId }
            }
        }
        reextractJobs[cardId] = launchedJob
        launchedJob.invokeOnCompletion {
            reextractJobs.remove(cardId, launchedJob)
            _reextractingCardIds.update { it - cardId }
        }
        return launchedJob
    }

    private fun resolveAttachmentResolver(driver: WeChatDatabaseDriver? = resolveDriverFromSettings()): WeChatAttachmentResolver? {
        val activeDriver = driver ?: return null
        val explicitAttachmentRoot = WeChatPathDetector.resolveUserPath(_settings.value.wechatAttachmentRootDir)
        return WeChatAttachmentResolver(activeDriver, explicitAttachmentRootDir = explicitAttachmentRoot)
    }

    private fun resolveMessagesForReextraction(card: EventCard): List<WeChatMessage> {
        val dbMessages = try {
            val driver = resolveDriverFromSettings()
            if (driver == null) {
                emptyList()
            } else {
                WeChatSessionContext(driver).use { session ->
                    val contactRepo = WeChatContactRepository(driver, sessionContext = session)
                    val attachmentResolver = resolveAttachmentResolver(driver)
                    WeChatMessageRepository(driver, contactRepo, attachmentResolver, session)
                        .findAnchorMessages(card.sourceRoomId, card.sourceLocalIds, card.timestampSeconds)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (dbMessages.isNotEmpty()) return dbMessages

        val refreshedAttachments = try {
            val resolver = resolveAttachmentResolver()
            if (resolver != null && card.attachments.isNotEmpty()) {
                resolver.refreshAttachments(card.attachments)
            } else {
                // Fail closed if WeChat attachment root cannot be verified
                card.attachments.map { it.copy(localPath = null) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            card.attachments.map { it.copy(localPath = null) }
        }

        val bubbles = SourceMessageParser.parse(card.sourceMessages)
        return bubbles.mapIndexed { idx, bubble ->
            val senderLabel = bubble.sender.ifBlank { card.originalSender.ifBlank { "Sender" } }
            val senderId = card.originalSenderId.ifBlank { senderLabel }
            WeChatMessage(
                localId = card.sourceLocalIds.getOrElse(idx) { idx.toLong() + 1L },
                timestampSeconds = card.timestampSeconds,
                senderId = senderId,
                senderDisplayName = senderLabel,
                rawContent = bubble.body,
                textContent = bubble.body,
                isZstdCompressed = false,
                attachments = if (idx == bubbles.lastIndex) refreshedAttachments else emptyList(),
                isOwner = bubble.isOwner
            )
        }.filter { it.textContent.isNotBlank() || it.attachments.isNotEmpty() }
    }

    /**
     * Loads up to [beforeCount] earlier and [afterCount] later messages surrounding [card] directly from
     * WeChat's SQLite shards on [Dispatchers.IO].
     */
    suspend fun loadContextMessages(
        card: EventCard,
        beforeCount: Int = 5,
        afterCount: Int = 5
    ): Result<WeChatMessageRepository.ContextMessagesResult> {
        return try {
            withContext(Dispatchers.IO) {
                val driver = resolveDriverFromSettings()
                    ?: return@withContext Result.failure(IllegalStateException("WeChat data directory is not configured or does not exist."))
                WeChatSessionContext(driver).use { session ->
                    val contactRepo = WeChatContactRepository(driver, sessionContext = session)
                    val repo = WeChatMessageRepository(driver, contactRepo, attachmentResolver = null, sessionContext = session)
                    Result.success(
                        repo.getContextMessages(
                            roomId = card.sourceRoomId,
                            anchorLocalIds = card.sourceLocalIds,
                            anchorTimestampSeconds = card.timestampSeconds,
                            beforeCount = beforeCount,
                            afterCount = afterCount
                        )
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(IllegalStateException(LlmJsonUtils.sanitizeLogMessage(e.message)))
        }
    }

    /** Dismisses [card] and adds its [EventCard.originalSenderId] to the group's muted sender blocklist. */
    fun dismissAndMuteSender(card: EventCard): Job = scope.launch {
        dismiss(card.id).join()
        val senderId = card.originalSenderId.trim()
        if (senderId.isBlank() || groupConfigStore == null) {
            _snackbarMessage.emit("Notice dismissed (sender ID unavailable for muting).")
            return@launch
        }
        try {
            groupConfigMutex.withLock {
                withContext(Dispatchers.IO) {
                    val existing = groupConfigStore.getOrThrow(card.sourceRoomId) ?: GroupConfig(roomId = card.sourceRoomId)
                    val updated = existing.copy(
                        mutedSenderIds = (existing.mutedSenderIds + senderId).distinct(),
                        updatedAt = System.currentTimeMillis() / 1000
                    )
                    groupConfigStore.saveOrThrow(updated)
                    groupConfigStore.getAll()
                }.also { _groupConfigs.value = it }
            }
            _snackbarMessage.emit("Dismissed notice and muted sender in room.")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _snackbarMessage.emit("Notice dismissed, but failed updating group mute list: ${LlmJsonUtils.sanitizeLogMessage(e.message)}")
        }
    }

    /** Dismisses [card] and appends a validated static [PrefilterRule] so matching noise is dropped pre-LLM. */
    fun dismissAndAddPrefilterRule(
        card: EventCard,
        pattern: String,
        matchType: PrefilterMatchType = PrefilterMatchType.CONTAINS
    ): Job = scope.launch {
        val cleanPattern = pattern.trim()
        val validationError = MessageSanitizer.validateRulePattern(cleanPattern, matchType)
        if (validationError != null) {
            _snackbarMessage.emit(validationError)
            return@launch
        }
        if (_settings.value.prefilter.rules.size >= MAX_PREFILTER_RULES) {
            _snackbarMessage.emit("Maximum prefilter rules limit ($MAX_PREFILTER_RULES) reached.")
            return@launch
        }
        dismiss(card.id).join()
        transformSettings { current ->
            val alreadyExists = current.prefilter.rules.any {
                it.matchType == matchType && it.pattern.equals(cleanPattern, ignoreCase = true)
            }
            if (alreadyExists || current.prefilter.rules.size >= MAX_PREFILTER_RULES) {
                current
            } else {
                val newRule = PrefilterRule(
                    id = "dismiss-${java.util.UUID.randomUUID().toString().take(8)}",
                    pattern = cleanPattern,
                    matchType = matchType,
                    enabled = true,
                    label = "Muted from feed"
                )
                current.copy(prefilter = current.prefilter.copy(rules = current.prefilter.rules + newRule))
            }
        }.join()
        _snackbarMessage.emit("Dismissed notice and added prefilter rule.")
    }

    private companion object {
        const val MAX_PREFILTER_RULES = 200
        const val PENDING_RECHECK_COOLDOWN_MS = 10_000L
    }

    /**
     * Opens a downloaded safe document [attachment] in its default viewer, strictly confined
     * under WeChat's verified `msg/file` folder.
     */
    fun openAttachment(attachment: EventAttachment): Job =
        performAttachmentAction(attachment, verb = "open") { file, allowedRoots ->
            attachmentLauncher.openFile(file, allowedRoots = allowedRoots)
        }

    /**
     * Reveals a downloaded [attachment] in Finder, strictly confined under WeChat's verified media folders.
     */
    fun revealAttachmentInFinder(attachment: EventAttachment): Job =
        performAttachmentAction(attachment, verb = "reveal") { file, allowedRoots ->
            attachmentLauncher.revealInFinder(file, allowedRoots = allowedRoots)
        }

    private fun performAttachmentAction(
        attachment: EventAttachment,
        verb: String,
        action: (File, List<File>) -> Result<Unit>
    ): Job = scope.launch {
        val localPath = attachment.localPath?.takeIf { it.isNotBlank() }
        if (localPath == null) {
            _snackbarMessage.emit("File is not downloaded on disk yet. Open it in WeChat first, then click Re-check.")
            return@launch
        }
        val result = withContext(Dispatchers.IO) {
            val allowedRoots = resolveAttachmentResolver()?.resolveAllowedMediaRoots().orEmpty()
            if (allowedRoots.isEmpty()) {
                return@withContext Result.failure<Unit>(
                    IllegalStateException("WeChat attachments folder could not be verified.")
                )
            }
            action(File(localPath), allowedRoots)
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
                    val resolver = resolveAttachmentResolver() ?: return@withContext null to 0
                    val updatedList = resolver.refreshAttachments(card.attachments)
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

    /**
     * Re-scans WeChat local storage once per cooldown window across active non-completed cards with
     * missing attachment files (for example when the main window regains focus from WeChat).
     */
    fun recheckAllPendingAttachments(
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Job {
        val candidateCards = _events.value.filter { card ->
            !card.isDismissed &&
                !card.isCompleted &&
                card.attachments.isNotEmpty()
        }
        if (candidateCards.isEmpty()) {
            return Job().apply { complete() }
        }
        val prevMs = lastPendingRecheckMs.get()
        if (!force && prevMs > 0L && nowMs >= prevMs && nowMs - prevMs < PENDING_RECHECK_COOLDOWN_MS) {
            return Job().apply { complete() }
        }
        if (!isPendingRecheckRunning.compareAndSet(false, true)) {
            return Job().apply { complete() }
        }

        val job = scope.launch {
            try {
                val updates = withContext(Dispatchers.IO) {
                    val pendingCards = candidateCards.filter { card ->
                        card.attachments.any { !it.isLocalFilePresent() }
                    }
                    if (pendingCards.isEmpty()) return@withContext emptyList<Pair<EventCard, List<EventAttachment>>>()
                    val resolver = resolveAttachmentResolver() ?: return@withContext emptyList()
                    lastPendingRecheckMs.set(nowMs)
                    pendingCards.mapNotNull { card ->
                        val refreshed = resolver.refreshAttachments(card.attachments)
                        if (refreshed != card.attachments) card to refreshed else null
                    }
                }
                if (updates.isEmpty()) return@launch

                var newlyFoundCount = 0
                val updatedByCardId = mutableMapOf<String, List<EventAttachment>>()
                eventsDbMutex.withLock {
                    val currentById = _events.value.associateBy { it.id }
                    withContext(Dispatchers.IO) {
                        for ((snapCard, refreshedList) in updates) {
                            val latest = currentById[snapCard.id] ?: continue
                            if (latest.isDismissed) continue
                            val refreshedByKey = refreshedList.associateBy { it.previewKey() }
                            val mergedList = latest.attachments.map { latestAtt ->
                                val refreshedAtt = refreshedByKey[latestAtt.previewKey()] ?: return@map latestAtt
                                latestAtt.copy(localPath = refreshedAtt.localPath)
                            }
                            if (mergedList == latest.attachments) continue
                            eventCardStore.updateAttachmentsOrThrow(latest.id, mergedList)
                            updatedByCardId[latest.id] = mergedList
                            val oldPresentKeys = latest.attachments
                                .filter { it.isLocalFilePresent() }
                                .mapTo(HashSet()) { it.previewKey() }
                            newlyFoundCount += mergedList.count { att ->
                                att.isLocalFilePresent() && att.previewKey() !in oldPresentKeys
                            }
                        }
                    }
                    if (updatedByCardId.isNotEmpty()) {
                        eventsMutationEpoch.incrementAndGet()
                        _events.update { list ->
                            list.map { existing ->
                                val updatedList = updatedByCardId[existing.id] ?: return@map existing
                                existing.copy(attachments = updatedList)
                            }
                        }
                    }
                }
                if (newlyFoundCount > 0) {
                    _snackbarMessage.emit(
                        "Located $newlyFoundCount newly downloaded attachment${if (newlyFoundCount == 1) "" else "s"}."
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                System.err.println(
                    "[AppState] Auto attachment re-check failed: ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
                )
            } finally {
                isPendingRecheckRunning.set(false)
            }
        }
        job.invokeOnCompletion { isPendingRecheckRunning.set(false) }
        return job
    }
}

