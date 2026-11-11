package com.orion.store.morphe.ui.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orion.store.morphe.model.JobStage
import com.orion.store.morphe.model.PatchJob
import com.orion.store.morphe.store.JobStore
import com.orion.store.morphe.store.MorpheCatalogApp
import com.orion.store.morphe.store.MorpheCatalogRepository
import com.orion.store.morphe.ui.sheets.PatchConsoleLogSheet
import com.orion.store.morphe.worker.PatchJobRunner
import com.orion.store.platform.DownloadOrchestrator
import com.orion.store.platform.PatchStorageCleaner
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun QueueTab(vm: MainViewModel) {
    val jobs by JobStore.jobs.collectAsStateWithLifecycle()
    val activeCount by JobStore.activeCount.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFilter by remember { mutableStateOf("All") }
    var showLogsForJob by remember { mutableStateOf<PatchJob?>(null) }
    var cloneDialogJob by remember { mutableStateOf<PatchJob?>(null) }
    var targetJobForProvide by remember { mutableStateOf<PatchJob?>(null) }

    val retryFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val job = targetJobForProvide
        if (uri != null && job != null) {
            val name = uri.lastPathSegment?.substringAfterLast("/") ?: "selected.apk"
            scope.launch(Dispatchers.IO) {
                try {
                    val dest = File(context.cacheDir, "provided_${job.params.packageName}_${System.currentTimeMillis()}.apk")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    if (DownloadOrchestrator.isApkValid(dest)) {
                        DownloadOrchestrator.cacheBaseApk(context, job.params.packageName, "Local", dest)
                        withContext(Dispatchers.Main) {
                            vm.haptic(H.SUCCESS)
                            vm.snackbar.showSnackbar("Loaded APK $name. Retrying patch...")
                            JobStore.retryJob(job.id, newSourcePath = dest.absolutePath)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            vm.snackbar.showSnackbar("Selected file is corrupted or not a valid APK.")
                            vm.haptic(H.ERROR)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        vm.snackbar.showSnackbar("Failed to read selected APK: ${e.message}")
                        vm.haptic(H.ERROR)
                    }
                }
            }
        }
    }

    val filteredJobs = remember(jobs, selectedFilter) {
        when (selectedFilter) {
            "Active" -> jobs.filter { it.stage.isActive }
            "Completed" -> jobs.filter { it.stage == JobStage.SUCCEEDED }
            "Failed" -> jobs.filter { it.stage == JobStage.FAILED || it.stage == JobStage.CANCELLED }
            else -> jobs
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Patch Job Queue, $activeCount active jobs" },
        color = cs.background
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header stats & filter chips
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Patch Queue",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = cs.onBackground
                    )
                    Text(
                        text = "$activeCount active operations",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }

                if (jobs.any { it.stage.isTerminal }) {
                    OutlinedButton(
                        onClick = {
                            vm.haptic(H.LIGHT)
                            val terminalJobs = jobs.filter { it.stage.isTerminal }
                            terminalJobs.forEach { j ->
                                PatchStorageCleaner.purgeAppCache(context, j.params.packageName)
                            }
                            JobStore.clearCompleted()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Clear Completed", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Filter row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Active", "Completed", "Failed").forEach { filter ->
                    val selected = selectedFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = {
                            vm.haptic(H.LIGHT)
                            selectedFilter = filter
                        },
                        label = { Text(filter) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = cs.primaryContainer,
                            selectedLabelColor = cs.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (filteredJobs.isEmpty()) {
                // Empty state
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(cs.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Sym.Check, null, Modifier.size(36.dp), tint = cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No jobs in this view",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onBackground
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Patch builds and installations will appear here in real-time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredJobs, key = { it.id }) { job ->
                        JobItemCard(
                            job = job,
                            vm = vm,
                            onViewLogs = { showLogsForJob = job },
                            onCloneRepatch = { cloneDialogJob = job },
                            onRequestProvideApk = {
                                targetJobForProvide = job
                                retryFilePicker.launch(arrayOf(
                                    "application/vnd.android.package-archive",
                                    "application/octet-stream",
                                    "*/*"
                                ))
                            }
                        )
                    }
                }
            }
        }
    }

    showLogsForJob?.let { job ->
        PatchConsoleLogSheet(
            vm = vm,
            job = job,
            onDismiss = { showLogsForJob = null }
        )
    }

    cloneDialogJob?.let { job ->
        CloneRepatchDialog(
            job = job,
            vm = vm,
            onDismiss = { cloneDialogJob = null }
        )
    }
}

@Composable
fun JobItemCard(
    job: PatchJob,
    vm: MainViewModel,
    onViewLogs: () -> Unit,
    onCloneRepatch: () -> Unit,
    onRequestProvideApk: () -> Unit,
    onRemoveFromQueue: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }

    val builtApk = remember(job.outputApkPath, job.params.packageName, job.params.appName) {
        job.outputApkPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
            ?: runCatching {
                val downloads = com.orion.store.platform.Downloads.dir()
                val pkg = job.params.packageName
                val cleanAppName = job.params.appName.replace(" ", "_")
                downloads.listFiles()?.firstOrNull { f ->
                    f.isFile && f.name.startsWith("Patched_", ignoreCase = true) &&
                        (f.name.contains(pkg, ignoreCase = true) || f.name.contains(cleanAppName, ignoreCase = true)) &&
                        f.length() > 0
                }
            }.getOrNull()
    }
    val isInstalled = remember(job.params.packageName) {
        runCatching { context.packageManager.getPackageInfo(job.params.packageName, 0) }.isSuccess
    }
    val isConflict = remember(job.failureReason) {
        job.failureReason?.contains("conflict", ignoreCase = true) == true ||
            job.failureReason?.contains("signature", ignoreCase = true) == true ||
            job.failureReason?.contains("incompatible", ignoreCase = true) == true
    }

    val progressAnimated by animateFloatAsState(
        targetValue = job.progress / 100f,
        label = "progress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                builtApk != null && job.stage == JobStage.SUCCEEDED -> cs.surfaceContainerHigh
                builtApk != null && job.stage == JobStage.FAILED -> cs.errorContainer.copy(alpha = 0.4f)
                job.stage == JobStage.FAILED -> cs.errorContainer.copy(alpha = 0.5f)
                else -> cs.surfaceContainer
            }
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header: App info + Stage pill + 3-dots overflow menu
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = job.params.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurface
                    )
                    Text(
                        text = "${job.params.packageName} • v${job.params.targetVersion.ifEmpty { "1.0.0" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StageBadge(stage = job.stage)

                    Box {
                        IconButton(
                            onClick = {
                                vm.haptic(H.LIGHT)
                                menuExpanded = true
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Sym.MoreVert, "More actions", tint = cs.onSurfaceVariant)
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Show in Folder") },
                                onClick = {
                                    menuExpanded = false
                                    vm.haptic(H.LIGHT)
                                    vm.showInFolder(builtApk ?: com.orion.store.platform.Downloads.dir())
                                },
                                leadingIcon = { Icon(Sym.FolderOpen, null) }
                            )

                            if (builtApk != null) {
                                DropdownMenuItem(
                                    text = { Text("Install Built APK") },
                                    onClick = {
                                        menuExpanded = false
                                        vm.haptic(H.LIGHT)
                                        vm.installPatchedApk(job)
                                    },
                                    leadingIcon = { Icon(Sym.FileDownload, null) }
                                )
                            }

                            DropdownMenuItem(
                                text = { Text("View Live Logs") },
                                onClick = {
                                    menuExpanded = false
                                    vm.haptic(H.LIGHT)
                                    onViewLogs()
                                },
                                leadingIcon = { Icon(Sym.Terminal, null) }
                            )

                            DropdownMenuItem(
                                text = { Text("Clone & Repatch") },
                                onClick = {
                                    menuExpanded = false
                                    vm.haptic(H.LIGHT)
                                    onCloneRepatch()
                                },
                                leadingIcon = { Icon(Sym.ContentCopy, null) }
                            )

                            if (isInstalled) {
                                DropdownMenuItem(
                                    text = { Text("Uninstall Original App") },
                                    onClick = {
                                        menuExpanded = false
                                        vm.haptic(H.MEDIUM)
                                        vm.requestUninstall(job.params.packageName)
                                    },
                                    leadingIcon = { Icon(Sym.Delete, null) }
                                )
                            }

                            DropdownMenuItem(
                                text = { Text("Remove from Queue & Cache", color = cs.error) },
                                onClick = {
                                    menuExpanded = false
                                    vm.haptic(H.LIGHT)
                                    if (job.stage.isActive || job.stage == JobStage.QUEUED) {
                                        PatchJobRunner.cancel(job.id)
                                    }
                                    JobStore.deleteJob(job.id)
                                    builtApk?.delete()
                                    val freed = PatchStorageCleaner.purgeAppCache(context, job.params.packageName)
                                    scope.launch {
                                        val freedMb = String.format(java.util.Locale.US, "%.1f", freed / (1024f * 1024f))
                                        vm.snackbar.showSnackbar("Removed ${job.params.appName} from queue" + if (freed > 0) " (freed ${freedMb}MB)" else "")
                                    }
                                    onRemoveFromQueue()
                                },
                                leadingIcon = { Icon(Sym.Delete, null, tint = cs.error) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Stage description message or failure reason
            val messageText = job.failureReason ?: job.stageMessage
            Text(
                text = messageText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (job.stage == JobStage.FAILED) cs.error else cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (job.stage.isActive) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progressAnimated },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = cs.primary,
                    trackColor = cs.surfaceContainerHighest
                )
            }

            Spacer(Modifier.height(12.dp))

            // Action row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Direct Remove / Cancel from Queue
                OutlinedButton(
                    onClick = {
                        vm.haptic(H.MEDIUM)
                        if (job.stage.isActive || job.stage == JobStage.QUEUED) {
                            PatchJobRunner.cancel(job.id)
                        }
                        JobStore.deleteJob(job.id)
                        builtApk?.delete()
                        val freed = PatchStorageCleaner.purgeAppCache(context, job.params.packageName)
                        scope.launch {
                            val freedMb = String.format(java.util.Locale.US, "%.1f", freed / (1024f * 1024f))
                            vm.snackbar.showSnackbar("Removed ${job.params.appName} from queue" + if (freed > 0) " (freed ${freedMb}MB)" else "")
                        }
                        onRemoveFromQueue()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                    border = BorderStroke(1.dp, cs.error.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Icon(Sym.Delete, null, Modifier.size(14.dp), tint = cs.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Remove", style = MaterialTheme.typography.labelSmall, color = cs.error)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Direct "Show in Folder" button
                    if (builtApk != null || job.stage.isTerminal) {
                        OutlinedButton(
                            onClick = {
                                vm.haptic(H.LIGHT)
                                vm.showInFolder(builtApk ?: com.orion.store.platform.Downloads.dir())
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Icon(Sym.FolderOpen, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Show in Folder", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    when {
                        // 1. Valid built APK available: primary "Install Built APK"
                        builtApk != null -> {
                            if (isInstalled && isConflict) {
                                FilledTonalButton(
                                    onClick = {
                                        vm.haptic(H.MEDIUM)
                                        vm.requestUninstall(job.params.packageName)
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shapes = ButtonDefaults.shapes()
                                ) {
                                    Icon(Sym.Delete, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Uninstall Original", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            Button(
                                onClick = {
                                    vm.haptic(H.LIGHT)
                                    vm.installPatchedApk(job)
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                shapes = ButtonDefaults.shapes()
                            ) {
                                Icon(Sym.FileDownload, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (isInstalled && isConflict) "Install Patched" else "Install Built APK",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        // 2. Active job: "Cancel" and "Live Logs"
                        job.stage.isActive || job.stage == JobStage.QUEUED -> {
                            OutlinedButton(
                                onClick = {
                                    vm.haptic(H.MEDIUM)
                                    PatchJobRunner.cancel(job.id)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shapes = ButtonDefaults.shapes()
                            ) {
                                Icon(Sym.Close, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Cancel", style = MaterialTheme.typography.labelSmall)
                            }
                            FilledTonalButton(
                                onClick = {
                                    vm.haptic(H.LIGHT)
                                    onViewLogs()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shapes = ButtonDefaults.shapes()
                            ) {
                                Icon(Sym.Terminal, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Live Logs", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // 3. Failed job without built APK: "Retry" and "Provide APK to Retry"
                        job.stage == JobStage.FAILED || job.stage == JobStage.CANCELLED -> {
                            OutlinedButton(
                                onClick = {
                                    vm.haptic(H.LIGHT)
                                    onRequestProvideApk()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shapes = ButtonDefaults.shapes()
                            ) {
                                Icon(Sym.FileDownload, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Provide APK", style = MaterialTheme.typography.labelSmall)
                            }
                            FilledTonalButton(
                                onClick = {
                                    vm.haptic(H.MEDIUM)
                                    JobStore.retryJob(job.id)
                                    PatchJobRunner.runJob(job, context)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shapes = ButtonDefaults.shapes()
                            ) {
                                Icon(Sym.Refresh, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Retry", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // 4. Fallback completed without file
                        else -> {
                            FilledTonalButton(
                                onClick = {
                                    vm.haptic(H.LIGHT)
                                    onViewLogs()
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shapes = ButtonDefaults.shapes()
                            ) {
                                Icon(Sym.Terminal, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Live Logs", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CloneRepatchDialog(
    job: PatchJob,
    vm: MainViewModel,
    onDismiss: () -> Unit
) {
    var clonePkg by remember(job) { mutableStateOf("${job.params.packageName}.morphe") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone & Repatch") },
        text = {
            Column {
                Text(
                    "Enter a custom package name to build an isolated clone of ${job.params.appName}. This allows side-by-side installation with the original unpatched app.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = clonePkg,
                    onValueChange = { clonePkg = it },
                    label = { Text("Custom Package Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targetPkg = clonePkg.trim().ifEmpty { "${job.params.packageName}.morphe" }
                    val newOptions = job.params.patchOptions + ("clonePackageName" to targetPkg)
                    val newParams = job.params.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        patchOptions = newOptions,
                        sourceApkPath = null
                    )
                    if (job.params.selectedPatchIds.isEmpty()) {
                        val morpheApp = MorpheCatalogRepository.getApp(job.params.packageName)
                            ?: MorpheCatalogApp(packageName = targetPkg, name = job.params.appName)
                        vm.openMorpheInstall(morpheApp)
                    } else {
                        JobStore.enqueue(newParams)
                    }
                    vm.haptic(H.SUCCESS)
                    scope.launch {
                        vm.snackbar.showSnackbar("Queued clone build for $targetPkg")
                    }
                    onDismiss()
                }
            ) {
                Text("Start Build")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun StageBadge(stage: JobStage) {
    val cs = MaterialTheme.colorScheme
    val (bgColor, fgColor, text) = when (stage) {
        JobStage.QUEUED -> Triple(cs.secondaryContainer, cs.onSecondaryContainer, "Queued")
        JobStage.DOWNLOADING -> Triple(cs.primaryContainer, cs.onPrimaryContainer, "Downloading")
        JobStage.VERIFYING -> Triple(cs.tertiaryContainer, cs.onTertiaryContainer, "Verifying")
        JobStage.PATCHING -> Triple(cs.primaryContainer, cs.onPrimaryContainer, "Patching")
        JobStage.ALIGNING -> Triple(cs.tertiaryContainer, cs.onTertiaryContainer, "Aligning")
        JobStage.SIGNING -> Triple(cs.primaryContainer, cs.onPrimaryContainer, "Signing")
        JobStage.INSTALLING -> Triple(cs.primaryContainer, cs.onPrimaryContainer, "Installing")
        JobStage.SUCCEEDED -> Triple(Color(0xFF2E7D32).copy(alpha = 0.2f), Color(0xFF2E7D32), "Done")
        JobStage.FAILED -> Triple(cs.errorContainer, cs.onErrorContainer, "Failed")
        JobStage.CANCELLED -> Triple(cs.surfaceContainerHighest, cs.onSurfaceVariant, "Cancelled")
    }

    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fgColor
        )
    }
}
