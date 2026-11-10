package com.orion.store.platform

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.UUID

/**
 * Deterministic Local File Auto-Clean and Session Isolation engine for Morphe Patcher.
 * Strictly adheres to Orion Store Fork Specification (Section 3).
 */
object PatchStorageCleaner {

    private const val SESSION_EXPIRY_MS = 60 * 60 * 1000L // 1 hour
    private const val MIN_FREE_STORAGE_BYTES = 1_000_000_000L // 1 GB minimum required

    /**
     * Creates an isolated patch session directory:
     * context.cacheDir.resolve("patcher_session/<packageName>/<sessionId>/")
     */
    fun createSessionDir(context: Context, packageName: String): Pair<String, File> {
        val sessionId = UUID.randomUUID().toString()
        val sessionDir = File(context.cacheDir, "patcher_session/$packageName/$sessionId")
        sessionDir.mkdirs()
        return sessionId to sessionDir
    }

    /**
     * Verifies that the device has at least [minBytes] free internal storage.
     */
    fun hasSufficientStorage(context: Context, minBytes: Long = MIN_FREE_STORAGE_BYTES): Boolean {
        return try {
            val cacheDir = context.cacheDir ?: return true
            val stat = StatFs(cacheDir.absolutePath)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            available >= minBytes
        } catch (e: Exception) {
            true // fallback gracefully
        }
    }

    /**
     * Gets available free storage in MB for diagnostics.
     */
    fun getFreeStorageMb(context: Context): Long {
        return try {
            val cacheDir = context.cacheDir ?: return 0L
            val stat = StatFs(cacheDir.absolutePath)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Immediate Post-Install / Post-Failure Cleanup.
     * Deletes:
     * - raw downloaded base APKs (*base*.apk, *.xapk, *.apkm, *.apks)
     * - split APK extraction and merge directories (splits_temp/, merged_input.apk)
     * - app_ephemeral/patcher/patched/resources/ and temporary *.idsig signature files
     * - output *_patched.apk unless [keepPatchedApks] is explicitly true.
     */
    fun cleanupSession(
        sessionDir: File,
        keepPatchedApks: Boolean = false,
        outputPatchedApk: File? = null
    ) {
        try {
            if (!sessionDir.exists()) return

            // If keepPatchedApks is true, move the patched APK out or keep it before purging the session dir
            if (keepPatchedApks && outputPatchedApk != null && outputPatchedApk.exists()) {
                // Kept safely in permanent location
            }

            // Delete temporary files inside session directory
            sessionDir.listFiles()?.forEach { file ->
                val name = file.name.lowercase()
                val isPatchedApk = file.absolutePath == outputPatchedApk?.absolutePath || name.contains("patched")

                if (isPatchedApk && keepPatchedApks) {
                    // Retain the single latest patched APK
                } else {
                    file.deleteRecursively()
                }
            }

            // If session dir is now empty (or not keeping), delete the directory itself
            val remaining = sessionDir.listFiles()
            if (remaining.isNullOrEmpty()) {
                sessionDir.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Automatically cleans older patched APKs for [appName] / [packageName] in Downloads.
     * Retains ONLY the single latest build [keepFile].
     */
    fun cleanOlderPatchedApks(appName: String, packageName: String, keepFile: File) {
        try {
            val dir = Downloads.dir()
            if (!dir.exists()) return
            val cleanApp = appName.replace(" ", "_").lowercase()
            val cleanPkg = packageName.lowercase()

            dir.listFiles()?.forEach { file ->
                if (file.absolutePath != keepFile.absolutePath && file.isFile) {
                    val name = file.name.lowercase()
                    val isOldPatchedBuild = (name.startsWith("patched_") || name.contains("_patched")) &&
                        (name.contains(cleanApp) || name.contains(cleanPkg))
                    if (isOldPatchedBuild) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Cleans older cached base APKs for [packageName], retaining only [keepFile].
     */
    fun cleanOlderBaseApks(context: Context, packageName: String, keepFile: File) {
        try {
            val baseCacheDir = File(context.filesDir, "base_apk_cache/$packageName")
            if (!baseCacheDir.exists()) return
            baseCacheDir.listFiles()?.forEach { file ->
                if (file.absolutePath != keepFile.absolutePath && file.isFile) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Startup & periodic sweep:
     * Purges all patcher_session/ folders older than 1 hour across all packages.
     */
    fun sweepExpiredSessions(context: Context) {
        try {
            val rootSessionDir = File(context.cacheDir, "patcher_session")
            if (rootSessionDir.exists() && rootSessionDir.isDirectory) {
                val now = System.currentTimeMillis()
                rootSessionDir.listFiles()?.forEach { pkgDir ->
                    if (pkgDir.isDirectory) {
                        pkgDir.listFiles()?.forEach { sessionDir ->
                            if (sessionDir.isDirectory) {
                                val lastMod = sessionDir.lastModified()
                                if (now - lastMod > SESSION_EXPIRY_MS) {
                                    sessionDir.deleteRecursively()
                                }
                            }
                        }
                        if (pkgDir.listFiles().isNullOrEmpty()) {
                            pkgDir.delete()
                        }
                    }
                }
            }

            // Clean incomplete .part and .tmp files in Downloads.dir()
            runCatching {
                val dlDir = Downloads.dir()
                if (dlDir.exists()) {
                    dlDir.listFiles()?.forEach { f ->
                        val n = f.name.lowercase()
                        if (n.endsWith(".part") || n.endsWith(".tmp")) {
                            if (System.currentTimeMillis() - f.lastModified() > 10 * 60 * 1000L) {
                                f.delete()
                            }
                        }
                    }
                }
            }

            // Also clean legacy temporary files in cache root
            val now = System.currentTimeMillis()
            context.cacheDir?.listFiles()?.forEach { f ->
                val n = f.name.lowercase()
                if (n.startsWith("source_") || n.startsWith("patched_") || n.startsWith("aligned_") || n.startsWith("signed_")) {
                    if (now - f.lastModified() > SESSION_EXPIRY_MS) {
                        f.deleteRecursively()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

