package com.wechatdigest.app

import com.wechatdigest.core.calendar.CalendarLauncher
import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.detector.DiscoveredPath
import com.wechatdigest.core.detector.WeChatPathDetector
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LocalOllamaModel
import com.wechatdigest.core.llm.MessageTranslator
import com.wechatdigest.core.llm.OllamaDiscoveryResult
import com.wechatdigest.core.llm.OllamaService
import com.wechatdigest.core.model.EventCard
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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
    private val llmClientFactory: (LlmConfig) -> LlmClient = { KtorLlmClient(it) }
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
    val sortOrder = MutableStateFlow(EventSortOrder.NEWEST_FIRST)
    val searchQuery = MutableStateFlow("")
    val groupSearchQuery = MutableStateFlow("")

    private val _selectedEventId = MutableStateFlow<String?>(null)
    val selectedEventId: StateFlow<String?> = _selectedEventId.asStateFlow()

    private val _detectedOllamaModels = MutableStateFlow<List<LocalOllamaModel>>(emptyList())
    val detectedOllamaModels: StateFlow<List<LocalOllamaModel>> = _detectedOllamaModels.asStateFlow()

    private val _isDetectingOllama = MutableStateFlow(false)
    val isDetectingOllama: StateFlow<Boolean> = _isDetectingOllama.asStateFlow()

    private val _ollamaStatusMessage = MutableStateFlow<String?>(null)
    val ollamaStatusMessage: StateFlow<String?> = _ollamaStatusMessage.asStateFlow()

    private val _syncCheckpoints = MutableStateFlow<Map<String, SyncCheckpoint>>(emptyMap())
    val syncCheckpoints: StateFlow<Map<String, SyncCheckpoint>> = _syncCheckpoints.asStateFlow()

    private val filterCriteriaFlow = combine(
        priorityFilter,
        groupFilter,
        starredOnlyFilter,
        sortOrder,
        searchQuery
    ) { priority, roomId, starredOnly, order, query ->
        FeedFilterCriteria(priority, roomId, starredOnly, order, query)
    }

    val visibleEvents: StateFlow<List<EventCard>> = combine(
        _events,
        filterCriteriaFlow
    ) { allEvents, criteria ->
        val filtered = allEvents.filter { card ->
            val matchesPriority = criteria.priority == null || card.priority == criteria.priority
            val matchesGroup = criteria.roomId == null || card.sourceRoomId == criteria.roomId
            val matchesStarred = !criteria.starredOnly || card.isStarred
            val query = criteria.query
            val matchesQuery = if (query.isBlank()) true else {
                card.title.contains(query, ignoreCase = true) ||
                card.summary.contains(query, ignoreCase = true) ||
                card.translatedSummary.contains(query, ignoreCase = true) ||
                card.originalSender.contains(query, ignoreCase = true) ||
                card.sections.any { section ->
                    section.heading.contains(query, ignoreCase = true) ||
                    section.content.contains(query, ignoreCase = true)
                }
            }
            matchesPriority && matchesGroup && matchesStarred && matchesQuery
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

    private val _translatingCardIds = MutableStateFlow<Set<String>>(emptySet())
    val translatingCardIds: StateFlow<Set<String>> = _translatingCardIds.asStateFlow()

    private val settingsMutex = Mutex()

    private val _showWizard = MutableStateFlow(false)
    val showWizard: StateFlow<Boolean> = _showWizard.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    val scheduler: SyncScheduler = syncScheduler ?: SyncScheduler(
        syncAction = {
            val result = syncService.syncNow(_settings.value)
            if (result is SyncResult.Success && result.newEventsCount > 0) {
                loadEvents().join()
            }
            loadCheckpoints().join()
            result
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
            withContext(Dispatchers.IO) {
                syncCheckpointStore.reset(roomId)
            }
            loadCheckpoints().join()
            _snackbarMessage.emit("Checkpoint reset for group. Next sync will scan full history.")
        }
    }

    fun clearEventsAndCheckpoints(): Job = scope.launch {
        withContext(Dispatchers.IO) {
            eventCardStore.clearAll()
            syncCheckpointStore?.resetAll()
        }
        _events.value = emptyList()
        _syncCheckpoints.value = emptyMap()
        groupFilter.value = null
        starredOnlyFilter.value = false
        _selectedEventId.value = null
        _snackbarMessage.emit("Cleared all extracted events and sync checkpoints.")
    }

    fun resetLocalAppDatabase(): Job = scope.launch {
        scheduler.stop()
        withContext(Dispatchers.IO) {
            eventCardStore.clearAll()
            syncCheckpointStore?.resetAll()
            groupConfigStore?.clearAll()
            settingsStore.clear()
            if (decryptedCacheDir.exists() && decryptedCacheDir.isDirectory && decryptedCacheDir.name == "decrypted") {
                decryptedCacheDir.deleteRecursively()
            }
        }
        _events.value = emptyList()
        _syncCheckpoints.value = emptyMap()
        _groupConfigs.value = emptyMap()
        _availableRooms.value = emptyList()
        _detectedOllamaModels.value = emptyList()
        _ollamaStatusMessage.value = null
        priorityFilter.value = null
        groupFilter.value = null
        starredOnlyFilter.value = false
        sortOrder.value = EventSortOrder.NEWEST_FIRST
        searchQuery.value = ""
        groupSearchQuery.value = ""
        _selectedEventId.value = null
        _settings.value = AppSettings()
        _snackbarMessage.emit("Local app database reset to defaults. WeChat files untouched.")
    }

    fun setShowWizard(show: Boolean) {
        _showWizard.value = show
    }

    fun autoDetectWeChatCandidates(): List<DiscoveredPath> {
        return wechatPathDetector.detectCandidates()
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
            _detectedOllamaModels.value = emptyList()
            _ollamaStatusMessage.value = "Detection failed: ${e.message}"
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
            val err = result.exceptionOrNull()?.message ?: "Unknown error"
            _snackbarMessage.emit("Failed to export to Calendar: $err")
        }
    }

    fun loadEvents(): Job = scope.launch {
        val list = withContext(Dispatchers.IO) {
            eventCardStore.listActive()
        }
        _events.value = list
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
        _events.value = _events.value.map { if (it.id == id) it.copy(isRead = true) else it }
        withContext(Dispatchers.IO) {
            eventCardStore.markRead(id)
            eventCardStore.listActive()
        }.also {
            _events.value = it
        }
    }

    fun markUnread(id: String): Job = scope.launch {
        _events.value = _events.value.map { if (it.id == id) it.copy(isRead = false) else it }
        withContext(Dispatchers.IO) {
            eventCardStore.markUnread(id)
            eventCardStore.listActive()
        }.also {
            _events.value = it
        }
    }

    fun toggleStarred(id: String): Job = scope.launch {
        val current = _events.value.firstOrNull { it.id == id } ?: return@launch
        val newStarred = !current.isStarred
        _events.value = _events.value.map { if (it.id == id) it.copy(isStarred = newStarred) else it }
        withContext(Dispatchers.IO) {
            eventCardStore.setStarred(id, newStarred)
            eventCardStore.listActive()
        }.also {
            _events.value = it
        }
    }

    fun dismiss(id: String): Job = scope.launch {
        if (_selectedEventId.value == id) {
            _selectedEventId.value = null
        }
        _events.value = _events.value.filterNot { it.id == id }
        withContext(Dispatchers.IO) {
            eventCardStore.dismiss(id)
            eventCardStore.listActive()
        }.also {
            _events.value = it
        }
    }

    fun updateSettings(newSettings: AppSettings): Job = scope.launch {
        _settings.value = newSettings
        settingsMutex.withLock {
            val latest = _settings.value
            withContext(Dispatchers.IO) {
                settingsStore.save(latest)
            }
        }
        refreshRoomsInternal()

        if (newSettings.autoSyncEnabled) {
            scheduler.start(
                intervalMinutes = newSettings.syncIntervalMinutes,
                notifyOnNewEvents = newSettings.notifyOnNewEvents
            )
        } else {
            scheduler.stop()
        }
    }

    fun toggleRoomMonitored(roomId: String): Job {
        val currentMonitored = _settings.value.monitoredRoomIds
        val updated = if (currentMonitored.contains(roomId)) {
            currentMonitored - roomId
        } else {
            currentMonitored + roomId
        }
        return updateSettings(_settings.value.copy(monitoredRoomIds = updated))
    }

    fun refreshRooms(): Job = scope.launch {
        refreshRoomsInternal()
    }

    private suspend fun refreshRoomsInternal() {
        val dataDirStr = _settings.value.wechatDataDir
        if (dataDirStr.isBlank()) {
            _availableRooms.value = emptyList()
            return
        }
        val dir = File(dataDirStr)
        if (!dir.exists() || !dir.isDirectory) {
            _availableRooms.value = emptyList()
            return
        }

        _isLoadingRooms.value = true
        try {
            val keysFile = if (_settings.value.keysPath.isNotBlank()) File(_settings.value.keysPath) else null
            val rooms = withContext(Dispatchers.IO) {
                val driver = WeChatDatabaseDriver(baseDir = dir, customKeysFile = keysFile)
                val contactRepo = WeChatContactRepository(driver)
                contactRepo.listAllChatrooms()
            }
            _availableRooms.value = rooms
        } catch (e: Exception) {
            System.err.println("[AppState] Failed to load chatrooms: ${e.message}")
            _availableRooms.value = emptyList()
        } finally {
            _isLoadingRooms.value = false
        }
    }

    /**
     * Loads resolved group members for a given [roomId] from WeChat's contact.db on IO.
     */
    suspend fun loadRoomMembers(roomId: String): List<com.wechatdigest.core.model.GroupMember> {
        val dataDirStr = _settings.value.wechatDataDir
        if (dataDirStr.isBlank()) return emptyList()
        val canonicalDir = File(dataDirStr).canonicalFile
        if (!canonicalDir.exists() || !canonicalDir.isDirectory) return emptyList()

        return try {
            val keysFile = _settings.value.keysPath
                .takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.canonicalFile
                ?.takeIf { it.exists() && it.isFile }
            withContext(Dispatchers.IO) {
                val driver = WeChatDatabaseDriver(
                    baseDir = canonicalDir,
                    customKeysFile = keysFile,
                    cacheDir = decryptedCacheDir
                )
                WeChatContactRepository(driver).listRoomMembers(roomId)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            System.err.println("[AppState] Failed to load members: ${e.message}")
            emptyList()
        }
    }

    fun triggerSync(): Job = scope.launch {
        if (_isSyncing.value) return@launch
        _isSyncing.value = true
        try {
            val result = syncService.syncNow(_settings.value)
            when (result) {
                is SyncResult.Success -> {
                    val statusMsg = if (result.roomErrors.isNotEmpty()) {
                        "Sync completed with ${result.roomErrors.size} room error(s): ${result.roomErrors.first()} (${result.newEventsCount} new events)."
                    } else {
                        "Sync complete: ${result.newEventsCount} new events from ${result.totalScannedMessages} messages."
                    }
                    _snackbarMessage.emit(statusMsg)
                    loadEvents().join()
                    loadCheckpoints().join()
                }
                is SyncResult.Failure -> {
                    _snackbarMessage.emit("Sync failed: ${result.message}")
                }
            }
        } catch (e: Exception) {
            _snackbarMessage.emit("Sync error: ${e.message}")
        } finally {
            _isSyncing.value = false
        }
    }

    fun translateSourceMessages(cardId: String): Job = scope.launch {
        if (cardId in _translatingCardIds.value) return@launch
        val card = _events.value.firstOrNull { it.id == cardId } ?: return@launch
        if (card.sourceMessages.isBlank() || card.translatedSourceMessages.isNotBlank()) return@launch

        _translatingCardIds.value = _translatingCardIds.value + cardId
        try {
            val currentSettings = _settings.value
            val result = withContext(Dispatchers.IO) {
                llmClientFactory(currentSettings.llm).use { client ->
                    MessageTranslator(client).translateMessages(
                        sourceMessages = card.sourceMessages,
                        targetLanguage = currentSettings.targetLanguage
                    )
                }
            }
            result.onSuccess { translated ->
                withContext(Dispatchers.IO) {
                    eventCardStore.updateTranslatedSourceMessages(card.id, translated)
                }
                _events.value = _events.value.map { existing ->
                    if (existing.id == cardId) {
                        existing.copy(translatedSourceMessages = translated)
                    } else {
                        existing
                    }
                }
            }.onFailure { err ->
                _snackbarMessage.emit("Message translation failed: ${err.message ?: "Unknown error"}")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _snackbarMessage.emit("Message translation failed: ${e.message ?: "Unknown error"}")
        } finally {
            _translatingCardIds.value = _translatingCardIds.value - cardId
        }
    }
}

