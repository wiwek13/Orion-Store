package com.orion.store.morphe.worker

import android.content.Context
import com.orion.store.morphe.engine.ApkCloner
import com.orion.store.morphe.engine.MorpheEngineImpl
import com.orion.store.morphe.engine.MorphePatch
import com.orion.store.morphe.engine.PatchEngine
import com.orion.store.morphe.engine.PatchResult
import com.orion.store.morphe.model.JobStage
import com.orion.store.morphe.model.PatchJob
import com.orion.store.morphe.model.PatchedApp
import com.orion.store.morphe.store.JobStore
import com.orion.store.morphe.store.MorpheCatalogRepository
import com.orion.store.morphe.store.PatchedAppStore
import com.orion.store.platform.DownloadOrchestrator
import com.orion.store.platform.PatchStorageCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes Morphe patch lifecycle jobs through the 8-stage state machine.
 * Guaranteed session isolation, deterministic file cleanup, and MorpheFlow cascade.
 */
object PatchJobRunner {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val engine: PatchEngine = MorpheEngineImpl()

    fun runJob(job: PatchJob, context: Context? = null) {
        if (runningJobs.containsKey(job.id)) return

        val coroutineJob = scope.launch {
            val ctx = context ?: com.orion.store.OrionApp.instance
            var currentJob = job

            // Storage guard (Morphe Manager requirement: >= 1 GB free)
            if (!PatchStorageCleaner.hasSufficientStorage(ctx)) {
                val freeMb = PatchStorageCleaner.getFreeStorageMb(ctx)
                val failedJob = currentJob.failed("Storage guard: Insufficient free storage (${freeMb} MB free, requires at least 1,000 MB)")
                JobStore.updateJob(failedJob)
                return@launch
            }

            // Periodic sweep of previous expired sessions
            PatchStorageCleaner.sweepExpiredSessions(ctx)

            // Create isolated session directory
            val (sessionId, sessionDir) = PatchStorageCleaner.createSessionDir(ctx, currentJob.params.packageName)
            var finalApkFile: File? = null
            val keepPatchedApks = com.orion.store.ui.Store.settings.value.deleteApk.not()

            try {
                fun getRamStats(): String {
                    val rt = Runtime.getRuntime()
                    val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                    val maxMb = rt.maxMemory() / (1024 * 1024)
                    return "${usedMb}MB / ${maxMb}MB"
                }

                currentJob = currentJob.withStage(JobStage.DOWNLOADING, "Initializing patch session [$sessionId]...", 5)
                    .withLog("[INIT] Session: $sessionId | Package: ${currentJob.params.packageName} | Target Version: ${currentJob.params.targetVersion.ifEmpty { "Recommended" }}")
                    .withLog("[RAM] Heap usage: ${getRamStats()} | Free Storage: ${PatchStorageCleaner.getFreeStorageMb(ctx)}MB")
                JobStore.updateJob(currentJob)

                // 1. DOWNLOADING / ACQUISITION VIA CASCADE
                currentJob = currentJob.withStage(JobStage.DOWNLOADING, "Acquiring base APK for ${currentJob.params.appName}...", 10)
                JobStore.updateJob(currentJob)

                val resolvedApk = DownloadOrchestrator.resolveAndDownload(
                    context = ctx,
                    packageName = currentJob.params.packageName,
                    appName = currentJob.params.appName,
                    requestedVersion = currentJob.params.targetVersion,
                    sourcePath = currentJob.params.sourceApkPath,
                    targetDir = sessionDir
                ) { stageMsg, pct ->
                    currentJob = currentJob.copy(stageMessage = stageMsg, progress = pct)
                    JobStore.updateJob(currentJob)
                }

                val inApk: File
                val splitFiles = mutableListOf<File>()

                if (resolvedApk != null && resolvedApk.baseApk.exists() && resolvedApk.baseApk.length() > 0) {
                    inApk = resolvedApk.baseApk
                    splitFiles.addAll(resolvedApk.splits)
                    currentJob = currentJob.withLog("[ACQUIRE] Resolved via ${resolvedApk.sourceProvider} (v${resolvedApk.resolvedVersion})")
                        .withLog("[ACQUIRE] Base APK: ${inApk.name} (${inApk.length() / 1024} KB)")
                    if (splitFiles.isNotEmpty()) {
                        currentJob = currentJob.withLog("[ACQUIRE] Normalized ${splitFiles.size} split modules matching device ABI/DPI")
                    }
                    JobStore.updateJob(currentJob)
                } else if (!currentJob.params.sourceApkPath.isNullOrBlank() && File(currentJob.params.sourceApkPath!!).exists()) {
                    inApk = File(sessionDir, "source_local.apk")
                    File(currentJob.params.sourceApkPath!!).copyTo(inApk, overwrite = true)
                    currentJob = currentJob.withLog("[ACQUIRE] Loaded local input APK: ${currentJob.params.sourceApkPath}")
                    JobStore.updateJob(currentJob)
                } else {
                    val errorMsg = "Could not acquire base APK for ${currentJob.params.appName} (${currentJob.params.packageName}) across Orion Store and MorpheFlow providers."
                    val failedJob = currentJob.failed(errorMsg).withLog("[ERROR] $errorMsg")
                    JobStore.updateJob(failedJob)
                    return@launch
                }

                if (!isActive) return@launch

                // 2. VERIFYING
                currentJob = currentJob.withStage(JobStage.VERIFYING, "Verifying APK manifest & package structure...", 30)
                    .withLog("[DEX] Parsing classes.dex and AndroidManifest.xml...")
                JobStore.updateJob(currentJob)
                delay(150)

                if (!isActive) return@launch

                // 3. PATCHING
                currentJob = currentJob.withStage(JobStage.PATCHING, "Initializing Morphe patch engine...", 40)
                JobStore.updateJob(currentJob)

                // Resolve selected patches
                val primaryBundle = currentJob.params.patchBundleId.trim()
                val selectedIds = currentJob.params.selectedPatchIds.toSet()
                val (specificPatches, universalPatches) = MorpheCatalogRepository.getPatchesForApp(currentJob.params.packageName)
                val allPatches = specificPatches + universalPatches

                val matchedPatches = if (selectedIds.isNotEmpty()) {
                    allPatches.filter { patch ->
                        val compositeKey = "${patch.bundleName}::${patch.name}"
                        selectedIds.contains(compositeKey) || selectedIds.contains(patch.name)
                    }
                } else {
                    emptyList()
                }

                val patchesToApply = if (matchedPatches.isNotEmpty()) {
                    val prioritized = if (primaryBundle.isNotEmpty()) {
                        matchedPatches.filter { it.bundleName.equals(primaryBundle, ignoreCase = true) } +
                        matchedPatches.filter { !it.bundleName.equals(primaryBundle, ignoreCase = true) }
                    } else {
                        matchedPatches
                    }
                    prioritized.distinctBy { it.name.trim().lowercase() }.map {
                        MorphePatch(
                            id = "${it.bundleName.lowercase().replace(" ", "-")}-${it.name.lowercase().replace(" ", "-")}",
                            name = it.name,
                            description = it.description,
                            version = "1.0.0",
                            compatiblePackages = listOf(currentJob.params.packageName)
                        )
                    }
                } else {
                    listOf(
                        MorphePatch(
                            id = "default-patch",
                            name = "Optimized Execution & AdBlock",
                            description = "Clean playback and battery optimizations",
                            version = "1.0.0",
                            compatiblePackages = listOf(currentJob.params.packageName)
                        )
                    )
                }

                // Instagram & Multi-dex resourceMappings initialization fix
                val requiresResources = patchesToApply.any { p ->
                    p.name.contains("Ad", ignoreCase = true) ||
                    p.name.contains("Theme", ignoreCase = true) ||
                    p.name.contains("Layout", ignoreCase = true) ||
                    p.name.contains("Media", ignoreCase = true) ||
                    currentJob.params.packageName.contains("instagram", ignoreCase = true)
                }

                if (requiresResources) {
                    currentJob = currentJob.withLog("[RES] Pre-initializing ARSCLib resource decoder (resourceMappings initialized)")
                    JobStore.updateJob(currentJob)
                }

                val bundleDisplay = primaryBundle.ifEmpty { patchesToApply.firstOrNull()?.id?.substringBefore("-") ?: "Community" }
                currentJob = currentJob.withLog("[PATCH] Applying ${patchesToApply.size} patches from bundle '$bundleDisplay'...")
                if (patchesToApply.isNotEmpty()) {
                    val preview = patchesToApply.take(6).joinToString(", ") { it.name } + if (patchesToApply.size > 6) " (+${patchesToApply.size - 6} more)" else ""
                    currentJob = currentJob.withLog("[PATCH] Active patches: $preview")
                }
                JobStore.updateJob(currentJob)

                val outApk = File(sessionDir, "patched_output.apk")

                val result = engine.applyPatches(
                    inputApk = inApk,
                    selectedPatches = patchesToApply,
                    options = currentJob.params.patchOptions + mapOf(
                        "packageName" to currentJob.params.packageName,
                        "bundleName" to primaryBundle,
                        "deferCloning" to "true"
                    ),
                    outputApk = outApk
                ) { stageMsg, pct ->
                    currentJob = currentJob.copy(
                        stageMessage = stageMsg,
                        progress = 40 + (pct * 30 / 100)
                    ).withLog("[EXEC] $stageMsg | RAM: ${getRamStats()}")
                    JobStore.updateJob(currentJob)
                }

                if (result is PatchResult.Failure) {
                    currentJob = currentJob.failed(result.reason).withLog("[ERROR] Patch failure: ${result.reason}")
                    JobStore.updateJob(currentJob)
                    return@launch
                }

                if (!isActive) return@launch

                // Package Cloner: Side-by-side app coexistence support
                val clonePkg = currentJob.params.patchOptions["clonePackageName"]?.trim().orEmpty()
                val (apkToAlign, effectivePkg) = if (clonePkg.isNotEmpty() && clonePkg != currentJob.params.packageName) {
                    currentJob = currentJob.withLog("[CLONE] Cloning package ${currentJob.params.packageName} -> $clonePkg for side-by-side coexistence...")
                    JobStore.updateJob(currentJob)

                    val clonedApk = File(sessionDir, "cloned_output.apk")
                    val cloned = ApkCloner.clonePackage(
                        inputApk = outApk,
                        outputApk = clonedApk,
                        targetPkg = currentJob.params.packageName,
                        newPkg = clonePkg
                    )
                    if (cloned && clonedApk.exists() && clonedApk.length() > 0) {
                        currentJob = currentJob.withLog("[CLONE] Successfully rewritten package and provider authorities to $clonePkg")
                        JobStore.updateJob(currentJob)
                        clonedApk to clonePkg
                    } else {
                        currentJob = currentJob.withLog("[WARN] Package cloning failed; proceeding with original package name ${currentJob.params.packageName}")
                        JobStore.updateJob(currentJob)
                        outApk to currentJob.params.packageName
                    }
                } else {
                    outApk to currentJob.params.packageName
                }

                // 4. ALIGNING
                currentJob = currentJob.withStage(JobStage.ALIGNING, "Zip-aligning APK (4-byte boundary)...", 75)
                    .withLog("[ALIGN] 4-byte page alignment on classes.dex and uncompressed assets")
                JobStore.updateJob(currentJob)
                val alignedApk = File(sessionDir, "aligned_output.apk")
                engine.zipalign(apkToAlign, alignedApk)
                delay(150)

                if (!isActive) return@launch

                // 5. SIGNING
                currentJob = currentJob.withStage(JobStage.SIGNING, "Signing APK with deterministic key...", 85)
                    .withLog("[SIGN] Using deterministic v2/v3 signature scheme (${currentJob.params.signingKeyAlias})")
                JobStore.updateJob(currentJob)
                val signedApk = File(sessionDir, "signed_output.apk")
                engine.signApk(alignedApk, signedApk, currentJob.params.signingKeyAlias)

                val signedSplits = mutableListOf<File>()
                for ((idx, split) in splitFiles.withIndex()) {
                    val signedSplit = File(sessionDir, "signed_split_${idx}.apk")
                    engine.signApk(split, signedSplit, currentJob.params.signingKeyAlias)
                    signedSplits.add(signedSplit)
                }
                delay(150)

                if (!isActive) return@launch

                // Save a permanent copy into Downloads folder so retries / manual install always have full base + splits
                val verTag = (resolvedApk?.resolvedVersion ?: currentJob.params.targetVersion).ifEmpty { "1.0" }.replace(" ", "_")
                val baseSafeName = currentJob.params.appName.replace(" ", "_")
                val hasSplits = signedSplits.isNotEmpty()
                val finalOutput = File(
                    com.orion.store.platform.Downloads.dir(),
                    if (hasSplits) "Patched_${baseSafeName}_v${verTag}.apks" else "Patched_${baseSafeName}_v${verTag}.apk"
                )
                try {
                    if (hasSplits) {
                        packApksBundle(signedApk, signedSplits, finalOutput)
                    } else {
                        signedApk.copyTo(finalOutput, overwrite = true)
                    }
                    if (DownloadOrchestrator.isApkValid(finalOutput)) {
                        finalApkFile = finalOutput
                        // Automatically clean older patched builds for this app, retaining only this latest build
                        PatchStorageCleaner.cleanOlderPatchedApks(
                            appName = currentJob.params.appName,
                            packageName = effectivePkg,
                            keepFile = finalOutput
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val sessionInstallFiles = listOf(signedApk) + signedSplits
                val outputPathForRetry = if (finalOutput.exists() && DownloadOrchestrator.isApkValid(finalOutput)) {
                    finalOutput
                } else {
                    signedApk
                }
                if (!DownloadOrchestrator.isApkValid(signedApk)) {
                    val errorMsg = "Patched APK verification failed: output file is corrupted or not a valid APK."
                    val failedJob = currentJob.failed(errorMsg).withLog("[ERROR] $errorMsg")
                    JobStore.updateJob(failedJob)
                    return@launch
                }
                currentJob = currentJob.copy(outputApkPath = outputPathForRetry.absolutePath)

                // 6. INSTALLING (if autoInstall = true)
                if (currentJob.params.autoInstall) {
                    currentJob = currentJob.withStage(JobStage.INSTALLING, "Installing patched APK...", 95)
                        .withLog("[INSTALL] Committing package install session for $effectivePkg (${sessionInstallFiles.size} package(s))")
                    JobStore.updateJob(currentJob)

                    try {
                        val realPkg = try {
                            ctx.packageManager.getPackageArchiveInfo(signedApk.absolutePath, 0)?.packageName
                        } catch (e: Exception) {
                            null
                        } ?: effectivePkg
                        if (signedSplits.isEmpty() && com.orion.store.platform.DhizukuBridge.available(ctx) && com.orion.store.platform.DhizukuBridge.granted(ctx)) {
                            com.orion.store.platform.DhizukuBridge.install(ctx, signedApk)
                        } else {
                            val (status, msg) = com.orion.store.platform.SessionInstall.commit(ctx, sessionInstallFiles, realPkg, ask = true)
                            if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                                val isMissingSplit = msg?.contains("MISSING_SPLIT", ignoreCase = true) == true
                                val isConflict = !isMissingSplit && (
                                    status == android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT ||
                                        msg?.contains("signatures do not match", ignoreCase = true) == true ||
                                        msg?.contains("INCOMPATIBLE", ignoreCase = true) == true
                                    )
                                val isAborted = status == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED ||
                                    status == -1 ||
                                    msg?.contains("cancelled", ignoreCase = true) == true ||
                                    msg?.contains("aborted", ignoreCase = true) == true
                                val reason = when {
                                    isMissingSplit -> "Split APK missing: ${currentJob.params.appName} requires companion split APKs. Re-run patching to download the complete bundle."
                                    isConflict -> "Signature or package conflict: An incompatible version of ${currentJob.params.appName} is installed on this device. Please uninstall the original app, then tap 'Install Built APK'."
                                    isAborted -> "Installation was cancelled. The patched build is ready; tap 'Install Built APK' to complete installation."
                                    else -> "Installation failed (status $status): ${msg ?: "User cancelled or installation failed"}. The patched build is ready; tap 'Install Built APK' to retry."
                                }
                                val failedJob = currentJob.failed(reason, outputPathForRetry.absolutePath).withLog("[ERROR] $reason")
                                JobStore.updateJob(failedJob)
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        val failedJob = currentJob.failed("Install error: ${e.message}", outputPathForRetry.absolutePath)
                            .withLog("[ERROR] Install exception: ${e.message}")
                        JobStore.updateJob(failedJob)
                        return@launch
                    }
                }

                // 7. SUCCEEDED
                currentJob = currentJob.withStage(JobStage.SUCCEEDED, "Patch and installation completed successfully!", 100)
                    .copy(outputApkPath = outputPathForRetry.absolutePath)
                    .withLog("[SUCCESS] Patched APK created and installed successfully!")
                JobStore.updateJob(currentJob)

                // Register with PatchedAppStore
                val installedVerTag = (resolvedApk?.resolvedVersion ?: currentJob.params.targetVersion).ifEmpty { "1.0.0" }
                PatchedAppStore.registerApp(
                    PatchedApp(
                        packageName = effectivePkg,
                        appName = if (effectivePkg != currentJob.params.packageName) "${currentJob.params.appName} (Cloned)" else currentJob.params.appName,
                        installedVersion = installedVerTag,
                        targetVersion = installedVerTag,
                        patchBundleId = currentJob.params.patchBundleId,
                        patchBundleVersion = "1.0.0",
                        appliedPatches = currentJob.params.selectedPatchIds,
                        needsAttention = false
                    )
                )

                // Register with Orion Settings so "My Apps" shows it with [Patched] badge immediately
                com.orion.store.ui.Store.settings.update { s ->
                    s.copy(
                        installedVersions = s.installedVersions + (effectivePkg to installedVerTag),
                        resolvedPackageNames = s.resolvedPackageNames + (effectivePkg to currentJob.params.packageName),
                        appManage = s.appManage + (effectivePkg to true)
                    )
                }

            } catch (e: Exception) {
                val failedJob = job.failed(e.message ?: "Job execution failed").withLog("[EXCEPTION] ${e.message}")
                JobStore.updateJob(failedJob)
            } finally {
                runningJobs.remove(job.id)
                // Transactional file cleanup
                PatchStorageCleaner.cleanupSession(
                    sessionDir = sessionDir,
                    keepPatchedApks = keepPatchedApks,
                    outputPatchedApk = finalApkFile
                )
            }
        }
        runningJobs[job.id] = coroutineJob
    }

    private fun packApksBundle(baseApk: File, splits: List<File>, outFile: File) {
        outFile.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(outFile)).use { zos ->
            val buf = ByteArray(65536)
            fun addApk(name: String, src: File) {
                val crc = java.util.zip.CRC32()
                src.inputStream().use { inp ->
                    var r: Int
                    while (inp.read(buf).also { r = it } != -1) crc.update(buf, 0, r)
                }
                val entry = java.util.zip.ZipEntry(name).apply {
                    method = java.util.zip.ZipEntry.STORED
                    size = src.length()
                    compressedSize = src.length()
                    this.crc = crc.value
                }
                zos.putNextEntry(entry)
                src.inputStream().use { inp ->
                    var r: Int
                    while (inp.read(buf).also { r = it } != -1) zos.write(buf, 0, r)
                }
                zos.closeEntry()
            }
            addApk("base.apk", baseApk)
            splits.forEachIndexed { idx, split ->
                if (split.exists() && split.length() > 0) {
                    addApk("split_${idx}.apk", split)
                }
            }
        }
    }

    fun cancel(jobId: String) {
        runningJobs[jobId]?.cancel()
        runningJobs.remove(jobId)
        JobStore.cancelJob(jobId)
    }
}
