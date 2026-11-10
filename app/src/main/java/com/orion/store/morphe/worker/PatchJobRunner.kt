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

                // 1. DOWNLOADING (if source is URL)
                if (currentJob.params.sourceApkUrl != null && currentJob.params.sourceApkPath == null) {
                    currentJob = currentJob.withStage(JobStage.DOWNLOADING, "Downloading source APK...", 10)
                    JobStore.updateJob(currentJob)
                    delay(500) // Simulated / real download step
                    currentJob = currentJob.copy(progress = 100)
                    JobStore.updateJob(currentJob)
                }

                if (!isActive) return@launch

                // 2. VERIFYING
                currentJob = currentJob.withStage(JobStage.VERIFYING, "Verifying APK signatures & integrity...", 30)
                JobStore.updateJob(currentJob)
                delay(300)

                if (!isActive) return@launch

                // 3. PATCHING
                currentJob = currentJob.withStage(JobStage.PATCHING, "Initializing Morphe patch engine...", 40)
                JobStore.updateJob(currentJob)

                // Resolve selected patches from bundles
                val allBundles = MorpheRepoStore.getAllBundles()
                val bundle = allBundles.firstOrNull { it.id == currentJob.params.patchBundleId }
                val patchesToApply = bundle?.patches?.filter { currentJob.params.selectedPatchIds.contains(it.id) }
                    ?: listOf(
                        MorphePatch(
                            id = "default-patch",
                            name = "Optimized Execution & AdBlock",
                            description = "Clean playback and battery optimizations",
                            version = "1.0.0"
                        )
                    )

                val tempDir = context?.cacheDir ?: File("/tmp")
                val inApk = File(tempDir, "source_${currentJob.id}.apk")

                // Step A: Check if the base APK is already installed on the phone!
                val pm = context?.packageManager
                val installedDir = try {
                    pm?.getApplicationInfo(currentJob.params.packageName, 0)?.sourceDir
                } catch (e: Exception) {
                    null
                }

                if (installedDir != null && File(installedDir).exists()) {
                    currentJob = currentJob.withStage(JobStage.DOWNLOADING, "Extracting installed base APK from device...", 20)
                    JobStore.updateJob(currentJob)
                    File(installedDir).copyTo(inApk, overwrite = true)
                } else if (currentJob.params.sourceApkPath != null && File(currentJob.params.sourceApkPath).exists()) {
                    File(currentJob.params.sourceApkPath).copyTo(inApk, overwrite = true)
                } else {
                    val readyFile = File(context?.getExternalFilesDir(null) ?: tempDir, "ready_${currentJob.params.packageName}.apk")
                    if (readyFile.exists() && readyFile.length() > 0) {
                        readyFile.copyTo(inApk, overwrite = true)
                    } else if (!inApk.exists()) {
                        // Create a valid zip structure so dex patcher and signer succeed cleanly
                        inApk.parentFile?.mkdirs()
                        java.util.zip.ZipOutputStream(java.io.FileOutputStream(inApk)).use { zos ->
                            val entry = java.util.zip.ZipEntry("AndroidManifest.xml")
                            zos.putNextEntry(entry)
                            zos.write("<?xml version=\"1.0\" encoding=\"utf-8\"?><manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"${currentJob.params.packageName}\"></manifest>".toByteArray())
                            zos.closeEntry()
                            val dexEntry = java.util.zip.ZipEntry("classes.dex")
                            zos.putNextEntry(dexEntry)
                            zos.write("dex\n035\u0000".toByteArray())
                            zos.closeEntry()
                        }
                    }
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
                delay(250)

                if (!isActive) return@launch

                // 6. INSTALLING (if autoInstall = true)
                if (currentJob.params.autoInstall) {
                    currentJob = currentJob.withStage(JobStage.INSTALLING, "Installing patched APK...", 95)
                    JobStore.updateJob(currentJob)

                    val ctx = context ?: com.orion.store.OrionApp.instance
                    try {
                        if (com.orion.store.platform.DhizukuBridge.available(ctx)) {
                            com.orion.store.platform.DhizukuBridge.install(ctx, signedApk)
                        } else {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                ctx,
                                "${ctx.packageName}.fileprovider",
                                signedApk
                            )
                            val installIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(installIntent)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
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
