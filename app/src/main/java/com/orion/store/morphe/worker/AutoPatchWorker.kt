package com.orion.store.morphe.worker

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.orion.store.data.Settings
import com.orion.store.data.compareVersions
import com.orion.store.morphe.model.PatchJobParams
import com.orion.store.morphe.store.JobStore
import com.orion.store.morphe.store.MorpheCatalogRepository
import com.orion.store.morphe.store.PatchedAppStore
import com.orion.store.ui.Store
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Background auto-patching engine for installed Morphe apps.
 * Implements Section 6 of Orion Store Fork Specification.
 */
object AutoPatchWorker {

    const val JOB_ID = 7350

    fun schedule(context: Context, settings: Settings) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        if (!settings.autoPatchGlobal) {
            js.cancel(JOB_ID)
            return
        }

        val net = if (settings.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        val existing = js.getPendingJob(JOB_ID)
        if (existing?.networkType == net) return

        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, AutoPatchJobService::class.java))
                .setPeriodic(6 * 3_600_000L, 3_600_000L)
                .setRequiredNetworkType(net)
                .setPersisted(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= 26) {
                        setRequiresBatteryNotLow(true)
                        setRequiresStorageNotLow(true)
                    }
                }
                .build()
        )
    }

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        Store.init(context)
        val s = Store.settings.value
        if (!s.autoPatchGlobal) return@withContext

        // Refresh catalogs
        MorpheCatalogRepository.sync(force = false)

        val installedPatchedApps = PatchedAppStore.apps.value
        for (patchedApp in installedPatchedApps) {
            val pkg = patchedApp.packageName

            // Check per-app auto-patch toggle (defaults to true unless explicitly disabled)
            val isAppAutoPatchEnabled = s.autoPatchApps[pkg] ?: true
            if (!isAppAutoPatchEnabled) {
                continue
            }

            // Check pinned version
            val pinnedVersion = s.pinnedAppVersions[pkg]

            // Inspect catalog metadata for this app
            val catalogApp = MorpheCatalogRepository.getApp(pkg)
            val recommendedVersion = catalogApp?.compatibleVersions
                ?.filter { it.isNotBlank() }
                ?.maxWithOrNull { a, b -> compareVersions(a, b) }
                ?: patchedApp.targetVersion

            val targetVersion = pinnedVersion ?: recommendedVersion

            // Determine if an update is needed:
            // 1. Bundle updated
            val bundleMeta = MorpheCatalogRepository.resolveBundleMetadata(patchedApp.patchBundleId)
                ?: MorpheCatalogRepository.resolveBundleForPackage(pkg)
            val bundleUpdated = bundleMeta != null &&
                bundleMeta.version.isNotBlank() &&
                patchedApp.patchBundleVersion.isNotBlank() &&
                compareVersions(bundleMeta.version, patchedApp.patchBundleVersion) > 0
            // 2. Recommended app version updated (and not pinned)
            val versionUpdated = pinnedVersion == null &&
                recommendedVersion.isNotBlank() &&
                compareVersions(recommendedVersion, patchedApp.installedVersion) > 0

            if (bundleUpdated || versionUpdated) {
                val savedPatches = s.savedPatchSelections[pkg] ?: patchedApp.appliedPatches
                val params = PatchJobParams(
                    id = UUID.randomUUID().toString(),
                    packageName = pkg,
                    appName = patchedApp.appName,
                    targetVersion = targetVersion,
                    selectedPatchIds = savedPatches,
                    autoInstall = s.autoInstallPatchedGlobal
                )
                JobStore.enqueue(params)
            }
        }
    }
}

class AutoPatchJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            runCatching { AutoPatchWorker.run(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }
}

