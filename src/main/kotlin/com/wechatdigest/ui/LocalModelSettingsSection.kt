package com.wechatdigest.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wechatdigest.core.llm.local.ImportedLocalModel
import com.wechatdigest.core.llm.local.LocalModelCatalog
import com.wechatdigest.core.llm.local.LocalModelDownloadState
import com.wechatdigest.core.llm.local.LocalModelMirror
import com.wechatdigest.core.llm.local.LocalModelSpec
import com.wechatdigest.core.llm.local.LocalRuntimeState
import com.wechatdigest.core.llm.local.LocalServerState
import com.wechatdigest.core.llm.local.SystemHardwareInfo
import com.wechatdigest.core.llm.local.formatModelBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalModelSettingsSection(
    hardwareInfo: SystemHardwareInfo,
    catalogModels: List<LocalModelSpec>,
    downloadedModelIds: Set<String>,
    importedModels: List<ImportedLocalModel>,
    downloadStates: Map<String, LocalModelDownloadState>,
    runtimeState: LocalRuntimeState,
    serverState: LocalServerState,
    activeModelId: String,
    selectedMirror: LocalModelMirror,
    onSelectMirror: (LocalModelMirror) -> Unit,
    onSelectModel: (String) -> Unit,
    onDownloadModel: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDeleteDownloadedModel: (String) -> Unit,
    onImportGgufFile: (File) -> Unit,
    onRemoveImportedModel: (String) -> Unit,
    onUnloadServer: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HardwareAndRuntimeBanner(
            hardwareInfo = hardwareInfo,
            runtimeState = runtimeState,
            serverState = serverState,
            onUnloadServer = onUnloadServer
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Model Download Source",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LocalModelMirror.entries.forEach { mirror ->
                    val selected = selectedMirror == mirror
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectMirror(mirror) },
                        label = { Text(mirror.label) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (selected) Icons.Filled.Public else Icons.Outlined.Public,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Built-in Model Catalog",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            val recommendedSpecId = remember(hardwareInfo.totalRamGb) {
                LocalModelCatalog.recommendedModelForRam(hardwareInfo.totalRamGb).id
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                catalogModels.forEachIndexed { index, spec ->
                    key(spec.id) {
                        val isDownloaded = spec.id in downloadedModelIds
                        val isSelected = activeModelId == spec.id
                        val downloadState = downloadStates[spec.id] ?: LocalModelDownloadState.Idle
                        val lowRamWarning = hardwareInfo.totalRamGb in 1 until spec.minRamGb

                        CatalogModelRow(
                            spec = spec,
                            shape = segmentedItemShape(index = index, totalCount = catalogModels.size),
                            isDownloaded = isDownloaded,
                            isSelected = isSelected,
                            isRecommendedForHardware = spec.id == recommendedSpecId,
                            downloadState = downloadState,
                            lowRamWarning = lowRamWarning,
                            systemRamGb = hardwareInfo.totalRamGb,
                            onSelect = { onSelectModel(spec.id) },
                            onDownload = { onDownloadModel(spec.id) },
                            onCancelDownload = { onCancelDownload(spec.id) },
                            onDelete = { onDeleteDownloadedModel(spec.id) }
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Custom Local Models (.gguf)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = { openGgufFilePicker(onImportGgufFile) }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import .gguf File...")
                }
            }
            if (importedModels.isEmpty()) {
                Text(
                    text = "You can also import an existing single-file .gguf model already stored on your Mac.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    importedModels.forEachIndexed { index, imported ->
                        key(imported.id) {
                            ImportedModelRow(
                                model = imported,
                                shape = segmentedItemShape(index = index, totalCount = importedModels.size),
                                isSelected = activeModelId == imported.id,
                                onSelect = { onSelectModel(imported.id) },
                                onRemove = { onRemoveImportedModel(imported.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareAndRuntimeBanner(
    hardwareInfo: SystemHardwareInfo,
    runtimeState: LocalRuntimeState,
    serverState: LocalServerState,
    onUnloadServer: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val archLabel = hardwareInfo.architecture?.let { arch ->
                val accel = if (arch.supportsMetal) "Metal GPU" else "CPU mode"
                "${arch.displayLabel} ($accel)"
            } ?: "Unsupported OS/Architecture"
            val ramText = if (hardwareInfo.totalRamGb > 0) "${hardwareInfo.totalRamGb} GB RAM" else "Unknown RAM"
            val diskText = if (hardwareInfo.freeDiskBytes > 0L) "${formatModelBytes(hardwareInfo.freeDiskBytes)} free" else ""
            Text(
                text = listOfNotNull(archLabel, ramText, diskText.takeIf { it.isNotBlank() }).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )

            val runtimeText = when (runtimeState) {
                is LocalRuntimeState.NotInstalled -> "Inference runtime (llama-server ~17 MB) downloads automatically on first run."
                is LocalRuntimeState.Downloading -> {
                    val pct = if (runtimeState.totalBytes > 0) {
                        ((runtimeState.bytesDownloaded * 100) / runtimeState.totalBytes).coerceIn(0, 100)
                    } else 0
                    "Downloading runtime: $pct% (${formatModelBytes(runtimeState.bytesDownloaded)})"
                }
                is LocalRuntimeState.Verifying -> "Verifying runtime checksum..."
                is LocalRuntimeState.Extracting -> "Installing local inference runtime..."
                is LocalRuntimeState.Ready -> "Runtime ready (llama-server ${runtimeState.tag})"
                is LocalRuntimeState.Failed -> "Runtime error: ${runtimeState.message}"
            }
            Text(
                text = runtimeText,
                style = MaterialTheme.typography.bodySmall,
                color = if (runtimeState is LocalRuntimeState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (serverText, isError) = when (serverState) {
                    is LocalServerState.Stopped -> "Server: Idle (loads automatically on sync or translate)" to false
                    is LocalServerState.Starting -> "Server: Loading ${serverState.modelName}..." to false
                    is LocalServerState.Running -> "Server: Loaded ${serverState.modelName}" to false
                    is LocalServerState.Error -> "Server error: ${serverState.message}" to true
                }
                Text(
                    text = serverText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (serverState is LocalServerState.Running || serverState is LocalServerState.Starting) {
                    TextButton(onClick = onUnloadServer) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Unload")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogModelRow(
    spec: LocalModelSpec,
    shape: RoundedCornerShape,
    isDownloaded: Boolean,
    isSelected: Boolean,
    isRecommendedForHardware: Boolean,
    downloadState: LocalModelDownloadState,
    lowRamWarning: Boolean,
    systemRamGb: Int,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val isInitializing = downloadState is LocalModelDownloadState.Starting
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        shape = shape,
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .shimmerOverlay(
                enabled = isInitializing,
                highlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            )
            .let { base -> if (isDownloaded && !isSelected) base.clickable(onClick = onSelect) else base }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${spec.displayName} (${spec.quantization})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (isRecommendedForHardware) {
                            ModelBadge(
                                text = "Recommended for ${if (systemRamGb > 0) "${systemRamGb} GB" else "Mac"}",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (isDownloaded && isSelected) {
                            ModelBadge(
                                text = "Active",
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = "${spec.parameterLabel} • ${formatModelBytes(spec.sizeBytes)} • Min ${spec.minRamGb} GB RAM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = spec.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (lowRamWarning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "May swap heavily on $systemRamGb GB RAM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                when {
                    downloadState is LocalModelDownloadState.Starting ||
                        downloadState is LocalModelDownloadState.Downloading ||
                        downloadState is LocalModelDownloadState.Verifying -> {
                        OutlinedButton(onClick = onCancelDownload) {
                            Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                    isDownloaded && isSelected -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Active model",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = onDelete) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete downloaded model",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    isDownloaded -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = onSelect) { Text("Use") }
                            IconButton(onClick = onDelete) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete downloaded model",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    else -> {
                        IconButton(onClick = onDownload) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download ${spec.displayName}",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            when (downloadState) {
                is LocalModelDownloadState.Starting -> {
                    Text(
                        text = "Initializing download…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                is LocalModelDownloadState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { downloadState.progressFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val pct = (downloadState.progressFraction * 100f).toInt().coerceIn(0, 100)
                    val speedText = if (downloadState.bytesPerSecond > 0L) {
                        " • ${formatModelBytes(downloadState.bytesPerSecond)}/s"
                    } else ""
                    Text(
                        text = "$pct% (${formatModelBytes(downloadState.bytesDownloaded)} / ${formatModelBytes(downloadState.totalBytes)})$speedText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is LocalModelDownloadState.Verifying -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Verifying SHA-256 integrity...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                is LocalModelDownloadState.Failed -> {
                    Text(
                        text = downloadState.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                LocalModelDownloadState.Idle -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportedModelRow(
    model: ImportedLocalModel,
    shape: RoundedCornerShape,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    val fileExists by produceState(initialValue = true, key1 = model.filePath) {
        value = withContext(Dispatchers.IO) {
            File(model.filePath).let { it.exists() && it.isFile }
        }
    }
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        shape = shape,
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .let { base -> if (fileExists && !isSelected) base.clickable(onClick = onSelect) else base }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (isSelected) {
                        ModelBadge(
                            text = "Active",
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = "${formatModelBytes(model.sizeBytes)} • ${model.filePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (!fileExists) {
                    Text(
                        text = "File missing on disk",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Active model",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    OutlinedButton(onClick = onSelect, enabled = fileExists) { Text("Use") }
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Remove imported model",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelBadge(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

internal fun openGgufFilePicker(onFileSelected: (File) -> Unit) {
    DesktopPickers.openExtensionFilePicker(
        title = "Select Local GGUF Model",
        allowedExtension = ".gguf",
        onSelected = onFileSelected
    )
}
