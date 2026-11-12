package com.orion.store.morphe.ui.tabs

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orion.store.data.Tab
import com.orion.store.morphe.engine.MorphePatch
import com.orion.store.morphe.engine.PatchBundle
import com.orion.store.morphe.model.PatchJobParams
import com.orion.store.morphe.store.JobStore
import com.orion.store.morphe.store.KeyStoreManager
import com.orion.store.morphe.store.MorpheRepoStore
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchStudioTab(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val bundles = remember { MorpheRepoStore.getAllBundles() }

    // State for Studio workflow
    var selectedAppName by remember { mutableStateOf("YouTube ReVanced") }
    var selectedPkgName by remember { mutableStateOf("app.revanced.android.youtube") }
    var targetVersion by remember { mutableStateOf("19.16.39") }

    val selectedBundle = remember(bundles) { bundles.firstOrNull() }
    val selectedPatchIds = remember {
        mutableStateListOf(
            "sponsorblock",
            "return-youtube-dislike",
            "hide-shorts",
            "microg-support"
        )
    }

    var autoInstall by remember { mutableStateOf(true) }
    var selectedKeyAlias by remember { mutableStateOf("morphe-default") }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    vm.haptic(H.MEDIUM)
                    val jobId = "job-${UUID.randomUUID().toString().take(8)}"
                    val params = PatchJobParams(
                        id = jobId,
                        packageName = selectedPkgName,
                        appName = selectedAppName,
                        targetVersion = targetVersion,
                        patchBundleId = selectedBundle?.id ?: "morphe-custom",
                        selectedPatchIds = selectedPatchIds.toList(),
                        signingKeyAlias = selectedKeyAlias,
                        autoInstall = autoInstall
                    )
                    vm.enqueuePatchJob(params, saveSelections = false)
                    // Navigate directly to Queue tab so user sees live progress
                    vm.navTo(Tab.PATCH_QUEUE)
                },
                modifier = Modifier.padding(bottom = 80.dp),
                icon = { Icon(Sym.Build, null) },
                text = { Text("Patch App (${selectedPatchIds.size} patches)") },
                containerColor = cs.primary,
                contentColor = cs.onPrimary
            )
        }
    ) { innerPadding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(cs.background),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 160.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Patch Studio",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = cs.onBackground
                )
                Text(
                    text = "Select application, configure patches, and generate signed APKs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }

            // Step 1: Target App Selection Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(cs.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("1", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("Target Application", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(12.dp))

                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(cs.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Sym.Android, null, Modifier.size(24.dp), tint = cs.onPrimaryContainer)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(selectedAppName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text("$selectedPkgName • v$targetVersion", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    }
                                }
                                Box(
                                    Modifier.clip(RoundedCornerShape(8.dp)).background(cs.tertiaryContainer).padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Selected", style = MaterialTheme.typography.labelSmall, color = cs.onTertiaryContainer)
                                }
                            }
                        }
                    }
                }
            }

            // Step 2: Available Patches Selection
            item {
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape).background(cs.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("2", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text("Select Patches", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }

                            Row {
                                Text(
                                    text = "${selectedPatchIds.size} of ${selectedBundle?.patches?.size ?: 0} selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        selectedBundle?.patches?.forEach { patch ->
                            val isChecked = selectedPatchIds.contains(patch.id)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        vm.haptic(H.LIGHT)
                                        if (isChecked) selectedPatchIds.remove(patch.id)
                                        else selectedPatchIds.add(patch.id)
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        vm.haptic(H.LIGHT)
                                        if (checked) selectedPatchIds.add(patch.id)
                                        else selectedPatchIds.remove(patch.id)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = cs.primary)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(patch.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        if (patch.isUniversal) {
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                Modifier.clip(RoundedCornerShape(4.dp)).background(cs.tertiaryContainer).padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text("Universal", style = MaterialTheme.typography.labelSmall, color = cs.onTertiaryContainer)
                                            }
                                        }
                                    }
                                    Text(patch.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            // Step 3: Build & Signing Options
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(cs.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("3", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("Signing & Automation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(14.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto-install upon build", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Uses Shizuku or Android PackageInstaller", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            Switch(checked = autoInstall, onCheckedChange = { autoInstall = it; vm.haptic(H.LIGHT) })
                        }

                        Spacer(Modifier.height(10.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Signing Key", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Active key: $selectedKeyAlias", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            Box(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Keystore Key", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}
