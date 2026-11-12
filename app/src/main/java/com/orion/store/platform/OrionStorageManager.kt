package com.orion.store.platform

import android.os.Environment
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Metadata for tracking applied patches on disk inside Download/OrionStore/<appName>/applied_patches.json
 */
@Serializable
data class AppPatchManifest(
    val packageName: String,
    val appName: String,
    val version: String,
    val bundleName: String = "",
    val bundleRepoUrl: String = "",
    val appliedPatches: List<String> = emptyList(),
    val patchOptions: Map<String, String> = emptyMap(),
    val patchedApkFileName: String? = null,
    val untouchedApkFileName: String? = null,
    val lastPatchedAt: Long = System.currentTimeMillis()
)

/**
 * Unified storage manager for Orion Store.
 * Organizes files app-wise under:
 *   /storage/emulated/0/Download/OrionStore/<appName>/
 *     ├── untouched/
 *     │   └── <appName>_v<version>_untouched.apk
 *     ├── applied_patches.json
 *     └── Patched_<appName>_v<version>.apk (or .apks)
 */
object OrionStorageManager {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** Root directory: /storage/emulated/0/Download/OrionStore */
    fun getOrionStoreRootDir(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(publicDownloads, "OrionStore").apply { mkdirs() }
    }

    fun sanitizeAppName(appName: String, packageName: String): String {
        val clean = appName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
        return clean.ifEmpty {
            packageName.substringAfterLast(".").ifEmpty { packageName }
        }
    }

    /** App-wise folder: /storage/emulated/0/Download/OrionStore/<appName> */
    fun getAppDir(appName: String, packageName: String): File {
        val safeName = sanitizeAppName(appName, packageName)
        return File(getOrionStoreRootDir(), safeName).apply { mkdirs() }
    }

    /** Subdirectory for untouched original APK: /storage/emulated/0/Download/OrionStore/<appName>/untouched */
    fun getUntouchedDir(appName: String, packageName: String): File {
        return File(getAppDir(appName, packageName), "untouched").apply { mkdirs() }
    }

    /** File tracking applied patches: /storage/emulated/0/Download/OrionStore/<appName>/applied_patches.json */
    fun getAppliedPatchesFile(appName: String, packageName: String): File {
        return File(getAppDir(appName, packageName), "applied_patches.json")
    }

    /** Save untouched APK into the app's untouched/ folder */
    fun saveUntouchedApk(
        appName: String,
        packageName: String,
        version: String,
        baseApk: File,
        splits: List<File> = emptyList()
    ): File? {
        return try {
            val untouchedDir = getUntouchedDir(appName, packageName)
            val safeName = sanitizeAppName(appName, packageName)
            val cleanVer = version.ifEmpty { "1.0" }.replace(" ", "_")
            val dest = File(untouchedDir, "${safeName}_v${cleanVer}_untouched.apk")
            if (!dest.exists() || dest.length() != baseApk.length()) {
                baseApk.copyTo(dest, overwrite = true)
            }
            if (splits.isNotEmpty()) {
                val splitsDir = File(untouchedDir, "splits").apply { mkdirs() }
                splits.forEach { split ->
                    val splitDest = File(splitsDir, split.name)
                    if (!splitDest.exists() || splitDest.length() != split.length()) {
                        split.copyTo(splitDest, overwrite = true)
                    }
                }
            }
            dest
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Save or update applied patches manifest */
    fun saveAppliedPatches(manifest: AppPatchManifest) {
        try {
            val file = getAppliedPatchesFile(manifest.appName, manifest.packageName)
            file.writeText(json.encodeToString(manifest))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Read applied patches manifest from the app's folder */
    fun readAppliedPatches(appName: String, packageName: String): AppPatchManifest? {
        return try {
            val file = getAppliedPatchesFile(appName, packageName)
            if (file.exists() && file.length() > 0) {
                json.decodeFromString<AppPatchManifest>(file.readText())
            } else {
                // Fallback: check if an app folder named after packageName exists
                val pkgFile = File(File(getOrionStoreRootDir(), packageName), "applied_patches.json")
                if (pkgFile.exists() && pkgFile.length() > 0) {
                    json.decodeFromString<AppPatchManifest>(pkgFile.readText())
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Scan all app folders under Download/OrionStore/ to find applied patches and patched APKs */
    fun scanAllAppFolders(): List<AppPatchManifest> {
        val root = getOrionStoreRootDir()
        val results = mutableListOf<AppPatchManifest>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { appDir ->
            val manifestFile = File(appDir, "applied_patches.json")
            if (manifestFile.exists() && manifestFile.length() > 0) {
                runCatching {
                    results.add(json.decodeFromString<AppPatchManifest>(manifestFile.readText()))
                }
            }
        }
        return results
    }

    /** Destination file for a patched APK inside the app's folder */
    fun getPatchedApkFile(appName: String, packageName: String, version: String, hasSplits: Boolean): File {
        val appDir = getAppDir(appName, packageName)
        val safeName = sanitizeAppName(appName, packageName)
        val cleanVer = version.ifEmpty { "1.0" }.replace(" ", "_")
        val ext = if (hasSplits) "apks" else "apk"
        return File(appDir, "Patched_${safeName}_v${cleanVer}.$ext")
    }

    /**
     * Find patched APK for this app.
     * Looks in Download/OrionStore/<appName>/ first, with migration fallback to older locations.
     */
    fun findPatchedApkForApp(appName: String, packageName: String): File? {
        val appDir = getAppDir(appName, packageName)
        val safeName = sanitizeAppName(appName, packageName)

        // 1. Direct search inside appDir
        val inAppDir = appDir.listFiles()?.firstOrNull { f ->
            f.isFile && f.name.startsWith("Patched_", ignoreCase = true) &&
                (f.name.endsWith(".apk", ignoreCase = true) || f.name.endsWith(".apks", ignoreCase = true)) &&
                f.length() > 50_000
        }
        if (inAppDir != null) return inAppDir

        // 2. Migration: Check root OrionStore or public Downloads or private downloads
        val fallbackDirs = listOf(
            getOrionStoreRootDir(),
            Downloads.dir(),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )
        for (dir in fallbackDirs) {
            val candidate = dir.listFiles()?.firstOrNull { f ->
                f.isFile && f.name.startsWith("Patched_", ignoreCase = true) &&
                    (f.name.contains(safeName, ignoreCase = true) || f.name.contains(packageName, ignoreCase = true)) &&
                    (f.name.endsWith(".apk", ignoreCase = true) || f.name.endsWith(".apks", ignoreCase = true)) &&
                    f.length() > 50_000
            }
            if (candidate != null) {
                // Move or copy into appDir for clean organization
                return try {
                    val dest = File(appDir, candidate.name)
                    if (candidate != dest) {
                        candidate.copyTo(dest, overwrite = true)
                        candidate.delete()
                    }
                    dest
                } catch (e: Exception) {
                    candidate
                }
            }
        }

        return null
    }

    /** Clean older patched APKs in the app directory, retaining only the latest build */
    fun cleanOlderPatchedApks(appName: String, packageName: String, keepFile: File? = null): Long {
        var freed = 0L
        try {
            val appDir = getAppDir(appName, packageName)
            val builds = appDir.listFiles()?.filter { f ->
                f.isFile && f.name.startsWith("Patched_", ignoreCase = true) &&
                    (f.name.endsWith(".apk", ignoreCase = true) || f.name.endsWith(".apks", ignoreCase = true))
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

            val targetKeep = keepFile ?: builds.firstOrNull()
            builds.filter { it != targetKeep }.forEach { older ->
                freed += older.length()
                older.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return freed
    }

    /** Completely purge an app's folder from OrionStore (untouched, applied_patches, patched builds) */
    fun purgeAppStorage(appName: String, packageName: String): Long {
        var freed = 0L
        try {
            val appDir = getAppDir(appName, packageName)
            if (appDir.exists()) {
                freed = appDir.walkBottomUp().fold(0L) { acc, file ->
                    val len = if (file.isFile) file.length() else 0L
                    file.delete()
                    acc + len
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return freed
    }

    /**
     * Migrates legacy patched APK files from app-private Downloads directory (Android/data)
     * or root Downloads directory into their dedicated /Download/OrionStore/<appName>/ folders.
     */
    fun migrateLegacyDownloads() {
        try {
            val legacyDirs = listOf(
                Downloads.dir(),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            )
            for (dir in legacyDirs) {
                if (!dir.exists()) continue
                dir.listFiles()?.filter { f ->
                    f.isFile && f.name.startsWith("Patched_", ignoreCase = true) &&
                        (f.name.endsWith(".apk", ignoreCase = true) || f.name.endsWith(".apks", ignoreCase = true)) &&
                        f.length() > 50_000
                }?.forEach { legacyFile ->
                    val rawName = legacyFile.name.removePrefix("Patched_")
                    val appPart = if ("_v" in rawName) rawName.substringBefore("_v") else rawName.substringBeforeLast(".")
                    val appName = appPart.replace("_", " ")
                    val targetDir = getAppDir(appName, "")
                    val targetFile = File(targetDir, legacyFile.name)
                    if (!targetFile.exists() || targetFile.length() != legacyFile.length()) {
                        legacyFile.copyTo(targetFile, overwrite = true)
                    }
                    if (legacyFile.canonicalPath != targetFile.canonicalPath) {
                        legacyFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

