package com.orion.store.morphe.model

import kotlinx.serialization.Serializable

/**
 * Metadata for an installed application that has been patched and is managed by Morphe.
 */
@Serializable
data class PatchedApp(
    val packageName: String,
    val appName: String,
    val installedVersion: String,
    val targetVersion: String,
    val patchBundleId: String,
    val patchBundleVersion: String,
    val appliedPatches: List<String> = emptyList(),
    val installedAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long = System.currentTimeMillis(),
    val needsAttention: Boolean = false,
    val attentionReason: String? = null,
    val availableAppUpdate: String? = null,
    val availablePatchUpdate: String? = null,
    val signatureSha256: String = ""
)
