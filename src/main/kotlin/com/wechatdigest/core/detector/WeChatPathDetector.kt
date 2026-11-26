package com.wechatdigest.core.detector

import java.io.File
import java.nio.file.Files

data class DiscoveredPath(
    val path: File,
    val label: String,
    val isDecryptedCopy: Boolean,
    val hasContacts: Boolean,
    val hasMessages: Boolean
)

class WeChatPathDetector {

    fun detectCandidates(customRoots: List<File>? = null): List<DiscoveredPath> {
        if (customRoots != null) {
            return customRoots.mapNotNull { dir ->
                if (dir.exists() && dir.isDirectory && !Files.isSymbolicLink(dir.toPath())) {
                    inspectDirectory(dir, label = dir.name)
                } else {
                    null
                }
            }
        }

        val userHome = System.getProperty("user.home") ?: "."
        val userDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return buildList {
            addAll(collectDecryptedCandidates(userHome, userDir))
            addAll(collectXWeChatCandidates(userHome))
            collectAppSupportCandidate(userHome)?.let { add(it) }
        }
    }

    private fun collectDecryptedCandidates(userHome: String, userDir: File): List<DiscoveredPath> {
        val candidateDirs = buildList {
            add(File(userDir, "wcdb-key-tool/decrypted"))
            userDir.parentFile?.let { add(File(it, "wcdb-key-tool/decrypted")) }
            add(File(userHome, "wcdb-key-tool/decrypted"))
            add(File(userHome, ".wcdb-key-tool/decrypted"))
        }.distinctBy { it.absolutePath }

        return candidateDirs.mapNotNull { dir ->
            if (!dir.exists() || !dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return@mapNotNull null
            inspectDirectory(dir, label = "Decrypted WCDB Key Tool Directory")?.copy(isDecryptedCopy = true)
        }
    }

    private fun collectXWeChatCandidates(userHome: String): List<DiscoveredPath> {
        val xwechatFiles = File(userHome, "Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files")
        if (!xwechatFiles.exists() || !xwechatFiles.isDirectory || Files.isSymbolicLink(xwechatFiles.toPath())) {
            return emptyList()
        }
        val wxidDirs = xwechatFiles.listFiles { f ->
            f.isDirectory && !Files.isSymbolicLink(f.toPath()) && (f.name.startsWith("wxid_") || f.name.contains("_"))
        } ?: return emptyList()

        return wxidDirs.mapNotNull { accountDir ->
            if (Files.isSymbolicLink(accountDir.toPath())) return@mapNotNull null
            val dbStorage = File(accountDir, "db_storage")
            val targetDir = if (dbStorage.exists() && dbStorage.isDirectory && !Files.isSymbolicLink(dbStorage.toPath())) {
                dbStorage
            } else {
                accountDir
            }
            if (Files.isSymbolicLink(targetDir.toPath())) return@mapNotNull null
            inspectDirectory(targetDir, label = "WeChat Account: ${accountDir.name}")
        }
    }

    fun collectAppSupportCandidate(userHome: String): DiscoveredPath? {
        val appSupportDir = File(userHome, "Library/Application Support/com.tencent.xinWeChat")
        if (!appSupportDir.exists() || !appSupportDir.isDirectory || Files.isSymbolicLink(appSupportDir.toPath())) return null
        return inspectDirectory(appSupportDir, label = "WeChat App Support Directory")
    }

    fun isValidWeChatDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return false
        val hasContacts = hasContactDb(dir)
        val hasMessages = hasMessageDbs(dir)
        return hasContacts && hasMessages
    }

    private fun inspectDirectory(dir: File, label: String): DiscoveredPath? {
        if (!dir.exists() || !dir.isDirectory || Files.isSymbolicLink(dir.toPath())) {
            return null
        }
        val hasContacts = hasContactDb(dir)
        val hasMessages = hasMessageDbs(dir)
        if (!hasContacts && !hasMessages) {
            return null
        }
        val contactDir = File(dir, "contact")
        val nestedContact = File(contactDir, "contact.db")
        val flatContact = File(dir, "contact.db")
        val contactFile = when {
            !Files.isSymbolicLink(contactDir.toPath()) && nestedContact.isFile && !Files.isSymbolicLink(nestedContact.toPath()) -> nestedContact
            flatContact.isFile && !Files.isSymbolicLink(flatContact.toPath()) -> flatContact
            else -> null
        }
        val isEncrypted = contactFile?.let { com.wechatdigest.core.db.WcdbDecryptor.isEncrypted(it) } ?: true
        val hasKeys = com.wechatdigest.core.db.WcdbDecryptor.findKeysFile(baseDir = dir) != null

        val finalLabel = if (!isEncrypted) {
            "$label (Plaintext / Decrypted)"
        } else if (hasKeys) {
            "$label (Encrypted, auto-decrypt ready)"
        } else {
            "$label (Encrypted, requires all_keys.json)"
        }

        return DiscoveredPath(
            path = dir,
            label = finalLabel,
            isDecryptedCopy = !isEncrypted,
            hasContacts = hasContacts,
            hasMessages = hasMessages
        )
    }

    private fun hasContactDb(dir: File): Boolean {
        val flat = File(dir, "contact.db")
        if (flat.isFile && !Files.isSymbolicLink(flat.toPath())) return true
        val contactDir = File(dir, "contact")
        val nested = File(contactDir, "contact.db")
        return contactDir.isDirectory &&
            !Files.isSymbolicLink(contactDir.toPath()) &&
            nested.isFile &&
            !Files.isSymbolicLink(nested.toPath())
    }

    private val SHARD_FILE_REGEX = Regex("^message_\\d+\\.db$")

    private fun hasMessageDbs(dir: File): Boolean {
        val messageSubdir = File(dir, "message")
        if (messageSubdir.exists() && messageSubdir.isDirectory && !Files.isSymbolicLink(messageSubdir.toPath())) {
            val shard = messageSubdir.listFiles { f ->
                f.isFile && !Files.isSymbolicLink(f.toPath()) && SHARD_FILE_REGEX.matches(f.name)
            }
            if (!shard.isNullOrEmpty()) return true
        }
        val directShard = dir.listFiles { f ->
            f.isFile && !Files.isSymbolicLink(f.toPath()) && SHARD_FILE_REGEX.matches(f.name)
        }
        return !directShard.isNullOrEmpty()
    }

    companion object {
        /** Expands a leading `~/` against `user.home` for user-configured filesystem paths. */
        internal fun resolveUserPath(rawPath: String): File? {
            val trimmed = rawPath.trim()
            if (trimmed.isBlank()) return null
            val expanded = when {
                trimmed == "~" -> System.getProperty("user.home").orEmpty()
                trimmed.startsWith("~/") -> "${System.getProperty("user.home").orEmpty()}/${trimmed.removePrefix("~/")}"
                else -> trimmed
            }
            return expanded.takeIf { it.isNotBlank() }?.let { File(it) }
        }
    }
}
