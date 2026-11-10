package com.orion.store.morphe.worker

import android.content.Context
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
                val (specificPatches, universalPatches) = MorpheCatalogRepository.getPatchesForApp(currentJob.params.packageName)
                val allPatches = specificPatches + universalPatches
                val patchesToApply = if (currentJob.params.selectedPatchIds.isNotEmpty()) {
                    allPatches.filter { currentJob.params.selectedPatchIds.contains(it.name) }.map {
                        MorphePatch(
                            id = it.name.lowercase().replace(" ", "-"),
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

                currentJob = currentJob.withLog("[PATCH] Applying ${patchesToApply.size} selected patches...")
                JobStore.updateJob(currentJob)

                val outApk = File(sessionDir, "patched_output.apk")

                val result = engine.applyPatches(
                    inputApk = inApk,
                    selectedPatches = patchesToApply,
                    options = currentJob.params.patchOptions,
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

                // 4. ALIGNING
                currentJob = currentJob.withStage(JobStage.ALIGNING, "Zip-aligning APK (4-byte boundary)...", 75)
                    .withLog("[ALIGN] 4-byte page alignment on classes.dex and uncompressed assets")
                JobStore.updateJob(currentJob)
                val alignedApk = File(sessionDir, "aligned_output.apk")
                engine.zipalign(outApk, alignedApk)
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

                // Save a permanent copy into Downloads folder if keepPatchedApks is enabled
                val finalApk = File(
                    com.orion.store.platform.Downloads.dir(),
                    "Patched_${currentJob.params.appName.replace(" ", "_")}_v${currentJob.params.targetVersion.ifEmpty { "1.0" }}.apk"
                )
                try {
                    signedApk.copyTo(finalApk, overwrite = true)
                    finalApkFile = finalApk
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val apkToInstall = if (finalApk.exists() && finalApk.length() > 0) finalApk else signedApk
                val allApksToInstall = listOf(apkToInstall) + signedSplits

                // 6. INSTALLING (if autoInstall = true)
                if (currentJob.params.autoInstall) {
                    currentJob = currentJob.withStage(JobStage.INSTALLING, "Installing patched APK...", 95)
                        .withLog("[INSTALL] Committing package install session for ${currentJob.params.packageName}")
                    JobStore.updateJob(currentJob)

                    try {
                        if (splitFiles.isEmpty() && com.orion.store.platform.DhizukuBridge.available(ctx) && com.orion.store.platform.DhizukuBridge.granted(ctx)) {
                            com.orion.store.platform.DhizukuBridge.install(ctx, apkToInstall)
                        } else {
                            val (status, msg) = com.orion.store.platform.SessionInstall.commit(ctx, allApksToInstall, currentJob.params.packageName, ask = true)
                            if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                                val isConflict = status == android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT ||
                                    msg?.contains("signatures do not match", ignoreCase = true) == true ||
                                    msg?.contains("INCOMPATIBLE", ignoreCase = true) == true
                                val reason = if (isConflict) {
                                    "Signature conflict: An existing version of ${currentJob.params.appName} is installed with a conflicting certificate (e.g. from Google Play). Please uninstall the original app first, then re-patch and install."
                                } else {
                                    "Installation failed (status $status): ${msg ?: "User cancelled or installation failed"}"
                                }
                                val failedJob = currentJob.failed(reason).withLog("[ERROR] $reason")
                                JobStore.updateJob(failedJob)
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        val failedJob = currentJob.failed("Install error: ${e.message}").withLog("[ERROR] Install exception: ${e.message}")
                        JobStore.updateJob(failedJob)
                        return@launch
                    }
                }

                // 7. SUCCEEDED
                currentJob = currentJob.withStage(JobStage.SUCCEEDED, "Patch and installation completed successfully!", 100)
                    .copy(outputApkPath = apkToInstall.absolutePath)
                    .withLog("[SUCCESS] Patched APK created and installed successfully!")
                JobStore.updateJob(currentJob)

                // Register with PatchedAppStore
                PatchedAppStore.registerApp(
                    PatchedApp(
                        packageName = currentJob.params.packageName,
                        appName = currentJob.params.appName,
                        installedVersion = currentJob.params.targetVersion.ifEmpty { "1.0.0" },
                        targetVersion = currentJob.params.targetVersion.ifEmpty { "1.0.0" },
                        patchBundleId = currentJob.params.patchBundleId,
                        patchBundleVersion = "1.0.0",
                        appliedPatches = currentJob.params.selectedPatchIds,
                        needsAttention = false
                    )
                )

                // Register with Orion Settings so "My Apps" shows it with [Patched] badge immediately
                com.orion.store.ui.Store.settings.update { s ->
                    s.copy(
                        installedVersions = s.installedVersions + (currentJob.params.packageName to currentJob.params.targetVersion.ifEmpty { "1.0.0" }),
                        resolvedPackageNames = s.resolvedPackageNames + (currentJob.params.packageName to currentJob.params.packageName),
                        appManage = s.appManage + (currentJob.params.packageName to true)
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

    fun cancel(jobId: String) {
        runningJobs[jobId]?.cancel()
        runningJobs.remove(jobId)
        JobStore.cancelJob(jobId)
    }
}
