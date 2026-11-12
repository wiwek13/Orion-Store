package com.orion.store.morphe.store

import com.orion.store.OrionApp
import com.orion.store.data.compareVersions
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
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Store for Morphe community repositories, patch bundles, and 3-tier update checking.
 */
object MorpheRepoStore {

    private const val REPOS_FILE = "morphe_repos.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val _repos = MutableStateFlow<List<MorpheRepo>>(emptyList())
    val repos: StateFlow<List<MorpheRepo>> = _repos.asStateFlow()

    private val _updates = MutableStateFlow<List<MorpheUpdate>>(emptyList())
    val updates: StateFlow<List<MorpheUpdate>> = _updates.asStateFlow()

    private val _updateCount = MutableStateFlow(0)
    val updateCount: StateFlow<Int> = _updateCount.asStateFlow()

    private fun reposFileOrNull(): File? = runCatching {
        File(OrionApp.instance.filesDir, REPOS_FILE)
    }.getOrNull()

    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private val writeLock = Any()

    private fun saveToDisk() {
        val snapshot = _repos.value
        ioScope.launch {
            synchronized(writeLock) {
                runCatching {
                    val file = reposFileOrNull() ?: return@runCatching
                    file.parentFile?.mkdirs()
                    val text = json.encodeToString(snapshot)
                    val atomic = android.util.AtomicFile(file)
                    val stream = atomic.startWrite()
                    try {
                        stream.write(text.toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(stream)
                    } catch (e: Exception) {
                        atomic.failWrite(stream)
                        throw e
                    }
                }
            }
        }
    }

    private fun loadFromDisk(): Boolean {
        return runCatching {
            val file = reposFileOrNull() ?: return false
            if (!file.exists() || file.length() == 0L) return false
            val decoded = json.decodeFromString<List<MorpheRepo>>(file.readText())
            if (decoded.isEmpty()) return false
            _repos.value = decoded
            true
        }.getOrDefault(false)
    }

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

        if (!loadFromDisk()) {
            _repos.value = listOf(defaultRepo, communityRepo)
        }
        refreshUpdates()
    }

    private fun recalculateUpdates() {
        _updateCount.value = _updates.value.size
    }

    fun refreshUpdates() {
        val computed = mutableListOf<MorpheUpdate>()
        val patchedApps = runCatching { PatchedAppStore.apps.value }.getOrDefault(emptyList())

        for (app in patchedApps) {
            val catalogApp = runCatching { MorpheCatalogRepository.getApp(app.packageName) }.getOrNull()
            val bundleMeta = runCatching {
                MorpheCatalogRepository.resolveBundleMetadata(app.patchBundleId)
                    ?: MorpheCatalogRepository.resolveBundleForPackage(app.packageName)
            }.getOrNull()

            if (bundleMeta != null && bundleMeta.version.isNotBlank() &&
                app.patchBundleVersion.isNotBlank() &&
                compareVersions(bundleMeta.version, app.patchBundleVersion) > 0
            ) {
                computed.add(
                    MorpheUpdate(
                        id = "bundle-${app.packageName}",
                        type = UpdateType.PATCH_BUNDLE,
                        title = "${app.appName} (${bundleMeta.name})",
                        currentVersion = app.patchBundleVersion,
                        newVersion = bundleMeta.version,
                        changelog = "Updated patch bundle available for ${app.appName}."
                    )
                )
            }

            val latestCompat = catalogApp?.compatibleVersions
                ?.filter { it.isNotBlank() }
                ?.maxWithOrNull { a, b -> compareVersions(a, b) }
            if (!latestCompat.isNullOrBlank() && compareVersions(latestCompat, app.installedVersion) > 0) {
                computed.add(
                    MorpheUpdate(
                        id = "apk-${app.packageName}",
                        type = UpdateType.SOURCE_APK,
                        title = "${app.appName} (${app.packageName})",
                        currentVersion = app.installedVersion,
                        newVersion = latestCompat,
                        changelog = "Newer compatible target APK version $latestCompat is supported by patches."
                    )
                )
            }
        }

        _updates.value = computed
        recalculateUpdates()
    }

    fun addRepo(
        name: String,
        url: String,
        description: String = "Custom user added patch repository",
        isTrusted: Boolean = false
    ) {
        val norm = url.trimEnd('/').lowercase()
        if (_repos.value.any { it.url.trimEnd('/').lowercase() == norm }) return
        val repo = MorpheRepo(
            id = UUID.randomUUID().toString(),
            name = name,
            url = url,
            description = description,
            isEnabled = true,
            isDefault = false,
            isTrusted = isTrusted
        )
        _repos.update { it + repo }
        saveToDisk()
    }

    suspend fun syncFromMetaMorpheSources(): Int {
        val added = MetaMorpheScraper.syncSourcesToRepoStore()
        refreshUpdates()
        return added
    }

    fun updateRepo(id: String, name: String, url: String) {
        _repos.update { list ->
            list.map {
                if (it.id == id) it.copy(name = name, url = url) else it
            }
        }
        saveToDisk()
    }

    fun removeRepo(id: String) {
        val target = _repos.value.firstOrNull { it.id == id }
        if (target != null) {
            // Persist removal so background scraping never restores it
            runCatching {
                com.orion.store.ui.Store.settings.update { s ->
                    s.copy(morpheRemovedRepoUrls = s.morpheRemovedRepoUrls + target.url + target.name)
                }
            }
        }
        _repos.update { list -> list.filterNot { it.id == id } }
        saveToDisk()
    }

    fun resetToDefaults() {
        runCatching {
            com.orion.store.ui.Store.settings.update { s ->
                s.copy(morpheRemovedRepoUrls = emptySet())
            }
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
        saveToDisk()
    }

    fun toggleRepo(id: String, enabled: Boolean) {
        _repos.update { list ->
            list.map { if (it.id == id) it.copy(isEnabled = enabled) else it }
        }
        saveToDisk()
    }

    fun getAllBundles(): List<PatchBundle> {
        return _repos.value.filter { it.isEnabled }.flatMap { it.bundles }
    }
}
