package com.orion.store.morphe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.orion.store.data.Settings
import com.orion.store.data.Tab
import com.orion.store.morphe.nav.NavDestination
import com.orion.store.morphe.nav.NavRegistry
import com.orion.store.ui.OrionTheme
import com.orion.store.ui.Pill
import com.orion.store.ui.Sym

/**
 * Visual Mockup renderer reproducing Orion's exact M3 Expressive floating dock.
 */
@Composable
fun MockupDockRenderer(
    destinations: List<NavDestination>,
    selectedTab: Tab = Tab.ANDROID,
    updates: Int = 0,
    queueCount: Int = 0,
    updatesCount: Int = 0,
    isDark: Boolean = false,
    widthDp: Int = 400
) {
    val settings = Settings(theme = if (isDark) "dark" else "light", isOled = false)
    OrionTheme(settings) {
        val cs = MaterialTheme.colorScheme
        val scrollState = rememberScrollState()
        val needsScroll = destinations.size >= 6

        Surface(
            Modifier.fillMaxWidth().height(160.dp),
            color = cs.surface
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .widthIn(max = widthDp.dp),
                    shape = Pill,
                    color = cs.surfaceContainerHigh,
                    shadowElevation = 8.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Row(
                            Modifier
                                .then(if (needsScroll) Modifier.horizontalScroll(scrollState) else Modifier)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            destinations.forEach { dest ->
                                val on = dest.tab == selectedTab
                                val bg = if (on) cs.secondaryContainer else Color.Transparent
                                val fg = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant

                                Row(
                                    Modifier
                                        .height(48.dp)
                                        .clip(Pill)
                                        .background(bg)
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val iconComposable = @Composable {
                                        Icon(if (on) dest.selectedIcon else dest.icon, dest.label, tint = fg, modifier = Modifier.size(22.dp))
                                    }
                                    when {
                                        dest.tab == Tab.MYAPPS && updates > 0 -> BadgedBox({ Badge { Text("$updates") } }) { iconComposable() }
                                        dest.tab == Tab.PATCH_QUEUE && queueCount > 0 -> BadgedBox({ Badge(containerColor = cs.primary) { Text("$queueCount") } }) { iconComposable() }
                                        dest.tab == Tab.PATCH_UPDATES && updatesCount > 0 -> BadgedBox({ Badge(containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer) { Text("$updatesCount") } }) { iconComposable() }
                                        else -> iconComposable()
                                    }

                                    if (on) {
                                        Text(
                                            dest.label,
                                            Modifier.padding(start = 8.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = fg,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        if (needsScroll && scrollState.canScrollForward) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .align(Alignment.CenterEnd)
                                    .width(24.dp)
                                    .background(Brush.horizontalGradient(listOf(Color.Transparent, cs.surfaceContainerHigh)))
                            )
                        }
                    }
                }
            }
        }
    }
}

// region 5.1 Mockups A & B
/** Mockup A: Orion's current actual interface (6 core pills). */
@Preview(name = "Mockup A - Orion Core Bar", widthDp = 400, heightDp = 180)
@Composable
fun Preview_MockupA_OrionCoreBar() {
    MockupDockRenderer(
        destinations = NavRegistry.coreDestinations,
        selectedTab = Tab.ANDROID
    )
}

/** Mockup B: The identical screen with all 5 Morphe pills appended (purely additive delta). */
@Preview(name = "Mockup B - 11 Pills Appended", widthDp = 400, heightDp = 180)
@Composable
fun Preview_MockupB_MorpheAppended() {
    MockupDockRenderer(
        destinations = NavRegistry.all,
        selectedTab = Tab.ANDROID
    )
}

/** Mockup B Variant: Selected state on Morphe Store. */
@Preview(name = "Mockup B - Selected Morphe Store", widthDp = 400, heightDp = 180)
@Composable
fun Preview_MockupB_SelectedState() {
    MockupDockRenderer(
        destinations = NavRegistry.all,
        selectedTab = Tab.MORPHE_STORE
    )
}

/** Mockup B Variant: Live Badges on Queue, Updates, and MyApps. */
@Preview(name = "Mockup B - Badges Present", widthDp = 400, heightDp = 180)
@Composable
fun Preview_MockupB_BadgesPresent() {
    MockupDockRenderer(
        destinations = NavRegistry.all,
        selectedTab = Tab.PATCH_QUEUE,
        updates = 3,
        queueCount = 2,
        updatesCount = 5
    )
}

/** Mockup B Variant: Narrow screen (320dp width) showing discoverable edge peek & scroll. */
@Preview(name = "Mockup B - 320dp Phone", widthDp = 320, heightDp = 180)
@Composable
fun Preview_MockupB_NarrowScreen320dp() {
    MockupDockRenderer(
        destinations = NavRegistry.all,
        selectedTab = Tab.ANDROID,
        widthDp = 320
    )
}

/** Mockup B Variant: Dark theme. */
@Preview(name = "Mockup B - Dark Mode", widthDp = 400, heightDp = 180)
@Composable
fun Preview_MockupB_DarkMode() {
    MockupDockRenderer(
        destinations = NavRegistry.all,
        selectedTab = Tab.MORPHE_STORE,
        isDark = true
    )
}

/** Mockup B Variant: 200% Font Scale Accessibility. */
@Preview(name = "Mockup B - 200% Font Scale", widthDp = 400, heightDp = 180)
@Composable
fun Preview_MockupB_200FontScale() {
    val currentDensity = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, fontScale = 2.0f)) {
        MockupDockRenderer(
            destinations = NavRegistry.all,
            selectedTab = Tab.PATCH_STUDIO
        )
    }
}

/** Mockup B Variant: Tablet / Foldable NavigationRail. */
@Preview(name = "Mockup B - Tablet Navigation Rail", widthDp = 800, heightDp = 480)
@Composable
fun Preview_MockupB_TabletRail() {
    val cs = MaterialTheme.colorScheme
    OrionTheme(Settings()) {
        Surface(Modifier.fillMaxSize(), color = cs.surface) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = cs.surfaceContainer) {
                    Spacer(Modifier.height(16.dp))
                    NavRegistry.all.forEach { dest ->
                        val on = dest.tab == Tab.MORPHE_STORE
                        NavigationRailItem(
                            selected = on,
                            onClick = {},
                            icon = { Icon(if (on) dest.selectedIcon else dest.icon, dest.label) },
                            label = { Text(dest.label) }
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                    Text("Tablet / Foldable Content Area", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
// endregion
