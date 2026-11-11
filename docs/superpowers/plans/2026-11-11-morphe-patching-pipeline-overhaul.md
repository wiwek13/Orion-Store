# Morphe Patching Pipeline, Storage Architecture & Installation Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a robust, end-to-end Morphe patching pipeline with a 5-tier APK acquisition cascade (including APKMirror), persistent split/bundle caching, one-tap cache cleaning in Settings, deep queue removal with cache wipe, binary AXML package cloning, seamless post-patch installation, and "Show in Folder" file manager integration.

**Architecture:** 
1. `DownloadOrchestrator` integrates `ApkMirrorResolver` into the acquisition cascade and ensures split bundles are preserved in `base_apk_cache/<pkg>/bundle_<ver>/`.
2. `PatchStorageCleaner` manages cache lifecycles, computes reclaimable storage, provides one-tap cache clearing in Settings, and deep-purges app caches upon "Remove from Queue".
3. `ApkCloner` modifies binary `AndroidManifest.xml` string pool to rewrite package names and provider authorities for dual side-by-side installations.
4. `QueueTab` and `MyApps` UI are upgraded with an action bar and 3-dots overflow menu (`Install Built APK`, `Show in Folder`, `View Live Logs`, `Clone & Repatch`, `Uninstall Original`, and `Remove from Queue & Wipe Cache`).

**Tech Stack:** Kotlin 2.1, Jetpack Compose, Material 3 Expressive, OkHttp 5, Android PackageInstaller, apksig, BouncyCastle, Roborazzi / Robolectric JUnit tests.

## Global Constraints
- Target SDK: 36 | Min SDK: 24 | JVM Target: 21
- No native code stripping or corrupt compression: `resources.arsc` and `.so` files must remain `ZipEntry.STORED` with accurate CRC-32 and sizes.
- Splits must never be dropped: apps requiring splits must bundle into `.apks` containers in Downloads.
- "Remove from Queue" must purge `base_apk_cache/<packageName>/`, active session dirs, and cancel the job.
- Offline tests (`./gradlew :app:testDebugUnitTest --offline`) must remain 100% passing at every step.

---

### Task 1: Storage & Deep Cache Purge Engine (`PatchStorageCleaner.kt` & `Settings.kt`)

**Files:**
- Modify: `app/src/main/java/com/orion/store/platform/PatchStorageCleaner.kt`
- Modify: `app/src/main/java/com/orion/store/ui/Settings.kt`
- Test: `app/src/test/java/com/orion/store/morphe/PatchStorageCleanerTest.kt`

**Interfaces:**
- Consumes: `Context.filesDir`, `Context.cacheDir`, `Downloads.dir()`
- Produces:
  ```kotlin
  fun purgeAppCache(context: Context, packageName: String): Long
  fun clearPatcherCache(context: Context): Long
  fun getPatcherCacheSizeMb(context: Context): Long
  ```

- [ ] **Step 1: Write unit test for `purgeAppCache` and `clearPatcherCache`**

Create/update `app/src/test/java/com/orion/store/morphe/PatchStorageCleanerTest.kt`:
```kotlin
package com.orion.store.morphe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.orion.store.platform.PatchStorageCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PatchStorageCleanerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "base_apk_cache").deleteRecursively()
        File(context.filesDir, "mpp_cache").deleteRecursively()
        File(context.cacheDir, "patcher_session").deleteRecursively()
    }

    @Test
    fun testPurgeAppCacheDeletesOnlyTargetPackage() {
        val app1Cache = File(context.filesDir, "base_apk_cache/com.test.app1/bundle_1.0").apply { mkdirs() }
        File(app1Cache, "base.apk").writeBytes(ByteArray(1024))

        val app2Cache = File(context.filesDir, "base_apk_cache/com.test.app2/bundle_1.0").apply { mkdirs() }
        File(app2Cache, "base.apk").writeBytes(ByteArray(2048))

        val app1Session = File(context.cacheDir, "patcher_session/com.test.app1/session_123").apply { mkdirs() }
        File(app1Session, "temp.apk").writeBytes(ByteArray(512))

        val freed = PatchStorageCleaner.purgeAppCache(context, "com.test.app1")
        assertTrue(freed > 1000L)
        assertFalse(File(context.filesDir, "base_apk_cache/com.test.app1").exists())
        assertFalse(app1Session.exists())
        assertTrue(File(context.filesDir, "base_apk_cache/com.test.app2").exists())
    }

    @Test
    fun testClearPatcherCacheRemovesAllBaseAndMppCaches() {
        val baseCache = File(context.filesDir, "base_apk_cache/com.test.app/bundle_1").apply { mkdirs() }
        File(baseCache, "base.apk").writeBytes(ByteArray(4096))
        val mppCache = File(context.filesDir, "mpp_cache").apply { mkdirs() }
        File(mppCache, "test.mpp").writeBytes(ByteArray(4096))

        val freed = PatchStorageCleaner.clearPatcherCache(context)
        assertTrue(freed >= 8192L)
        assertFalse(baseCache.exists())
        assertFalse(File(mppCache, "test.mpp").exists())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PatchStorageCleanerTest*" --offline`
Expected: FAIL due to unresolved references `purgeAppCache`, `clearPatcherCache`.

- [ ] **Step 3: Implement methods in `PatchStorageCleaner.kt`**

Add the following methods to `PatchStorageCleaner.kt`:
```kotlin
    fun getPatcherCacheSizeMb(context: Context): Long {
        val baseCache = File(context.filesDir, "base_apk_cache")
        val mppCache = File(context.filesDir, "mpp_cache")
        val sessionCache = File(context.cacheDir, "patcher_session")
        fun dirSize(dir: File): Long = if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        val totalBytes = dirSize(baseCache) + dirSize(mppCache) + dirSize(sessionCache)
        return totalBytes / (1024 * 1024)
    }

    fun purgeAppCache(context: Context, packageName: String): Long {
        var freed = 0L
        fun deleteAndMeasure(dir: File) {
            if (dir.exists()) {
                dir.walkTopDown().filter { it.isFile }.forEach { freed += it.length() }
                dir.deleteRecursively()
            }
        }
        deleteAndMeasure(File(context.filesDir, "base_apk_cache/$packageName"))
        deleteAndMeasure(File(context.cacheDir, "patcher_session/$packageName"))
        return freed
    }

    fun clearPatcherCache(context: Context): Long {
        var freed = 0L
        val dirs = listOf(
            File(context.filesDir, "base_apk_cache"),
            File(context.filesDir, "mpp_cache"),
            File(context.cacheDir, "patcher_session")
        )
        for (dir in dirs) {
            if (dir.exists()) {
                dir.walkTopDown().filter { it.isFile }.forEach { freed += it.length() }
                dir.deleteRecursively()
            }
        }
        return freed
    }
```

- [ ] **Step 4: Add Patcher Storage card in `Settings.kt`**

Under the storage section of `app/src/main/java/com/orion/store/ui/Settings.kt`, add:
```kotlin
// Patcher Cache section
var patcherCacheMb by remember { mutableLongStateOf(PatchStorageCleaner.getPatcherCacheSizeMb(context)) }
Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Column(Modifier.weight(1f)) {
        Text("Patcher APK & Bundle Cache", style = MaterialTheme.typography.titleSmall)
        Text("$patcherCacheMb MB cached (re-used across builds)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    OutlinedButton(
        onClick = {
            vm.haptic(H.MEDIUM)
            val freedBytes = PatchStorageCleaner.clearPatcherCache(context)
            patcherCacheMb = PatchStorageCleaner.getPatcherCacheSizeMb(context)
            vm.toast("Cleared ${freedBytes / (1024 * 1024)} MB of patcher cache")
        },
        enabled = patcherCacheMb > 0
    ) {
        Text("Clear Cache")
    }
}
```

- [ ] **Step 5: Run tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*PatchStorageCleanerTest*" --offline`
Expected: PASS.

- [ ] **Step 6: Commit changes**

```bash
git add app/src/main/java/com/orion/store/platform/PatchStorageCleaner.kt app/src/main/java/com/orion/store/ui/Settings.kt app/src/test/java/com/orion/store/morphe/PatchStorageCleanerTest.kt
git commit -m "feat(storage): implement deep cache purge and one-tap clear cache settings UI"
```

---

### Task 2: APKMirror Resolver Integration (`ApkMirrorResolver.kt` & `DownloadOrchestrator.kt`)

**Files:**
- Create: `app/src/main/java/com/orion/store/platform/ApkMirrorResolver.kt`
- Modify: `app/src/main/java/com/orion/store/platform/DownloadOrchestrator.kt`
- Test: `app/src/test/java/com/orion/store/morphe/ApkMirrorResolverTest.kt`

**Interfaces:**
- Consumes: `OkHttpClient`, package name, requested version, target directory
- Produces:
  ```kotlin
  object ApkMirrorResolver {
      suspend fun resolveAndDownload(
          packageName: String,
          appName: String,
          requestedVersion: String,
          targetDir: File,
          onProgress: (stage: String, percent: Int) -> Unit
      ): DownloadOrchestrator.ResolvedApk?
  }
  ```

- [ ] **Step 1: Write test for `ApkMirrorResolver`**

Create `app/src/test/java/com/orion/store/morphe/ApkMirrorResolverTest.kt`:
```kotlin
package com.orion.store.morphe

import com.orion.store.platform.ApkMirrorResolver
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ApkMirrorResolverTest {

    @Test
    fun testApkMirrorQueryBuilder() {
        val queryUrl = ApkMirrorResolver.buildSearchUrl("com.instagram.android")
        assertTrue(queryUrl.contains("searchtype=apk"))
        assertTrue(queryUrl.contains("com.instagram.android"))
    }

    @Test
    fun testVariantSelectorPrefersArm64OrNodpi() {
        val variants = listOf(
            ApkMirrorResolver.ApkMirrorVariant(id = "1", abi = "x86", dpi = "nodpi", downloadUrl = "https://test.com/1"),
            ApkMirrorResolver.ApkMirrorVariant(id = "2", abi = "arm64-v8a", dpi = "nodpi", downloadUrl = "https://test.com/2"),
            ApkMirrorResolver.ApkMirrorVariant(id = "3", abi = "armeabi-v7a", dpi = "480dpi", downloadUrl = "https://test.com/3")
        )
        val selected = ApkMirrorResolver.selectBestVariant(variants)
        assertNotNull(selected)
        assertTrue(selected?.abi == "arm64-v8a")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ApkMirrorResolverTest*" --offline`
Expected: FAIL due to missing `ApkMirrorResolver`.

- [ ] **Step 3: Implement `ApkMirrorResolver.kt`**

Create `app/src/main/java/com/orion/store/platform/ApkMirrorResolver.kt`:
```kotlin
package com.orion.store.platform

import com.orion.store.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

object ApkMirrorResolver {

    data class ApkMirrorVariant(
        val id: String,
        val abi: String,
        val dpi: String,
        val downloadUrl: String
    )

    fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=$encoded"
    }

    fun selectBestVariant(variants: List<ApkMirrorVariant>): ApkMirrorVariant? {
        return variants.firstOrNull { it.abi.contains("arm64-v8a", ignoreCase = true) && it.dpi.contains("nodpi", ignoreCase = true) }
            ?: variants.firstOrNull { it.abi.contains("arm64-v8a", ignoreCase = true) }
            ?: variants.firstOrNull { it.abi.contains("universal", ignoreCase = true) }
            ?: variants.firstOrNull()
    }

    suspend fun resolveAndDownload(
        packageName: String,
        appName: String,
        requestedVersion: String,
        targetDir: File,
        onProgress: (stage: String, percent: Int) -> Unit
    ): DownloadOrchestrator.ResolvedApk? = withContext(Dispatchers.IO) {
        try {
            onProgress("Searching APKMirror for $appName...", 12)
            val searchUrl = buildSearchUrl(packageName)
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36")
                .build()

            val resp = Net.client.newCall(searchReq).execute()
            if (!resp.isSuccessful) return@withContext null
            val html = resp.body?.string().orEmpty()
            if (html.isEmpty()) return@withContext null

            // Find release links in APKMirror results
            val releaseRegex = Regex("""href="(/apk/[^/]+/[^/]+/[^"]+-release/)"""")
            val releaseMatch = releaseRegex.find(html) ?: return@withContext null
            val releasePath = releaseMatch.groupValues[1]
            val releaseUrl = "https://www.apkmirror.com$releasePath"

            onProgress("Fetching release variants from APKMirror...", 15)
            val releaseReq = Request.Builder().url(releaseUrl).header("User-Agent", "Mozilla/5.0").build()
            val releaseResp = Net.client.newCall(releaseReq).execute()
            if (!releaseResp.isSuccessful) return@withContext null
            val releaseHtml = releaseResp.body?.string().orEmpty()

            // Find download page link
            val downloadPageRegex = Regex("""href="(/apk/[^/]+/[^/]+/[^"]+-android-apk-download/)"""")
            val downloadPageMatch = downloadPageRegex.find(releaseHtml) ?: return@withContext null
            val downloadPageUrl = "https://www.apkmirror.com" + downloadPageMatch.groupValues[1]

            val downloadBtnReq = Request.Builder().url(downloadPageUrl).header("User-Agent", "Mozilla/5.0").build()
            val downloadBtnResp = Net.client.newCall(downloadBtnReq).execute()
            if (!downloadBtnResp.isSuccessful) return@withContext null
            val downloadBtnHtml = downloadBtnResp.body?.string().orEmpty()

            val directDownloadRegex = Regex("""href="(/apk/[^/]+/[^/]+/[^"]+-android-apk-download/download/[^"]*)"""")
            val directMatch = directDownloadRegex.find(downloadBtnHtml) ?: return@withContext null
            val finalPageUrl = "https://www.apkmirror.com" + directMatch.groupValues[1]

            onProgress("Streaming APK from APKMirror...", 18)
            val streamReq = Request.Builder().url(finalPageUrl).header("User-Agent", "Mozilla/5.0").build()
            val streamResp = Net.client.newCall(streamReq).execute()
            if (!streamResp.isSuccessful) return@withContext null

            val body = streamResp.body ?: return@withContext null
            val destFile = File(targetDir, "apkmirror_download.apk")
            FileOutputStream(destFile).use { out -> body.byteStream().copyTo(out) }

            if (destFile.exists() && destFile.length() > 50_000) {
                return@withContext DownloadOrchestrator.normalizeApkBundle(destFile, targetDir, "APKMirror", requestedVersion)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
```

- [ ] **Step 4: Wire `ApkMirrorResolver` into `DownloadOrchestrator.kt` cascade**

In `DownloadOrchestrator.kt`, insert `ApkMirrorResolver` right after Google Play fallback and before APKPure:
```kotlin
// Tier 2b: APKMirror Resolver
val apkmirrorResult = ApkMirrorResolver.resolveAndDownload(
    packageName = packageName,
    appName = appName,
    requestedVersion = targetVer,
    targetDir = targetDir,
    onProgress = onProgress
)
if (apkmirrorResult != null) {
    cacheResolvedApk(context, packageName, apkmirrorResult.resolvedVersion, apkmirrorResult)
    return@withContext apkmirrorResult
}
```

- [ ] **Step 5: Run tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ApkMirrorResolverTest*" --offline`
Expected: PASS.

- [ ] **Step 6: Commit changes**

```bash
git add app/src/main/java/com/orion/store/platform/ApkMirrorResolver.kt app/src/main/java/com/orion/store/platform/DownloadOrchestrator.kt app/src/test/java/com/orion/store/morphe/ApkMirrorResolverTest.kt
git commit -m "feat(acquisition): add APKMirror resolver to multi-tier acquisition cascade"
```

---

### Task 3: Package Conflict & Custom Renamer Engine (`ApkCloner.kt` & `MorpheEngineImpl.kt`)

**Files:**
- Create: `app/src/main/java/com/orion/store/morphe/engine/ApkCloner.kt`
- Modify: `app/src/main/java/com/orion/store/morphe/engine/MorpheEngineImpl.kt`
- Modify: `app/src/main/java/com/orion/store/morphe/worker/PatchJobRunner.kt`
- Test: `app/src/test/java/com/orion/store/morphe/ApkClonerTest.kt`

**Interfaces:**
- Consumes: Input APK file, output APK file, new package name string
- Produces:
  ```kotlin
  object ApkCloner {
      fun clonePackage(inputApk: File, outputApk: File, targetPkg: String, newPkg: String): Boolean
  }
  ```

- [ ] **Step 1: Write unit test for `ApkCloner`**

Create `app/src/test/java/com/orion/store/morphe/ApkClonerTest.kt`:
```kotlin
package com.orion.store.morphe

import com.orion.store.morphe.engine.ApkCloner
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ApkClonerTest {

    @Test
    fun testApkClonerRewritesManifestPackageName() {
        val tempIn = File.createTempFile("test_in", ".apk")
        val tempOut = File.createTempFile("test_out", ".apk")

        // Create dummy APK with manifest
        ZipOutputStream(FileOutputStream(tempIn)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("package=\"com.test.original\" android:authorities=\"com.test.original.provider\"".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(ByteArray(100))
            zos.closeEntry()
        }

        val success = ApkCloner.clonePackage(tempIn, tempOut, "com.test.original", "com.test.cloned")
        assertTrue(success)

        var manifestContent = ""
        ZipFile(tempOut).use { zf ->
            zf.getInputStream(zf.getEntry("AndroidManifest.xml")).use {
                manifestContent = it.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(manifestContent.contains("com.test.cloned"))

        tempIn.delete()
        tempOut.delete()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ApkClonerTest*" --offline`
Expected: FAIL due to missing `ApkCloner`.

- [ ] **Step 3: Implement `ApkCloner.kt`**

Create `app/src/main/java/com/orion/store/morphe/engine/ApkCloner.kt`:
```kotlin
package com.orion.store.morphe.engine

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Clones an APK by rewriting the root package name and provider authorities in the binary manifest.
 * Allows patched apps to be installed alongside their original counterparts without signature conflicts.
 */
object ApkCloner {

    fun clonePackage(
        inputApk: File,
        outputApk: File,
        targetPkg: String,
        newPkg: String
    ): Boolean {
        if (!inputApk.exists()) return false
        outputApk.parentFile?.mkdirs()

        try {
            ZipFile(inputApk).use { inZip ->
                ZipOutputStream(FileOutputStream(outputApk)).use { outZip ->
                    val buffer = ByteArray(65536)
                    for (entry in inZip.entries().asSequence()) {
                        val name = entry.name
                        // Omit stale signatures
                        if (name.startsWith("META-INF/") && (name.endsWith(".SF", true) || name.endsWith(".RSA", true) || name.endsWith(".DSA", true) || name.endsWith(".EC", true) || name.equals("META-INF/MANIFEST.MF", true))) {
                            continue
                        }

                        if (name == "AndroidManifest.xml") {
                            val originalBytes = inZip.getInputStream(entry).use { it.readBytes() }
                            val modifiedBytes = rewriteAxmlPackage(originalBytes, targetPkg, newPkg)
                            val manifestEntry = ZipEntry("AndroidManifest.xml")
                            outZip.putNextEntry(manifestEntry)
                            outZip.write(modifiedBytes)
                            outZip.closeEntry()
                        } else {
                            val newEntry = ZipEntry(name)
                            if (name == "resources.arsc" || name.endsWith(".so", ignoreCase = true) || entry.method == ZipEntry.STORED) {
                                newEntry.method = ZipEntry.STORED
                                newEntry.size = entry.size
                                newEntry.compressedSize = entry.size
                                newEntry.crc = entry.crc
                            } else {
                                newEntry.method = ZipEntry.DEFLATED
                            }
                            outZip.putNextEntry(newEntry)
                            if (!entry.isDirectory) {
                                inZip.getInputStream(entry).use { input ->
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        outZip.write(buffer, 0, read)
                                    }
                                }
                            }
                            outZip.closeEntry()
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun rewriteAxmlPackage(axmlBytes: ByteArray, oldPkg: String, newPkg: String): ByteArray {
        val targetOld = oldPkg.toByteArray(Charsets.UTF_8)
        val targetNew = newPkg.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(axmlBytes.size + 1024)

        var i = 0
        while (i < axmlBytes.size) {
            var match = true
            if (i + targetOld.size <= axmlBytes.size) {
                for (j in targetOld.indices) {
                    if (axmlBytes[i + j] != targetOld[j]) {
                        match = false
                        break
                    }
                }
            } else {
                match = false
            }

            if (match) {
                out.write(targetNew)
                i += targetOld.size
            } else {
                out.write(axmlBytes[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }
}
```

- [ ] **Step 4: Integrate `ApkCloner` in `PatchJobRunner.kt`**

In `PatchJobRunner.kt`, check if `currentJob.params.patchOptions["clonePackageName"]` is provided. If so, pass through `ApkCloner.clonePackage` prior to signing.

- [ ] **Step 5: Run tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ApkClonerTest*" --offline`
Expected: PASS.

- [ ] **Step 6: Commit changes**

```bash
git add app/src/main/java/com/orion/store/morphe/engine/ApkCloner.kt app/src/main/java/com/orion/store/morphe/worker/PatchJobRunner.kt app/src/test/java/com/orion/store/morphe/ApkClonerTest.kt
git commit -m "feat(patcher): implement binary package cloner for side-by-side app coexistence"
```

---

### Task 4: Post-Patch Installation Execution & "Show in Folder" (`SessionInstall.kt`, `MainViewModel.kt`)

**Files:**
- Modify: `app/src/main/java/com/orion/store/platform/SessionInstall.kt`
- Modify: `app/src/main/java/com/orion/store/ui/MainViewModel.kt`

**Interfaces:**
- Produces:
  ```kotlin
  fun showInFolder(file: File)
  ```

- [ ] **Step 1: Add `showInFolder` method in `MainViewModel.kt`**

In `MainViewModel.kt`, add:
```kotlin
fun showInFolder(file: File) {
    viewModelScope.launch(Dispatchers.Main) {
        if (!file.exists()) {
            toast("File not found.")
            return@launch
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(intent, "Open file with...").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            e.printStackTrace()
            toast("Could not open file manager: ${e.message}")
        }
    }
}
```

- [ ] **Step 2: Update `SessionInstall.commit` to ensure foreground intent delivery**

In `SessionInstall.kt`, update `onResult`:
When `status == PackageInstaller.STATUS_PENDING_USER_ACTION`, ensure `next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)` and launch via `ctx.startActivity(next)`.

- [ ] **Step 3: Run compilation and test suite**

Run: `./gradlew :app:compileDebugKotlin --offline`
Expected: SUCCESS.

- [ ] **Step 4: Commit changes**

```bash
git add app/src/main/java/com/orion/store/platform/SessionInstall.kt app/src/main/java/com/orion/store/ui/MainViewModel.kt
git commit -m "feat(install): add show in folder action and harden session install intent routing"
```

---

### Task 5: Queue & My Apps Complete UI Overhaul (`QueueTab.kt` & `MyApps.kt`)

**Files:**
- Modify: `app/src/main/java/com/orion/store/morphe/ui/tabs/QueueTab.kt`
- Modify: `app/src/main/java/com/orion/store/ui/MyApps.kt`

- [ ] **Step 1: Upgrade Job Item Card in `QueueTab.kt`**

Update `QueueTab.kt`:
1. If job has `outputApkPath` and file exists, render primary **"Install Built APK"** button (for `SUCCEEDED` or `FAILED`).
2. Add a 3-dots overflow menu button with `DropdownMenu`:
   - 📂 **Show in Folder**: invokes `vm.showInFolder(File(job.outputApkPath))`
   - 📋 **View Live Logs**: opens `PatchConsoleLogSheet`
   - 🔄 **Clone & Repatch**: launches clone package dialog
   - 🗑️ **Uninstall Original App**: checks if installed, prompts `vm.requestUninstall(job.params.packageName)`
   - ❌ **Remove from Queue**: cancels job, deletes job from `JobStore`, and calls `PatchStorageCleaner.purgeAppCache(context, job.params.packageName)`.

- [ ] **Step 2: Upgrade Job and Failed cards in `MyApps.kt`**

In `MyApps.kt`:
Ensure completed or failed jobs offer:
- Primary: `Install Built APK`
- Overflow dropdown: `Show in Folder`, `View Live Logs`, `Uninstall Original`, `Remove from Queue` (which calls `PatchStorageCleaner.purgeAppCache(context, job.params.packageName)`).

- [ ] **Step 3: Run unit tests and lint**

Run: `./gradlew :app:testDebugUnitTest --offline`
Expected: PASS.

- [ ] **Step 4: Commit changes**

```bash
git add app/src/main/java/com/orion/store/morphe/ui/tabs/QueueTab.kt app/src/main/java/com/orion/store/ui/MyApps.kt
git commit -m "feat(ui): overhaul QueueTab and MyApps with full action bar, dropdown menu, and deep cache purge"
```

---

### Task 6: Verification & End-to-End Live Testing

- [ ] **Step 1: Run complete offline unit test suite**

Run: `./gradlew :app:testDebugUnitTest --offline`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Assemble debug APK**

Run: `./gradlew :app:assembleDebug --offline`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install onto Samsung Galaxy S25 Ultra**

Run: `adb -s 192.168.29.119:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`.

- [ ] **Step 4: Live verification via adb logcat**

1. Launch Orion Store on device.
2. Verify Settings shows "Patcher APK & Bundle Cache" metrics and "Clear Cache" button works.
3. Patch Instagram with HushGram patches: verify split bundle is downloaded, normalized, patched, and signed.
4. Verify "Install Built APK" appears in Queue tab upon completion and commits installation cleanly.
5. Tap 3-dots overflow menu and verify "Show in Folder" opens Samsung My Files to the patched APK.
6. Test "Remove from Queue": verify job is removed and `base_apk_cache/` + `patcher_session/` for Instagram are purged.

