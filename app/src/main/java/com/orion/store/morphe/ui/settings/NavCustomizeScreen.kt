package com.orion.store.morphe.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.orion.store.data.Settings
import com.orion.store.morphe.nav.*
import com.orion.store.morphe.ui.MockupDockRenderer
import com.orion.store.ui.H
import com.orion.store.ui.MainViewModel
import com.orion.store.ui.Sym

/**
 * Navigation Bar Customization Screen:
 * - Live preview of the customized bottom dock.
 * - Move up / down accessible controls and drag handles.
 * - Individual visibility toggles per destination.
 * - Mandatory destination protection (About is locked).
 * - "Reset to default" action.
 */
@Composable
fun NavCustomizeScreen(
    vm: MainViewModel,
    s: Settings,
    onBack: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val configState = s.navConfig.takeIf { it.items.isNotEmpty() } ?: NavConfigState.defaultState()
    val allDestinations = NavRegistry.all.associateBy { it.id }

    // Live list of items in current user order
    val items = configState.items.mapNotNull { item ->
        allDestinations[item.id]?.let { dest -> item to dest }
    }

    Column(Modifier.fillMaxSize()) {
        // Top app bar with back navigation and reset action
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Sym.ArrowBack, "Back")
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Customize Navigation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Reorder and toggle tabs", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            TextButton(onClick = {
                vm.haptic(H.MEDIUM)
                val reset = reduceNavConfig(configState, NavConfigAction.ResetToDefault)
                vm.set { it.copy(navConfig = reset) }
            }) {
                Text("Reset")
            }
        }

        // Live Bar Preview
        Surface(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = cs.surfaceContainerLow
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Live Preview", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.primary)
                Spacer(Modifier.height(8.dp))
                val visibleDestinations = resolveVisibleDestinations(configState, NavRegistry.all, s.morpheEnabled)
                MockupDockRenderer(
                    destinations = visibleDestinations,
                    selectedTab = visibleDestinations.firstOrNull()?.tab ?: NavRegistry.ANDROID.tab,
                    isDark = s.isOled || s.theme == "dark" || s.theme == "dusk"
                )
            }
        }

        // Instructions card
        Surface(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            color = cs.secondaryContainer.copy(alpha = 0.5f)
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Sym.Info, null, Modifier.size(18.dp), tint = cs.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Use Move Up/Down to reorder. Core tabs cannot be reduced below 2 tabs. Dev tab is mandatory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSecondaryContainer
                )
            }
        }

        // Morphe Ecosystem Toggle
        Surface(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(18.dp),
            color = if (s.morpheEnabled) cs.primaryContainer else cs.surfaceContainerHigh
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Enable Morphe Ecosystem", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Adds Morphe Store, Studio, My Patched Apps, Queue & Updates", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
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

        // Pill list
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items, key = { _, (config, _) -> config.id }) { index, (pillConfig, dest) ->
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = cs.surfaceContainerHigh
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Move Up Button
                        IconButton(
                            onClick = {
                                vm.haptic(H.LIGHT)
                                val next = reduceNavConfig(configState, NavConfigAction.MoveUp(dest.id))
                                vm.set { it.copy(navConfig = next) }
                            },
                            enabled = index > 0,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Sym.KeyboardArrowUp, "Move up", tint = if (index > 0) cs.onSurface else cs.onSurface.copy(alpha = 0.3f))
                        }

                        // Move Down Button
                        IconButton(
                            onClick = {
                                vm.haptic(H.LIGHT)
                                val next = reduceNavConfig(configState, NavConfigAction.MoveDown(dest.id))
                                vm.set { it.copy(navConfig = next) }
                            },
                            enabled = index < items.lastIndex,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Sym.ArrowDownward, "Move down", tint = if (index < items.lastIndex) cs.onSurface else cs.onSurface.copy(alpha = 0.3f))
                        }

                        Spacer(Modifier.width(4.dp))

                        // Destination Icon
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(if (dest.group == NavGroup.MORPHE) cs.tertiaryContainer else cs.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(dest.icon, null, Modifier.size(18.dp), tint = if (dest.group == NavGroup.MORPHE) cs.onTertiaryContainer else cs.onPrimaryContainer)
                        }

                        Spacer(Modifier.width(12.dp))

                        // Destination Info
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(dest.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (dest.mandatory) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Sym.Lock, "Mandatory", Modifier.size(14.dp), tint = cs.onSurfaceVariant)
                                }
                            }
                            Text(
                                if (dest.group == NavGroup.CORE) "Core tab" else "Morphe tab",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (dest.group == NavGroup.MORPHE) cs.tertiary else cs.onSurfaceVariant
                            )
                        }

                        // Visibility Switch
                        Switch(
                            checked = pillConfig.visible,
                            onCheckedChange = {
                                vm.haptic(H.SELECTION)
                                val next = reduceNavConfig(configState, NavConfigAction.ToggleVisibility(dest.id))
                                vm.set { it.copy(navConfig = next) }
                            },
                            enabled = !dest.mandatory
                        )
                    }
                }
            }
        }
    }
}
