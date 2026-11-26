package com.wechatdigest.core.attachment

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeChatXmlMessageParserTest {

    @Test
    fun `parses quote replies, nested refermsg XML, and bidi sanitization safely`() {
        val quoteType = (57L shl 32) or 49L
        val quoteXml = """
            <msg>
                <appmsg>
                    <title>Got it, room changed to 305!</title>
                    <type>57</type>
                    <refermsg>
                        <type>49</type>
                        <svrid>88001</svrid>
                        <displayname>Prof&#8238;Lin</displayname>
                        <content>&lt;msg&gt;&lt;appmsg&gt;&lt;title&gt;schedule.xlsx&lt;/title&gt;&lt;type&gt;6&lt;/type&gt;&lt;appattach&gt;&lt;totallen&gt;2048&lt;/totallen&gt;&lt;/appattach&gt;&lt;/appmsg&gt;&lt;/msg&gt;</content>
                    </refermsg>
                </appmsg>
            </msg>
        """.trimIndent()

        val parsed = WeChatXmlMessageParser.parseMessage(quoteXml, quoteType, 1795000000L)
        val quote = assertIs<ParsedWeChatMessage.QuoteReply>(parsed)
        assertEquals("Got it, room changed to 305!", quote.replyText)
        assertEquals(88001L, quote.quotedServerId)
        assertFalseControlOrBidi(quote.quoteSummary)
        assertTrue(quote.quoteSummary.contains("Shared File: schedule.xlsx"))
        // File-only parser contract stays null for subtype 57
        assertNull(WeChatXmlMessageParser.parseFileMessage(quoteXml, 1795000000L))
    }

    @Test
    fun `parses links, mini-programs ignoring waerrpage, forwarded recorditem, and group announcements`() {
        val linkXml = """
            <msg><appmsg>
                <title>2026 Exam Schedule</title>
                <des>Updated dates for final exams</des>
                <type>5</type>
                <url>https://edu.example.org/exams?term=fall</url>
                <sourcedisplayname>Academic Office</sourcedisplayname>
            </appmsg></msg>
        """.trimIndent()
        val link = assertIs<ParsedWeChatMessage.LinkCard>(
            WeChatXmlMessageParser.parseMessage(linkXml, 49L, 1000L)
        )
        assertEquals("https://edu.example.org/exams?term=fall", link.url)
        assertTrue(link.displayText.contains("[Link: Academic Office] 2026 Exam Schedule"))

        val unsafeLinkXml = "<msg><appmsg><title>Click</title><type>5</type><url>javascript:alert(1)</url></appmsg></msg>"
        val unsafeLink = assertIs<ParsedWeChatMessage.LinkCard>(
            WeChatXmlMessageParser.parseMessage(unsafeLinkXml, 49L, 1000L)
        )
        assertNull(unsafeLink.url)

        val miniXml = """
            <msg><appmsg>
                <title>Lab Safety Quiz Form</title>
                <des>Submit before Friday</des>
                <type>33</type>
                <url>https://mp.weixin.qq.com/mp/waerrpage?appid=wx123</url>
                <sourcedisplayname>问卷星</sourcedisplayname>
                <weappinfo><pagepath>pages/quiz/index</pagepath></weappinfo>
            </appmsg></msg>
        """.trimIndent()
        val mini = assertIs<ParsedWeChatMessage.MiniProgram>(
            WeChatXmlMessageParser.parseMessage(miniXml, (33L shl 32) or 49L, 1000L)
        )
        assertEquals("问卷星", mini.appName)
        assertEquals("pages/quiz/index", mini.pagePath)
        assertTrue(mini.displayText.contains("[MiniProgram: 问卷星] Lab Safety Quiz Form"))
        assertTrue(!mini.displayText.contains("waerrpage"))

        val forwardXml = """
            <msg><appmsg>
                <title>Faculty Notice History</title>
                <des>Fallback preview</des>
                <type>19</type>
                <recorditem><![CDATA[<recordinfo><title>History</title><datalist count="2"><dataitem datatype="1"><sourcename>Dean</sourcename><sourcetime>10:00</sourcetime><datadesc>Mandatory assembly tomorrow at 09:00</datadesc></dataitem><dataitem datatype="1"><sourcename>Secretary</sourcename><sourcetime>10:05</sourcetime><datadesc>Hall B-201</datadesc></dataitem></datalist></recordinfo>]]></recorditem>
            </appmsg></msg>
        """.trimIndent()
        val forward = assertIs<ParsedWeChatMessage.ForwardedRecord>(
            WeChatXmlMessageParser.parseMessage(forwardXml, 49L, 1000L)
        )
        assertTrue(forward.displayText.contains("Mandatory assembly tomorrow at 09:00"))
        assertTrue(forward.displayText.contains("Hall B-201"))

        val noticeXml = """
            <msg><appmsg>
                <title>@所有人 Short header</title>
                <textannouncement>@所有人 Full midterm notice: Nov 29 at 14:00 in Building C.</textannouncement>
                <type>87</type>
            </appmsg></msg>
        """.trimIndent()
        val notice = assertIs<ParsedWeChatMessage.GroupAnnouncement>(
            WeChatXmlMessageParser.parseMessage(noticeXml, (87L shl 32) or 49L, 1000L)
        )
        assertTrue(notice.displayText.contains("[Group Announcement]"))
        assertTrue(notice.displayText.contains("Building C"))
    }

    @Test
    fun `parses revoke sysmsg, ignores 10000 sysmsg, and blocks XXE doctypes in appmsg and recorditem`() {
        val revokeXml = """<sysmsg type="revokemsg"><revokemsg><msgid>123</msgid><newmsgid>987654321</newmsgid><replacemsg><![CDATA["Alice" recalled a message]]></replacemsg></revokemsg></sysmsg>"""
        val revoke = assertIs<ParsedWeChatMessage.RevokeSysMsg>(
            WeChatXmlMessageParser.parseMessage(revokeXml, 10002L, 1000L)
        )
        assertEquals(987654321L, revoke.recalledServerId)

        assertIs<ParsedWeChatMessage.IgnoredSysMsg>(
            WeChatXmlMessageParser.parseMessage("Bob invited Carol to the group", 10000L, 1000L)
        )

        val xxeRecordItemXml = """
            <msg><appmsg>
                <title>History</title>
                <des>Safe fallback preview</des>
                <type>19</type>
                <recorditem><![CDATA[<!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]><recordinfo><datalist><dataitem><datadesc>&xxe;</datadesc></dataitem></datalist></recordinfo>]]></recorditem>
            </appmsg></msg>
        """.trimIndent()
        val forwardFallback = assertIs<ParsedWeChatMessage.ForwardedRecord>(
            WeChatXmlMessageParser.parseMessage(xxeRecordItemXml, 49L, 1000L)
        )
        assertEquals("[Forwarded: History]\nSafe fallback preview", forwardFallback.displayText)

        // Plain-text localType=1 <sysmsg revokemsg> must NOT parse as recall when allowLegacyXmlFallback=false
        assertNull(
            WeChatXmlMessageParser.parseMessage(revokeXml, localType = 1L, timestampSeconds = 1000L, allowLegacyXmlFallback = false)
        )

        // Multi-line quote reply preserves newlines, and empty <textannouncement/> falls back to <announcement>
        val multilineQuote = """<msg><appmsg><title>Line 1
Line 2</title><type>57</type></appmsg></msg>"""
        val qMulti = assertIs<ParsedWeChatMessage.QuoteReply>(
            WeChatXmlMessageParser.parseMessage(multilineQuote, (57L shl 32) or 49L, 1000L)
        )
        assertEquals("Line 1\nLine 2", qMulti.replyText)

        val emptyTextAnn = """<msg><appmsg><title>Fallback</title><textannouncement/><announcement>Real notice body</announcement><type>87</type></appmsg></msg>"""
        val ann = assertIs<ParsedWeChatMessage.GroupAnnouncement>(
            WeChatXmlMessageParser.parseMessage(emptyTextAnn, (87L shl 32) or 49L, 1000L)
        )
        assertEquals("Real notice body", ann.body)
    }

    private fun assertFalseControlOrBidi(text: String) {
        assertTrue(text.none { it == '\u202E' || it == '\u202D' || it == '\u0000' })
    }
}
