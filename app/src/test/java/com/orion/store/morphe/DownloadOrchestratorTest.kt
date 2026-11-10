package com.orion.store.morphe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.orion.store.platform.DownloadOrchestrator
import com.orion.store.platform.PatchStorageCleaner
import com.orion.store.morphe.model.PatchJobParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadOrchestratorTest {

    private fun createValidDummyApk(file: File, extraEntry: String = "") {
        file.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("DUMMY_MANIFEST".toByteArray())
            zos.closeEntry()
            if (extraEntry.isNotEmpty()) {
                zos.putNextEntry(ZipEntry(extraEntry))
                zos.write("EXTRA".toByteArray())
                zos.closeEntry()
            }
            // Add uncompressible random padding so compressed size exceeds 50KB minimum
            val randomBytes = ByteArray(70_000).apply { java.util.Random(42).nextBytes(this) }
            zos.putNextEntry(ZipEntry("assets/padding.bin"))
            zos.write(randomBytes)
            zos.closeEntry()
        }
    }

    @Test
    fun `test second resolve does not redownload and reuses cached base APK`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkg = "com.test.dummyapp"
        val targetDir = File("build/test_temp/cache_test").apply { mkdirs() }

        // Manually place a valid APK in cache
        val sourceApk = File(targetDir, "source.apk")
        createValidDummyApk(sourceApk)

        DownloadOrchestrator.cacheBaseApk(context, pkg, "1.2.3", sourceApk)

        // Now resolve: it must reuse the cached APK without hitting network
        val sessionDir = File("build/test_temp/session_1").apply { mkdirs() }
        var progressLogged = ""
        val resolved = DownloadOrchestrator.resolveAndDownload(
            context = context,
            packageName = pkg,
            appName = "Dummy App",
            requestedVersion = "1.2.3",
            targetDir = sessionDir
        ) { stage, pct ->
            progressLogged += "$stage;"
        }

        assertNotNull("Should resolve cached APK", resolved)
        assertTrue("Source provider should indicate cached reuse", resolved!!.sourceProvider.contains("Cached"))
        assertEquals("Version should match cached version", "1.2.3", resolved.resolvedVersion)
        assertTrue("Progress should report reuse", progressLogged.contains("Reusing already downloaded"))
    }

    @Test
    fun `test cleaning older patched and base APKs retains only latest build`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkg = "com.instagram.android"
        val appName = "Instagram"

        // 1. Test cleanOlderBaseApks
        val baseCacheDir = DownloadOrchestrator.getBaseCacheDir(context, pkg)
        val oldBase = File(baseCacheDir, "base_${pkg}_447.0.0.55.81.apk").apply {
            createValidDummyApk(this)
        }
        val newBase = File(baseCacheDir, "base_${pkg}_454.0.0.54.77.apk").apply {
            createValidDummyApk(this)
        }

        assertTrue("Old base should exist before clean", oldBase.exists())
        assertTrue("New base should exist before clean", newBase.exists())

        PatchStorageCleaner.cleanOlderBaseApks(context, pkg, newBase)

        assertFalse("Old base APK must be deleted", oldBase.exists())
        assertTrue("New base APK must be retained", newBase.exists())

        // 2. Test cleanOlderPatchedApks
        val dlDir = com.orion.store.platform.Downloads.dir().apply { mkdirs() }
        val oldPatched = File(dlDir, "Patched_Instagram_v447.0.0.55.81.apk").apply {
            createValidDummyApk(this)
        }
        val newPatched = File(dlDir, "Patched_Instagram_v454.0.0.54.77.apk").apply {
            createValidDummyApk(this)
        }

        assertTrue("Old patched build should exist before clean", oldPatched.exists())
        assertTrue("New patched build should exist before clean", newPatched.exists())

        PatchStorageCleaner.cleanOlderPatchedApks(appName, pkg, newPatched)

        assertFalse("Older patched build must be automatically cleaned", oldPatched.exists())
        assertTrue("Latest patched build must be kept", newPatched.exists())
    }

    @Test
    fun `test JobStore removeJob deletes job so cross button dismisses it`() {
        val params = PatchJobParams(
            id = "test-job-dismiss",
            packageName = "com.test.app",
            appName = "Test App"
        )
        val job = com.orion.store.morphe.store.JobStore.enqueue(params)
        // Mark failed
        com.orion.store.morphe.store.JobStore.updateJob(job.failed("Test error"))

        assertTrue("Failed job should exist in JobStore", com.orion.store.morphe.store.JobStore.jobs.value.any { it.id == "test-job-dismiss" })

        // Dismiss via removeJob (invoked by cross button)
        com.orion.store.morphe.store.JobStore.removeJob("test-job-dismiss")

        assertFalse("Failed job must be removed from JobStore when dismissed", com.orion.store.morphe.store.JobStore.jobs.value.any { it.id == "test-job-dismiss" })
    }

    @Test
    fun `test resolve boost for reddit from github releases`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDir = File("build/test_temp/boost").apply { mkdirs() }

        val resolved = DownloadOrchestrator.resolveAndDownload(
            context = context,
            packageName = "com.rubenmayayo.reddit",
            appName = "Boost for Reddit",
            requestedVersion = "1.14.3",
            targetDir = targetDir
        ) { stage, pct ->
            println("[TEST BOOST] $stage ($pct%)")
        }

        assertNotNull("Should resolve Boost APK from GitHub", resolved)
        assertTrue("Base APK should be valid", DownloadOrchestrator.isApkValid(resolved!!.baseApk))
        println("[TEST BOOST] Success: ${resolved.baseApk.absolutePath} (${resolved.baseApk.length()} bytes)")
    }
}
