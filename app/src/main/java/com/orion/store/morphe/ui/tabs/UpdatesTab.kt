package com.orion.store.morphe.ui.tabs

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orion.store.data.Tab
import com.orion.store.morphe.model.MorpheUpdate
import com.orion.store.morphe.model.UpdateType
import com.orion.store.morphe.store.MorpheRepoStore
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym

@Composable
fun UpdatesTab(vm: MainViewModel) {
    val updates by MorpheRepoStore.updates.collectAsStateWithLifecycle()
    val updateCount by MorpheRepoStore.updateCount.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Morphe Updates, $updateCount updates available" },
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
                        text = "Morphe Updates",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = cs.onBackground
                    )
                    Text(
                        text = "3-tier ecosystem update manager ($updateCount available)",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }

                if (updates.isNotEmpty()) {
                    Button(
                        onClick = {
                            vm.haptic(H.MEDIUM)
                            vm.navTo(Tab.PATCH_STUDIO)
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("Update All", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (updates.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).background(cs.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Sym.Check, null, Modifier.size(36.dp), tint = cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Everything up to date",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onBackground
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Source APKs, patch bundles, and the patch engine are at their latest versions.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(updates, key = { it.id }) { update ->
                        UpdateItemCard(
                            update = update,
                            onApply = {
                                vm.haptic(H.MEDIUM)
                                vm.navTo(Tab.PATCH_STUDIO)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateItemCard(
    update: MorpheUpdate,
    onApply: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    val (tierLabel, tierBg, tierFg) = when (update.type) {
        UpdateType.SOURCE_APK -> Triple("Source APK", cs.secondaryContainer, cs.onSecondaryContainer)
        UpdateType.PATCH_BUNDLE -> Triple("Patch Bundle", cs.tertiaryContainer, cs.onTertiaryContainer)
        UpdateType.PATCH_ENGINE -> Triple("Core Engine", cs.primaryContainer, cs.onPrimaryContainer)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tierBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (update.type) {
                                UpdateType.SOURCE_APK -> Sym.Android
                                UpdateType.PATCH_BUNDLE -> Sym.Extension
                                UpdateType.PATCH_ENGINE -> Sym.Build
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = tierFg
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column {
                        Text(
                            text = update.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "v${update.currentVersion} ➔ v${update.newVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                }

                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(tierBg).padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(tierLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = tierFg)
                }
            }

            if (update.changelog.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = update.changelog,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                FilledTonalButton(
                    onClick = onApply,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Apply & Re-patch")
                }
            }
        }
    }
}
