package com.orion.store.morphe.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orion.store.data.Tab
import com.orion.store.morphe.engine.PatchBundle
import com.orion.store.morphe.store.MorpheRepoStore
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym

@Composable
fun MorpheStoreTab(vm: MainViewModel) {
    val repos by MorpheRepoStore.repos.collectAsStateWithLifecycle()
    val allBundles = rememberBundles(repos)
    val cs = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = cs.background
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Hero Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(cs.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Sym.Extension, null, Modifier.size(24.dp), tint = cs.onPrimary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Morphe Community Store",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = cs.onPrimaryContainer
                                )
                                Text(
                                    text = "Decentralized, open-source bytecode patches",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Browse community patch repositories, explore verified bundles, and customize applications safely on device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onPrimaryContainer
                        )
                    }
                }
            }

            // Featured Patchable Apps Swimlane
            item {
                Text(
                    text = "Featured Applications",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.onBackground
                )
                Spacer(Modifier.height(10.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val popular = listOf(
                        "YouTube" to "AdBlock, SponsorBlock, OLED",
                        "Spotify" to "AMOLED Black, No Podcasts",
                        "Telegram" to "Anti-Delete, No Stories",
                        "Twitter / X" to "Ad-free, Clean Navigation",
                        "Reddit" to "No promoted posts, custom font"
                    )
                    items(popular) { (name, subtitle) ->
                        Card(
                            modifier = Modifier
                                .width(160.dp)
                                .clickable {
                                    vm.haptic(H.LIGHT)
                                    vm.navTo(Tab.PATCH_STUDIO)
                                },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Box(
                                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(cs.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Sym.Android, null, Modifier.size(20.dp), tint = cs.onSecondaryContainer)
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            // Patch Bundles Section
            item {
                Text(
                    text = "Available Patch Bundles (${allBundles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.onBackground
                )
            }

            items(allBundles, key = { it.id }) { bundle ->
                BundleCard(
                    bundle = bundle,
                    onOpenInStudio = {
                        vm.haptic(H.MEDIUM)
                        vm.navTo(Tab.PATCH_STUDIO)
                    }
                )
            }
        }
    }
}

@Composable
private fun rememberBundles(repos: List<com.orion.store.morphe.model.MorpheRepo>): List<PatchBundle> {
    return androidx.compose.runtime.remember(repos) {
        repos.filter { it.isEnabled }.flatMap { it.bundles }
    }
}

@Composable
private fun BundleCard(
    bundle: PatchBundle,
    onOpenInStudio: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

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
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = bundle.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(cs.tertiaryContainer).padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("v${bundle.version}", style = MaterialTheme.typography.labelSmall, color = cs.onTertiaryContainer)
                        }
                    }
                    Text(
                        text = "By ${bundle.author} • ${bundle.patches.size} patches included",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = bundle.description,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                FilledTonalButton(
                    onClick = onOpenInStudio,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Sym.Build, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open in Studio")
                }
            }
        }
    }
}
