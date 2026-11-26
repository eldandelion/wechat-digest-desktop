package com.wechatdigest.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wechatdigest.core.model.EventAttachment
import com.wechatdigest.core.model.EventCard
import com.wechatdigest.core.model.EventCardStatus
import com.wechatdigest.core.model.EventCategory
import com.wechatdigest.core.model.EventDetailItem
import com.wechatdigest.core.model.EventDetailItemParser
import com.wechatdigest.core.model.EventPriority
import com.wechatdigest.core.model.EventSection
import com.wechatdigest.core.model.SourceMessageBubble
import com.wechatdigest.core.model.SourceMessageParser
import com.wechatdigest.core.repository.WeChatMessageRepository
import com.wechatdigest.ui.theme.LocalIsDarkTheme
import com.wechatdigest.ui.theme.PriorityColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Empty state shown on the right half of the split view when no event is currently selected.
 */
@Composable
internal fun EventDetailEmptyState() {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(modifier = Modifier.size(72.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.EventNote, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(text = "Select an event", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Right detail pane showing the adaptive top action bar, card header and sections,
 * attachments list, source message block, and Previous/Next navigation controls.
 */
@Composable
internal fun EventDetailPane(
    card: EventCard,
    roomName: String,
    currentIndex: Int,
    totalVisibleCount: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onSelectPrevious: () -> Unit,
    onSelectNext: () -> Unit,
    isTranslatingMessages: Boolean,
    isRecheckingAttachments: Boolean,
    onTranslateSourceMessages: () -> Unit,
    onOpenAttachment: (EventAttachment) -> Unit,
    onRevealAttachmentInFinder: (EventAttachment) -> Unit,
    onRecheckAttachments: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleRead: () -> Unit,
    onAddToCalendar: () -> Unit,
    onRequestDelete: () -> Unit,
    isEnrichingSections: Boolean = false,
    onEnrichSections: (Int?) -> Unit = {},
    isReextracting: Boolean = false,
    canReextract: Boolean = true,
    onReextractCard: () -> Unit = {},
    onQuickEditCard: (String, EventCategory, EventPriority, String?) -> Unit = { _, _, _, _ -> },
    onLoadContextMessages: suspend () -> Result<WeChatMessageRepository.ContextMessagesResult> = {
        Result.failure(IllegalStateException("Context loader not configured."))
    },
    contentStartPadding: Dp = 16.dp,
    onNavigateBack: (() -> Unit)? = null
) {
    var showTranslatedMessages by remember(card.id) { mutableStateOf(false) }
    var autoRevealTranslation by remember(card.id) { mutableStateOf(false) }
    var highlightedBubbleIndex by remember(card.id) { mutableStateOf<Int?>(null) }
    var showQuickEditDialog by remember(card.id) { mutableStateOf(false) }
    var showContextDialog by remember(card.id) { mutableStateOf(false) }

    LaunchedEffect(card.translatedSourceMessages, isTranslatingMessages) {
        if (autoRevealTranslation && card.translatedSourceMessages.isNotBlank()) {
            showTranslatedMessages = true
            autoRevealTranslation = false
        } else if (!isTranslatingMessages && card.translatedSourceMessages.isBlank()) {
            autoRevealTranslation = false
        }
    }

    val isShowingTranslation = showTranslatedMessages && card.translatedSourceMessages.isNotBlank()
    val activeSourceText = if (isShowingTranslation) card.translatedSourceMessages else card.sourceMessages
    val sourceBubbles = remember(activeSourceText) { SourceMessageParser.parse(activeSourceText) }
    val handleTranslationAction: () -> Unit = {
        highlightedBubbleIndex = null
        if (card.translatedSourceMessages.isNotBlank()) {
            showTranslatedMessages = !showTranslatedMessages
        } else {
            autoRevealTranslation = true
            onTranslateSourceMessages()
        }
    }

    val scrollState = key(card.id) { rememberScrollState() }
    val isScrolled by remember(scrollState) { derivedStateOf { scrollState.value > 0 } }
    val topBarColor by animateColorAsState(
        targetValue = if (isScrolled) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surface
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            EventDetailTopBar(
                card = card,
                containerColor = topBarColor,
                contentStartPadding = contentStartPadding,
                onNavigateBack = onNavigateBack,
                isTranslatingMessages = isTranslatingMessages,
                isShowingTranslation = isShowingTranslation,
                isReextracting = isReextracting,
                canReextract = canReextract,
                onQuickEdit = { showQuickEditDialog = true },
                onReextract = onReextractCard,
                onAddToCalendar = onAddToCalendar,
                onTranslateAction = handleTranslationAction,
                onRequestDelete = onRequestDelete,
                onToggleRead = onToggleRead
            )
            EventDetailScrollContent(
                card = card,
                roomName = roomName,
                scrollState = scrollState,
                contentStartPadding = contentStartPadding,
                isTranslatingMessages = isTranslatingMessages,
                isShowingTranslation = isShowingTranslation,
                isRecheckingAttachments = isRecheckingAttachments,
                isEnrichingSections = isEnrichingSections,
                sourceBubbles = sourceBubbles,
                highlightedBubbleIndex = highlightedBubbleIndex,
                onDetailItemClick = { item ->
                    val matched = SourceMessageParser.findMatchingBubbleIndex(
                        bubbles = sourceBubbles,
                        queryText = "${item.label} ${item.value}"
                    )
                    if (matched != null) {
                        highlightedBubbleIndex = if (highlightedBubbleIndex == matched) null else matched
                    }
                },
                onClearHighlight = { highlightedBubbleIndex = null },
                onShowContextDialog = { showContextDialog = true },
                onTranslateAction = handleTranslationAction,
                onEnrichSections = onEnrichSections,
                onOpenAttachment = onOpenAttachment,
                onRevealAttachmentInFinder = onRevealAttachmentInFinder,
                onRecheckAttachments = onRecheckAttachments,
                onToggleStar = onToggleStar,
                onAddToCalendar = onAddToCalendar
            )
        }
        if (totalVisibleCount > 0) {
            EventDetailNavigationBar(
                currentIndex = currentIndex,
                totalVisibleCount = totalVisibleCount,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onSelectPrevious = onSelectPrevious,
                onSelectNext = onSelectNext,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
            )
        }
    }

    if (showQuickEditDialog) {
        key(card.id) {
            QuickEditCardDialog(
                card = card,
                onSave = { title, category, priority, eventDate ->
                    onQuickEditCard(title, category, priority, eventDate)
                    showQuickEditDialog = false
                },
                onDismiss = { showQuickEditDialog = false }
            )
        }
    }
    if (showContextDialog) {
        ContextMessagesDialog(
            card = card,
            onLoadContextMessages = onLoadContextMessages,
            onDismiss = { showContextDialog = false }
        )
    }
}

@Composable
private fun EventDetailTopBar(
    card: EventCard,
    containerColor: Color,
    contentStartPadding: Dp,
    onNavigateBack: (() -> Unit)?,
    isTranslatingMessages: Boolean,
    isShowingTranslation: Boolean,
    isReextracting: Boolean,
    canReextract: Boolean,
    onQuickEdit: () -> Unit,
    onReextract: () -> Unit,
    onAddToCalendar: () -> Unit,
    onTranslateAction: () -> Unit,
    onRequestDelete: () -> Unit,
    onToggleRead: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = contentStartPadding, top = 8.dp, end = 16.dp, bottom = 8.dp)
                .height(50.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onNavigateBack != null) {
                TopBarTonalIconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to events",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TopBarTonalIconButton(onClick = onQuickEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Quick edit notice", modifier = Modifier.size(20.dp))
            }
            if (card.sourceMessages.isNotBlank()) {
                TopBarTonalIconButton(
                    onClick = onReextract,
                    enabled = canReextract && !isReextracting
                ) {
                    if (isReextracting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Re-extract notice", modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (!card.eventDate.isNullOrBlank()) {
                TopBarTonalIconButton(onClick = onAddToCalendar) {
                    Icon(Icons.Default.CalendarToday, contentDescription = "Add to Calendar", modifier = Modifier.size(20.dp))
                }
            }
            if (card.sourceMessages.isNotBlank()) {
                TranslationActionButton(
                    isTranslating = isTranslatingMessages,
                    isShowingTranslation = isShowingTranslation,
                    onClick = onTranslateAction,
                    buttonSizeDp = 40,
                    iconSizeDp = 20,
                    useSecondaryContainer = true
                )
            }
            TopBarTonalIconButton(onClick = onRequestDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete notice", modifier = Modifier.size(20.dp))
            }
            TopBarTonalIconButton(onClick = onToggleRead) {
                Icon(
                    imageVector = if (card.isRead) Icons.Default.MarkEmailUnread else Icons.Default.MarkEmailRead,
                    contentDescription = if (card.isRead) "Mark as unread" else "Mark as read",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun TopBarTonalIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.30f),
            disabledContentColor = contentColor
        ),
        content = content
    )
}

@Composable
private fun TranslationActionButton(
    isTranslating: Boolean,
    isShowingTranslation: Boolean,
    onClick: () -> Unit,
    buttonSizeDp: Int,
    iconSizeDp: Int,
    useSecondaryContainer: Boolean = false
) {
    val iconTint = when {
        isTranslating -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        isShowingTranslation -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val description = when {
        isTranslating -> "Translating groupchat message"
        isShowingTranslation -> "Show original groupchat message"
        else -> "Translate groupchat message"
    }
    if (useSecondaryContainer) {
        TopBarTonalIconButton(
            onClick = onClick,
            enabled = !isTranslating,
            contentColor = iconTint
        ) {
            Icon(
                imageVector = Icons.Default.Translate,
                contentDescription = description,
                modifier = Modifier.size(iconSizeDp.dp)
            )
        }
    } else {
        IconButton(onClick = onClick, enabled = !isTranslating, modifier = Modifier.size(buttonSizeDp.dp)) {
            Icon(
                imageVector = Icons.Default.Translate,
                contentDescription = description,
                modifier = Modifier.size(iconSizeDp.dp),
                tint = iconTint
            )
        }
    }
}

@Composable
private fun EventDetailScrollContent(
    card: EventCard,
    roomName: String,
    scrollState: ScrollState,
    contentStartPadding: Dp,
    isTranslatingMessages: Boolean,
    isShowingTranslation: Boolean,
    isRecheckingAttachments: Boolean,
    isEnrichingSections: Boolean,
    sourceBubbles: List<SourceMessageBubble>,
    highlightedBubbleIndex: Int?,
    onDetailItemClick: (EventDetailItem) -> Unit,
    onClearHighlight: () -> Unit,
    onShowContextDialog: () -> Unit,
    onTranslateAction: () -> Unit,
    onEnrichSections: (Int?) -> Unit,
    onOpenAttachment: (EventAttachment) -> Unit,
    onRevealAttachmentInFinder: (EventAttachment) -> Unit,
    onRecheckAttachments: () -> Unit,
    onToggleStar: () -> Unit,
    onAddToCalendar: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val priorityColor = PriorityColors.getColor(card.priority, isDark)
    val priorityContainer = PriorityColors.getContainerColor(card.priority, isDark)
    var sourceBoxTopPx by remember(card.id) { mutableStateOf(0) }
    val hasSourceMessages = card.sourceMessages.isNotBlank()

    LaunchedEffect(highlightedBubbleIndex) {
        if (highlightedBubbleIndex != null && sourceBoxTopPx > 0) {
            scrollState.animateScrollTo(sourceBoxTopPx)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(start = contentStartPadding, top = 8.dp, end = 16.dp, bottom = 84.dp)
    ) {
        EventDetailTitleRow(card = card, priorityColor = priorityColor, priorityContainer = priorityContainer, onToggleStar = onToggleStar)
        Spacer(Modifier.height(16.dp))
        EventDetailCardBody(
            card = card,
            roomName = roomName,
            priorityColor = priorityColor,
            priorityContainer = priorityContainer,
            onAddToCalendar = onAddToCalendar
        )
        if (card.sections.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                card.sections.forEachIndexed { idx, section ->
                    EventDetailSectionCard(
                        section = section,
                        isEnrichingSections = isEnrichingSections,
                        onEnrichSections = { onEnrichSections(idx) },
                        enableDetailItemClick = hasSourceMessages,
                        onDetailItemClick = onDetailItemClick
                    )
                }
            }
        }
        if (card.attachments.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            AttachmentsSection(card.attachments, isRecheckingAttachments, onOpenAttachment, onRevealAttachmentInFinder, onRecheckAttachments)
        }
        if (hasSourceMessages) {
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().onGloballyPositioned { sourceBoxTopPx = it.positionInParent().y.toInt() }) {
                EventDetailSourceMessagesBox(
                    bubbles = sourceBubbles,
                    highlightedBubbleIndex = highlightedBubbleIndex,
                    isTranslatingMessages = isTranslatingMessages,
                    isShowingTranslation = isShowingTranslation,
                    onClearHighlight = onClearHighlight,
                    onShowContextDialog = onShowContextDialog,
                    onTranslateAction = onTranslateAction
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventDetailTitleRow(
    card: EventCard,
    priorityColor: Color,
    priorityContainer: Color,
    onToggleStar: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = card.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (card.status != EventCardStatus.ACTIVE) {
                    val isCancelled = card.status == EventCardStatus.CANCELLED
                    DetailBadgeChip(
                        text = formatStatusLabel(card.status),
                        containerColor = if (isCancelled) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        textColor = if (isCancelled) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                DetailBadgeChip(text = formatPriorityLabel(card.priority), containerColor = priorityContainer, textColor = priorityColor)
                DetailBadgeChip(text = formatCategoryLabel(card.category), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, textColor = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!card.eventDate.isNullOrBlank()) {
                    val formattedEventDate = remember(card.eventDate) { formatEventDateLabel(card.eventDate) }
                    DetailBadgeChip(text = formattedEventDate, containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), textColor = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        IconButton(onClick = onToggleStar) {
            Icon(
                imageVector = if (card.isStarred) Icons.Filled.Star else Icons.Default.StarBorder,
                contentDescription = if (card.isStarred) "Unstar event" else "Star event",
                tint = if (card.isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailBadgeChip(text: String, containerColor: Color, textColor: Color) {
    Surface(color = containerColor, shape = RoundedCornerShape(6.dp)) {
        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = MaterialTheme.typography.labelSmall.fontSize),
                fontWeight = FontWeight.Light,
                color = textColor
            )
        }
    }
}

@Composable
private fun EventDetailCardBody(
    card: EventCard,
    roomName: String,
    priorityColor: Color,
    priorityContainer: Color,
    onAddToCalendar: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            EventDetailSenderRow(card = card, roomName = roomName, priorityColor = priorityColor, priorityContainer = priorityContainer)
            Spacer(Modifier.height(16.dp))
            Text(text = card.translatedSummary.ifBlank { card.summary }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!card.eventDate.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                EventDetailCalendarFooter(onAddToCalendar = onAddToCalendar)
            }
        }
    }
}

@Composable
private fun EventDetailSenderRow(card: EventCard, roomName: String, priorityColor: Color, priorityContainer: Color) {
    val senderName = card.originalSender.ifBlank { "Unknown" }
    val senderInitial = senderName.trim().take(1).uppercase().ifBlank { "S" }
    val formattedTime = remember(card.timestampSeconds) {
        DateTimeFormatter.ofPattern("MMM dd, yyyy • HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(card.timestampSeconds))
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(42.dp), shape = CircleShape, color = priorityContainer) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = senderInitial,
                    style = MaterialTheme.typography.headlineSmall.copy(lineHeight = MaterialTheme.typography.headlineSmall.fontSize),
                    fontWeight = FontWeight.Light,
                    color = priorityColor
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = senderName, style = MaterialTheme.typography.titleSmall.copy(lineHeight = MaterialTheme.typography.titleSmall.fontSize), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                Text(text = formattedTime, style = MaterialTheme.typography.labelMedium.copy(lineHeight = MaterialTheme.typography.labelMedium.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            Text(text = roomName, style = MaterialTheme.typography.bodySmall.copy(lineHeight = MaterialTheme.typography.bodySmall.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EventDetailSectionCard(
    section: EventSection,
    isEnrichingSections: Boolean,
    onEnrichSections: () -> Unit,
    enableDetailItemClick: Boolean,
    onDetailItemClick: (EventDetailItem) -> Unit
) {
    val resolved = remember(section) { EventDetailItemParser.resolveSectionDisplay(section) }
    val safeHeading = remember(section.heading) {
        EventDetailItemParser.sanitizeSingleLine(section.heading, EventDetailItemParser.MAX_SECTION_HEADING_CHARS)
    }
    val canEnrichWithAi = remember(section, resolved.items.size) {
        EventDetailItemParser.hasUnstructuredBullets(section) || resolved.items.isNotEmpty()
    }
    if (safeHeading.isBlank() && resolved.items.isEmpty() && resolved.remainingContent.isBlank()) {
        return
    }
    val hasBulletCards = resolved.items.isNotEmpty()
    val sectionContainerModifier = if (hasBulletCards) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    }
    Column(modifier = sectionContainerModifier) {
        val hasHeaderRow = safeHeading.isNotBlank() || canEnrichWithAi
        if (hasHeaderRow) {
            Row(
                modifier = if (hasBulletCards) Modifier.fillMaxWidth().padding(horizontal = 4.dp) else Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (safeHeading.isNotBlank()) {
                    Text(
                        text = safeHeading,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (canEnrichWithAi) {
                    if (isEnrichingSections) {
                        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(onClick = onEnrichSections, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Structure detail cards with AI",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        if (hasBulletCards) {
            if (hasHeaderRow) Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                resolved.items.forEachIndexed { index, item ->
                    DetailMetadataRow(
                        item = item,
                        shape = segmentedItemShape(index = index, totalCount = resolved.items.size),
                        enabled = enableDetailItemClick,
                        onClick = { onDetailItemClick(item) }
                    )
                }
            }
        }
        if (resolved.remainingContent.isNotBlank()) {
            when {
                hasBulletCards -> Spacer(Modifier.height(10.dp))
                hasHeaderRow -> Spacer(Modifier.height(4.dp))
            }
            Text(
                text = resolved.remainingContent,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (hasBulletCards) Modifier.padding(horizontal = 4.dp) else Modifier
            )
        }
    }
}

@Composable
private fun DetailMetadataRow(
    item: EventDetailItem,
    shape: RoundedCornerShape,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val visuals = detailItemVisuals(item)
    val labelText = item.label.ifBlank { EventDetailItemParser.defaultLabel(item.kind) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .let { mod -> if (enabled) mod.clickable(onClick = onClick) else mod }
            .semantics(mergeDescendants = true) {},
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = visuals.badgeContainerColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = visuals.icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = visuals.badgeIconColor
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelMedium.copy(lineHeight = MaterialTheme.typography.labelMedium.fontSize),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
internal fun Modifier.shimmerOverlay(enabled: Boolean, highlightColor: Color): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition()
    val progress by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    return this.drawWithContent {
        drawContent()
        val bandWidth = size.width * 0.55f
        val startX = (size.width * progress) - bandWidth * 0.5f
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, highlightColor, Color.Transparent),
                start = Offset(startX, 0f),
                end = Offset(startX + bandWidth, size.height)
            )
        )
    }
}

@Composable
private fun EventDetailSourceMessagesBox(
    bubbles: List<SourceMessageBubble>,
    highlightedBubbleIndex: Int?,
    isTranslatingMessages: Boolean,
    isShowingTranslation: Boolean,
    onClearHighlight: () -> Unit,
    onShowContextDialog: () -> Unit,
    onTranslateAction: () -> Unit
) {
    val headingText = when {
        isTranslatingMessages -> "Source Messages (Translating…)"
        isShowingTranslation -> "Source Messages (Translated)"
        else -> "Source Messages"
    }
    val textColor = if (isTranslatingMessages) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = headingText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onShowContextDialog,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Icon(Icons.Default.Forum, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("±5 Context", style = MaterialTheme.typography.labelSmall)
            }
            TranslationActionButton(
                isTranslating = isTranslatingMessages,
                isShowingTranslation = isShowingTranslation,
                onClick = onTranslateAction,
                buttonSizeDp = 28,
                iconSizeDp = 16
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shimmerOverlay(
                    enabled = isTranslatingMessages,
                    highlightColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            bubbles.forEach { bubble ->
                val isHighlighted = bubble.index == highlightedBubbleIndex
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .let { mod -> if (isHighlighted) mod.clickable(onClick = onClearHighlight) else mod },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isHighlighted) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
                    border = if (isHighlighted) {
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (bubble.sender.isNotBlank() || bubble.timestamp.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (bubble.sender.isNotBlank()) {
                                    Text(
                                        text = bubble.sender,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (bubble.isOwner) {
                                    DetailBadgeChip(
                                        text = "Owner",
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        textColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                if (bubble.timestamp.isNotBlank()) {
                                    Text(
                                        text = bubble.timestamp,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Text(
                            text = bubble.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickEditCardDialog(
    card: EventCard,
    onSave: (title: String, category: EventCategory, priority: EventPriority, eventDate: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(card.title) }
    var category by remember { mutableStateOf(card.category) }
    var priority by remember { mutableStateOf(card.priority) }
    var eventDate by remember { mutableStateOf(card.eventDate.orEmpty()) }
    val dateError = remember(eventDate) { validateQuickEditIsoDate(eventDate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick Edit Notice") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = eventDate,
                    onValueChange = { eventDate = it },
                    label = { Text("Event Date (YYYY-MM-DD or empty)") },
                    isError = dateError != null,
                    supportingText = dateError?.let { err -> { Text(err) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Priority", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EventPriority.entries.forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(formatPriorityLabel(p)) }
                        )
                    }
                }
                Text("Category", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EventCategory.entries.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(formatCategoryLabel(c)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && dateError == null,
                onClick = { onSave(title, category, priority, eventDate.takeIf { it.isNotBlank() }) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

internal fun validateQuickEditIsoDate(rawDate: String?): String? {
    val trimmed = rawDate?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val isDate = runCatching { java.time.LocalDate.parse(trimmed) }.isSuccess
    if (isDate) return null
    val normalized = trimmed.replace(' ', 'T')
    val isDateTime = runCatching {
        when (normalized.length) {
            16 -> java.time.LocalDateTime.parse("$normalized:00")
            else -> java.time.LocalDateTime.parse(normalized)
        }
    }.isSuccess
    return if (isDateTime) null else "Use YYYY-MM-DD or YYYY-MM-DDTHH:MM"
}

@Composable
private fun ContextMessagesDialog(
    card: EventCard,
    onLoadContextMessages: suspend () -> Result<WeChatMessageRepository.ContextMessagesResult>,
    onDismiss: () -> Unit
) {
    val contextState by produceState<Result<WeChatMessageRepository.ContextMessagesResult>?>(
        initialValue = null,
        key1 = card.id
    ) {
        value = onLoadContextMessages()
    }
    val formatter = remember {
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Surrounding Chat Context (±5)") },
        text = {
            val loaded = contextState
            when {
                loaded == null -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                loaded.isFailure -> {
                    Text(
                        text = "Unable to load surrounding messages: ${loaded.exceptionOrNull()?.message ?: "Unknown error"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    val result = loaded.getOrThrow()
                    if (result.messages.isEmpty()) {
                        Text(
                            text = "No surrounding messages found in local WeChat history.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = result.messages,
                                key = { msg -> "${msg.timestampSeconds}:${msg.localId}:${msg.senderId}:${msg.rawContent.hashCode()}" }
                            ) { msg ->
                                val isAnchor = WeChatMessageRepository.contextKey(msg) in result.anchorFingerprints
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isAnchor) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    border = if (isAnchor) {
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                    } else {
                                        null
                                    }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(
                                                text = msg.senderDisplayName.ifBlank { msg.senderId },
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = formatter.format(Instant.ofEpochSecond(msg.timestampSeconds)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = msg.textContent.ifBlank { "[Media/Attachment]" },
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun EventDetailCalendarFooter(onAddToCalendar: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = onAddToCalendar) {
            Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text = "Add to Calendar", lineHeight = MaterialTheme.typography.labelLarge.fontSize)
        }
    }
}

@Composable
private fun EventDetailNavigationBar(
    currentIndex: Int,
    totalVisibleCount: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onSelectPrevious: () -> Unit,
    onSelectNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSelectPrevious, enabled = hasPrevious, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous notice", modifier = Modifier.size(20.dp))
            }
            Text(
                text = "${currentIndex + 1} / $totalVisibleCount",
                modifier = Modifier.padding(horizontal = 6.dp),
                style = MaterialTheme.typography.labelMedium.copy(lineHeight = MaterialTheme.typography.labelMedium.fontSize),
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onSelectNext, enabled = hasNext, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next notice", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun AttachmentsSection(
    attachments: List<EventAttachment>,
    isRechecking: Boolean,
    onOpenAttachment: (EventAttachment) -> Unit,
    onRevealInFinder: (EventAttachment) -> Unit,
    onRecheck: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (attachments.size == 1) "Attachment" else "Attachments (${attachments.size})",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            attachments.forEachIndexed { index, attachment ->
                key(attachment.previewKey()) {
                    AttachmentRow(
                        attachment = attachment,
                        shape = segmentedItemShape(index = index, totalCount = attachments.size),
                        isRechecking = isRechecking,
                        onOpen = { onOpenAttachment(attachment) },
                        onRevealInFinder = { onRevealInFinder(attachment) },
                        onRecheck = onRecheck
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: EventAttachment,
    shape: RoundedCornerShape,
    isRechecking: Boolean,
    onOpen: () -> Unit,
    onRevealInFinder: () -> Unit,
    onRecheck: () -> Unit
) {
    val isPresent by produceState(initialValue = false, attachment.previewKey(), attachment.localPath, isRechecking) {
        value = withContext(Dispatchers.IO) { attachment.isLocalFilePresent() }
    }
    val isExpired = remember(attachment.mediaExpireAtSeconds) { attachment.isExpired() }
    val sizeText = remember(attachment.fileSizeBytes) { formatFileSizeLabel(attachment.fileSizeBytes) }
    val statusText = when {
        isPresent -> "$sizeText • Downloaded"
        isExpired -> "$sizeText • Expired in WeChat"
        else -> "$sizeText • Open in WeChat, then Re-check"
    }

    Surface(modifier = Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AttachmentRowLeadingIcon(fileExtension = attachment.fileExtension, isPresent = isPresent)
            Spacer(Modifier.width(12.dp))
            AttachmentRowMetadata(fileName = attachment.fileName, statusText = statusText, isExpiredError = isExpired && !isPresent, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AttachmentRowActions(isPresent = isPresent, isRechecking = isRechecking, onOpen = onOpen, onRevealInFinder = onRevealInFinder, onRecheck = onRecheck)
        }
    }
}

@Composable
private fun AttachmentRowLeadingIcon(fileExtension: String, isPresent: Boolean) {
    val fileIcon = when (fileExtension.lowercase(Locale.ROOT)) {
        "xlsx", "xls", "csv", "tsv" -> Icons.Default.TableChart
        "pdf" -> Icons.Default.PictureAsPdf
        else -> Icons.Default.Description
    }
    val containerTint = if (isPresent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val iconTint = if (isPresent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(modifier = Modifier.size(36.dp), shape = CircleShape, color = containerTint) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = fileIcon, contentDescription = null, modifier = Modifier.size(18.dp), tint = iconTint)
        }
    }
}

@Composable
private fun AttachmentRowMetadata(fileName: String, statusText: String, isExpiredError: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.titleSmall.copy(lineHeight = MaterialTheme.typography.titleSmall.fontSize),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = MaterialTheme.typography.bodySmall.fontSize),
            color = if (isExpiredError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AttachmentRowActions(
    isPresent: Boolean,
    isRechecking: Boolean,
    onOpen: () -> Unit,
    onRevealInFinder: () -> Unit,
    onRecheck: () -> Unit
) {
    if (isPresent) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpen, modifier = Modifier.size(34.dp)) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open attachment", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onRevealInFinder, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Reveal in Finder", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    OutlinedButton(onClick = onRecheck, enabled = !isRechecking, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = Modifier.height(32.dp)) {
        if (isRechecking) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(text = if (isRechecking) "Checking..." else "Re-check", style = MaterialTheme.typography.labelMedium.copy(lineHeight = MaterialTheme.typography.labelMedium.fontSize))
    }
}
