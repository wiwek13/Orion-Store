package com.orion.store.morphe

import com.orion.store.morphe.model.JobStage
import com.orion.store.morphe.model.JobStateMachine
import com.orion.store.morphe.model.PatchJob
import com.orion.store.morphe.model.PatchJobParams
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobStateMachineTest {

    @Test
    fun `test legal forward happy path transition`() {
        val stages = listOf(
            JobStage.QUEUED,
            JobStage.DOWNLOADING,
            JobStage.VERIFYING,
            JobStage.PATCHING,
            JobStage.ALIGNING,
            JobStage.SIGNING,
            JobStage.INSTALLING,
            JobStage.SUCCEEDED
        )

        for (i in 0 until stages.size - 1) {
            val from = stages[i]
            val to = stages[i + 1]
            assertTrue("Expected legal transition from $from to $to", JobStateMachine.canTransition(from, to))
        }
    }

    @Test
    fun `test cancellation from any active stage`() {
        val cancellableStages = listOf(
            JobStage.QUEUED,
            JobStage.DOWNLOADING,
            JobStage.VERIFYING,
            JobStage.PATCHING,
            JobStage.ALIGNING,
            JobStage.SIGNING,
            JobStage.INSTALLING
        )

        for (stage in cancellableStages) {
            assertTrue("Expected stage $stage can transition to CANCELLED", JobStateMachine.canTransition(stage, JobStage.CANCELLED))
            assertTrue("Expected stage $stage can transition to FAILED", JobStateMachine.canTransition(stage, JobStage.FAILED))
        }
    }

    @Test
    fun `test illegal backward and skip transitions rejected`() {
        // Cannot jump directly from QUEUED to SIGNING or INSTALLING
        assertFalse(JobStateMachine.canTransition(JobStage.QUEUED, JobStage.SIGNING))
        assertFalse(JobStateMachine.canTransition(JobStage.QUEUED, JobStage.INSTALLING))
        assertFalse(JobStateMachine.canTransition(JobStage.QUEUED, JobStage.SUCCEEDED))

        // Cannot transition backwards
        assertFalse(JobStateMachine.canTransition(JobStage.INSTALLING, JobStage.PATCHING))
        assertFalse(JobStateMachine.canTransition(JobStage.SIGNING, JobStage.DOWNLOADING))

        // Terminal SUCCEEDED cannot transition to anything
        assertFalse(JobStateMachine.canTransition(JobStage.SUCCEEDED, JobStage.QUEUED))
        assertFalse(JobStateMachine.canTransition(JobStage.SUCCEEDED, JobStage.FAILED))
        assertFalse(JobStateMachine.canTransition(JobStage.SUCCEEDED, JobStage.CANCELLED))
    }

    @Test(expected = IllegalStateException::class)
    fun `test assertLegalTransition throws on illegal transition`() {
        val params = PatchJobParams(
            id = "job-1",
            packageName = "com.google.android.youtube",
            appName = "YouTube"
        )
        val job = PatchJob(id = "job-1", params = params, stage = JobStage.QUEUED)
        // Illegal jump: QUEUED -> SIGNING
        job.withStage(JobStage.SIGNING)
    }

    @Test
    fun `test retry from FAILED or CANCELLED to QUEUED is permitted`() {
        assertTrue(JobStateMachine.canTransition(JobStage.FAILED, JobStage.QUEUED))
        assertTrue(JobStateMachine.canTransition(JobStage.CANCELLED, JobStage.QUEUED))
    }
}
