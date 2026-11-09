package com.orion.store.morphe.model

/**
 * State machine enforcing legal transitions for patch lifecycle jobs:
 * Queued → Downloading → Verifying → Patching → Aligning → Signing → Installing → Succeeded | Failed(reason) | Cancelled.
 */
object JobStateMachine {

    private val legalTransitions: Map<JobStage, Set<JobStage>> = mapOf(
        JobStage.QUEUED to setOf(
            JobStage.DOWNLOADING,
            JobStage.VERIFYING, // if source APK is already local
            JobStage.CANCELLED,
            JobStage.FAILED
        ),
        JobStage.DOWNLOADING to setOf(
            JobStage.VERIFYING,
            JobStage.CANCELLED,
            JobStage.FAILED
        ),
        JobStage.VERIFYING to setOf(
            JobStage.PATCHING,
            JobStage.CANCELLED,
            JobStage.FAILED
        ),
        JobStage.PATCHING to setOf(
            JobStage.ALIGNING,
            JobStage.CANCELLED,
            JobStage.FAILED
        ),
        JobStage.ALIGNING to setOf(
            JobStage.SIGNING,
            JobStage.CANCELLED,
            JobStage.FAILED
        ),
        JobStage.SIGNING to setOf(
            JobStage.INSTALLING,
            JobStage.SUCCEEDED, // if autoInstall = false
            JobStage.CANCELLED,
            JobStage.FAILED
        ),
        JobStage.INSTALLING to setOf(
            JobStage.SUCCEEDED,
            JobStage.CANCELLED,
            JobStage.FAILED
        ),
        // Terminal states cannot transition to anything
        JobStage.SUCCEEDED to emptySet(),
        JobStage.FAILED to setOf(JobStage.QUEUED), // Allow retry from Failed to Queued
        JobStage.CANCELLED to setOf(JobStage.QUEUED) // Allow retry from Cancelled to Queued
    )

    /**
     * Checks if transitioning from [current] to [next] is legally permitted.
     */
    fun canTransition(current: JobStage, next: JobStage): Boolean {
        if (current == next) return true // idempotent
        return legalTransitions[current]?.contains(next) == true
    }

    /**
     * Asserts that transition from [current] to [next] is valid, throwing [IllegalStateException] if illegal.
     */
    fun assertLegalTransition(current: JobStage, next: JobStage) {
        if (!canTransition(current, next)) {
            throw IllegalStateException("Illegal state transition from $current to $next")
        }
    }
}
