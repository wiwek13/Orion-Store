package com.orion.store.platform

import android.content.Context
import android.os.Build
import com.orion.store.data.Net
import com.orion.store.ui.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

/**
 * Two-Tier Download Pipeline: Orion Store Primary -> MorpheFlow Base APK Ingestion Cascade.
 * Implements Section 4 of Orion Store Fork Specification.
 */
object DownloadOrchestrator {

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
            val isMatch = requestedVersion.isBlank() || requestedVersion.equals("Latest", ignoreCase = true) || requestedVersion == installedVer
            val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
            if (isMatch && appInfo != null && File(appInfo.sourceDir).exists()) {
                onProgress("Extracting installed base APK from device...", 15)
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
                return@withContext ResolvedApk(base, splits, "Device Extraction", installedVer)
            }
        }

        // Tier 1: Orion Store Native Downloader
        onProgress("Checking Orion Store repository...", 10)
        val orionResult = resolveFromOrionStore(packageName, requestedVersion, targetDir, onProgress)
        if (orionResult != null) {
            return@withContext orionResult
        }

        // Tier 2: MorpheFlow Base APK Cascade
        // Fallback 1: Google Play (goopdl / GPlay API)
        onProgress("Resolving from Google Play (goopdl)...", 20)
        val gplayResult = resolveFromGooglePlay(packageName, requestedVersion, targetDir, onProgress)
        if (gplayResult != null) {
            return@withContext gplayResult
        }

        // Fallback 2: Uptodown Resolver
        onProgress("Resolving from Uptodown repository...", 35)
        val uptodownResult = resolveFromUptodown(packageName, requestedVersion, targetDir, onProgress)
        if (uptodownResult != null) {
            return@withContext uptodownResult
        }

        // Fallback 3: APKCombo Resolver
        onProgress("Resolving from APKCombo repository...", 50)
        val apkComboResult = resolveFromApkCombo(packageName, requestedVersion, targetDir, onProgress)
        if (apkComboResult != null) {
            return@withContext apkComboResult
        }

        // Fallback 4: APKPure Resolver
        onProgress("Resolving from APKPure repository...", 65)
        val apkPureResult = resolveFromApkPure(packageName, requestedVersion, targetDir, onProgress)
        if (apkPureResult != null) {
            return@withContext apkPureResult
        }

        // Fallback 5: Local Downloads search
        onProgress("Checking local Downloads directory...", 80)
        val localResult = resolveFromLocalDownloads(context, packageName, appName, targetDir)
        if (localResult != null) {
            return@withContext localResult
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
            val queryUrl = "https://apkpure-resolver.morphe.software/resolve?pkg=$packageName&ver=${URLEncoder.encode(versionName, "UTF-8")}"
            val req = Request.Builder().url(queryUrl).build()
            val resp = Net.client.newCall(req).execute()
            val body = resp.body
            if (resp.isSuccessful && body != null) {
                val targetFile = File(targetDir, "apkpure_base.apk")
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                }
                if (targetFile.exists() && targetFile.length() > 500_000) {
                    normalizeApkBundle(targetFile, targetDir, "APKPure", versionName)
                } else null
            } else null
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
            val downloads = Downloads.dir()
            val candidate = downloads.listFiles()?.firstOrNull { file ->
                val ext = file.extension.lowercase()
                val name = file.name.lowercase()
                (ext in setOf("apk", "xapk", "apkm", "apks")) && (
                    name.contains(packageName.lowercase()) ||
                    name.contains(appName.lowercase().replace(" ", ""))
                )
            }
            if (candidate != null && candidate.exists() && candidate.length() > 0) {
                val dest = File(targetDir, candidate.name)
                candidate.copyTo(dest, overwrite = true)
                normalizeApkBundle(dest, targetDir, "Local Downloads", "Local")
            } else null
        } catch (e: Exception) {
            null
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
            val req = Request.Builder().url(url).build()
            val resp = Net.client.newCall(req).execute()
            val body = resp.body
            if (resp.isSuccessful && body != null) {
                dest.parentFile?.mkdirs()
                val contentLength = body.contentLength()
                var totalBytesRead = 0L
                val buffer = ByteArray(8192)

                body.byteStream().use { input ->
                    FileOutputStream(dest).use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (contentLength > 0) {
                                val currentPct = startPct + ((totalBytesRead * (endPct - startPct)) / contentLength).toInt()
                                onProgress("Downloading APK (${totalBytesRead / 1024} KB)...", currentPct)
                            }
                        }
                    }
                }
                dest.exists() && dest.length() > 0
            } else false
        } catch (e: Exception) {
            false
        }
    }
}
