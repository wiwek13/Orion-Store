package com.orion.store.morphe.ui.sheets

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym

enum class VersionSelectionType {
    RECOMMENDED,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionSelectorSheet(
    vm: MainViewModel,
    app: MorpheCatalogApp,
    currentChoice: VersionChoice,
    onVersionSelected: (VersionChoice) -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    val recommendedVersion = remember(app.compatibleVersions) {
        app.compatibleVersions.firstOrNull() ?: "Recommended"
    }

    var selectedType by remember { mutableStateOf(currentChoice.type) }
    var selectedVersion by remember { mutableStateOf(currentChoice.versionName.ifEmpty { recommendedVersion }) }
    var isPinned by remember {
        mutableStateOf(vm.s.pinnedAppVersions.containsKey(app.packageName) || currentChoice.isPinned)
    }
    var localFileUri by remember { mutableStateOf(currentChoice.localUri) }
    var localFileName by remember { mutableStateOf(currentChoice.localPath?.substringAfterLast("/") ?: "") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            localFileUri = uri
            val name = uri.lastPathSegment?.substringAfterLast("/") ?: "Selected Local APK"
            localFileName = name
            selectedType = VersionSelectionType.LOCAL_FILE
            selectedVersion = "Local: $name"
            vm.haptic(H.SELECTION)
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
                .padding(bottom = 28.dp)
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
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        app.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Sym.Close, "Dismiss")
                }
            }

            Spacer(Modifier.height(14.dp))

            // Pinning Control Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (isPinned) cs.primaryContainer.copy(alpha = 0.4f) else cs.surfaceContainerHigh
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
                            "Prevents background auto-updates from silently upgrading the base APK",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isPinned,
                        onCheckedChange = { checked ->
                            isPinned = checked
                            vm.haptic(H.SELECTION)
                            // Persist to settings
                            com.orion.store.ui.Store.settings.update { s ->
                                if (checked) {
                                    s.copy(pinnedAppVersions = s.pinnedAppVersions + (app.packageName to selectedVersion))
                                } else {
                                    s.copy(pinnedAppVersions = s.pinnedAppVersions - app.packageName)
                                }
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Version Options List
            LazyColumn(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Choice 1: Recommended Version (Default)
                item {
                    VersionChoiceRow(
                        title = "Recommended (v$recommendedVersion)",
                        subtitle = "Guaranteed 100% fingerprint match with selected patches",
                        badge = "Recommended",
                        badgeColor = cs.primaryContainer,
                        badgeTextColor = cs.onPrimaryContainer,
                        isSelected = selectedType == VersionSelectionType.RECOMMENDED,
                        onClick = {
                            selectedType = VersionSelectionType.RECOMMENDED
                            selectedVersion = recommendedVersion
                            vm.haptic(H.SELECTION)
                        }
                    )
                }

                // Choice 2: Latest Upstream Version
                item {
                    VersionChoiceRow(
                        title = "Latest Upstream Version",
                        subtitle = "Absolute newest store build (may trigger fingerprint mismatch)",
                        badge = "Experimental",
                        badgeColor = cs.errorContainer,
                        badgeTextColor = cs.onErrorContainer,
                        isSelected = selectedType == VersionSelectionType.LATEST,
                        onClick = {
                            selectedType = VersionSelectionType.LATEST
                            selectedVersion = "Latest"
                            vm.haptic(H.SELECTION)
                        }
                    )
                }

                // Choice 3: Specific Versions
                if (app.compatibleVersions.isNotEmpty()) {
                    item {
                        Text(
                            "Specific Versions (${app.compatibleVersions.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = cs.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(app.compatibleVersions) { ver ->
                        val isCompatible = true
                        VersionChoiceRow(
                            title = "Version $ver",
                            subtitle = "Available from Orion Store & MorpheFlow catalog",
                            badge = if (ver == recommendedVersion) "Recommended" else "Tested",
                            badgeColor = if (ver == recommendedVersion) cs.primaryContainer else cs.secondaryContainer,
                            badgeTextColor = if (ver == recommendedVersion) cs.onPrimaryContainer else cs.onSecondaryContainer,
                            isSelected = selectedType == VersionSelectionType.SPECIFIC && selectedVersion == ver,
                            onClick = {
                                selectedType = VersionSelectionType.SPECIFIC
                                selectedVersion = ver
                                vm.haptic(H.SELECTION)
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
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    VersionChoiceRow(
                        title = if (localFileName.isNotBlank()) "Local: $localFileName" else "Select Local APK / Split Bundle",
                        subtitle = "Pick any .apk, .xapk, .apkm, or .apks file from device storage",
                        badge = "File Picker",
                        badgeColor = cs.tertiaryContainer,
                        badgeTextColor = cs.onTertiaryContainer,
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

            Spacer(Modifier.height(18.dp))

            // Confirm Selection Button
            Button(
                onClick = {
                    onVersionSelected(
                        VersionChoice(
                            type = selectedType,
                            versionName = selectedVersion,
                            localUri = localFileUri,
                            localPath = localFileName.takeIf { it.isNotBlank() },
                            isPinned = isPinned
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text("Confirm Version Selection", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun VersionChoiceRow(
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color,
    badgeTextColor: Color,
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = badgeColor) {
                        Text(
                            badge,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeTextColor
                        )
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

