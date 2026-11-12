package com.orion.store.platform

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
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

    data class ApkManifestMeta(
        val packageName: String,
        val versionName: String,
        val versionCode: Long
    )

    data class ResolvedApk(
        val baseApk: File,
        val splits: List<File> = emptyList(),
        val sourceProvider: String,
        val resolvedVersion: String,
        val resolvedVersionCode: Long = -1L
    )

    internal fun isSpecificVersionRequest(requestedVersion: String): Boolean {
        val clean = requestedVersion.trim().removePrefix("v").lowercase()
        return clean.isNotBlank() &&
            clean != "recommended" &&
            clean != "latest" &&
            clean != "local" &&
            clean != "saved"
    }

    internal fun versionsMatch(actualVersion: String, requestedVersion: String): Boolean {
        val a = actualVersion.trim().removePrefix("v").lowercase()
        val r = requestedVersion.trim().removePrefix("v").lowercase()
        if (a.isBlank() || r.isBlank()) return false
        return a == r
    }

    internal fun parseApkPureVersionCodeFromHtml(html: String, requestedVersion: String): Long? {
        val target = requestedVersion.trim().removePrefix("v")
        if (target.isBlank()) return null
        val regex = Regex(
            """data-dt-(?:version-code|versioncode)="(\d+)"[^>]{0,240}data-dt-version="([^"]+)"|data-dt-version="([^"]+)"[^>]{0,240}data-dt-(?:version-code|versioncode)="(\d+)"""",
            RegexOption.IGNORE_CASE
        )
        for (m in regex.findAll(html)) {
            val code = (m.groupValues[1].ifBlank { m.groupValues[4] }).toLongOrNull() ?: continue
            val ver = m.groupValues[2].ifBlank { m.groupValues[3] }
            if (versionsMatch(ver, target)) return code
        }
        return null
    }

    fun probeApkMetadata(apkFile: File): ApkManifestMeta? {
        if (!apkFile.exists()) return null
        return runCatching {
            val pm = com.orion.store.OrionApp.instance.packageManager
            val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
            val pkg = info.packageName.orEmpty()
            val verName = info.versionName.orEmpty()
            val verCode = PackageInfoCompat.getLongVersionCode(info)
            ApkManifestMeta(packageName = pkg, versionName = verName, versionCode = verCode)
        }.getOrNull()
    }

    fun getBaseCacheDir(context: Context, packageName: String): File {
        val dir = File(context.filesDir, "base_apk_cache/$packageName")
        dir.mkdirs()
        return dir
    }

    internal fun resolveFromBaseCache(
        context: Context,
        packageName: String,
        requestedVersion: String,
        targetDir: File
    ): ResolvedApk? {
        val cacheDir = getBaseCacheDir(context, packageName)
        if (!cacheDir.exists()) return null

        val cleanReq = requestedVersion.trim().removePrefix("v").lowercase()
        val isAnyVersion = cleanReq.isBlank() || cleanReq == "recommended" || cleanReq == "latest"
        val reqToken = cleanReq.replace(Regex("[^a-z0-9._-]"), "_")

        // 1. Prefer versioned split-aware bundle folders: base_apk_cache/<pkg>/bundle_<ver>/
        val bundleDirs = cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("bundle_") }?.sortedByDescending { it.lastModified() }.orEmpty()
        val orderedDirs = if (isAnyVersion) {
            bundleDirs
        } else {
            bundleDirs.filter { dir ->
                val verPart = dir.name.removePrefix("bundle_").lowercase()
                verPart == cleanReq || verPart == reqToken
            }
        }
        for (bDir in orderedDirs) {
            val baseFile = File(bDir, "base.apk")
            if (!baseFile.exists() || !isApkValid(baseFile, requireCleanBase = true)) {
                bDir.deleteRecursively()
                continue
            }
            val probed = probeApkMetadata(baseFile)
            val folderVer = bDir.name.removePrefix("bundle_")
            if (!isAnyVersion && probed != null && probed.versionName.isNotBlank() && !versionsMatch(probed.versionName, requestedVersion)) {
                android.util.Log.w(
                    "DownloadOrchestrator",
                    "Evicting poisoned cache ${bDir.name}: manifest v${probed.versionName} (${probed.versionCode}) != requested $requestedVersion"
                )
                bDir.deleteRecursively()
                continue
            }
            val splitFiles = bDir.listFiles()
                ?.filter { it.isFile && it.name != "base.apk" && it.extension.equals("apk", ignoreCase = true) && isApkValid(it) }
                ?.sortedBy { it.name }
                .orEmpty()
            val destBase = File(targetDir, "cached_base.apk")
            baseFile.copyTo(destBase, overwrite = true)
            val destSplits = splitFiles.mapIndexed { idx, sf ->
                File(targetDir, "cached_split_$idx.apk").also { sf.copyTo(it, overwrite = true) }
            }
            val realVer = probed?.versionName?.takeIf { it.isNotBlank() } ?: folderVer
            val realCode = probed?.versionCode ?: -1L
            return ResolvedApk(destBase, destSplits, "Cached Base APK (Reused)", realVer, realCode)
        }

        // 2. Fallback to legacy flat APK files in base_apk_cache/<pkg>/
        val candidates = cacheDir.listFiles()?.filter { file ->
            file.isFile && file.extension.equals("apk", ignoreCase = true) && isApkValid(file, requireCleanBase = true)
        } ?: return null

        if (candidates.isEmpty()) return null

        val chosen = if (isAnyVersion) {
            candidates.maxByOrNull { it.lastModified() }
        } else {
            candidates.firstOrNull { file ->
                val probed = probeApkMetadata(file)
                if (probed != null && probed.versionName.isNotBlank()) {
                    versionsMatch(probed.versionName, requestedVersion)
                } else {
                    val verToken = file.name.substringAfterLast("_").substringBeforeLast(".apk").lowercase()
                    verToken == cleanReq || verToken == reqToken
                }
            }
        } ?: return null

        val dest = File(targetDir, "cached_base.apk")
        chosen.copyTo(dest, overwrite = true)
        if (isApkValid(dest, requireCleanBase = true)) {
            val probed = probeApkMetadata(dest)
            val ver = probed?.versionName?.takeIf { it.isNotBlank() }
                ?: chosen.name.substringAfterLast("_").substringBeforeLast(".apk")
            val code = probed?.versionCode ?: -1L
            return ResolvedApk(dest, emptyList(), "Cached Base APK (Reused)", ver, code)
        }
        return null
    }

    fun cacheBaseApk(context: Context, packageName: String, version: String, baseFile: File) {
        cacheResolvedApk(
            context = context,
            packageName = packageName,
            resolved = ResolvedApk(baseFile, emptyList(), "Cache", version)
        )
    }

    fun cacheResolvedApk(context: Context, packageName: String, version: String, resolved: ResolvedApk) {
        val updated = if (resolved.resolvedVersion.isBlank()) resolved.copy(resolvedVersion = version) else resolved
        cacheResolvedApk(context, packageName, updated)
    }

    fun cacheResolvedApk(context: Context, packageName: String, resolved: ResolvedApk) {
        try {
            if (!isApkValid(resolved.baseApk, requireCleanBase = true)) return
            if (resolved.splits.isEmpty() && apkRequiresSplits(resolved.baseApk)) {
                android.util.Log.w("DownloadOrchestrator", "Refusing to cache split-requiring base APK without companion splits for $packageName")
                return
            }

            // Always key cache by true manifest versionName so a mismatched APK never poisons another version's cache
            val probed = probeApkMetadata(resolved.baseApk)
            val authoritativeVer = probed?.versionName?.takeIf { it.isNotBlank() } ?: resolved.resolvedVersion
            val cacheDir = getBaseCacheDir(context, packageName)
            val cleanVer = authoritativeVer.ifBlank { "latest" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val bundleDir = File(cacheDir, "bundle_$cleanVer")
            if (bundleDir.exists()) bundleDir.deleteRecursively()
            bundleDir.mkdirs()

            resolved.baseApk.copyTo(File(bundleDir, "base.apk"), overwrite = true)
            resolved.splits.forEachIndexed { idx, sf ->
                if (sf.exists() && sf.length() > 0) {
                    sf.copyTo(File(bundleDir, "split_$idx.apk"), overwrite = true)
                }
            }

            // Automatically clean older base APKs / bundles for this package
            PatchStorageCleaner.cleanOlderBaseApks(context, packageName, bundleDir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isCompletePackage(resolved: ResolvedApk?): Boolean {
        if (resolved == null) return false
        if (!isApkValid(resolved.baseApk, requireCleanBase = true)) return false
        if (resolved.splits.isEmpty() && apkRequiresSplits(resolved.baseApk)) {
            android.util.Log.w("DownloadOrchestrator", "Incomplete package: base requires splits (${resolved.baseApk.name}) but splits list is empty")
            if (resolved.baseApk.parentFile?.name?.startsWith("bundle_") == true) {
                resolved.baseApk.parentFile?.deleteRecursively()
            }
            return false
        }
        return true
    }

    /**
     * Resolves and downloads base APK for [packageName] and [requestedVersion] into [targetDir].
     * Enforces strict two-pass version matching against actual APK manifest metadata so a
     * mismatched "latest" build never silently poisons the cache or runs against version-locked patches.
     */
    suspend fun resolveAndDownload(
        context: Context,
        packageName: String,
        appName: String,
        requestedVersion: String,
        sourcePath: String? = null,
        targetDir: File,
        targetVersionCode: Long? = null,
        allowNearestFallback: Boolean = false,
        onProgress: (stage: String, percent: Int) -> Unit
    ): ResolvedApk? = withContext(Dispatchers.IO) {
        targetDir.mkdirs()

        val isSpecificVer = isSpecificVersionRequest(requestedVersion)
        var nearestFallback: ResolvedApk? = null

        fun considerCandidate(candidate: ResolvedApk?): ResolvedApk? {
            if (!isCompletePackage(candidate)) return null
            val resolved = candidate!!
            if (!isSpecificVer || versionsMatch(resolved.resolvedVersion, requestedVersion)) {
                return resolved
            }
            android.util.Log.w(
                "DownloadOrchestrator",
                "Skipping mismatched version from ${resolved.sourceProvider}: got v${resolved.resolvedVersion} (${resolved.resolvedVersionCode}), wanted v$requestedVersion"
            )
            if (allowNearestFallback && nearestFallback == null) {
                nearestFallback = resolved
            }
            return null
        }

        // Priority 0: User-provided local APK from device storage
        if (!sourcePath.isNullOrBlank()) {
            val src = File(sourcePath)
            if (src.exists() && isApkValid(src)) {
                onProgress("Using user-provided local APK...", 10)
                val resolved = normalizeApkBundle(src, targetDir, "User Provided Local APK", "Local")
                if (isCompletePackage(resolved)) {
                    cacheResolvedApk(context, packageName, resolved)
                    return@withContext resolved
                }
            }
        }

        // Step 0: Check untouched Download/OrionStore/<App>/untouched/ folder FIRST
        val untouchedDir = runCatching { OrionStorageManager.getUntouchedDir(appName, packageName) }.getOrNull()
        if (untouchedDir != null && untouchedDir.exists()) {
            val cleanReqTag = requestedVersion.trim().removePrefix("v").lowercase()
            val untouchedApk = untouchedDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in setOf("apk", "xapk", "apkm", "apks") && isApkValid(it, requireCleanBase = true) }
                ?.firstOrNull { f ->
                    if (!isSpecificVer) true else f.name.lowercase().contains(cleanReqTag)
                }
            if (untouchedApk != null) {
                val quickMeta = if (untouchedApk.extension.equals("apk", true)) probeApkMetadata(untouchedApk) else null
                val matchesManifest = !isSpecificVer || quickMeta == null || quickMeta.versionName.isBlank() ||
                    versionsMatch(quickMeta.versionName, requestedVersion)
                if (matchesManifest) {
                    val savedSplits = File(untouchedDir, "splits").listFiles()
                        ?.filter { it.isFile && it.extension.equals("apk", true) && isApkValid(it) }
                        ?.sortedBy { it.name }
                        .orEmpty()
                    onProgress("Using saved untouched APK from OrionStore/${untouchedDir.parentFile?.name}...", 10)
                    val dest = File(targetDir, untouchedApk.name)
                    untouchedApk.copyTo(dest, overwrite = true)
                    val resolved = if (untouchedApk.extension.equals("apk", true) && savedSplits.isNotEmpty()) {
                        val meta = probeApkMetadata(dest)
                        ResolvedApk(
                            baseApk = dest,
                            splits = savedSplits.mapIndexed { idx, sf ->
                                File(targetDir, "untouched_split_$idx.apk").also { sf.copyTo(it, overwrite = true) }
                            },
                            sourceProvider = "OrionStore Untouched Folder",
                            resolvedVersion = meta?.versionName?.takeIf { it.isNotBlank() } ?: requestedVersion.ifBlank { "Saved" },
                            resolvedVersionCode = meta?.versionCode ?: -1L
                        )
                    } else {
                        normalizeApkBundle(dest, targetDir, "OrionStore Untouched Folder", requestedVersion.ifBlank { "Saved" })
                    }
                    considerCandidate(resolved)?.let { return@withContext it }
                } else {
                    android.util.Log.w(
                        "DownloadOrchestrator",
                        "Ignoring poisoned untouched file ${untouchedApk.name}: actual manifest v${quickMeta?.versionName} != requested v$requestedVersion"
                    )
                }
            }
        }

        // Step 0b: Check if currently installed app on device is UNPATCHED (not in PatchedAppStore & not Orion-signed) and matches requested version
        val pm = context.packageManager
        val isTrackedPatched = runCatching {
            com.orion.store.morphe.store.PatchedAppStore.apps.value.any { it.packageName.equals(packageName, ignoreCase = true) }
        }.getOrDefault(false)
        val installedPkgInfo = if (!isTrackedPatched) runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull() else null
        if (installedPkgInfo != null && !isSignedByMorpheKeystore(pm, packageName)) {
            val installedVer = installedPkgInfo.versionName ?: ""
            val installedCode = PackageInfoCompat.getLongVersionCode(installedPkgInfo)
            val isMatch = !isSpecificVer || versionsMatch(installedVer, requestedVersion)
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
                val extracted = ResolvedApk(base, splits, "Device Extraction", installedVer, installedCode)
                if (isCompletePackage(extracted)) {
                    cacheResolvedApk(context, packageName, extracted)
                    return@withContext extracted
                }
            }
        }

        // Step 1: Check persistent base APK cache (DO NOT REDOWNLOAD if already downloaded & version matches!)
        onProgress("Checking local APK cache...", 8)
        considerCandidate(resolveFromBaseCache(context, packageName, requestedVersion, targetDir))?.let { cachedResult ->
            onProgress("Reusing already downloaded base APK...", 20)
            return@withContext cachedResult
        }

        // Step 2: Check local Downloads directory before hitting network
        considerCandidate(resolveFromLocalDownloads(context, packageName, appName, requestedVersion, targetDir))?.let { localResult ->
            onProgress("Using APK found in Downloads...", 20)
            cacheResolvedApk(context, packageName, localResult)
            return@withContext localResult
        }

        // Tier 1: Orion Store Native Downloader
        onProgress("Checking Orion Store repository...", 12)
        considerCandidate(resolveFromOrionStore(packageName, requestedVersion, targetDir, onProgress))?.let { orionResult ->
            cacheResolvedApk(context, packageName, orionResult)
            return@withContext orionResult
        }

        // Tier 2: MorpheFlow Base APK Cascade
        // Fallback 1: GitHub Official Releases (for open-source apps like Brave Browser)
        considerCandidate(resolveFromGitHubReleases(packageName, requestedVersion, targetDir, onProgress))?.let { githubResult ->
            cacheResolvedApk(context, packageName, githubResult)
            return@withContext githubResult
        }

        // Fallback 2: Google Play (goopdl / GPlay API)
        onProgress("Resolving from Google Play (goopdl)...", 25)
        considerCandidate(resolveFromGooglePlay(packageName, requestedVersion, targetDir, onProgress))?.let { gplayResult ->
            cacheResolvedApk(context, packageName, gplayResult)
            return@withContext gplayResult
        }

        // Tier 2b: APKMirror Resolver
        val apkmirrorResult = ApkMirrorResolver.resolveAndDownload(
            packageName = packageName,
            appName = appName,
            requestedVersion = requestedVersion,
            targetDir = targetDir,
            onProgress = onProgress
        )
        considerCandidate(apkmirrorResult)?.let { matched ->
            cacheResolvedApk(context, packageName, matched)
            return@withContext matched
        }

        // Fallback 3: APKPure Direct CDN (resolves versionCode generically via /versions for exact builds!)
        onProgress("Resolving from APKPure repository...", 35)
        val apkPureResult = resolveFromApkPure(
            packageName = packageName,
            versionName = requestedVersion,
            targetDir = targetDir,
            onProgress = onProgress,
            requestedVersionCode = targetVersionCode,
            allowLatestFallback = !isSpecificVer
        )
        considerCandidate(apkPureResult)?.let { matched ->
            cacheResolvedApk(context, packageName, matched)
            return@withContext matched
        }

        // Fallback 4: Aptoide Public API
        onProgress("Resolving from Aptoide repository...", 45)
        val aptoideResult = resolveFromAptoide(
            packageName = packageName,
            versionName = requestedVersion,
            targetDir = targetDir,
            onProgress = onProgress,
            allowLatestFallback = !isSpecificVer
        )
        considerCandidate(aptoideResult)?.let { matched ->
            cacheResolvedApk(context, packageName, matched)
            return@withContext matched
        }

        // Fallback 5: Uptodown Resolver
        onProgress("Resolving from Uptodown repository...", 60)
        considerCandidate(resolveFromUptodown(packageName, requestedVersion, targetDir, onProgress))?.let { matched ->
            cacheResolvedApk(context, packageName, matched)
            return@withContext matched
        }

        // Fallback 6: APKCombo Resolver
        onProgress("Resolving from APKCombo repository...", 75)
        considerCandidate(resolveFromApkCombo(packageName, requestedVersion, targetDir, onProgress))?.let { matched ->
            cacheResolvedApk(context, packageName, matched)
            return@withContext matched
        }

        // Pass 2: Nearest-version fallback (ONLY when user opted in or version wasn't pinned/specific)
        if (allowNearestFallback) {
            nearestFallback?.let { fb ->
                if (isCompletePackage(fb)) {
                    cacheResolvedApk(context, packageName, fb)
                    return@withContext fb
                }
            }
            if (isSpecificVer) {
                val latestPure = resolveFromApkPure(
                    packageName = packageName,
                    versionName = "latest",
                    targetDir = targetDir,
                    onProgress = onProgress,
                    requestedVersionCode = null,
                    allowLatestFallback = true
                )
                if (isCompletePackage(latestPure)) {
                    cacheResolvedApk(context, packageName, latestPure!!)
                    return@withContext latestPure
                }
            }
            if (installedPkgInfo != null) {
                val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                if (appInfo != null && File(appInfo.sourceDir).exists()) {
                    val installedVer = installedPkgInfo.versionName ?: ""
                    val installedCode = PackageInfoCompat.getLongVersionCode(installedPkgInfo)
                    onProgress("Using installed nearest version (v$installedVer)...", 90)
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
                    val extracted = ResolvedApk(base, splits, "Device Extraction (Nearest v$installedVer)", installedVer, installedCode)
                    if (isCompletePackage(extracted)) {
                        return@withContext extracted
                    }
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

    internal fun getKnownGitHubRepo(packageName: String): String? {
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

        return knownRepos[packageName] ?: run {
            val app = Store.data.value.apps.find { it.packageName == packageName || it.id == packageName }
            val rUrl = app?.repoUrl
            if (rUrl != null && rUrl.contains("github.com/")) {
                rUrl.substringAfter("github.com/").trimEnd('/').removeSuffix(".git").takeIf { it.contains("/") }
            } else null
        }
    }

    internal fun selectGitHubApkAsset(assets: JsonArray): JsonObject? {
        return (assets.firstOrNull { elem ->
            val name = (elem as? JsonObject)?.get("name")?.toString()?.trim('"')?.lowercase() ?: ""
            name.endsWith(".apk") && (name.contains("arm64") || name.contains("monoarm64") || name.contains("universal"))
        } ?: assets.firstOrNull { elem ->
            val name = (elem as? JsonObject)?.get("name")?.toString()?.trim('"')?.lowercase() ?: ""
            name.endsWith(".apk") && !name.contains("x86")
        }) as? JsonObject
    }

    private suspend fun resolveFromGitHubReleases(
        packageName: String,
        versionName: String,
        targetDir: File,
        onProgress: (String, Int) -> Unit
    ): ResolvedApk? {
        return try {
            val repoSlug = getKnownGitHubRepo(packageName) ?: return null

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
            val apkAsset = selectGitHubApkAsset(assets) ?: return null

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

    private fun resolveApkPureVersionCode(packageName: String, requestedVersion: String): Long? {
        return runCatching {
            val url = "https://apkpure.net/app/${URLEncoder.encode(packageName, "UTF-8")}/versions"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                .build()
            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val html = resp.body.string()
                parseApkPureVersionCodeFromHtml(html, requestedVersion)
            }
        }.getOrNull()
    }

    private suspend fun resolveFromApkPure(
        packageName: String,
        versionName: String,
        targetDir: File,
        onProgress: (String, Int) -> Unit,
        requestedVersionCode: Long? = null,
        allowLatestFallback: Boolean = false
    ): ResolvedApk? {
        return try {
            val targetFile = File(targetDir, "apkpure_${packageName}.apk")
            val targetXapk = File(targetDir, "apkpure_${packageName}.xapk")
            val cleanVer = versionName.trim().removePrefix("v")
            val isSpecificVer = isSpecificVersionRequest(cleanVer)

            // 1. Resolve exact versionCode (from .mpp metadata or APKPure /versions index) for reliable exact downloads
            if (isSpecificVer) {
                val resolvedCode = requestedVersionCode?.takeIf { it > 0L }
                    ?: resolveApkPureVersionCode(packageName, cleanVer)
                if (resolvedCode != null) {
                    val codeXapk = "https://d.apkpure.net/b/XAPK/$packageName?versionCode=$resolvedCode"
                    if (downloadFile(codeXapk, targetXapk, onProgress, 20, 35) && isApkValid(targetXapk, requireCleanBase = true)) {
                        val norm = normalizeApkBundle(targetXapk, targetDir, "APKPure (XAPK v$cleanVer #$resolvedCode)", cleanVer)
                        if (isCompletePackage(norm) && versionsMatch(norm.resolvedVersion, cleanVer)) return norm
                        targetXapk.delete()
                    }
                    val codeApk = "https://d.apkpure.net/b/APK/$packageName?versionCode=$resolvedCode"
                    if (downloadFile(codeApk, targetFile, onProgress, 20, 35) && isApkValid(targetFile, requireCleanBase = true)) {
                        val norm = normalizeApkBundle(targetFile, targetDir, "APKPure (v$cleanVer #$resolvedCode)", cleanVer)
                        if (isCompletePackage(norm) && versionsMatch(norm.resolvedVersion, cleanVer)) return norm
                        targetFile.delete()
                    }
                }

                val specApk = "https://d.apkpure.net/b/APK/$packageName?version=$cleanVer"
                if (downloadFile(specApk, targetFile, onProgress, 20, 35) && isApkValid(targetFile, requireCleanBase = true)) {
                    val norm = normalizeApkBundle(targetFile, targetDir, "APKPure (v$cleanVer)", cleanVer)
                    if (isCompletePackage(norm) && versionsMatch(norm.resolvedVersion, cleanVer)) return norm
                    targetFile.delete()
                }
                val specXapk = "https://d.apkpure.net/b/XAPK/$packageName?version=$cleanVer"
                if (downloadFile(specXapk, targetXapk, onProgress, 20, 35) && isApkValid(targetXapk, requireCleanBase = true)) {
                    val norm = normalizeApkBundle(targetXapk, targetDir, "APKPure (XAPK v$cleanVer)", cleanVer)
                    if (isCompletePackage(norm) && versionsMatch(norm.resolvedVersion, cleanVer)) return norm
                    targetXapk.delete()
                }
            }

            if (isSpecificVer && !allowLatestFallback) {
                return null
            }

            // 2. Try latest APK/XAPK endpoints only when latest was requested or nearest fallback is allowed
            val apkUrl = "https://d.apkpure.net/b/APK/$packageName?version=latest"
            if (downloadFile(apkUrl, targetFile, onProgress, 20, 35) && isApkValid(targetFile, requireCleanBase = true)) {
                val norm = normalizeApkBundle(targetFile, targetDir, "APKPure", versionName.ifBlank { "Latest" })
                if (isCompletePackage(norm)) return norm
                targetFile.delete()
            }

            val xapkUrl = "https://d.apkpure.net/b/XAPK/$packageName?version=latest"
            if (downloadFile(xapkUrl, targetXapk, onProgress, 20, 35) && isApkValid(targetXapk, requireCleanBase = true)) {
                val norm = normalizeApkBundle(targetXapk, targetDir, "APKPure (XAPK)", versionName.ifBlank { "Latest" })
                if (isCompletePackage(norm)) return norm
                targetXapk.delete()
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
        onProgress: (String, Int) -> Unit,
        allowLatestFallback: Boolean = false
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

                if (downloadUrl.isNullOrBlank() && (!isSpecificVer || allowLatestFallback) && !latestPath.isNullOrBlank()) {
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
                        val exactItem = matchedItems.firstOrNull {
                            val v = it["file"]?.jsonObject?.get("vername")?.jsonPrimitive?.contentOrNull ?: ""
                            versionsMatch(v, cleanVer) || v.startsWith(cleanVer, ignoreCase = true)
                        }
                        exactItem ?: if (allowLatestFallback) matchedItems.firstOrNull() else null
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
        if (cacheDir.exists()) {
            val hasValidBundle = cacheDir.listFiles()?.any { entry ->
                if (entry.isDirectory && entry.name.startsWith("bundle_")) {
                    val base = File(entry, "base.apk")
                    val splits = File(entry, "splits").listFiles()?.filter { it.isFile && it.extension.equals("apk", true) }.orEmpty()
                    base.exists() && isApkValid(base, requireCleanBase = true) && (splits.isNotEmpty() || !apkRequiresSplits(base))
                } else {
                    entry.isFile && isApkValid(entry, requireCleanBase = true) && !apkRequiresSplits(entry)
                }
            } == true
            if (hasValidBundle) return true
        }
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
                isApkValid(file) &&
                (ext != "apk" || !apkRequiresSplits(file))
            } == true) return true
        }
        return false
    }

    private fun isSignedByMorpheKeystore(pm: android.content.pm.PackageManager, packageName: String): Boolean {
        return runCatching {
            val keystoreDigests = com.orion.store.morphe.store.KeyStoreManager.getKeySha256Fingerprints()
            if (keystoreDigests.isEmpty()) return false
            val pkgInfo = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            }
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                pkgInfo.signingInfo?.apkContentsSigners ?: pkgInfo.signingInfo?.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures
            } ?: return false
            val md = java.security.MessageDigest.getInstance("SHA-256")
            sigs.any { sig ->
                val hex = md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
                hex in keystoreDigests
            }
        }.getOrDefault(false)
    }

    private fun resolveFromLocalDownloads(
        context: Context,
        packageName: String,
        appName: String,
        requestedVersion: String,
        targetDir: File
    ): ResolvedApk? {
        return try {
            val searchDirs = listOfNotNull(
                Downloads.dir(),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            )
            val isSpecificVer = isSpecificVersionRequest(requestedVersion)
            val cleanReq = requestedVersion.trim().removePrefix("v").lowercase()

            for (dir in searchDirs) {
                if (!dir.exists()) continue
                val matching = dir.listFiles()?.filter { file ->
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
                }.orEmpty().sortedByDescending { f ->
                    if (isSpecificVer && f.name.lowercase().contains(cleanReq)) 1 else 0
                }

                for (candidate in matching) {
                    val dest = File(targetDir, candidate.name)
                    candidate.copyTo(dest, overwrite = true)
                    if (isApkValid(dest, requireCleanBase = true)) {
                        val norm = normalizeApkBundle(dest, targetDir, "Local Downloads (${dir.name})", "Local")
                        if (!isSpecificVer || versionsMatch(norm.resolvedVersion, requestedVersion)) {
                            return norm
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks whether [apkFile]'s binary AndroidManifest.xml declares split requirements
     * (`android:requiredSplitTypes` non-empty or `android:isSplitRequired="true"`).
     * Returns false for non-split APKs, bundles, or synthetic test fixtures.
     */
    fun apkRequiresSplits(apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() < 100) return false
        return try {
            java.util.zip.ZipFile(apkFile).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return false
                val bytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                axmlRequiresSplits(bytes)
            }
        } catch (e: Exception) {
            false
        }
    }

    internal fun axmlRequiresSplits(bytes: ByteArray): Boolean {
        if (bytes.size < 36) return false
        return try {
            val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val rootType = buf.getShort(0).toInt() and 0xFFFF
            if (rootType != 0x0003) return false // Not valid AXML

            var stringPoolStart = -1
            var stringPoolIsUtf8 = false
            var stringOffsets = IntArray(0)
            var poolDataStart = 0

            fun getString(idx: Int): String? {
                if (stringPoolStart < 0 || idx < 0 || idx >= stringOffsets.size) return null
                var pos = poolDataStart + stringOffsets[idx]
                if (pos < 0 || pos + 2 > bytes.size) return null
                if (stringPoolIsUtf8) {
                    var u16Len = bytes[pos].toInt() and 0xFF
                    pos++
                    if ((u16Len and 0x80) != 0) pos++
                    if (pos >= bytes.size) return null
                    var u8Len = bytes[pos].toInt() and 0xFF
                    pos++
                    if ((u8Len and 0x80) != 0 && pos < bytes.size) {
                        u8Len = ((u8Len and 0x7F) shl 8) or (bytes[pos].toInt() and 0xFF)
                        pos++
                    }
                    if (pos + u8Len > bytes.size) return null
                    return String(bytes, pos, u8Len, Charsets.UTF_8)
                } else {
                    var u16Len = buf.getShort(pos).toInt() and 0xFFFF
                    pos += 2
                    if ((u16Len and 0x8000) != 0 && pos + 2 <= bytes.size) {
                        u16Len = ((u16Len and 0x7FFF) shl 16) or (buf.getShort(pos).toInt() and 0xFFFF)
                        pos += 2
                    }
                    val byteLen = u16Len * 2
                    if (pos + byteLen > bytes.size) return null
                    return String(bytes, pos, byteLen, Charsets.UTF_16LE)
                }
            }

            var pos = 8
            while (pos + 8 <= bytes.size) {
                val ctype = buf.getShort(pos).toInt() and 0xFFFF
                val csize = buf.getInt(pos + 4)
                if (csize < 8 || pos + csize > bytes.size) break

                if (ctype == 0x0001 && pos + 28 <= bytes.size) {
                    stringPoolStart = pos
                    val strCount = buf.getInt(pos + 8)
                    val flags = buf.getInt(pos + 16)
                    val stringsStart = buf.getInt(pos + 20)
                    stringPoolIsUtf8 = (flags and 0x100) != 0
                    poolDataStart = pos + stringsStart
                    if (strCount in 0..200_000 && pos + 28 + strCount * 4 <= bytes.size) {
                        stringOffsets = IntArray(strCount) { i -> buf.getInt(pos + 28 + i * 4) }
                    }
                } else if (ctype == 0x0102 && pos + 36 <= bytes.size) {
                    // Inspect first START_ELEMENT (<manifest>)
                    val attrStart = buf.getShort(pos + 24).toInt() and 0xFFFF
                    val attrSize = buf.getShort(pos + 26).toInt() and 0xFFFF
                    val attrCount = buf.getShort(pos + 28).toInt() and 0xFFFF
                    val attrsBase = pos + 16 + attrStart
                    if (attrSize >= 20 && attrsBase + attrCount * attrSize <= bytes.size) {
                        for (i in 0 until attrCount) {
                            val ap = attrsBase + i * attrSize
                            val nameIdx = buf.getInt(ap + 4)
                            val dataType = bytes[ap + 15].toInt() and 0xFF
                            val data = buf.getInt(ap + 16)
                            val attrName = getString(nameIdx)
                            if (attrName == "requiredSplitTypes" && dataType == 3) {
                                if (!getString(data).isNullOrBlank()) return true
                            }
                            if (attrName == "isSplitRequired" && data != 0) {
                                return true
                            }
                        }
                    }
                    break
                }
                pos += csize
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verifies that a file is a valid, uncorrupted Android APK or Split Bundle with EOCD and manifest.
     * When [requireCleanBase] is true, also rejects APKs that have already been modded (e.g. contains piko, revanced, etc.).
     */
    fun isApkValid(file: File, requireCleanBase: Boolean = false): Boolean {
        if (!file.exists() || file.length() < 50_000) return false
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                val hasRootManifest = zip.getEntry("AndroidManifest.xml") != null
                val hasSplitApks = !hasRootManifest && zip.entries().asSequence().any { it.name.endsWith(".apk", ignoreCase = true) }
                if (!hasRootManifest && !hasSplitApks) return false

                if (requireCleanBase && hasRootManifest) {
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
        val hasRootManifest = runCatching {
            java.util.zip.ZipFile(bundleFile).use { it.getEntry("AndroidManifest.xml") != null }
        }.getOrDefault(bundleFile.extension.equals("apk", ignoreCase = true))

        fun readRealMeta(apk: File, fallback: String): Pair<String, Long> {
            val clean = fallback.trim()
            val probed = probeApkMetadata(apk)
            val realVer = probed?.versionName?.takeIf { it.isNotBlank() } ?: clean.ifBlank { "Latest" }
            val realCode = probed?.versionCode ?: -1L
            return realVer to realCode
        }

        if (hasRootManifest) {
            val (realVer, realCode) = readRealMeta(bundleFile, version)
            return ResolvedApk(
                baseApk = bundleFile,
                splits = emptyList(),
                sourceProvider = provider,
                resolvedVersion = realVer,
                resolvedVersionCode = realCode
            )
        }

        // Handle split bundle (.xapk, .apkm, .apks, or an XAPK served with .apk extension)
        val parts = runCatching {
            Bundles.parts(com.orion.store.OrionApp.instance, bundleFile)
        }.getOrDefault(emptyList())
        if (parts.isNotEmpty()) {
            val base = parts.firstOrNull {
                val n = it.name.lowercase()
                n == "base.apk" || (!n.contains("split") && !n.contains("config.") && !n.contains("config_"))
            } ?: parts.first()
            val splits = parts.filter { it.absolutePath != base.absolutePath }
            val (realVer, realCode) = readRealMeta(base, version)
            return ResolvedApk(
                baseApk = base,
                splits = splits,
                sourceProvider = "$provider (Normalized Splits)",
                resolvedVersion = realVer,
                resolvedVersionCode = realCode
            )
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
