package com.orion.store.morphe.model

import kotlinx.serialization.Serializable

/**
 * Explicit 8-stage state machine for Morphe Patch jobs.
 */
@Serializable
enum class JobStage {
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    PATCHING,
    ALIGNING,
    SIGNING,
    INSTALLING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED

    val isActive: Boolean
        get() = !isTerminal && this != QUEUED
}

/**
 * Patch job parameters.
 */
@Serializable
data class PatchJobParams(
    val id: String,
    val packageName: String,
    val appName: String,
    val sourceApkUrl: String? = null,
    val sourceApkPath: String? = null,
    val targetVersion: String = "",
    val patchBundleId: String = "",
    val selectedPatchIds: List<String> = emptyList(),
    val patchOptions: Map<String, String> = emptyMap(),
    val signingKeyAlias: String = "morphe-default",
    val autoInstall: Boolean = true
) {
    fun effectivePackageName(): String =
        patchOptions["clonePackageName"]?.trim()?.takeIf { it.isNotEmpty() } ?: packageName
}

/**
 * Persistent Job Entity representing a single build/patch task.
 */
@Serializable
data class PatchJob(
    val id: String,
    val params: PatchJobParams,
    val stage: JobStage = JobStage.QUEUED,
    val progress: Int = 0, // 0..100
    val stageMessage: String = "Waiting in queue...",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val failureReason: String? = null,
    val outputApkPath: String? = null,
    val logs: List<String> = emptyList()
) {
    fun withStage(nextStage: JobStage, message: String = "", progress: Int = this.progress): PatchJob {
        JobStateMachine.assertLegalTransition(this.stage, nextStage)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logEntry = "[$timestamp] [${nextStage.name}] ${message.ifEmpty { nextStage.name }}"
        return copy(
            stage = nextStage,
            stageMessage = message.ifEmpty { nextStage.name.lowercase().replaceFirstChar { it.uppercase() } },
            progress = progress,
            logs = (logs + logEntry).takeLast(250),
            updatedAt = System.currentTimeMillis()
        )
    }

    fun withLog(logLine: String): PatchJob {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logEntry = "[$timestamp] $logLine"
        return copy(
            logs = (logs + logEntry).takeLast(250),
            updatedAt = System.currentTimeMillis()
        )
    }

    fun failed(reason: String, outputApk: String? = null): PatchJob {
        JobStateMachine.assertLegalTransition(this.stage, JobStage.FAILED)
        return copy(
            stage = JobStage.FAILED,
            failureReason = reason,
            stageMessage = "Failed: $reason",
            outputApkPath = outputApk ?: this.outputApkPath,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun cancelled(): PatchJob {
        JobStateMachine.assertLegalTransition(this.stage, JobStage.CANCELLED)
        return copy(
            stage = JobStage.CANCELLED,
            stageMessage = "Job cancelled by user",
            updatedAt = System.currentTimeMillis()
        )
    }
}
