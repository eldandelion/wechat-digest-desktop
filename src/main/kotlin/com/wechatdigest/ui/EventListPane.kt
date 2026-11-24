package com.wechatdigest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wechatdigest.app.EventSortOrder
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.ui.theme.LocalIsDarkTheme
import com.wechatdigest.ui.theme.PriorityColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val EmptySyncStatusFlow: StateFlow<String?> = MutableStateFlow(null)

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
    starredOnly: Boolean,
    onToggleStarredOnly: () -> Unit,
    unreadOnly: Boolean,
    onToggleUnreadOnly: () -> Unit,
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
    syncStatusTextFlow: StateFlow<String?> = EmptySyncStatusFlow,
    contentEndPadding: Dp = 16.dp,
    onTriggerSync: () -> Unit
) {
    val listState = rememberLazyListState()
    val isFabExpanded = rememberFabExpandedState(listState)

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, top = 8.dp, end = contentEndPadding)) {
            PillSearchBar(query = searchQuery, onQueryChange = onSearchQueryChange, placeholder = "Search events", modifier = Modifier.fillMaxWidth())
            // FilterChip has 8.dp built-in vertical touch padding above and below its pill, so 8.dp spacers give 16.dp visual gaps on both sides.
            Spacer(Modifier.height(8.dp))
            EventListFilterBar(
                starredOnly = starredOnly,
                onToggleStarredOnly = onToggleStarredOnly,
                unreadOnly = unreadOnly,
                onToggleUnreadOnly = onToggleUnreadOnly,
                selectedFilter = selectedFilter,
                onSelectPriority = onSelectPriority,
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
                listState = listState,
                selectedEventId = selectedEventId,
                onSelectEvent = onSelectEvent,
                onToggleStarEvent = onToggleStarEvent
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
                    syncStatusTextFlow = syncStatusTextFlow
                )
            }
            SyncFloatingActionButton(
                isSyncing = isSyncing,
                expanded = isFabExpanded,
                onTriggerSync = onTriggerSync
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
    selectedFilter: EventPriority?,
    onSelectPriority: (EventPriority?) -> Unit,
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
    listState: LazyListState,
    selectedEventId: String?,
    onSelectEvent: (String) -> Unit,
    onToggleStarEvent: (String) -> Unit
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
            EventListItem(
                card = card,
                roomName = roomName,
                shape = segmentedItemShape(index = index, totalCount = visibleEvents.size),
                isSelected = card.id == selectedEventId,
                onClick = onClick,
                onToggleStar = onToggleStar
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
    syncStatusTextFlow: StateFlow<String?>,
    modifier: Modifier = Modifier
) {
    if (!isSyncing) return
    val syncStatusText by syncStatusTextFlow.collectAsState()
    val status = syncStatusText?.takeIf { it.isNotBlank() } ?: return
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
    modifier: Modifier = Modifier
) {
    ExtendedFloatingActionButton(
        text = { Text(text = if (isSyncing) "Syncing..." else "Sync now", lineHeight = MaterialTheme.typography.labelLarge.fontSize) },
        icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
        onClick = onTriggerSync,
        expanded = expanded,
        modifier = modifier,
        containerColor = if (isSyncing) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (isSyncing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
    )
}

/**
 * Compact 3-line segmented list row inside the left master pane using `surfaceContainerLowest`.
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EventListAvatar(initial = avatarInitial, containerColor = priorityContainer, textColor = priorityColor)
            Spacer(Modifier.width(12.dp))
            EventListItemContent(card = card, roomName = roomName, senderName = senderName, onToggleStar = onToggleStar, modifier = Modifier.weight(1f))
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

@Composable
private fun EventListItemContent(
    card: EventCard,
    roomName: String,
    senderName: String,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryTextColor = if (!card.isRead) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val summaryTextColor = if (!card.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f)
    val displaySummary = remember(card.translatedSummary, card.summary) {
        card.translatedSummary.ifBlank { card.summary }.replace('\n', ' ').trim()
    }
    val formattedTime = remember(card.timestampSeconds) {
        DateTimeFormatter.ofPattern("MMM dd, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(card.timestampSeconds))
    }

    Column(modifier = modifier) {
        EventListItemHeader(card = card, roomName = roomName, senderName = senderName, formattedTime = formattedTime, primaryTextColor = primaryTextColor)
        Spacer(Modifier.height(3.dp))
        Text(
            text = card.title,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = MaterialTheme.typography.bodyMedium.fontSize),
            fontWeight = if (!card.isRead) FontWeight.Bold else FontWeight.Normal,
            color = primaryTextColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        EventListItemFooter(displaySummary = displaySummary, summaryTextColor = summaryTextColor, isStarred = card.isStarred, onToggleStar = onToggleStar)
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
    isStarred: Boolean,
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
