package com.wechatdigest.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.wechatdigest.core.llm.KtorLlmClient
import com.wechatdigest.core.llm.LlmClient
import com.wechatdigest.core.llm.LlmConfig
import com.wechatdigest.core.llm.LlmProviderType
import com.wechatdigest.core.llm.local.LlamaRuntimeManager
import com.wechatdigest.core.llm.local.LlamaServerManager
import com.wechatdigest.core.llm.local.LocalModelDownloadManager
import com.wechatdigest.core.llm.local.LocalModelStore
import com.wechatdigest.core.llm.local.ManagedLocalLlmClient
import com.wechatdigest.storage.AppDatabase
import com.wechatdigest.storage.EventCardStore
import com.wechatdigest.storage.GroupConfigStore
import com.wechatdigest.storage.SettingsStore
import com.wechatdigest.storage.SyncCheckpointStore
import com.wechatdigest.ui.EventFeedScreen
import com.wechatdigest.ui.GroupsScreen
import com.wechatdigest.ui.OnboardingWizardDialog
import com.wechatdigest.ui.SettingsScreen
import com.wechatdigest.ui.TrayIconHelper
import com.wechatdigest.ui.theme.WeChatDigestTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest

fun main() {
    System.setProperty("java.net.useSystemProxies", "true")
    application {
    val windowState = remember {
        WindowState(
            size = DpSize(1040.dp, 720.dp)
        )
    }

    val appDatabase = remember { AppDatabase() }
    val settingsStore = remember { SettingsStore(appDatabase) }
    val eventCardStore = remember { EventCardStore(appDatabase) }
    val groupConfigStore = remember { GroupConfigStore(appDatabase) }
    val syncCheckpointStore = remember { SyncCheckpointStore(appDatabase) }
    val localModelStore = remember { LocalModelStore() }
    val localModelDownloadManager = remember { LocalModelDownloadManager(localModelStore) }
    val llamaRuntimeManager = remember { LlamaRuntimeManager() }
    val llamaServerScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val llamaServerManager = remember { LlamaServerManager(llamaRuntimeManager, scope = llamaServerScope) }
    val llmClientFactory: (LlmConfig) -> LlmClient = remember {
        { config ->
            when (config.providerType) {
                LlmProviderType.BUILT_IN_LOCAL -> ManagedLocalLlmClient(config, localModelStore, llamaServerManager)
                else -> KtorLlmClient(config)
            }
        }
    }
    val syncService = remember {
        SyncService(
            eventCardStore = eventCardStore,
            groupConfigStore = groupConfigStore,
            syncCheckpointStore = syncCheckpointStore,
            llmClientFactory = llmClientFactory
        )
    }
    val appState = remember {
        AppState(
            settingsStore = settingsStore,
            eventCardStore = eventCardStore,
            syncService = syncService,
            groupConfigStore = groupConfigStore,
            syncCheckpointStore = syncCheckpointStore,
            llmClientFactory = llmClientFactory,
            localModelStore = localModelStore,
            localModelDownloadManager = localModelDownloadManager,
            llamaRuntimeManager = llamaRuntimeManager,
            llamaServerManager = llamaServerManager
        )
    }

    DisposableEffect(Unit) {
        val shutdownHook = Thread({ llamaServerManager.shutdownBlocking() }, "llama-server-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        onDispose {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            llamaServerScope.cancel()
            appState.close()
        }
    }

    var isWindowVisible by remember { mutableStateOf(true) }
    val trayIcon = remember { TrayIconHelper.createTrayIconPainter() }

    val currentSettings by appState.settings.collectAsState()
    val events by appState.events.collectAsState()
    val unreadCount = remember(events) { events.count { !it.isRead } }

    Tray(
        icon = trayIcon,
        tooltip = "WeChat Digest",
        onAction = { isWindowVisible = true },
        menu = {
            Item(
                text = if (unreadCount > 0) "Open WeChat Digest ($unreadCount unread)" else "Open WeChat Digest",
                onClick = { isWindowVisible = true }
            )
            Item(
                text = "Sync Now",
                onClick = { appState.triggerSync() }
            )
            Separator()
            Item(
                text = "Quit",
                onClick = ::exitApplication
            )
        }
    )

    if (isWindowVisible) {
        Window(
            onCloseRequest = {
                if (currentSettings.autoSyncEnabled) {
                    isWindowVisible = false
                } else {
                    exitApplication()
                }
            },
            title = "WeChat Digest",
            state = windowState
        ) {
            LaunchedEffect(window) {
                window.minimumSize = java.awt.Dimension(440, 500)
            }
            WeChatDigestTheme(themeMode = currentSettings.themeMode) {
                MainAppShell(appState)
            }
        }
    }
    }
}

@Composable
fun MainAppShell(appState: AppState) {
    val currentScreen by appState.currentScreen.collectAsState()
    val events by appState.events.collectAsState()
    val unreadCount = remember(events) { events.count { !it.isRead } }
    val showWizard by appState.showWizard.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        appState.snackbarMessage.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    if (showWizard) {
        OnboardingWizardDialog(
            appState = appState,
            onDismiss = { appState.setShowWizard(false) }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useBottomNav = maxWidth < 680.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (useBottomNav) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            selected = currentScreen == Screen.EVENTS,
                            onClick = { appState.selectScreen(Screen.EVENTS) },
                            icon = { EventsNavIcon(unreadCount) },
                            label = { Text("Events") }
                        )
                        NavigationBarItem(
                            selected = currentScreen == Screen.GROUPS,
                            onClick = { appState.selectScreen(Screen.GROUPS) },
                            icon = { Icon(Icons.Default.Group, contentDescription = "Groups") },
                            label = { Text("Groups") }
                        )
                        NavigationBarItem(
                            selected = currentScreen == Screen.SETTINGS,
                            onClick = { appState.selectScreen(Screen.SETTINGS) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") }
                        )
                    }
                }
            }
        ) { paddingValues ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                color = MaterialTheme.colorScheme.background
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (!useBottomNav) {
                        NavigationRail(
                            modifier = Modifier.fillMaxHeight(),
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Spacer(Modifier.weight(1f))

                            NavigationRailItem(
                                selected = currentScreen == Screen.EVENTS,
                                onClick = { appState.selectScreen(Screen.EVENTS) },
                                icon = { EventsNavIcon(unreadCount) },
                                label = { Text("Events") }
                            )

                            Spacer(Modifier.height(8.dp))

                            NavigationRailItem(
                                selected = currentScreen == Screen.GROUPS,
                                onClick = { appState.selectScreen(Screen.GROUPS) },
                                icon = {
                                    Icon(Icons.Default.Group, contentDescription = "Groups")
                                },
                                label = { Text("Groups") }
                            )

                            Spacer(Modifier.height(8.dp))

                            NavigationRailItem(
                                selected = currentScreen == Screen.SETTINGS,
                                onClick = { appState.selectScreen(Screen.SETTINGS) },
                                icon = {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                },
                                label = { Text("Settings") }
                            )

                            Spacer(Modifier.weight(1f))
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        when (currentScreen) {
                            Screen.EVENTS -> EventFeedScreen(appState)
                            Screen.GROUPS -> GroupsScreen(appState)
                            Screen.SETTINGS -> SettingsScreen(appState)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsNavIcon(unreadCount: Int) {
    if (unreadCount > 0) {
        BadgedBox(badge = { Badge { Text("$unreadCount") } }) {
            Icon(Icons.AutoMirrored.Filled.EventNote, contentDescription = "Events")
        }
    } else {
        Icon(Icons.AutoMirrored.Filled.EventNote, contentDescription = "Events")
    }
}

