package com.wechatdigest.core.attachment

import com.wechatdigest.core.llm.LlmJsonUtils
import kotlinx.coroutines.CancellationException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.util.Locale

/**
 * Extracts bounded, prompt-safe text previews from downloaded WeChat attachments
 * (`.xlsx`, `.xls`, `.pdf`, `.docx`, `.txt`, `.csv`, `.tsv`, `.md`).
 */
object AttachmentContentExtractor {

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

    /** Maximum pages extracted per PDF document. */
    const val PDF_MAX_PAGES: Int = 4

    private const val MAX_FORMATTED_ROW_CHARS: Int = 320
    private const val ZIP_BOMB_MIN_INFLATE_RATIO: Double = 0.05
    private const val ZIP_BOMB_MAX_ENTRY_BYTES: Long = 16L * 1024 * 1024
    private const val ZIP_BOMB_MAX_FILE_COUNT: Long = 200L

    private val SUPPORTED_TEXT_EXTENSIONS = setOf("txt", "csv", "tsv", "md", "log")
    private val poiLock = Any()

    private fun enforceZipBombLimits() {
        ZipSecureFile.setMinInflateRatio(ZIP_BOMB_MIN_INFLATE_RATIO)
        ZipSecureFile.setMaxEntrySize(ZIP_BOMB_MAX_ENTRY_BYTES)
        ZipSecureFile.setMaxFileCount(ZIP_BOMB_MAX_FILE_COUNT)
    }

    /**
     * Reads up to [maxChars] of normalized text from [file], prioritizing spreadsheet rows matching [focusHints]
     * when large tables exceed [EXCEL_MAX_ROWS_PER_SHEET]. Returns `null` if missing, unsupported,
     * encrypted, oversized, or unreadable without throwing.
     */
    fun extractBoundedPreview(
        file: File,
        focusHints: Set<String> = emptySet(),
        maxChars: Int = MAX_PREVIEW_CHARS
    ): String? {
        if (maxChars <= 0 || !file.exists() || !file.isFile) return null
        val size = file.length()
        if (size <= 0L || size > MAX_FILE_BYTES) return null

        val ext = file.extension.lowercase(Locale.ROOT)
        val raw = extractRawText(file, ext, focusHints, maxChars) ?: return null
        val normalized = raw
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

    private fun extractRawText(
        file: File,
        ext: String,
        focusHints: Set<String>,
        maxChars: Int
    ): String? {
        return try {
            when (ext) {
                "xlsx", "xls" -> synchronized(poiLock) {
                    enforceZipBombLimits()
                    extractSpreadsheet(file, focusHints, maxChars)
                }
                "pdf" -> extractPdf(file, maxChars)
                "docx" -> synchronized(poiLock) {
                    enforceZipBombLimits()
                    extractDocx(file, maxChars)
                }
                in SUPPORTED_TEXT_EXTENSIONS -> extractPlainText(file, maxChars)
                else -> null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            System.err.println(
                "[AttachmentContentExtractor] Failed to extract attachment ($ext): ${LlmJsonUtils.sanitizeLogMessage(e.message)}"
            )
            null
        } catch (e: Throwable) {
            if (e !is OutOfMemoryError && e !is StackOverflowError) throw e
            System.err.println("[AttachmentContentExtractor] Resource guard triggered while reading .$ext attachment")
            null
        }
    }

    private fun extractSpreadsheet(
        file: File,
        focusHints: Set<String>,
        maxChars: Int
    ): String {
        val normalizedHints = focusHints
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.length >= 2 }

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

    private fun extractPdf(file: File, maxChars: Int): String {
        Loader.loadPDF(file, "", null, null, IOUtils.createTempFileOnlyStreamCache()).use { doc ->
            val totalPages = doc.numberOfPages
            if (totalPages <= 0) return ""
            val endPage = totalPages.coerceAtMost(PDF_MAX_PAGES)
            val stripper = PDFTextStripper().apply {
                startPage = 1
                this.endPage = endPage
            }
            val text = stripper.getText(doc).trim()
            if (totalPages > endPage && text.length < maxChars - 40) {
                return "$text\n... (showing first $endPage of $totalPages pages)"
            }
            return text
        }
    }

    private fun extractDocx(file: File, maxChars: Int): String {
        OPCPackage.open(file, PackageAccess.READ).use { pkg ->
            XWPFDocument(pkg).use { doc ->
                val sb = StringBuilder()
                appendDocxParagraphs(doc, sb, maxChars)
                if (sb.length < maxChars) {
                    appendDocxTables(doc, sb, maxChars)
                }
                return sb.toString()
            }
        }
    }

    private fun appendDocxParagraphs(doc: XWPFDocument, sb: StringBuilder, maxChars: Int) {
        for (paragraph in doc.paragraphs) {
            val text = paragraph.text?.trim().orEmpty()
            if (text.isNotBlank()) {
                sb.append(text).append('\n')
            }
            if (sb.length >= maxChars) break
        }
    }

    private fun appendDocxTables(doc: XWPFDocument, sb: StringBuilder, maxChars: Int) {
        for (table in doc.tables) {
            appendSingleDocxTable(table, sb, maxChars)
            if (sb.length >= maxChars) break
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
