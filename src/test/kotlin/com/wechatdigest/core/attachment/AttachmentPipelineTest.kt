package com.wechatdigest.core.attachment

import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.model.EventAttachment
import com.wechatdigest.core.model.WeChatMessage
import com.wechatdigest.core.sanitizer.MessageSanitizer
import com.wechatdigest.core.sanitizer.PrefilterConfig
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentPipelineTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `parseFileMessage extracts packed local_type file XML and ignores quote replies`() {
        val packedFileType = (6L shl 32) or 49L
        val fileXml = """
            wxid_teacher:
            <?xml version="1.0"?>
            <msg>
                <appmsg appid="" sdkver="0">
                    <title>final_exam_seats.xlsx</title>
                    <type>6</type>
                    <appattach>
                        <totallen>18432</totallen>
                        <fileext>xlsx</fileext>
                    </appattach>
                    <md5>0123456789abcdef0123456789abcdef</md5>
                </appmsg>
                <extcommoninfo>
                    <media_expire_at>1893456000</media_expire_at>
                </extcommoninfo>
            </msg>
        """.trimIndent()

        assertTrue(WeChatFileMessageParser.isCandidateFileMessage(packedFileType, fileXml))
        val parsed = WeChatFileMessageParser.parseFileMessage(fileXml, 1795000000L)
        assertNotNull(parsed)
        assertEquals("final_exam_seats.xlsx", parsed.fileName)
        assertEquals("xlsx", parsed.fileExtension)
        assertEquals(18432L, parsed.fileSizeBytes)
        assertEquals("0123456789abcdef0123456789abcdef", parsed.md5)
        assertEquals(1893456000L, parsed.mediaExpireAtSeconds)
        assertEquals("[Shared File: final_exam_seats.xlsx (18 KB)]", WeChatFileMessageParser.formatAttachmentMarker(parsed))

        // Quote reply (<type>57</type>) must return null
        val quoteXml = "<msg><appmsg><title>reply</title><type>57</type></appmsg></msg>"
        assertNull(WeChatFileMessageParser.parseFileMessage(quoteXml, 1795000000L))
    }

    @Test
    fun `parseFileMessage blocks XXE doctype and sanitizes path traversal filenames`() {
        val xxeXml = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <msg><appmsg><title>notice.txt</title><type>6</type></appmsg></msg>
        """.trimIndent()
        assertNull(WeChatFileMessageParser.parseFileMessage(xxeXml, 1000L), "DOCTYPE declarations must be rejected")

        val traversalXml = """
            <msg><appmsg><title>../../secret/seats.xlsx</title><type>6</type><appattach><totallen>512</totallen></appattach></appmsg></msg>
        """.trimIndent()
        val sanitized = WeChatFileMessageParser.parseFileMessage(traversalXml, 1000L)
        assertNotNull(sanitized)
        assertEquals("seats.xlsx", sanitized.fileName, "Traversal path components must be stripped cleanly")
    }

    @Test
    fun `resolver finds downloaded file via hardlink dir1 dir2 and timestamp fallback`() {
        val accountDir = File(tempDir, "wxid_test_account").apply { mkdirs() }
        val dbStorage = File(accountDir, "db_storage").apply { mkdirs() }
        val hardlinkDir = File(dbStorage, "hardlink").apply { mkdirs() }
        val msgFileDir = File(accountDir, "msg/file").apply { mkdirs() }

        val monthDir = File(msgFileDir, "2026-11").apply { mkdirs() }
        val excelBytes = ByteArray(120) { 1 }
        val excelFile = File(monthDir, "exam_seats.xlsx").apply { writeBytes(excelBytes) }
        val excelMd5 = md5Hex(excelBytes)

        val hashedSubdir = File(msgFileDir, "ab/cd1234").apply { mkdirs() }
        val hashedBytes = ByteArray(240) { 2 }
        val hashedFile = File(hashedSubdir, "lab_manual.docx").apply { writeBytes(hashedBytes) }
        val hashedMd5 = md5Hex(hashedBytes)

        val hardlinkDb = File(hardlinkDir, "hardlink.db")
        DriverManager.getConnection("jdbc:sqlite:${hardlinkDb.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE dir2id(username TEXT PRIMARY KEY)")
                stmt.execute("INSERT INTO dir2id(rowid, username) VALUES (1, '2026-11')")
                stmt.execute("INSERT INTO dir2id(rowid, username) VALUES (2, 'ab')")
                stmt.execute("INSERT INTO dir2id(rowid, username) VALUES (3, 'cd1234')")
                stmt.execute(
                    "CREATE TABLE file_hardlink_info_v4(" +
                        "md5 TEXT, file_name TEXT, file_size INTEGER, dir1 INTEGER, dir2 INTEGER)"
                )
                stmt.executeUpdate(
                    "INSERT INTO file_hardlink_info_v4 VALUES ('$excelMd5', 'exam_seats.xlsx', 120, 1, 0)"
                )
                stmt.executeUpdate(
                    "INSERT INTO file_hardlink_info_v4 VALUES ('$hashedMd5', 'lab_manual.docx', 240, 3, 2)"
                )
            }
        }

        val resolver = WeChatAttachmentResolver(WeChatDatabaseDriver(dbStorage))
        val resolvedExcel = resolver.resolve(
            EventAttachment(
                fileName = "exam_seats.xlsx",
                fileExtension = "xlsx",
                fileSizeBytes = 120L,
                md5 = excelMd5,
                messageTimestampSeconds = 1795000000L
            )
        )
        assertEquals(excelFile.canonicalPath, resolvedExcel.localPath)
        assertTrue(resolvedExcel.isLocalFilePresent())

        val resolvedDocx = resolver.resolve(
            EventAttachment(
                fileName = "lab_manual.docx",
                fileExtension = "docx",
                fileSizeBytes = 240L,
                md5 = hashedMd5,
                messageTimestampSeconds = 1795000000L
            )
        )
        assertEquals(hashedFile.canonicalPath, resolvedDocx.localPath)

        // Fallback when hardlink.db doesn't contain the entry
        val fallbackMonth = YearMonth.from(Instant.ofEpochSecond(1795000000L).atZone(ZoneId.systemDefault()))
            .format(DateTimeFormatter.ofPattern("yyyy-MM"))
        val fallbackFile = File(File(msgFileDir, fallbackMonth).apply { mkdirs() }, "fallback_schedule.csv")
            .apply { writeText("Course,Room\nAlgorithms,302\n") }
        val resolvedFallback = resolver.resolve(
            EventAttachment(
                fileName = "fallback_schedule.csv",
                fileExtension = "csv",
                fileSizeBytes = fallbackFile.length(),
                messageTimestampSeconds = 1795000000L
            )
        )
        assertEquals(fallbackFile.canonicalPath, resolvedFallback.localPath)

        val missing = resolver.resolve(
            EventAttachment(fileName = "not_downloaded_yet.pdf", fileExtension = "pdf", messageTimestampSeconds = 1795000000L)
        )
        assertNull(missing.localPath)
        assertFalse(missing.isLocalFilePresent())
    }

    @Test
    fun `content extractor reads bounded xlsx pdf docx and csv previews and handles corrupt files`() {
        // 1. Large XLSX with focus hint prioritization
        val xlsxFile = File(tempDir, "exam_seats.xlsx")
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Seats")
            val header = sheet.createRow(0)
            header.createCell(0).setCellValue("StudentID")
            header.createCell(1).setCellValue("Major")
            header.createCell(2).setCellValue("Room")
            for (i in 1..60) {
                val r = sheet.createRow(i)
                r.createCell(0).setCellValue("S2026${i.toString().padStart(3, '0')}")
                r.createCell(1).setCellValue(if (i == 55) "International CS" else "General Engineering")
                r.createCell(2).setCellValue(if (i == 55) "Room A-408 Seat 19" else "Room B-$i")
            }
            xlsxFile.outputStream().use { wb.write(it) }
        }
        val xlsxPreview = AttachmentContentExtractor.extractBoundedPreview(
            file = xlsxFile,
            focusHints = setOf("international", "cs")
        )
        assertNotNull(xlsxPreview)
        assertTrue(xlsxPreview.contains("[Sheet: Seats (61 rows)]"))
        assertTrue(xlsxPreview.contains("StudentID | Major | Room"))
        assertTrue(xlsxPreview.contains("Room A-408 Seat 19"), "Matching row should be prioritized in large spreadsheet preview")
        assertTrue(xlsxPreview.length <= AttachmentContentExtractor.MAX_PREVIEW_CHARS)

        // 2. Multi-page PDF bounded to PDF_MAX_PAGES
        val pdfFile = File(tempDir, "exam_rules.pdf")
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (pageNum in 1..6) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("Exam Policy Page $pageNum Bring Student ID")
                    cs.endText()
                }
            }
            doc.save(pdfFile)
        }
        val pdfPreview = AttachmentContentExtractor.extractBoundedPreview(pdfFile)
        assertNotNull(pdfPreview)
        assertTrue(pdfPreview.contains("Exam Policy Page 1"))
        assertFalse(pdfPreview.contains("Exam Policy Page 6"), "Pages beyond PDF_MAX_PAGES must be omitted")
        assertTrue(pdfPreview.contains("showing first 4 of 6 pages"))

        // 3. DOCX extraction
        val docxFile = File(tempDir, "notice.docx")
        XWPFDocument().use { doc ->
            doc.createParagraph().createRun().setText("Final examination starts Nov 30 at 09:00.")
            docxFile.outputStream().use { doc.write(it) }
        }
        val docxPreview = AttachmentContentExtractor.extractBoundedPreview(docxFile)
        assertNotNull(docxPreview)
        assertTrue(docxPreview.contains("Final examination starts Nov 30 at 09:00."))

        // 4. Corrupt / unsupported files return null without throwing
        val corruptXlsx = File(tempDir, "broken.xlsx").apply { writeText("not a zip workbook") }
        assertNull(AttachmentContentExtractor.extractBoundedPreview(corruptXlsx))
        val imageFile = File(tempDir, "photo.png").apply { writeBytes(ByteArray(32)) }
        assertNull(AttachmentContentExtractor.extractBoundedPreview(imageFile))
    }

    @Test
    fun `MessageSanitizer preserves messages with extracted attachments even under XML and length rules`() {
        val fileMsg = WeChatMessage(
            localId = 50L,
            timestampSeconds = 1795000000L,
            senderId = "wxid_teacher",
            senderDisplayName = "Prof Lin",
            rawContent = "<msg><appmsg><title>a.xls</title><type>6</type></appmsg></msg>",
            textContent = "[Shared File: a.xls]",
            isZstdCompressed = false,
            localType = (6L shl 32) or 49L,
            attachments = listOf(EventAttachment(fileName = "a.xls", fileExtension = "xls"))
        )
        val xmlNoiseMsg = WeChatMessage(
            localId = 51L,
            timestampSeconds = 1795000005L,
            senderId = "wxid_student",
            senderDisplayName = "Student",
            rawContent = "<msg><emoji /></msg>",
            textContent = "<msg><emoji /></msg>",
            isZstdCompressed = false
        )

        val filtered = MessageSanitizer.filterSubstantiveMessages(
            listOf(fileMsg, xmlNoiseMsg),
            PrefilterConfig(minMessageLength = 50)
        )
        assertEquals(listOf(50L), filtered.map { it.localId })
    }

    @Test
    fun `AttachmentLauncher blocks unsafe extensions and paths outside allowedRoot`() {
        val launcher = AttachmentLauncher()
        val allowedRoot = File(tempDir, "msg/file").apply { mkdirs() }
        val scriptInRoot = File(allowedRoot, "run_me.command").apply { writeText("echo hi") }
        val outsideDoc = File(tempDir, "outside.pdf").apply { writeText("%PDF") }

        val unsafeExtResult = launcher.openFile(scriptInRoot, allowedRoot = allowedRoot)
        assertTrue(unsafeExtResult.isFailure)
        assertTrue(unsafeExtResult.exceptionOrNull()?.message.orEmpty().contains("blocked for safety"))

        val confinedOpenResult = launcher.openFile(outsideDoc, allowedRoot = allowedRoot)
        assertTrue(confinedOpenResult.isFailure)
        assertTrue(confinedOpenResult.exceptionOrNull()?.message.orEmpty().contains("outside WeChat attachments folder"))

        val confinedRevealResult = launcher.revealInFinder(outsideDoc, allowedRoot = allowedRoot)
        assertTrue(confinedRevealResult.isFailure)
    }

    @Test
    fun `resolver refuses wrong size or conflicting md5 files under msg file fallback`() {
        val accountDir = File(tempDir, "wxid_mismatch").apply { mkdirs() }
        val dbStorage = File(accountDir, "db_storage").apply { mkdirs() }
        val monthDir = File(accountDir, "msg/file/2026-11").apply { mkdirs() }
        File(monthDir, "schedule.pdf").apply { writeBytes(ByteArray(200)) }

        val resolver = WeChatAttachmentResolver(WeChatDatabaseDriver(dbStorage))
        val wrongSize = resolver.resolve(
            EventAttachment(
                fileName = "schedule.pdf",
                fileExtension = "pdf",
                fileSizeBytes = 9999L,
                messageTimestampSeconds = 1795000000L
            )
        )
        assertNull(wrongSize.localPath, "Resolver must reject files whose size contradicts XML <totallen>")

        val wrongMd5 = resolver.resolve(
            EventAttachment(
                fileName = "schedule.pdf",
                fileExtension = "pdf",
                fileSizeBytes = 200L,
                md5 = "00000000000000000000000000000000",
                messageTimestampSeconds = 1795000000L
            )
        )
        assertNull(wrongMd5.localPath, "Resolver must reject files whose content MD5 contradicts XML <md5>")
    }

    @Test
    fun `content extractor handles plain text, docx tables, maxChars truncation, legacy XML, and expiration`() {
        val csvFile = File(tempDir, "notes.csv").apply { writeText("Room,Time\nBuilding C-102,09:00\n" + "Line\n".repeat(40)) }
        val truncated = AttachmentContentExtractor.extractBoundedPreview(csvFile, maxChars = 60)
        assertNotNull(truncated)
        assertTrue(truncated.length <= 60)
        assertTrue(truncated.endsWith("..."))

        val tableDocx = File(tempDir, "table_notice.docx")
        XWPFDocument().use { doc ->
            val tbl = doc.createTable(2, 2)
            tbl.getRow(0).getCell(0).text = "Section"
            tbl.getRow(0).getCell(1).text = "Room"
            tbl.getRow(1).getCell(0).text = "CS-A"
            tbl.getRow(1).getCell(1).text = "Hall 405"
            tableDocx.outputStream().use { doc.write(it) }
        }
        val tablePreview = AttachmentContentExtractor.extractBoundedPreview(tableDocx)
        assertNotNull(tablePreview)
        assertTrue(tablePreview.contains("CS-A | Hall 405"))

        assertTrue(
            WeChatFileMessageParser.isCandidateFileMessage(
                localType = 1L,
                content = "<msg><appmsg><type>6</type><title>a.pdf</title></appmsg></msg>",
                allowLegacyXmlFallback = true
            )
        )
        assertTrue(EventAttachment(fileName = "old.pdf", mediaExpireAtSeconds = 100L).isExpired(nowEpochSeconds = 200L))
        assertFalse(EventAttachment(fileName = "valid.pdf", mediaExpireAtSeconds = 300L).isExpired(nowEpochSeconds = 200L))
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
