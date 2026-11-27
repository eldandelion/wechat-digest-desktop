package com.wechatdigest.core.attachment

import com.wechatdigest.core.db.WeChatDatabaseDriver
import com.wechatdigest.core.model.EventAttachment
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalVisionOcrAndImageTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `image decoder detects XOR keys across JPEG PNG GIF WEBP and cleans temp files in finally`() {
        val jpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val gifMagic = "GIF89a......".toByteArray()
        val webpMagic = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20
        )

        val xorJpg = xorBytes(jpegMagic, 0x42)
        val xorPng = xorBytes(pngMagic, 0xAB)
        val xorGif = xorBytes(gifMagic, 0x1F)
        val xorWebp = xorBytes(webpMagic, 0x77)

        assertEquals(WeChatImageDecoder.DecodedImageSpec("jpg", 0x42), WeChatImageDecoder.detectFormat(xorJpg))
        assertEquals(WeChatImageDecoder.DecodedImageSpec("png", 0xAB), WeChatImageDecoder.detectFormat(xorPng))
        assertEquals(WeChatImageDecoder.DecodedImageSpec("gif", 0x1F), WeChatImageDecoder.detectFormat(xorGif))
        assertEquals(WeChatImageDecoder.DecodedImageSpec("webp", 0x77), WeChatImageDecoder.detectFormat(xorWebp))
        assertNull(WeChatImageDecoder.detectFormat("not-an-image".toByteArray()))

        val datFile = File(tempDir, "poster.dat").apply { writeBytes(xorPng) }
        val expectedPlainMd5 = md5Hex(pngMagic)
        assertTrue(WeChatImageDecoder.verifyPlaintextMd5(datFile, expectedPlainMd5))
        assertFalse(WeChatImageDecoder.verifyPlaintextMd5(datFile, "0".repeat(32)))

        var capturedTempPath: File? = null
        val decodedMatch = WeChatImageDecoder.withDecodedImageFile(datFile) { decoded ->
            capturedTempPath = decoded
            assertEquals("png", decoded.extension)
            assertTrue(decoded.readBytes().contentEquals(pngMagic))
            true
        }
        assertEquals(true, decodedMatch)
        assertNotNull(capturedTempPath)
        assertFalse(capturedTempPath!!.exists(), "Temporary decoded image must be deleted after block")
        assertFalse(capturedTempPath!!.parentFile.exists(), "Temporary directory must be deleted after block")

        // Even on exception, temp file and temp dir must be cleaned up
        var leakedOnThrow: File? = null
        assertFailsWith<IllegalStateException> {
            WeChatImageDecoder.withDecodedImageFile(datFile) { decoded ->
                leakedOnThrow = decoded
                error("boom")
            }
        }
        assertNotNull(leakedOnThrow)
        assertFalse(leakedOnThrow!!.exists())
        assertFalse(leakedOnThrow!!.parentFile.exists())
    }

    @Test
    fun `xml parser handles local_type 3 images and resolver locates XOR dat in image_hardlink_info_v4 preferring HD`() {
        val imgBytes = makeValidPngBytes("Final Schedule Room 302")
        val imgMd5 = md5Hex(imgBytes)
        val xml = """
            <msg><img aeskey="k1" cdnthumburl="https://example/t" length="900" hdlength="${imgBytes.size}" md5="$imgMd5" /></msg>
        """.trimIndent()
        val parsed = WeChatXmlMessageParser.parseMessage(xml, localType = 3L, timestampSeconds = 1795000000L)
        assertNotNull(parsed)
        assertTrue(parsed is ParsedWeChatMessage.ImageAttachment)
        val fields = parsed.toMessageFields(xml)
        assertEquals(1, fields.attachments.size)
        val parsedAtt = fields.attachments.single()
        assertEquals(imgMd5, parsedAtt.md5)
        assertTrue(parsedAtt.isImageAttachment())
        assertTrue(fields.textContent.contains("[Image Attachment:"))

        val accountDir = File(tempDir, "wxid_img_acc").apply { mkdirs() }
        val dbStorage = File(accountDir, "db_storage").apply { mkdirs() }
        val hardlinkDir = File(dbStorage, "hardlink").apply { mkdirs() }
        val msgAttach = File(accountDir, "msg/attach").apply { mkdirs() }
        val imgFolder = File(msgAttach, "roomhash/2026-11/Img").apply { mkdirs() }

        // Put both thumbnail and HD XOR .dat files
        File(imgFolder, "${imgMd5}_t.dat").writeBytes(xorBytes(imgBytes.take(32).toByteArray(), 0x33))
        val hdDat = File(imgFolder, "${imgMd5}_h.dat").apply { writeBytes(xorBytes(imgBytes, 0x33)) }

        DriverManager.getConnection("jdbc:sqlite:${File(hardlinkDir, "hardlink.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE dir2id(username TEXT PRIMARY KEY)")
                stmt.execute("INSERT INTO dir2id(rowid, username) VALUES (1, 'roomhash')")
                stmt.execute("INSERT INTO dir2id(rowid, username) VALUES (2, '2026-11')")
                stmt.execute(
                    "CREATE TABLE image_hardlink_info_v4(" +
                        "md5 TEXT, file_name TEXT, file_size INTEGER, dir1 INTEGER, dir2 INTEGER)"
                )
                stmt.executeUpdate(
                    "INSERT INTO image_hardlink_info_v4 VALUES ('$imgMd5', '${imgMd5}_t.dat', 32, 1, 2)"
                )
                stmt.executeUpdate(
                    "INSERT INTO image_hardlink_info_v4 VALUES ('$imgMd5', '${imgMd5}_h.dat', ${imgBytes.size}, 1, 2)"
                )
            }
        }

        val resolver = WeChatAttachmentResolver(
            driver = WeChatDatabaseDriver(dbStorage),
            autoDetectSingleAccountRoot = false
        )
        val resolved = resolver.resolve(parsedAtt)
        assertEquals(hdDat.canonicalPath, resolved.localPath)
        assertTrue(resolver.resolveAllowedMediaRoots().any { it.canonicalPath == msgAttach.canonicalPath })

        val launcher = AttachmentLauncher()
        val openDatResult = launcher.openFile(hdDat, allowedRoots = resolver.resolveAllowedMediaRoots())
        assertTrue(openDatResult.isFailure, "Raw obfuscated .dat must not be launched directly")
        assertTrue(openDatResult.exceptionOrNull()?.message?.contains(".dat") == true)
    }

    @Test
    fun `ocr service parses QR payloads, rejects symlinks, and executes compiled macOS Apple Vision helper when available`() {
        val fakeBin = File(tempDir, "fake-vision-ocr").apply {
            writeText("#!/bin/sh\necho ok\n")
            setExecutable(true)
        }
        val testPng = File(tempDir, "sample.png").apply { writeBytes(makeValidPngBytes("EXAM ROOM 405")) }
        val mockService = LocalVisionOcrService(
            binDir = File(tempDir, "bin"),
            isMacOs = { true },
            customBinaryResolver = { fakeBin },
            processRunner = { _, _ ->
                VisionProcessResult(
                    exitCode = 0,
                    stdout = """{"text":"Line 1\n\nLine 2","qrPayloads":["https://uni.edu/rsvp","https://uni.edu/rsvp"]}""",
                    stderr = ""
                )
            }
        )
        val output = mockService.recognize(testPng)
        assertNotNull(output)
        assertEquals(listOf("https://uni.edu/rsvp"), output.qrPayloads)
        val formatted = mockService.formatAsPreview(output)
        assertNotNull(formatted)
        assertTrue(formatted.contains("[QR Code]: https://uni.edu/rsvp"))
        assertTrue(formatted.contains("Line 1"))

        val symImg = File(tempDir, "sym.png")
        Files.createSymbolicLink(symImg.toPath(), testPng.toPath())
        assertNull(mockService.recognize(symImg), "Symlinked image paths must be rejected")

        // Integration smoke check against the real compiled macOS Apple Vision CLI when available
        val liveService = LocalVisionOcrService(binDir = File(tempDir, "live-bin"))
        assumeTrue(liveService.isAvailable(), "Skipping live Apple Vision OCR check when helper is unavailable")
        val liveResult = liveService.recognize(testPng)
        if (System.getenv("CI") == "true") {
            assumeTrue(liveResult != null, "Skipping live Apple Vision OCR assertion when headless CI VM lacks Vision runtime")
        }
        val nonNullLiveResult = kotlin.test.assertNotNull(liveResult)
        assertTrue(
            nonNullLiveResult.text.contains("EXAM", ignoreCase = true) ||
                nonNullLiveResult.text.contains("ROOM", ignoreCase = true),
            "Expected Apple Vision to recognize synthetic English text, got: '${nonNullLiveResult.text}'"
        )
    }

    private fun xorBytes(bytes: ByteArray, key: Int): ByteArray =
        ByteArray(bytes.size) { i -> ((bytes[i].toInt() and 0xFF) xor key).toByte() }

    private fun makeValidPngBytes(label: String): ByteArray {
        val img = BufferedImage(640, 200, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, 640, 200)
            g.color = Color.BLACK
            g.font = Font("SansSerif", Font.BOLD, 44)
            g.drawString(label, 30, 115)
        } finally {
            g.dispose()
        }
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
