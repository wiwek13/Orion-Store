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
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

/**
 * Two-Tier Download Pipeline: Orion Store Primary -> MorpheFlow Base APK Ingestion Cascade.
 * Implements Section 4 of Orion Store Fork Specification.
 */
object DownloadOrchestrator {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ResolvedApk(
        val baseApk: File,
        val splits: List<File> = emptyList(),
        val sourceProvider: String,
        val resolvedVersion: String
    )

    /**
     * Resolves and downloads base APK for [packageName] and [versionName] into [targetDir].
     */
    suspend fun resolveAndDownload(
        context: Context,
        packageName: String,
        appName: String,
        requestedVersion: String,
        targetDir: File,
        onProgress: (stage: String, percent: Int) -> Unit
    ): ResolvedApk? = withContext(Dispatchers.IO) {
        targetDir.mkdirs()

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
                    return@withContext ResolvedApk(base, splits, "Device Extraction", installedVer)
                }
            }
        }

        // Tier 1: Orion Store Native Downloader
        onProgress("Checking Orion Store repository...", 10)
        val orionResult = resolveFromOrionStore(packageName, requestedVersion, targetDir, onProgress)
        if (orionResult != null && isApkValid(orionResult.baseApk)) {
            return@withContext orionResult
        }

        // Tier 2: MorpheFlow Base APK Cascade
        // Fallback 1: GitHub Official Releases (for open-source apps like Brave Browser)
        val githubResult = resolveFromGitHubReleases(packageName, requestedVersion, targetDir, onProgress)
        if (githubResult != null && isApkValid(githubResult.baseApk)) {
            return@withContext githubResult
        }

        // Fallback 2: APKPure Direct CDN (reliable for Instagram, Brave, Proton, etc.)
        onProgress("Resolving from APKPure repository...", 20)
        val apkPureResult = resolveFromApkPure(packageName, requestedVersion, targetDir, onProgress)
        if (apkPureResult != null && isApkValid(apkPureResult.baseApk)) {
            return@withContext apkPureResult
        }

        // Fallback 2: Google Play (goopdl / GPlay API)
        onProgress("Resolving from Google Play (goopdl)...", 40)
        val gplayResult = resolveFromGooglePlay(packageName, requestedVersion, targetDir, onProgress)
        if (gplayResult != null && isApkValid(gplayResult.baseApk)) {
            return@withContext gplayResult
        }

        // Fallback 3: Uptodown Resolver
        onProgress("Resolving from Uptodown repository...", 55)
        val uptodownResult = resolveFromUptodown(packageName, requestedVersion, targetDir, onProgress)
        if (uptodownResult != null && isApkValid(uptodownResult.baseApk)) {
            return@withContext uptodownResult
        }

        // Fallback 4: APKCombo Resolver
        onProgress("Resolving from APKCombo repository...", 70)
        val apkComboResult = resolveFromApkCombo(packageName, requestedVersion, targetDir, onProgress)
        if (apkComboResult != null && isApkValid(apkComboResult.baseApk)) {
            return@withContext apkComboResult
        }

        // Fallback 5: Local Downloads search (Orion Downloads & /sdcard/Download)
        onProgress("Checking local Downloads directory...", 85)
        val localResult = resolveFromLocalDownloads(context, packageName, appName, targetDir)
        if (localResult != null && isApkValid(localResult.baseApk)) {
            return@withContext localResult
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
                if (isApkValid(base)) {
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
            val candidateUrl = app.variants.firstOrNull()?.url ?: app.downloadUrl
            val targetFile = File(targetDir, "orion_base.apk")
            if (downloadFile(candidateUrl, targetFile, onProgress, 12, 18)) {
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
            val repoSlug = when (packageName) {
                "com.brave.browser" -> "brave/brave-browser"
                else -> null
            } ?: return null

            onProgress("Checking GitHub releases for $repoSlug...", 22)
            val apiUrl = "https://api.github.com/repos/$repoSlug/releases/latest"
            val req = Request.Builder().url(apiUrl).header("User-Agent", "OrionStore").build()
            val resp = Net.client.newCall(req).execute()
            val body = resp.body?.string() ?: return null

            val jsonElement = json.parseToJsonElement(body)
            val assets = (jsonElement as? JsonObject)?.get("assets") as? JsonArray ?: return null
            val apkAsset = assets.firstOrNull { elem ->
                val name = (elem as? JsonObject)?.get("name")?.toString()?.trim('"')?.lowercase() ?: ""
                name.endsWith(".apk") && (name.contains("arm64") || name.contains("monoarm64"))
            } as? JsonObject ?: return null

            val dlUrl = apkAsset["browser_download_url"]?.toString()?.trim('"') ?: return null
            val targetFile = File(targetDir, "github_${packageName}.apk")
            if (downloadFile(dlUrl, targetFile, onProgress, 25, 45) && isApkValid(targetFile)) {
                return normalizeApkBundle(targetFile, targetDir, "GitHub Releases ($repoSlug)", versionName)
            }
            null
        } catch (e: Exception) {
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
            val resp = Net.client.newCall(req).execute()
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
            val resp = Net.client.newCall(req).execute()
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
            val resp = Net.client.newCall(req).execute()
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
            val targetFile = File(targetDir, "apkpure_base.apk")
            val targetXapk = File(targetDir, "apkpure_bundle.xapk")

            // 1. Try direct APK endpoint from APKPure CDN
            val apkUrl = "https://d.apkpure.net/b/APK/$packageName?version=latest"
            if (downloadFile(apkUrl, targetFile, onProgress, 20, 35) && isApkValid(targetFile)) {
                return normalizeApkBundle(targetFile, targetDir, "APKPure", versionName)
            }

            // 2. Try direct XAPK bundle endpoint if APK not available
            val xapkUrl = "https://d.apkpure.net/b/XAPK/$packageName?version=latest"
            if (downloadFile(xapkUrl, targetXapk, onProgress, 20, 35) && isApkValid(targetXapk)) {
                return normalizeApkBundle(targetXapk, targetDir, "APKPure (XAPK)", versionName)
            }

            null
        } catch (e: Exception) {
            null
        }
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
                    isApkValid(file)
                }

                if (candidate != null && candidate.exists()) {
                    val dest = File(targetDir, candidate.name)
                    candidate.copyTo(dest, overwrite = true)
                    if (isApkValid(dest)) {
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
     */
    fun isApkValid(file: File): Boolean {
        if (!file.exists() || file.length() < 50_000) return false
        val ext = file.extension.lowercase()
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                if (ext in setOf("xapk", "apkm", "apks")) {
                    zip.entries().asSequence().any { it.name.endsWith(".apk", ignoreCase = true) }
                } else {
                    zip.getEntry("AndroidManifest.xml") != null
                }
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
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                .build()
            val resp = Net.client.newCall(req).execute()
            val body = resp.body
            if (resp.isSuccessful && body != null) {
                dest.parentFile?.mkdirs()
                val tempFile = File(dest.parentFile, "${dest.name}.part")
                if (tempFile.exists()) tempFile.delete()

                val contentLength = body.contentLength()
                var totalBytesRead = 0L
                val buffer = ByteArray(32768)

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (contentLength > 0) {
                                val currentPct = startPct + ((totalBytesRead * (endPct - startPct)) / contentLength).toInt()
                                onProgress("Downloading APK (${totalBytesRead / (1024 * 1024)} MB)...", currentPct)
                            }
                        }
                        output.flush()
                    }
                }

                if (contentLength > 0 && totalBytesRead < contentLength) {
                    tempFile.delete()
                    return false
                }

                if (tempFile.exists() && tempFile.length() > 50_000) {
                    if (dest.exists()) dest.delete()
                    tempFile.renameTo(dest)
                    return isApkValid(dest)
                }
                tempFile.delete()
                false
            } else false
        } catch (e: Exception) {
            false
        }
    }
}
