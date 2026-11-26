package com.wechatdigest.core.sanitizer

import com.wechatdigest.core.model.WeChatMessage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SolitaireCollapserAndSanitizerPhase2Test {

    @Test
    fun `MessageSanitizer drops sysmsg, suppresses recalled targets, gates short quote replies, and collapses same-topic #接龙 chains`() {
        val messages = listOf(
            // Will be recalled by serverId 5001
            WeChatMessage(1L, 1000L, "wxid_prof", "Prof", "Old typo: exam is cancelled.", "Old typo: exam is cancelled.", false, localType = 1L, serverId = 5001L),
            // Plain sysmsg 10000
            WeChatMessage(2L, 1010L, "sys", "sys", "Student_A joined the group", "Student_A joined the group", false, localType = 10000L),
            // Revoke sysmsg targeting 5001
            WeChatMessage(3L, 1020L, "sys", "sys", "<sysmsg type=\"revokemsg\"/>", "<sysmsg type=\"revokemsg\"/>", false, localType = 10002L, isSystemMessage = true, recalledServerId = 5001L),
            // Casual "+1" quote-reply quoting a long notice must still be dropped because reply body itself is non-substantive
            WeChatMessage(4L, 1030L, "wxid_s1", "Student_1", "<msg/>", "收到", false, localType = (57L shl 32) or 49L, quoteContext = "Replying to Prof: Final exam is Nov 30 at 09:00 in Hall 101"),
            // Substantive quote-reply must be kept
            WeChatMessage(5L, 1040L, "wxid_prof", "Prof", "<msg/>", "Room actually moved from 101 to 205.", false, localType = (57L shl 32) or 49L, quoteContext = "Replying to Prof: Final exam is Nov 30 at 09:00 in Hall 101"),
            // Same-topic #接龙 check-in updates (3 snapshots -> keep only latest bounded)
            WeChatMessage(6L, 1050L, "wxid_s1", "S1", "", "#接龙 Friday Lab Attendance\n1. Alice", false),
            WeChatMessage(7L, 1060L, "wxid_s2", "S2", "", "#接龙 Friday Lab Attendance\n1. Alice\n2. Bob", false),
            WeChatMessage(
                8L, 1070L, "wxid_s3", "S3", "",
                buildString {
                    append("#接龙 Friday Lab Attendance\n")
                    for (i in 1..14) append("$i. Student_$i\n")
                }.trimEnd(),
                false
            ),
            // Distinct #接龙 topic must NOT collapse into Friday Lab Attendance
            WeChatMessage(9L, 1080L, "wxid_s4", "S4", "", "#接龙 Thesis Defense Bus Booking\n1. Carol", false)
        )

        val surviving = MessageSanitizer.filterSubstantiveMessages(messages)
        assertEquals(listOf(5L, 8L, 9L), surviving.map { it.localId })

        val collapsedLab = surviving[1]
        assertTrue(collapsedLab.textContent.contains("10. Student_10"))
        assertFalse(collapsedLab.textContent.contains("11. Student_11"), "Roster should cap displayed entries")
        assertTrue(collapsedLab.textContent.contains("collapsed 2 prior check-in update(s)"))
        assertEquals(1070L, collapsedLab.timestampSeconds)

        val distinctBus = surviving[2]
        assertTrue(distinctBus.textContent.contains("Thesis Defense Bus Booking"))
        assertTrue(distinctBus.textContent.contains("1. Carol"))
    }

    @Test
    fun `numeric-leading #接龙 headers stay distinct and sysmsg recalls run before allowlist filtering`() {
        val numericChains = listOf(
            WeChatMessage(1L, 1000L, "s1", "S1", "", "#接龙\n25 May Lab Sign-Up\n1. Alice", false),
            WeChatMessage(2L, 1010L, "s2", "S2", "", "#接龙\n10 min shuttle booking\n1. Bob", false)
        )
        val collapsed = MessageSanitizer.filterSubstantiveMessages(numericChains)
        assertEquals(listOf(1L, 2L), collapsed.map { it.localId }, "Distinct numeric-header chains must not collide")

        val recalledWithAllowlist = listOf(
            WeChatMessage(10L, 1000L, "wxid_prof", "Prof", "Old typo notice", "Old typo notice", false, localType = 1L, serverId = 777L),
            WeChatMessage(11L, 1010L, "sys", "sys", "<sysmsg/>", "<sysmsg/>", false, localType = 10002L, isSystemMessage = true, recalledServerId = 777L),
            WeChatMessage(12L, 1020L, "wxid_prof", "Prof", "Final updated notice", "Final updated notice", false, localType = 1L, serverId = 778L)
        )
        val preCleaned = MessageSanitizer.suppressSystemAndRecalledMessages(recalledWithAllowlist)
            .filter { it.senderId == "wxid_prof" }
        assertEquals(listOf(12L), preCleaned.map { it.localId })
    }
}
