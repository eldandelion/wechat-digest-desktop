package com.wechatdigest.core.detector

import java.io.File

data class DiscoveredPath(
    val path: File,
    val label: String,
    val isDecryptedCopy: Boolean,
    val hasContacts: Boolean,
    val hasMessages: Boolean
)

class WeChatPathDetector {

    fun detectCandidates(customRoots: List<File>? = null): List<DiscoveredPath> {
        val results = mutableListOf<DiscoveredPath>()

        if (customRoots != null) {
            for (dir in customRoots) {
                if (dir.exists() && dir.isDirectory) {
                    val candidate = inspectDirectory(dir, label = dir.name)
                    if (candidate != null) {
                        results.add(candidate)
                    }
                }
            }
            return results
        }

        val userHome = System.getProperty("user.home") ?: "."

        // 1. Check standard decrypted WCDB key tool directories under user home
        val decryptedCandidates = listOf(
            File(userHome, "wcdb-key-tool/decrypted"),
            File(userHome, ".wcdb-key-tool/decrypted")
        )
        for (localDecrypted in decryptedCandidates) {
            if (localDecrypted.exists() && localDecrypted.isDirectory) {
                val candidate = inspectDirectory(localDecrypted, label = "Decrypted WCDB Key Tool Directory")
                if (candidate != null) {
                    results.add(candidate.copy(isDecryptedCopy = true))
                }
            }
        }

        // 2. Scan standard macOS WeChat Container (xwechat_files)
        val xwechatFiles = File(userHome, "Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files")
        if (xwechatFiles.exists() && xwechatFiles.isDirectory) {
            val wxidDirs = xwechatFiles.listFiles { f -> f.isDirectory && (f.name.startsWith("wxid_") || f.name.contains("_")) }
            if (wxidDirs != null) {
                for (accountDir in wxidDirs) {
                    val dbStorage = File(accountDir, "db_storage")
                    val targetDir = if (dbStorage.exists() && dbStorage.isDirectory) dbStorage else accountDir
                    val candidate = inspectDirectory(targetDir, label = "WeChat Account: ${accountDir.name}")
                    if (candidate != null) {
                        results.add(candidate)
                    }
                }
            }
        }

        // 3. Scan Application Support legacy container
        val appSupportDir = File(userHome, "Library/Application Support/com.tencent.xinWeChat")
        if (appSupportDir.exists() && appSupportDir.isDirectory) {
            val candidate = inspectDirectory(appSupportDir, label = "WeChat App Support Directory")
            if (candidate != null) {
                results.add(candidate)
            }
        }

        return results
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
