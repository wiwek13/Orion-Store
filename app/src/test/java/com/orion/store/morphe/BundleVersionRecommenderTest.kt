package com.orion.store.morphe

import com.orion.store.morphe.store.BundleVersionRecommender
import com.orion.store.morphe.store.MorpheCatalogPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleVersionRecommenderTest {

    private fun patch(
        bundle: String,
        name: String,
        versions: List<String>,
        universal: Boolean = false
    ) = MorpheCatalogPatch(
        name = name,
        isUniversal = universal,
        bundleName = bundle,
        compatiblePackages = if (universal) emptyList() else listOf("com.instagram.android"),
        targetVersions = versions
    )

    @Test
    fun `recommends distinct best version depending on which bundle is selected`() {
        val patches = listOf(
            patch("HushGram", "Download reel", listOf("454.0.0.54.77")),
            patch("HushGram", "Copy comment", listOf("454.0.0.54.77")),
            patch("Heval's Morphe Patches", "Hide Ads", listOf("398.0.0.48.80"))
        )

        val hushSummary = BundleVersionRecommender.recommend(
            specificPatches = patches,
            selectedPatchKeys = setOf("HushGram::Download reel")
        )
        assertEquals("454.0.0.54.77", hushSummary.bestVersion)
        assertEquals(listOf("HushGram"), hushSummary.activeBundleNames)
        assertFalse(hushSummary.hasBundleVersionConflict)

        val hevalSummary = BundleVersionRecommender.recommend(
            specificPatches = patches,
            selectedPatchKeys = setOf("Heval's Morphe Patches::Hide Ads")
        )
        assertEquals("398.0.0.48.80", hevalSummary.bestVersion)
        assertEquals(listOf("Heval's Morphe Patches"), hevalSummary.activeBundleNames)
        assertFalse(hevalSummary.hasBundleVersionConflict)

        // All candidate versions should be ordered newest-first regardless of input order
        assertEquals(listOf("454.0.0.54.77", "398.0.0.48.80"), hushSummary.allCandidateVersions)
    }

    @Test
    fun `multi-bundle selection picks shared intersection or flags conflict cleanly`() {
        val sharedPatches = listOf(
            patch("BundleA", "P1", listOf("20.07.39", "19.47.53")),
            patch("BundleB", "P2", listOf("19.47.53"))
        )
        val combinedShared = BundleVersionRecommender.recommend(
            specificPatches = sharedPatches,
            selectedPatchKeys = setOf("BundleA::P1", "BundleB::P2")
        )
        assertEquals("19.47.53", combinedShared.bestVersion)
        assertEquals(2, combinedShared.matchedPatchCount)
        assertEquals(2, combinedShared.constrainedPatchCount)
        assertFalse(combinedShared.hasBundleVersionConflict)

        val disjointPatches = listOf(
            patch("BundleA", "P1", listOf("454.0.0.54.77")),
            patch("BundleA", "P2", listOf("454.0.0.54.77")),
            patch("BundleB", "P3", listOf("398.0.0.48.80"))
        )
        val combinedDisjoint = BundleVersionRecommender.recommend(
            specificPatches = disjointPatches,
            selectedPatchKeys = setOf("BundleA::P1", "BundleA::P2", "BundleB::P3")
        )
        assertEquals("454.0.0.54.77", combinedDisjoint.bestVersion)
        assertTrue(combinedDisjoint.hasBundleVersionConflict)
        assertEquals(2, combinedDisjoint.matchedPatchCount)
        assertEquals(3, combinedDisjoint.constrainedPatchCount)
    }

    @Test
    fun `bestVersionForPatch and compatibility check match target versions`() {
        val patches = listOf(
            patch("HushGram", "Constrained", listOf("450.0.0.1.0", "454.0.0.54.77")),
            patch("HushGram", "AnyInBundle", emptyList())
        )
        val summary = BundleVersionRecommender.recommend(patches, emptySet())
        val rec = summary.perBundle["HushGram"]

        assertEquals("454.0.0.54.77", BundleVersionRecommender.bestVersionForPatch(patches[0], rec))
        assertEquals("454.0.0.54.77", BundleVersionRecommender.bestVersionForPatch(patches[1], rec))

        assertTrue(BundleVersionRecommender.isPatchCompatibleWithVersion(patches[0], "454.0.0.54.77"))
        assertFalse(BundleVersionRecommender.isPatchCompatibleWithVersion(patches[0], "455.0.0.51.72"))
        assertTrue(BundleVersionRecommender.isPatchCompatibleWithVersion(patches[1], "455.0.0.51.72"))
    }
}

