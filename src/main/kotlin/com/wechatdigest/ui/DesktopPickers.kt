package com.wechatdigest.ui

import com.wechatdigest.core.repository.WeChatMessageRepository
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** Clamps a raw `messagesPerSync` input integer into the shared supported range. */
internal fun clampMessagesPerSync(value: Int): Int =
    value.coerceIn(WeChatMessageRepository.MIN_MESSAGES_PER_SYNC, WeChatMessageRepository.MAX_MESSAGES_PER_SYNC)

internal object DesktopPickers {
    private val isPickerOpen = AtomicBoolean(false)

    /** Opens a native macOS folder picker using AWT [FileDialog] and returns the verified non-symlink directory. */
    fun openDirectoryPicker(title: String, onSelected: (File) -> Unit) {
        launchFileDialog(
            title = title,
            selectDirectory = true,
            allowedExtension = null,
            onSelected = onSelected
        )
    }

    /** Opens a native macOS file picker filtered by [allowedExtension] (e.g. `.gguf`, `.json`). */
    fun openExtensionFilePicker(
        title: String,
        allowedExtension: String,
        onSelected: (File) -> Unit
    ) {
        launchFileDialog(
            title = title,
            selectDirectory = false,
            allowedExtension = allowedExtension.lowercase(Locale.ROOT),
            onSelected = onSelected
        )
    }

    private fun launchFileDialog(
        title: String,
        selectDirectory: Boolean,
        allowedExtension: String?,
        onSelected: (File) -> Unit
    ) {
        if (!isPickerOpen.compareAndSet(false, true)) return
        try {
            SwingUtilities.invokeLater {
                var previousDirProp: String? = null
                var restoredProp = false
                var dialog: FileDialog? = null
                try {
                    previousDirProp = System.getProperty("apple.awt.fileDialogForDirectories")
                    System.setProperty("apple.awt.fileDialogForDirectories", if (selectDirectory) "true" else "false")
                    val owner = Frame.getFrames().firstOrNull { it.isShowing && it.isActive }
                        ?: Frame.getFrames().firstOrNull { it.isShowing }
                    dialog = FileDialog(owner, title, FileDialog.LOAD)
                    if (!selectDirectory && !allowedExtension.isNullOrBlank()) {
                        dialog.filenameFilter = FilenameFilter { _, name ->
                            name.lowercase(Locale.ROOT).endsWith(allowedExtension)
                        }
                    }
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val file = dialog.file
                    if (!dir.isNullOrBlank()) {
                        val chosen = if (file.isNullOrBlank()) File(dir) else File(dir, file)
                        val isAcceptable = isAcceptableSelection(chosen, selectDirectory, allowedExtension)
                        if (isAcceptable) {
                            onSelected(chosen)
                        }
                    }
                } finally {
                    if (previousDirProp == null) {
                        System.clearProperty("apple.awt.fileDialogForDirectories")
                    } else {
                        System.setProperty("apple.awt.fileDialogForDirectories", previousDirProp)
                    }
                    restoredProp = true
                    runCatching { dialog?.dispose() }
                    if (restoredProp) {
                        isPickerOpen.set(false)
                    }
                }
            }
        } catch (e: Throwable) {
            isPickerOpen.set(false)
        }
    }

    internal fun isAcceptableSelection(
        chosen: File,
        selectDirectory: Boolean,
        allowedExtension: String?
    ): Boolean {
        if (Files.isSymbolicLink(chosen.toPath())) return false
        return if (selectDirectory) {
            chosen.isDirectory
        } else {
            val extMatches = allowedExtension.isNullOrBlank() ||
                chosen.name.lowercase(Locale.ROOT).endsWith(allowedExtension.lowercase(Locale.ROOT))
            chosen.isFile && extMatches
        }
    }
}
