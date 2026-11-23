package com.wechatdigest.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContactMail
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Diversity3
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.wechatdigest.core.model.EventDetailItem
import com.wechatdigest.core.model.EventDetailItemParser
import com.wechatdigest.core.model.EventDetailKind

internal data class DetailTileVisuals(
    val icon: ImageVector,
    val badgeContainerColor: Color,
    val badgeIconColor: Color
)

private enum class IconTone {
    PRIMARY,
    SECONDARY,
    TERTIARY,
    ALERT,
    NEUTRAL
}

private data class IconSpec(
    val vector: ImageVector,
    val tone: IconTone
)

private val iconCatalogBySlug: Map<String, IconSpec> = mapOf(
    // Dates, Schedules & Deadlines (12)
    "calendar_today" to IconSpec(Icons.Default.CalendarToday, IconTone.PRIMARY),
    "event" to IconSpec(Icons.Default.Event, IconTone.PRIMARY),
    "date_range" to IconSpec(Icons.Default.DateRange, IconTone.PRIMARY),
    "event_available" to IconSpec(Icons.Default.EventAvailable, IconTone.SECONDARY),
    "schedule" to IconSpec(Icons.Default.Schedule, IconTone.PRIMARY),
    "access_time" to IconSpec(Icons.Default.AccessTime, IconTone.PRIMARY),
    "alarm" to IconSpec(Icons.Default.Alarm, IconTone.ALERT),
    "timer" to IconSpec(Icons.Default.Timer, IconTone.TERTIARY),
    "hourglass_bottom" to IconSpec(Icons.Default.HourglassBottom, IconTone.ALERT),
    "event_busy" to IconSpec(Icons.Default.EventBusy, IconTone.ALERT),
    "update" to IconSpec(Icons.Default.Update, IconTone.SECONDARY),
    "history" to IconSpec(Icons.Default.History, IconTone.NEUTRAL),
    // Locations, Campuses, Rooms & Travel (11)
    "location_on" to IconSpec(Icons.Default.LocationOn, IconTone.SECONDARY),
    "meeting_room" to IconSpec(Icons.Default.MeetingRoom, IconTone.SECONDARY),
    "apartment" to IconSpec(Icons.Default.Apartment, IconTone.SECONDARY),
    "school" to IconSpec(Icons.Default.School, IconTone.PRIMARY),
    "map" to IconSpec(Icons.Default.Map, IconTone.SECONDARY),
    "directions" to IconSpec(Icons.Default.Directions, IconTone.SECONDARY),
    "directions_bus" to IconSpec(Icons.Default.DirectionsBus, IconTone.TERTIARY),
    "flight" to IconSpec(Icons.Default.Flight, IconTone.PRIMARY),
    "hotel" to IconSpec(Icons.Default.Hotel, IconTone.TERTIARY),
    "public" to IconSpec(Icons.Default.Public, IconTone.PRIMARY),
    "video_call" to IconSpec(Icons.Default.VideoCall, IconTone.PRIMARY),
    // Documents, Forms, Copies & Formats (12)
    "description" to IconSpec(Icons.Default.Description, IconTone.SECONDARY),
    "article" to IconSpec(Icons.AutoMirrored.Filled.Article, IconTone.SECONDARY),
    "assignment" to IconSpec(Icons.AutoMirrored.Filled.Assignment, IconTone.PRIMARY),
    "folder" to IconSpec(Icons.Default.Folder, IconTone.SECONDARY),
    "attach_file" to IconSpec(Icons.Default.AttachFile, IconTone.NEUTRAL),
    "badge" to IconSpec(Icons.Default.Badge, IconTone.TERTIARY),
    "content_copy" to IconSpec(Icons.Default.ContentCopy, IconTone.SECONDARY),
    "print" to IconSpec(Icons.Default.Print, IconTone.SECONDARY),
    "picture_as_pdf" to IconSpec(Icons.Default.PictureAsPdf, IconTone.ALERT),
    "photo_camera" to IconSpec(Icons.Default.PhotoCamera, IconTone.TERTIARY),
    "qr_code" to IconSpec(Icons.Default.QrCode2, IconTone.NEUTRAL),
    "draw" to IconSpec(Icons.Default.Draw, IconTone.TERTIARY),
    // Eligibility, Audience & Rules (14)
    "verified_user" to IconSpec(Icons.Default.VerifiedUser, IconTone.SECONDARY),
    "how_to_reg" to IconSpec(Icons.Default.HowToReg, IconTone.PRIMARY),
    "check_circle" to IconSpec(Icons.Default.CheckCircle, IconTone.SECONDARY),
    "checklist" to IconSpec(Icons.Default.Checklist, IconTone.PRIMARY),
    "fact_check" to IconSpec(Icons.AutoMirrored.Filled.FactCheck, IconTone.SECONDARY),
    "workspace_premium" to IconSpec(Icons.Default.WorkspacePremium, IconTone.TERTIARY),
    "groups" to IconSpec(Icons.Default.Groups, IconTone.SECONDARY),
    "diversity_3" to IconSpec(Icons.Default.Diversity3, IconTone.TERTIARY),
    "gavel" to IconSpec(Icons.Default.Gavel, IconTone.NEUTRAL),
    "rule" to IconSpec(Icons.AutoMirrored.Filled.Rule, IconTone.NEUTRAL),
    "policy" to IconSpec(Icons.Default.Policy, IconTone.NEUTRAL),
    "lock" to IconSpec(Icons.Default.Lock, IconTone.ALERT),
    "security" to IconSpec(Icons.Default.Security, IconTone.SECONDARY),
    "balance" to IconSpec(Icons.Default.Balance, IconTone.NEUTRAL),
    // Exams, Courses & Academic Milestones (9)
    "quiz" to IconSpec(Icons.Default.Quiz, IconTone.PRIMARY),
    "menu_book" to IconSpec(Icons.AutoMirrored.Filled.MenuBook, IconTone.SECONDARY),
    "edit_note" to IconSpec(Icons.Default.EditNote, IconTone.SECONDARY),
    "grade" to IconSpec(Icons.Default.Grade, IconTone.TERTIARY),
    "psychology" to IconSpec(Icons.Default.Psychology, IconTone.TERTIARY),
    "science" to IconSpec(Icons.Default.Science, IconTone.PRIMARY),
    "biotech" to IconSpec(Icons.Default.Biotech, IconTone.SECONDARY),
    "calculate" to IconSpec(Icons.Default.Calculate, IconTone.NEUTRAL),
    "translate" to IconSpec(Icons.Default.Translate, IconTone.PRIMARY),
    // Contacts & People (8)
    "contact_mail" to IconSpec(Icons.Default.ContactMail, IconTone.SECONDARY),
    "email" to IconSpec(Icons.Default.Email, IconTone.SECONDARY),
    "phone" to IconSpec(Icons.Default.Phone, IconTone.SECONDARY),
    "forum" to IconSpec(Icons.Default.Forum, IconTone.PRIMARY),
    "person" to IconSpec(Icons.Default.Person, IconTone.NEUTRAL),
    "supervisor_account" to IconSpec(Icons.Default.SupervisorAccount, IconTone.PRIMARY),
    "support_agent" to IconSpec(Icons.Default.SupportAgent, IconTone.TERTIARY),
    "campaign" to IconSpec(Icons.Default.Campaign, IconTone.TERTIARY),
    // Links, Portals & Actions (10)
    "link" to IconSpec(Icons.Default.Link, IconTone.PRIMARY),
    "open_in_new" to IconSpec(Icons.AutoMirrored.Filled.OpenInNew, IconTone.PRIMARY),
    "language" to IconSpec(Icons.Default.Language, IconTone.PRIMARY),
    "cloud_upload" to IconSpec(Icons.Default.CloudUpload, IconTone.PRIMARY),
    "download" to IconSpec(Icons.Default.Download, IconTone.SECONDARY),
    "touch_app" to IconSpec(Icons.Default.TouchApp, IconTone.PRIMARY),
    "send" to IconSpec(Icons.AutoMirrored.Filled.Send, IconTone.PRIMARY),
    "assignment_turned_in" to IconSpec(Icons.Default.AssignmentTurnedIn, IconTone.SECONDARY),
    "drive_file_rename_outline" to IconSpec(Icons.Default.DriveFileRenameOutline, IconTone.NEUTRAL),
    "archive" to IconSpec(Icons.Default.Archive, IconTone.NEUTRAL),
    // Warnings, Health, Food, Payments & Logistics (20)
    "warning" to IconSpec(Icons.Default.WarningAmber, IconTone.ALERT),
    "report_problem" to IconSpec(Icons.Default.ReportProblem, IconTone.ALERT),
    "priority_high" to IconSpec(Icons.Default.PriorityHigh, IconTone.ALERT),
    "block" to IconSpec(Icons.Default.Block, IconTone.ALERT),
    "payments" to IconSpec(Icons.Default.Payments, IconTone.TERTIARY),
    "account_balance" to IconSpec(Icons.Default.AccountBalance, IconTone.SECONDARY),
    "receipt_long" to IconSpec(Icons.AutoMirrored.Filled.ReceiptLong, IconTone.TERTIARY),
    "credit_card" to IconSpec(Icons.Default.CreditCard, IconTone.PRIMARY),
    "laptop" to IconSpec(Icons.Default.Laptop, IconTone.NEUTRAL),
    "devices" to IconSpec(Icons.Default.Devices, IconTone.NEUTRAL),
    "wifi" to IconSpec(Icons.Default.Wifi, IconTone.PRIMARY),
    "mic" to IconSpec(Icons.Default.Mic, IconTone.TERTIARY),
    "medical_services" to IconSpec(Icons.Default.MedicalServices, IconTone.ALERT),
    "restaurant" to IconSpec(Icons.Default.Restaurant, IconTone.TERTIARY),
    "inventory_2" to IconSpec(Icons.Default.Inventory2, IconTone.NEUTRAL),
    "card_giftcard" to IconSpec(Icons.Default.CardGiftcard, IconTone.TERTIARY),
    "info" to IconSpec(Icons.Default.Info, IconTone.NEUTRAL),
    "lightbulb" to IconSpec(Icons.Default.Lightbulb, IconTone.TERTIARY),
    "bookmark" to IconSpec(Icons.Default.Bookmark, IconTone.SECONDARY),
    "star" to IconSpec(Icons.Default.Star, IconTone.TERTIARY)
)

@Composable
internal fun detailItemVisuals(item: EventDetailItem): DetailTileVisuals {
    val resolvedSlug = EventDetailItemParser.normalizeIconSlug(item.icon)
        ?: defaultSlugForKind(item.kind)
    val spec = iconCatalogBySlug[resolvedSlug] ?: IconSpec(Icons.Default.Info, IconTone.NEUTRAL)
    val scheme = MaterialTheme.colorScheme
    return when (spec.tone) {
        IconTone.PRIMARY -> DetailTileVisuals(spec.vector, scheme.primaryContainer.copy(alpha = 0.72f), scheme.onPrimaryContainer)
        IconTone.SECONDARY -> DetailTileVisuals(spec.vector, scheme.secondaryContainer.copy(alpha = 0.85f), scheme.onSecondaryContainer)
        IconTone.TERTIARY -> DetailTileVisuals(spec.vector, scheme.tertiaryContainer.copy(alpha = 0.78f), scheme.onTertiaryContainer)
        IconTone.ALERT -> DetailTileVisuals(spec.vector, scheme.errorContainer.copy(alpha = 0.65f), scheme.onErrorContainer)
        IconTone.NEUTRAL -> DetailTileVisuals(spec.vector, scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
    }
}

private fun defaultSlugForKind(kind: EventDetailKind): String = when (kind) {
    EventDetailKind.DATE -> "calendar_today"
    EventDetailKind.TIME -> "schedule"
    EventDetailKind.DEADLINE -> "hourglass_bottom"
    EventDetailKind.LOCATION -> "location_on"
    EventDetailKind.DOCUMENT -> "description"
    EventDetailKind.COPIES -> "print"
    EventDetailKind.FORMAT -> "drive_file_rename_outline"
    EventDetailKind.ELIGIBILITY -> "verified_user"
    EventDetailKind.EXAM -> "quiz"
    EventDetailKind.CONTACT -> "email"
    EventDetailKind.PERSON -> "person"
    EventDetailKind.AUDIENCE -> "groups"
    EventDetailKind.LINK -> "link"
    EventDetailKind.ACTION -> "check_circle"
    EventDetailKind.REQUIREMENT -> "checklist"
    EventDetailKind.RULE -> "gavel"
    EventDetailKind.WARNING -> "warning"
    EventDetailKind.PAYMENT -> "payments"
    EventDetailKind.DEVICE -> "laptop"
    EventDetailKind.OTHER -> "info"
}
