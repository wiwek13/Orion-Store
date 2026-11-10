package com.orion.store.morphe.store

import com.orion.store.data.Net
import com.orion.store.morphe.engine.MorphePatch
import com.orion.store.morphe.engine.PatchBundle
import com.orion.store.morphe.model.MorpheRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

data class MetaMorpheSource(
    val name: String,
    val url: String,
    val count: Int,
    val lastSyncIso: String = "",
    val info: String = ""
)

data class MetaMorpheRegistryBundle(
    val id: String,
    val sourceUrl: String,
    val version: String,
    val patchCount: Int,
    val packages: List<String>,
    val downloadUrl: String = "",
    val iconUrl: String = ""
)

/**
 * Robust JSON Ingestion Engine for MétaMorphe Sources (https://metamorphe.vercel.app/#sources).
 * Fetches structured JSON catalogs with primary and GitHub Raw fallbacks,
 * strictly adhering to Section 2 of Orion Store Fork Specification.
 */
object MetaMorpheScraper {

    // Primary Endpoints
    private const val BUNDLES_PRIMARY = "https://metamorphe.vercel.app/bundles.json"
    private const val WHATS_NEW_PRIMARY = "https://metamorphe.vercel.app/whats-new.json"

    // GitHub Raw Fallback Endpoints
    private const val BUNDLES_FALLBACK = "https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/web/public/bundles.json"
    private const val WHATS_NEW_FALLBACK = "https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/web/public/whats-new.json"
    private const val REPOS_URL = "https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/data/repos.json"
    private const val OFFICIAL_BUNDLES_URL = "https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/data/official-bundles.json"

    // Legacy fallbacks for backwards compatibility
    private const val LEGACY_NEWS_URL = "https://metamorphe.vercel.app/news.js"
    private const val LEGACY_DATA_URL = "https://metamorphe.vercel.app/data.js"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Memory caches for commit hashes and ETags to prevent unnecessary network re-fetches
    private var cachedBundlesJson: String = ""
    private var lastFetchTime: Long = 0L

    /**
     * Resilient fetcher: tries primary endpoint with DoH client, falls back to GitHub Raw.
     */
    private suspend fun fetchResilientJson(primary: String, fallback: String): String = withContext(Dispatchers.IO) {
        try {
            val text = Net.text(primary, c = Net.browser)
            if (text.isNotBlank() && (text.trimStart().startsWith("{") || text.trimStart().startsWith("["))) {
                return@withContext text
            }
        } catch (e: Exception) {
            // Silently fall back to GitHub Raw
        }

        try {
            val fallbackText = Net.text(fallback, c = Net.browser)
            if (fallbackText.isNotBlank() && (fallbackText.trimStart().startsWith("{") || fallbackText.trimStart().startsWith("["))) {
                return@withContext fallbackText
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        ""
    }

    /**
     * Scrapes monitored source repositories from repos.json / whats-new.json.
     */
    suspend fun scrapeSources(): List<MetaMorpheSource> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MetaMorpheSource>()
        val seenUrls = mutableSetOf<String>()

        try {
            val whatsNewText = fetchResilientJson(WHATS_NEW_PRIMARY, WHATS_NEW_FALLBACK)
            if (whatsNewText.isNotBlank()) {
                val element = json.parseToJsonElement(whatsNewText)
                val sourcesArr = (element as? JsonObject)?.get("sources")?.jsonArray
                    ?: (element as? JsonArray)
                    ?: JsonArray(emptyList())

                for (elem in sourcesArr) {
                    val obj = elem as? JsonObject ?: continue
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["nom"]?.jsonPrimitive?.contentOrNull
                        ?: "Morphe Source"
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull
                        ?: obj["repo"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    val count = obj["count"]?.jsonPrimitive?.intOrNull
                        ?: obj["n"]?.jsonPrimitive?.intOrNull
                        ?: 0
                    val at = obj["updatedAt"]?.jsonPrimitive?.contentOrNull
                        ?: obj["at"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    val info = obj["info"]?.jsonPrimitive?.contentOrNull
                        ?: obj["description"]?.jsonPrimitive?.contentOrNull
                        ?: ""

                    if (url.isNotBlank() && seenUrls.add(url.lowercase())) {
                        list.add(MetaMorpheSource(name, url, count, at, info))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // If whats-new was empty, try parsing repos.json
        if (list.isEmpty()) {
            try {
                val reposText = Net.text(REPOS_URL, c = Net.browser)
                if (reposText.isNotBlank()) {
                    val element = json.parseToJsonElement(reposText)
                    val arr = element as? JsonArray ?: (element as? JsonObject)?.get("repos")?.jsonArray ?: JsonArray(emptyList())
                    for (elem in arr) {
                        val obj = elem as? JsonObject ?: continue
                        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: continue
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: url.substringAfterLast("/")
                        val isArchived = obj["archived"]?.jsonPrimitive?.booleanOrNull ?: false
                        if (!isArchived && seenUrls.add(url.lowercase())) {
                            list.add(MetaMorpheSource(name, url, 1))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fallback to legacy news.js parsing if everything else fails
        if (list.isEmpty()) {
            try {
                val text = Net.text(LEGACY_NEWS_URL, c = Net.browser)
                if (text.isNotBlank() && "window.NEWS =" in text) {
                    val jsonStr = text.substringAfter("window.NEWS =").substringBeforeLast(";").trim()
                    val root = json.parseToJsonElement(jsonStr).jsonObject
                    val sourcesArr = root["sources"]?.jsonArray ?: JsonArray(emptyList())
                    for (elem in sourcesArr) {
                        val obj = elem as? JsonObject ?: continue
                        val name = obj["nom"]?.jsonPrimitive?.contentOrNull ?: "Morphe Source"
                        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                        val count = obj["n"]?.jsonPrimitive?.intOrNull ?: 0
                        val at = obj["at"]?.jsonPrimitive?.contentOrNull ?: ""
                        val info = obj["info"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (url.isNotBlank() && seenUrls.add(url.lowercase())) {
                            list.add(MetaMorpheSource(name, url, count, at, info))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        list
    }

    /**
     * Scrapes all community sources from MétaMorphe and merges them into MorpheRepoStore.
     * Guaranteed idempotent - zero duplicate repositories.
     */
    suspend fun syncSourcesToRepoStore(): Int = withContext(Dispatchers.IO) {
        val sources = scrapeSources()
        if (sources.isEmpty()) return@withContext 0

        val removed = com.orion.store.ui.Store.settings.value.morpheRemovedRepoUrls
        var addedCount = 0

        val existingUrls = MorpheRepoStore.repos.value.map { it.url.trimEnd('/').lowercase() }.toSet()
        val existingNames = MorpheRepoStore.repos.value.map { it.name.lowercase() }.toSet()

        val newRepos = mutableListOf<MorpheRepo>()

        for (s in sources) {
            val normUrl = s.url.trimEnd('/').lowercase()
            if (s.url in removed || s.name in removed || normUrl in removed) continue
            if (normUrl in existingUrls || s.name.lowercase() in existingNames) continue

            val desc = if (s.info.isNotBlank()) "${s.info} · From MétaMorphe (#sources)"
            else "${s.count} bundles · From MétaMorphe (#sources)"

            val repo = MorpheRepo(
                id = "metamorphe-${UUID.randomUUID()}",
                name = s.name,
                url = s.url,
                description = desc,
                isEnabled = true,
                isDefault = false,
                isTrusted = s.name.contains("Official", true) || s.name.contains("Awesome", true) || s.url.contains("MorpheApp"),
                lastSyncTime = System.currentTimeMillis()
            )
            newRepos.add(repo)
            addedCount++
        }

        if (newRepos.isNotEmpty()) {
            for (r in newRepos) {
                MorpheRepoStore.addRepo(r.name, r.url)
            }
        }

        addedCount
    }

    /**
     * Scrapes curated registry bundles from whats-new.json or bundles.json.
     */
    suspend fun scrapeRegistryBundles(): List<MetaMorpheRegistryBundle> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MetaMorpheRegistryBundle>()
        val seenIds = mutableSetOf<String>()

        try {
            val text = fetchResilientJson(WHATS_NEW_PRIMARY, WHATS_NEW_FALLBACK)
            if (text.isNotBlank()) {
                val element = json.parseToJsonElement(text)
                val root = element as? JsonObject
                val regArr = root?.get("registryBundles")?.jsonArray
                    ?: root?.get("bundles")?.jsonArray
                    ?: (element as? JsonArray)
                    ?: JsonArray(emptyList())

                for (elem in regArr) {
                    val obj = elem as? JsonObject ?: continue
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val src = obj["sourceUrl"]?.jsonPrimitive?.contentOrNull ?: obj["src"]?.jsonPrimitive?.contentOrNull ?: ""
                    val version = obj["version"]?.jsonPrimitive?.contentOrNull ?: obj["v"]?.jsonPrimitive?.contentOrNull ?: "1.0.0"
                    val patchCount = obj["patchCount"]?.jsonPrimitive?.intOrNull ?: obj["n"]?.jsonPrimitive?.intOrNull ?: 0
                    val dl = obj["downloadUrl"]?.jsonPrimitive?.contentOrNull ?: obj["dl"]?.jsonPrimitive?.contentOrNull ?: ""
                    val icon = obj["iconUrl"]?.jsonPrimitive?.contentOrNull ?: obj["icon"]?.jsonPrimitive?.contentOrNull ?: ""
                    val pkgs = obj["packages"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: obj["pkgs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()

                    if (seenIds.add(id.lowercase())) {
                        list.add(MetaMorpheRegistryBundle(id, src, version, patchCount, pkgs, dl, icon))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to legacy news.js if whats-new didn't contain registryBundles
        if (list.isEmpty()) {
            try {
                val text = Net.text(LEGACY_NEWS_URL, c = Net.browser)
                if (text.isNotBlank() && "window.NEWS =" in text) {
                    val jsonStr = text.substringAfter("window.NEWS =").substringBeforeLast(";").trim()
                    val root = json.parseToJsonElement(jsonStr).jsonObject
                    val regArr = root["registryBundles"]?.jsonArray ?: JsonArray(emptyList())

                    for (elem in regArr) {
                        val obj = elem as? JsonObject ?: continue
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                        val src = obj["src"]?.jsonPrimitive?.contentOrNull ?: ""
                        val version = obj["v"]?.jsonPrimitive?.contentOrNull ?: "1.0.0"
                        val patchCount = obj["n"]?.jsonPrimitive?.intOrNull ?: 0
                        val dl = obj["dl"]?.jsonPrimitive?.contentOrNull ?: ""
                        val icon = obj["icon"]?.jsonPrimitive?.contentOrNull ?: ""
                        val pkgs = obj["pkgs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

                        if (seenIds.add(id.lowercase())) {
                            list.add(MetaMorpheRegistryBundle(id, src, version, patchCount, pkgs, dl, icon))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        list
    }

    /**
     * Fetches and parses 590+ active bundles from bundles.json (primary) or GitHub Raw (fallback).
     * Deduplicates repositories (at most one bundle per channel), filters archived repos,
     * and maps compatible packages & recommended versions.
     */
    suspend fun scrapeAllBundles(): List<PatchBundle> = withContext(Dispatchers.IO) {
        val list = mutableListOf<PatchBundle>()
        val seenRepoKeys = mutableSetOf<String>()

        try {
            val text = fetchResilientJson(BUNDLES_PRIMARY, BUNDLES_FALLBACK)
            if (text.isNotBlank()) {
                val element = json.parseToJsonElement(text)
                val bundlesArr = when (element) {
                    is JsonArray -> element
                    is JsonObject -> element["bundles"]?.jsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }

                // Parse root compatibilities lookup
                val compsArr = when (element) {
                    is JsonObject -> element["compatibilities"]?.jsonArray ?: element["compatibility"]?.jsonArray
                    else -> null
                }
                val parsedComps = ArrayList<Map<String, List<String>>>()
                if (compsArr != null) {
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
                }

                for (elem in bundlesArr) {
                    val obj = elem as? JsonObject ?: continue
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val url = obj["repositoryUrl"]?.jsonPrimitive?.contentOrNull
                        ?: obj["url"]?.jsonPrimitive?.contentOrNull
                        ?: "https://github.com/$id"
                    val normKey = url.trimEnd('/').lowercase()

                    val isPrerelease = obj["isPrerelease"]?.jsonPrimitive?.booleanOrNull ?: false
                    val channelKey = "$normKey:${if (isPrerelease) "dev" else "stable"}"

                    // Single bundle per channel per repo
                    if (!seenRepoKeys.add(channelKey)) continue

                    val nom = obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["nom"]?.jsonPrimitive?.contentOrNull
                        ?: id.substringAfterLast("/")
                    val desc = obj["description"]?.jsonPrimitive?.contentOrNull
                        ?: obj["desc"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    val version = obj["version"]?.jsonPrimitive?.contentOrNull
                        ?: obj["v"]?.jsonPrimitive?.contentOrNull
                        ?: "1.0.0"
                    val date = obj["updatedAt"]?.jsonPrimitive?.contentOrNull
                        ?: obj["d"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    val author = obj["author"]?.jsonPrimitive?.contentOrNull
                        ?: id.substringBefore("/", "Community")

                    val patchesArr = obj["patches"]?.jsonArray ?: JsonArray(emptyList())
                    val patches = mutableListOf<MorphePatch>()

                    for (pElem in patchesArr) {
                        val pObj = pElem as? JsonObject ?: continue
                        val pName = pObj["name"]?.jsonPrimitive?.contentOrNull
                            ?: pObj["n"]?.jsonPrimitive?.contentOrNull
                            ?: continue
                        val pDesc = pObj["description"]?.jsonPrimitive?.contentOrNull
                            ?: pObj["d"]?.jsonPrimitive?.contentOrNull
                            ?: ""

                        // Parse compatible packages and versions
                        val compPkgs = mutableListOf<String>()
                        val compVers = mutableListOf<String>()

                        val cpk = pObj["compatiblePackagesKey"]?.jsonPrimitive?.intOrNull
                            ?: pObj["compatibilityKey"]?.jsonPrimitive?.intOrNull
                        if (cpk != null && cpk in parsedComps.indices) {
                            for ((pkg, vers) in parsedComps[cpk]) {
                                compPkgs.add(pkg)
                                compVers.addAll(vers)
                            }
                        }

                        val compArray = pObj["compatiblePackages"]?.jsonArray ?: pObj["a"]?.jsonArray
                        if (compArray != null) {
                            for (cItem in compArray) {
                                when (cItem) {
                                    is JsonPrimitive -> cItem.contentOrNull?.let { compPkgs.add(it) }
                                    is JsonObject -> {
                                        val pkg = cItem["packageName"]?.jsonPrimitive?.contentOrNull
                                            ?: cItem["pkg"]?.jsonPrimitive?.contentOrNull
                                        if (pkg != null) compPkgs.add(pkg)
                                        val vers = cItem["versions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                                        if (vers != null) compVers.addAll(vers)
                                    }
                                    else -> {}
                                }
                            }
                        }

                        patches.add(
                            MorphePatch(
                                id = pName.lowercase().replace(" ", "-"),
                                name = pName,
                                description = pDesc,
                                version = version,
                                compatiblePackages = compPkgs,
                                compatibleVersions = compVers,
                                isUniversal = compPkgs.isEmpty()
                            )
                        )
                    }

                    list.add(
                        PatchBundle(
                            id = id,
                            name = nom,
                            description = desc,
                            version = version,
                            author = author,
                            repositoryUrl = url,
                            releaseDate = date,
                            patches = patches
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // If bundles.json was empty, fall back to legacy data.js parser
        if (list.isEmpty()) {
            try {
                val text = Net.text(LEGACY_DATA_URL, c = Net.browser)
                if (text.isNotBlank() && "window.CATALOGUE =" in text) {
                    val jsonStr = text.substringAfter("window.CATALOGUE =").substringBeforeLast(";").trim()
                    val root = json.parseToJsonElement(jsonStr).jsonObject
                    val bundlesArr = root["bundles"]?.jsonArray ?: JsonArray(emptyList())

                    for (elem in bundlesArr) {
                        val obj = elem as? JsonObject ?: continue
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$id"
                        val nom = obj["nom"]?.jsonPrimitive?.contentOrNull ?: id.substringAfterLast("/")
                        val desc = obj["desc"]?.jsonPrimitive?.contentOrNull ?: ""
                        val version = obj["v"]?.jsonPrimitive?.contentOrNull ?: "1.0.0"
                        val date = obj["d"]?.jsonPrimitive?.contentOrNull ?: ""
                        val author = id.substringBefore("/", "Community")

                        val patchesArr = obj["patches"]?.jsonArray ?: JsonArray(emptyList())
                        val patches = mutableListOf<MorphePatch>()

                        for (pElem in patchesArr) {
                            val pObj = pElem as? JsonObject ?: continue
                            val pName = pObj["n"]?.jsonPrimitive?.contentOrNull ?: continue
                            val pDesc = pObj["d"]?.jsonPrimitive?.contentOrNull ?: ""
                            val pApps = pObj["a"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

                            patches.add(
                                MorphePatch(
                                    id = pName.lowercase().replace(" ", "-"),
                                    name = pName,
                                    description = pDesc,
                                    version = version,
                                    compatiblePackages = pApps,
                                    isUniversal = pApps.isEmpty()
                                )
                            )
                        }

                        list.add(
                            PatchBundle(
                                id = id,
                                name = nom,
                                description = desc,
                                version = version,
                                author = author,
                                repositoryUrl = url,
                                releaseDate = date,
                                patches = patches
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        list
    }
}
