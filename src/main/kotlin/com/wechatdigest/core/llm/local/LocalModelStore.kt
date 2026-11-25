package com.wechatdigest.core.llm.local

import com.wechatdigest.core.llm.LlmJsonUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Manages locally downloaded GGUF models under `models/` and user-imported `.gguf` references.
 */
class LocalModelStore(
    val paths: LocalModelPaths = LocalModelPaths()
) {
    private val storeLock = Any()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _downloadedModelIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModelIds: StateFlow<Set<String>> = _downloadedModelIds.asStateFlow()

    private val _importedModels = MutableStateFlow<List<ImportedLocalModel>>(emptyList())
    val importedModels: StateFlow<List<ImportedLocalModel>> = _importedModels.asStateFlow()

    init {
        refresh()
    }

    fun detectHardwareInfo(): SystemHardwareInfo = SystemHardwareInfo.detect(paths.modelsDir)

    /**
     * Re-scans `models/` for valid downloaded catalog GGUFs and reloads `imported-models.json`.
     */
    fun refresh() {
        synchronized(storeLock) {
            val catalogIds = LocalModelCatalog.CURATED_MODELS
                .filter { spec -> validateGgufFile(paths.modelFile(spec)) }
                .map { it.id }
                .toSet()
            val imported = loadImportedModelsInternal()
            _downloadedModelIds.value = catalogIds
            _importedModels.value = imported
        }
    }

    /**
     * Validates that [file] is a regular non-symlink file starting with the 4-byte `GGUF` magic header.
     */
    fun validateGgufFile(file: File): Boolean {
        val path = file.toPath()
        if (Files.isSymbolicLink(path) || !file.exists() || !file.isFile || file.length() <= GGUF_HEADER_MIN_BYTES) {
            return false
        }
        return runCatching {
            val magic = ByteArray(4)
            FileInputStream(file).use { input ->
                val read = input.read(magic)
                read == 4 && magic.contentEquals(GGUF_MAGIC_BYTES)
            }
        }.getOrDefault(false)
    }

    /**
     * Resolves [modelId] to a ready-to-serve catalog or imported `.gguf` model on disk, or `null` if unavailable.
     */
    fun resolveModel(modelId: String): ResolvedLocalModel? {
        if (modelId.isBlank()) return null
        LocalModelCatalog.findById(modelId)?.let { spec ->
            val file = paths.modelFile(spec)
            if (validateGgufFile(file)) {
                return ResolvedLocalModel(
                    id = spec.id,
                    displayName = spec.displayName,
                    file = file,
                    disableThinkingWithBudgetZero = spec.disableThinkingWithBudgetZero,
                    isImported = false
                )
            }
            return null
        }

        val imported = synchronized(storeLock) {
            _importedModels.value.firstOrNull { it.id == modelId }
        } ?: return null
        val file = File(imported.filePath)
        if (!validateGgufFile(file)) return null
        val disableThinking = imported.displayName.contains("qwen3", ignoreCase = true) &&
            !imported.displayName.contains("instruct", ignoreCase = true)
        return ResolvedLocalModel(
            id = imported.id,
            displayName = imported.displayName,
            file = file,
            disableThinkingWithBudgetZero = disableThinking,
            isImported = true
        )
    }

    /**
     * Registers an existing `.gguf` file on disk without copying multi-GB weights.
     */
    fun importGgufFile(file: File): Result<ImportedLocalModel> = synchronized(storeLock) {
        runCatching {
            require(file.exists() && file.isFile) { "Selected file does not exist." }
            require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links cannot be imported as local models." }
            require(file.name.lowercase().endsWith(".gguf")) { "Only .gguf model files are supported." }
            require(file.canRead()) { "Selected .gguf file is not readable." }
            require(validateGgufFile(file)) { "Selected file is not a valid GGUF model (missing GGUF header)." }

            val canonical = file.canonicalFile
            val digestPrefix = sha256Prefix(canonical.absolutePath)
            val id = "imported:$digestPrefix"
            val currentList = loadImportedModelsInternal()
            val existing = currentList.firstOrNull {
                runCatching { File(it.filePath).canonicalPath }.getOrNull() == canonical.absolutePath
            }
            if (existing != null) {
                return@runCatching existing
            }

            val displayName = canonical.nameWithoutExtension
                .ifBlank { canonical.name }
                .take(80)
            val entry = ImportedLocalModel(
                id = id,
                displayName = displayName,
                filePath = canonical.absolutePath,
                sizeBytes = canonical.length(),
                importedAtEpochMs = System.currentTimeMillis()
            )
            saveImportedModelsInternal(currentList + entry)
            _importedModels.value = currentList + entry
            entry
        }
    }

    /**
     * Removes an imported model entry from app metadata without deleting the user's `.gguf` file on disk.
     */
    fun removeImportedModel(modelId: String): Boolean = synchronized(storeLock) {
        val currentList = loadImportedModelsInternal()
        val updated = currentList.filterNot { it.id == modelId }
        if (updated.size == currentList.size) return@synchronized false
        saveImportedModelsInternal(updated)
        _importedModels.value = updated
        true
    }

    /**
     * Deletes an app-downloaded catalog `.gguf` (and any `.part`) strictly inside [LocalModelPaths.modelsDir].
     */
    fun deleteCatalogModel(modelId: String): Boolean = synchronized(storeLock) {
        val spec = LocalModelCatalog.findById(modelId) ?: return@synchronized false
        val modelsRoot = paths.modelsDir.canonicalFile
        var deletedAny = false
        for (candidate in listOf(paths.modelFile(spec), paths.partialModelFile(spec))) {
            val candidatePath = candidate.toPath()
            if (Files.isSymbolicLink(candidatePath)) continue
            if (!candidate.exists()) continue
            val canonicalTarget = candidate.canonicalFile
            if (!canonicalTarget.toPath().startsWith(modelsRoot.toPath())) continue
            if (canonicalTarget.delete()) {
                deletedAny = true
            }
        }
        refresh()
        deletedAny
    }

    private fun loadImportedModelsInternal(): List<ImportedLocalModel> {
        val manifest = paths.importedManifestFile
        if (!manifest.exists() || !manifest.isFile || Files.isSymbolicLink(manifest.toPath())) {
            return emptyList()
        }
        return runCatching {
            json.decodeFromString<List<ImportedLocalModel>>(manifest.readText())
        }.getOrElse { err ->
            System.err.println("[LocalModelStore] Failed to load imported-models manifest: ${LlmJsonUtils.sanitizeLogMessage(err.message)}")
            emptyList()
        }
    }

    private fun saveImportedModelsInternal(models: List<ImportedLocalModel>) {
        LocalModelPaths.ensureOwnerOnlyDirectory(paths.modelsDir)
        val tempFile = Files.createTempFile(paths.modelsDir.toPath(), "imported-models-", ".json.tmp").toFile()
        try {
            LocalModelPaths.setOwnerOnlyPermissions(tempFile, isDirectory = false)
            tempFile.writeText(json.encodeToString(models))
            LocalModelPaths.atomicMove(tempFile, paths.importedManifestFile)
        } finally {
            runCatching { if (tempFile.exists()) tempFile.delete() }
        }
    }

    private fun sha256Prefix(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val GGUF_MAGIC_BYTES = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
        private const val GGUF_HEADER_MIN_BYTES = 16L
    }
}
