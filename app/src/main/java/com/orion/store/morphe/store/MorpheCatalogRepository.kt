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
    val isDefault: Boolean = true,
    val isUniversal: Boolean = false,
    val bundleName: String = "",
    val bundleAuthor: String = "",
    val compatiblePackages: List<String> = emptyList(),
    val targetVersions: List<String> = emptyList()
)

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
                val bundleUrl = bObj["repositoryUrl"]?.jsonPrimitive?.contentOrNull ?: ""
                if (bundleUrl in removedUrls || bundleName in removedUrls || bundleAuthor in removedUrls) {
                    continue
                }

                val targetApps = bObj["targetApps"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val bUpdated = bObj["updatedAt"]?.jsonPrimitive?.longOrNull ?: bObj["firstSeen"]?.jsonPrimitive?.longOrNull ?: 0L
                for (t in targetApps) {
                    val prev = appUpdatesMap[t] ?: 0L
                    if (bUpdated > prev) appUpdatesMap[t] = bUpdated
                }
                val patchesArr = bObj["patches"]?.jsonArray ?: continue

                for (appPkg in targetApps) {
                    appBundlesMap.getOrPut(appPkg) { mutableSetOf() }.add(bundleName)
                }

                for (pElement in patchesArr) {
                    val pObj = pElement as? JsonObject ?: continue
                    val patchName = pObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val patchDesc = pObj["description"]?.jsonPrimitive?.contentOrNull ?: ""
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
                        // Truly universal patch
                        if (seenUniversal.add(patchName.lowercase())) {
                            universalList.add(
                                MorpheCatalogPatch(
                                    name = patchName,
                                    description = patchDesc,
                                    isDefault = isDefault,
                                    isUniversal = true,
                                    bundleName = bundleName,
                                    bundleAuthor = bundleAuthor
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
                                isDefault = isDefault,
                                isUniversal = false,
                                bundleName = bundleName,
                                bundleAuthor = bundleAuthor,
                                compatiblePackages = listOf(pkg),
                                targetVersions = assignedVers.toList()
                            )
                            val patchList = appPatchesMap.getOrPut(pkg) { mutableListOf() }
                            val existingIdx = patchList.indexOfFirst { it.name.equals(patchName, ignoreCase = true) }
                            if (existingIdx >= 0) {
                                val existing = patchList[existingIdx]
                                val mergedVers = (existing.targetVersions + assignedVers).distinct()
                                patchList[existingIdx] = existing.copy(targetVersions = mergedVers)
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
                val versions = appVersionsMap[pkg]?.toList() ?: emptyList()
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

            _apps.value = appsList
            _categories.value = sortedCats
            _universalPatches.value = universalList
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        return _apps.value.firstOrNull { it.packageName == pkg }
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
        _apps.value = currentApps
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

            for (patch in bundle.patches) {
                if (patch.isUniversal || patch.compatiblePackages.isEmpty()) {
                    // Universal patch
                    val isDup = _universalPatches.value.any { it.name.equals(patch.name, ignoreCase = true) }
                    if (!isDup) {
                        _universalPatches.value = _universalPatches.value + MorpheCatalogPatch(
                            name = patch.name,
                            description = patch.description,
                            isDefault = patch.enabledByDefault,
                            isUniversal = true,
                            bundleName = bundle.name,
                            bundleAuthor = bundle.author
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
                            isDefault = patch.enabledByDefault,
                            isUniversal = false,
                            bundleName = bundle.name,
                            bundleAuthor = bundle.author,
                            compatiblePackages = listOf(pkg),
                            targetVersions = patch.compatibleVersions
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
                val seenNames = existingPatches.map { it.name.lowercase() }.toMutableSet()
                val mergedList = existingPatches.toMutableList()

                for (p in patches) {
                    if (seenNames.add(p.name.lowercase())) {
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
                val allVers = (app.compatibleVersions.toSet() + vers).toList()
                currentApps[idx] = app.copy(
                    patchCount = specificPatches.size,
                    bundleCount = allBundles.size,
                    bundleNames = allBundles,
                    compatibleVersions = allVers
                )
            } else {
                // Discover human-readable name from package
                val simpleName = when {
                    pkg.contains("instagram", ignoreCase = true) -> "Instagram"
                    pkg.contains("youtube", ignoreCase = true) -> "YouTube"
                    pkg.contains("twitter", ignoreCase = true) || pkg.contains("x.android", ignoreCase = true) -> "Twitter / X"
                    pkg.contains("reddit", ignoreCase = true) -> "Reddit"
                    pkg.contains("spotify", ignoreCase = true) -> "Spotify"
                    pkg.contains("tiktok", ignoreCase = true) -> "TikTok"
                    else -> pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }
                }

                val newApp = MorpheCatalogApp(
                    packageName = pkg,
                    name = simpleName,
                    category = "Social & Media",
                    iconUrl = "https://icon.horse/icon/$pkg",
                    description = "Morphe-compatible app with ${specificPatches.size} patches across ${bundlesSet.size} bundles",
                    compatibleVersions = vers,
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
        _apps.value = currentApps
    }
}
