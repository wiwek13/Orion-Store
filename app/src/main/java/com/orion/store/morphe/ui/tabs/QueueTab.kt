package com.orion.store.morphe.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.orion.store.morphe.worker.PatchJobRunner
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym

@Composable
fun QueueTab(vm: MainViewModel) {
    val jobs by JobStore.jobs.collectAsStateWithLifecycle()
    val activeCount by JobStore.activeCount.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    var selectedFilter by remember { mutableStateOf("All") }

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
                            onCancel = {
                                vm.haptic(H.MEDIUM)
                                PatchJobRunner.cancel(job.id)
                            },
                            onRetry = {
                                vm.haptic(H.MEDIUM)
                                JobStore.retryJob(job.id)
                                PatchJobRunner.runJob(job, context)
                            },
                            onDelete = {
                                vm.haptic(H.LIGHT)
                                JobStore.deleteJob(job.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JobItemCard(
    job: PatchJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val progressAnimated by animateFloatAsState(
        targetValue = job.progress / 100f,
        label = "progress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (job.stage) {
                JobStage.FAILED -> cs.errorContainer.copy(alpha = 0.5f)
                JobStage.SUCCEEDED -> cs.surfaceContainerHigh
                else -> cs.surfaceContainer
            }
        )
    ) {
        Column(Modifier.padding(16.dp)) {
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

                // Stage pill
                StageBadge(stage = job.stage)
            }

            Spacer(Modifier.height(10.dp))

            // Stage description message
            Text(
                text = job.stageMessage,
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
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (job.stage.isActive || job.stage == JobStage.QUEUED) {
                    OutlinedButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Cancel")
                    }
                } else if (job.stage == JobStage.FAILED || job.stage == JobStage.CANCELLED) {
                    FilledTonalButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Retry")
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(Sym.Delete, "Delete", tint = cs.onSurfaceVariant)
                    }
                } else if (job.stage == JobStage.SUCCEEDED) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(Sym.Delete, "Delete", tint = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
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
