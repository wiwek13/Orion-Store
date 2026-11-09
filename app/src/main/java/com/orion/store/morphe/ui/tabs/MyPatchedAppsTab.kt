package com.orion.store.morphe.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orion.store.data.Tab
import com.orion.store.morphe.model.PatchedApp
import com.orion.store.morphe.store.PatchedAppStore
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyPatchedAppsTab(vm: MainViewModel) {
    val apps by PatchedAppStore.apps.collectAsStateWithLifecycle()
    val attentionCount by PatchedAppStore.attentionCount.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "My Patched Apps, $attentionCount apps requiring attention" },
        color = cs.background
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "My Patched Apps",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = cs.onBackground
                    )
                    Text(
                        text = "${apps.size} managed applications (${attentionCount} need attention)",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }

                Button(
                    onClick = {
                        vm.haptic(H.MEDIUM)
                        vm.navTo(Tab.PATCH_STUDIO)
                    },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Sym.Build, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Patch", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (apps.isEmpty()) {
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
                            Icon(Sym.Extension, null, Modifier.size(36.dp), tint = cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No patched apps found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onBackground
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Use Patch Studio to apply community patches to your favorite apps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                vm.haptic(H.LIGHT)
                                vm.navTo(Tab.PATCH_STUDIO)
                            }
                        ) {
                            Text("Open Patch Studio")
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        PatchedAppCard(
                            app = app,
                            onRepatch = {
                                vm.haptic(H.MEDIUM)
                                vm.navTo(Tab.PATCH_STUDIO)
                            },
                            onRemove = {
                                vm.haptic(H.LIGHT)
                                PatchedAppStore.removeApp(app.packageName)
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatchedAppCard(
    app: PatchedApp,
    onRepatch: () -> Unit,
    onRemove: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (app.needsAttention) cs.surfaceContainerHigh else cs.surfaceContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Sym.Android, null, Modifier.size(24.dp), tint = cs.onPrimaryContainer)
                    }

                    Spacer(Modifier.width(12.dp))

                    Column {
                        Text(
                            text = app.appName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${app.packageName} • v${app.installedVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (app.needsAttention) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(cs.errorContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Update",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = cs.onErrorContainer
                        )
                    }
                }
            }

            if (app.attentionReason != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = app.attentionReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.error,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(12.dp))

            // Applied patches badges flow
            Text(
                text = "Applied Patches (${app.appliedPatches.size}):",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                app.appliedPatches.forEach { patchId ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(cs.surfaceContainerHighest)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = patchId,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurface
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Action row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(Sym.Delete, "Untrack app", tint = cs.onSurfaceVariant)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onRepatch,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Sym.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (app.needsAttention) "Update & Re-patch" else "Re-patch")
                    }
                }
            }
        }
    }
}
