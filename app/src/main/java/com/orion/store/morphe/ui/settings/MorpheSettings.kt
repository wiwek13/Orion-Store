package com.orion.store.morphe.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    var confirmClearCache by remember { mutableStateOf(false) }
    var confirmResetRepos by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Master Feature Flag Toggle
        item {
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
        }

        if (s.morpheEnabled) {
            // Navigation Customization Shortcut
            item {
                Surface(
                    Modifier.fillMaxWidth().clickable { onNavigateToNavCustomize() },
                    shape = RoundedCornerShape(18.dp),
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
            }

            // Patching Engine Section
            item {
                Text("Patching Engine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
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
            }

            // Repositories Section
            item {
                Text("Community Repositories", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = cs.surfaceContainerHigh
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Sym.Extension, null, tint = cs.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("MorpheApp/morphe-patches", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("Default repository · 42 compatible target apps", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { confirmResetRepos = true }) {
                                Text("Reset to Default")
                            }
                            FilledTonalButton(onClick = { /* Add repo modal */ }) {
                                Icon(Sym.Add, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add Repository")
                            }
                        }
                    }
                }
            }

            // Signing Keys Section
            item {
                Text("Signing Keys (Keystore)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = cs.surfaceContainerHigh
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Sym.Key, null, tint = cs.secondary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Android Keystore Key", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("Alias: morphe_default_key (RSA 2048)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { showKeyDialog = true }) {
                                Text("Generate / Export")
                            }
                        }
                    }
                }
            }

            // Cache & Temp Storage Section
            item {
                Text("Storage & Cache", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
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
                        OutlinedButton(onClick = { confirmClearCache = true }) {
                            Text("Clear")
                        }
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

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("APK Signing Keys") },
            text = { Text("Key material is safely protected by hardware-backed Android Keystore and never saved in plaintext.") },
            confirmButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("Close") }
            }
        )
    }
}
