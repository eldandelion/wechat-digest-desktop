package com.wechatdigest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wechatdigest.app.AppState
import com.wechatdigest.app.EventSortOrder
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.GroupChat
import com.wechatdigest.ui.theme.PriorityColors
import java.awt.Cursor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class GroupFilterOption(
    val roomId: String,
    val displayName: String
)

internal data class SegmentedCornerRadii(
    val topStartDp: Int,
    val topEndDp: Int,
    val bottomStartDp: Int,
    val bottomEndDp: Int
)

internal fun formatPriorityLabel(priority: EventPriority): String =
    priority.name.lowercase().replaceFirstChar { it.titlecase() }

internal fun formatCategoryLabel(category: EventCategory): String =
    category.name.lowercase().replaceFirstChar { it.titlecase() }

/**
 * Computes segmented list corner radii where the outer list corners use [outerRadiusDp]
 * and adjacent inner item edges use [innerRadiusDp] (matching Material 3 segmented list cards).
 */
internal fun computeSegmentedCornerRadii(
    index: Int,
    totalCount: Int,
    outerRadiusDp: Int = 16,
    innerRadiusDp: Int = 4
): SegmentedCornerRadii {
    if (totalCount <= 1) {
        return SegmentedCornerRadii(outerRadiusDp, outerRadiusDp, outerRadiusDp, outerRadiusDp)
    }
    return when (index) {
        0 -> SegmentedCornerRadii(
            topStartDp = outerRadiusDp,
            topEndDp = outerRadiusDp,
            bottomStartDp = innerRadiusDp,
            bottomEndDp = innerRadiusDp
        )
        totalCount - 1 -> SegmentedCornerRadii(
            topStartDp = innerRadiusDp,
            topEndDp = innerRadiusDp,
            bottomStartDp = outerRadiusDp,
            bottomEndDp = outerRadiusDp
        )
        else -> SegmentedCornerRadii(
            topStartDp = innerRadiusDp,
            topEndDp = innerRadiusDp,
            bottomStartDp = innerRadiusDp,
            bottomEndDp = innerRadiusDp
        )
    }
}

private fun segmentedItemShape(
    index: Int,
    totalCount: Int
): RoundedCornerShape {
    val radii = computeSegmentedCornerRadii(index, totalCount)
    return RoundedCornerShape(
        topStart = radii.topStartDp.dp,
        topEnd = radii.topEndDp.dp,
        bottomStart = radii.bottomStartDp.dp,
        bottomEnd = radii.bottomEndDp.dp
    )
}

internal fun buildGroupFilterOptions(
    events: List<EventCard>,
    availableRooms: List<GroupChat>,
    monitoredRoomIds: Set<String>
): List<GroupFilterOption> {
    val roomNamesMap = availableRooms.associate { it.roomId to it.displayName }
    val optionsById = linkedMapOf<String, String>()

    events.forEach { card ->
        if (card.sourceRoomId.isNotBlank() && card.sourceRoomId !in optionsById) {
            val resolved = roomNamesMap[card.sourceRoomId]?.ifBlank { null }
                ?: card.sourceRoomName.ifBlank { null }
                ?: card.sourceRoomId
            optionsById[card.sourceRoomId] = resolved
        }
    }
    monitoredRoomIds.forEach { roomId ->
        if (roomId.isNotBlank() && roomId !in optionsById) {
            val resolved = roomNamesMap[roomId]?.ifBlank { null } ?: roomId
            optionsById[roomId] = resolved
        }
    }

    return optionsById.entries
        .map { (id, name) -> GroupFilterOption(roomId = id, displayName = name) }
        .sortedBy { it.displayName.lowercase() }
}

/**
 * Computes whether the Sync [ExtendedFloatingActionButton] should stay expanded with text
 * or collapse to an icon-only FAB based on list scroll movement.
 */
internal fun shouldExpandFab(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    previousIndex: Int,
    previousOffset: Int,
    currentlyExpanded: Boolean,
    offsetDeadbandPx: Int = 6
): Boolean {
    if (firstVisibleIndex == 0 && firstVisibleOffset == 0) return true
    if (firstVisibleIndex != previousIndex) {
        return firstVisibleIndex < previousIndex
    }
    val offsetDelta = firstVisibleOffset - previousOffset
    return when {
        offsetDelta > offsetDeadbandPx -> false
        offsetDelta < -offsetDeadbandPx -> true
        else -> currentlyExpanded
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventFeedScreen(appState: AppState) {
    val visibleEvents by appState.visibleEvents.collectAsState()
    val allEvents by appState.events.collectAsState()
    val availableRooms by appState.availableRooms.collectAsState()
    val settings by appState.settings.collectAsState()
    val selectedFilter by appState.priorityFilter.collectAsState()
    val selectedGroupId by appState.groupFilter.collectAsState()
    val starredOnly by appState.starredOnlyFilter.collectAsState()
    val sortOrder by appState.sortOrder.collectAsState()
    val searchQuery by appState.searchQuery.collectAsState()
    val selectedEventId by appState.selectedEventId.collectAsState()
    val isSyncing by appState.isSyncing.collectAsState()
    val translatingCardIds by appState.translatingCardIds.collectAsState()
    var cardPendingDelete by remember { mutableStateOf<EventCard?>(null) }
    var splitFraction by remember { mutableStateOf(0.44f) }

    val roomNamesMap = remember(availableRooms) {
        availableRooms.associate { it.roomId to it.displayName }
    }
    val groupOptions = remember(allEvents, availableRooms, settings.monitoredRoomIds) {
        buildGroupFilterOptions(allEvents, availableRooms, settings.monitoredRoomIds)
    }
    val selectedGroupName = remember(selectedGroupId, groupOptions) {
        groupOptions.firstOrNull { it.roomId == selectedGroupId }?.displayName ?: selectedGroupId
    }
    val selectedIndex = remember(visibleEvents, selectedEventId) {
        visibleEvents.indexOfFirst { it.id == selectedEventId }
    }
    val selectedCard = if (selectedIndex >= 0) visibleEvents[selectedIndex] else null

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val isSplitView = totalWidth >= 680.dp
        val density = LocalDensity.current
        val totalWidthPx = remember(totalWidth, density) {
            with(density) { totalWidth.toPx().coerceAtLeast(1f) }
        }

        if (isSplitView) {
            Row(modifier = Modifier.fillMaxSize()) {
                EventListPane(
                    modifier = Modifier
                        .width(totalWidth * splitFraction)
                        .fillMaxHeight(),
                    allEvents = allEvents,
                    visibleEvents = visibleEvents,
                    roomNamesMap = roomNamesMap,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { appState.searchQuery.value = it },
                    starredOnly = starredOnly,
                    onToggleStarredOnly = { appState.starredOnlyFilter.value = !starredOnly },
                    selectedFilter = selectedFilter,
                    onSelectPriority = { appState.priorityFilter.value = it },
                    selectedGroupId = selectedGroupId,
                    selectedGroupName = selectedGroupName,
                    groupOptions = groupOptions,
                    onSelectGroup = { appState.groupFilter.value = it },
                    sortOrder = sortOrder,
                    onSelectSortOrder = { appState.sortOrder.value = it },
                    selectedEventId = selectedCard?.id,
                    onSelectEvent = { appState.selectEvent(it) },
                    onToggleStarEvent = { appState.toggleStarred(it) },
                    isSyncing = isSyncing,
                    onTriggerSync = { if (!isSyncing) appState.triggerSync() }
                )

                SplitResizeHandle(
                    onDragDeltaPx = { deltaPx ->
                        splitFraction = (splitFraction + deltaPx / totalWidthPx).coerceIn(0.28f, 0.65f)
                    }
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (selectedCard == null) {
                        EventDetailEmptyState()
                    } else {
                        val resolvedRoomName = roomNamesMap[selectedCard.sourceRoomId]?.ifBlank { null }
                            ?: selectedCard.sourceRoomName.ifBlank { null }
                            ?: selectedCard.sourceRoomId
                        EventDetailPane(
                            card = selectedCard,
                            roomName = resolvedRoomName,
                            currentIndex = selectedIndex,
                            totalVisibleCount = visibleEvents.size,
                            hasPrevious = selectedIndex > 0,
                            hasNext = selectedIndex in 0 until visibleEvents.lastIndex,
                            onSelectPrevious = {
                                if (selectedIndex > 0) {
                                    appState.selectEvent(visibleEvents[selectedIndex - 1].id)
                                }
                            },
                            onSelectNext = {
                                if (selectedIndex in 0 until visibleEvents.lastIndex) {
                                    appState.selectEvent(visibleEvents[selectedIndex + 1].id)
                                }
                            },
                            isTranslatingMessages = selectedCard.id in translatingCardIds,
                            onTranslateSourceMessages = { appState.translateSourceMessages(selectedCard.id) },
                            onToggleStar = { appState.toggleStarred(selectedCard.id) },
                            onToggleRead = {
                                if (selectedCard.isRead) {
                                    appState.markUnread(selectedCard.id)
                                } else {
                                    appState.markRead(selectedCard.id)
                                }
                            },
                            onAddToCalendar = { appState.exportToCalendar(selectedCard) },
                            onRequestDelete = { cardPendingDelete = selectedCard }
                        )
                    }
                }
            }
        } else {
            if (selectedCard == null) {
                EventListPane(
                    modifier = Modifier.fillMaxSize(),
                    allEvents = allEvents,
                    visibleEvents = visibleEvents,
                    roomNamesMap = roomNamesMap,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { appState.searchQuery.value = it },
                    starredOnly = starredOnly,
                    onToggleStarredOnly = { appState.starredOnlyFilter.value = !starredOnly },
                    selectedFilter = selectedFilter,
                    onSelectPriority = { appState.priorityFilter.value = it },
                    selectedGroupId = selectedGroupId,
                    selectedGroupName = selectedGroupName,
                    groupOptions = groupOptions,
                    onSelectGroup = { appState.groupFilter.value = it },
                    sortOrder = sortOrder,
                    onSelectSortOrder = { appState.sortOrder.value = it },
                    selectedEventId = null,
                    onSelectEvent = { appState.selectEvent(it) },
                    onToggleStarEvent = { appState.toggleStarred(it) },
                    isSyncing = isSyncing,
                    onTriggerSync = { if (!isSyncing) appState.triggerSync() }
                )
            } else {
                val resolvedRoomName = roomNamesMap[selectedCard.sourceRoomId]?.ifBlank { null }
                    ?: selectedCard.sourceRoomName.ifBlank { null }
                    ?: selectedCard.sourceRoomId
                EventDetailPane(
                    card = selectedCard,
                    roomName = resolvedRoomName,
                    currentIndex = selectedIndex,
                    totalVisibleCount = visibleEvents.size,
                    hasPrevious = selectedIndex > 0,
                    hasNext = selectedIndex in 0 until visibleEvents.lastIndex,
                    onSelectPrevious = {
                        if (selectedIndex > 0) {
                            appState.selectEvent(visibleEvents[selectedIndex - 1].id)
                        }
                    },
                    onSelectNext = {
                        if (selectedIndex in 0 until visibleEvents.lastIndex) {
                            appState.selectEvent(visibleEvents[selectedIndex + 1].id)
                        }
                    },
                    isTranslatingMessages = selectedCard.id in translatingCardIds,
                    onTranslateSourceMessages = { appState.translateSourceMessages(selectedCard.id) },
                    onToggleStar = { appState.toggleStarred(selectedCard.id) },
                    onToggleRead = {
                        if (selectedCard.isRead) {
                            appState.markUnread(selectedCard.id)
                        } else {
                            appState.markRead(selectedCard.id)
                        }
                    },
                    onAddToCalendar = { appState.exportToCalendar(selectedCard) },
                    onRequestDelete = { cardPendingDelete = selectedCard }
                )
            }
        }

        if (isSyncing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }

        cardPendingDelete?.let { target ->
            DeleteConfirmationDialog(
                title = "Delete Notice?",
                message = "Remove \"${target.title}\" from your event feed?",
                onConfirm = {
                    appState.dismiss(target.id)
                    cardPendingDelete = null
                },
                onDismiss = { cardPendingDelete = null }
            )
        }
    }
}

@Composable
private fun EventListPane(
    modifier: Modifier,
    allEvents: List<EventCard>,
    visibleEvents: List<EventCard>,
    roomNamesMap: Map<String, String>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    starredOnly: Boolean,
    onToggleStarredOnly: () -> Unit,
    selectedFilter: EventPriority?,
    onSelectPriority: (EventPriority?) -> Unit,
    selectedGroupId: String?,
    selectedGroupName: String?,
    groupOptions: List<GroupFilterOption>,
    onSelectGroup: (String?) -> Unit,
    sortOrder: EventSortOrder,
    onSelectSortOrder: (EventSortOrder) -> Unit,
    selectedEventId: String?,
    onSelectEvent: (String) -> Unit,
    onToggleStarEvent: (String) -> Unit,
    isSyncing: Boolean,
    onTriggerSync: () -> Unit
) {
    val listState = rememberLazyListState()
    var isFabExpanded by remember { mutableStateOf(true) }
    var previousIndex by remember { mutableStateOf(0) }
    var previousOffset by remember { mutableStateOf(0) }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        val currentIndex = listState.firstVisibleItemIndex
        val currentOffset = listState.firstVisibleItemScrollOffset
        isFabExpanded = shouldExpandFab(
            firstVisibleIndex = currentIndex,
            firstVisibleOffset = currentOffset,
            previousIndex = previousIndex,
            previousOffset = previousOffset,
            currentlyExpanded = isFabExpanded
        )
        previousIndex = currentIndex
        previousOffset = currentOffset
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, top = 16.dp, end = 12.dp)
        ) {
            PillSearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                placeholder = "Search events",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            // Single-line horizontally scrollable filter chip bar (never wraps onto a second line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = starredOnly,
                    onClick = onToggleStarredOnly,
                    leadingIcon = {
                        Icon(
                            imageVector = if (starredOnly) Icons.Filled.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "Starred",
                            maxLines = 1,
                            lineHeight = MaterialTheme.typography.labelLarge.fontSize
                        )
                    }
                )

                DropdownFilterChip(
                    selected = selectedFilter != null,
                    label = if (selectedFilter == null) "Priority" else "Priority: ${formatPriorityLabel(selectedFilter)}",
                    items = listOf<Pair<String, EventPriority?>>("All priorities" to null) +
                        EventPriority.entries.map { formatPriorityLabel(it) to it },
                    isItemSelected = { it == selectedFilter },
                    onSelect = onSelectPriority
                )

                DropdownFilterChip(
                    selected = selectedGroupId != null,
                    label = if (selectedGroupId == null) "Group" else "Group: ${selectedGroupName.orEmpty()}",
                    items = listOf<Pair<String, String?>>("All groups" to null) +
                        groupOptions.map { it.displayName to it.roomId },
                    isItemSelected = { it == selectedGroupId },
                    onSelect = onSelectGroup
                )

                DropdownFilterChip(
                    selected = sortOrder != EventSortOrder.NEWEST_FIRST,
                    label = sortOrder.label,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    items = EventSortOrder.entries.map { it.label to it },
                    isItemSelected = { it == sortOrder },
                    onSelect = onSelectSortOrder
                )
            }

            Spacer(Modifier.height(10.dp))

            if (visibleEvents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (allEvents.isEmpty()) "No events extracted yet" else "No matching events found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (allEvents.isEmpty()) {
                                "Tap 'Sync now' or configure your monitored groups in the Groups tab."
                            } else {
                                "Try clearing your search query or selecting a different filter."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(visibleEvents, key = { _, card -> card.id }) { index, card ->
                        val resolvedRoomName = roomNamesMap[card.sourceRoomId]?.ifBlank { null }
                            ?: card.sourceRoomName.ifBlank { null }
                            ?: card.sourceRoomId
                        val itemShape = segmentedItemShape(index = index, totalCount = visibleEvents.size)
                        EventListItem(
                            card = card,
                            roomName = resolvedRoomName,
                            shape = itemShape,
                            isSelected = card.id == selectedEventId,
                            onClick = { onSelectEvent(card.id) },
                            onToggleStar = { onToggleStarEvent(card.id) }
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            text = {
                Text(
                    text = if (isSyncing) "Syncing..." else "Sync now",
                    lineHeight = MaterialTheme.typography.labelLarge.fontSize
                )
            },
            icon = {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = if (isSyncing) "Syncing" else "Sync now"
                )
            },
            onClick = onTriggerSync,
            expanded = isFabExpanded,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = if (isSyncing) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = if (isSyncing) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            }
        )
    }
}

/**
 * Compact 3-line segmented list row inside the left master pane using `surfaceContainerLowest`
 * and larger outer list corners (`16.dp`) with tight inner item corners (`4.dp`).
 */
@Composable
private fun EventListItem(
    card: EventCard,
    roomName: String,
    shape: RoundedCornerShape,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleStar: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val priorityColor = PriorityColors.getColor(card.priority, isDark)
    val priorityContainer = PriorityColors.getContainerColor(card.priority, isDark)
    val senderName = card.originalSender.ifBlank { "Unknown" }
    val displaySummary = remember(card.translatedSummary, card.summary) {
        card.translatedSummary.ifBlank { card.summary }.replace('\n', ' ').trim()
    }
    val formattedTime = remember(card.timestampSeconds) {
        val instant = Instant.ofEpochSecond(card.timestampSeconds)
        DateTimeFormatter.ofPattern("MMM dd, HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }
    val avatarInitial = remember(roomName, senderName) {
        roomName.trim().take(1).ifBlank { senderName.trim().take(1) }.uppercase().ifBlank { "E" }
    }

    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = priorityContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = avatarInitial,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            lineHeight = MaterialTheme.typography.headlineSmall.fontSize
                        ),
                        fontWeight = FontWeight.Light,
                        color = priorityColor
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                val primaryTextColor = if (!card.isRead) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                val summaryTextColor = if (!card.isRead) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$roomName • $senderName",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall.copy(
                            lineHeight = MaterialTheme.typography.titleSmall.fontSize
                        ),
                        fontWeight = if (!card.isRead) FontWeight.Bold else FontWeight.Normal,
                        color = primaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall.copy(
                            lineHeight = MaterialTheme.typography.labelSmall.fontSize
                        ),
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
                        )
                    }
                }

                Spacer(Modifier.height(3.dp))

                Text(
                    text = card.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = MaterialTheme.typography.bodyMedium.fontSize
                    ),
                    fontWeight = if (!card.isRead) FontWeight.Bold else FontWeight.Normal,
                    color = primaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displaySummary,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall.copy(
                            lineHeight = MaterialTheme.typography.bodySmall.fontSize
                        ),
                        color = summaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onToggleStar,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = if (card.isStarred) Icons.Filled.Star else Icons.Default.StarBorder,
                            contentDescription = if (card.isStarred) "Unstar event" else "Star event",
                            modifier = Modifier.size(18.dp),
                            tint = if (card.isStarred) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Slim desktop split-view resize handle (`10.dp` hit zone with a compact `3.dp × 24.dp` vertical pill indicator).
 */
@Composable
private fun SplitResizeHandle(
    onDragDeltaPx: (Float) -> Unit
) {
    val draggableState = rememberDraggableState { deltaPx ->
        onDragDeltaPx(deltaPx)
    }

    Box(
        modifier = Modifier
            .width(10.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                orientation = Orientation.Horizontal,
                state = draggableState
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.40f))
        )
    }
}

/**
 * Empty state shown on the right half of the split view when no event is currently selected.
 */
@Composable
private fun EventDetailEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.EventNote,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Select an event",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Right detail pane showing the adaptive top action bar (without a redundant close button),
 * title/badges header with star toggle, full event card body on `surfaceContainerLowest`,
 * and bottom-right Previous/Next notice navigation buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventDetailPane(
    card: EventCard,
    roomName: String,
    currentIndex: Int,
    totalVisibleCount: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onSelectPrevious: () -> Unit,
    onSelectNext: () -> Unit,
    isTranslatingMessages: Boolean,
    onTranslateSourceMessages: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleRead: () -> Unit,
    onAddToCalendar: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    var showTranslatedMessages by remember(card.id) { mutableStateOf(false) }
    var autoRevealTranslation by remember(card.id) { mutableStateOf(false) }

    LaunchedEffect(card.translatedSourceMessages, isTranslatingMessages) {
        if (autoRevealTranslation && card.translatedSourceMessages.isNotBlank()) {
            showTranslatedMessages = true
            autoRevealTranslation = false
        } else if (!isTranslatingMessages && card.translatedSourceMessages.isBlank()) {
            autoRevealTranslation = false
        }
    }

    val isShowingTranslation = showTranslatedMessages && card.translatedSourceMessages.isNotBlank()
    val formattedTime = remember(card.timestampSeconds) {
        val instant = Instant.ofEpochSecond(card.timestampSeconds)
        DateTimeFormatter.ofPattern("MMM dd, yyyy • HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }
    val senderName = card.originalSender.ifBlank { "Unknown" }
    val priorityColor = PriorityColors.getColor(card.priority, isDark)
    val priorityContainer = PriorityColors.getContainerColor(card.priority, isDark)

    val handleTranslationAction = {
        if (card.translatedSourceMessages.isNotBlank()) {
            showTranslatedMessages = !showTranslatedMessages
        } else {
            autoRevealTranslation = true
            onTranslateSourceMessages()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Adaptive Top Bar appearing on the right when a card is open (no close button)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!card.eventDate.isNullOrBlank()) {
                    IconButton(onClick = onAddToCalendar) {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = "Add to Calendar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (card.sourceMessages.isNotBlank()) {
                    if (isTranslatingMessages) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        IconButton(onClick = handleTranslationAction) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = if (isShowingTranslation) {
                                    "Show original groupchat message"
                                } else {
                                    "Translate groupchat message"
                                },
                                tint = if (isShowingTranslation) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }

                IconButton(onClick = onRequestDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete notice",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onToggleRead) {
                    Icon(
                        imageVector = if (card.isRead) Icons.Default.MarkEmailUnread else Icons.Default.MarkEmailRead,
                        contentDescription = if (card.isRead) "Mark as unread" else "Mark as read",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 16.dp, bottom = 84.dp)
            ) {
                // Detail Title + Thin Title-Case Badges + Star button row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = card.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(Modifier.height(8.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Surface(
                                color = priorityContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = formatPriorityLabel(card.priority),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            lineHeight = MaterialTheme.typography.labelSmall.fontSize
                                        ),
                                        fontWeight = FontWeight.Light,
                                        color = priorityColor
                                    )
                                }
                            }

                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = formatCategoryLabel(card.category),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            lineHeight = MaterialTheme.typography.labelSmall.fontSize
                                        ),
                                        fontWeight = FontWeight.Light,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!card.eventDate.isNullOrBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = card.eventDate,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                lineHeight = MaterialTheme.typography.labelSmall.fontSize
                                            ),
                                            fontWeight = FontWeight.Light,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }

                    IconButton(onClick = onToggleStar) {
                        Icon(
                            imageVector = if (card.isStarred) Icons.Filled.Star else Icons.Default.StarBorder,
                            contentDescription = if (card.isStarred) "Unstar event" else "Star event",
                            tint = if (card.isStarred) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Main detail card body using surfaceContainerLowest
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        // Sender avatar + metadata header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val senderInitial = senderName.trim().take(1).uppercase().ifBlank { "S" }
                            Surface(
                                modifier = Modifier.size(42.dp),
                                shape = CircleShape,
                                color = priorityContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = senderInitial,
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            lineHeight = MaterialTheme.typography.headlineSmall.fontSize
                                        ),
                                        fontWeight = FontWeight.Light,
                                        color = priorityColor
                                    )
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = senderName,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            lineHeight = MaterialTheme.typography.titleSmall.fontSize
                                        ),
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = formattedTime,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            lineHeight = MaterialTheme.typography.labelMedium.fontSize
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = roomName,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        lineHeight = MaterialTheme.typography.bodySmall.fontSize
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Full Summary
                        val displaySummary = card.translatedSummary.ifBlank { card.summary }
                        Text(
                            text = displaySummary,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // All Structured Sections
                        if (card.sections.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                card.sections.forEach { section ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                            .padding(horizontal = 14.dp, vertical = 12.dp)
                                    ) {
                                        if (section.heading.isNotBlank()) {
                                            Text(
                                                text = section.heading,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        if (section.content.isNotBlank()) {
                                            if (section.heading.isNotBlank()) {
                                                Spacer(Modifier.height(4.dp))
                                            }
                                            Text(
                                                text = section.content,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Full Source Groupchat Messages
                        if (card.sourceMessages.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            val activeMessageText = if (isShowingTranslation) {
                                card.translatedSourceMessages
                            } else {
                                card.sourceMessages
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isShowingTranslation) {
                                            "Source Messages (Translated)"
                                        } else {
                                            "Source Messages"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    if (isTranslatingMessages) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        IconButton(
                                            modifier = Modifier.size(28.dp),
                                            onClick = handleTranslationAction
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Translate,
                                                contentDescription = if (isShowingTranslation) {
                                                    "Show original groupchat message"
                                                } else {
                                                    "Translate groupchat message"
                                                },
                                                modifier = Modifier.size(16.dp),
                                                tint = if (isShowingTranslation) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                Text(
                                    text = activeMessageText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!card.eventDate.isNullOrBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(onClick = onAddToCalendar) {
                                    Icon(
                                        Icons.Default.CalendarToday,
                                        contentDescription = "Add to Calendar",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Add to Calendar",
                                        lineHeight = MaterialTheme.typography.labelLarge.fontSize
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom-right Previous / Next notice navigation buttons
        if (totalVisibleCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onSelectPrevious,
                        enabled = hasPrevious,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Previous notice",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = "${currentIndex + 1} / $totalVisibleCount",
                        modifier = Modifier.padding(horizontal = 6.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            lineHeight = MaterialTheme.typography.labelMedium.fontSize
                        ),
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    IconButton(
                        onClick = onSelectNext,
                        enabled = hasNext,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Next notice",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact dropdown [FilterChip] anchoring its own [DropdownMenu] with active checkmarks.
 */
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
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = MaterialTheme.typography.labelLarge.fontSize,
                    modifier = Modifier.widthIn(max = 220.dp)
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            items.forEach { (itemLabel, value) ->
                val itemChecked = isItemSelected(value)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = itemLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 280.dp)
                        )
                    },
                    trailingIcon = if (itemChecked) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Determines whether a source message block is long enough (> [lineThreshold] lines,
 * accounting for both explicit line breaks and wrapped long paragraphs) to collapse
 * into an expandable dropdown instead of rendering inline.
 */
internal fun isLongSourceMessage(
    text: String,
    lineThreshold: Int = 10,
    charsPerVisualLine: Int = 75
): Boolean {
    if (text.isBlank()) return false
    val safeCharsPerLine = charsPerVisualLine.coerceAtLeast(1)
    val estimatedLines = text.split('\n').sumOf { paragraph ->
        if (paragraph.isEmpty()) 1 else (paragraph.length + safeCharsPerLine - 1) / safeCharsPerLine
    }
    return estimatedLines > lineThreshold
}
