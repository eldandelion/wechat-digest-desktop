package com.wechatdigest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wechatdigest.app.EventSortOrder
import com.wechatdigest.core.llm.local.LocalRuntimeState
import com.wechatdigest.core.llm.local.LocalServerState
import com.wechatdigest.core.model.DeadlineTriage
import com.wechatdigest.core.model.DeadlineUrgency
import com.wechatdigest.core.model.DetailChecklistKeys
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCardStatus
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventDetailItemParser
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.ui.theme.LocalIsDarkTheme
import com.wechatdigest.ui.theme.PriorityColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val listItemTimestampFormatter = DateTimeFormatter.ofPattern("MMM dd, HH:mm").withZone(ZoneId.systemDefault())
private val EmptySyncStatusFlow: StateFlow<String?> = MutableStateFlow(null)
private val EmptyRuntimeStateFlow: StateFlow<LocalRuntimeState> = MutableStateFlow(LocalRuntimeState.NotInstalled)
private val EmptyServerStateFlow: StateFlow<LocalServerState> = MutableStateFlow(LocalServerState.Stopped)

/** Resolves a concise live status pill string combining sync progress with local AI runtime/model lifecycle states. */
internal fun resolveSyncStatusDisplay(
    isSyncing: Boolean,
    syncStatusText: String?,
    isLocalProvider: Boolean,
    runtimeState: LocalRuntimeState,
    serverState: LocalServerState
): String? {
    if (isLocalProvider) {
        when (runtimeState) {
            is LocalRuntimeState.Downloading -> {
                val pct = if (runtimeState.totalBytes > 0L) {
                    " (${((runtimeState.bytesDownloaded * 100L) / runtimeState.totalBytes).coerceIn(0L, 100L)}%)"
                } else {
                    ""
                }
                return "Downloading AI runtime$pct..."
            }
            is LocalRuntimeState.Verifying -> return "Verifying AI runtime..."
            is LocalRuntimeState.Extracting -> return "Extracting local AI runtime..."
            else -> Unit
        }
        if (serverState is LocalServerState.Starting) {
            return "Starting local model (${serverState.modelName})..."
        }
    }
    if (!isSyncing) return null
    return syncStatusText?.takeIf { it.isNotBlank() } ?: "Syncing..."
}

/**
 * Left-hand master list pane displaying search, filter chips, segmented event rows, and sync FAB.
 */
@Composable
internal fun EventListPane(
    modifier: Modifier,
    allEvents: List<EventCard>,
    visibleEvents: List<EventCard>,
    roomNamesMap: Map<String, String>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchFocusRequester: FocusRequester? = null,
    starredOnly: Boolean,
    onToggleStarredOnly: () -> Unit,
    unreadOnly: Boolean,
    onToggleUnreadOnly: () -> Unit,
    hideCompleted: Boolean = false,
    onToggleHideCompleted: () -> Unit = {},
    selectedFilter: EventPriority?,
    onSelectPriority: (EventPriority?) -> Unit,
    selectedCategory: EventCategory? = null,
    onSelectCategory: (EventCategory?) -> Unit = {},
    selectedGroupId: String?,
    selectedGroupName: String?,
    groupOptions: List<GroupFilterOption>,
    onSelectGroup: (String?) -> Unit,
    sortOrder: EventSortOrder,
    onSelectSortOrder: (EventSortOrder) -> Unit,
    selectedEventId: String?,
    onSelectEvent: (String) -> Unit,
    onToggleStarEvent: (String) -> Unit,
    onToggleCompletedEvent: (String) -> Unit = {},
    isSyncing: Boolean,
    isLocalProvider: Boolean = false,
    syncStatusTextFlow: StateFlow<String?> = EmptySyncStatusFlow,
    localRuntimeStateFlow: StateFlow<LocalRuntimeState> = EmptyRuntimeStateFlow,
    localServerStateFlow: StateFlow<LocalServerState> = EmptyServerStateFlow,
    contentEndPadding: Dp = 16.dp,
    onTriggerSync: () -> Unit,
    onCancelSync: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val isFabExpanded = rememberFabExpandedState(listState)
    val minuteTick = rememberMinuteTick()

    LaunchedEffect(selectedEventId, visibleEvents) {
        val targetId = selectedEventId ?: return@LaunchedEffect
        val idx = visibleEvents.indexOfFirst { it.id == targetId }
        if (idx >= 0) {
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == idx }
            if (!isVisible) {
                listState.animateScrollToItem(idx)
            }
        }
    }

    Box(
        modifier = modifier.onKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown &&
                !keyEvent.isShiftPressed &&
                !keyEvent.isAltPressed &&
                !keyEvent.isCtrlPressed &&
                !keyEvent.isMetaPressed &&
                (keyEvent.key == Key.DirectionDown || keyEvent.key == Key.DirectionUp)
            ) {
                val step = if (keyEvent.key == Key.DirectionDown) 1 else -1
                val nextId = navigateEventSelection(visibleEvents.map { it.id }, selectedEventId, step)
                if (nextId != null) {
                    if (nextId != selectedEventId) onSelectEvent(nextId)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, top = 8.dp, end = contentEndPadding)) {
            PillSearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                placeholder = "Search events",
                modifier = Modifier.fillMaxWidth(),
                focusRequester = searchFocusRequester
            )
            // FilterChip has 8.dp built-in vertical touch padding above and below its pill, so 8.dp spacers give 16.dp visual gaps on both sides.
            Spacer(Modifier.height(8.dp))
            EventListFilterBar(
                starredOnly = starredOnly,
                onToggleStarredOnly = onToggleStarredOnly,
                unreadOnly = unreadOnly,
                onToggleUnreadOnly = onToggleUnreadOnly,
                hideCompleted = hideCompleted,
                onToggleHideCompleted = onToggleHideCompleted,
                selectedFilter = selectedFilter,
                onSelectPriority = onSelectPriority,
                selectedCategory = selectedCategory,
                onSelectCategory = onSelectCategory,
                selectedGroupId = selectedGroupId,
                selectedGroupName = selectedGroupName,
                groupOptions = groupOptions,
                onSelectGroup = onSelectGroup,
                sortOrder = sortOrder,
                onSelectSortOrder = onSelectSortOrder
            )
            Spacer(Modifier.height(8.dp))
            EventListBody(
                allEvents = allEvents,
                visibleEvents = visibleEvents,
                roomNamesMap = roomNamesMap,
                minuteTick = minuteTick,
                listState = listState,
                selectedEventId = selectedEventId,
                onSelectEvent = onSelectEvent,
                onToggleStarEvent = onToggleStarEvent,
                onToggleCompletedEvent = onToggleCompletedEvent
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                SyncStatusPill(
                    isSyncing = isSyncing,
                    isLocalProvider = isLocalProvider,
                    syncStatusTextFlow = syncStatusTextFlow,
                    localRuntimeStateFlow = localRuntimeStateFlow,
                    localServerStateFlow = localServerStateFlow
                )
            }
            SyncFloatingActionButton(
                isSyncing = isSyncing,
                expanded = isFabExpanded,
                onTriggerSync = onTriggerSync,
                onCancelSync = onCancelSync
            )
        }
    }
}

@Composable
private fun rememberFabExpandedState(listState: LazyListState): Boolean {
    var isFabExpanded by remember { mutableStateOf(true) }
    var previousIndex by remember { mutableStateOf(0) }
    var previousOffset by remember { mutableStateOf(0) }
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val currentIndex = listState.firstVisibleItemIndex
        val currentOffset = listState.firstVisibleItemScrollOffset
        isFabExpanded = shouldExpandFab(currentIndex, currentOffset, previousIndex, previousOffset, isFabExpanded)
        previousIndex = currentIndex
        previousOffset = currentOffset
    }
    return isFabExpanded
}

@Composable
private fun EventListFilterBar(
    starredOnly: Boolean,
    onToggleStarredOnly: () -> Unit,
    unreadOnly: Boolean,
    onToggleUnreadOnly: () -> Unit,
    hideCompleted: Boolean,
    onToggleHideCompleted: () -> Unit,
    selectedFilter: EventPriority?,
    onSelectPriority: (EventPriority?) -> Unit,
    selectedCategory: EventCategory?,
    onSelectCategory: (EventCategory?) -> Unit,
    selectedGroupId: String?,
    selectedGroupName: String?,
    groupOptions: List<GroupFilterOption>,
    onSelectGroup: (String?) -> Unit,
    sortOrder: EventSortOrder,
    onSelectSortOrder: (EventSortOrder) -> Unit
) {
    val priorityItems = remember {
        listOf<Pair<String, EventPriority?>>("All priorities" to null) + EventPriority.entries.map { formatPriorityLabel(it) to it }
    }
    val categoryItems = remember {
        listOf<Pair<String, EventCategory?>>("All categories" to null) + EventCategory.entries.map { formatCategoryLabel(it) to it }
    }
    val groupItems = remember(groupOptions) {
        listOf<Pair<String, String?>>("All groups" to null) + groupOptions.map { it.displayName to it.roomId }
    }
    val sortItems = remember { EventSortOrder.entries.map { it.label to it } }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToggleFilterChip(
            selected = starredOnly,
            label = "Starred",
            icon = if (starredOnly) Icons.Filled.Star else Icons.Default.StarBorder,
            onClick = onToggleStarredOnly
        )
        ToggleFilterChip(
            selected = unreadOnly,
            label = "Unread",
            icon = if (unreadOnly) Icons.Filled.MarkEmailUnread else Icons.Outlined.MarkEmailUnread,
            onClick = onToggleUnreadOnly
        )
        ToggleFilterChip(
            selected = hideCompleted,
            label = "Hide done",
            icon = if (hideCompleted) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
            onClick = onToggleHideCompleted
        )
        DropdownFilterChip(
            selected = selectedFilter != null,
            label = if (selectedFilter == null) "Priority" else "Priority: ${formatPriorityLabel(selectedFilter)}",
            leadingIcon = {
                Icon(
                    imageVector = if (selectedFilter != null) Icons.Filled.Flag else Icons.Outlined.Flag,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            items = priorityItems,
            isItemSelected = { it == selectedFilter },
            onSelect = onSelectPriority
        )
        DropdownFilterChip(
            selected = selectedCategory != null,
            label = if (selectedCategory == null) "Category" else formatCategoryLabel(selectedCategory),
            leadingIcon = {
                Icon(
                    imageVector = if (selectedCategory != null) Icons.Filled.Category else Icons.Outlined.Category,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            items = categoryItems,
            isItemSelected = { it == selectedCategory },
            onSelect = onSelectCategory
        )
        DropdownFilterChip(
            selected = selectedGroupId != null,
            label = if (selectedGroupId == null) "Group" else "Group: ${selectedGroupName.orEmpty()}",
            leadingIcon = {
                Icon(
                    imageVector = if (selectedGroupId != null) Icons.Filled.Group else Icons.Outlined.Group,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            items = groupItems,
            isItemSelected = { it == selectedGroupId },
            onSelect = onSelectGroup
        )
        DropdownFilterChip(
            selected = sortOrder != EventSortOrder.NEWEST_FIRST,
            label = sortOrder.label,
            leadingIcon = {
                Icon(
                    imageVector = if (sortOrder != EventSortOrder.NEWEST_FIRST) Icons.Filled.SwapVert else Icons.Outlined.SwapVert,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            items = sortItems,
            isItemSelected = { it == sortOrder },
            onSelect = onSelectSortOrder
        )
    }
}

@Composable
private fun ToggleFilterChip(selected: Boolean, label: String, icon: ImageVector, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        label = {
            Text(text = label, maxLines = 1, lineHeight = MaterialTheme.typography.labelLarge.fontSize)
        }
    )
}

@Composable
private fun EventListBody(
    allEvents: List<EventCard>,
    visibleEvents: List<EventCard>,
    roomNamesMap: Map<String, String>,
    minuteTick: Long,
    listState: LazyListState,
    selectedEventId: String?,
    onSelectEvent: (String) -> Unit,
    onToggleStarEvent: (String) -> Unit,
    onToggleCompletedEvent: (String) -> Unit
) {
    if (visibleEvents.isEmpty()) {
        EventFeedEmptyState(hasAnyEvents = allEvents.isNotEmpty())
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(visibleEvents, key = { _, card -> card.id }) { index, card ->
            val roomName = resolveRoomDisplayName(roomNamesMap, card)
            val onClick = remember(card.id, onSelectEvent) { { onSelectEvent(card.id) } }
            val onToggleStar = remember(card.id, onToggleStarEvent) { { onToggleStarEvent(card.id) } }
            val onToggleCompleted = remember(card.id, onToggleCompletedEvent) { { onToggleCompletedEvent(card.id) } }
            EventListItem(
                card = card,
                roomName = roomName,
                minuteTick = minuteTick,
                shape = segmentedItemShape(index = index, totalCount = visibleEvents.size),
                isSelected = card.id == selectedEventId,
                onClick = onClick,
                onToggleStar = onToggleStar,
                onToggleCompleted = onToggleCompleted
            )
        }
    }
}

@Composable
private fun EventFeedEmptyState(hasAnyEvents: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (!hasAnyEvents) "No events extracted yet" else "No matching events found",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (!hasAnyEvents) {
                    "Tap 'Sync now' or configure your monitored groups in the Groups tab."
                } else {
                    "Try clearing your search query or selecting a different filter."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun SyncStatusPill(
    isSyncing: Boolean,
    isLocalProvider: Boolean,
    syncStatusTextFlow: StateFlow<String?>,
    localRuntimeStateFlow: StateFlow<LocalRuntimeState>,
    localServerStateFlow: StateFlow<LocalServerState>,
    modifier: Modifier = Modifier
) {
    val syncStatusText by syncStatusTextFlow.collectAsState()
    val runtimeState by localRuntimeStateFlow.collectAsState()
    val serverState by localServerStateFlow.collectAsState()
    val status = resolveSyncStatusDisplay(
        isSyncing = isSyncing,
        syncStatusText = syncStatusText,
        isLocalProvider = isLocalProvider,
        runtimeState = runtimeState,
        serverState = serverState
    ) ?: return

    Surface(
        modifier = modifier.widthIn(max = 360.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Text(
            text = status,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SyncFloatingActionButton(
    isSyncing: Boolean,
    expanded: Boolean,
    onTriggerSync: () -> Unit,
    onCancelSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExtendedFloatingActionButton(
        text = { Text(text = if (isSyncing) "Stop" else "Sync now", lineHeight = MaterialTheme.typography.labelLarge.fontSize) },
        icon = { Icon(if (isSyncing) Icons.Default.Stop else Icons.Default.Refresh, contentDescription = null) },
        onClick = if (isSyncing) onCancelSync else onTriggerSync,
        expanded = expanded,
        modifier = modifier,
        containerColor = if (isSyncing) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (isSyncing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    )
}

/**
 * Compact 3-line segmented list row inside the left master pane using `surfaceContainerLowest`.
 */
@Composable
private fun EventListItem(
    card: EventCard,
    roomName: String,
    minuteTick: Long,
    shape: RoundedCornerShape,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleCompleted: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val priorityColor = PriorityColors.getColor(card.priority, isDark)
    val priorityContainer = PriorityColors.getContainerColor(card.priority, isDark)
    val senderName = card.originalSender.ifBlank { "Unknown" }
    val avatarInitial = remember(roomName, senderName) {
        roomName.trim().take(1).ifBlank { senderName.trim().take(1) }.uppercase().ifBlank { "E" }
    }
    val containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest

    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
        shape = shape,
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp)
                .alpha(if (card.isCompleted) 0.68f else 1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EventListAvatar(initial = avatarInitial, containerColor = priorityContainer, textColor = priorityColor)
            Spacer(Modifier.width(12.dp))
            EventListItemContent(
                card = card,
                roomName = roomName,
                senderName = senderName,
                minuteTick = minuteTick,
                priorityColor = priorityColor,
                priorityContainer = priorityContainer,
                onToggleStar = onToggleStar,
                onToggleCompleted = onToggleCompleted,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EventListAvatar(initial: String, containerColor: Color, textColor: Color) {
    Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = containerColor) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initial,
                style = MaterialTheme.typography.headlineSmall.copy(lineHeight = MaterialTheme.typography.headlineSmall.fontSize),
                fontWeight = FontWeight.Light,
                color = textColor
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventListItemContent(
    card: EventCard,
    roomName: String,
    senderName: String,
    minuteTick: Long,
    priorityColor: Color,
    priorityContainer: Color,
    onToggleStar: () -> Unit,
    onToggleCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryTextColor = if (!card.isRead) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val summaryTextColor = if (!card.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f)
    val displaySummary = remember(card.translatedSummary, card.summary) {
        card.translatedSummary.ifBlank { card.summary }.replace('\n', ' ').trim()
    }
    val formattedTime = remember(card.timestampSeconds) {
        listItemTimestampFormatter.format(Instant.ofEpochSecond(card.timestampSeconds))
    }
    val deadlineInfo = remember(card.eventDate, minuteTick) { DeadlineTriage.evaluate(card.eventDate) }
    val checklistCounts = remember(card.sections, card.checkedItems) {
        DetailChecklistKeys.countProgress(card.sections, card.checkedItems)
    }

    Column(modifier = modifier) {
        EventListItemHeader(card = card, roomName = roomName, senderName = senderName, formattedTime = formattedTime, primaryTextColor = primaryTextColor)
        Spacer(Modifier.height(3.dp))
        Text(
            text = card.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                lineHeight = MaterialTheme.typography.bodyMedium.fontSize,
                textDecoration = if (card.isCompleted) TextDecoration.LineThrough else TextDecoration.None
            ),
            fontWeight = if (!card.isRead) FontWeight.Bold else FontWeight.Normal,
            color = primaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val activeDeadline = if (card.status != EventCardStatus.CANCELLED) deadlineInfo else null
        val hasTriageBadges = card.priority == EventPriority.CRITICAL ||
            card.priority == EventPriority.IMPORTANT ||
            activeDeadline != null ||
            checklistCounts.second > 0
        if (hasTriageBadges) {
            Spacer(Modifier.height(3.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (card.priority == EventPriority.CRITICAL || card.priority == EventPriority.IMPORTANT) {
                    ListBadgeChip(
                        text = formatPriorityLabel(card.priority),
                        containerColor = priorityContainer,
                        textColor = priorityColor
                    )
                }
                if (activeDeadline != null) {
                    val (bg, fg) = when {
                        card.isCompleted -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
                        activeDeadline.urgency == DeadlineUrgency.OVERDUE -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                        activeDeadline.urgency == DeadlineUrgency.DUE_TODAY -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f) to MaterialTheme.colorScheme.onErrorContainer
                        activeDeadline.urgency == DeadlineUrgency.UPCOMING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    ListBadgeChip(text = activeDeadline.label, containerColor = bg, textColor = fg)
                }
                if (checklistCounts.second > 0) {
                    ListBadgeChip(
                        text = "${checklistCounts.first}/${checklistCounts.second} done",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        EventListItemFooter(
            displaySummary = displaySummary,
            summaryTextColor = summaryTextColor,
            isCompleted = card.isCompleted,
            isStarred = card.isStarred,
            onToggleCompleted = onToggleCompleted,
            onToggleStar = onToggleStar
        )
    }
}

@Composable
private fun ListBadgeChip(text: String, containerColor: Color, textColor: Color) {
    Surface(color = containerColor, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(lineHeight = MaterialTheme.typography.labelSmall.fontSize),
            color = textColor,
            maxLines = 1
        )
    }
}

@Composable
private fun EventListItemHeader(
    card: EventCard,
    roomName: String,
    senderName: String,
    formattedTime: String,
    primaryTextColor: Color
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$roomName • $senderName",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall.copy(lineHeight = MaterialTheme.typography.titleSmall.fontSize),
            fontWeight = if (!card.isRead) FontWeight.Bold else FontWeight.Normal,
            color = primaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (card.status != EventCardStatus.ACTIVE) {
            Spacer(Modifier.width(6.dp))
            val isCancelled = card.status == EventCardStatus.CANCELLED
            Surface(
                color = if (isCancelled) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = formatStatusLabel(card.status),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = MaterialTheme.typography.labelSmall.fontSize),
                    color = if (isCancelled) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (card.attachments.isNotEmpty()) {
            Icon(Icons.Default.AttachFile, contentDescription = "Has attachment", modifier = Modifier.size(13.dp), tint = primaryTextColor)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = formattedTime,
            style = MaterialTheme.typography.labelSmall.copy(lineHeight = MaterialTheme.typography.labelSmall.fontSize),
            fontWeight = if (!card.isRead) FontWeight.Bold else FontWeight.Normal,
            color = primaryTextColor
        )
        if (!card.isRead) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = "Unread" }
            )
        }
    }
}

@Composable
private fun EventListItemFooter(
    displaySummary: String,
    summaryTextColor: Color,
    isCompleted: Boolean,
    isStarred: Boolean,
    onToggleCompleted: () -> Unit,
    onToggleStar: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = displaySummary,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = MaterialTheme.typography.bodySmall.fontSize),
            color = summaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onToggleCompleted, modifier = Modifier.size(26.dp)) {
            Icon(
                imageVector = if (isCompleted) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                contentDescription = if (isCompleted) "Mark as not done" else "Mark as done",
                modifier = Modifier.size(17.dp),
                tint = if (isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onToggleStar, modifier = Modifier.size(26.dp)) {
            Icon(
                imageVector = if (isStarred) Icons.Filled.Star else Icons.Default.StarBorder,
                contentDescription = if (isStarred) "Unstar event" else "Star event",
                modifier = Modifier.size(18.dp),
                tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun <T> DropdownFilterChip(
    selected: Boolean,
    label: String,
    items: List<Pair<String, T>>,
    isItemSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = selected,
            onClick = { expanded = true },
            leadingIcon = leadingIcon,
            label = {
                Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = MaterialTheme.typography.labelLarge.fontSize, modifier = Modifier.widthIn(max = 220.dp))
            },
            trailingIcon = { Icon(imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (itemLabel, value) ->
                DropdownMenuItem(
                    text = { Text(text = itemLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp)) },
                    trailingIcon = if (isItemSelected(value)) { { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) } } else null,
                    onClick = { onSelect(value); expanded = false }
                )
            }
        }
    }
}
