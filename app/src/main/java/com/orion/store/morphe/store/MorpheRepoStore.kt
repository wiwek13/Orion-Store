package com.orion.store.morphe.store

import com.orion.store.morphe.engine.MorphePatch
import com.orion.store.morphe.engine.PatchBundle
import com.orion.store.morphe.engine.PatchOption
import com.orion.store.morphe.model.MorpheRepo
import com.orion.store.morphe.model.MorpheUpdate
import com.orion.store.morphe.model.UpdateType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Store for Morphe community repositories, patch bundles, and 3-tier update checking.
 */
object MorpheRepoStore {

    private val _repos = MutableStateFlow<List<MorpheRepo>>(emptyList())
    val repos: StateFlow<List<MorpheRepo>> = _repos.asStateFlow()

    private val _updates = MutableStateFlow<List<MorpheUpdate>>(emptyList())
    val updates: StateFlow<List<MorpheUpdate>> = _updates.asStateFlow()

    private val _updateCount = MutableStateFlow(0)
    val updateCount: StateFlow<Int> = _updateCount.asStateFlow()

    init {
        // Official and community repositories
        val defaultRepo = MorpheRepo(
            id = "repo-official-morphe",
            name = "Official Morphe Ecosystem",
            url = "https://api.morphe.app/v1/patches.json",
            description = "Verified and cryptographically signed patches for popular media and messaging apps.",
            isEnabled = true,
            isDefault = true,
            isTrusted = true,
            lastSyncTime = System.currentTimeMillis() - 3600_000,
            bundles = listOf(
                PatchBundle(
                    id = "morphe-revanced-bundle",
                    name = "Morphe YouTube & Music Extended",
                    description = "Ad-blocking, background playback, SponsorBlock, dislike restoration, AMOLED black theme.",
                    version = "4.7.0",
                    author = "Morphe Team & ReVanced",
                    repositoryUrl = "https://github.com/morphe/morphe-patches",
                    releaseDate = "2026-11-01",
                    signatureSha256 = "6A:B9:31:82:FF:4C:E3:D9",
                    patches = listOf(
                        MorphePatch(
                            id = "sponsorblock",
                            name = "SponsorBlock",
                            description = "Automatically skip sponsor segments, intros, outros, and subscription reminders.",
                            version = "2.1.0",
                            compatiblePackages = listOf("app.revanced.android.youtube", "com.google.android.youtube"),
                            options = listOf(
                                PatchOption("auto_skip", "Auto-skip sponsor", "Instantly skip without prompt", defaultBoolean = true),
                                PatchOption("skip_intro", "Skip Intro", "Skip title and intro sequences", defaultBoolean = true)
                            )
                        ),
                        MorphePatch(
                            id = "return-youtube-dislike",
                            name = "Return YouTube Dislike",
                            description = "Restore public dislike count using the Return YouTube Dislike API.",
                            version = "1.9.0",
                            compatiblePackages = listOf("app.revanced.android.youtube", "com.google.android.youtube")
                        ),
                        MorphePatch(
                            id = "hide-shorts",
                            name = "Hide Shorts Components",
                            description = "Remove the Shorts button from the navigation bar and feed recommendations.",
                            version = "1.4.0",
                            compatiblePackages = listOf("app.revanced.android.youtube", "com.google.android.youtube")
                        ),
                        MorphePatch(
                            id = "microg-support",
                            name = "GmsCore / MicroG Support",
                            description = "Allows Google account login on non-rooted devices using microG.",
                            version = "3.2.0",
                            compatiblePackages = listOf("app.revanced.android.youtube", "com.google.android.youtube")
                        ),
                        MorphePatch(
                            id = "amoled-black",
                            name = "Pure AMOLED Black Theme",
                            description = "Replaces dark grey surfaces with #000000 pure black to conserve battery on OLED screens.",
                            version = "1.0.0",
                            isUniversal = true
                        )
                    )
                ),
                PatchBundle(
                    id = "morphe-audio-bundle",
                    name = "Audio Streams Enhancer",
                    description = "Enhanced UI, tablet layout unlock, podcast remover, clean library view.",
                    version = "1.2.5",
                    author = "AudioMods",
                    releaseDate = "2026-10-28",
                    patches = listOf(
                        MorphePatch(
                            id = "amoled-theme",
                            name = "AMOLED Pitch Black Theme",
                            description = "Forces pure black #000000 backgrounds across all views.",
                            version = "1.2.0",
                            compatiblePackages = listOf("com.spotify.music")
                        ),
                        MorphePatch(
                            id = "hide-podcasts",
                            name = "Hide Podcasts & Audiobooks",
                            description = "Hides podcast carousels and recommendations from the home feed.",
                            version = "1.1.0",
                            compatiblePackages = listOf("com.spotify.music")
                        )
                    )
                ),
                PatchBundle(
                    id = "morphe-tg-bundle",
                    name = "Telegram Morphe Mod",
                    description = "Anti-delete messages, invisible mode, disabled stories.",
                    version = "2.1.0",
                    author = "TGPatches",
                    releaseDate = "2026-11-04",
                    patches = listOf(
                        MorphePatch(
                            id = "anti-delete-messages",
                            name = "Anti-Delete Messages",
                            description = "Keep deleted messages visible with a strike-through indicator.",
                            version = "2.0.0",
                            compatiblePackages = listOf("org.telegram.messenger")
                        ),
                        MorphePatch(
                            id = "disable-stories",
                            name = "Disable Stories Bar",
                            description = "Hides the stories carousel from the chat list header.",
                            version = "1.0.0",
                            compatiblePackages = listOf("org.telegram.messenger")
                        )
                    )
                )
            )
        )

        val communityRepo = MorpheRepo(
            id = "repo-community-tools",
            name = "Community Open Patches",
            url = "https://raw.githubusercontent.com/morphe-community/repo/main/index.json",
            description = "Community submitted patches for utility and open-source applications.",
            isEnabled = true,
            isDefault = false,
            isTrusted = false,
            bundles = emptyList()
        )

        _repos.value = listOf(defaultRepo, communityRepo)

        // Initial 3-tier updates
        _updates.value = listOf(
            MorpheUpdate(
                id = "upd-1",
                type = UpdateType.PATCH_BUNDLE,
                title = "Morphe YouTube Extended (v4.7.0)",
                currentVersion = "4.6.1",
                newVersion = "4.7.0",
                changelog = "Fixes buffer stutters and updates SponsorBlock API endpoints.",
                releaseDate = "2026-11-01"
            ),
            MorpheUpdate(
                id = "upd-2",
                type = UpdateType.SOURCE_APK,
                title = "Telegram Messenger APK (v10.12.0)",
                currentVersion = "10.11.1",
                newVersion = "10.12.0",
                changelog = "Upstream Telegram update with new mini-app APIs.",
                releaseDate = "2026-11-07"
            ),
            MorpheUpdate(
                id = "upd-3",
                type = UpdateType.PATCH_ENGINE,
                title = "Morphe Patch Core Engine (v2.2.0)",
                currentVersion = "2.1.0",
                newVersion = "2.2.0",
                changelog = "Added support for Android 16 (API 36) DEX alignment and faster bytecode verification.",
                releaseDate = "2026-11-08"
            )
        )
        recalculateUpdates()
    }

    private fun recalculateUpdates() {
        _updateCount.value = _updates.value.size
    }

    fun addRepo(name: String, url: String) {
        val repo = MorpheRepo(
            id = UUID.randomUUID().toString(),
            name = name,
            url = url,
            description = "Custom user added patch repository",
            isEnabled = true,
            isDefault = false,
            isTrusted = false
        )
        _repos.update { it + repo }
    }

    fun updateRepo(id: String, name: String, url: String) {
        _repos.update { list ->
            list.map {
                if (it.id == id) it.copy(name = name, url = url) else it
            }
        }
    }

    fun removeRepo(id: String) {
        val target = _repos.value.firstOrNull { it.id == id }
        if (target != null) {
            // Persist removal so background scraping never restores it
            com.orion.store.ui.Store.settings.update { s ->
                s.copy(morpheRemovedRepoUrls = s.morpheRemovedRepoUrls + target.url + target.name)
            }
        }
        _repos.update { list -> list.filterNot { it.id == id } }
    }

    fun resetToDefaults() {
        com.orion.store.ui.Store.settings.update { s ->
            s.copy(morpheRemovedRepoUrls = emptySet())
        }
        // Restore default and community repo
        val defaultRepo = MorpheRepo(
            id = "repo-official-morphe",
            name = "Official Morphe Ecosystem",
            url = "https://api.morphe.app/v1/patches.json",
            description = "Verified and cryptographically signed patches for popular media and messaging apps.",
            isEnabled = true,
            isDefault = true,
            isTrusted = true,
            lastSyncTime = System.currentTimeMillis() - 3600_000
        )
        val communityRepo = MorpheRepo(
            id = "repo-community-tools",
            name = "Community Open Patches",
            url = "https://raw.githubusercontent.com/morphe-community/repo/main/index.json",
            description = "Community submitted patches for utility and open-source applications.",
            isEnabled = true,
            isDefault = false,
            isTrusted = false
        )
        _repos.value = listOf(defaultRepo, communityRepo)
    }

    fun toggleRepo(id: String, enabled: Boolean) {
        _repos.update { list ->
            list.map { if (it.id == id) it.copy(isEnabled = enabled) else it }
        }
    }

    fun getAllBundles(): List<PatchBundle> {
        return _repos.value.filter { it.isEnabled }.flatMap { it.bundles }
    }
}
