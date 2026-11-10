package com.orion.store.morphe.store

import com.orion.store.data.Net
import com.orion.store.morphe.engine.MorphePatch
import com.orion.store.morphe.engine.PatchBundle
import com.orion.store.morphe.model.MorpheRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Scraper and integration engine for https://metamorphe.vercel.app/#sources.
 * Aggregates 7 community repository sources, 630+ patch bundles, and official registry releases.
 */
object MetaMorpheScraper {

    private const val BASE_URL = "https://metamorphe.vercel.app"
    private const val NEWS_URL = "$BASE_URL/news.js"
    private const val DATA_URL = "$BASE_URL/data.js"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Scrapes the sources listed on https://metamorphe.vercel.app/#sources.
     */
    suspend fun scrapeSources(): List<MetaMorpheSource> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MetaMorpheSource>()
        try {
            val text = Net.text(NEWS_URL, c = Net.browser)
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
                    if (url.isNotBlank()) {
                        list.add(MetaMorpheSource(name, url, count, at, info))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    /**
     * Scrapes all community sources from https://metamorphe.vercel.app/#sources
     * and adds them to MorpheRepoStore (respecting user-removed repositories).
     */
    suspend fun syncSourcesToRepoStore(): Int = withContext(Dispatchers.IO) {
        val sources = scrapeSources()
        if (sources.isEmpty()) return@withContext 0

        val removed = com.orion.store.ui.Store.settings.value.morpheRemovedRepoUrls
        var addedCount = 0

        val existingUrls = MorpheRepoStore.repos.value.map { it.url }.toSet()
        val existingNames = MorpheRepoStore.repos.value.map { it.name }.toSet()

        val newRepos = mutableListOf<MorpheRepo>()

        for (s in sources) {
            if (s.url in removed || s.name in removed) continue
            if (s.url in existingUrls || s.name in existingNames) continue

            val desc = if (s.info.isNotBlank()) "${s.info} · From MétaMorphe (#sources)"
            else "${s.count} bundles/repos · From MétaMorphe (#sources)"

            val repo = MorpheRepo(
                id = "metamorphe-${UUID.randomUUID()}",
                name = s.name,
                url = s.url,
                description = desc,
                isEnabled = true,
                isDefault = false,
                isTrusted = s.name.contains("Official", true) || s.name.contains("Awesome", true),
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
     * Scrapes curated registry bundles from news.js with direct .mpp download links and package mappings.
     */
    suspend fun scrapeRegistryBundles(): List<MetaMorpheRegistryBundle> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MetaMorpheRegistryBundle>()
        try {
            val text = Net.text(NEWS_URL, c = Net.browser)
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

                    list.add(MetaMorpheRegistryBundle(id, src, version, patchCount, pkgs, dl, icon))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    /**
     * Scrapes 630+ bundles and their patches from https://metamorphe.vercel.app/data.js.
     */
    suspend fun scrapeAllBundles(): List<PatchBundle> = withContext(Dispatchers.IO) {
        val list = mutableListOf<PatchBundle>()
        try {
            val text = Net.text(DATA_URL, c = Net.browser)
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
        list
    }
}
