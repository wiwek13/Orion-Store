package com.orion.store.morphe.store

import com.orion.store.data.compareVersions
import com.orion.store.data.isSameVersion

/**
 * Recommendation summary for a single patch bundle targeting an app.
 */
data class BundleVersionRecommendation(
    val bundleName: String,
    val recommendedVersion: String,
    val supportedVersions: List<String>,
    val matchedPatchCount: Int,
    val constrainedPatchCount: Int
)

/**
 * Combined recommendation summary across all currently selected bundles/patches for an app.
 */
data class SelectedBundlesVersionSummary(
    val bestVersion: String,
    val allCandidateVersions: List<String>,
    val activeBundleNames: List<String>,
    val matchedPatchCount: Int,
    val constrainedPatchCount: Int,
    val hasBundleVersionConflict: Boolean,
    val perBundle: Map<String, BundleVersionRecommendation>
)

/**
 * Computes deterministic, newest-first compatible APK version recommendations scoped per bundle
 * and dynamically intersected across the user's selected patch bundles.
 */
object BundleVersionRecommender {

    private fun cleanVersion(raw: String): String = raw.trim().removePrefix("v")

    internal fun compareVersionsDesc(a: String, b: String): Int {
        val cmp = compareVersions(b, a)
        if (cmp != 0) return cmp
        return b.compareTo(a, ignoreCase = true)
    }

    private fun matchesVersion(candidates: List<String>, target: String): Boolean {
        val cleanTarget = cleanVersion(target)
        return candidates.any { c ->
            val cleanC = cleanVersion(c)
            cleanC.equals(cleanTarget, ignoreCase = true) || isSameVersion(cleanC, cleanTarget)
        }
    }

    /**
     * Picks the highest-coverage (and newest on ties) version from [patches].
     * Returns empty string when no constrained patch version metadata exists.
     */
    internal fun scoreBestVersion(patches: List<MorpheCatalogPatch>, extraFallbacks: List<String> = emptyList()): Pair<String, Int> {
        val constrained = patches.filter { it.targetVersions.isNotEmpty() }
        val pool = (constrained.flatMap { it.targetVersions } + extraFallbacks)
            .map(::cleanVersion)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (pool.isEmpty()) return "" to 0

        val ranked = pool.sortedWith { v1, v2 ->
            val count1 = constrained.count { matchesVersion(it.targetVersions, v1) }
            val count2 = constrained.count { matchesVersion(it.targetVersions, v2) }
            if (count1 != count2) return@sortedWith count2.compareTo(count1)

            val bundles1 = constrained.filter { matchesVersion(it.targetVersions, v1) }
                .map { it.bundleName.trim().lowercase() }.distinct().size
            val bundles2 = constrained.filter { matchesVersion(it.targetVersions, v2) }
                .map { it.bundleName.trim().lowercase() }.distinct().size
            if (bundles1 != bundles2) return@sortedWith bundles2.compareTo(bundles1)

            compareVersionsDesc(v1, v2)
        }
        val best = ranked.first()
        val bestCount = constrained.count { matchesVersion(it.targetVersions, best) }
        return best to bestCount
    }

    fun recommend(
        specificPatches: List<MorpheCatalogPatch>,
        selectedPatchKeys: Set<String>,
        fallbackCompatibleVersions: List<String> = emptyList(),
        preferredPrimaryBundle: String = ""
    ): SelectedBundlesVersionSummary {
        val hasQualified = selectedPatchKeys.any { it.contains("::") }
        fun isPatchSelected(p: MorpheCatalogPatch): Boolean {
            val composite = "${p.bundleName}::${p.name}"
            return selectedPatchKeys.contains(composite) ||
                (!hasQualified && selectedPatchKeys.contains(p.name))
        }

        val byBundle = specificPatches.groupBy { it.bundleName.ifBlank { "Community" } }
        val perBundle = linkedMapOf<String, BundleVersionRecommendation>()

        for ((bundleName, bundlePatches) in byBundle) {
            val selectedInBundle = bundlePatches.filter(::isPatchSelected)
            val reference = selectedInBundle.ifEmpty { bundlePatches }
            val (bestForBundle, matchedCount) = scoreBestVersion(reference)
            val constrainedCount = reference.count { it.targetVersions.isNotEmpty() }
            val allBundleVers = bundlePatches.flatMap { it.targetVersions }
                .map(::cleanVersion)
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .sortedWith(::compareVersionsDesc)
            perBundle[bundleName] = BundleVersionRecommendation(
                bundleName = bundleName,
                recommendedVersion = bestForBundle,
                supportedVersions = allBundleVers,
                matchedPatchCount = matchedCount,
                constrainedPatchCount = constrainedCount
            )
        }

        val selectedSpecific = specificPatches.filter(::isPatchSelected)
        val activeBundles = if (selectedSpecific.isNotEmpty()) {
            selectedSpecific.map { it.bundleName.ifBlank { "Community" } }.distinct()
        } else {
            val fallbackBundle = preferredPrimaryBundle.takeIf { it.isNotBlank() && byBundle.containsKey(it) }
                ?: byBundle.keys.firstOrNull()
            listOfNotNull(fallbackBundle)
        }

        val activePatches = if (selectedSpecific.isNotEmpty()) {
            selectedSpecific
        } else {
            val primary = activeBundles.firstOrNull()
            if (primary != null) byBundle[primary].orEmpty() else emptyList()
        }

        val (bestVersion, bestMatchedCount) = scoreBestVersion(
            patches = activePatches,
            extraFallbacks = if (activePatches.none { it.targetVersions.isNotEmpty() }) fallbackCompatibleVersions else emptyList()
        )
        val constrainedCount = activePatches.count { it.targetVersions.isNotEmpty() }

        val allCandidateVersions = (
            specificPatches.flatMap { it.targetVersions } + fallbackCompatibleVersions
            )
            .map(::cleanVersion)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedWith(::compareVersionsDesc)

        val resolvedBest = bestVersion.ifBlank { allCandidateVersions.firstOrNull().orEmpty() }
        val hasConflict = activeBundles.size > 1 && constrainedCount > 0 && bestMatchedCount < constrainedCount

        return SelectedBundlesVersionSummary(
            bestVersion = resolvedBest,
            allCandidateVersions = allCandidateVersions,
            activeBundleNames = activeBundles,
            matchedPatchCount = bestMatchedCount,
            constrainedPatchCount = constrainedCount,
            hasBundleVersionConflict = hasConflict,
            perBundle = perBundle
        )
    }

    /**
     * Resolves the concise display version for an individual [patch], preferring its owning
     * bundle's recommended version when compatible.
     */
    fun bestVersionForPatch(
        patch: MorpheCatalogPatch,
        bundleRecommendation: BundleVersionRecommendation?
    ): String {
        val cleanTargets = patch.targetVersions.map(::cleanVersion).filter { it.isNotBlank() }
        if (cleanTargets.isNotEmpty()) {
            val bundleRec = bundleRecommendation?.recommendedVersion?.let(::cleanVersion).orEmpty()
            if (bundleRec.isNotBlank() && matchesVersion(cleanTargets, bundleRec)) {
                return bundleRec
            }
            return cleanTargets.sortedWith(::compareVersionsDesc).first()
        }
        return if (!patch.isUniversal) {
            bundleRecommendation?.recommendedVersion.orEmpty()
        } else {
            ""
        }
    }

    /**
     * Returns whether [patch] explicitly supports [chosenVersion] (or has no version constraint).
     */
    fun isPatchCompatibleWithVersion(patch: MorpheCatalogPatch, chosenVersion: String): Boolean {
        if (patch.targetVersions.isEmpty()) return true
        val clean = cleanVersion(chosenVersion)
        if (clean.isBlank() ||
            clean.equals("Recommended", ignoreCase = true) ||
            clean.equals("Latest", ignoreCase = true) ||
            clean.startsWith("Local:", ignoreCase = true)
        ) {
            return true
        }
        return matchesVersion(patch.targetVersions, clean)
    }
}
