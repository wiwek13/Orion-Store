package com.orion.store.morphe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.orion.store.platform.DownloadOrchestrator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadOrchestratorTest {

    @Test
    fun `test download client can resolve Brave browser from github releases`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDir = File("build/test_temp/brave").apply { mkdirs() }

        val resolved = DownloadOrchestrator.resolveAndDownload(
            context = context,
            packageName = "com.brave.browser",
            appName = "Brave Browser",
            requestedVersion = "latest",
            targetDir = targetDir
        ) { stage, pct ->
            println("[TEST BRAVE] $stage ($pct%)")
        }

        assertNotNull("Should resolve Brave APK", resolved)
        assertTrue("Base APK should exist and be valid", DownloadOrchestrator.isApkValid(resolved!!.baseApk))
        println("[TEST BRAVE] Success: ${resolved.baseApk.absolutePath} (${resolved.baseApk.length()} bytes)")
    }

    @Test
    fun `test download client can acquire Instagram APK from APKPure`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDir = File("build/test_temp/instagram").apply { mkdirs() }

        val resolved = DownloadOrchestrator.resolveAndDownload(
            context = context,
            packageName = "com.instagram.android",
            appName = "Instagram",
            requestedVersion = "454.0.0.54.77",
            targetDir = targetDir
        ) { stage, pct ->
            println("[TEST INSTA] $stage ($pct%)")
        }

        assertNotNull("Should resolve Instagram APK", resolved)
        assertTrue("Base APK should exist and be valid", DownloadOrchestrator.isApkValid(resolved!!.baseApk))
        println("[TEST INSTA] Success: ${resolved.baseApk.absolutePath} (${resolved.baseApk.length()} bytes)")
    }
}

