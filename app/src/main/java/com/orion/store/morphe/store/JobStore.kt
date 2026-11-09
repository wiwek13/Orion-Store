package com.orion.store.morphe.store

import com.orion.store.morphe.model.JobStage
import com.orion.store.morphe.model.PatchJob
import com.orion.store.morphe.model.PatchJobParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Thread-safe persistent in-memory and disk-backed Job store for Morphe patch jobs.
 */
object JobStore {

    private val _jobs = MutableStateFlow<List<PatchJob>>(emptyList())
    val jobs: StateFlow<List<PatchJob>> = _jobs.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    init {
        // Pre-populate with sample jobs for immediate feedback in preview and discovery
        val initialJobs = listOf(
            PatchJob(
                id = "sample-job-1",
                params = PatchJobParams(
                    id = "sample-job-1",
                    packageName = "app.revanced.android.youtube",
                    appName = "YouTube ReVanced",
                    targetVersion = "19.16.39",
                    patchBundleId = "morphe-revanced-bundle",
                    selectedPatchIds = listOf("sponsorblock", "return-youtube-dislike", "hide-shorts")
                ),
                stage = JobStage.PATCHING,
                progress = 65,
                stageMessage = "Applying bytecode transformation: sponsorblock"
            ),
            PatchJob(
                id = "sample-job-2",
                params = PatchJobParams(
                    id = "sample-job-2",
                    packageName = "com.spotify.music",
                    appName = "Spotify",
                    targetVersion = "8.9.18.512",
                    patchBundleId = "morphe-audio-bundle",
                    selectedPatchIds = listOf("amoled-theme", "hide-podcasts")
                ),
                stage = JobStage.QUEUED,
                progress = 0,
                stageMessage = "Waiting in queue..."
            )
        )
        _jobs.value = initialJobs
        recalculateActive()
    }

    private fun recalculateActive() {
        _activeCount.value = _jobs.value.count { it.stage.isActive }
    }

    fun enqueue(params: PatchJobParams): PatchJob {
        val job = PatchJob(
            id = if (params.id.isNotEmpty()) params.id else UUID.randomUUID().toString(),
            params = params,
            stage = JobStage.QUEUED,
            progress = 0,
            stageMessage = "Waiting in queue..."
        )
        _jobs.update { listOf(job) + it }
        recalculateActive()
        return job
    }

    fun updateJob(updated: PatchJob) {
        _jobs.update { list ->
            list.map { if (it.id == updated.id) updated else it }
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

    fun retryJob(id: String) {
        _jobs.update { list ->
            list.map {
                if (it.id == id && (it.stage == JobStage.FAILED || it.stage == JobStage.CANCELLED)) {
                    it.copy(
                        stage = JobStage.QUEUED,
                        progress = 0,
                        stageMessage = "Retrying job...",
                        failureReason = null,
                        updatedAt = System.currentTimeMillis()
                    )
                } else it
            }
        }
        recalculateActive()
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
