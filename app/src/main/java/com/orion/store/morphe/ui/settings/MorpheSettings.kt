package com.orion.store.morphe.ui.settings

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.orion.store.data.Settings
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym
import kotlinx.coroutines.launch

/**
 * Full Morphe Settings page integrated into Orion's settings hub.
 */
@Composable
fun MorpheSettingsPage(
    vm: MainViewModel,
    s: Settings,
    onNavigateToNavCustomize: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val repos by com.orion.store.morphe.store.MorpheRepoStore.repos.collectAsState()
    var showManageRepos by remember { mutableStateOf(false) }
    var repoToEdit by remember { mutableStateOf<com.orion.store.morphe.model.MorpheRepo?>(null) }
    var showAddRepoDialog by remember { mutableStateOf(false) }
    var newRepoName by remember { mutableStateOf("") }
    var newRepoUrl by remember { mutableStateOf("") }
    var confirmResetRepos by remember { mutableStateOf(false) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var keyInfo by remember { mutableStateOf(com.orion.store.morphe.store.KeyStoreManager.getActiveKeyInfo()) }
    var showExportCert by remember { mutableStateOf(false) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Master Feature Flag Toggle
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = if (s.morpheEnabled) cs.primaryContainer else cs.surfaceContainerHigh
        ) {
            Row(
                Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Enable Morphe", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (s.morpheEnabled) "Morphe store, patch studio, and job queue active" else "Morphe is off (Orion runs strictly as core store)",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
                Switch(
                    checked = s.morpheEnabled,
                    onCheckedChange = {
                        vm.haptic(H.SELECTION)
                        vm.set { it.copy(morpheEnabled = !it.morpheEnabled) }
                    }
                )
            }
        }

        if (s.morpheEnabled) {
            // Navigation Customization Shortcut
            Surface(
                Modifier.fillMaxWidth().clickable { onNavigateToNavCustomize() },
                shape = RoundedCornerShape(20.dp),
                color = cs.tertiaryContainer
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sym.Tune, null, tint = cs.onTertiaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Navigation & Tab Customization", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onTertiaryContainer)
                        Text("Reorder, hide, or show Morphe tabs in the bottom bar", style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer.copy(alpha = 0.8f))
                    }
                    Icon(Sym.KeyboardArrowRight, null, tint = cs.onTertiaryContainer)
                }
            }

            // Background Auto-Patching & Updates Section
            Text("Background Auto-Patching & Updates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = cs.surfaceContainerHigh
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-patch updates in background", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Automatically re-patch installed apps when community patch bundles or recommended APKs update",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = s.autoPatchGlobal,
                            onCheckedChange = { checked ->
                                vm.haptic(H.SELECTION)
                                vm.set { it.copy(autoPatchGlobal = checked) }
                            }
                        )
                    }

                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-install patched builds", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Install silently via Shizuku, Dhizuku, Root, or system session without manual prompts",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = s.autoInstallPatchedGlobal,
                            onCheckedChange = { checked ->
                                vm.haptic(H.SELECTION)
                                vm.set { it.copy(autoInstallPatchedGlobal = checked) }
                            }
                        )
                    }
                }
            }

            // Patching Engine Section
            Text("Patching Engine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = cs.surfaceContainerHigh
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym.Build, null, tint = cs.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Morphe Engine v2.0", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Dalvik bytecode patcher & Smali injector", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Badge(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer) { Text("Ready") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Execution mode: Native in-process worker", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }

            // Repositories Section
            Text("Community Repositories", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Surface(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { showManageRepos = true },
                shape = RoundedCornerShape(20.dp),
                color = cs.surfaceContainerHigh
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym.Extension, null, tint = cs.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${repos.size} Repositories Configured", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "${repos.count { it.isEnabled }} active · Tap to view, add, edit, or remove",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                        Icon(Sym.KeyboardArrowRight, "Open", tint = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { showManageRepos = true },
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Icon(Sym.Tune, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Manage Repositories")
                        }
                        FilledTonalButton(
                            onClick = {
                                newRepoName = ""
                                newRepoUrl = ""
                                showAddRepoDialog = true
                            },
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Icon(Sym.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Repo")
                        }
                    }
                }
            }

            // Signing Keys Section
            Text("Signing Keys (Keystore)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = cs.surfaceContainerHigh
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym.Key, null, tint = cs.secondary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Active Key: ${keyInfo.alias}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Algorithm: ${keyInfo.algorithm} · SHA-256", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            Text(
                                "Fingerprint: ${keyInfo.fingerprintSha256}",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            keyInfo = com.orion.store.morphe.store.KeyStoreManager.generateNewKey("morphe_custom_${System.currentTimeMillis() % 10000}")
                            vm.haptic(H.SUCCESS)
                        }, shapes = ButtonDefaults.shapes()) {
                            Icon(Sym.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Generate Key")
                        }
                        FilledTonalButton(onClick = { showExportCert = true }, shapes = ButtonDefaults.shapes()) {
                            Icon(Sym.FileDownload, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Export Cert")
                        }
                    }
                }
            }

            // Cache & Temp Storage Section
            Text("Storage & Cache", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = cs.surfaceContainerHigh
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Clear Patch Cache", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Remove intermediate dex, align, and temporary files", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { confirmClearCache = true }, shapes = ButtonDefaults.shapes()) {
                        Text("Clear")
                    }
                }
            }
        }
    }

    // Confirmation Dialogs
    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("Clear Patch Cache?") },
            text = { Text("This will delete all temporary build artifacts. Downloaded source APKs and installed apps are preserved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearCache = false
                    vm.haptic(H.SUCCESS)
                }) { Text("Clear", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmResetRepos) {
        AlertDialog(
            onDismissRequest = { confirmResetRepos = false },
            title = { Text("Reset Repositories?") },
            text = { Text("Custom patch bundle repositories will be removed and reset to the official Morphe repository.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmResetRepos = false
                    vm.haptic(H.SUCCESS)
                }) { Text("Reset", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetRepos = false }) { Text("Cancel") }
            }
        )
    }

    if (showExportCert) {
        val pem = remember(keyInfo) { com.orion.store.morphe.store.KeyStoreManager.getCertificatePem(keyInfo.alias) }
        AlertDialog(
            onDismissRequest = { showExportCert = false },
            title = { Text("Public X.509 Certificate") },
            text = {
                Column {
                    Text("Subject: ${keyInfo.subject.ifEmpty { "CN=Morphe Default" }}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("SHA-256 Fingerprint:\n${keyInfo.fingerprintSha256}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        Modifier.fillMaxWidth().heightIn(max = 180.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = cs.surfaceContainerLowest
                    ) {
                        Text(
                            pem,
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(pem))
                    vm.haptic(H.SUCCESS)
                    showExportCert = false
                }) { Text("Copy PEM") }
            },
            dismissButton = {
                TextButton(onClick = { showExportCert = false }) { Text("Close") }
            }
        )
    }

    // Community Repositories Management Dialog
    if (showManageRepos) {
        AlertDialog(
            onDismissRequest = { showManageRepos = false },
            title = {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Community Repositories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(
                        onClick = {
                            newRepoName = ""
                            newRepoUrl = ""
                            showAddRepoDialog = true
                        }
                    ) {
                        Icon(Sym.Add, "Add Repository", tint = cs.primary)
                    }
                }
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "Manage patch repositories. Removed repositories will never be automatically re-added.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var syncingMM by remember { mutableStateOf(false) }
                        FilledTonalButton(
                            onClick = {
                                if (syncingMM) return@FilledTonalButton
                                syncingMM = true
                                scope.launch {
                                    val added = com.orion.store.morphe.store.MorpheRepoStore.syncFromMetaMorpheSources()
                                    syncingMM = false
                                    vm.haptic(H.SUCCESS)
                                    vm.snackbar.showSnackbar(
                                        message = "Synced from https://metamorphe.vercel.app/#sources ($added new sources added)",
                                        withDismissAction = true
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shapes = ButtonDefaults.shapes()
                        ) {
                            if (syncingMM) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Sym.Refresh, null, Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Sync MétaMorphe Sources", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(repos, key = { it.id }) { repo ->
                            Surface(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = cs.surfaceContainerHighest
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                repo.name,
                                                modifier = Modifier.weight(1f, fill = false),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            if (repo.isDefault) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = cs.primaryContainer
                                                ) {
                                                    Text(
                                                        "Official",
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = cs.onPrimaryContainer,
                                                        maxLines = 1,
                                                        softWrap = false
                                                    )
                                                }
                                            } else if (repo.description.contains("MétaMorphe", true)) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = cs.secondaryContainer
                                                ) {
                                                    Text(
                                                        "MétaMorphe",
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = cs.onSecondaryContainer,
                                                        maxLines = 1,
                                                        softWrap = false
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            repo.url,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = cs.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        if (repo.description.isNotBlank()) {
                                            Text(
                                                repo.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = cs.primary,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { repoToEdit = repo },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Sym.Edit, "Edit", Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                                    }

                                    IconButton(
                                        onClick = {
                                            com.orion.store.morphe.store.MorpheRepoStore.removeRepo(repo.id)
                                            vm.haptic(H.MEDIUM)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Sym.Delete, "Remove", Modifier.size(16.dp), tint = cs.error)
                                    }

                                    Switch(
                                        checked = repo.isEnabled,
                                        onCheckedChange = { enabled ->
                                            com.orion.store.morphe.store.MorpheRepoStore.toggleRepo(repo.id, enabled)
                                            vm.haptic(H.SELECTION)
                                        },
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageRepos = false }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = {
                    com.orion.store.morphe.store.MorpheRepoStore.resetToDefaults()
                    vm.haptic(H.SUCCESS)
                }) {
                    Text("Reset to Defaults", color = cs.error)
                }
            }
        )
    }

    // Add Repository Dialog
    if (showAddRepoDialog) {
        var addError by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddRepoDialog = false },
            title = { Text("Add Repository") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter repository details to fetch community patches.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    OutlinedTextField(
                        value = newRepoName,
                        onValueChange = { newRepoName = it },
                        label = { Text("Repository Name") },
                        placeholder = { Text("e.g. Crimera Patches") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRepoUrl,
                        onValueChange = { newRepoUrl = it },
                        label = { Text("Repository JSON URL") },
                        placeholder = { Text("https://example.com/patches.json") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (addError.isNotEmpty()) {
                        Text(addError, style = MaterialTheme.typography.labelSmall, color = cs.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newRepoName.isBlank() || newRepoUrl.isBlank()) {
                            addError = "Please enter both name and URL"
                            return@TextButton
                        }
                        com.orion.store.morphe.store.MorpheRepoStore.addRepo(newRepoName.trim(), newRepoUrl.trim())
                        vm.haptic(H.SUCCESS)
                        showAddRepoDialog = false
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddRepoDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Edit Repository Dialog
    repoToEdit?.let { repo ->
        var editName by remember { mutableStateOf(repo.name) }
        var editUrl by remember { mutableStateOf(repo.url) }
        AlertDialog(
            onDismissRequest = { repoToEdit = null },
            title = { Text("Edit Repository") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Repository Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("Repository URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editName.isNotBlank() && editUrl.isNotBlank()) {
                            com.orion.store.morphe.store.MorpheRepoStore.updateRepo(repo.id, editName.trim(), editUrl.trim())
                            vm.haptic(H.SUCCESS)
                        }
                        repoToEdit = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { repoToEdit = null }) { Text("Cancel") }
            }
        )
    }
}
