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

                val currentSettings = com.orion.store.ui.Store.settings.value

                // Resolve selected patches & bundle metadata before APK download so .mpp AppTarget versionCodes guide provider downloads
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
                    specificPatches.filter { it.isDefault }
                }

                val prioritized = if (primaryBundle.isNotEmpty()) {
                    matchedPatches.filter { it.bundleName.equals(primaryBundle, ignoreCase = true) } +
                        matchedPatches.filter { !it.bundleName.equals(primaryBundle, ignoreCase = true) }
                } else {
                    matchedPatches
                }
                val patchesToApply = prioritized.distinctBy { it.name.trim().lowercase() }.map {
                    MorphePatch(
                        id = "${it.bundleName.lowercase().replace(" ", "-")}-${it.name.lowercase().replace(" ", "-")}",
                        name = it.name,
                        description = it.description,
                        category = it.category,
                        version = "1.0.0",
                        compatiblePackages = listOf(currentJob.params.packageName)
                    )
                }

                if (patchesToApply.isEmpty()) {
                    val err = "No patches selected or matched in catalog for ${currentJob.params.packageName}."
                    currentJob = currentJob.failed(err).withLog("[ERROR] $err")
                    JobStore.updateJob(currentJob)
                    return@launch
                }

                val effectiveBundleName = primaryBundle.ifEmpty { prioritized.firstOrNull()?.bundleName.orEmpty() }
                val bundleMeta = MorpheCatalogRepository.resolveBundleMetadata(effectiveBundleName)
                    ?: MorpheCatalogRepository.resolveBundleForPackage(currentJob.params.packageName)
                val resolvedBundleRepo = prioritized.firstOrNull { it.bundleRepo.isNotBlank() }?.bundleRepo
                    ?: bundleMeta?.repo.orEmpty()
                val resolvedBundleDl = prioritized.firstOrNull { it.bundleDownloadUrl.isNotBlank() }?.bundleDownloadUrl
                    ?: bundleMeta?.downloadUrl.orEmpty()
                val bundleDisplay = effectiveBundleName.ifEmpty { bundleMeta?.name ?: "Community" }

                val bundleInspection = (engine as? MorpheEngineImpl)?.inspectBundleForPackage(
                    packageName = currentJob.params.packageName,
                    preferredRepo = resolvedBundleRepo,
                    preferredBundleName = effectiveBundleName,
                    preferredDownloadUrl = resolvedBundleDl,
                    onLog = { line ->
                        currentJob = currentJob.withLog(line)
                        JobStore.updateJob(currentJob)
                    }
                )
                val rawTargetVer = currentJob.params.targetVersion.trim()
                val effectiveRequestedVersion = if (
                    rawTargetVer.isEmpty() ||
                    rawTargetVer.equals("Recommended", ignoreCase = true) ||
                    rawTargetVer.equals("latest", ignoreCase = true)
                ) {
                    bundleInspection?.recommendedVersion?.ifBlank { rawTargetVer } ?: rawTargetVer
                } else {
                    rawTargetVer
                }
                val targetVersionCode = bundleInspection?.versionCodes?.get(effectiveRequestedVersion)
                if (effectiveRequestedVersion.isNotBlank() || targetVersionCode != null) {
                    currentJob = currentJob.withLog(
                        "[PRECHECK] Target v${effectiveRequestedVersion.ifEmpty { "Recommended" }}" +
                            (targetVersionCode?.let { " (versionCode=$it)" } ?: "") +
                            " for bundle '$bundleDisplay'"
                    )
                    JobStore.updateJob(currentJob)
                }

                // 1. DOWNLOADING / ACQUISITION VIA CASCADE
                currentJob = currentJob.withStage(JobStage.DOWNLOADING, "Acquiring base APK for ${currentJob.params.appName}...", 10)
                JobStore.updateJob(currentJob)

                val resolvedApk = DownloadOrchestrator.resolveAndDownload(
                    context = ctx,
                    packageName = currentJob.params.packageName,
                    appName = currentJob.params.appName,
                    requestedVersion = effectiveRequestedVersion,
                    sourcePath = currentJob.params.sourceApkPath,
                    targetDir = sessionDir,
                    targetVersionCode = targetVersionCode,
                    allowNearestFallback = currentSettings.allowNearestApkVersion
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

                    // Save untouched base APK into Download/OrionStore/<appName>/untouched/
                    val verTagForUntouched = resolvedApk.resolvedVersion.ifEmpty { effectiveRequestedVersion.ifEmpty { "1.0" } }
                    val savedUntouched = com.orion.store.platform.OrionStorageManager.saveUntouchedApk(
                        appName = currentJob.params.appName,
                        packageName = currentJob.params.packageName,
                        version = verTagForUntouched,
                        baseApk = inApk,
                        splits = splitFiles
                    )
                    if (savedUntouched != null) {
                        currentJob = currentJob.withLog("[STORAGE] Stored untouched base APK in ${savedUntouched.parentFile?.name}/${savedUntouched.name}")
                    }
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
                    .withLog("[DEX] Inspecting APK archive${if (splitFiles.isNotEmpty()) " (+${splitFiles.size} splits)" else ""}...")
                JobStore.updateJob(currentJob)

                if (!isActive) return@launch

                // 3. PATCHING
                currentJob = currentJob.withStage(JobStage.PATCHING, "Initializing Morphe patch engine...", 40)
                JobStore.updateJob(currentJob)

                currentJob = currentJob.withLog("[PATCH] Applying ${patchesToApply.size} patches from bundle '$bundleDisplay'...")
                val preview = patchesToApply.take(6).joinToString(", ") { it.name } +
                    if (patchesToApply.size > 6) " (+${patchesToApply.size - 6} more)" else ""
                currentJob = currentJob.withLog("[PATCH] Active patches: $preview")
                JobStore.updateJob(currentJob)

                val outApk = File(sessionDir, "patched_output.apk")

                val result = engine.applyPatches(
                    inputApk = inApk,
                    selectedPatches = patchesToApply,
                    options = currentJob.params.patchOptions + mapOf(
                        "packageName" to currentJob.params.packageName,
                        "bundleName" to effectiveBundleName,
                        "bundleRepo" to resolvedBundleRepo,
                        "bundleDownloadUrl" to resolvedBundleDl,
                        "deferCloning" to "true",
                        "skipFailingPatches" to currentSettings.skipFailingPatches.toString(),
                        "allowUnsupportedVersion" to currentSettings.allowNearestApkVersion.toString()
                    ),
                    outputApk = outApk,
                    splitApks = splitFiles,
                    onLog = { logLine ->
                        currentJob = currentJob.withLog(logLine)
                        JobStore.updateJob(currentJob)
                    },
                    onProgress = { stageMsg, pct ->
                        val nextProgress = 40 + (pct * 30 / 100)
                        val stageChanged = stageMsg != currentJob.stageMessage
                        currentJob = currentJob.copy(
                            stageMessage = stageMsg,
                            progress = nextProgress
                        ).let { updated ->
                            if (stageChanged) updated.withLog("[EXEC] $stageMsg | RAM: ${getRamStats()}") else updated
                        }
                        JobStore.updateJob(currentJob)
                    }
                )

                if (result is PatchResult.Failure) {
                    currentJob = currentJob.failed(result.reason).withLog("[ERROR] Patch failure: ${result.reason}")
                    JobStore.updateJob(currentJob)
                    return@launch
                }

                val successResult = result as? PatchResult.Success
                val appliedPatchNames = successResult?.appliedPatches?.takeIf { it.isNotEmpty() }
                    ?: patchesToApply.map { it.name }
                val skippedPatchNames = successResult?.skippedPatches.orEmpty()
                if (skippedPatchNames.isNotEmpty()) {
                    currentJob = currentJob.withLog(
                        "[WARN] Skipped ${skippedPatchNames.size} incompatible patch(es): ${skippedPatchNames.joinToString()}"
                    )
                    JobStore.updateJob(currentJob)
                }

                if (!isActive) return@launch

                // Package Cloner: run ONLY when side-by-side package cloning is explicitly requested
                val clonePkg = currentJob.params.patchOptions["clonePackageName"]?.trim().orEmpty()
                val targetPkg = currentJob.params.packageName
                val newPkg = if (clonePkg.isNotEmpty()) clonePkg else targetPkg

                val (apkToAlign, effectivePkg) = if (clonePkg.isNotEmpty() && clonePkg != targetPkg) {
                    currentJob = currentJob.withLog("[CLONE] Cloning package $targetPkg -> $newPkg for side-by-side coexistence...")
                    JobStore.updateJob(currentJob)
                    val clonedApk = File(sessionDir, "standalone_output.apk")
                    val cloned = ApkCloner.clonePackage(
                        inputApk = outApk,
                        outputApk = clonedApk,
                        targetPkg = targetPkg,
                        newPkg = newPkg
                    )
                    if (cloned && clonedApk.exists() && clonedApk.length() > 0) {
                        currentJob = currentJob.withLog("[CLONE] Successfully rewritten package and provider authorities to $clonePkg")
                        JobStore.updateJob(currentJob)
                        clonedApk to newPkg
                    } else {
                        outApk to targetPkg
                    }
                } else {
                    outApk to targetPkg
                }

                // 4. ALIGNING
                currentJob = currentJob.withStage(JobStage.ALIGNING, "Zip-aligning standalone APK...", 75)
                    .withLog("[ALIGN] Verifying alignment prior to APK v2/v3 signing")
                JobStore.updateJob(currentJob)
                val alignedApk = File(sessionDir, "aligned_output.apk")
                engine.zipalign(apkToAlign, alignedApk)

                if (!isActive) return@launch

                // 5. SIGNING
                currentJob = currentJob.withStage(JobStage.SIGNING, "Signing APK with deterministic key...", 85)
                    .withLog("[SIGN] Using deterministic v1/v2/v3 signature scheme (${currentJob.params.signingKeyAlias})")
                JobStore.updateJob(currentJob)
                val signedApk = File(sessionDir, "signed_output.apk")
                engine.signApk(alignedApk, signedApk, currentJob.params.signingKeyAlias)

                if (!isActive) return@launch

                // Save a permanent copy into Download/OrionStore/<appName>/
                val verTag = (resolvedApk?.resolvedVersion ?: effectiveRequestedVersion).ifEmpty { "1.0" }.replace(" ", "_")
                val finalOutput = com.orion.store.platform.OrionStorageManager.getPatchedApkFile(
                    appName = currentJob.params.appName,
                    packageName = effectivePkg,
                    version = verTag,
                    hasSplits = false
                )
                try {
                    signedApk.copyTo(finalOutput, overwrite = true)
                    if (DownloadOrchestrator.isApkValid(finalOutput)) {
                        val untouchedFile = com.orion.store.platform.OrionStorageManager.getUntouchedDir(currentJob.params.appName, effectivePkg)
                            .listFiles()?.firstOrNull { it.name.endsWith(".apk") || it.name.endsWith(".apks") }
                        com.orion.store.platform.OrionStorageManager.saveAppliedPatches(
                            com.orion.store.platform.AppPatchManifest(
                                packageName = effectivePkg,
                                appName = currentJob.params.appName,
                                version = verTag,
                                bundleName = bundleDisplay,
                                bundleRepoUrl = bundleMeta?.repositoryUrl.orEmpty(),
                                appliedPatches = appliedPatchNames,
                                patchOptions = currentJob.params.patchOptions,
                                patchedApkFileName = finalOutput.name,
                                untouchedApkFileName = untouchedFile?.name,
                                lastPatchedAt = System.currentTimeMillis()
                            )
                        )
                        com.orion.store.platform.OrionStorageManager.cleanOlderPatchedApks(
                            appName = currentJob.params.appName,
                            packageName = effectivePkg,
                            keepFile = finalOutput
                        )
                        PatchStorageCleaner.cleanOlderPatchedApks(
                            appName = currentJob.params.appName,
                            packageName = effectivePkg,
                            keepFile = finalOutput
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val sessionInstallFiles = listOf(signedApk)
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
                        if (com.orion.store.platform.DhizukuBridge.available(ctx) && com.orion.store.platform.DhizukuBridge.granted(ctx)) {
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
                val installedVerTag = (resolvedApk?.resolvedVersion ?: effectiveRequestedVersion).ifEmpty { "1.0.0" }
                val latestBundleVer = (bundleMeta?.version ?: "").ifBlank { "1.0.0" }
                PatchedAppStore.registerApp(
                    PatchedApp(
                        packageName = effectivePkg,
                        appName = if (effectivePkg != currentJob.params.packageName) "${currentJob.params.appName} (Cloned)" else currentJob.params.appName,
                        installedVersion = installedVerTag,
                        targetVersion = installedVerTag,
                        patchBundleId = bundleDisplay,
                        patchBundleVersion = latestBundleVer,
                        appliedPatches = appliedPatchNames,
                        needsAttention = false
                    )
                )
                com.orion.store.morphe.store.MorpheRepoStore.refreshUpdates()

                // Register with Orion Settings so "My Apps" stays synced across packageName and catalog IDs
                val normEff = com.orion.store.data.normalizePackageName(effectivePkg)
                com.orion.store.ui.Store.settings.update { s ->
                    val ownerKey = s.packageOwners[normEff]
                    val resolvedKeys = s.resolvedPackageNames
                        .filterValues { it == effectivePkg || it == currentJob.params.packageName }
                        .keys
                    val matchingKeys = (listOfNotNull(ownerKey) + resolvedKeys + effectivePkg).distinct()
                    s.copy(
                        installedVersions = s.installedVersions + matchingKeys.associateWith { installedVerTag },
                        resolvedPackageNames = s.resolvedPackageNames + matchingKeys.associateWith { currentJob.params.packageName }
                    ).withPackagesManaged(matchingKeys)
                }

            } catch (e: Exception) {
                val failedJob = currentJob.failed(e.message ?: "Job execution failed").withLog("[EXCEPTION] ${e.message}")
                JobStore.updateJob(failedJob)
            } finally {
                runningJobs.remove(job.id)
                // Clean only ephemeral session files; permanent build output lives in Download/OrionStore/
                PatchStorageCleaner.cleanupSession(
                    sessionDir = sessionDir,
                    keepPatchedApks = true,
                    outputPatchedApk = null
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
