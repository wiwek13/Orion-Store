package com.orion.store.morphe.ui.sheets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.orion.store.morphe.model.JobStage
import com.orion.store.morphe.model.PatchJobParams
import com.orion.store.morphe.store.BundleVersionRecommendation
import com.orion.store.morphe.store.BundleVersionRecommender
import com.orion.store.morphe.store.JobStore
import com.orion.store.morphe.store.MorpheCatalogApp
import com.orion.store.morphe.store.MorpheCatalogPatch
import com.orion.store.morphe.store.MorpheCatalogRepository
import com.orion.store.morphe.store.groupPatchesByCategory
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
        rawSpecificPatches.distinctBy { "${it.bundleName}::${it.name.trim().lowercase()}" }
    }
    val universalPatches = remember(rawUniversalPatches) {
        rawUniversalPatches.distinctBy { "${it.bundleName}::${it.name.trim().lowercase()}" }
    }

    val specificBundles = remember(specificPatches) {
        val grouped = specificPatches.groupBy { it.bundleName.ifEmpty { "App Patches" } }
        grouped.entries.sortedByDescending { (name, _) ->
            when {
                name.contains("Hush", ignoreCase = true) -> 100
                name.contains("Morphe", ignoreCase = true) -> 50
                name.contains("Piko", ignoreCase = true) -> 40
                else -> 0
            }
        }.associate { it.key to it.value }
    }
    val universalBundles = remember(universalPatches) {
        universalPatches.groupBy { it.bundleName.ifEmpty { "Universal Patches" } }
    }

    // Selected patch names - restored from saved selections or starts with primary bundle defaults
    val selectedPatches = remember(app.packageName) {
        mutableStateListOf<String>()
    }

    LaunchedEffect(app.packageName, specificBundles, universalPatches) {
        val validKeys = (specificPatches.map { "${it.bundleName}::${it.name}" } + universalPatches.map { "${it.bundleName}::${it.name}" }).toSet()
        val validNames = (specificPatches.map { it.name } + universalPatches.map { it.name }).toSet()
        val saved = vm.s.savedPatchSelections[app.packageName]
        val diskManifest = if (saved == null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.orion.store.platform.OrionStorageManager.readAppliedPatches(app.name, app.packageName)
            }
        } else null
        val initialPatches = saved ?: diskManifest?.appliedPatches
        val matchedInitial = initialPatches
            ?.filter { it in validKeys || it in validNames }
            .orEmpty()
        if (matchedInitial.isNotEmpty() && selectedPatches.isEmpty()) {
            selectedPatches.addAll(matchedInitial)
        } else if (selectedPatches.isEmpty()) {
            // Select default patches only for the PRIMARY / FIRST bundle, never mix multiple mod bundles!
            val primaryBundle = specificBundles.keys.firstOrNull()
            if (primaryBundle != null) {
                val defaults = specificBundles[primaryBundle]?.filter { it.isDefault } ?: emptyList()
                selectedPatches.addAll(defaults.map { "${it.bundleName}::${it.name}" })
            } else {
                val defaults = universalPatches.filter { it.isDefault }
                selectedPatches.addAll(defaults.map { "${it.bundleName}::${it.name}" })
            }
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
    val bundleSummary = remember(specificPatches, selectedPatches.toList(), app.compatibleVersions, specificBundles) {
        BundleVersionRecommender.recommend(
            specificPatches = specificPatches,
            selectedPatchKeys = selectedPatches.toSet(),
            fallbackCompatibleVersions = app.compatibleVersions,
            preferredPrimaryBundle = specificBundles.keys.firstOrNull().orEmpty()
        )
    }
    val recommendedVersion = bundleSummary.bestVersion.ifBlank { installedVersion ?: "Recommended" }

    val isPinnedInitial = vm.s.pinnedAppVersions.containsKey(app.packageName)
    val initialVersion = vm.s.pinnedAppVersions[app.packageName] ?: recommendedVersion

    var versionChoice by remember(app.packageName) {
        mutableStateOf(
            VersionChoice(
                type = if (isPinnedInitial) VersionSelectionType.SPECIFIC else VersionSelectionType.RECOMMENDED,
                versionName = initialVersion,
                isPinned = isPinnedInitial
            )
        )
    }
    // Automatically track the best version whenever selected bundles/patches change unless user pinned/picked custom
    LaunchedEffect(recommendedVersion) {
        if (versionChoice.type == VersionSelectionType.RECOMMENDED &&
            !versionChoice.isPinned &&
            recommendedVersion.isNotBlank() &&
            versionChoice.versionName != recommendedVersion
        ) {
            versionChoice = versionChoice.copy(versionName = recommendedVersion)
        }
    }
    var showVersionSelector by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val directFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast("/") ?: "Selected Local APK"
            scope.launch(Dispatchers.IO) {
                try {
                    val dest = File(context.cacheDir, "picked_${app.packageName}_${System.currentTimeMillis()}.apk")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    if (com.orion.store.platform.DownloadOrchestrator.isApkValid(dest)) {
                        com.orion.store.platform.DownloadOrchestrator.cacheBaseApk(context, app.packageName, "Local", dest)
                        withContext(Dispatchers.Main) {
                            versionChoice = VersionChoice(
                                type = VersionSelectionType.LOCAL_FILE,
                                versionName = "Local: $name",
                                localUri = uri,
                                localPath = dest.absolutePath,
                                isPinned = true
                            )
                            vm.haptic(H.SUCCESS)
                            vm.snackbar.showSnackbar("Loaded local base APK: $name")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            vm.snackbar.showSnackbar("Selected file is corrupted or not a valid APK.")
                            vm.haptic(H.ERROR)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Search filter across specific and universal bundles
    val filteredSpecificBundles = remember(specificBundles, patchSearchQuery) {
        if (patchSearchQuery.isBlank()) specificBundles
        else {
            specificBundles.mapValues { (_, patches) ->
                patches.filter {
                    it.name.contains(patchSearchQuery, ignoreCase = true) ||
                    it.description.contains(patchSearchQuery, ignoreCase = true) ||
                    it.category.contains(patchSearchQuery, ignoreCase = true) ||
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
                    it.category.contains(patchSearchQuery, ignoreCase = true) ||
                    it.bundleName.contains(patchSearchQuery, ignoreCase = true)
                }
            }.filterValues { it.isNotEmpty() }
        }
    }

    // Apply 4-way filter pills (All, App-Specific, Universal, Selected)
    val selectedSnapshot = selectedPatches.toList()
    val displaySpecificBundles = remember(filteredSpecificBundles, activeFilter, selectedSnapshot) {
        if (activeFilter == "Universal") emptyMap()
        else if (activeFilter == "Selected") {
            filteredSpecificBundles.mapValues { (_, patches) ->
                patches.filter { selectedSnapshot.contains("${it.bundleName}::${it.name}") || selectedSnapshot.contains(it.name) }
            }.filterValues { it.isNotEmpty() }
        } else {
            filteredSpecificBundles
        }
    }

    val displayUniversalBundles = remember(filteredUniversalBundles, activeFilter, selectedSnapshot) {
        if (activeFilter == "App-Specific") emptyMap()
        else if (activeFilter == "Selected") {
            filteredUniversalBundles.mapValues { (_, patches) ->
                patches.filter { selectedSnapshot.contains("${it.bundleName}::${it.name}") || selectedSnapshot.contains(it.name) }
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
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val verChipLabel = when {
                            versionChoice.type == VersionSelectionType.LOCAL_FILE -> versionChoice.versionName
                            versionChoice.versionName == "Recommended" -> "Recommended"
                            else -> "v${versionChoice.versionName}"
                        }
                        Surface(
                            onClick = { showVersionSelector = true; vm.haptic(H.LIGHT) },
                            shape = RoundedCornerShape(8.dp),
                            color = cs.secondaryContainer
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    verChipLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onSecondaryContainer,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (versionChoice.isPinned) {
                                    Spacer(Modifier.width(3.dp))
                                    Icon(Sym.Lock, "Pinned", Modifier.size(11.dp), tint = cs.onSecondaryContainer)
                                } else {
                                    Spacer(Modifier.width(3.dp))
                                    Icon(Sym.ArrowDropDown, "Select version", Modifier.size(14.dp), tint = cs.onSecondaryContainer)
                                }
                            }
                        }
                        if (versionChoice.type != VersionSelectionType.LOCAL_FILE &&
                            recommendedVersion.isNotBlank() &&
                            recommendedVersion != "Recommended" &&
                            !versionChoice.versionName.equals(recommendedVersion, ignoreCase = true)
                        ) {
                            Surface(
                                onClick = {
                                    versionChoice = VersionChoice(
                                        type = VersionSelectionType.RECOMMENDED,
                                        versionName = recommendedVersion,
                                        isPinned = versionChoice.isPinned
                                    )
                                    if (versionChoice.isPinned) {
                                        com.orion.store.ui.Store.settings.update { s ->
                                            s.copy(pinnedAppVersions = s.pinnedAppVersions + (app.packageName to recommendedVersion))
                                        }
                                    }
                                    vm.haptic(H.SELECTION)
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = cs.tertiaryContainer
                            ) {
                                Text(
                                    "Use Best v$recommendedVersion",
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = cs.onTertiaryContainer,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        if (installedVersion != null) {
                            Surface(shape = RoundedCornerShape(8.dp), color = cs.tertiaryContainer.copy(alpha = 0.7f)) {
                                Text(
                                    "Installed v$installedVersion",
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onTertiaryContainer,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        } else {
                            Surface(shape = RoundedCornerShape(8.dp), color = cs.surfaceContainerHigh) {
                                Text(
                                    "Not Installed",
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        Surface(
                            onClick = {
                                directFilePicker.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
                                vm.haptic(H.LIGHT)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (versionChoice.type == VersionSelectionType.LOCAL_FILE) cs.primaryContainer else cs.surfaceContainerHigh
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Sym.FileDownload,
                                    null,
                                    Modifier.size(12.dp),
                                    tint = if (versionChoice.type == VersionSelectionType.LOCAL_FILE) cs.onPrimaryContainer else cs.onSurfaceVariant
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (versionChoice.type == VersionSelectionType.LOCAL_FILE) "Local APK Loaded" else "Provide APK",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (versionChoice.type == VersionSelectionType.LOCAL_FILE) cs.onPrimaryContainer else cs.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    if (bundleSummary.hasBundleVersionConflict) {
                        Text(
                            "Bundles target different APK versions (${bundleSummary.matchedPatchCount}/${bundleSummary.constrainedPatchCount} match v$recommendedVersion)",
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.tertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
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
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = cs.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (specificPatches.isNotEmpty()) {
                                val firstBundle = specificBundles.keys.firstOrNull() ?: ""
                                val hasAppPatchesSelected = selectedPatches.any { key ->
                                    val bName = if (key.contains("::")) key.substringBefore("::") else ""
                                    bName in specificBundles.keys
                                }
                                TextButton(
                                    onClick = {
                                        if (hasAppPatchesSelected) {
                                            selectedPatches.removeAll { key ->
                                                val bName = if (key.contains("::")) key.substringBefore("::") else ""
                                                bName in specificBundles.keys
                                            }
                                        } else {
                                            specificBundles[firstBundle]?.filter { it.isDefault }?.forEach { p ->
                                                val k = "${p.bundleName}::${p.name}"
                                                if (!selectedPatches.contains(k)) selectedPatches.add(k)
                                            }
                                        }
                                        vm.haptic(H.SELECTION)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        if (hasAppPatchesSelected) "Clear App Patches" else "Select Default ($firstBundle)",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    items(displaySpecificBundles.entries.toList(), key = { "spec-${it.key}" }) { (bundleName, bundlePatches) ->
                        val dedupPatches = remember(bundlePatches) { bundlePatches.distinctBy { it.name.trim().lowercase() } }
                        val isExpanded = expandedBundles.contains(bundleName) || activeFilter == "Selected"
                        val autoExpandCats = patchSearchQuery.isNotBlank() || activeFilter == "Selected"
                        BundleAccordionCard(
                            bundleName = bundleName,
                            patches = dedupPatches,
                            selectedPatches = selectedPatches,
                            bundleRecommendation = bundleSummary.perBundle[bundleName],
                            selectedTargetVersion = versionChoice.versionName,
                            isExpanded = isExpanded,
                            autoExpandCategories = autoExpandCats,
                            onToggleExpand = {
                                if (isExpanded) expandedBundles.remove(bundleName)
                                else expandedBundles.add(bundleName)
                                vm.haptic(H.LIGHT)
                            },
                            onSelectAllInBundle = {
                                // Clear other app-specific bundles so bytecode hooks from competing mod flavours don't conflict
                                selectedPatches.removeAll { key ->
                                    val bName = if (key.contains("::")) key.substringBefore("::") else ""
                                    bName.isNotEmpty() && bName != bundleName && bName in specificBundles.keys
                                }
                                dedupPatches.forEach { p ->
                                    val k = "${p.bundleName}::${p.name}"
                                    if (!selectedPatches.contains(k)) selectedPatches.add(k)
                                    selectedPatches.remove(p.name)
                                }
                                vm.haptic(H.SELECTION)
                            },
                            onClearAllInBundle = {
                                val names = dedupPatches.map { it.name }.toSet()
                                selectedPatches.removeAll { key ->
                                    val kName = if (key.contains("::")) key.substringAfter("::") else key
                                    val bName = if (key.contains("::")) key.substringBefore("::") else ""
                                    (bName == bundleName && kName in names) || (bName.isEmpty() && key in names)
                                }
                                vm.haptic(H.SELECTION)
                            },
                            isUniversal = false,
                            onHaptic = { vm.haptic(H.LIGHT) }
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
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = cs.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(
                                onClick = { showUniversalSection = !showUniversalSection },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    if (showUniversalSection || activeFilter == "Universal") "Hide Universal" else "Show Universal",
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    if (showUniversalSection || activeFilter == "Universal" || activeFilter == "Selected") {
                        items(displayUniversalBundles.entries.toList(), key = { "univ-${it.key}" }) { (bundleName, bundlePatches) ->
                            val dedupPatches = remember(bundlePatches) { bundlePatches.distinctBy { it.name.trim().lowercase() } }
                            val isExpanded = expandedBundles.contains("univ-$bundleName") || activeFilter == "Selected"
                            val autoExpandCats = patchSearchQuery.isNotBlank() || activeFilter == "Selected"
                            BundleAccordionCard(
                                bundleName = bundleName,
                                patches = dedupPatches,
                                selectedPatches = selectedPatches,
                                isExpanded = isExpanded,
                                autoExpandCategories = autoExpandCats,
                                onToggleExpand = {
                                    if (isExpanded) expandedBundles.remove("univ-$bundleName")
                                    else expandedBundles.add("univ-$bundleName")
                                    vm.haptic(H.LIGHT)
                                },
                                onSelectAllInBundle = {
                                    dedupPatches.forEach { p ->
                                        val k = "${p.bundleName}::${p.name}"
                                        if (!selectedPatches.contains(k)) selectedPatches.add(k)
                                        selectedPatches.remove(p.name)
                                    }
                                    vm.haptic(H.SELECTION)
                                },
                                onClearAllInBundle = {
                                    val names = dedupPatches.map { it.name }.toSet()
                                    selectedPatches.removeAll { key ->
                                        val kName = if (key.contains("::")) key.substringAfter("::") else key
                                        val bName = if (key.contains("::")) key.substringBefore("::") else ""
                                        (bName == bundleName && kName in names) || (bName.isEmpty() && key in names)
                                    }
                                    vm.haptic(H.SELECTION)
                                },
                                isUniversal = true,
                                onHaptic = { vm.haptic(H.LIGHT) }
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
                            when {
                                versionChoice.type == VersionSelectionType.LOCAL_FILE -> "Target: ${versionChoice.versionName}"
                                versionChoice.versionName == "Recommended" -> "Target: Recommended"
                                else -> "Target: v${versionChoice.versionName}"
                            } + if (versionChoice.isPinned) " (Pinned)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = {
                            val primaryBundle = selectedPatches
                                .mapNotNull { if (it.contains("::")) it.substringBefore("::") else null }
                                .filter { it in specificBundles.keys }
                                .groupBy { it }
                                .maxByOrNull { it.value.size }?.key
                                ?: specificBundles.keys.firstOrNull() ?: ""

                            val resolvedTargetVersion = when (versionChoice.type) {
                                VersionSelectionType.RECOMMENDED -> recommendedVersion
                                VersionSelectionType.LATEST -> "Latest"
                                else -> versionChoice.versionName
                            }

                            val params = PatchJobParams(
                                id = java.util.UUID.randomUUID().toString(),
                                packageName = app.packageName,
                                appName = app.name,
                                targetVersion = resolvedTargetVersion,
                                sourceApkPath = if (versionChoice.type == VersionSelectionType.LOCAL_FILE) versionChoice.localPath else null,
                                patchBundleId = primaryBundle,
                                selectedPatchIds = selectedPatches.toList()
                            )
                            vm.enqueuePatchJob(params)
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
            bundleSummary = bundleSummary,
            onVersionSelected = { choice ->
                versionChoice = choice
            },
            onDismiss = { showVersionSelector = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BundleAccordionCard(
    bundleName: String,
    patches: List<MorpheCatalogPatch>,
    selectedPatches: MutableList<String>,
    bundleRecommendation: BundleVersionRecommendation? = null,
    selectedTargetVersion: String = "",
    isExpanded: Boolean,
    autoExpandCategories: Boolean = false,
    onToggleExpand: () -> Unit,
    onSelectAllInBundle: () -> Unit,
    onClearAllInBundle: () -> Unit,
    isUniversal: Boolean = false,
    onHaptic: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    val hasQualifiedSelection = selectedPatches.any { it.contains("::") }
    val selectedInThisBundle = remember(selectedPatches.toList(), patches) {
        patches.count { p ->
            val k = "${p.bundleName}::${p.name}"
            selectedPatches.contains(k) || (!hasQualifiedSelection && selectedPatches.contains(p.name))
        }
    }
    val allSelected = selectedInThisBundle == patches.size && patches.isNotEmpty()
    val turn by androidx.compose.animation.core.animateFloatAsState(if (isExpanded) 180f else 0f, label = "turn")
    val categoryGroups = remember(patches) { groupPatchesByCategory(patches) }
    val expandedCategories = remember(bundleName) { mutableStateListOf<String>() }

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
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            bundleName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isUniversal) {
                            Surface(shape = RoundedCornerShape(5.dp), color = cs.secondaryContainer) {
                                Text(
                                    "Universal",
                                    Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                    color = cs.onSecondaryContainer,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        } else {
                            val bundleRecVer = bundleRecommendation?.recommendedVersion.orEmpty()
                            if (bundleRecVer.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = cs.tertiaryContainer.copy(alpha = 0.85f)
                                ) {
                                    Text(
                                        "Rec v$bundleRecVer",
                                        Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = cs.onTertiaryContainer,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "${patches.size} patches" +
                            (if (categoryGroups.isNotEmpty()) " · ${categoryGroups.size} categories" else "") +
                            if (selectedInThisBundle > 0) " · $selectedInThisBundle selected" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedInThisBundle > 0) cs.primary else cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(
                    onClick = {
                        if (allSelected) onClearAllInBundle() else onSelectAllInBundle()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (allSelected) "Clear" else "Select all",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Icon(
                    Sym.ExpandMore,
                    if (isExpanded) "Collapse" else "Expand",
                    Modifier.size(22.dp).graphicsLayer { rotationZ = turn },
                    tint = cs.onSurfaceVariant
                )
            }

            // Expanded Patch Items (categorized accordions when categories exist, flat list otherwise)
            if (isExpanded) {
                HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                if (categoryGroups.isNotEmpty()) {
                    Column(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        categoryGroups.forEach { (categoryName, catPatches) ->
                            key(categoryName) {
                                val catSelected = catPatches.count { p ->
                                    val k = "${p.bundleName}::${p.name}"
                                    selectedPatches.contains(k) || (!hasQualifiedSelection && selectedPatches.contains(p.name))
                                }
                                val allCatSelected = catSelected == catPatches.size && catPatches.isNotEmpty()
                                val isCatExpanded = autoExpandCategories || expandedCategories.contains(categoryName)
                                val catTurn by androidx.compose.animation.core.animateFloatAsState(
                                    if (isCatExpanded) 180f else 0f,
                                    label = "catTurn"
                                )

                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (catSelected > 0) cs.secondaryContainer.copy(alpha = 0.42f) else cs.surfaceContainer
                                ) {
                                    Column(Modifier.fillMaxWidth().animateContentSize()) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = !autoExpandCategories) {
                                                    if (expandedCategories.contains(categoryName)) expandedCategories.remove(categoryName)
                                                    else expandedCategories.add(categoryName)
                                                    onHaptic()
                                                }
                                                .padding(horizontal = 12.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Sym.Category,
                                                null,
                                                Modifier.size(18.dp),
                                                tint = if (catSelected > 0) cs.primary else cs.onSurfaceVariant
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    categoryName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    "${catPatches.size} ${if (catPatches.size == 1) "patch" else "patches"}" +
                                                        if (catSelected > 0) " · $catSelected selected" else "",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (catSelected > 0) cs.primary else cs.onSurfaceVariant
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    if (allCatSelected) {
                                                        catPatches.forEach { p ->
                                                            selectedPatches.remove("${p.bundleName}::${p.name}")
                                                            selectedPatches.remove(p.name)
                                                        }
                                                    } else {
                                                        catPatches.forEach { p ->
                                                            val k = "${p.bundleName}::${p.name}"
                                                            if (!selectedPatches.contains(k)) selectedPatches.add(k)
                                                            selectedPatches.remove(p.name)
                                                        }
                                                    }
                                                    onHaptic()
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                            ) {
                                                Text(if (allCatSelected) "Clear" else "All", style = MaterialTheme.typography.labelSmall)
                                            }
                                            Icon(
                                                Sym.ExpandMore,
                                                if (isCatExpanded) "Collapse $categoryName" else "Expand $categoryName",
                                                Modifier.size(20.dp).graphicsLayer { rotationZ = catTurn },
                                                tint = cs.onSurfaceVariant
                                            )
                                        }

                                        if (isCatExpanded) {
                                            HorizontalDivider(
                                                Modifier.padding(horizontal = 10.dp),
                                                color = cs.outlineVariant.copy(alpha = 0.35f)
                                            )
                                            Column(
                                                Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                catPatches.forEach { patch ->
                                                    PatchRowItem(
                                                        patch = patch,
                                                        selectedPatches = selectedPatches,
                                                        bundleRecommendation = bundleRecommendation,
                                                        selectedTargetVersion = selectedTargetVersion,
                                                        onHaptic = onHaptic
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        patches.forEach { patch ->
                            PatchRowItem(
                                patch = patch,
                                selectedPatches = selectedPatches,
                                bundleRecommendation = bundleRecommendation,
                                selectedTargetVersion = selectedTargetVersion,
                                onHaptic = onHaptic
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatchRowItem(
    patch: MorpheCatalogPatch,
    selectedPatches: MutableList<String>,
    bundleRecommendation: BundleVersionRecommendation? = null,
    selectedTargetVersion: String = "",
    onHaptic: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val patchKey = "${patch.bundleName}::${patch.name}"
    val isChecked = selectedPatches.contains(patchKey) ||
        (selectedPatches.contains(patch.name) && !selectedPatches.any { it.contains("::") })
    val recVer = remember(patch, bundleRecommendation) {
        BundleVersionRecommender.bestVersionForPatch(patch, bundleRecommendation)
    }
    val matchesSelectedTarget = remember(patch, selectedTargetVersion) {
        BundleVersionRecommender.isPatchCompatibleWithVersion(patch, selectedTargetVersion)
    }
    val togglePatch: (Boolean) -> Unit = { checked ->
        if (checked) {
            if (!selectedPatches.contains(patchKey)) selectedPatches.add(patchKey)
            selectedPatches.remove(patch.name)
        } else {
            selectedPatches.remove(patchKey)
            selectedPatches.remove(patch.name)
        }
        onHaptic()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { togglePatch(!isChecked) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = togglePatch
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    patch.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal
                )
                if (recVer.isNotBlank()) {
                    val badgeBg = if (matchesSelectedTarget) cs.tertiaryContainer.copy(alpha = 0.78f) else cs.errorContainer
                    val badgeFg = if (matchesSelectedTarget) cs.onTertiaryContainer else cs.onErrorContainer
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = badgeBg
                    ) {
                        Text(
                            text = "v$recVer",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                            fontWeight = FontWeight.Medium,
                            color = badgeFg,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
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
