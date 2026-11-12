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
    fun `test resolve boost for reddit from github releases`() {
        val repoSlug = DownloadOrchestrator.getKnownGitHubRepo("com.rubenmayayo.reddit")
        assertEquals("rubenmayayo/boost-for-reddit", repoSlug)

        // Mock GitHub release assets JSON with multiple architecture variants (arm64, universal, x86)
        val mockReleaseJson = """
            {
              "tag_name": "v1.14.3",
              "assets": [
                {
                  "name": "boost-1.14.3-x86.apk",
                  "browser_download_url": "https://github.com/rubenmayayo/boost-for-reddit/releases/download/v1.14.3/boost-x86.apk"
                },
                {
                  "name": "boost-1.14.3-arm64-v8a.apk",
                  "browser_download_url": "https://github.com/rubenmayayo/boost-for-reddit/releases/download/v1.14.3/boost-arm64.apk"
                },
                {
                  "name": "boost-1.14.3-universal.apk",
                  "browser_download_url": "https://github.com/rubenmayayo/boost-for-reddit/releases/download/v1.14.3/boost-universal.apk"
                },
                {
                  "name": "source-code.zip",
                  "browser_download_url": "https://github.com/rubenmayayo/boost-for-reddit/archive/v1.14.3.zip"
                }
              ]
            }
        """.trimIndent()

        val jsonElement = kotlinx.serialization.json.Json.parseToJsonElement(mockReleaseJson) as kotlinx.serialization.json.JsonObject
        val assets = jsonElement["assets"] as kotlinx.serialization.json.JsonArray

        val selected = DownloadOrchestrator.selectGitHubApkAsset(assets)
        assertNotNull("Should select an APK asset from GitHub release", selected)

        val selectedName = selected?.get("name")?.toString()?.trim('"')
        val downloadUrl = selected?.get("browser_download_url")?.toString()?.trim('"')

        assertTrue("Should select arm64 or universal over x86", selectedName?.contains("arm64") == true || selectedName?.contains("universal") == true)
        assertEquals("https://github.com/rubenmayayo/boost-for-reddit/releases/download/v1.14.3/boost-arm64.apk", downloadUrl)
    }

    @Test
    fun `test cacheResolvedApk preserves split APKs across cache reuse`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkg = "com.test.splitcacheapp"
        val srcDir = File("build/test_temp/split_src").apply { mkdirs() }
        val baseApk = File(srcDir, "base.apk").also { createValidDummyApk(it) }
        val split1 = File(srcDir, "config.arm64_v8a.apk").also { createValidDummyApk(it) }
        val split2 = File(srcDir, "config.xxxhdpi.apk").also { createValidDummyApk(it) }

        DownloadOrchestrator.cacheResolvedApk(
            context = context,
            packageName = pkg,
            resolved = DownloadOrchestrator.ResolvedApk(
                baseApk = baseApk,
                splits = listOf(split1, split2),
                sourceProvider = "APKPure (XAPK)",
                resolvedVersion = "2.0.0"
            )
        )

        val sessionDir = File("build/test_temp/split_session").apply { mkdirs() }
        val resolved = DownloadOrchestrator.resolveAndDownload(
            context = context,
            packageName = pkg,
            appName = "Split App",
            requestedVersion = "2.0.0",
            targetDir = sessionDir
        ) { _, _ -> }

        assertNotNull("Should resolve cached split bundle", resolved)
        assertEquals("2.0.0", resolved!!.resolvedVersion)
        assertEquals("Both split APKs must be preserved in cache", 2, resolved.splits.size)
        srcDir.deleteRecursively()
        sessionDir.deleteRecursively()
    }

    @Test
    fun `test Bundles choose picks only primary ABI and orders base APK first`() {
        val picked = com.orion.store.platform.Bundles.choose(
            names = listOf(
                "config.armeabi_v7a.apk",
                "config.xxxhdpi.apk",
                "config.xxhdpi.apk",
                "com.instagram.android.apk",
                "config.arm64_v8a.apk"
            ),
            abis = listOf("arm64-v8a", "armeabi-v7a"),
            densityDpi = 480
        )

        assertEquals("Base APK must be sorted first", "com.instagram.android.apk", picked.first())
        assertTrue("Primary arm64-v8a ABI must be selected", picked.contains("config.arm64_v8a.apk"))
        assertFalse("Secondary armeabi-v7a ABI must NOT be included alongside arm64-v8a", picked.contains("config.armeabi_v7a.apk"))
        assertTrue("Closest xxhdpi DPI must be selected", picked.contains("config.xxhdpi.apk"))
        assertFalse("Non-matching xxxhdpi DPI must be excluded", picked.contains("config.xxxhdpi.apk"))
    }

    @Test
    fun `test split-requiring base APK without splits is refused from cache and rejected by hasLocalBaseApk`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkg = "com.test.incomplete.splitapp"
        val cacheDir = DownloadOrchestrator.getBaseCacheDir(context, pkg)
        cacheDir.deleteRecursively()

        val tmpDir = File("build/test_temp/split_reject").apply { mkdirs() }
        val splitRequiringBase = File(tmpDir, "base.apk")
        // Build minimal valid binary AXML declaring <manifest requiredSplitTypes="base__density">
        val s0 = "requiredSplitTypes".toByteArray(Charsets.UTF_8)
        val s1 = "base__density".toByteArray(Charsets.UTF_8)
        val poolStrBytes = java.io.ByteArrayOutputStream().apply {
            write(s0.size); write(s0.size); write(s0); write(0)
            val s1Off = size()
            write(s1.size); write(s1.size); write(s1); write(0)
            while (size() % 4 != 0) write(0)
        }.toByteArray()
        val s1Off = 2 + s0.size + 1
        val poolSize = 28 + 8 + poolStrBytes.size
        val elemSize = 56
        val totalAxml = 8 + poolSize + elemSize
        val axml = java.nio.ByteBuffer.allocate(totalAxml).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            // Root AXML chunk
            putShort(0x0003); putShort(8); putInt(totalAxml)
            // String pool chunk
            putShort(0x0001); putShort(28); putInt(poolSize)
            putInt(2); putInt(0); putInt(0x100); putInt(36); putInt(0)
            putInt(0); putInt(s1Off)
            put(poolStrBytes)
            // START_ELEMENT (<manifest requiredSplitTypes="base__density">)
            putShort(0x0102); putShort(16); putInt(elemSize); putInt(1); putInt(-1)
            putInt(-1); putInt(0); putShort(20); putShort(20); putShort(1); putShort(0); putShort(0); putShort(0)
            // Attr: ns=-1, name=0, rawValue=1, size=8, res0=0, type=3 (STRING), data=1
            putInt(-1); putInt(0); putInt(1); putShort(8); put(0); put(3); putInt(1)
        }.array()
        ZipOutputStream(FileOutputStream(splitRequiringBase)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(axml)
            zos.closeEntry()
            val pad = ByteArray(70_000).apply { java.util.Random(7).nextBytes(this) }
            zos.putNextEntry(ZipEntry("assets/pad.bin"))
            zos.write(pad)
            zos.closeEntry()
        }

        assertTrue("apkRequiresSplits must detect splits requirement", DownloadOrchestrator.apkRequiresSplits(splitRequiringBase))

        // cacheResolvedApk must refuse caching without companion splits
        DownloadOrchestrator.cacheResolvedApk(
            context = context,
            packageName = pkg,
            resolved = DownloadOrchestrator.ResolvedApk(
                baseApk = splitRequiringBase,
                splits = emptyList(),
                sourceProvider = "Test",
                resolvedVersion = "1.0.0"
            )
        )
        assertFalse("Incomplete split base must NOT be cached", DownloadOrchestrator.hasLocalBaseApk(context, pkg))

        // Even if a broken bundle_* dir exists without splits/, hasLocalBaseApk must reject it
        val brokenBundle = File(cacheDir, "bundle_1.0.0").apply { mkdirs() }
        splitRequiringBase.copyTo(File(brokenBundle, "base.apk"), overwrite = true)
        assertFalse("Poisoned split-less bundle dir must return false in hasLocalBaseApk", DownloadOrchestrator.hasLocalBaseApk(context, pkg))

        tmpDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `test deleteJobsForPackage removes all jobs for package`() {
        val pkg = "com.test.cleanup.pkg"
        com.orion.store.morphe.store.JobStore.enqueue(
            PatchJobParams(id = "cleanup-job-1", packageName = pkg, appName = "Cleanup App")
        )
        com.orion.store.morphe.store.JobStore.enqueue(
            PatchJobParams(id = "cleanup-job-2", packageName = pkg, appName = "Cleanup App")
        )
        assertTrue(
            "Expected at least 2 jobs for $pkg",
            com.orion.store.morphe.store.JobStore.jobs.value.count { it.params.packageName == pkg } >= 2
        )
        com.orion.store.morphe.store.JobStore.deleteJobsForPackage(pkg)
        assertEquals(0, com.orion.store.morphe.store.JobStore.jobs.value.count { it.params.packageName == pkg })
    }

    @Test
    fun `test resolveFromBaseCache does not fallback to mismatched version when specific version requested`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkg = "com.test.exactver"
        val tmpDir = File("build/test_temp/exact_ver").apply {
            deleteRecursively()
            mkdirs()
        }
        val apk = File(tmpDir, "source.apk")
        createValidDummyApk(apk)
        DownloadOrchestrator.cacheBaseApk(context, pkg, "1.0.0", apk)

        val mismatched = DownloadOrchestrator.resolveFromBaseCache(context, pkg, "2.0.0", File(tmpDir, "out1"))
        assertEquals("Mismatched requested version must not reuse cached 1.0.0", null, mismatched)

        val matched = DownloadOrchestrator.resolveFromBaseCache(context, pkg, "1.0.0", File(tmpDir, "out2"))
        assertNotNull("Exact cached version should resolve cleanly", matched)
        assertEquals("1.0.0", matched!!.resolvedVersion)

        tmpDir.deleteRecursively()
        DownloadOrchestrator.getBaseCacheDir(context, pkg).deleteRecursively()
    }

    @Test
    fun `test parseApkPureVersionCodeFromHtml extracts versionCode across attribute orders`() {
        val htmlOrderA = """
            <a class="ver_download_link" data-dt-version="455.0.0.51.72" data-dt-versioncode="386113969" href="/dl">Latest</a>
            <a class="ver_download_link" data-dt-version="454.0.0.54.77" data-dt-versioncode="386014758" href="/dl2">Target</a>
        """.trimIndent()
        assertEquals(386014758L, DownloadOrchestrator.parseApkPureVersionCodeFromHtml(htmlOrderA, "454.0.0.54.77"))
        assertEquals(386113969L, DownloadOrchestrator.parseApkPureVersionCodeFromHtml(htmlOrderA, "v455.0.0.51.72"))
        assertEquals(null, DownloadOrchestrator.parseApkPureVersionCodeFromHtml(htmlOrderA, "453.0.0.1.0"))

        val htmlOrderB = """
            <div data-dt-version-code="386014758" data-dt-version="454.0.0.54.77">Instagram</div>
        """.trimIndent()
        assertEquals(386014758L, DownloadOrchestrator.parseApkPureVersionCodeFromHtml(htmlOrderB, "454.0.0.54.77"))
    }

    @Test
    fun `test isSpecificVersionRequest and versionsMatch`() {
        assertFalse(DownloadOrchestrator.isSpecificVersionRequest(""))
        assertFalse(DownloadOrchestrator.isSpecificVersionRequest("Recommended"))
        assertFalse(DownloadOrchestrator.isSpecificVersionRequest("latest"))
        assertFalse(DownloadOrchestrator.isSpecificVersionRequest("Local"))
        assertTrue(DownloadOrchestrator.isSpecificVersionRequest("454.0.0.54.77"))

        assertTrue(DownloadOrchestrator.versionsMatch("v454.0.0.54.77", "454.0.0.54.77"))
        assertFalse(DownloadOrchestrator.versionsMatch("455.0.0.51.72", "454.0.0.54.77"))
    }
}
