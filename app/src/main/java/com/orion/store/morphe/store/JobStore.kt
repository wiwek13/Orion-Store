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
                _jobs.value = list
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
        _jobs.update { listOf(job) + it }
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
