package com.orion.store.morphe.model

import com.orion.store.morphe.engine.PatchBundle
import kotlinx.serialization.Serializable

/**
 * Community or official patch repository metadata.
 */
@Serializable
data class MorpheRepo(
    val id: String,
    val name: String,
    val url: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false,
    val lastSyncTime: Long = 0L,
    val etag: String? = null,
    val bundles: List<PatchBundle> = emptyList(),
    val isTrusted: Boolean = false
)

/**
 * Update notification model for Morphe 3-tier update detector.
 */
@Serializable
data class MorpheUpdate(
    val id: String,
    val type: UpdateType,
    val title: String,
    val currentVersion: String,
    val newVersion: String,
    val changelog: String = "",
    val downloadUrl: String = "",
    val releaseDate: String = ""
)

@Serializable
enum class UpdateType {
    SOURCE_APK,
    PATCH_BUNDLE,
    PATCH_ENGINE
}
