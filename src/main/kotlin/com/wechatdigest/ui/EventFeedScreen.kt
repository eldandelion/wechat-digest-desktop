package com.wechatdigest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wechatdigest.app.AppState
import com.wechatdigest.app.EventSortOrder
import com.wechatdigest.core.attachment.WeChatFileMessageParser
import com.wechatdigest.core.llm.EventExtractor
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCardStatus
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.GroupChat
import java.awt.Cursor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    priority.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

internal fun formatCategoryLabel(category: EventCategory): String =
    category.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

internal fun formatStatusLabel(status: EventCardStatus): String = when (status) {
    EventCardStatus.ACTIVE -> "Active"
    EventCardStatus.UPDATED -> "Updated"
    EventCardStatus.CANCELLED -> "Cancelled"
}

private val eventDateOnlyDisplayFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH)
private val eventDateTimeDisplayFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy • HH:mm", Locale.ENGLISH)
private val inlineIsoDateTimeSeparatorRegex = Regex("""(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}(?::\d{2})?)""")

/**
 * Formats an ISO-8601 [rawDate] string (e.g. `2026-11-25T15:30:00` or `2026-11-25`) into a
 * human-readable badge label while preserving unparseable fallback text cleanly without a raw `T`.
 */
internal fun formatEventDateLabel(rawDate: String): String {
    val trimmed = rawDate.trim()
    if (trimmed.isEmpty()) return ""

    runCatching { LocalDate.parse(trimmed) }.getOrNull()?.let { date ->
        return eventDateOnlyDisplayFormatter.format(date)
    }

    val normalized = trimmed.replace(' ', 'T')
    val localDateTime = runCatching {
        when {
            normalized.length == 16 -> LocalDateTime.parse("$normalized:00")
            normalized.contains('.') -> LocalDateTime.parse(normalized.substringBefore('.'))
            else -> LocalDateTime.parse(normalized)
        }
    }.getOrNull() ?: runCatching {
        OffsetDateTime.parse(normalized).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
    }.getOrNull()

    if (localDateTime != null) {
        return eventDateTimeDisplayFormatter.format(localDateTime)
    }

    return trimmed.replace(inlineIsoDateTimeSeparatorRegex, "$1 $2")
}

internal fun resolveRoomDisplayName(roomNamesMap: Map<String, String>, card: EventCard): String =
    roomNamesMap[card.sourceRoomId]?.ifBlank { null }
        ?: card.sourceRoomName.ifBlank { null }
        ?: card.sourceRoomId

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
        0 -> SegmentedCornerRadii(outerRadiusDp, outerRadiusDp, innerRadiusDp, innerRadiusDp)
        totalCount - 1 -> SegmentedCornerRadii(innerRadiusDp, innerRadiusDp, outerRadiusDp, outerRadiusDp)
        else -> SegmentedCornerRadii(innerRadiusDp, innerRadiusDp, innerRadiusDp, innerRadiusDp)
    }
}

internal fun segmentedItemShape(index: Int, totalCount: Int): RoundedCornerShape {
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
            optionsById[card.sourceRoomId] = resolveRoomDisplayName(roomNamesMap, card)
        }
    }
    monitoredRoomIds.forEach { roomId ->
        if (roomId.isNotBlank() && roomId !in optionsById) {
            optionsById[roomId] = roomNamesMap[roomId]?.ifBlank { null } ?: roomId
        }
    }

    return optionsById.entries
        .map { (id, name) -> GroupFilterOption(roomId = id, displayName = name) }
        .sortedBy { it.displayName.lowercase(Locale.ROOT) }
}

/**
 * Computes whether the Sync FAB should stay expanded with text or collapse based on scroll movement.
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
    if (firstVisibleIndex != previousIndex) return firstVisibleIndex < previousIndex
    val offsetDelta = firstVisibleOffset - previousOffset
    return when {
        offsetDelta > offsetDeadbandPx -> false
        offsetDelta < -offsetDeadbandPx -> true
        else -> currentlyExpanded
    }
}

internal fun formatFileSizeLabel(bytes: Long): String = WeChatFileMessageParser.formatByteSize(bytes)

/**
 * Determines whether a source message block is long enough (> [lineThreshold] lines,
 * accounting for wrapped long paragraphs) to treat as a long notice.
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

/**
 * Top-level responsive master-detail feed screen adapting between split view and single-pane navigation.
 */
@Composable
fun EventFeedScreen(appState: AppState) {
    val visibleEvents by appState.visibleEvents.collectAsState()
    val allEvents by appState.events.collectAsState()
    val availableRooms by appState.availableRooms.collectAsState()
    val settings by appState.settings.collectAsState()
    val selectedEventId by appState.selectedEventId.collectAsState()
    val isSyncing by appState.isSyncing.collectAsState()
    var cardPendingDelete by remember { mutableStateOf<EventCard?>(null) }
    var splitFraction by remember { mutableStateOf(0.44f) }

    val roomNamesMap = remember(availableRooms) { availableRooms.associate { it.roomId to it.displayName } }
    val groupOptions = remember(allEvents, availableRooms, settings.monitoredRoomIds) {
        buildGroupFilterOptions(allEvents, availableRooms, settings.monitoredRoomIds)
    }
    val selectedIndex = remember(visibleEvents, selectedEventId) { visibleEvents.indexOfFirst { it.id == selectedEventId } }
    val selectedCard = if (selectedIndex >= 0) visibleEvents[selectedIndex] else null

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val density = LocalDensity.current
        val totalWidthPx = remember(totalWidth, density) { with(density) { totalWidth.toPx().coerceAtLeast(1f) } }

        if (totalWidth >= 680.dp) {
            Row(modifier = Modifier.fillMaxSize()) {
                EventListHost(
                    appState = appState,
                    allEvents = allEvents,
                    visibleEvents = visibleEvents,
                    roomNamesMap = roomNamesMap,
                    groupOptions = groupOptions,
                    selectedEventId = selectedCard?.id,
                    isSyncing = isSyncing,
                    contentEndPadding = 12.dp,
                    modifier = Modifier.width(totalWidth * splitFraction).fillMaxHeight()
                )
                SplitResizeHandle { deltaPx -> splitFraction = (splitFraction + deltaPx / totalWidthPx).coerceIn(0.28f, 0.65f) }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (selectedCard == null) {
                        EventDetailEmptyState()
                    } else {
                        EventDetailHost(
                            appState = appState,
                            selectedCard = selectedCard,
                            selectedIndex = selectedIndex,
                            visibleEvents = visibleEvents,
                            roomNamesMap = roomNamesMap,
                            contentStartPadding = 12.dp,
                            onNavigateBack = null,
                            onRequestDelete = { cardPendingDelete = selectedCard }
                        )
                    }
                }
            }
        } else if (selectedCard == null) {
            EventListHost(
                appState = appState,
                allEvents = allEvents,
                visibleEvents = visibleEvents,
                roomNamesMap = roomNamesMap,
                groupOptions = groupOptions,
                selectedEventId = null,
                isSyncing = isSyncing,
                contentEndPadding = 16.dp,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            EventDetailHost(
                appState = appState,
                selectedCard = selectedCard,
                selectedIndex = selectedIndex,
                visibleEvents = visibleEvents,
                roomNamesMap = roomNamesMap,
                contentStartPadding = 16.dp,
                onNavigateBack = { appState.selectEvent(null) },
                onRequestDelete = { cardPendingDelete = selectedCard }
            )
        }

        SyncTopProgressBar(isSyncing = isSyncing)
        cardPendingDelete?.let { target ->
            DismissCardDialog(
                card = target,
                onDismissOnly = { appState.dismiss(target.id); cardPendingDelete = null },
                onDismissAndMuteSender = { appState.dismissAndMuteSender(target); cardPendingDelete = null },
                onDismissAndAddFilter = { pattern, matchType ->
                    appState.dismissAndAddPrefilterRule(target, pattern, matchType)
                    cardPendingDelete = null
                },
                onCancel = { cardPendingDelete = null }
            )
        }
    }
}

@Composable
private fun BoxScope.SyncTopProgressBar(isSyncing: Boolean) {
    if (!isSyncing) return
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .semantics { contentDescription = "Sync in progress" }
    )
}

@Composable
private fun EventListHost(
    appState: AppState,
    allEvents: List<EventCard>,
    visibleEvents: List<EventCard>,
    roomNamesMap: Map<String, String>,
    groupOptions: List<GroupFilterOption>,
    selectedEventId: String?,
    isSyncing: Boolean,
    contentEndPadding: Dp,
    modifier: Modifier
) {
    val selectedFilter by appState.priorityFilter.collectAsState()
    val selectedGroupId by appState.groupFilter.collectAsState()
    val starredOnly by appState.starredOnlyFilter.collectAsState()
    val unreadOnly by appState.unreadOnlyFilter.collectAsState()
    val sortOrder by appState.sortOrder.collectAsState()
    val searchQuery by appState.searchQuery.collectAsState()
    val selectedGroupName = remember(selectedGroupId, groupOptions) {
        groupOptions.firstOrNull { it.roomId == selectedGroupId }?.displayName ?: selectedGroupId
    }

    EventListPane(
        modifier = modifier,
        allEvents = allEvents,
        visibleEvents = visibleEvents,
        roomNamesMap = roomNamesMap,
        searchQuery = searchQuery,
        onSearchQueryChange = { appState.searchQuery.value = it },
        starredOnly = starredOnly,
        onToggleStarredOnly = { appState.starredOnlyFilter.value = !starredOnly },
        unreadOnly = unreadOnly,
        onToggleUnreadOnly = { appState.unreadOnlyFilter.value = !unreadOnly },
        selectedFilter = selectedFilter,
        onSelectPriority = { appState.priorityFilter.value = it },
        selectedGroupId = selectedGroupId,
        selectedGroupName = selectedGroupName,
        groupOptions = groupOptions,
        onSelectGroup = { appState.groupFilter.value = it },
        sortOrder = sortOrder,
        onSelectSortOrder = { appState.sortOrder.value = it },
        selectedEventId = selectedEventId,
        onSelectEvent = { appState.selectEvent(it) },
        onToggleStarEvent = { appState.toggleStarred(it) },
        isSyncing = isSyncing,
        syncStatusTextFlow = appState.syncStatusText,
        contentEndPadding = contentEndPadding,
        onTriggerSync = { if (!isSyncing) appState.triggerSync() }
    )
}

@Composable
private fun EventDetailHost(
    appState: AppState,
    selectedCard: EventCard,
    selectedIndex: Int,
    visibleEvents: List<EventCard>,
    roomNamesMap: Map<String, String>,
    contentStartPadding: Dp,
    onNavigateBack: (() -> Unit)?,
    onRequestDelete: () -> Unit
) {
    val translatingCardIds by appState.translatingCardIds.collectAsState()
    val recheckingCardIds by appState.recheckingAttachmentCardIds.collectAsState()
    val enrichingCardIds by appState.enrichingCardIds.collectAsState()
    val reextractingCardIds by appState.reextractingCardIds.collectAsState()
    val isSyncing by appState.isSyncing.collectAsState()
    val settings by appState.settings.collectAsState()
    val canReextract = !(isSyncing && EventExtractor.resolveConcurrencyForConfig(settings.llm) == 1)

    EventDetailPane(
        card = selectedCard,
        roomName = resolveRoomDisplayName(roomNamesMap, selectedCard),
        currentIndex = selectedIndex,
        totalVisibleCount = visibleEvents.size,
        hasPrevious = selectedIndex > 0,
        hasNext = selectedIndex in 0 until visibleEvents.lastIndex,
        onSelectPrevious = { if (selectedIndex > 0) appState.selectEvent(visibleEvents[selectedIndex - 1].id) },
        onSelectNext = { if (selectedIndex in 0 until visibleEvents.lastIndex) appState.selectEvent(visibleEvents[selectedIndex + 1].id) },
        isTranslatingMessages = selectedCard.id in translatingCardIds,
        isRecheckingAttachments = selectedCard.id in recheckingCardIds,
        isEnrichingSections = selectedCard.id in enrichingCardIds,
        isReextracting = selectedCard.id in reextractingCardIds,
        canReextract = canReextract,
        onTranslateSourceMessages = { appState.translateSourceMessages(selectedCard.id) },
        onEnrichSections = { targetSectionIdx -> appState.enrichSectionCards(selectedCard.id, targetSectionIdx) },
        onReextractCard = { appState.reextractCard(selectedCard.id) },
        onQuickEditCard = { title, category, priority, eventDate ->
            appState.quickEditCard(selectedCard.id, title, category, priority, eventDate)
        },
        onLoadContextMessages = { appState.loadContextMessages(selectedCard) },
        onOpenAttachment = { appState.openAttachment(it) },
        onRevealAttachmentInFinder = { appState.revealAttachmentInFinder(it) },
        onRecheckAttachments = { appState.recheckCardAttachments(selectedCard.id) },
        onToggleStar = { appState.toggleStarred(selectedCard.id) },
        onToggleRead = { if (selectedCard.isRead) appState.markUnread(selectedCard.id) else appState.markRead(selectedCard.id) },
        onAddToCalendar = { appState.exportToCalendar(selectedCard) },
        onRequestDelete = onRequestDelete,
        contentStartPadding = contentStartPadding,
        onNavigateBack = onNavigateBack
    )
}

@Composable
private fun SplitResizeHandle(onDragDeltaPx: (Float) -> Unit) {
    val draggableState = rememberDraggableState { deltaPx -> onDragDeltaPx(deltaPx) }
    Box(
        modifier = Modifier
            .width(10.dp)
            .fillMaxHeight()
            .semantics { contentDescription = "Resize split view" }
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(orientation = Orientation.Horizontal, state = draggableState),
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
