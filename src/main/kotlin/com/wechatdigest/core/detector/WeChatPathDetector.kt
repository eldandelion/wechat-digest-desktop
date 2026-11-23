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
        if (!xwechatFiles.exists() || !xwechatFiles.isDirectory) return emptyList()
        val wxidDirs = xwechatFiles.listFiles { f ->
            f.isDirectory && (f.name.startsWith("wxid_") || f.name.contains("_"))
        } ?: return emptyList()

        return wxidDirs.mapNotNull { accountDir ->
            val dbStorage = File(accountDir, "db_storage")
            val targetDir = if (dbStorage.exists() && dbStorage.isDirectory) dbStorage else accountDir
            inspectDirectory(targetDir, label = "WeChat Account: ${accountDir.name}")
        }
    }

    private fun collectAppSupportCandidate(userHome: String): DiscoveredPath? {
        val appSupportDir = File(userHome, "Library/Application Support/com.tencent.xinWeChat")
        if (!appSupportDir.exists() || !appSupportDir.isDirectory) return null
        return inspectDirectory(appSupportDir, label = "WeChat App Support Directory")
    }

    fun isValidWeChatDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val hasContacts = hasContactDb(dir)
        val hasMessages = hasMessageDbs(dir)
        return hasContacts && hasMessages
    }

    private fun inspectDirectory(dir: File, label: String): DiscoveredPath? {
        val hasContacts = hasContactDb(dir)
        val hasMessages = hasMessageDbs(dir)
        if (!hasContacts && !hasMessages) {
            return null
        }
        val contactFile = File(dir, "contact/contact.db").takeIf { it.exists() } ?: File(dir, "contact.db")
        val isEncrypted = com.wechatdigest.core.db.WcdbDecryptor.isEncrypted(contactFile)
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
        if (File(dir, "contact.db").exists()) return true
        if (File(dir, "contact/contact.db").exists()) return true
        return false
    }

    private fun hasMessageDbs(dir: File): Boolean {
        val messageSubdir = File(dir, "message")
        if (messageSubdir.exists() && messageSubdir.isDirectory) {
            val shard = messageSubdir.listFiles { f -> f.isFile && f.name.startsWith("message_") && f.name.endsWith(".db") }
            if (!shard.isNullOrEmpty()) return true
        }
        val directShard = dir.listFiles { f -> f.isFile && f.name.startsWith("message_") && f.name.endsWith(".db") }
        return !directShard.isNullOrEmpty()
    }
}
