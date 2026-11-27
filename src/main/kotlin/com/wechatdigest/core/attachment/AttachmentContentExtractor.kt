package com.wechatdigest.core.attachment

import com.wechatdigest.core.llm.ExtractionPrompts
import com.wechatdigest.core.llm.LlmJsonUtils
import com.wechatdigest.core.llm.local.LocalModelPaths
import com.wechatdigest.core.model.EventAttachment
import kotlinx.coroutines.CancellationException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFGroupShape
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTable
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.nio.file.Files
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Extracts bounded, prompt-safe text previews from downloaded WeChat attachments
 * (`.xlsx`, `.xls`, `.pdf`, `.docx`, `.pptx`, `.txt`, `.csv`, `.tsv`, `.md`, images & `.dat`).
 */
object AttachmentContentExtractor {

    /** Extracted prompt preview text (if any) and whether an analyzer actually processed the file. */
    data class ExtractionOutcome(
        val preview: String?,
        val analyzedByAi: Boolean
    )

    /** Maximum file size in bytes (25 MiB) permitted before extraction refuses the file. */
    const val MAX_FILE_BYTES: Long = 25L * 1024 * 1024

    /** Maximum normalized character budget emitted per attachment preview. */
    const val MAX_PREVIEW_CHARS: Int = 2_400

    /** Maximum number of Excel sheets inspected per workbook. */
    const val EXCEL_MAX_SHEETS: Int = 3

    /** Maximum number of rows emitted per worksheet preview. */
    const val EXCEL_MAX_ROWS_PER_SHEET: Int = 35

    /** Hard cap on physical rows scanned per worksheet to bound heap/CPU usage. */
    const val EXCEL_TOTAL_SCANNED_ROWS: Int = 2_000

    /** Maximum columns formatted per worksheet row. */
    const val EXCEL_MAX_COLS: Int = 16

    /** Maximum pages extracted per text-based PDF document. */
    const val PDF_MAX_PAGES: Int = 4

    /** Maximum PDF pages scanned when [focusHints] are provided to prioritize relevant sections. */
    const val PDF_HINT_SCAN_MAX_PAGES: Int = 12

    /** Maximum pages rendered & OCR'd per scanned image-only PDF document. */
    const val PDF_OCR_MAX_PAGES: Int = 3

    /** Maximum slides inspected per `.pptx` presentation. */
    const val PPTX_MAX_SLIDES: Int = 25

    private const val SCANNED_PDF_MIN_TEXT_CHARS: Int = 40
    private const val PDF_OCR_RENDER_DPI: Float = 150f
    private const val PDF_OCR_MAX_PIXEL_DIM: Float = 2048f
    private const val PPTX_MAX_GROUP_DEPTH: Int = 5
    private const val DOCX_MAX_TABLES: Int = 50
    private const val MAX_FORMATTED_ROW_CHARS: Int = 320
    private const val ZIP_BOMB_MIN_INFLATE_RATIO: Double = 0.05
    private const val ZIP_BOMB_MAX_ENTRY_BYTES: Long = 8L * 1024 * 1024
    private const val ZIP_BOMB_MAX_FILE_COUNT: Long = 2_000L

    private val SUPPORTED_TEXT_EXTENSIONS = setOf("txt", "csv", "tsv", "md", "log")
    private val SUPPORTED_DOCUMENT_EXTENSIONS = setOf("xlsx", "xls", "pdf", "docx", "pptx")

    private val poiLock = Any()

    internal val defaultOcrService: LocalVisionOcrService by lazy { LocalVisionOcrService() }

    /**
     * Returns `true` when an attachment with [fileExtension] (or [fileName]) is supported by
     * [extractWithStatus] under the active [enableVisualOcr] / [isVisionAvailable] settings.
     */
    fun isExtractableAttachment(
        fileExtension: String,
        fileName: String = "",
        enableVisualOcr: Boolean = true,
        isVisionAvailable: Boolean = true
    ): Boolean {
        val ext = fileExtension.trim().lowercase(Locale.ROOT)
            .ifBlank { fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) }
        return when (ext) {
            in SUPPORTED_DOCUMENT_EXTENSIONS, in SUPPORTED_TEXT_EXTENSIONS -> true
            in EventAttachment.IMAGE_EXTENSIONS -> enableVisualOcr && isVisionAvailable
            else -> false
        }
    }

    private fun enforceZipBombLimits() {
        ZipSecureFile.setMinInflateRatio(ZIP_BOMB_MIN_INFLATE_RATIO)
        ZipSecureFile.setMaxEntrySize(ZIP_BOMB_MAX_ENTRY_BYTES)
        ZipSecureFile.setMaxFileCount(ZIP_BOMB_MAX_FILE_COUNT)
    }

    /**
     * Reads up to [maxChars] of normalized text from [file], prioritizing sections matching [focusHints]
     * and running Apple Vision OCR for images or scanned PDFs when available.
     */
    fun extractBoundedPreview(
        file: File,
        focusHints: Set<String> = emptySet(),
        maxChars: Int = MAX_PREVIEW_CHARS
    ): String? = extractWithStatus(
        file = file,
        focusHints = focusHints,
        maxChars = maxChars
    ).preview

    /**
     * Extracts a bounded preview from [file] and reports whether an extractable document or visual
     * media analyzer actually processed it (`analyzedByAi = true`).
     */
    fun extractWithStatus(
        file: File,
        focusHints: Set<String> = emptySet(),
        maxChars: Int = MAX_PREVIEW_CHARS,
        enableVisualOcr: Boolean = true,
        ocrService: LocalVisionOcrService? = defaultOcrService,
        extensionOverride: String? = null
    ): ExtractionOutcome {
        if (maxChars <= 0 || !file.exists() || !file.isFile || Files.isSymbolicLink(file.toPath())) {
            return ExtractionOutcome(preview = null, analyzedByAi = false)
        }
        val size = file.length()
        if (size <= 0L || size > MAX_FILE_BYTES) {
            return ExtractionOutcome(preview = null, analyzedByAi = false)
        }

        val ext = file.extension.lowercase(Locale.ROOT)
            .ifBlank { extensionOverride?.trim()?.lowercase(Locale.ROOT).orEmpty() }
            .ifBlank { WeChatImageDecoder.inspectFile(file)?.formatExtension.orEmpty() }
        val rawOutcome = extractRawText(file, ext, focusHints, maxChars, enableVisualOcr, ocrService)
        val rawText = rawOutcome.preview ?: return ExtractionOutcome(preview = null, analyzedByAi = rawOutcome.analyzedByAi)
        val normalized = normalizeExtractedText(rawText, maxChars)
        return ExtractionOutcome(preview = normalized, analyzedByAi = rawOutcome.analyzedByAi)
    }

    private fun normalizeExtractedText(raw: String, maxChars: Int): String? {
        val normalized = ExtractionPrompts.sanitizeTranscriptForPrompt(raw)
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        if (normalized.isBlank()) return null
        return if (normalized.length > maxChars) {
            if (maxChars <= 3) normalized.take(maxChars) else normalized.take(maxChars - 3).trimEnd() + "..."
        } else {
            normalized
        }
    }

    private fun normalizeHints(focusHints: Set<String>): List<String> =
        focusHints
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length >= 2 }
            .distinct()

    private fun extractRawText(
        file: File,
        ext: String,
        focusHints: Set<String>,
        maxChars: Int,
        enableVisualOcr: Boolean,
        ocrService: LocalVisionOcrService?
    ): ExtractionOutcome {
        return try {
            val hints = normalizeHints(focusHints)
            when (ext) {
                "xlsx", "xls" -> synchronized(poiLock) {
                    enforceZipBombLimits()
                    ExtractionOutcome(extractSpreadsheet(file, hints, maxChars), analyzedByAi = true)
                }
                "pdf" -> extractPdf(file, hints, maxChars, enableVisualOcr, ocrService)
                "docx" -> synchronized(poiLock) {
                    enforceZipBombLimits()
                    ExtractionOutcome(extractDocx(file, hints, maxChars), analyzedByAi = true)
                }
                "pptx" -> synchronized(poiLock) {
                    enforceZipBombLimits()
                    ExtractionOutcome(extractPptx(file, hints, maxChars), analyzedByAi = true)
                }
                in SUPPORTED_TEXT_EXTENSIONS -> ExtractionOutcome(extractPlainText(file, maxChars), analyzedByAi = true)
                in EventAttachment.IMAGE_EXTENSIONS -> extractImageViaOcr(file, maxChars, enableVisualOcr, ocrService)
                else -> ExtractionOutcome(preview = null, analyzedByAi = false)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            System.err.println(
                "[AttachmentContentExtractor] Failed to extract attachment ($ext): ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            ExtractionOutcome(preview = null, analyzedByAi = false)
        } catch (e: Throwable) {
            if (e !is OutOfMemoryError && e !is StackOverflowError) throw e
            System.err.println("[AttachmentContentExtractor] Resource guard triggered while reading .$ext attachment")
            ExtractionOutcome(preview = null, analyzedByAi = false)
        }
    }

    private fun extractSpreadsheet(
        file: File,
        normalizedHints: List<String>,
        maxChars: Int
    ): String {

        WorkbookFactory.create(file, null, true).use { workbook ->
            val formatter = DataFormatter(Locale.ROOT)
            val sb = StringBuilder()
            val sheetLimit = workbook.numberOfSheets.coerceAtMost(EXCEL_MAX_SHEETS)

            for (sheetIdx in 0 until sheetLimit) {
                if (sb.length >= maxChars) break
                val sheet = workbook.getSheetAt(sheetIdx)
                appendSheetPreview(sb, sheet, formatter, normalizedHints, maxChars)
                sb.append('\n')
            }

            if (workbook.numberOfSheets > sheetLimit && sb.length < maxChars - 32) {
                sb.append("... (${workbook.numberOfSheets - sheetLimit} more sheets omitted)")
            }
            return sb.toString()
        }
    }

    private fun appendSheetPreview(
        sb: StringBuilder,
        sheet: Sheet,
        formatter: DataFormatter,
        normalizedHints: List<String>,
        maxChars: Int
    ) {
        val totalRows = sheet.physicalNumberOfRows
        sb.append("[Sheet: ").append(sheet.sheetName).append(" (").append(totalRows).append(" rows)]\n")
        if (totalRows <= 0) return

        val nonEmptyRows = collectSheetRows(sheet, formatter)
        if (nonEmptyRows.isEmpty()) return

        sb.append(nonEmptyRows.first()).append('\n')
        val dataRows = nonEmptyRows.drop(1)
        if (dataRows.isEmpty()) return

        val selectedRows = selectPrioritizedSheetRows(dataRows, normalizedHints)
        for (line in selectedRows) {
            if (sb.length + line.length + 1 > maxChars) break
            sb.append(line).append('\n')
        }
        if (dataRows.size > selectedRows.size && sb.length < maxChars - 32) {
            sb.append("... (${dataRows.size - selectedRows.size} more rows omitted)\n")
        }
    }

    private fun collectSheetRows(sheet: Sheet, formatter: DataFormatter): List<String> {
        val nonEmptyRows = mutableListOf<String>()
        var totalScanned = 0
        for (row in sheet) {
            totalScanned++
            val formatted = formatRow(row, formatter)
            if (formatted.isNotBlank()) nonEmptyRows.add(formatted)
            // Bound total scanned rows cleanly so huge or blank-formatted workbooks never exhaust heap
            if (nonEmptyRows.size >= 500 || totalScanned >= EXCEL_TOTAL_SCANNED_ROWS) break
        }
        return nonEmptyRows
    }

    private fun selectPrioritizedSheetRows(dataRows: List<String>, normalizedHints: List<String>): List<String> {
        if (dataRows.size <= EXCEL_MAX_ROWS_PER_SHEET - 1 || normalizedHints.isEmpty()) {
            return dataRows.take(EXCEL_MAX_ROWS_PER_SHEET - 1)
        }
        val matching = dataRows.filter { line ->
            val lower = line.lowercase(Locale.ROOT)
            normalizedHints.any { hint -> lower.contains(hint) }
        }.take(15)
        val matchingSet = matching.toSet()
        val remainingSlots = (EXCEL_MAX_ROWS_PER_SHEET - 1 - matching.size).coerceAtLeast(10)
        val headRows = dataRows.filterNot { it in matchingSet }.take(remainingSlots)
        return (headRows + matching).distinct()
    }

    private fun formatRow(
        row: org.apache.poi.ss.usermodel.Row,
        formatter: DataFormatter
    ): String {
        val lastCol = row.lastCellNum.toInt().coerceIn(0, EXCEL_MAX_COLS)
        if (lastCol <= 0) return ""
        val cells = (0 until lastCol).map { colIdx ->
            val cell = row.getCell(colIdx)
            if (cell == null) "" else formatter.formatCellValue(cell).replace(Regex("\\s+"), " ").trim()
        }
        if (cells.all { it.isEmpty() }) return ""
        return cells.dropLastWhile { it.isEmpty() }.joinToString(" | ").take(MAX_FORMATTED_ROW_CHARS)
    }

    private fun extractPdf(
        file: File,
        normalizedHints: List<String>,
        maxChars: Int,
        enableVisualOcr: Boolean,
        ocrService: LocalVisionOcrService?
    ): ExtractionOutcome {
        Loader.loadPDF(file, "", null, null, IOUtils.createTempFileOnlyStreamCache()).use { doc ->
            val totalPages = doc.numberOfPages
            if (totalPages <= 0) return ExtractionOutcome(preview = "", analyzedByAi = true)

            val maxScanPage = if (normalizedHints.isEmpty()) PDF_MAX_PAGES else PDF_HINT_SCAN_MAX_PAGES
            val endPage = totalPages.coerceAtMost(maxScanPage)
            val pageBlocks = mutableListOf<String>()
            var totalStrippedChars = 0
            for (pageNum in 1..endPage) {
                val stripper = PDFTextStripper().apply {
                    startPage = pageNum
                    this.endPage = pageNum
                }
                val pageText = stripper.getText(doc).trim()
                totalStrippedChars += pageText.count { !it.isWhitespace() }
                if (pageText.isNotEmpty()) {
                    pageBlocks += if (totalPages > 1) "[Page $pageNum]\n$pageText" else pageText
                }
            }

            if (totalStrippedChars < SCANNED_PDF_MIN_TEXT_CHARS) {
                return extractScannedPdfViaOcr(doc, totalPages, maxChars, enableVisualOcr, ocrService)
            }

            val suffix = if (totalPages > endPage) "\n... (showing first $endPage of $totalPages pages)" else null
            val boundedBlocks = if (normalizedHints.isEmpty()) {
                pageBlocks.take(PDF_MAX_PAGES)
            } else {
                pageBlocks
            }
            val formatted = selectPrioritizedTextBlocks(
                blocks = boundedBlocks,
                normalizedHints = normalizedHints,
                maxChars = maxChars,
                truncatedSuffix = suffix
            )
            return ExtractionOutcome(preview = formatted, analyzedByAi = true)
        }
    }

    private fun extractScannedPdfViaOcr(
        doc: org.apache.pdfbox.pdmodel.PDDocument,
        totalPages: Int,
        maxChars: Int,
        enableVisualOcr: Boolean,
        ocrService: LocalVisionOcrService?
    ): ExtractionOutcome {
        if (!enableVisualOcr || ocrService == null || !ocrService.isAvailable()) {
            return ExtractionOutcome(preview = null, analyzedByAi = false)
        }
        val renderer = PDFRenderer(doc)
        val ocrPageLimit = totalPages.coerceAtMost(PDF_OCR_MAX_PAGES)
        val sb = StringBuilder()
        var anyPageProcessed = false

        val tempDir = LocalModelPaths.createOwnerOnlyTempDirectory("wechat-digest-pdfocr-")
        try {
            for (pageIdx in 0 until ocrPageLimit) {
                if (sb.length >= maxChars) break
                val cropBox = doc.getPage(pageIdx).cropBox
                val maxDimensionPt = maxOf(cropBox.width, cropBox.height).coerceAtLeast(1f)
                val targetDpi = minOf(PDF_OCR_RENDER_DPI, PDF_OCR_MAX_PIXEL_DIM * 72f / maxDimensionPt)
                if (targetDpi < 18f) continue
                val pageTempFile = LocalModelPaths.createOwnerOnlyTempFile(tempDir, "page-$pageIdx-", ".png")
                try {
                    val pageImage = renderer.renderImageWithDPI(pageIdx, targetDpi, ImageType.RGB)
                    try {
                        ImageIO.write(pageImage, "png", pageTempFile)
                    } finally {
                        pageImage.flush()
                    }
                    val output = ocrService.recognize(pageTempFile)
                    if (output != null) {
                        anyPageProcessed = true
                        val formattedPage = ocrService.formatAsPreview(output, maxChars)
                        if (!formattedPage.isNullOrBlank()) {
                            if (sb.isNotEmpty()) sb.append("\n\n")
                            sb.append("[Page ").append(pageIdx + 1).append(" OCR]\n").append(formattedPage)
                        }
                    }
                } finally {
                    runCatching { pageTempFile.delete() }
                }
            }
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
        return ExtractionOutcome(preview = sb.toString().trim(), analyzedByAi = anyPageProcessed)
    }

    private fun extractImageViaOcr(
        file: File,
        maxChars: Int,
        enableVisualOcr: Boolean,
        ocrService: LocalVisionOcrService?
    ): ExtractionOutcome {
        if (!enableVisualOcr || ocrService == null || !ocrService.isAvailable()) {
            return ExtractionOutcome(preview = null, analyzedByAi = false)
        }
        var recognized = false
        val preview = WeChatImageDecoder.withDecodedImageFile(file) { decodedFile ->
            val output = ocrService.recognize(decodedFile) ?: return@withDecodedImageFile null
            recognized = true
            ocrService.formatAsPreview(output, maxChars)
        }
        return ExtractionOutcome(preview = preview, analyzedByAi = recognized)
    }

    private fun extractDocx(
        file: File,
        normalizedHints: List<String>,
        maxChars: Int
    ): String {
        OPCPackage.open(file, PackageAccess.READ).use { pkg ->
            XWPFDocument(pkg).use { doc ->
                val blocks = mutableListOf<String>()
                for (paragraph in doc.paragraphs) {
                    val text = paragraph.text?.trim().orEmpty()
                    if (text.isNotBlank()) blocks.add(text)
                    if (blocks.size >= 400) break
                }
                var tableCount = 0
                for (table in doc.tables) {
                    if (blocks.size >= 400 || tableCount++ >= DOCX_MAX_TABLES) break
                    val tableSb = StringBuilder()
                    appendSingleDocxTable(table, tableSb, maxChars)
                    val tableText = tableSb.toString().trim()
                    if (tableText.isNotBlank()) blocks.add(tableText)
                }
                return selectPrioritizedTextBlocks(blocks, normalizedHints, maxChars)
            }
        }
    }

    private fun extractPptx(
        file: File,
        normalizedHints: List<String>,
        maxChars: Int
    ): String {
        OPCPackage.open(file, PackageAccess.READ).use { pkg ->
            XMLSlideShow(pkg).use { slideShow ->
                val slides = slideShow.slides
                if (slides.isEmpty()) return ""
                val visibleSlides = slides.filterNot { it.isHidden }
                val inspected = visibleSlides.take(PPTX_MAX_SLIDES)
                val slideBlocks = inspected.mapNotNull { slide -> formatSlideBlock(slide) }
                val suffix = if (visibleSlides.size > inspected.size) {
                    "\n... (showing from first ${inspected.size} of ${visibleSlides.size} slides)"
                } else {
                    null
                }
                return selectPrioritizedTextBlocks(slideBlocks, normalizedHints, maxChars, suffix)
            }
        }
    }

    private fun formatSlideBlock(slide: XSLFSlide): String? {
        val lines = mutableListOf<String>()
        val title = slide.title?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        for (shape in slide.shapes) {
            collectPptxShapeText(shape, depth = 0, lines = lines)
        }
        val notesText = slide.notes?.textParagraphs
            ?.flatten()
            ?.mapNotNull { p -> p.text?.trim()?.takeIf { it.isNotBlank() } }
            ?.joinToString(" ")
            .orEmpty()
        val distinctLines = lines
            .map { it.trim() }
            .filter { it.isNotBlank() && it != title }
            .distinct()
        if (title.isBlank() && distinctLines.isEmpty() && notesText.isBlank()) {
            return null
        }
        return buildString {
            append("[Slide ").append(slide.slideNumber)
            if (title.isNotBlank()) append(": ").append(title)
            append("]\n")
            for (line in distinctLines) {
                append(line).append('\n')
            }
            if (notesText.isNotBlank()) {
                append("[Notes: ").append(notesText.take(MAX_FORMATTED_ROW_CHARS)).append("]\n")
            }
        }.trim()
    }

    private fun collectPptxShapeText(shape: XSLFShape, depth: Int, lines: MutableList<String>) {
        if (depth > PPTX_MAX_GROUP_DEPTH) return
        when (shape) {
            is XSLFTextShape -> {
                val raw = shape.text?.trim().orEmpty()
                if (raw.isNotBlank()) {
                    raw.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { lines.add(it) }
                }
            }
            is XSLFTable -> {
                for (row in shape.rows) {
                    val rowText = row.cells.joinToString(" | ") { cell ->
                        cell.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                    }.trim()
                    if (rowText.isNotBlank()) {
                        lines.add(rowText.take(MAX_FORMATTED_ROW_CHARS))
                    }
                }
            }
            is XSLFGroupShape -> {
                for (child in shape.shapes) {
                    collectPptxShapeText(child, depth + 1, lines)
                }
            }
        }
    }

    internal fun selectPrioritizedTextBlocks(
        blocks: List<String>,
        normalizedHints: List<String>,
        maxChars: Int,
        truncatedSuffix: String? = null
    ): String {
        val cleanBlocks = blocks.map { it.trim() }.filter { it.isNotBlank() }
        if (cleanBlocks.isEmpty()) return ""
        val reserve = truncatedSuffix?.length ?: 0
        val budget = (maxChars - reserve).coerceAtLeast(64)

        val selectedIndices = linkedSetOf<Int>()
        selectedIndices.add(0)
        var usedChars = cleanBlocks[0].length

        if (cleanBlocks.size > 1) {
            val rankedRemaining = (1 until cleanBlocks.size).sortedWith(
                compareByDescending<Int> { idx ->
                    val lower = cleanBlocks[idx].lowercase(Locale.ROOT)
                    normalizedHints.count { hint -> lower.contains(hint) }
                }.thenBy { it }
            )
            for (idx in rankedRemaining) {
                val nextCost = cleanBlocks[idx].length + 2
                if (usedChars + nextCost <= budget) {
                    selectedIndices.add(idx)
                    usedChars += nextCost
                }
            }
        }

        val orderedBody = selectedIndices.sorted().joinToString("\n\n") { cleanBlocks[it] }
        return if (!truncatedSuffix.isNullOrBlank() && orderedBody.length + truncatedSuffix.length <= maxChars) {
            orderedBody + truncatedSuffix
        } else {
            orderedBody
        }
    }

    private fun appendSingleDocxTable(
        table: org.apache.poi.xwpf.usermodel.XWPFTable,
        sb: StringBuilder,
        maxChars: Int
    ) {
        for (row in table.rows) {
            val rowText = row.tableCells.joinToString(" | ") { cell ->
                cell.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            }.trim()
            if (rowText.isNotBlank()) {
                sb.append(rowText.take(MAX_FORMATTED_ROW_CHARS)).append('\n')
            }
            if (sb.length >= maxChars) break
        }
    }

    private fun extractPlainText(file: File, maxChars: Int): String {
        val sb = StringBuilder()
        val buf = CharArray(4096)
        val charLimit = maxChars.coerceAtLeast(1) + 256
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            while (sb.length < charLimit) {
                val toRead = minOf(buf.size, charLimit - sb.length)
                val read = reader.read(buf, 0, toRead)
                if (read <= 0) break
                sb.append(buf, 0, read)
            }
        }
        return sb.toString()
    }
}
