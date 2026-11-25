package com.wechatdigest.core.llm.local

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Locale

/**
 * Download mirror options for curated GGUF weights.
 */
enum class LocalModelMirror(val label: String, val baseUrl: String) {
    HF_OFFICIAL("Hugging Face", "https://huggingface.co"),
    MODELSCOPE("ModelScope (China Direct)", "https://modelscope.cn"),
    HF_MIRROR("HF-Mirror", "https://hf-mirror.com");

    companion object {
        fun fromKey(raw: String?): LocalModelMirror {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: HF_OFFICIAL
        }
    }
}

/**
 * Supported macOS CPU/GPU architectures for `llama-server` binaries.
 */
enum class MacArchitecture(
    val assetArch: String,
    val displayLabel: String,
    val supportsMetal: Boolean
) {
    ARM64("arm64", "Apple Silicon", supportsMetal = true),
    X64("x64", "Intel x64", supportsMetal = false);

    companion object {
        fun detect(
            osName: String = System.getProperty("os.name").orEmpty(),
            osArch: String = System.getProperty("os.arch").orEmpty()
        ): MacArchitecture? {
            val normalizedOs = osName.lowercase(Locale.ROOT)
            if (!normalizedOs.contains("mac") && !normalizedOs.contains("darwin")) {
                return null
            }
            val normalizedArch = osArch.lowercase(Locale.ROOT)
            return when {
                normalizedArch.contains("aarch64") || normalizedArch.contains("arm64") -> ARM64
                normalizedArch.contains("x86_64") || normalizedArch.contains("amd64") -> X64
                else -> null
            }
        }
    }
}

/**
 * Curated GGUF model specification with pinned size and SHA-256 verification metadata.
 */
data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val parameterLabel: String,
    val quantization: String,
    val repoId: String,
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
    val minRamGb: Int,
    val recommendedRamGb: Int,
    val description: String,
    val disableThinkingWithBudgetZero: Boolean = false,
    val isRecommended: Boolean = false
)

/**
 * Persisted reference to a user-imported `.gguf` model file on disk.
 */
@Serializable
data class ImportedLocalModel(
    val id: String,
    val displayName: String,
    val filePath: String,
    val sizeBytes: Long,
    val importedAtEpochMs: Long
)

/**
 * Resolved local model ready to pass to [LlamaServerManager].
 */
data class ResolvedLocalModel(
    val id: String,
    val displayName: String,
    val file: File,
    val disableThinkingWithBudgetZero: Boolean,
    val isImported: Boolean
)

/**
 * Pinned upstream `ggml-org/llama.cpp` release metadata with SHA-256 checksums for macOS binaries.
 */
data class LlamaRuntimeRelease(
    val tag: String,
    val arm64AssetName: String,
    val arm64SizeBytes: Long,
    val arm64Sha256: String,
    val x64AssetName: String,
    val x64SizeBytes: Long,
    val x64Sha256: String
) {
    fun assetNameFor(arch: MacArchitecture): String = when (arch) {
        MacArchitecture.ARM64 -> arm64AssetName
        MacArchitecture.X64 -> x64AssetName
    }

    fun expectedSizeFor(arch: MacArchitecture): Long = when (arch) {
        MacArchitecture.ARM64 -> arm64SizeBytes
        MacArchitecture.X64 -> x64SizeBytes
    }

    fun expectedSha256For(arch: MacArchitecture): String = when (arch) {
        MacArchitecture.ARM64 -> arm64Sha256
        MacArchitecture.X64 -> x64Sha256
    }

    fun downloadUrlFor(arch: MacArchitecture): String =
        "https://github.com/ggml-org/llama.cpp/releases/download/$tag/${assetNameFor(arch)}"
}

/**
 * Pinned release binaries and curated single-file GGUF catalog tested for JSON event extraction.
 */
object LocalModelCatalog {
    val PINNED_RUNTIME = LlamaRuntimeRelease(
        tag = "b12679",
        arm64AssetName = "llama-b12679-bin-macos-arm64.tar.gz",
        arm64SizeBytes = 16_820_140L,
        arm64Sha256 = "4641893fccfdb786647e2c6264d516d3f28449dcb4adb4ef3e3cde70fc2b172f",
        x64AssetName = "llama-b12679-bin-macos-x64.tar.gz",
        x64SizeBytes = 12_540_794L,
        x64Sha256 = "af84bb30e32e5e793e7ba228d087338038273e51908296ac8c1fe3f110091d99"
    )

    val CURATED_MODELS: List<LocalModelSpec> = listOf(
        LocalModelSpec(
            id = "qwen2.5-3b-instruct-q4km",
            displayName = "Qwen2.5 3B Instruct",
            parameterLabel = "3B",
            quantization = "Q4_K_M",
            repoId = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            filename = "qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2_104_932_768L,
            sha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            minRamGb = 8,
            recommendedRamGb = 8,
            description = "Compact and fast bilingual model suitable for 8 GB Macs.",
            disableThinkingWithBudgetZero = false
        ),
        LocalModelSpec(
            id = "qwen3-4b-instruct-2507-q4km",
            displayName = "Qwen3 4B Instruct",
            parameterLabel = "4B",
            quantization = "Q4_K_M",
            repoId = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
            filename = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            sizeBytes = 2_497_281_120L,
            sha256 = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
            minRamGb = 8,
            recommendedRamGb = 8,
            description = "Best balance of speed, Chinese/English comprehension, and strict JSON formatting.",
            disableThinkingWithBudgetZero = false,
            isRecommended = true
        ),
        LocalModelSpec(
            id = "qwen2.5-7b-instruct-q4km",
            displayName = "Qwen2.5 7B Instruct",
            parameterLabel = "7B",
            quantization = "Q4_K_M",
            repoId = "bartowski/Qwen2.5-7B-Instruct-GGUF",
            filename = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            sizeBytes = 4_683_074_240L,
            sha256 = "65b8fcd92af6b4fefa935c625d1ac27ea29dcb6ee14589c55a8f115ceaaa1423",
            minRamGb = 16,
            recommendedRamGb = 16,
            description = "Higher extraction accuracy for dense multi-topic group notices on 16 GB+ Macs.",
            disableThinkingWithBudgetZero = false
        ),
        LocalModelSpec(
            id = "qwen3-8b-q4km",
            displayName = "Qwen3 8B",
            parameterLabel = "8B",
            quantization = "Q4_K_M",
            repoId = "Qwen/Qwen3-8B-GGUF",
            filename = "Qwen3-8B-Q4_K_M.gguf",
            sizeBytes = 5_027_783_488L,
            sha256 = "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
            minRamGb = 16,
            recommendedRamGb = 16,
            description = "Highest reasoning quality for complex notices; runs with thinking disabled for JSON reliability.",
            disableThinkingWithBudgetZero = true
        )
    )

    fun findById(id: String?): LocalModelSpec? {
        if (id.isNullOrBlank()) return null
        return CURATED_MODELS.firstOrNull { it.id == id }
    }

    fun downloadUrl(spec: LocalModelSpec, mirror: LocalModelMirror): String = when (mirror) {
        LocalModelMirror.HF_OFFICIAL -> "https://huggingface.co/${spec.repoId}/resolve/main/${spec.filename}"
        LocalModelMirror.HF_MIRROR -> "https://hf-mirror.com/${spec.repoId}/resolve/main/${spec.filename}"
        LocalModelMirror.MODELSCOPE -> "https://modelscope.cn/models/${spec.repoId}/resolve/master/${spec.filename}"
    }

    fun recommendedModelForRam(ramGb: Int): LocalModelSpec {
        return if (ramGb >= 16) {
            CURATED_MODELS.first { it.id == "qwen3-4b-instruct-2507-q4km" }
        } else {
            CURATED_MODELS.first { it.id == "qwen2.5-3b-instruct-q4km" }
        }
    }
}

/**
 * Snapshot of macOS hardware capabilities relevant to local GGUF sizing.
 */
data class SystemHardwareInfo(
    val totalRamBytes: Long,
    val totalRamGb: Int,
    val architecture: MacArchitecture?,
    val freeDiskBytes: Long
) {
    companion object {
        fun detect(storageDir: File): SystemHardwareInfo {
            val totalMemBytes = runCatching {
                val mxBean = ManagementFactory.getOperatingSystemMXBean()
                (mxBean as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: 0L
            }.getOrDefault(0L)
            val totalRamGb = if (totalMemBytes > 0L) {
                kotlin.math.round(totalMemBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)).toInt().coerceAtLeast(1)
            } else {
                0
            }
            val probeDir = generateSequence(storageDir) { it.parentFile }
                .firstOrNull { it.exists() }
            val freeBytes = runCatching { probeDir?.usableSpace ?: 0L }.getOrDefault(0L)
            return SystemHardwareInfo(
                totalRamBytes = totalMemBytes,
                totalRamGb = totalRamGb,
                architecture = MacArchitecture.detect(),
                freeDiskBytes = freeBytes
            )
        }
    }
}

/**
 * Per-model download lifecycle state surfaced to Compose UI.
 */
sealed class LocalModelDownloadState {
    data object Idle : LocalModelDownloadState()
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long
    ) : LocalModelDownloadState() {
        val progressFraction: Float
            get() = if (totalBytes > 0L) {
                (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
    }
    data object Verifying : LocalModelDownloadState()
    data class Failed(val message: String) : LocalModelDownloadState()
}

/**
 * Runtime binary (`llama-server`) installation state.
 */
sealed class LocalRuntimeState {
    data object NotInstalled : LocalRuntimeState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : LocalRuntimeState()
    data object Verifying : LocalRuntimeState()
    data object Extracting : LocalRuntimeState()
    data class Ready(val tag: String, val executablePath: String) : LocalRuntimeState()
    data class Failed(val message: String) : LocalRuntimeState()
}

/**
 * Managed `llama-server` child-process state.
 */
sealed class LocalServerState {
    data object Stopped : LocalServerState()
    data class Starting(val modelId: String, val modelName: String) : LocalServerState()
    data class Running(val modelId: String, val modelName: String, val endpointUrl: String) : LocalServerState()
    data class Error(val message: String) : LocalServerState()
}

/**
 * Central path and atomic-file utilities under `~/Library/Application Support/WeChatDigest/`.
 */
class LocalModelPaths(val baseDir: File = defaultBaseDir()) {
    val modelsDir: File get() = File(baseDir, "models")
    val runtimeDir: File get() = File(baseDir, "runtime")
    val importedManifestFile: File get() = File(modelsDir, "imported-models.json")

    fun modelFile(spec: LocalModelSpec): File = File(modelsDir, spec.filename)
    fun partialModelFile(spec: LocalModelSpec): File = File(modelsDir, "${spec.filename}.part")
    fun runtimeTagDir(tag: String): File = File(runtimeDir, tag)

    companion object {
        fun defaultBaseDir(): File {
            val userHome = System.getProperty("user.home") ?: "."
            return File(userHome, "Library/Application Support/WeChatDigest")
        }

        fun ensureOwnerOnlyDirectory(dir: File): File {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            setOwnerOnlyPermissions(dir, isDirectory = true)
            return dir
        }

        fun setOwnerOnlyPermissions(path: File, isDirectory: Boolean, executable: Boolean = false) {
            if (!path.exists()) return
            val perms = buildSet {
                add(PosixFilePermission.OWNER_READ)
                add(PosixFilePermission.OWNER_WRITE)
                if (isDirectory || executable) {
                    add(PosixFilePermission.OWNER_EXECUTE)
                }
            }
            runCatching { Files.setPosixFilePermissions(path.toPath(), perms) }
        }

        fun atomicMove(source: File, target: File, executable: Boolean = false): File {
            ensureOwnerOnlyDirectory(target.parentFile)
            setOwnerOnlyPermissions(source, isDirectory = false, executable = executable)
            runCatching {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.getOrElse {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(target, isDirectory = false, executable = executable)
            return target
        }
    }
}

fun formatModelBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) {
        return String.format(Locale.US, "%.2f GB", gb)
    }
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) {
        return String.format(Locale.US, "%.0f MB", mb)
    }
    val kb = bytes / 1024.0
    return String.format(Locale.US, "%.0f KB", kb)
}

internal suspend fun computeFileSha256HexCancellable(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    FileInputStream(file).use { input ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun sha256HexEqualsConstantTime(actualHex: String, expectedHex: String): Boolean {
    return MessageDigest.isEqual(
        actualHex.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII),
        expectedHex.lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII)
    )
}
