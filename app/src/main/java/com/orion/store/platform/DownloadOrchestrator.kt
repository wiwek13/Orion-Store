package com.orion.store.platform

import android.content.Context
import android.os.Build
import com.orion.store.data.Net
import com.orion.store.ui.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Two-Tier Download Pipeline: Orion Store Primary -> MorpheFlow Base APK Ingestion Cascade.
 * Implements Section 4 of Orion Store Fork Specification.
 */
object DownloadOrchestrator {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Dedicated OkHttpClient for large APK downloads with extended timeouts and followRedirects.
     * Crucial: callTimeout MUST be 0 (unlimited) so 100MB-500MB APK files do not fail with timeout.
     */
    val downloadClient: OkHttpClient by lazy {
        Net.client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    data class ResolvedApk(
        val baseApk: File,
        val splits: List<File> = emptyList(),
        val sourceProvider: String,
        val resolvedVersion: String
    )

    fun getBaseCacheDir(context: Context, packageName: String): File {
        val dir = File(context.filesDir, "base_apk_cache/$packageName")
        dir.mkdirs()
        return dir
    }

    private fun resolveFromBaseCache(
        context: Context,
        packageName: String,
        requestedVersion: String,
        targetDir: File
    ): ResolvedApk? {
        val cacheDir = getBaseCacheDir(context, packageName)
        if (!cacheDir.exists()) return null

        val cleanReq = requestedVersion.trim().removePrefix("v").lowercase()
        val isAnyVersion = cleanReq.isBlank() || cleanReq == "recommended" || cleanReq == "latest"

        val candidates = cacheDir.listFiles()?.filter { file ->
            file.extension.equals("apk", ignoreCase = true) && isApkValid(file, requireCleanBase = true)
        } ?: return null

        if (candidates.isEmpty()) return null

        val chosen = if (isAnyVersion) {
            candidates.maxByOrNull { it.lastModified() }
        } else {
            candidates.firstOrNull { it.name.lowercase().contains(cleanReq) }
                ?: candidates.maxByOrNull { it.lastModified() }
        } ?: return null

        val dest = File(targetDir, "cached_base.apk")
        chosen.copyTo(dest, overwrite = true)
        if (isApkValid(dest, requireCleanBase = true)) {
            val ver = chosen.name.substringAfterLast("_").substringBeforeLast(".apk")
            return ResolvedApk(dest, emptyList(), "Cached Base APK (Reused)", ver)
        }
        return null
    }

    fun cacheBaseApk(context: Context, packageName: String, version: String, baseFile: File) {
        try {
            if (!isApkValid(baseFile, requireCleanBase = true)) return
            val cacheDir = getBaseCacheDir(context, packageName)
            val cleanVer = version.ifBlank { "latest" }.replace(" ", "_")
            val target = File(cacheDir, "base_${packageName}_$cleanVer.apk")
            baseFile.copyTo(target, overwrite = true)
            // Automatically clean older base APKs for this package
            PatchStorageCleaner.cleanOlderBaseApks(context, packageName, target)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Resolves and downloads base APK for [packageName] and [versionName] into [targetDir].
     * Avoids re-downloading if already cached/downloaded, and cleans older versions automatically.
     */
    suspend fun resolveAndDownload(
        context: Context,
        packageName: String,
        appName: String,
        requestedVersion: String,
        sourcePath: String? = null,
        targetDir: File,
        onProgress: (stage: String, percent: Int) -> Unit
    ): ResolvedApk? = withContext(Dispatchers.IO) {
        targetDir.mkdirs()

        // Priority 0: User-provided local APK from device storage
        if (!sourcePath.isNullOrBlank()) {
            val src = File(sourcePath)
            if (src.exists() && isApkValid(src)) {
                onProgress("Using user-provided local APK...", 10)
                val resolved = normalizeApkBundle(src, targetDir, "User Provided Local APK", "Local")
                cacheBaseApk(context, packageName, resolved.resolvedVersion, resolved.baseApk)
                return@withContext resolved
            }
        }

        // Step 0: Check if currently installed app on device matches requested version
        val pm = context.packageManager
        val installedPkgInfo = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
        if (installedPkgInfo != null) {
            val installedVer = installedPkgInfo.versionName ?: ""
            val cleanReq = requestedVersion.trim().removePrefix("v").lowercase()
            val cleanInst = installedVer.trim().removePrefix("v").lowercase()
            val isMatch = requestedVersion.isBlank() ||
                cleanReq == "recommended" ||
                cleanReq == "latest" ||
                cleanReq == cleanInst
            val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
            if (isMatch && appInfo != null && File(appInfo.sourceDir).exists()) {
                onProgress("Extracting installed base APK from device (v$installedVer)...", 15)
                val base = File(targetDir, "base_extracted.apk")
                File(appInfo.sourceDir).copyTo(base, overwrite = true)
                val splits = mutableListOf<File>()
                appInfo.splitSourceDirs?.forEachIndexed { idx, sPath ->
                    val sf = File(sPath)
                    if (sf.exists() && sf.length() > 0) {
                        val targetSplit = File(targetDir, "split_${idx}.apk")
                        sf.copyTo(targetSplit, overwrite = true)
                        splits.add(targetSplit)
                    }
                }
                if (isApkValid(base)) {
                    cacheBaseApk(context, packageName, installedVer, base)
                    return@withContext ResolvedApk(base, splits, "Device Extraction", installedVer)
                }
            }
        }

        // Step 1: Check persistent base APK cache (DO NOT REDOWNLOAD if already downloaded!)
        onProgress("Checking local APK cache...", 8)
        val cachedResult = resolveFromBaseCache(context, packageName, requestedVersion, targetDir)
        if (cachedResult != null && isApkValid(cachedResult.baseApk)) {
            onProgress("Reusing already downloaded base APK...", 20)
            return@withContext cachedResult
        }

        // Step 2: Check local Downloads directory before hitting network
        val localResult = resolveFromLocalDownloads(context, packageName, appName, targetDir)
        if (localResult != null && isApkValid(localResult.baseApk)) {
            onProgress("Using APK found in Downloads...", 20)
            cacheBaseApk(context, packageName, localResult.resolvedVersion, localResult.baseApk)
            return@withContext localResult
        }

        // Tier 1: Orion Store Native Downloader
        onProgress("Checking Orion Store repository...", 12)
        val orionResult = resolveFromOrionStore(packageName, requestedVersion, targetDir, onProgress)
        if (orionResult != null && isApkValid(orionResult.baseApk)) {
            cacheBaseApk(context, packageName, orionResult.resolvedVersion, orionResult.baseApk)
            return@withContext orionResult
        }

        // Tier 2: MorpheFlow Base APK Cascade
        // Fallback 1: GitHub Official Releases (for open-source apps like Brave Browser)
        val githubResult = resolveFromGitHubReleases(packageName, requestedVersion, targetDir, onProgress)
        if (githubResult != null && isApkValid(githubResult.baseApk)) {
            cacheBaseApk(context, packageName, githubResult.resolvedVersion, githubResult.baseApk)
            return@withContext githubResult
        }

        // Fallback 2: APKPure Direct CDN (reliable for Instagram, Brave, Proton, etc.)
        onProgress("Resolving from APKPure repository...", 20)
        val apkPureResult = resolveFromApkPure(packageName, requestedVersion, targetDir, onProgress)
        if (apkPureResult != null && isApkValid(apkPureResult.baseApk)) {
            cacheBaseApk(context, packageName, apkPureResult.resolvedVersion, apkPureResult.baseApk)
            return@withContext apkPureResult
        }

        // Fallback 3: Aptoide Public API (generalised for 99.9% of apps worldwide)
        onProgress("Resolving from Aptoide repository...", 28)
        val aptoideResult = resolveFromAptoide(packageName, requestedVersion, targetDir, onProgress)
        if (aptoideResult != null && isApkValid(aptoideResult.baseApk)) {
            cacheBaseApk(context, packageName, aptoideResult.resolvedVersion, aptoideResult.baseApk)
            return@withContext aptoideResult
        }

        // Fallback 4: Google Play (goopdl / GPlay API)
        onProgress("Resolving from Google Play (goopdl)...", 40)
        val gplayResult = resolveFromGooglePlay(packageName, requestedVersion, targetDir, onProgress)
        if (gplayResult != null && isApkValid(gplayResult.baseApk)) {
            cacheBaseApk(context, packageName, gplayResult.resolvedVersion, gplayResult.baseApk)
            return@withContext gplayResult
        }

        // Fallback 4: Uptodown Resolver
        onProgress("Resolving from Uptodown repository...", 55)
        val uptodownResult = resolveFromUptodown(packageName, requestedVersion, targetDir, onProgress)
        if (uptodownResult != null && isApkValid(uptodownResult.baseApk)) {
            cacheBaseApk(context, packageName, uptodownResult.resolvedVersion, uptodownResult.baseApk)
            return@withContext uptodownResult
        }

        // Fallback 5: APKCombo Resolver
        onProgress("Resolving from APKCombo repository...", 70)
        val apkComboResult = resolveFromApkCombo(packageName, requestedVersion, targetDir, onProgress)
        if (apkComboResult != null && isApkValid(apkComboResult.baseApk)) {
            cacheBaseApk(context, packageName, apkComboResult.resolvedVersion, apkComboResult.baseApk)
            return@withContext apkComboResult
        }

        // Fallback 6: If app is installed on device at any version, use it rather than failing
        if (installedPkgInfo != null) {
            val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
            if (appInfo != null && File(appInfo.sourceDir).exists()) {
                val installedVer = installedPkgInfo.versionName ?: ""
                onProgress("Using installed version (v$installedVer)...", 90)
                val base = File(targetDir, "base_extracted.apk")
                File(appInfo.sourceDir).copyTo(base, overwrite = true)
                val splits = mutableListOf<File>()
                appInfo.splitSourceDirs?.forEachIndexed { idx, sPath ->
                    val sf = File(sPath)
                    if (sf.exists() && sf.length() > 0) {
                        val targetSplit = File(targetDir, "split_${idx}.apk")
                        sf.copyTo(targetSplit, overwrite = true)
                        splits.add(targetSplit)
                    }
                }
                if (isApkValid(base, requireCleanBase = true)) {
                    return@withContext ResolvedApk(base, splits, "Device Extraction (Installed v$installedVer)", installedVer)
                }
            }
        }

        null
    }

    private suspend fun resolveFromOrionStore(
        packageName: String,
        versionName: String,
        targetDir: File,
        onProgress: (String, Int) -> Unit
    ): ResolvedApk? {
        val app = Store.data.value.apps.find { it.packageName == packageName || it.id == packageName }
        if (app != null && app.downloadUrl.isNotBlank() && app.downloadUrl != "#") {
            // Guard: Orion Store hosts pre-modded community builds (e.g. YouTube Morphe, Instagram Piko).
            // These MUST NEVER be used as unpatched base APKs for Morphe Patcher!
            val isPreModded = app.id.contains("revanced", true) ||
                app.id.contains("piko", true) ||
                app.id.contains("morphe", true) ||
                app.name.contains("revanced", true) ||
                app.name.contains("piko", true) ||
                app.name.contains("morphe", true) ||
                app.name.contains("mod", true) ||
                app.patches.isNotEmpty()
            if (isPreModded) {
                return null
            }

            val candidateUrl = app.variants.firstOrNull()?.url ?: app.downloadUrl
            val targetFile = File(targetDir, "orion_base.apk")
            if (downloadFile(candidateUrl, targetFile, onProgress, 12, 18) && isApkValid(targetFile, requireCleanBase = true)) {
                return normalizeApkBundle(targetFile, targetDir, "Orion Store", app.version)
            }
        }
        return null
    }

    private suspend fun resolveFromGitHubReleases(
        packageName: String,
        versionName: String,
        targetDir: File,
        onProgress: (String, Int) -> Unit
    ): ResolvedApk? {
        return try {
            val knownRepos = mapOf(
                "com.brave.browser" to "brave/brave-browser",
                "com.rubenmayayo.reddit" to "rubenmayayo/boost-for-reddit",
                "ch.protonvpn.android" to "ProtonVPN/android-app",
                "org.videolan.vlc" to "videolan/vlc-android",
                "org.schabi.newpipe" to "TeamNewPipe/NewPipe",
                "org.telegram.messenger" to "DrKLO/Telegram",
                "org.mozilla.firefox" to "mozilla-mobile/firefox-android",
                "org.thunderdog.challegram" to "TGX-Android/Telegram-X",
                "com.duckduckgo.mobile.android" to "duckduckgo/Android",
                "io.github.muntashirakon.AppManager" to "MuntashirAkon/AppManager",
                "com.aurora.store" to "whyorean/AuroraStore"
            )

            val repoSlug = knownRepos[packageName] ?: run {
                val app = Store.data.value.apps.find { it.packageName == packageName || it.id == packageName }
                val rUrl = app?.repoUrl
                if (rUrl != null && rUrl.contains("github.com/")) {
                    rUrl.substringAfter("github.com/").trimEnd('/').removeSuffix(".git").takeIf { it.contains("/") }
                } else null
            } ?: return null

            onProgress("Checking GitHub releases for $repoSlug...", 22)
            val cleanVer = versionName.trim().removePrefix("v")
            val isSpecificVer = cleanVer.isNotBlank() && cleanVer != "recommended" && cleanVer != "latest"

            val apiUrl = if (isSpecificVer) {
                "https://api.github.com/repos/$repoSlug/releases/tags/v$cleanVer"
            } else {
                "https://api.github.com/repos/$repoSlug/releases/latest"
            }

            var resp = downloadClient.newCall(Request.Builder().url(apiUrl).header("User-Agent", "OrionStore").build()).execute()
            if (!resp.isSuccessful && isSpecificVer) {
                resp = downloadClient.newCall(Request.Builder().url("https://api.github.com/repos/$repoSlug/releases/tags/$cleanVer").header("User-Agent", "OrionStore").build()).execute()
                if (!resp.isSuccessful) {
                    resp = downloadClient.newCall(Request.Builder().url("https://api.github.com/repos/$repoSlug/releases/latest").header("User-Agent", "OrionStore").build()).execute()
                }
            }
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null

            val jsonElement = json.parseToJsonElement(body)
            val assets = (jsonElement as? JsonObject)?.get("assets") as? JsonArray ?: return null
            val apkAsset = (assets.firstOrNull { elem ->
                val name = (elem as? JsonObject)?.get("name")?.toString()?.trim('"')?.lowercase() ?: ""
                name.endsWith(".apk") && (name.contains("arm64") || name.contains("monoarm64") || name.contains("universal"))
            } ?: assets.firstOrNull { elem ->
                val name = (elem as? JsonObject)?.get("name")?.toString()?.trim('"')?.lowercase() ?: ""
                name.endsWith(".apk") && !name.contains("x86")
            }) as? JsonObject ?: return null

            val dlUrl = apkAsset["browser_download_url"]?.toString()?.trim('"') ?: return null
            val targetFile = File(targetDir, "github_${packageName}.apk")
            if (downloadFile(dlUrl, targetFile, onProgress, 25, 45) && isApkValid(targetFile)) {
                return normalizeApkBundle(targetFile, targetDir, "GitHub Releases ($repoSlug)", versionName.ifBlank { "Latest" })
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("DownloadOrchestrator", "resolveFromGitHubReleases error: ${e.message}", e)
            null
        }
    }

    private suspend fun resolveFromGooglePlay(
        packageName: String,
        versionName: String,
        targetDir: File,
        onProgress: (String, Int) -> Unit
    ): ResolvedApk? {
        return try {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val queryUrl = "https://goopdl.morphe.software/resolve?pkg=$packageName&ver=${URLEncoder.encode(versionName, "UTF-8")}&abi=$abi"
            val req = Request.Builder().url(queryUrl).build()
            val resp = downloadClient.newCall(req).execute()
            val body = resp.body
            if (resp.isSuccessful && body != null) {
                val targetFile = File(targetDir, "gplay_base.apk")
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                }
                if (targetFile.exists() && targetFile.length() > 500_000) {
                    normalizeApkBundle(targetFile, targetDir, "Google Play (goopdl)", versionName)
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveFromUptodown(
        packageName: String,
        versionName: String,
        targetDir: File,
        onProgress: (String, Int) -> Unit
    ): ResolvedApk? {
        return try {
            val queryUrl = "https://uptodown-resolver.morphe.software/resolve?pkg=$packageName&version=${URLEncoder.encode(versionName, "UTF-8")}"
            val req = Request.Builder().url(queryUrl).build()
            val resp = downloadClient.newCall(req).execute()
            val body = resp.body
            if (resp.isSuccessful && body != null) {
                val targetFile = File(targetDir, "uptodown_base.apk")
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                }
                if (targetFile.exists() && targetFile.length() > 500_000) {
                    normalizeApkBundle(targetFile, targetDir, "Uptodown", versionName)
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveFromApkCombo(
        packageName: String,
        versionName: String,
        targetDir: File,
        onProgress: (String, Int) -> Unit
    ): ResolvedApk? {
        return try {
            val queryUrl = "https://apkcombo-resolver.morphe.software/resolve?pkg=$packageName&ver=${URLEncoder.encode(versionName, "UTF-8")}&abi=arm64-v8a"
            val req = Request.Builder().url(queryUrl).build()
            val resp = downloadClient.newCall(req).execute()
            val body = resp.body
            if (resp.isSuccessful && body != null) {
                val targetFile = File(targetDir, "apkcombo_base.apk")
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                }
                if (targetFile.exists() && targetFile.length() > 500_000) {
                    normalizeApkBundle(targetFile, targetDir, "APKCombo", versionName)
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveFromApkPure(
        packageName: String,
        versionName: String,
        targetDir: File,
        onProgress: (String, Int) -> Unit
    ): ResolvedApk? {
        return try {
            val targetFile = File(targetDir, "apkpure_${packageName}.apk")
            val targetXapk = File(targetDir, "apkpure_${packageName}.xapk")
            val cleanVer = versionName.trim().removePrefix("v")
            val isSpecificVer = cleanVer.isNotBlank() && cleanVer != "recommended" && cleanVer != "latest"

            // 1. Try specific version APK/XAPK if requested
            if (isSpecificVer) {
                val specApk = "https://d.apkpure.net/b/APK/$packageName?version=$cleanVer"
                if (downloadFile(specApk, targetFile, onProgress, 20, 35) && isApkValid(targetFile)) {
                    return normalizeApkBundle(targetFile, targetDir, "APKPure (v$cleanVer)", cleanVer)
                }
                val specXapk = "https://d.apkpure.net/b/XAPK/$packageName?version=$cleanVer"
                if (downloadFile(specXapk, targetXapk, onProgress, 20, 35) && isApkValid(targetXapk)) {
                    return normalizeApkBundle(targetXapk, targetDir, "APKPure (XAPK v$cleanVer)", cleanVer)
                }
            }

            // 2. Try latest APK endpoint from APKPure CDN
            val apkUrl = "https://d.apkpure.net/b/APK/$packageName?version=latest"
            if (downloadFile(apkUrl, targetFile, onProgress, 20, 35) && isApkValid(targetFile)) {
                return normalizeApkBundle(targetFile, targetDir, "APKPure", versionName.ifBlank { "Latest" })
            }

            // 3. Try latest XAPK bundle endpoint if APK not available
            val xapkUrl = "https://d.apkpure.net/b/XAPK/$packageName?version=latest"
            if (downloadFile(xapkUrl, targetXapk, onProgress, 20, 35) && isApkValid(targetXapk)) {
                return normalizeApkBundle(targetXapk, targetDir, "APKPure (XAPK)", versionName.ifBlank { "Latest" })
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveFromAptoide(
        packageName: String,
        versionName: String,
        targetDir: File,
        onProgress: (String, Int) -> Unit
    ): ResolvedApk? = withContext(Dispatchers.IO) {
        try {
            val headers = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                .build()

            val cleanVer = versionName.trim().removePrefix("v")
            val isSpecificVer = cleanVer.isNotBlank() &&
                !cleanVer.equals("recommended", ignoreCase = true) &&
                !cleanVer.equals("latest", ignoreCase = true)

            var downloadUrl: String? = null
            var resolvedVer: String = versionName

            // 1. Try Aptoide app detail endpoint
            val detailUrl = "https://ws75.aptoide.com/api/7/app/get/package_name=$packageName"
            val detailReq = Request.Builder().url(detailUrl).headers(headers).build()
            val detailResp = runCatching { downloadClient.newCall(detailReq).execute() }.getOrNull()

            if (detailResp != null && detailResp.isSuccessful) {
                val bodyStr = detailResp.body?.string() ?: ""
                val root = runCatching { json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
                val nodes = root?.get("nodes")?.jsonObject
                val metaData = nodes?.get("meta")?.jsonObject?.get("data")?.jsonObject
                val metaFile = metaData?.get("file")?.jsonObject
                val latestVer = metaFile?.get("vername")?.jsonPrimitive?.contentOrNull ?: ""
                val latestPath = metaFile?.get("path")?.jsonPrimitive?.contentOrNull

                if (isSpecificVer) {
                    if (latestVer.equals(cleanVer, ignoreCase = true) || latestVer.startsWith(cleanVer)) {
                        downloadUrl = latestPath
                        resolvedVer = latestVer
                    } else {
                        val vlist = nodes?.get("versions")?.jsonObject?.get("list")?.jsonArray
                        val matchingEntry = vlist?.mapNotNull { it as? JsonObject }?.firstOrNull { vObj ->
                            val vName = vObj["file"]?.jsonObject?.get("vername")?.jsonPrimitive?.contentOrNull
                            vName != null && (vName.equals(cleanVer, ignoreCase = true) || vName.startsWith(cleanVer))
                        }
                        val appId = matchingEntry?.get("id")?.jsonPrimitive?.longOrNull
                        if (appId != null) {
                            val verUrl = "https://ws75.aptoide.com/api/7/app/get/app_id=$appId"
                            val verResp = runCatching { downloadClient.newCall(Request.Builder().url(verUrl).headers(headers).build()).execute() }.getOrNull()
                            if (verResp != null && verResp.isSuccessful) {
                                val verRoot = runCatching { json.parseToJsonElement(verResp.body?.string() ?: "").jsonObject }.getOrNull()
                                val verFile = verRoot?.get("nodes")?.jsonObject?.get("meta")?.jsonObject?.get("data")?.jsonObject?.get("file")?.jsonObject
                                downloadUrl = verFile?.get("path")?.jsonPrimitive?.contentOrNull
                                resolvedVer = verFile?.get("vername")?.jsonPrimitive?.contentOrNull ?: cleanVer
                            }
                        }
                    }
                }

                if (downloadUrl.isNullOrBlank() && !latestPath.isNullOrBlank()) {
                    downloadUrl = latestPath
                    resolvedVer = latestVer.ifBlank { "Latest" }
                }
            }

            // 2. Fallback to Aptoide Search API if detail endpoint didn't provide a URL
            if (downloadUrl.isNullOrBlank()) {
                val searchUrl = "https://ws75.aptoide.com/api/7/apps/search/query=$packageName/limit=10"
                val searchReq = Request.Builder().url(searchUrl).headers(headers).build()
                val searchResp = runCatching { downloadClient.newCall(searchReq).execute() }.getOrNull()
                if (searchResp != null && searchResp.isSuccessful) {
                    val searchRoot = runCatching { json.parseToJsonElement(searchResp.body?.string() ?: "").jsonObject }.getOrNull()
                    val items = searchRoot?.get("datalist")?.jsonObject?.get("list")?.jsonArray
                    val matchedItems = items?.mapNotNull { it as? JsonObject }?.filter {
                        it["package"]?.jsonPrimitive?.contentOrNull == packageName
                    } ?: emptyList()

                    val targetItem = if (isSpecificVer) {
                        matchedItems.firstOrNull {
                            val v = it["file"]?.jsonObject?.get("vername")?.jsonPrimitive?.contentOrNull ?: ""
                            v.equals(cleanVer, ignoreCase = true) || v.startsWith(cleanVer)
                        } ?: matchedItems.firstOrNull()
                    } else {
                        matchedItems.firstOrNull()
                    }

                    if (targetItem != null) {
                        val f = targetItem["file"]?.jsonObject
                        downloadUrl = f?.get("path")?.jsonPrimitive?.contentOrNull
                        resolvedVer = f?.get("vername")?.jsonPrimitive?.contentOrNull ?: resolvedVer
                    }
                }
            }

            if (!downloadUrl.isNullOrBlank()) {
                val targetFile = File(targetDir, "aptoide_${packageName}.apk")
                onProgress("Downloading from Aptoide repository (v$resolvedVer)...", 28)
                if (downloadFile(downloadUrl, targetFile, onProgress, 28, 48) && isApkValid(targetFile)) {
                    return@withContext normalizeApkBundle(targetFile, targetDir, "Aptoide (v$resolvedVer)", resolvedVer)
                }
            }

            null
        } catch (e: Exception) {
            android.util.Log.e("DownloadOrchestrator", "Aptoide resolution failed for $packageName", e)
            null
        }
    }

    /**
     * Checks whether a valid base APK is already present on device (installed, in cache, or in downloads).
     */
    fun hasLocalBaseApk(context: Context, packageName: String): Boolean {
        if (runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess) return true
        val cacheDir = File(context.filesDir, "base_apk_cache/$packageName")
        if (cacheDir.exists() && cacheDir.listFiles()?.any { isApkValid(it) } == true) return true
        val searchDirs = listOfNotNull(
            Downloads.dir(),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        )
        for (dir in searchDirs) {
            if (!dir.exists()) continue
            if (dir.listFiles()?.any { file ->
                val ext = file.extension.lowercase()
                val name = file.name.lowercase()
                (ext in setOf("apk", "xapk", "apkm", "apks")) &&
                !name.startsWith("patched_") &&
                !name.contains("_patched") &&
                name.contains(packageName.lowercase()) &&
                isApkValid(file)
            } == true) return true
        }
        return false
    }

    private fun resolveFromLocalDownloads(
        context: Context,
        packageName: String,
        appName: String,
        targetDir: File
    ): ResolvedApk? {
        return try {
            val searchDirs = listOfNotNull(
                Downloads.dir(),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            )

            for (dir in searchDirs) {
                if (!dir.exists()) continue
                val candidate = dir.listFiles()?.firstOrNull { file ->
                    val ext = file.extension.lowercase()
                    val name = file.name.lowercase()
                    (ext in setOf("apk", "xapk", "apkm", "apks")) &&
                    !name.startsWith("patched_") &&
                    !name.contains("_patched") &&
                    (
                        name.contains(packageName.lowercase()) ||
                        name.contains(appName.lowercase().replace(" ", ""))
                    ) &&
                    isApkValid(file, requireCleanBase = true)
                }

                if (candidate != null && candidate.exists()) {
                    val dest = File(targetDir, candidate.name)
                    candidate.copyTo(dest, overwrite = true)
                    if (isApkValid(dest, requireCleanBase = true)) {
                        return normalizeApkBundle(dest, targetDir, "Local Downloads (${dir.name})", "Local")
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Verifies that a file is a valid, uncorrupted Android APK or Split Bundle with EOCD and manifest.
     * When [requireCleanBase] is true, also rejects APKs that have already been modded (e.g. contains piko, revanced, etc.).
     */
    fun isApkValid(file: File, requireCleanBase: Boolean = false): Boolean {
        if (!file.exists() || file.length() < 50_000) return false
        val ext = file.extension.lowercase()
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                val hasManifest = if (ext in setOf("xapk", "apkm", "apks")) {
                    zip.entries().asSequence().any { it.name.endsWith(".apk", ignoreCase = true) }
                } else {
                    zip.getEntry("AndroidManifest.xml") != null
                }
                if (!hasManifest) return false
                if (requireCleanBase) {
                    val isAlreadyModded = zip.entries().asSequence().any { entry ->
                        val n = entry.name.lowercase()
                        n.startsWith("assets/piko/") ||
                        n.startsWith("assets/revanced/") ||
                        n.startsWith("assets/morphe/") ||
                        n.startsWith("assets/hushgram/") ||
                        n.contains("piko_") ||
                        n.contains("revanced")
                    }
                    if (isAlreadyModded) return false
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Normalizes a downloaded APK or Split Bundle (.xapk, .apkm, .apks).
     * Extracts and selects only device-matching architecture, locale, and DPI splits.
     */
    fun normalizeApkBundle(
        bundleFile: File,
        targetDir: File,
        provider: String,
        version: String
    ): ResolvedApk {
        val ext = bundleFile.extension.lowercase()
        if (ext == "apk") {
            return ResolvedApk(baseApk = bundleFile, splits = emptyList(), sourceProvider = provider, resolvedVersion = version)
        }

        // Handle split bundle (.xapk, .apkm, .apks)
        val splitsDir = File(targetDir, "splits_temp")
        splitsDir.mkdirs()

        val parts = Bundles.parts(com.orion.store.OrionApp.instance, bundleFile)
        if (parts.isNotEmpty()) {
            val base = parts.firstOrNull { it.name.lowercase().contains("base") } ?: parts.first()
            val splits = parts.filter { it.absolutePath != base.absolutePath }
            return ResolvedApk(baseApk = base, splits = splits, sourceProvider = "$provider (Normalized Splits)", resolvedVersion = version)
        }

        return ResolvedApk(baseApk = bundleFile, splits = emptyList(), sourceProvider = provider, resolvedVersion = version)
    }

    private fun downloadFile(
        url: String,
        dest: File,
        onProgress: (String, Int) -> Unit,
        startPct: Int,
        endPct: Int
    ): Boolean {
        return try {
            android.util.Log.d("DownloadOrchestrator", "downloadFile started: $url")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                .build()
            val resp = downloadClient.newCall(req).execute()
            val body = resp.body
            if (resp.isSuccessful && body != null) {
                dest.parentFile?.mkdirs()
                val tempFile = File(dest.parentFile, "${dest.name}.part")
                if (tempFile.exists()) tempFile.delete()

                val contentLength = body.contentLength()
                android.util.Log.d("DownloadOrchestrator", "HTTP ${resp.code}, contentLength: $contentLength")
                var totalBytesRead = 0L
                val buffer = ByteArray(65536)

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        var bytesRead: Int
                        var lastLoggedMb = -1L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            val currentMb = totalBytesRead / (1024 * 1024)
                            if (currentMb != lastLoggedMb) {
                                lastLoggedMb = currentMb
                                if (contentLength > 0) {
                                    val currentPct = startPct + ((totalBytesRead * (endPct - startPct)) / contentLength).toInt()
                                    onProgress("Downloading APK (${currentMb} / ${contentLength / (1024 * 1024)} MB)...", currentPct.coerceIn(startPct, endPct))
                                } else {
                                    onProgress("Downloading APK (${currentMb} MB)...", startPct + 5)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (contentLength > 0 && totalBytesRead < contentLength) {
                    android.util.Log.w("DownloadOrchestrator", "Incomplete download: got $totalBytesRead, expected $contentLength")
                    tempFile.delete()
                    return false
                }

                if (tempFile.exists() && tempFile.length() > 50_000) {
                    if (dest.exists()) dest.delete()
                    val renamed = tempFile.renameTo(dest)
                    val valid = isApkValid(dest)
                    android.util.Log.d("DownloadOrchestrator", "Saved $dest (${dest.length()} bytes), isValid: $valid")
                    return valid
                }
                tempFile.delete()
                false
            } else {
                android.util.Log.w("DownloadOrchestrator", "HTTP ${resp.code} for $url")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("DownloadOrchestrator", "Error downloading $url: ${e.message}", e)
            false
        }
    }
}
