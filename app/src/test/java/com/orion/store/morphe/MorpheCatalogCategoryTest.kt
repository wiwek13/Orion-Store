package com.orion.store.morphe

import com.orion.store.morphe.engine.MorphePatch
import com.orion.store.morphe.store.MorpheCatalogPatch
import com.orion.store.morphe.store.groupPatchesByCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorpheCatalogCategoryTest {

    @Test
    fun `groupPatchesByCategory returns empty list when no patches have category`() {
        val patches = listOf(
            MorpheCatalogPatch(name = "Hide ads", bundleName = "ReVanced"),
            MorpheCatalogPatch(name = "SponsorBlock", category = "   ", bundleName = "ReVanced")
        )
        assertTrue(groupPatchesByCategory(patches).isEmpty())
    }

    @Test
    fun `groupPatchesByCategory sorts categories alphabetically with General last`() {
        val patches = listOf(
            MorpheCatalogPatch(name = "Uncategorized tweak", category = "", bundleName = "HushGram"),
            MorpheCatalogPatch(name = "Hide stories ads", category = "Stories", bundleName = "HushGram"),
            MorpheCatalogPatch(name = "Hide feed ads", category = "Ads", bundleName = "HushGram"),
            MorpheCatalogPatch(name = "Ghost seen", category = "Ghost mode", bundleName = "HushGram"),
            MorpheCatalogPatch(name = "Hide sponsored", category = "Ads", bundleName = "HushGram")
        )

        val grouped = groupPatchesByCategory(patches)
        assertEquals(listOf("Ads", "Ghost mode", "Stories", "General"), grouped.map { it.first })
        assertEquals(listOf("Hide feed ads", "Hide sponsored"), grouped[0].second.map { it.name })
        assertEquals(listOf("Uncategorized tweak"), grouped[3].second.map { it.name })
    }

    @Test
    fun `groupPatchesByCategory trims whitespace and preserves patch order inside category`() {
        val patches = listOf(
            MorpheCatalogPatch(name = "Download Reels", category = " Downloads ", bundleName = "HushGram"),
            MorpheCatalogPatch(name = "Download Stories", category = "Downloads", bundleName = "HushGram"),
            MorpheCatalogPatch(name = "Accent color", category = "Interface", bundleName = "HushGram")
        )

        val grouped = groupPatchesByCategory(patches)
        assertEquals(2, grouped.size)
        assertEquals("Downloads", grouped[0].first)
        assertEquals(listOf("Download Reels", "Download Stories"), grouped[0].second.map { it.name })
        assertEquals("Interface", grouped[1].first)
    }

    @Test
    fun `MorphePatch preserves category field with empty default`() {
        val uncategorized = MorphePatch(
            id = "hide-ads",
            name = "Hide ads",
            description = "Removes ads",
            version = "1.0.0"
        )
        val categorized = MorphePatch(
            id = "ghost-seen",
            name = "Ghost seen",
            description = "Hides read receipts",
            version = "1.0.0",
            category = "Ghost mode"
        )
        assertEquals("", uncategorized.category)
        assertEquals("Ghost mode", categorized.category)
    }
}
