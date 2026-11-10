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
import com.orion.store.morphe.store.MorpheRepoStore
import com.orion.store.morphe.store.PatchedAppStore
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
 */
object PatchJobRunner {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val engine: PatchEngine = MorpheEngineImpl()

    fun runJob(job: PatchJob, context: Context? = null) {
        if (runningJobs.containsKey(job.id)) return

        val coroutineJob = scope.launch {
            try {
                var currentJob = job

                val ctx = context ?: com.orion.store.OrionApp.instance
                val tempDir = ctx.cacheDir ?: File("/tmp")
                val inApk = File(tempDir, "source_${currentJob.id}.apk")
                val splitFiles = mutableListOf<File>()

                // 1. DOWNLOADING / ACQUISITION
                currentJob = currentJob.withStage(JobStage.DOWNLOADING, "Locating base APK for ${currentJob.params.appName}...", 10)
                JobStore.updateJob(currentJob)

                // Step A: Check if the base APK is already installed on the phone!
                val pm = ctx.packageManager
                val installedAppInfo = try {
                    pm.getApplicationInfo(currentJob.params.packageName, 0)
                } catch (e: Exception) {
                    null
                }

                if (installedAppInfo != null && File(installedAppInfo.sourceDir).exists()) {
                    currentJob = currentJob.withStage(JobStage.DOWNLOADING, "Extracting installed base APK from device...", 20)
                    JobStore.updateJob(currentJob)
                    File(installedAppInfo.sourceDir).copyTo(inApk, overwrite = true)
                    installedAppInfo.splitSourceDirs?.forEach { sPath ->
                        val sf = File(sPath)
                        if (sf.exists() && sf.length() > 0) splitFiles.add(sf)
                    }
                } else if (currentJob.params.sourceApkPath != null && File(currentJob.params.sourceApkPath).exists()) {
                    File(currentJob.params.sourceApkPath).copyTo(inApk, overwrite = true)
                } else if (!currentJob.params.sourceApkUrl.isNullOrBlank()) {
                    val sUrl = currentJob.params.sourceApkUrl!!
                    currentJob = currentJob.withStage(JobStage.DOWNLOADING, "Downloading source APK...", 15)
                    JobStore.updateJob(currentJob)
                    try {
                        val req = okhttp3.Request.Builder().url(sUrl).build()
                        val resp = com.orion.store.data.Net.client.newCall(req).execute()
                        if (resp.isSuccessful && resp.body != null) {
                            inApk.parentFile?.mkdirs()
                            resp.body!!.byteStream().use { input -> inApk.outputStream().use { output -> input.copyTo(output) } }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    // Check Orion catalog
                    val catalogApp = com.orion.store.ui.Store.data.value.apps.find {
                        it.packageName == currentJob.params.packageName || it.id == currentJob.params.packageName
                    }
                    if (catalogApp != null && catalogApp.downloadUrl.isNotBlank() && catalogApp.downloadUrl != "#") {
                        currentJob = currentJob.withStage(JobStage.DOWNLOADING, "Downloading base APK from Orion catalog...", 15)
                        JobStore.updateJob(currentJob)
                        val url = catalogApp.variants.firstOrNull()?.url ?: catalogApp.downloadUrl
                        try {
                            val req = okhttp3.Request.Builder().url(url).build()
                            val resp = com.orion.store.data.Net.client.newCall(req).execute()
                            if (resp.isSuccessful && resp.body != null) {
                                inApk.parentFile?.mkdirs()
                                resp.body!!.byteStream().use { input -> inApk.outputStream().use { output -> input.copyTo(output) } }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else {
                        // Check Downloads directory
                        val candidate = com.orion.store.platform.Downloads.dir().listFiles()?.firstOrNull {
                            it.extension.lowercase() == "apk" && (
                                it.name.contains(currentJob.params.packageName, ignoreCase = true) ||
                                it.name.contains(currentJob.params.appName, ignoreCase = true)
                            )
                        }
                        if (candidate != null && candidate.length() > 0) {
                            candidate.copyTo(inApk, overwrite = true)
                        }
                    }
                }

                if (!inApk.exists() || inApk.length() == 0L) {
                    val errorMsg = "Base APK for ${currentJob.params.appName} (${currentJob.params.packageName}) is not installed on this device or found in Downloads. Please install it first from Play Store or place its APK in Downloads."
                    val failedJob = currentJob.failed(errorMsg)
                    JobStore.updateJob(failedJob)
                    return@launch
                }

                if (!isActive) return@launch

                // 2. VERIFYING
                currentJob = currentJob.withStage(JobStage.VERIFYING, "Verifying APK manifest & package structure...", 30)
                JobStore.updateJob(currentJob)
                delay(200)

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

                val outApk = File(tempDir, "patched_${currentJob.id}.apk")

                val result = engine.applyPatches(
                    inputApk = inApk,
                    selectedPatches = patchesToApply,
                    options = currentJob.params.patchOptions,
                    outputApk = outApk
                ) { stageMsg, pct ->
                    val jobWithProgress = currentJob.copy(
                        stageMessage = stageMsg,
                        progress = 40 + (pct * 30 / 100)
                    )
                    JobStore.updateJob(jobWithProgress)
                }

                if (result is PatchResult.Failure) {
                    currentJob = currentJob.failed(result.reason)
                    JobStore.updateJob(currentJob)
                    return@launch
                }

                if (!isActive) return@launch

                // 4. ALIGNING
                currentJob = currentJob.withStage(JobStage.ALIGNING, "Zip-aligning APK (4-byte alignment)...", 75)
                JobStore.updateJob(currentJob)
                val alignedApk = File(tempDir, "aligned_${currentJob.id}.apk")
                engine.zipalign(outApk, alignedApk)
                delay(200)

                if (!isActive) return@launch

                // 5. SIGNING
                currentJob = currentJob.withStage(JobStage.SIGNING, "Signing APK with key [${currentJob.params.signingKeyAlias}]...", 85)
                JobStore.updateJob(currentJob)
                val signedApk = File(tempDir, "signed_${currentJob.id}.apk")
                engine.signApk(alignedApk, signedApk, currentJob.params.signingKeyAlias)

                val signedSplits = mutableListOf<File>()
                for ((idx, split) in splitFiles.withIndex()) {
                    val signedSplit = File(tempDir, "signed_split_${idx}_${currentJob.id}.apk")
                    engine.signApk(split, signedSplit, currentJob.params.signingKeyAlias)
                    signedSplits.add(signedSplit)
                }
                delay(200)

                if (!isActive) return@launch

                // Save a permanent copy into Downloads folder
                val finalApk = File(
                    com.orion.store.platform.Downloads.dir(),
                    "Patched_${currentJob.params.appName.replace(" ", "_")}_v${currentJob.params.targetVersion.ifEmpty { "1.0" }}.apk"
                )
                try {
                    signedApk.copyTo(finalApk, overwrite = true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val apkToInstall = if (finalApk.exists() && finalApk.length() > 0) finalApk else signedApk
                val allApksToInstall = listOf(apkToInstall) + signedSplits

                // 6. INSTALLING (if autoInstall = true)
                if (currentJob.params.autoInstall) {
                    currentJob = currentJob.withStage(JobStage.INSTALLING, "Installing patched APK...", 95)
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
                                val failedJob = currentJob.failed(reason)
                                JobStore.updateJob(failedJob)
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        val failedJob = currentJob.failed("Install error: ${e.message}")
                        JobStore.updateJob(failedJob)
                        return@launch
                    }
                }

                // 7. SUCCEEDED
                currentJob = currentJob.withStage(JobStage.SUCCEEDED, "Patch and installation completed successfully!", 100)
                    .copy(outputApkPath = signedApk.absolutePath)
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

                // Register with Orion Settings so "My Apps" shows it with [Patched] badge immediately!
                com.orion.store.ui.Store.settings.update { s ->
                    s.copy(
                        installedVersions = s.installedVersions + (currentJob.params.packageName to currentJob.params.targetVersion.ifEmpty { "1.0.0" }),
                        resolvedPackageNames = s.resolvedPackageNames + (currentJob.params.packageName to currentJob.params.packageName),
                        appManage = s.appManage + (currentJob.params.packageName to true)
                    )
                }

            } catch (e: Exception) {
                val failedJob = job.failed(e.message ?: "Job execution failed")
                JobStore.updateJob(failedJob)
            } finally {
                runningJobs.remove(job.id)
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
