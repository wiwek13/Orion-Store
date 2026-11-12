package com.orion.store.morphe.engine

import com.orion.store.OrionApp
import com.orion.store.data.Net
import com.orion.store.morphe.store.MorpheCatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Resolves and caches real `.mpp` patch bundle binaries for any catalog app or GitHub repository.
 */
object MppBundleResolver {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun mppCacheDir(): File = File(OrionApp.instance.filesDir, "mpp_cache").apply { mkdirs() }

    /**
     * Resolves a local verified `.mpp` file containing compiled `classes.dex` patches.
     */
    suspend fun resolveBundleFile(
        packageName: String,
        preferredRepo: String = "",
        preferredBundleName: String = "",
        preferredDownloadUrl: String = "",
        onLog: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val metaFromBundle = if (preferredBundleName.isNotBlank()) {
            MorpheCatalogRepository.resolveBundleMetadata(preferredBundleName)
        } else null
        val metaFromPkg = MorpheCatalogRepository.resolveBundleForPackage(packageName)

        val candidateDownloadUrls = listOfNotNull(
            preferredDownloadUrl.takeIf { it.isNotBlank() },
            metaFromBundle?.downloadUrl?.takeIf { it.isNotBlank() },
            metaFromPkg?.downloadUrl?.takeIf { it.isNotBlank() }
        ).distinct()

        for (dl in candidateDownloadUrls) {
            if (isDirectBundleUrl(dl)) {
                val repoTag = normalizeRepoSlug(preferredRepo.ifBlank { metaFromBundle?.repo.orEmpty() })
                    .ifBlank { preferredBundleName.ifBlank { packageName } }
                onLog("[BUNDLE] Downloading .mpp bundle: $dl")
                val cached = downloadMppAsset(dl, cacheKey = repoTag, assetName = dl.substringAfterLast('/').substringBefore('?'))
                if (cached != null && isValidMppArchive(cached)) {
                    onLog("[BUNDLE] Verified .mpp (${cached.length() / 1024} KB)")
                    return@withContext cached
                }
            }
        }

        val repoSlugs = buildList {
            preferredRepo.takeIf { it.isNotBlank() }?.let { add(normalizeRepoSlug(it)) }
            metaFromBundle?.repo?.takeIf { it.isNotBlank() }?.let { add(normalizeRepoSlug(it)) }
            metaFromBundle?.repositoryUrl?.takeIf { it.isNotBlank() }?.let { add(normalizeRepoSlug(it)) }
            metaFromPkg?.repo?.takeIf { it.isNotBlank() }?.let { add(normalizeRepoSlug(it)) }
            metaFromPkg?.repositoryUrl?.takeIf { it.isNotBlank() }?.let { add(normalizeRepoSlug(it)) }
            val (specifics, _) = MorpheCatalogRepository.getPatchesForApp(packageName)
            specifics.mapNotNull { p ->
                p.bundleRepo.takeIf { it.isNotBlank() }?.let(::normalizeRepoSlug)
            }.forEach { add(it) }
        }.filter { it.contains("/") }.distinct()

        val preferPrerelease = metaFromBundle?.isPrerelease ?: metaFromPkg?.isPrerelease ?: false

        for (repoSlug in repoSlugs) {
            onLog("[BUNDLE] Querying GitHub releases for $repoSlug...")
            val asset = fetchMppAssetFromGitHub(repoSlug, preferPrerelease)
            if (asset != null) {
                val (assetName, assetUrl) = asset
                onLog("[BUNDLE] Downloading $assetName ($repoSlug)")
                val cached = downloadMppAsset(assetUrl, cacheKey = repoSlug, assetName = assetName)
                if (cached != null && isValidMppArchive(cached)) {
                    onLog("[BUNDLE] Loaded verified .mpp bundle ($repoSlug: ${cached.length() / 1024} KB)")
                    return@withContext cached
                }
            }

            // Offline/rate-limit fallback: reuse any valid cached .mpp for this repo
            findCachedMppForRepo(repoSlug)?.let { cached ->
                onLog("[BUNDLE] Using cached offline .mpp for $repoSlug (${cached.name})")
                return@withContext cached
            }
        }

        null
    }

    internal fun normalizeRepoSlug(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        val afterGh = when {
            trimmed.contains("github.com/", ignoreCase = true) ->
                trimmed.substringAfter("github.com/", "")
            trimmed.startsWith("http", ignoreCase = true) -> return ""
            else -> trimmed
        }
        val parts = afterGh.split('/').filter { it.isNotBlank() }
        return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else ""
    }

    internal fun isDirectBundleUrl(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".mpp")
    }

    internal fun findMppAssetInReleaseJson(releaseObj: JsonObject): Pair<String, String>? {
        val assets = releaseObj["assets"]?.jsonArray ?: return null
        var best: Pair<String, String>? = null
        for (item in assets) {
            val obj = item as? JsonObject ?: continue
            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.endsWith(".mpp", ignoreCase = true) && url.isNotBlank()) {
                return name to url
            }
            if (best == null && (name.endsWith(".rvp", ignoreCase = true)) && url.isNotBlank()) {
                best = name to url
            }
        }
        return best
    }

    private fun fetchMppAssetFromGitHub(repoSlug: String, preferPrerelease: Boolean): Pair<String, String>? {
        val endpoints = if (preferPrerelease) {
            listOf(
                "https://api.github.com/repos/$repoSlug/releases?per_page=8",
                "https://api.github.com/repos/$repoSlug/releases/latest"
            )
        } else {
            listOf(
                "https://api.github.com/repos/$repoSlug/releases/latest",
                "https://api.github.com/repos/$repoSlug/releases?per_page=8"
            )
        }

        for (endpoint in endpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "OrionStore-Android/2.0")
                    .build()
                Net.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body.string()
                    if (body.isBlank()) return@use
                    val parsed = json.parseToJsonElement(body)
                    when (parsed) {
                        is JsonObject -> findMppAssetInReleaseJson(parsed)?.let { return it }
                        is JsonArray -> {
                            for (rel in parsed) {
                                val obj = rel as? JsonObject ?: continue
                                findMppAssetInReleaseJson(obj)?.let { return it }
                            }
                        }
                        else -> {}
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun downloadMppAsset(url: String, cacheKey: String, assetName: String): File? {
        val safeFolder = cacheKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val safeName = assetName.ifBlank { "bundle.mpp" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dir = File(mppCacheDir(), safeFolder).apply { mkdirs() }
        val target = File(dir, safeName)
        val urlMarker = File(dir, "$safeName.url")
        val cachedUrlMatches = runCatching { urlMarker.exists() && urlMarker.readText() == url }.getOrDefault(false)
        if (cachedUrlMatches && target.exists() && target.length() > 1024L && isValidMppArchive(target)) {
            target.setReadOnly()
            return target
        }

        val tmp = File(dir, "$safeName.tmp")
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "OrionStore-Android/2.0")
                .build()
            Net.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body.byteStream().use { input ->
                    FileOutputStream(tmp).use { out ->
                        input.copyTo(out)
                    }
                }
            }
            if (!isValidMppArchive(tmp)) {
                tmp.delete()
                return null
            }
            // Keep only the latest valid .mpp per repo cache folder
            dir.listFiles()?.forEach { old ->
                if (old.name != safeName && old.name != tmp.name && old.name != urlMarker.name) {
                    old.setWritable(true)
                    old.delete()
                }
            }
            if (target.exists()) {
                target.setWritable(true)
                target.delete()
            }
            val finalFile = if (tmp.renameTo(target)) target else tmp
            runCatching { urlMarker.writeText(url) }
            // Android 14+ rejects loading writable DEX/MPP files via DexClassLoader
            finalFile.setReadOnly()
            finalFile
        } catch (_: Exception) {
            tmp.delete()
            null
        }
    }

    private fun findCachedMppForRepo(repoSlug: String): File? {
        val safeFolder = repoSlug.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dir = File(mppCacheDir(), safeFolder)
        if (!dir.exists() || !dir.isDirectory) return null
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mpp", ignoreCase = true) && isValidMppArchive(it) }
            ?.maxByOrNull { it.lastModified() }
            ?.also { it.setReadOnly() }
    }

    fun isValidMppArchive(file: File): Boolean {
        if (!file.exists() || file.length() < 512L) return false
        return runCatching {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().any { it.name == "classes.dex" || it.name.endsWith(".dex") }
            }
        }.getOrDefault(false)
    }
}

