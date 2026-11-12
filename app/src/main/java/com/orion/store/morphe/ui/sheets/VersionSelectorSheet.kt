package com.orion.store.morphe.ui.sheets

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orion.store.morphe.store.MorpheCatalogApp
import com.orion.store.morphe.store.SelectedBundlesVersionSummary
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym

enum class VersionSelectionType {
    RECOMMENDED,
    INSTALLED,
    SPECIFIC,
    LATEST,
    LOCAL_FILE
}

data class VersionChoice(
    val type: VersionSelectionType,
    val versionName: String,
    val localUri: Uri? = null,
    val localPath: String? = null,
    val isPinned: Boolean = false
)

private data class VersionBadge(
    val text: String,
    val containerColor: Color,
    val contentColor: Color
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VersionSelectorSheet(
    vm: MainViewModel,
    app: MorpheCatalogApp,
    currentChoice: VersionChoice,
    bundleSummary: SelectedBundlesVersionSummary? = null,
    onVersionSelected: (VersionChoice) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val recommendedVersion = remember(bundleSummary, app.compatibleVersions) {
        bundleSummary?.bestVersion?.takeIf { it.isNotBlank() }
            ?: app.compatibleVersions.firstOrNull()
            ?: "Recommended"
    }
    val candidateVersions = remember(bundleSummary, app.compatibleVersions) {
        bundleSummary?.allCandidateVersions?.takeIf { it.isNotEmpty() }
            ?: app.compatibleVersions
    }

    var selectedType by remember(app.packageName, currentChoice) { mutableStateOf(currentChoice.type) }
    var selectedVersion by remember(app.packageName, currentChoice, recommendedVersion) {
        mutableStateOf(currentChoice.versionName.ifEmpty { recommendedVersion })
    }
    var isPinned by remember(app.packageName, currentChoice) {
        mutableStateOf(vm.s.pinnedAppVersions.containsKey(app.packageName) || currentChoice.isPinned)
    }
    var localFileUri by remember(app.packageName, currentChoice) { mutableStateOf(currentChoice.localUri) }
    var localFileName by remember(app.packageName, currentChoice) {
        mutableStateOf(currentChoice.localPath?.substringAfterLast("/") ?: "")
    }
    var localFilePath by remember(app.packageName, currentChoice) { mutableStateOf(currentChoice.localPath) }

    fun syncPinSetting(pinned: Boolean, version: String, type: VersionSelectionType) {
        com.orion.store.ui.Store.settings.update { s ->
            if (pinned && type != VersionSelectionType.LATEST && type != VersionSelectionType.LOCAL_FILE && version.isNotBlank()) {
                s.copy(pinnedAppVersions = s.pinnedAppVersions + (app.packageName to version))
            } else if (!pinned) {
                s.copy(pinnedAppVersions = s.pinnedAppVersions - app.packageName)
            } else {
                s
            }
        }
    }

    fun selectAndApply(
        type: VersionSelectionType,
        version: String,
        uri: Uri? = localFileUri,
        path: String? = localFilePath
    ) {
        selectedType = type
        selectedVersion = version
        syncPinSetting(isPinned, version, type)
        vm.haptic(H.SELECTION)
        onVersionSelected(
            VersionChoice(
                type = type,
                versionName = version,
                localUri = uri,
                localPath = path,
                isPinned = isPinned
            )
        )
        onDismiss()
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast("/") ?: "Selected Local APK"
            localFileUri = uri
            localFileName = name
            scope.launch(Dispatchers.IO) {
                try {
                    val dest = File(context.cacheDir, "picked_${app.packageName}_${System.currentTimeMillis()}.apk")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    if (com.orion.store.platform.DownloadOrchestrator.isApkValid(dest)) {
                        com.orion.store.platform.DownloadOrchestrator.cacheBaseApk(context, app.packageName, "Local", dest)
                        withContext(Dispatchers.Main) {
                            localFilePath = dest.absolutePath
                            selectAndApply(
                                type = VersionSelectionType.LOCAL_FILE,
                                version = "Local: $name",
                                uri = uri,
                                path = dest.absolutePath
                            )
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
            // Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Select APK Version",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        app.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Sym.Close, "Dismiss")
                }
            }

            if (bundleSummary != null && bundleSummary.perBundle.values.any { it.recommendedVersion.isNotBlank() }) {
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    bundleSummary.perBundle.values
                        .filter { it.recommendedVersion.isNotBlank() }
                        .forEach { rec ->
                            val isActive = rec.bundleName in bundleSummary.activeBundleNames
                            Surface(
                                onClick = { selectAndApply(VersionSelectionType.SPECIFIC, rec.recommendedVersion) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isActive) cs.tertiaryContainer else cs.surfaceContainerHigh
                            ) {
                                Text(
                                    text = "${rec.bundleName}: v${rec.recommendedVersion}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isActive) cs.onTertiaryContainer else cs.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Pinning Control Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (isPinned) cs.primaryContainer.copy(alpha = 0.4f) else cs.surfaceContainerHigh
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Sym.Lock,
                        null,
                        Modifier.size(22.dp),
                        tint = if (isPinned) cs.primary else cs.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Pin selected APK version",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Prevents background auto-updates from changing base APK",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = isPinned,
                        onCheckedChange = { checked ->
                            isPinned = checked
                            vm.haptic(H.SELECTION)
                            syncPinSetting(checked, selectedVersion, selectedType)
                            onVersionSelected(
                                VersionChoice(
                                    type = selectedType,
                                    versionName = selectedVersion,
                                    localUri = localFileUri,
                                    localPath = localFilePath,
                                    isPinned = checked
                                )
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Version Options List (1-tap immediate binding & close)
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Choice 1: Best Recommended Version for Selected Bundles (Default)
                item {
                    val activeLabel = bundleSummary?.activeBundleNames
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(" + ")
                    val recSubtitle = when {
                        bundleSummary?.hasBundleVersionConflict == true ->
                            "Partial fit (${bundleSummary.matchedPatchCount}/${bundleSummary.constrainedPatchCount} patches) — bundles target different versions"
                        activeLabel != null && (bundleSummary.constrainedPatchCount > 0) ->
                            "Best for $activeLabel (${bundleSummary.matchedPatchCount}/${bundleSummary.constrainedPatchCount} constrained patches)"
                        activeLabel != null ->
                            "Best recommended base for $activeLabel"
                        else ->
                            "Guaranteed fingerprint match with selected patches"
                    }
                    val recBadges = buildList {
                        add(VersionBadge("Recommended", cs.primaryContainer, cs.onPrimaryContainer))
                        if (bundleSummary?.hasBundleVersionConflict == true) {
                            add(VersionBadge("Partial Fit", cs.tertiaryContainer, cs.onTertiaryContainer))
                        }
                    }
                    VersionChoiceRow(
                        title = if (recommendedVersion == "Recommended") "Recommended" else "Best Match (v$recommendedVersion)",
                        subtitle = recSubtitle,
                        badges = recBadges,
                        isSelected = selectedType == VersionSelectionType.RECOMMENDED,
                        onClick = {
                            selectAndApply(VersionSelectionType.RECOMMENDED, recommendedVersion)
                        }
                    )
                }

                // Choice 1.5: Installed on Device
                val installedVer = vm.s.installedVersions[app.packageName]
                if (!installedVer.isNullOrBlank()) {
                    item {
                        VersionChoiceRow(
                            title = "Installed on Device (v$installedVer)",
                            subtitle = "Extracted directly from this phone — zero download needed",
                            badges = listOf(VersionBadge("Installed", cs.tertiaryContainer, cs.onTertiaryContainer)),
                            isSelected = selectedType == VersionSelectionType.INSTALLED,
                            onClick = {
                                selectAndApply(VersionSelectionType.INSTALLED, installedVer)
                            }
                        )
                    }
                }

                // Choice 2: Latest Upstream Version
                item {
                    VersionChoiceRow(
                        title = "Latest Upstream Version",
                        subtitle = "Newest store release (may trigger fingerprint mismatch)",
                        badges = listOf(VersionBadge("Experimental", cs.errorContainer, cs.onErrorContainer)),
                        isSelected = selectedType == VersionSelectionType.LATEST,
                        onClick = {
                            selectAndApply(VersionSelectionType.LATEST, "Latest")
                        }
                    )
                }

                // Choice 3: Specific Versions (sorted newest-first with per-bundle tags)
                if (candidateVersions.isNotEmpty()) {
                    item {
                        Text(
                            "Specific Versions (${candidateVersions.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = cs.primary,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                    }
                    items(candidateVersions, key = { it }) { ver ->
                        val recBundles = bundleSummary?.perBundle?.values
                            ?.filter { it.recommendedVersion.equals(ver, ignoreCase = true) }
                            ?.map { it.bundleName }
                            .orEmpty()
                        val badges = buildList {
                            if (ver.equals(recommendedVersion, ignoreCase = true)) {
                                add(VersionBadge("Recommended", cs.primaryContainer, cs.onPrimaryContainer))
                            }
                            recBundles.take(2).forEach { bName ->
                                add(VersionBadge(bName, cs.tertiaryContainer, cs.onTertiaryContainer))
                            }
                            if (isEmpty()) {
                                add(VersionBadge("Tested", cs.secondaryContainer, cs.onSecondaryContainer))
                            }
                        }
                        val sub = if (recBundles.isNotEmpty()) {
                            "Recommended by ${recBundles.joinToString(", ")}"
                        } else {
                            "Supported version from catalog"
                        }
                        VersionChoiceRow(
                            title = "v$ver",
                            subtitle = sub,
                            badges = badges,
                            isSelected = selectedType == VersionSelectionType.SPECIFIC && selectedVersion.equals(ver, ignoreCase = true),
                            onClick = {
                                selectAndApply(VersionSelectionType.SPECIFIC, ver)
                            }
                        )
                    }
                }

                // Choice 4: Select Local APK / Split Bundle
                item {
                    Text(
                        "Local Storage",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                    VersionChoiceRow(
                        title = if (localFileName.isNotBlank()) "Local: $localFileName" else "Select Local APK / Split Bundle",
                        subtitle = "Pick any .apk, .xapk, .apkm, or .apks file from device storage",
                        badges = listOf(VersionBadge("File Picker", cs.tertiaryContainer, cs.onTertiaryContainer)),
                        isSelected = selectedType == VersionSelectionType.LOCAL_FILE,
                        onClick = {
                            filePicker.launch(arrayOf(
                                "application/vnd.android.package-archive",
                                "application/octet-stream",
                                "*/*"
                            ))
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Secondary Apply / Done action (tapping any version option already applies immediately)
            Button(
                onClick = {
                    selectAndApply(selectedType, selectedVersion)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                val labelVer = if (selectedType == VersionSelectionType.LOCAL_FILE) {
                    selectedVersion
                } else {
                    "v$selectedVersion"
                }
                Text(
                    "Use $labelVer",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VersionChoiceRow(
    title: String,
    subtitle: String,
    badges: List<VersionBadge>,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) cs.surfaceContainerHighest else cs.surfaceContainer,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, cs.primary) else null
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    badges.forEach { badge ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badge.containerColor
                        ) {
                            Text(
                                badge.text,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = badge.contentColor,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

