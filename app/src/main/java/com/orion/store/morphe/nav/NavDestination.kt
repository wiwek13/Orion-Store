package com.orion.store.morphe.nav

import androidx.compose.ui.graphics.vector.ImageVector
import com.orion.store.data.Tab
import com.orion.store.ui.Sym

enum class NavGroup { CORE, MORPHE }

enum class BadgeType { NONE, NUMERIC, ATTENTION }

/**
 * Universal destination model acting as the single source of truth for navigation.
 * Drives bottom dock, navigation rail, customizer UI, and router.
 */
data class NavDestination(
    val id: String,
    val tab: Tab,
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val group: NavGroup = NavGroup.CORE,
    val mandatory: Boolean = false,
    val defaultOrder: Int = 0,
    val featureFlag: String? = null,
    val badgeType: BadgeType = BadgeType.NONE
)

object NavRegistry {
    // 6 Pre-existing Orion Core destinations (Preserved 100% identically)
    val ANDROID = NavDestination(
        id = "android",
        tab = Tab.ANDROID,
        label = "Apps",
        subtitle = "apps",
        icon = Sym.Android,
        selectedIcon = Sym.AndroidFilled,
        group = NavGroup.CORE,
        mandatory = false,
        defaultOrder = 0
    )

    val PC = NavDestination(
        id = "pc",
        tab = Tab.PC,
        label = "PC",
        subtitle = "PC",
        icon = Sym.DesktopWindows,
        selectedIcon = Sym.DesktopWindowsFilled,
        group = NavGroup.CORE,
        mandatory = false,
        defaultOrder = 1
    )

    val TV = NavDestination(
        id = "tv",
        tab = Tab.TV,
        label = "TV",
        subtitle = "TV",
        icon = Sym.Tv,
        selectedIcon = Sym.TvFilled,
        group = NavGroup.CORE,
        mandatory = false,
        defaultOrder = 2
    )

    val ORBIT = NavDestination(
        id = "orbit",
        tab = Tab.ORBIT,
        label = "Orbit",
        subtitle = "Orbit",
        icon = Sym.Orbit,
        selectedIcon = Sym.OrbitFilled,
        group = NavGroup.CORE,
        mandatory = false,
        defaultOrder = 3
    )

    val MYAPPS = NavDestination(
        id = "myapps",
        tab = Tab.MYAPPS,
        label = "My Apps",
        subtitle = "my",
        icon = Sym.GridView,
        selectedIcon = Sym.GridViewFilled,
        group = NavGroup.CORE,
        mandatory = false,
        defaultOrder = 4,
        badgeType = BadgeType.NUMERIC
    )

    val ABOUT = NavDestination(
        id = "about",
        tab = Tab.ABOUT,
        label = "Dev",
        subtitle = "About Orion",
        icon = Sym.Code,
        selectedIcon = Sym.CodeFilled,
        group = NavGroup.CORE,
        mandatory = true, // Mandatory: Cannot be hidden in settings
        defaultOrder = 5
    )

    // 5 New Morphe destinations (Appended after core tabs)
    val MORPHE_STORE = NavDestination(
        id = "morphe_store",
        tab = Tab.MORPHE_STORE,
        label = "Morphe Store",
        subtitle = "Community patches & apps",
        icon = Sym.Extension,
        selectedIcon = Sym.Extension,
        group = NavGroup.MORPHE,
        mandatory = false,
        defaultOrder = 6,
        featureFlag = "morphe_enabled"
    )

    val PATCH_STUDIO = NavDestination(
        id = "patch_studio",
        tab = Tab.PATCH_STUDIO,
        label = "Patch Studio",
        subtitle = "Target APK & patch configuration",
        icon = Sym.Build,
        selectedIcon = Sym.Build,
        group = NavGroup.MORPHE,
        mandatory = false,
        defaultOrder = 7,
        featureFlag = "morphe_enabled"
    )

    val MY_PATCHED_APPS = NavDestination(
        id = "my_patched_apps",
        tab = Tab.MY_PATCHED_APPS,
        label = "My Patched Apps",
        subtitle = "Installed patched packages",
        icon = Sym.Package2,
        selectedIcon = Sym.Package2,
        group = NavGroup.MORPHE,
        mandatory = false,
        defaultOrder = 8,
        featureFlag = "morphe_enabled",
        badgeType = BadgeType.ATTENTION
    )

    val PATCH_QUEUE = NavDestination(
        id = "patch_queue",
        tab = Tab.PATCH_QUEUE,
        label = "Queue",
        subtitle = "Active builds & background jobs",
        icon = Sym.Downloading,
        selectedIcon = Sym.Downloading,
        group = NavGroup.MORPHE,
        mandatory = false,
        defaultOrder = 9,
        featureFlag = "morphe_enabled",
        badgeType = BadgeType.NUMERIC
    )

    val PATCH_UPDATES = NavDestination(
        id = "patch_updates",
        tab = Tab.PATCH_UPDATES,
        label = "Updates",
        subtitle = "Source APK, patch & engine updates",
        icon = Sym.Sync,
        selectedIcon = Sym.Sync,
        group = NavGroup.MORPHE,
        mandatory = false,
        defaultOrder = 10,
        featureFlag = "morphe_enabled",
        badgeType = BadgeType.NUMERIC
    )

    val coreDestinations: List<NavDestination> = listOf(ANDROID, PC, TV, ORBIT, MYAPPS, ABOUT)
    val morpheDestinations: List<NavDestination> = emptyList()
    val all: List<NavDestination> = coreDestinations

    fun findById(id: String): NavDestination? = all.firstOrNull { it.id == id }
    fun findByTab(tab: Tab): NavDestination? = all.firstOrNull { it.tab == tab }
}
