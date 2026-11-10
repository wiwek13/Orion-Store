package com.orion.store.morphe.ui.sheets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orion.store.morphe.model.PatchJob
import com.orion.store.morphe.store.JobStore
import com.orion.store.morphe.worker.PatchJobRunner
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchConsoleLogSheet(
    vm: MainViewModel,
    job: PatchJob,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showCancelDialog by remember { mutableStateOf(false) }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(job.logs.size) {
        if (job.logs.isNotEmpty()) {
            listState.animateScrollToItem(job.logs.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF121417),
        tonalElevation = 8.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${job.params.appName} — Live Patcher Console",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "Stage: ${job.stageMessage} (${job.progress}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.primary
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Sym.Close, "Close", tint = Color.LightGray)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Diagnostic Header Summary Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E2228)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Package: ${job.params.packageName}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA), fontFamily = FontFamily.Monospace)
                        Text("Target: v${job.params.targetVersion.ifEmpty { "Recommended" }}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA), fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Patches: ${job.params.selectedPatchIds.size}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA), fontFamily = FontFamily.Monospace)
                        val rt = Runtime.getRuntime()
                        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                        val maxMb = rt.maxMemory() / (1024 * 1024)
                        Text("Heap Limit: ${usedMb}MB / ${maxMb}MB", style = MaterialTheme.typography.labelSmall, color = Color(0xFF81C784), fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Console Terminal View
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 380.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0A0C0E)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (job.logs.isEmpty()) {
                        item {
                            Text(
                                "Initializing logger stream...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        items(job.logs) { line ->
                            val textColor = when {
                                line.contains("[ERROR]") || line.contains("[EXCEPTION]") -> Color(0xFFFF6B6B)
                                line.contains("[WARN]") -> Color(0xFFFFD166)
                                line.contains("[RES]") || line.contains("[DEX]") -> Color(0xFF4D96FF)
                                line.contains("[SUCCESS]") -> Color(0xFF6BCB77)
                                line.contains("[RAM]") -> Color(0xFFA8DADC)
                                else -> Color(0xFFE0E0E0)
                            }
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                                color = textColor,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action Buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        val allLogs = job.logs.joinToString("\n")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Patch Logs", allLogs))
                        vm.haptic(H.SELECTION)
                        scope.launch { vm.snackbar.showSnackbar("Logs copied to clipboard") }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Sym.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Logs", maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        try {
                            val exportDir = com.orion.store.platform.Downloads.dir()
                            val logFile = File(exportDir, "Morphe_${job.params.packageName}_debug.log")
                            logFile.writeText(job.logs.joinToString("\n"))
                            vm.haptic(H.SELECTION)
                            scope.launch { vm.snackbar.showSnackbar("Exported to Downloads/${logFile.name}") }
                        } catch (e: Exception) {
                            scope.launch { vm.snackbar.showSnackbar("Export failed: ${e.message}") }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Sym.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export", maxLines = 1)
                }

                if (job.stage.isActive) {
                    Button(
                        onClick = { showCancelDialog = true },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.error)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Patching?") },
            text = { Text("Are you sure you want to cancel the build for ${job.params.appName}? All temporary files will be cleaned.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        PatchJobRunner.cancel(job.id)
                        vm.haptic(H.LIGHT)
                        onDismiss()
                    }
                ) {
                    Text("Yes, Cancel", color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Keep Running")
                }
            }
        )
    }
}

