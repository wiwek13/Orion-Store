package com.orion.store.morphe.store

import com.orion.store.OrionApp
import com.orion.store.morphe.model.JobStage
import com.orion.store.morphe.model.PatchJob
import com.orion.store.morphe.model.PatchJobParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Thread-safe persistent in-memory and disk-backed Job store for Morphe patch jobs.
 */
object JobStore {

    private const val FILE_NAME = "morphe_jobs.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val _jobs = MutableStateFlow<List<PatchJob>>(emptyList())
    val jobs: StateFlow<List<PatchJob>> = _jobs.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val storageFile: File
        get() = File(OrionApp.instance.filesDir, FILE_NAME)

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        try {
            val file = storageFile
            if (file.exists() && file.length() > 0L) {
                val list = json.decodeFromString<List<PatchJob>>(file.readText())
                // Prune duplicate or stale failed jobs for apps that already succeeded
                val succeededPkgs = list.filter { it.stage == JobStage.SUCCEEDED }.map { it.params.packageName }.toSet()
                val cleaned = list.filterNot { it.stage == JobStage.FAILED && it.params.packageName in succeededPkgs }

                // Recover outputApkPath for any failed job if the patched APK is present on disk
                val recovered = cleaned.map { job ->
                    if (job.outputApkPath == null || !File(job.outputApkPath).exists()) {
                        val sessionDir = File(OrionApp.instance.cacheDir, "patcher_session/${job.params.packageName}")
                        val foundInSession = sessionDir.listFiles()?.mapNotNull { sDir ->
                            File(sDir, "patched_output.apk").takeIf { it.exists() && it.length() > 50_000 }
                        }?.maxByOrNull { it.lastModified() }

                        val publicDownloads = com.orion.store.platform.Downloads.dir()
                        val cleanAppName = job.params.appName.replace(" ", "_")
                        val foundInDownloads = publicDownloads.listFiles()?.firstOrNull { f ->
                            f.name.startsWith("Patched_", ignoreCase = true) && (
                                f.name.contains(job.params.packageName, ignoreCase = true) ||
                                f.name.contains(cleanAppName, ignoreCase = true) ||
                                f.name.contains(job.params.appName.filter { it.isLetterOrDigit() }, ignoreCase = true)
                            ) && f.length() > 50_000
                        }

                        val target = foundInSession ?: foundInDownloads
                        if (target != null) {
                            job.copy(outputApkPath = target.absolutePath)
                        } else job
                    } else job
                }

                _jobs.value = recovered
                recalculateActive()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveToDisk() {
        try {
            val file = storageFile
            val text = json.encodeToString(_jobs.value)
            file.writeText(text)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun recalculateActive() {
        _activeCount.value = _jobs.value.count { it.stage.isActive }
        saveToDisk()
    }

    fun enqueue(params: PatchJobParams): PatchJob {
        val job = PatchJob(
            id = if (params.id.isNotEmpty()) params.id else UUID.randomUUID().toString(),
            params = params,
            stage = JobStage.QUEUED,
            progress = 0,
            stageMessage = "Waiting in queue..."
        )
        // Automatically clear any older failed jobs for the same package
        _jobs.update { list ->
            listOf(job) + list.filterNot { it.params.packageName == params.packageName && it.stage == JobStage.FAILED }
        }
        recalculateActive()
        try {
            com.orion.store.morphe.worker.PatchJobRunner.runJob(job, com.orion.store.OrionApp.instance)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return job
    }

    fun updateJob(updated: PatchJob) {
        _jobs.update { list ->
            if (updated.stage == JobStage.SUCCEEDED) {
                // When an app succeeds, prune any older failed jobs for this package
                list.filterNot { it.id != updated.id && it.params.packageName == updated.params.packageName && it.stage == JobStage.FAILED }
                    .map { if (it.id == updated.id) updated else it }
            } else {
                list.map { if (it.id == updated.id) updated else it }
            }
        }
        recalculateActive()
    }

    fun cancelJob(id: String) {
        _jobs.update { list ->
            list.map {
                if (it.id == id && !it.stage.isTerminal) {
                    it.cancelled()
                } else it
            }
        }
        recalculateActive()
    }

    fun removeJob(id: String) {
        _jobs.update { list ->
            list.filter { it.id != id }
        }
        recalculateActive()
    }

    fun dismissJob(id: String) {
        removeJob(id)
    }

    fun retryJob(id: String, newSourcePath: String? = null) {
        var jobToRun: PatchJob? = null
        _jobs.update { list ->
            list.map {
                if (it.id == id && (it.stage == JobStage.FAILED || it.stage == JobStage.CANCELLED)) {
                    val updatedParams = if (newSourcePath != null) it.params.copy(sourceApkPath = newSourcePath) else it.params
                    val retried = it.copy(
                        params = updatedParams,
                        stage = JobStage.QUEUED,
                        progress = 0,
                        stageMessage = "Retrying job...",
                        failureReason = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    jobToRun = retried
                    retried
                } else it
            }
        }
        recalculateActive()
        jobToRun?.let { job ->
            try {
                com.orion.store.morphe.worker.PatchJobRunner.runJob(job, com.orion.store.OrionApp.instance)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteJob(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id } }
        recalculateActive()
    }

    fun clearCompleted() {
        _jobs.update { list -> list.filterNot { it.stage.isTerminal } }
        recalculateActive()
    }

    fun getJob(id: String): PatchJob? {
        return _jobs.value.firstOrNull { it.id == id }
    }
}
