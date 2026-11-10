package com.orion.store.morphe.ui.sheets

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

    val (rawSpecificPatches, rawUniversalPatches) = remember(app.packageName) {
        MorpheCatalogRepository.getPatchesForApp(app.packageName)
    }

    val specificPatches = remember(rawSpecificPatches) {
        rawSpecificPatches.distinctBy { it.name.lowercase() }
    }
    val universalPatches = remember(rawUniversalPatches) {
        rawUniversalPatches.distinctBy { it.name.lowercase() }
    }

    val specificBundles = remember(specificPatches) {
        specificPatches.groupBy { it.bundleName.ifEmpty { "App Patches" } }
    }
    val universalBundles = remember(universalPatches) {
        universalPatches.groupBy { it.bundleName.ifEmpty { "Universal Patches" } }
    }

    // Selected patch names - restored from saved selections or starts empty
    val selectedPatches = remember(app.packageName) {
        mutableStateListOf<String>()
    }

    LaunchedEffect(app.packageName, specificPatches, universalPatches) {
        val validPatchNames = (specificPatches.map { it.name } + universalPatches.map { it.name }).toSet()
        val saved = vm.s.savedPatchSelections[app.packageName]
        if (!saved.isNullOrEmpty() && selectedPatches.isEmpty()) {
            selectedPatches.addAll(saved.filter { it in validPatchNames })
        } else if (selectedPatches.isEmpty()) {
            selectedPatches.addAll(specificPatches.filter { it.isDefault }.map { it.name })
        }
        val cleanSelection = selectedPatches.distinct().filter { it in validPatchNames }
        if (cleanSelection.size != selectedPatches.size) {
            selectedPatches.clear()
            selectedPatches.addAll(cleanSelection)
        }
    }

    // Expand state: ALL bundles COLLAPSED by default
    val expandedBundles = remember(app.packageName) {
        mutableStateListOf<String>()
    }
    var showUniversalSection by remember { mutableStateOf(false) }
    var patchSearchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf("All") } // "All", "App-Specific", "Universal", "Selected"

    val installedVersion = vm.s.installedVersions[app.packageName]
    val recommendedVersion = app.compatibleVersions.firstOrNull() ?: installedVersion ?: "Recommended"

    val isPinnedInitial = vm.s.pinnedAppVersions.containsKey(app.packageName)
    val initialVersion = vm.s.pinnedAppVersions[app.packageName] ?: recommendedVersion

    var versionChoice by remember {
        mutableStateOf(
            VersionChoice(
                type = if (isPinnedInitial) VersionSelectionType.SPECIFIC else VersionSelectionType.RECOMMENDED,
                versionName = initialVersion,
                isPinned = isPinnedInitial
            )
        )
    }
    var showVersionSelector by remember { mutableStateOf(false) }

    // Search filter across specific and universal bundles
    val filteredSpecificBundles = remember(specificBundles, patchSearchQuery) {
        if (patchSearchQuery.isBlank()) specificBundles
        else {
            specificBundles.mapValues { (_, patches) ->
                patches.filter {
                    it.name.contains(patchSearchQuery, ignoreCase = true) ||
                    it.description.contains(patchSearchQuery, ignoreCase = true) ||
                    it.bundleName.contains(patchSearchQuery, ignoreCase = true)
                }
            }.filterValues { it.isNotEmpty() }
        }
    }

    val filteredUniversalBundles = remember(universalBundles, patchSearchQuery) {
        if (patchSearchQuery.isBlank()) universalBundles
        else {
            universalBundles.mapValues { (_, patches) ->
                patches.filter {
                    it.name.contains(patchSearchQuery, ignoreCase = true) ||
                    it.description.contains(patchSearchQuery, ignoreCase = true) ||
                    it.bundleName.contains(patchSearchQuery, ignoreCase = true)
                }
            }.filterValues { it.isNotEmpty() }
        }
    }

    // Apply 4-way filter pills (All, App-Specific, Universal, Selected)
    val displaySpecificBundles = remember(filteredSpecificBundles, activeFilter, selectedPatches.size) {
        if (activeFilter == "Universal") emptyMap()
        else if (activeFilter == "Selected") {
            filteredSpecificBundles.mapValues { (_, patches) ->
                patches.filter { selectedPatches.contains(it.name) }
            }.filterValues { it.isNotEmpty() }
        } else {
            filteredSpecificBundles
        }
    }

    val displayUniversalBundles = remember(filteredUniversalBundles, activeFilter, selectedPatches.size) {
        if (activeFilter == "App-Specific") emptyMap()
        else if (activeFilter == "Selected") {
            filteredUniversalBundles.mapValues { (_, patches) ->
                patches.filter { selectedPatches.contains(it.name) }
            }.filterValues { it.isNotEmpty() }
        } else {
            filteredUniversalBundles
        }
    }

    // Auto-expand matching bundles when actively searching
    LaunchedEffect(patchSearchQuery) {
        if (patchSearchQuery.isNotBlank()) {
            filteredSpecificBundles.keys.forEach { if (!expandedBundles.contains(it)) expandedBundles.add(it) }
            if (filteredUniversalBundles.isNotEmpty()) showUniversalSection = true
        }
    }

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
                        Surface(
                            onClick = { showVersionSelector = true; vm.haptic(H.LIGHT) },
                            shape = RoundedCornerShape(8.dp),
                            color = cs.secondaryContainer
                        ) {
                            Row(
                                Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("v${versionChoice.versionName}", style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer, maxLines = 1)
                                if (versionChoice.isPinned) {
                                    Spacer(Modifier.width(3.dp))
                                    Icon(Sym.Lock, "Pinned", Modifier.size(11.dp), tint = cs.onSecondaryContainer)
                                } else {
                                    Spacer(Modifier.width(3.dp))
                                    Icon(Sym.ArrowDropDown, "Select version", Modifier.size(14.dp), tint = cs.onSecondaryContainer)
                                }
                            }
                        }
                        if (installedVersion != null) {
                            Surface(shape = RoundedCornerShape(8.dp), color = cs.tertiaryContainer) {
                                Text("Installed v$installedVersion", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = cs.onTertiaryContainer, maxLines = 1)
                            }
                        } else {
                            Surface(shape = RoundedCornerShape(8.dp), color = cs.surfaceContainerHigh) {
                                Text("Not Installed", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Sym.Close, "Dismiss")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Search Bar for all patches
            OutlinedTextField(
                value = patchSearchQuery,
                onValueChange = { patchSearchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                placeholder = {
                    Text(
                        "Search all patches (e.g. adblock, theme...)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(Sym.Search, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                },
                trailingIcon = {
                    if (patchSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { patchSearchQuery = "" }) {
                            Icon(Sym.Close, "Clear", Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = cs.surfaceContainerHigh,
                    unfocusedContainerColor = cs.surfaceContainerHigh,
                    focusedBorderColor = cs.primary,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            Spacer(Modifier.height(8.dp))

            // 4-Way Filter Pills from MorpheFlow: All, App-Specific, Universal, Selected
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("All", "App-Specific", "Universal", "Selected").forEach { pill ->
                    val isPillSelected = activeFilter == pill
                    FilterChip(
                        selected = isPillSelected,
                        onClick = { activeFilter = pill; vm.haptic(H.SELECTION) },
                        label = {
                            val count = when (pill) {
                                "All" -> specificPatches.size + universalPatches.size
                                "App-Specific" -> specificPatches.size
                                "Universal" -> universalPatches.size
                                "Selected" -> selectedPatches.size
                                else -> 0
                            }
                            Text("$pill ($count)", style = MaterialTheme.typography.labelSmall)
                        },
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Patch selection list grouped by Bundle
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Section: App-Specific Bundles
                if (displaySpecificBundles.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "App Bundles (${displaySpecificBundles.size}) · ${specificPatches.size} patches",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = cs.primary
                            )
                            if (specificPatches.isNotEmpty()) {
                                val allSpecific = specificPatches.map { it.name }.toSet()
                                val allChecked = selectedPatches.containsAll(allSpecific)
                                TextButton(
                                    onClick = {
                                        if (allChecked) selectedPatches.removeAll(allSpecific)
                                        else selectedPatches.addAll(allSpecific)
                                        vm.haptic(H.SELECTION)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(if (allChecked) "Clear All" else "Select All")
                                }
                            }
                        }
                    }

                    items(displaySpecificBundles.entries.toList(), key = { "spec-${it.key}" }) { (bundleName, bundlePatches) ->
                        val dedupPatches = remember(bundlePatches) { bundlePatches.distinctBy { it.name.lowercase() } }
                        val isExpanded = expandedBundles.contains(bundleName) || activeFilter == "Selected"
                        BundleAccordionCard(
                            bundleName = bundleName,
                            patches = dedupPatches,
                            selectedPatches = selectedPatches,
                            isExpanded = isExpanded,
                            onToggleExpand = {
                                if (isExpanded) expandedBundles.remove(bundleName)
                                else expandedBundles.add(bundleName)
                                vm.haptic(H.LIGHT)
                            },
                            onSelectAllInBundle = {
                                dedupPatches.forEach { p -> if (!selectedPatches.contains(p.name)) selectedPatches.add(p.name) }
                                vm.haptic(H.SELECTION)
                            },
                            onClearAllInBundle = {
                                val names = dedupPatches.map { it.name }.toSet()
                                selectedPatches.removeAll(names)
                                vm.haptic(H.SELECTION)
                            },
                            isUniversal = false
                        )
                    }
                } else if (activeFilter == "App-Specific" || (activeFilter == "All" && displayUniversalBundles.isEmpty())) {
                    item {
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = cs.surfaceContainerHigh
                        ) {
                            Text(
                                if (patchSearchQuery.isNotBlank()) "No patches matching \"$patchSearchQuery\" in app bundles."
                                else "No app-specific bundles found. Universal patches below can still be applied.",
                                modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                    }
                }

                // Section: Universal Bundles (Collapsible)
                if (displayUniversalBundles.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Universal Bundles (${displayUniversalBundles.size}) · ${universalPatches.size} patches",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = cs.secondary
                            )
                            TextButton(
                                onClick = { showUniversalSection = !showUniversalSection },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(if (showUniversalSection || activeFilter == "Universal") "Hide Universal" else "Show Universal")
                            }
                        }
                    }

                    if (showUniversalSection || activeFilter == "Universal" || activeFilter == "Selected") {
                        items(displayUniversalBundles.entries.toList(), key = { "univ-${it.key}" }) { (bundleName, bundlePatches) ->
                            val dedupPatches = remember(bundlePatches) { bundlePatches.distinctBy { it.name.lowercase() } }
                            val isExpanded = expandedBundles.contains("univ-$bundleName") || activeFilter == "Selected"
                            BundleAccordionCard(
                                bundleName = bundleName,
                                patches = dedupPatches,
                                selectedPatches = selectedPatches,
                                isExpanded = isExpanded,
                                onToggleExpand = {
                                    if (isExpanded) expandedBundles.remove("univ-$bundleName")
                                    else expandedBundles.add("univ-$bundleName")
                                    vm.haptic(H.LIGHT)
                                },
                                onSelectAllInBundle = {
                                    dedupPatches.forEach { p -> if (!selectedPatches.contains(p.name)) selectedPatches.add(p.name) }
                                    vm.haptic(H.SELECTION)
                                },
                                onClearAllInBundle = {
                                    val names = dedupPatches.map { it.name }.toSet()
                                    selectedPatches.removeAll(names)
                                    vm.haptic(H.SELECTION)
                                },
                                isUniversal = true
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Solid, uncollapsible Bottom Action Dock
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = cs.surfaceContainerHigh,
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            if (selectedPatches.isEmpty()) "No patches selected" else "${selectedPatches.size} patches selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Target: v${versionChoice.versionName}" + if (versionChoice.isPinned) " (Pinned)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = {
                            val params = PatchJobParams(
                                id = java.util.UUID.randomUUID().toString(),
                                packageName = app.packageName,
                                appName = app.name,
                                targetVersion = versionChoice.versionName,
                                sourceApkPath = versionChoice.localPath,
                                selectedPatchIds = selectedPatches.toList()
                            )
                            // Persist user's selected patches
                            com.orion.store.ui.Store.settings.update { s ->
                                s.copy(savedPatchSelections = s.savedPatchSelections + (app.packageName to selectedPatches.toList()))
                            }
                            JobStore.enqueue(params)
                            vm.haptic(H.SUCCESS)
                            onDismiss()
                            scope.launch {
                                vm.snackbar.showSnackbar(
                                    message = "Started patching ${app.name} (${selectedPatches.size} patches)...",
                                    withDismissAction = true
                                )
                            }
                        },
                        enabled = selectedPatches.isNotEmpty(),
                        modifier = Modifier
                            .height(48.dp)
                            .defaultMinSize(minWidth = 148.dp),
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Icon(Sym.Build, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Queue & Build",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }

    if (showVersionSelector) {
        VersionSelectorSheet(
            vm = vm,
            app = app,
            currentChoice = versionChoice,
            onVersionSelected = { choice ->
                versionChoice = choice
            },
            onDismiss = { showVersionSelector = false }
        )
    }
}

@Composable
private fun BundleAccordionCard(
    bundleName: String,
    patches: List<MorpheCatalogPatch>,
    selectedPatches: MutableList<String>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectAllInBundle: () -> Unit,
    onClearAllInBundle: () -> Unit,
    isUniversal: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val selectedInThisBundle = remember(selectedPatches.size, patches) {
        patches.count { selectedPatches.contains(it.name) }
    }
    val allSelected = selectedInThisBundle == patches.size && patches.isNotEmpty()
    val turn by androidx.compose.animation.core.animateFloatAsState(if (isExpanded) 180f else 0f, label = "turn")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selectedInThisBundle > 0) cs.surfaceContainerHigh else cs.surfaceContainer
    ) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            // Bundle Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isUniversal) cs.secondaryContainer else cs.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isUniversal) Sym.AllInclusive else Sym.Layers,
                        null,
                        Modifier.size(18.dp),
                        tint = if (isUniversal) cs.onSecondaryContainer else cs.onPrimaryContainer
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            bundleName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isUniversal) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = cs.secondaryContainer) {
                                Text("Universal", Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer)
                            }
                        }
                    }
                    Text(
                        "${patches.size} patches" + if (selectedInThisBundle > 0) " · $selectedInThisBundle selected" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedInThisBundle > 0) cs.primary else cs.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = {
                        if (allSelected) onClearAllInBundle() else onSelectAllInBundle()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(if (allSelected) "Clear" else "Select all", style = MaterialTheme.typography.labelSmall)
                }

                Icon(
                    Sym.ExpandMore,
                    if (isExpanded) "Collapse" else "Expand",
                    Modifier.size(22.dp).graphicsLayer { rotationZ = turn },
                    tint = cs.onSurfaceVariant
                )
            }

            // Expanded Patch Items
            if (isExpanded) {
                HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                Column(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    patches.forEach { patch ->
                        val isChecked = selectedPatches.contains(patch.name)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (isChecked) selectedPatches.remove(patch.name)
                                    else selectedPatches.add(patch.name)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) selectedPatches.add(patch.name)
                                    else selectedPatches.remove(patch.name)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    patch.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal
                                )
                                if (patch.description.isNotBlank()) {
                                    Text(
                                        patch.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
