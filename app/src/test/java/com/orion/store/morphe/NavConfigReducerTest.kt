package com.orion.store.morphe

import com.orion.store.data.Tab
import com.orion.store.morphe.nav.*
import com.orion.store.ui.Sym
import org.junit.Assert.*
import org.junit.Test

class NavConfigReducerTest {

    @Test
    fun testCoreDestinationsPreservedUnchanged() {
        val core = NavRegistry.coreDestinations
        assertEquals(6, core.size)

        val android = core.first { it.id == "android" }
        assertEquals("Apps", android.label)
        assertEquals(Tab.ANDROID, android.tab)
        assertEquals(Sym.Android, android.icon)
        assertEquals(NavGroup.CORE, android.group)
        assertFalse(android.mandatory)

        val pc = core.first { it.id == "pc" }
        assertEquals("PC", pc.label)
        assertEquals(Tab.PC, pc.tab)
        assertEquals(Sym.DesktopWindows, pc.icon)

        val tv = core.first { it.id == "tv" }
        assertEquals("TV", tv.label)
        assertEquals(Tab.TV, tv.tab)
        assertEquals(Sym.Tv, tv.icon)

        val orbit = core.first { it.id == "orbit" }
        assertEquals("Orbit", orbit.label)
        assertEquals(Tab.ORBIT, orbit.tab)
        assertEquals(Sym.Orbit, orbit.icon)

        val myApps = core.first { it.id == "myapps" }
        assertEquals("My Apps", myApps.label)
        assertEquals(Tab.MYAPPS, myApps.tab)
        assertEquals(Sym.GridView, myApps.icon)
        assertEquals(BadgeType.NUMERIC, myApps.badgeType)

        val about = core.first { it.id == "about" }
        assertEquals("Dev", about.label)
        assertEquals(Tab.ABOUT, about.tab)
        assertEquals(Sym.Code, about.icon)
        assertTrue("About destination must be mandatory", about.mandatory)
    }

    @Test
    fun testCoreDestinationsPreservedInAll() {
        val all = NavRegistry.all
        assertEquals(6, all.size)

        // All 6 are core destinations
        assertEquals(listOf("android", "pc", "tv", "orbit", "myapps", "about"), all.map { it.id })
        assertTrue(all.all { it.group == NavGroup.CORE })
    }

    @Test
    fun testMandatoryDestinationCannotBeHidden() {
        val initial = NavConfigState.defaultState()
        assertTrue(initial.items.first { it.id == "about" }.visible)

        // Attempt toggle
        val toggled = reduceNavConfig(initial, NavConfigAction.ToggleVisibility("about"))
        assertTrue("Mandatory destination must remain visible on toggle", toggled.items.first { it.id == "about" }.visible)

        // Attempt explicit set false
        val setFalse = reduceNavConfig(initial, NavConfigAction.SetVisibility("about", false))
        assertTrue("Mandatory destination must remain visible on SetVisibility(false)", setFalse.items.first { it.id == "about" }.visible)
    }

    @Test
    fun testMinimumVisibleCountEnforced() {
        // Start with only 2 visible destinations
        val state = NavConfigState(
            schemaVersion = 1,
            items = listOf(
                NavPillConfig("android", visible = true),
                NavPillConfig("about", visible = true),
                NavPillConfig("pc", visible = false),
                NavPillConfig("morphe_store", visible = false)
            )
        )

        // Attempting to hide android should fail because count would drop below 2
        val next = reduceNavConfig(state, NavConfigAction.ToggleVisibility("android"))
        assertTrue("Should not hide destination if total visible is <= 2", next.items.first { it.id == "android" }.visible)

        val setFalse = reduceNavConfig(state, NavConfigAction.SetVisibility("android", false))
        assertTrue("Should not set destination hidden if total visible is <= 2", setFalse.items.first { it.id == "android" }.visible)
    }

    @Test
    fun testReorderingPills() {
        val initial = NavConfigState.defaultState()
        val firstId = initial.items[0].id
        val secondId = initial.items[1].id

        // Move first to second position
        val moved = reduceNavConfig(initial, NavConfigAction.Move(0, 1))
        assertEquals(secondId, moved.items[0].id)
        assertEquals(firstId, moved.items[1].id)

        // MoveDown
        val movedDown = reduceNavConfig(initial, NavConfigAction.MoveDown(firstId))
        assertEquals(firstId, movedDown.items[1].id)

        // MoveUp
        val movedUp = reduceNavConfig(movedDown, NavConfigAction.MoveUp(firstId))
        assertEquals(firstId, movedUp.items[0].id)

        // Out-of-bounds Move should be a no-op
        val oob = reduceNavConfig(initial, NavConfigAction.Move(0, 999))
        assertEquals(initial.items, oob.items)
    }

    @Test
    fun testSyncRegistryPreservesCustomOrderAndAppendsAdditions() {
        // Custom order where user swapped order and disabled some tabs
        val customState = NavConfigState(
            schemaVersion = 1,
            items = listOf(
                NavPillConfig("orbit", visible = true),
                NavPillConfig("android", visible = true),
                NavPillConfig("about", visible = true)
            )
        )

        // Sync with full registry (which contains 6 core destinations)
        val synced = reduceNavConfig(customState, NavConfigAction.SyncRegistry())

        // Top 3 must remain in custom order
        assertEquals("orbit", synced.items[0].id)
        assertEquals("android", synced.items[1].id)
        assertEquals("about", synced.items[2].id)

        // The remaining 3 core destinations must be appended after
        assertEquals(6, synced.items.size)
        val remainingIds = synced.items.drop(3).map { it.id }
        assertTrue(remainingIds.contains("pc"))
        assertTrue(remainingIds.contains("tv"))
        assertTrue(remainingIds.contains("myapps"))
    }

    @Test
    fun testResolveVisibleDestinationsRetainsCore() {
        val fullConfig = NavConfigState(items = NavRegistry.all.map { NavPillConfig(it.id, visible = true) })

        val coreOnly = resolveVisibleDestinations(fullConfig, morpheEnabled = false)
        assertEquals(6, coreOnly.size)
        assertTrue(coreOnly.none { it.group == NavGroup.MORPHE })

        val allVisible = resolveVisibleDestinations(fullConfig, morpheEnabled = true)
        assertEquals(6, allVisible.size)
    }

    @Test
    fun testUserCanIndividuallyEnableAndDisableTabs() {
        val fullConfig = NavConfigState(items = NavRegistry.all.map { NavPillConfig(it.id, visible = true) })

        // User hides pc and tv
        var state = reduceNavConfig(fullConfig, NavConfigAction.SetVisibility("pc", false))
        state = reduceNavConfig(state, NavConfigAction.SetVisibility("tv", false))

        val resolved = resolveVisibleDestinations(state, morpheEnabled = true)
        assertEquals(4, resolved.size)
        assertFalse(resolved.any { it.id == "pc" })
        assertFalse(resolved.any { it.id == "tv" })

        // Android and My Apps remain visible
        assertTrue(resolved.any { it.id == "android" })
        assertTrue(resolved.any { it.id == "myapps" })
    }
}
