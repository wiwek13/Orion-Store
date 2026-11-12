package com.orion.store.morphe.store

import com.orion.store.OrionApp
import com.orion.store.data.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class MorpheCatalogApp(
    val packageName: String,
    val name: String,
    val category: String = "Tools & Utilities",
    val iconUrl: String = "",
    val description: String = "",
    val installs: Long = 0,
    val compatibleVersions: List<String> = emptyList(),
    val patchCount: Int = 0,
    val bundleCount: Int = 0,
    val bundleNames: List<String> = emptyList(),
    val firstSeen: Long = 0,
    val updatedAt: Long = 0,
    val featuredRank: Int = Int.MAX_VALUE
)

@Serializable
data class MorpheCatalogPatch(
    val name: String,
    val description: String = "",
    val category: String = "",
    val isDefault: Boolean = true,
    val isUniversal: Boolean = false,
    val bundleName: String = "",
    val bundleAuthor: String = "",
    val compatiblePackages: List<String> = emptyList(),
    val targetVersions: List<String> = emptyList(),
    val bundleRepo: String = "",
    val bundleDownloadUrl: String = ""
)

@Serializable
data class BundleMetadata(
    val name: String,
    val repo: String = "",
    val repositoryUrl: String = "",
    val downloadUrl: String = "",
    val author: String = "",
    val version: String = "",
    val updatedAt: Long = 0L,
    val isPrerelease: Boolean = false
)

internal fun packageNameFallbackTitle(pkg: String): String {
    val clean = pkg.trim().lowercase()
    return when {
        clean.contains("instagram") -> "Instagram"
        clean.contains("youtube.music") -> "YouTube Music"
        clean.contains("youtube") -> "YouTube"
        clean.contains("twitter") || clean.contains("x.android") -> "Twitter / X"
        clean.contains("reddit") -> "Reddit"
        clean.contains("spotify") -> "Spotify"
        clean.contains("tiktok") || clean.contains("trill") || clean.contains("musically") -> "TikTok"
        clean.contains("facebook.katana") -> "Facebook"
        clean.contains("facebook.orca") -> "Messenger"
        clean.contains("telegram") -> "Telegram"
        else -> {
            val generic = setOf("android", "app", "mobile", "client", "lite", "free", "pro", "main", "frontpage", "release")
            val segs = pkg.split('.').filter { it.isNotBlank() }
            val chosen = segs.lastOrNull { it.lowercase() !in generic && it.length > 2 }
                ?: segs.lastOrNull { it.isNotBlank() }
                ?: pkg
            chosen.replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * Groups patches by [MorpheCatalogPatch.category] when at least one patch defines a category.
 * Returns an empty list if none of the patches specify a category (allowing flat rendering).
 */
internal fun groupPatchesByCategory(
    patches: List<MorpheCatalogPatch>
): List<Pair<String, List<MorpheCatalogPatch>>> {
    if (patches.none { it.category.isNotBlank() }) return emptyList()
    return patches
        .groupBy { it.category.trim().ifBlank { "General" } }
        .entries
        .sortedWith(
            compareBy<Map.Entry<String, List<MorpheCatalogPatch>>> { it.key == "General" }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key }
        )
        .map { it.key to it.value }
}

/**
 * Background repository and cache engine for https://morphe-patches.software.
 * Ingests 1,600+ real community applications and 480+ patch bundles with offline resilience.
 */
object MorpheCatalogRepository {

    private const val CATALOG_URL = "https://morphe-patches.software/data/catalog.json"
    private const val CACHE_FILE = "morphe_catalog.json"
    private const val CACHE_MAX_AGE_MS = 6 * 3600 * 1000L // 6 hours

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _apps = MutableStateFlow<List<MorpheCatalogApp>>(emptyList())
    val apps: StateFlow<List<MorpheCatalogApp>> = _apps.asStateFlow()

    private val _categories = MutableStateFlow<List<String>>(listOf("All"))
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private val _universalPatches = MutableStateFlow<List<MorpheCatalogPatch>>(emptyList())
    val universalPatches: StateFlow<List<MorpheCatalogPatch>> = _universalPatches.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSynced = MutableStateFlow(0L)
    val lastSynced: StateFlow<Long> = _lastSynced.asStateFlow()

    // Fast lookup cache: packageName -> (App-specific patches, Universal patches)
    private val appPatchesCache = HashMap<String, Pair<List<MorpheCatalogPatch>, List<MorpheCatalogPatch>>>()
    private val bundleMetadataMap = ConcurrentHashMap<String, BundleMetadata>()

    fun resolveBundleMetadata(bundleName: String): BundleMetadata? {
        if (bundleName.isBlank()) return null
        val key = bundleName.trim().lowercase()
        return bundleMetadataMap[key] ?: bundleMetadataMap.values.firstOrNull {
            it.repo.equals(bundleName, ignoreCase = true) ||
                it.name.equals(bundleName, ignoreCase = true)
        }
    }

    fun resolveBundleForPackage(packageName: String): BundleMetadata? {
        val (specifics, _) = getPatchesForApp(packageName)
        val topBundle = specifics.firstOrNull()?.bundleName.orEmpty()
        if (topBundle.isNotEmpty()) {
            resolveBundleMetadata(topBundle)?.let { return it }
        }
        val app = getApp(packageName) ?: return null
        for (b in app.bundleNames) {
            resolveBundleMetadata(b)?.let { return it }
        }
        return null
    }

    private val cacheFile: File
        get() = File(OrionApp.instance.filesDir, CACHE_FILE)

    init {
        // Load cached file on startup, then trigger background sync and periodic sync loop
        scope.launch {
            loadFromCache()
            sync(force = false)
            while (true) {
                kotlinx.coroutines.delay(4 * 3600 * 1000L)
                sync(force = true)
            }
        }
    }

    private suspend fun loadFromCache(): Boolean = withContext(Dispatchers.IO) {
        val file = cacheFile
        if (!file.exists() || file.length() == 0L) return@withContext false
        try {
            val text = file.readText()
            parseCatalogJson(text)
            _lastSynced.value = file.lastModified()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun sync(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val file = cacheFile
        val now = System.currentTimeMillis()
        if (!force && file.exists() && (now - file.lastModified() < CACHE_MAX_AGE_MS) && _apps.value.isNotEmpty()) {
            return@withContext true
        }

        _isSyncing.value = true
        var synced = false
        try {
            val body = Net.text(CATALOG_URL, c = Net.browser)
            if (body.isNotBlank() && body.startsWith("{")) {
                file.writeText(body)
                parseCatalogJson(body)
                _lastSynced.value = System.currentTimeMillis()
                synced = true
            }

            // Scrape & merge MétaMorphe (https://metamorphe.vercel.app/#sources)
            try {
                MetaMorpheScraper.syncSourcesToRepoStore()
                val metaBundles = MetaMorpheScraper.scrapeRegistryBundles()
                if (metaBundles.isNotEmpty()) {
                    mergeMetaMorpheRegistry(metaBundles)
                }
                val allBundles = MetaMorpheScraper.scrapeAllBundles()
                if (allBundles.isNotEmpty()) {
                    mergeMetaMorpheBundles(allBundles)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _isSyncing.value = false
        }
        return@withContext synced
    }

    private fun parseCatalogJson(rawJson: String) {
        try {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val storeObj = root["store"]?.jsonObject ?: return
            val bundlesArr = root["bundles"]?.jsonArray ?: JsonArray(emptyList())
            val compsArr = root["compatibilities"]?.jsonArray ?: JsonArray(emptyList())

            // 1. Parse compatibilities lookup
            // Each index maps to a Map<packageName, List<version>>
            val parsedComps = ArrayList<Map<String, List<String>>>()
            for (compElement in compsArr) {
                val pkgMap = mutableMapOf<String, List<String>>()
                when (compElement) {
                    is JsonArray -> {
                        for (item in compElement) {
                            if (item is JsonObject) {
                                val pkg = item["packageName"]?.jsonPrimitive?.contentOrNull
                                val targets = item["targets"]?.jsonArray
                                val versions = targets?.mapNotNull {
                                    (it as? JsonObject)?.get("version")?.jsonPrimitive?.contentOrNull
                                } ?: emptyList()
                                if (pkg != null) pkgMap[pkg] = versions
                            }
                        }
                    }
                    is JsonObject -> {
                        for ((pkg, vers) in compElement) {
                            val list = (vers as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                            pkgMap[pkg] = list
                        }
                    }
                    else -> {}
                }
                parsedComps.add(pkgMap)
            }

            // 2. Parse bundles and patches
            val appPatchesMap = mutableMapOf<String, MutableList<MorpheCatalogPatch>>()
            val appBundlesMap = mutableMapOf<String, MutableSet<String>>()
            val appVersionsMap = mutableMapOf<String, MutableSet<String>>()
            val appUpdatesMap = mutableMapOf<String, Long>()
            val universalList = mutableListOf<MorpheCatalogPatch>()
            val seenUniversal = mutableSetOf<String>()
            val removedUrls = com.orion.store.ui.Store.settings.value.morpheRemovedRepoUrls
            val featuredArr = root["featured"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val featuredMap = featuredArr.mapIndexed { idx, pkg -> pkg to idx }.toMap()

            for (bundleElement in bundlesArr) {
                val bObj = bundleElement as? JsonObject ?: continue
                val bundleName = bObj["name"]?.jsonPrimitive?.contentOrNull ?: "Morphe Bundle"
                val bundleAuthor = bObj["author"]?.jsonPrimitive?.contentOrNull ?: ""
                val bundleRepo = bObj["repo"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val bundleUrl = bObj["repositoryUrl"]?.jsonPrimitive?.contentOrNull
                    ?: bObj["url"]?.jsonPrimitive?.contentOrNull
                    ?: if (bundleRepo.isNotEmpty()) {
                        if (bundleRepo.startsWith("http")) bundleRepo else "https://github.com/$bundleRepo"
                    } else ""
                val bundleDownloadUrl = bObj["downloadUrl"]?.jsonPrimitive?.contentOrNull
                    ?: bObj["mppUrl"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val bundleVersion = bObj["version"]?.jsonPrimitive?.contentOrNull ?: ""
                val isPrerelease = bObj["isPrerelease"]?.jsonPrimitive?.booleanOrNull ?: false
                if (bundleUrl in removedUrls || bundleRepo in removedUrls || bundleName in removedUrls || bundleAuthor in removedUrls) {
                    continue
                }

                val targetApps = bObj["targetApps"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val bUpdated = bObj["updatedAt"]?.jsonPrimitive?.longOrNull ?: bObj["firstSeen"]?.jsonPrimitive?.longOrNull ?: 0L
                bundleMetadataMap[bundleName.trim().lowercase()] = BundleMetadata(
                    name = bundleName,
                    repo = bundleRepo,
                    repositoryUrl = bundleUrl,
                    downloadUrl = bundleDownloadUrl,
                    author = bundleAuthor,
                    version = bundleVersion,
                    updatedAt = bUpdated,
                    isPrerelease = isPrerelease
                )
                for (t in targetApps) {
                    val prev = appUpdatesMap[t] ?: 0L
                    if (bUpdated > prev) appUpdatesMap[t] = bUpdated
                }
                (bObj["appUpdates"] as? JsonObject)?.forEach { (pkg, tsElem) ->
                    val ts = tsElem.jsonPrimitive.longOrNull ?: 0L
                    if (ts > (appUpdatesMap[pkg] ?: 0L)) appUpdatesMap[pkg] = ts
                }
                val patchesArr = bObj["patches"]?.jsonArray ?: continue

                for (appPkg in targetApps) {
                    appBundlesMap.getOrPut(appPkg) { mutableSetOf() }.add(bundleName)
                }

                for (pElement in patchesArr) {
                    val pObj = pElement as? JsonObject ?: continue
                    val patchName = pObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val patchDesc = pObj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    val patchCategory = pObj["category"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                    val isDefault = pObj["default"]?.jsonPrimitive?.booleanOrNull ?: false
                    val cpk = pObj["compatiblePackagesKey"]?.jsonPrimitive?.intOrNull
                        ?: pObj["compatibilityKey"]?.jsonPrimitive?.intOrNull

                    val assignedPkgs = mutableSetOf<String>()
                    val assignedVers = mutableSetOf<String>()

                    if (cpk != null && cpk in parsedComps.indices) {
                        val compPkgs = parsedComps[cpk]
                        for ((pkg, vers) in compPkgs) {
                            assignedPkgs.add(pkg)
                            assignedVers.addAll(vers)
                            appVersionsMap.getOrPut(pkg) { mutableSetOf() }.addAll(vers)
                        }
                    }

                    // Only assign targetApps if the bundle targets exactly one single app and compatibility table is empty
                    if (assignedPkgs.isEmpty() && targetApps.size == 1) {
                        assignedPkgs.add(targetApps.first())
                    }

                    if (assignedPkgs.isEmpty()) {
                        // Truly universal patch (deduplicate per bundle+name, not globally across all bundles)
                        if (seenUniversal.add("${bundleName}::${patchName}".lowercase())) {
                            universalList.add(
                                MorpheCatalogPatch(
                                    name = patchName,
                                    description = patchDesc,
                                    category = patchCategory,
                                    isDefault = isDefault,
                                    isUniversal = true,
                                    bundleName = bundleName,
                                    bundleAuthor = bundleAuthor,
                                    bundleRepo = bundleRepo,
                                    bundleDownloadUrl = bundleDownloadUrl
                                )
                            )
                        }
                    } else {
                        // App-specific patch
                        for (pkg in assignedPkgs) {
                            if (assignedVers.isNotEmpty()) {
                                appVersionsMap.getOrPut(pkg) { mutableSetOf() }.addAll(assignedVers)
                            }
                            appBundlesMap.getOrPut(pkg) { mutableSetOf() }.add(bundleName)
                            val patch = MorpheCatalogPatch(
                                name = patchName,
                                description = patchDesc,
                                category = patchCategory,
                                isDefault = isDefault,
                                isUniversal = false,
                                bundleName = bundleName,
                                bundleAuthor = bundleAuthor,
                                compatiblePackages = listOf(pkg),
                                targetVersions = assignedVers.toList(),
                                bundleRepo = bundleRepo,
                                bundleDownloadUrl = bundleDownloadUrl
                            )
                            val patchList = appPatchesMap.getOrPut(pkg) { mutableListOf() }
                            val existingIdx = patchList.indexOfFirst {
                                it.bundleName.equals(bundleName, ignoreCase = true) && it.name.equals(patchName, ignoreCase = true)
                            }
                            if (existingIdx >= 0) {
                                val existing = patchList[existingIdx]
                                val mergedVers = (existing.targetVersions + assignedVers).distinct()
                                patchList[existingIdx] = existing.copy(
                                    category = existing.category.ifBlank { patchCategory },
                                    targetVersions = mergedVers
                                )
                            } else {
                                patchList.add(patch)
                            }
                        }
                    }
                }
            }

            // 3. Parse apps from store
            val appsList = ArrayList<MorpheCatalogApp>(storeObj.size)
            val catsSet = mutableSetOf<String>()

            for ((pkg, appElem) in storeObj) {
                val appObj = appElem as? JsonObject ?: continue
                val name = appObj["name"]?.jsonPrimitive?.contentOrNull ?: pkg
                val cat = appObj["category"]?.jsonPrimitive?.contentOrNull?.ifBlank { "Tools & Utilities" } ?: "Tools & Utilities"
                val iconUrl = appObj["iconUrl"]?.jsonPrimitive?.contentOrNull ?: ""
                val desc = appObj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                val installs = appObj["installs"]?.jsonPrimitive?.longOrNull ?: 0L
                val firstSeen = appObj["firstSeen"]?.jsonPrimitive?.longOrNull ?: 0L
                val updatedAt = maxOf(appUpdatesMap[pkg] ?: 0L, firstSeen)
                val featuredRank = featuredMap[pkg] ?: Int.MAX_VALUE

                catsSet.add(cat)

                val specificPatches = appPatchesMap[pkg] ?: emptyList()
                val versions = (appVersionsMap[pkg]?.toList() ?: emptyList())
                    .sortedWith { a, b -> com.orion.store.data.compareVersions(b, a) }
                val bundles = appBundlesMap[pkg]?.toList() ?: emptyList()

                appsList.add(
                    MorpheCatalogApp(
                        packageName = pkg,
                        name = name,
                        category = cat,
                        iconUrl = iconUrl,
                        description = desc,
                        installs = installs,
                        compatibleVersions = versions,
                        patchCount = specificPatches.size,
                        bundleCount = bundles.size,
                        bundleNames = bundles,
                        firstSeen = firstSeen,
                        updatedAt = updatedAt,
                        featuredRank = featuredRank
                    )
                )
            }

            // Sort apps: apps with specific patches first, then by installs
            appsList.sortWith(compareByDescending<MorpheCatalogApp> { it.patchCount > 0 }.thenByDescending { it.installs })

            val sortedCats = listOf("All") + catsSet.sorted()

            synchronized(appPatchesCache) {
                appPatchesCache.clear()
                for ((pkg, patches) in appPatchesMap) {
                    appPatchesCache[pkg] = patches to universalList
                }
            }

            publishApps(appsList)
            _categories.value = sortedCats
            _universalPatches.value = universalList
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val appByPackageMap = java.util.concurrent.ConcurrentHashMap<String, MorpheCatalogApp>()

    private fun publishApps(appsList: List<MorpheCatalogApp>) {
        val nextMap = HashMap<String, MorpheCatalogApp>(appsList.size)
        for (app in appsList) {
            nextMap[app.packageName] = app
        }
        appByPackageMap.clear()
        appByPackageMap.putAll(nextMap)
        _apps.value = appsList
    }

    fun getPatchesForApp(pkg: String): Pair<List<MorpheCatalogPatch>, List<MorpheCatalogPatch>> {
        val (specifics, universals) = synchronized(appPatchesCache) {
            appPatchesCache[pkg] ?: (emptyList<MorpheCatalogPatch>() to _universalPatches.value)
        }
        val dedupSpecifics = specifics.distinctBy { "${it.bundleName}::${it.name.trim().lowercase()}" }
        val specificNames = dedupSpecifics.map { it.name.trim().lowercase() }.toSet()
        val dedupUniversals = universals
            .distinctBy { it.name.trim().lowercase() }
            .filter { it.name.trim().lowercase() !in specificNames }
        return dedupSpecifics to dedupUniversals
    }

    fun getApp(pkg: String): MorpheCatalogApp? {
        return appByPackageMap[pkg] ?: _apps.value.firstOrNull { it.packageName == pkg }
    }

    private fun mergeMetaMorpheRegistry(metaBundles: List<MetaMorpheRegistryBundle>) {
        val currentApps = _apps.value.toMutableList()
        val appIndexMap = currentApps.mapIndexed { idx, app -> app.packageName to idx }.toMap()
        val removed = com.orion.store.ui.Store.settings.value.morpheRemovedRepoUrls

        for (b in metaBundles) {
            if (b.sourceUrl in removed || b.id in removed) continue
            for (pkg in b.packages) {
                val idx = appIndexMap[pkg]
                if (idx != null) {
                    val app = currentApps[idx]
                    val updatedBundles = if (!app.bundleNames.contains(b.id)) app.bundleNames + b.id else app.bundleNames
                    currentApps[idx] = app.copy(
                        patchCount = maxOf(app.patchCount, b.patchCount),
                        bundleCount = updatedBundles.size,
                        bundleNames = updatedBundles
                    )
                }
            }
        }
        publishApps(currentApps)
    }

    private fun mergeMetaMorpheBundles(bundles: List<com.orion.store.morphe.engine.PatchBundle>) {
        val currentApps = _apps.value.toMutableList()
        val appIndexMap = mutableMapOf<String, Int>()
        currentApps.forEachIndexed { index, app -> appIndexMap[app.packageName] = index }
        val removed = com.orion.store.ui.Store.settings.value.morpheRemovedRepoUrls

        val newAppPatches = mutableMapOf<String, MutableList<MorpheCatalogPatch>>()
        val newAppVersions = mutableMapOf<String, MutableSet<String>>()
        val newAppBundles = mutableMapOf<String, MutableSet<String>>()

        for (bundle in bundles) {
            if (bundle.repositoryUrl in removed || bundle.name in removed || bundle.id in removed) continue

            val repoSlug = when {
                bundle.repositoryUrl.contains("github.com/", ignoreCase = true) ->
                    bundle.repositoryUrl.substringAfter("github.com/").trim('/').substringBefore("/releases")
                bundle.id.contains("/") -> bundle.id
                else -> ""
            }
            if (repoSlug.isNotBlank() || bundle.repositoryUrl.isNotBlank()) {
                val metaKey = bundle.name.trim().lowercase()
                bundleMetadataMap.putIfAbsent(
                    metaKey,
                    BundleMetadata(
                        name = bundle.name,
                        repo = repoSlug,
                        repositoryUrl = bundle.repositoryUrl,
                        author = bundle.author,
                        version = bundle.version,
                        updatedAt = bundle.releaseDate.toLongOrNull() ?: 0L
                    )
                )
            }

            for (patch in bundle.patches) {
                if (patch.isUniversal || patch.compatiblePackages.isEmpty()) {
                    // Universal patch
                    val isDup = _universalPatches.value.any { it.name.equals(patch.name, ignoreCase = true) }
                    if (!isDup) {
                        _universalPatches.value = _universalPatches.value + MorpheCatalogPatch(
                            name = patch.name,
                            description = patch.description,
                            category = patch.category,
                            isDefault = patch.enabledByDefault,
                            isUniversal = true,
                            bundleName = bundle.name,
                            bundleAuthor = bundle.author,
                            bundleRepo = repoSlug
                        )
                    }
                } else {
                    for (pkg in patch.compatiblePackages) {
                        newAppBundles.getOrPut(pkg) { mutableSetOf() }.add(bundle.name)
                        if (patch.compatibleVersions.isNotEmpty()) {
                            newAppVersions.getOrPut(pkg) { mutableSetOf() }.addAll(patch.compatibleVersions)
                        }

                        val catalogPatch = MorpheCatalogPatch(
                            name = patch.name,
                            description = patch.description,
                            category = patch.category,
                            isDefault = patch.enabledByDefault,
                            isUniversal = false,
                            bundleName = bundle.name,
                            bundleAuthor = bundle.author,
                            compatiblePackages = listOf(pkg),
                            targetVersions = patch.compatibleVersions,
                            bundleRepo = repoSlug
                        )
                        newAppPatches.getOrPut(pkg) { mutableListOf() }.add(catalogPatch)
                    }
                }
            }
        }

        // Update patches cache with strict name deduplication
        synchronized(appPatchesCache) {
            for ((pkg, patches) in newAppPatches) {
                val existing = appPatchesCache[pkg]
                val existingPatches = existing?.first ?: emptyList()
                val seenKeys = existingPatches.map { "${it.bundleName}::${it.name.trim().lowercase()}" }.toMutableSet()
                val mergedList = existingPatches.toMutableList()

                for (p in patches) {
                    val key = "${p.bundleName}::${p.name.trim().lowercase()}"
                    if (seenKeys.add(key)) {
                        mergedList.add(p)
                    }
                }
                appPatchesCache[pkg] = mergedList to _universalPatches.value
            }
        }

        // Merge or create apps keyed strictly by packageName
        for ((pkg, bundlesSet) in newAppBundles) {
            val idx = appIndexMap[pkg]
            val specificPatches = synchronized(appPatchesCache) { appPatchesCache[pkg]?.first ?: emptyList() }
            val vers = newAppVersions[pkg]?.toList() ?: emptyList()

            if (idx != null) {
                val app = currentApps[idx]
                val allBundles = (app.bundleNames.toSet() + bundlesSet).toList()
                val allVers = (app.compatibleVersions.toSet() + vers)
                    .toList()
                    .sortedWith { a, b -> com.orion.store.data.compareVersions(b, a) }
                currentApps[idx] = app.copy(
                    patchCount = specificPatches.size,
                    bundleCount = allBundles.size,
                    bundleNames = allBundles,
                    compatibleVersions = allVers
                )
            } else {
                val simpleName = packageNameFallbackTitle(pkg)
                val sortedVers = vers.sortedWith { a, b -> com.orion.store.data.compareVersions(b, a) }

                val newApp = MorpheCatalogApp(
                    packageName = pkg,
                    name = simpleName,
                    category = "Social & Media",
                    iconUrl = "https://icon.horse/icon/$pkg",
                    description = "Morphe-compatible app with ${specificPatches.size} patches across ${bundlesSet.size} bundles",
                    compatibleVersions = sortedVers,
                    patchCount = specificPatches.size,
                    bundleCount = bundlesSet.size,
                    bundleNames = bundlesSet.toList(),
                    firstSeen = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                appIndexMap[pkg] = currentApps.size
                currentApps.add(newApp)
            }
        }

        // Sort apps: apps with specific patches first, then by installs
        currentApps.sortWith(compareByDescending<MorpheCatalogApp> { it.patchCount > 0 }.thenByDescending { it.installs })
        publishApps(currentApps)
    }
}
