package com.orion.store.morphe.store

import com.orion.store.morphe.model.PatchedApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Registry of installed patched applications managed by Morphe.
 */
object PatchedAppStore {

    private val _apps = MutableStateFlow<List<PatchedApp>>(emptyList())
    val apps: StateFlow<List<PatchedApp>> = _apps.asStateFlow()

    private val _attentionCount = MutableStateFlow(0)
    val attentionCount: StateFlow<Int> = _attentionCount.asStateFlow()

    init {
        // Initial sample data for immediate visibility
        _apps.value = listOf(
            PatchedApp(
                packageName = "app.revanced.android.youtube",
                appName = "YouTube ReVanced",
                installedVersion = "19.16.39",
                targetVersion = "19.16.39",
                patchBundleId = "morphe-revanced-bundle",
                patchBundleVersion = "4.6.1",
                appliedPatches = listOf("sponsorblock", "return-youtube-dislike", "hide-shorts", "microg-support", "amoled-black"),
                needsAttention = true,
                attentionReason = "Patch bundle v4.7.0 available with critical playback fixes",
                availablePatchUpdate = "4.7.0"
            ),
            PatchedApp(
                packageName = "com.spotify.music",
                appName = "Spotify Mod",
                installedVersion = "8.9.18.512",
                targetVersion = "8.9.18.512",
                patchBundleId = "morphe-audio-bundle",
                patchBundleVersion = "1.2.0",
                appliedPatches = listOf("amoled-theme", "hide-podcasts", "clean-navbar"),
                needsAttention = false
            ),
            PatchedApp(
                packageName = "org.telegram.messenger",
                appName = "Telegram Morphe",
                installedVersion = "10.11.1",
                targetVersion = "10.11.1",
                patchBundleId = "morphe-tg-bundle",
                patchBundleVersion = "2.0.4",
                appliedPatches = listOf("anti-delete-messages", "disable-stories", "custom-accent"),
                needsAttention = true,
                attentionReason = "Source app update available: v10.12.0",
                availableAppUpdate = "10.12.0"
            )
        )
        recalculateAttention()
    }

    private fun recalculateAttention() {
        _attentionCount.value = _apps.value.count { it.needsAttention }
    }

    fun registerApp(app: PatchedApp) {
        _apps.update { list ->
            listOf(app) + list.filterNot { it.packageName == app.packageName }
        }
        recalculateAttention()
    }

    fun removeApp(packageName: String) {
        _apps.update { list -> list.filterNot { it.packageName == packageName } }
        recalculateAttention()
    }

    fun getApp(packageName: String): PatchedApp? {
        return _apps.value.firstOrNull { it.packageName == packageName }
    }
}
