package com.orion.store.morphe.ui.sheets

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.orion.store.morphe.model.JobStage
import com.orion.store.morphe.model.PatchJobParams
import com.orion.store.morphe.store.JobStore
import com.orion.store.morphe.store.MorpheCatalogApp
import com.orion.store.morphe.store.MorpheCatalogPatch
import com.orion.store.morphe.store.MorpheCatalogRepository
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorpheInstallSheet(
    vm: MainViewModel,
    app: MorpheCatalogApp,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val (specificPatches, universalPatches) = remember(app.packageName) {
        MorpheCatalogRepository.getPatchesForApp(app.packageName)
    }

    // Selected patch names
    val selectedPatches = remember(app.packageName) {
        val initial = mutableSetOf<String>()
        specificPatches.filter { it.isDefault }.forEach { initial.add(it.name) }
        // Keep high-value universal patches enabled by default if any
        universalPatches.filter { it.isDefault && (it.name.contains("GmsCore", true) || it.name.contains("MicroG", true) || it.name.contains("Ads", true)) }
            .forEach { initial.add(it.name) }
        mutableStateListOf(*initial.toTypedArray())
    }

    val installedVersion = vm.s.installedVersions[app.packageName]
    val recommendedVersion = app.compatibleVersions.firstOrNull() ?: installedVersion ?: "Latest"
    var showAllUniversal by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLow,
        tonalElevation = 8.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header: Icon, Name, Package, Version
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.orion.store.ui.RemoteIcon(
                    url = app.iconUrl,
                    name = app.name,
                    size = 54.dp,
                    shape = RoundedCornerShape(14.dp)
                )

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        app.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Badge(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
                            Text("Target v$recommendedVersion")
                        }
                        if (installedVersion != null) {
                            Badge(containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer) {
                                Text("Installed v$installedVersion")
                            }
                        }
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Sym.Close, "Dismiss")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Patch selection list
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Section: App-Specific Patches
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "App-Specific Patches (${specificPatches.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = cs.primary
                        )
                        if (specificPatches.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    val allSpecific = specificPatches.map { it.name }.toSet()
                                    if (selectedPatches.containsAll(allSpecific)) {
                                        selectedPatches.removeAll(allSpecific)
                                    } else {
                                        selectedPatches.addAll(allSpecific)
                                    }
                                    vm.haptic(H.SELECTION)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (selectedPatches.containsAll(specificPatches.map { it.name })) "Clear All" else "Select All")
                            }
                        }
                    }
                }

                if (specificPatches.isEmpty()) {
                    item {
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = cs.surfaceContainerHigh
                        ) {
                            Text(
                                "No app-specific patches indexed yet. Universal patches below can still be applied.",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(specificPatches, key = { "spec-${it.name}" }) { patch ->
                        PatchRow(
                            patch = patch,
                            isSelected = selectedPatches.contains(patch.name),
                            onToggle = {
                                if (selectedPatches.contains(patch.name)) {
                                    selectedPatches.remove(patch.name)
                                } else {
                                    selectedPatches.add(patch.name)
                                }
                                vm.haptic(H.SELECTION)
                            }
                        )
                    }
                }

                // Section: Universal Patches
                if (universalPatches.isNotEmpty()) {
                    item {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Universal Patches (${universalPatches.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = cs.secondary
                                )
                                if (universalPatches.size > 5) {
                                    TextButton(
                                        onClick = { showAllUniversal = !showAllUniversal },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(if (showAllUniversal) "Show less" else "Show all (${universalPatches.size})")
                                    }
                                }
                            }
                            val displayed = if (showAllUniversal) universalPatches else universalPatches.take(5)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                displayed.forEach { patch ->
                                    PatchRow(
                                        patch = patch,
                                        isSelected = selectedPatches.contains(patch.name),
                                        onToggle = {
                                            if (selectedPatches.contains(patch.name)) {
                                                selectedPatches.remove(patch.name)
                                            } else {
                                                selectedPatches.add(patch.name)
                                            }
                                            vm.haptic(H.SELECTION)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action footer
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${selectedPatches.size} selected",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant
                )

                Button(
                    onClick = {
                        val params = PatchJobParams(
                            id = java.util.UUID.randomUUID().toString(),
                            packageName = app.packageName,
                            appName = app.name,
                            targetVersion = recommendedVersion,
                            selectedPatchIds = selectedPatches.toList()
                        )
                        JobStore.enqueue(params)
                        vm.haptic(H.SUCCESS)
                        onDismiss()
                        scope.launch {
                            vm.snackbar.showSnackbar(
                                message = "Queued patch build for ${app.name} (${selectedPatches.size} patches) in background",
                                withDismissAction = true
                            )
                        }
                    },
                    enabled = selectedPatches.isNotEmpty(),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Icon(Sym.Build, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Queue & Build")
                }
            }
        }
    }
}

@Composable
private fun PatchRow(
    patch: MorpheCatalogPatch,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val borderStroke = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, cs.primary.copy(alpha = 0.5f)) else null
    Surface(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) cs.surfaceContainerHighest else cs.surfaceContainer,
        border = borderStroke
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() }
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    patch.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (patch.description.isNotEmpty()) {
                    Text(
                        patch.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (patch.bundleName.isNotEmpty()) {
                    Text(
                        "Bundle: ${patch.bundleName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
