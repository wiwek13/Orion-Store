package com.orion.store.morphe.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.orion.store.OrionApp
import com.orion.store.morphe.store.MorpheCatalogRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Collections

data class MppBundleInspection(
    val mppFile: File,
    val recommendedVersion: String = "",
    val versionCodes: Map<String, Long> = emptyMap(),
    val patchCount: Int = 0
)

private data class RemotePatchOutcome(
    val success: Boolean,
    val error: String? = null,
    val appliedPatches: List<String> = emptyList(),
    val skippedPatches: List<String> = emptyList()
)

/**
 * Concrete implementation of [PatchEngine] backed by `app.morphe:morphe-patcher`
 * executing inside the isolated `:patcher` Service process (`android:largeHeap="true"`).
 */
class MorpheEngineImpl : PatchEngine {

    override val engineVersion: String = "2.3.0"
    override val engineName: String = "Morphe Patcher Core"

    override suspend fun verifyCompatibility(
        apkFile: File,
        packageName: String,
        versionName: String,
        patches: List<MorphePatch>
    ): CompatibilityReport {
        val supported = mutableListOf<MorphePatch>()
        val incompatible = mutableListOf<MorphePatch>()
        val warnings = mutableListOf<String>()

        for (patch in patches) {
            val pkgMatches = patch.isUniversal || patch.compatiblePackages.isEmpty() || patch.compatiblePackages.contains(packageName)
            val verMatches = patch.isUniversal || patch.compatibleVersions.isEmpty() || patch.compatibleVersions.contains(versionName)

            if (pkgMatches && verMatches) {
                supported.add(patch)
            } else if (pkgMatches && !verMatches) {
                warnings.add("Patch '${patch.name}' is untested on version $versionName (expected ${patch.compatibleVersions.joinToString()})")
                supported.add(patch)
            } else {
                incompatible.add(patch)
            }
        }

        return CompatibilityReport(
            isCompatible = incompatible.isEmpty() || supported.isNotEmpty(),
            targetPackage = packageName,
            targetVersion = versionName,
            warnings = warnings,
            supportedPatches = supported,
            incompatiblePatches = incompatible
        )
    }

    override suspend fun applyPatches(
        inputApk: File,
        selectedPatches: List<MorphePatch>,
        options: Map<String, String>,
        outputApk: File,
        splitApks: List<File>,
        onLog: (String) -> Unit,
        onProgress: (stage: String, percent: Int) -> Unit
    ): PatchResult = withContext(Dispatchers.IO) {
        val collectedLogs = Collections.synchronizedList(mutableListOf<String>())
        fun emitLog(line: String) {
            collectedLogs.add(line)
            onLog(line)
        }

        try {
            outputApk.parentFile?.mkdirs()
            val pkg = options["packageName"].orEmpty()
            val bundleName = options["bundleName"].orEmpty()
            val preferredRepo = options["bundleRepo"].orEmpty()
            val preferredDl = options["bundleDownloadUrl"].orEmpty()
            val clonePkg = options["clonePackageName"]?.trim().orEmpty()
            val deferCloning = options["deferCloning"] == "true"
            val skipFailingPatches = options["skipFailingPatches"] != "false"
            val allowUnsupportedVersion = options["allowUnsupportedVersion"] == "true"

            emitLog("[ENGINE] Initializing $engineName v$engineVersion for $pkg")
            onProgress("Resolving .mpp patch bundle...", 5)

            // Look up bundleRepo/downloadUrl from catalog patches if not explicitly passed
            val catalogRepoFromPatches = MorpheCatalogRepository.getPatchesForApp(pkg).first
                .firstOrNull { p ->
                    selectedPatches.any { it.name.equals(p.name, ignoreCase = true) } &&
                        (p.bundleRepo.isNotBlank() || p.bundleDownloadUrl.isNotBlank())
                }

            val mppFile = MppBundleResolver.resolveBundleFile(
                packageName = pkg,
                preferredRepo = preferredRepo.ifBlank { catalogRepoFromPatches?.bundleRepo.orEmpty() },
                preferredBundleName = bundleName.ifBlank { catalogRepoFromPatches?.bundleName.orEmpty() },
                preferredDownloadUrl = preferredDl.ifBlank { catalogRepoFromPatches?.bundleDownloadUrl.orEmpty() },
                onLog = ::emitLog
            ) ?: return@withContext PatchResult.Failure(
                reason = "Could not download or locate compiled .mpp patch bundle for $pkg (bundle='${bundleName.ifBlank { "default" }}'). Check repository connectivity.",
                logs = collectedLogs.toList()
            )

            // Build option map minus internal orchestration keys
            val internalKeys = setOf(
                "packageName",
                "bundleName",
                "bundleRepo",
                "bundleDownloadUrl",
                "clonePackageName",
                "deferCloning",
                "skipFailingPatches",
                "allowUnsupportedVersion"
            )
            val patchOptionsMap = buildMap<String, String> {
                for (p in selectedPatches) {
                    for (opt in p.options) {
                        val value = when (opt.type) {
                            OptionType.BOOLEAN -> opt.currentBoolean.toString()
                            OptionType.INT -> opt.currentInt.toString()
                            OptionType.STRING, OptionType.SELECTION -> opt.currentString
                        }
                        if (value.isNotEmpty() || options.containsKey(opt.key)) {
                            put(opt.key, options[opt.key] ?: value)
                        }
                    }
                }
                for ((k, v) in options) {
                    if (k !in internalKeys) put(k, v)
                }
            }

            val workDir = File(outputApk.parentFile, "patcher_work").apply {
                deleteRecursively()
                mkdirs()
            }

            val serviceResult = runRemotePatcherService(
                inputApk = inputApk,
                splitApks = splitApks,
                mppFile = mppFile,
                outputApk = outputApk,
                workDir = workDir,
                packageName = pkg,
                selectedPatchNames = selectedPatches.map { it.name },
                optionsJson = Json.encodeToString(patchOptionsMap),
                skipFailingPatches = skipFailingPatches,
                allowUnsupportedVersion = allowUnsupportedVersion,
                onLog = ::emitLog,
                onProgress = onProgress
            )

            workDir.deleteRecursively()

            if (!serviceResult.success) {
                return@withContext PatchResult.Failure(
                    reason = serviceResult.error ?: "MorphePatcherService failed",
                    logs = collectedLogs.toList()
                )
            }

            // Clone package cleanly only when cloning was requested inline
            if (!deferCloning && clonePkg.isNotEmpty() && clonePkg != pkg) {
                onProgress("Cloning package to $clonePkg for side-by-side install...", 92)
                emitLog("[CLONE] Rewriting package ID $pkg -> $clonePkg")
                val tempCloned = File(outputApk.parentFile, "cloned_temp_${System.currentTimeMillis()}.apk")
                if (ApkCloner.clonePackage(outputApk, tempCloned, pkg, clonePkg)) {
                    tempCloned.copyTo(outputApk, overwrite = true)
                    tempCloned.delete()
                }
            }

            onProgress("Patches applied successfully", 100)
            PatchResult.Success(
                outputFile = outputApk,
                logs = collectedLogs.toList(),
                appliedPatches = serviceResult.appliedPatches,
                skippedPatches = serviceResult.skippedPatches
            )
        } catch (t: Throwable) {
            emitLog("[ERROR] ${t.javaClass.simpleName}: ${t.message}")
            PatchResult.Failure(
                reason = t.message ?: "Unknown patch engine error",
                error = t,
                logs = collectedLogs.toList()
            )
        }
    }

    private val inspectionCache = java.util.concurrent.ConcurrentHashMap<String, MppBundleInspection>()

    /**
     * Resolves and inspects the target `.mpp` bundle in the isolated `:patcher` service to extract
     * exact supported APK `version` strings and device-ABI `versionCodes`.
     */
    suspend fun inspectBundleForPackage(
        packageName: String,
        preferredRepo: String = "",
        preferredBundleName: String = "",
        preferredDownloadUrl: String = "",
        onLog: (String) -> Unit = {}
    ): MppBundleInspection? = withContext(Dispatchers.IO) {
        val mppFile = MppBundleResolver.resolveBundleFile(
            packageName = packageName,
            preferredRepo = preferredRepo,
            preferredBundleName = preferredBundleName,
            preferredDownloadUrl = preferredDownloadUrl,
            onLog = onLog
        ) ?: return@withContext null

        val cacheKey = "${mppFile.absolutePath}|${mppFile.lastModified()}|$packageName"
        inspectionCache[cacheKey]?.let { return@withContext it }

        val ctx = OrionApp.instance
        val workDir = File(ctx.cacheDir, "morphe_inspect").apply { mkdirs() }
        val resultDeferred = CompletableDeferred<MppBundleInspection?>()

        val clientHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == MorphePatcherService.MSG_INSPECT_RESULT) {
                    val bundle = msg.data
                    val ok = bundle.getBoolean(MorphePatcherService.KEY_SUCCESS, false)
                    if (!ok) {
                        val err = bundle.getString(MorphePatcherService.KEY_ERROR).orEmpty()
                        if (err.isNotBlank()) onLog("[WARN] .mpp inspection failed: $err")
                        resultDeferred.complete(null)
                        return
                    }
                    val recVer = bundle.getString(MorphePatcherService.KEY_RECOMMENDED_VERSION).orEmpty().trim()
                    val count = bundle.getInt(MorphePatcherService.KEY_PATCH_COUNT, 0)
                    val codesRaw = bundle.getString(MorphePatcherService.KEY_VERSION_CODES_JSON).orEmpty()
                    val codesMap = runCatching {
                        Json.parseToJsonElement(codesRaw).jsonObject
                            .mapNotNull { (k, v) -> v.jsonPrimitive.longOrNull?.let { k to it } }
                            .toMap()
                    }.getOrDefault(emptyMap())
                    resultDeferred.complete(
                        MppBundleInspection(
                            mppFile = mppFile,
                            recommendedVersion = recVer,
                            versionCodes = codesMap,
                            patchCount = count
                        )
                    )
                } else {
                    super.handleMessage(msg)
                }
            }
        }
        val replyMessenger = Messenger(clientHandler)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    resultDeferred.complete(null)
                    return
                }
                val remoteMessenger = Messenger(service)
                val msg = Message.obtain(null, MorphePatcherService.MSG_INSPECT_MPP).apply {
                    replyTo = replyMessenger
                    data = Bundle().apply {
                        putString(MorphePatcherService.KEY_MPP_FILE, mppFile.absolutePath)
                        putString(MorphePatcherService.KEY_PACKAGE_NAME, packageName)
                        putString(MorphePatcherService.KEY_WORK_DIR, workDir.absolutePath)
                    }
                }
                runCatching { remoteMessenger.send(msg) }.onFailure { resultDeferred.complete(null) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!resultDeferred.isCompleted) resultDeferred.complete(null)
            }
        }

        val intent = Intent(ctx, MorphePatcherService::class.java)
        val bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) return@withContext null

        try {
            val result = withTimeoutOrNull(25_000L) { resultDeferred.await() }
            if (result != null) {
                inspectionCache[cacheKey] = result
            }
            result
        } finally {
            runCatching { ctx.unbindService(connection) }
        }
    }

    private suspend fun runRemotePatcherService(
        inputApk: File,
        splitApks: List<File>,
        mppFile: File,
        outputApk: File,
        workDir: File,
        packageName: String,
        selectedPatchNames: List<String>,
        optionsJson: String,
        skipFailingPatches: Boolean,
        allowUnsupportedVersion: Boolean,
        onLog: (String) -> Unit,
        onProgress: (String, Int) -> Unit
    ): RemotePatchOutcome {
        val ctx = OrionApp.instance
        val resultDeferred = CompletableDeferred<RemotePatchOutcome>()

        val clientHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val bundle = msg.data
                when (msg.what) {
                    MorphePatcherService.MSG_LOG -> {
                        bundle.getString(MorphePatcherService.KEY_LOG_LINE)?.let(onLog)
                    }
                    MorphePatcherService.MSG_PROGRESS -> {
                        val stage = bundle.getString(MorphePatcherService.KEY_STAGE).orEmpty()
                        val pct = bundle.getInt(MorphePatcherService.KEY_PERCENT, 0)
                        onProgress(stage, pct)
                    }
                    MorphePatcherService.MSG_RESULT -> {
                        val ok = bundle.getBoolean(MorphePatcherService.KEY_SUCCESS, false)
                        val err = bundle.getString(MorphePatcherService.KEY_ERROR)
                        val applied = bundle.getStringArrayList(MorphePatcherService.KEY_APPLIED_PATCHES).orEmpty()
                        val skipped = bundle.getStringArrayList(MorphePatcherService.KEY_SKIPPED_PATCHES).orEmpty()
                        resultDeferred.complete(
                            RemotePatchOutcome(
                                success = ok,
                                error = err,
                                appliedPatches = applied,
                                skippedPatches = skipped
                            )
                        )
                    }
                    else -> super.handleMessage(msg)
                }
            }
        }
        val replyMessenger = Messenger(clientHandler)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    resultDeferred.complete(RemotePatchOutcome(false, "MorphePatcherService binder was null"))
                    return
                }
                val remoteMessenger = Messenger(service)
                val msg = Message.obtain(null, MorphePatcherService.MSG_RUN_PATCH).apply {
                    replyTo = replyMessenger
                    data = Bundle().apply {
                        putString(MorphePatcherService.KEY_INPUT_APK, inputApk.absolutePath)
                        putStringArrayList(MorphePatcherService.KEY_SPLIT_APKS, ArrayList(splitApks.map { it.absolutePath }))
                        putString(MorphePatcherService.KEY_MPP_FILE, mppFile.absolutePath)
                        putString(MorphePatcherService.KEY_OUTPUT_APK, outputApk.absolutePath)
                        putString(MorphePatcherService.KEY_WORK_DIR, workDir.absolutePath)
                        putString(MorphePatcherService.KEY_PACKAGE_NAME, packageName)
                        putStringArrayList(MorphePatcherService.KEY_SELECTED_PATCHES, ArrayList(selectedPatchNames))
                        putString(MorphePatcherService.KEY_OPTIONS_JSON, optionsJson)
                        putBoolean(MorphePatcherService.KEY_SKIP_FAILING_PATCHES, skipFailingPatches)
                        putBoolean(MorphePatcherService.KEY_ALLOW_UNSUPPORTED_VERSION, allowUnsupportedVersion)
                    }
                }
                try {
                    remoteMessenger.send(msg)
                } catch (t: Throwable) {
                    resultDeferred.complete(RemotePatchOutcome(false, "IPC dispatch failed: ${t.message}"))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(
                        RemotePatchOutcome(false, ":patcher process terminated unexpectedly (check memory/OOM logs)")
                    )
                }
            }
        }

        val intent = Intent(ctx, MorphePatcherService::class.java)
        val bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            return RemotePatchOutcome(false, "Unable to bind isolated :patcher service")
        }

        return try {
            resultDeferred.await()
        } finally {
            runCatching { ctx.unbindService(connection) }
        }
    }

    override suspend fun zipalign(inputApk: File, alignedApk: File): Boolean = withContext(Dispatchers.IO) {
        alignedApk.parentFile?.mkdirs()
        if (!inputApk.exists()) return@withContext false
        try {
            // Ensure resources.arsc and native .so libs are uncompressed (STORED) for Android R+ / 16KB alignment
            val needsRewrite = java.util.zip.ZipFile(inputApk).use { zip ->
                zip.entries().asSequence().any { e ->
                    !e.isDirectory &&
                        (e.name == "resources.arsc" || e.name.endsWith(".so", ignoreCase = true)) &&
                        e.method != java.util.zip.ZipEntry.STORED
                }
            }
            if (!needsRewrite) {
                inputApk.copyTo(alignedApk, overwrite = true)
            } else {
                rewriteEnsuringStoredEntries(inputApk, alignedApk)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun rewriteEnsuringStoredEntries(inputApk: File, outputApk: File) {
        val buf = ByteArray(65536)
        java.util.zip.ZipFile(inputApk).use { inZip ->
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(outputApk)).use { outZip ->
                for (entry in inZip.entries().asSequence()) {
                    val mustStore = !entry.isDirectory && (
                        entry.name == "resources.arsc" ||
                            entry.name.endsWith(".so", ignoreCase = true) ||
                            entry.method == java.util.zip.ZipEntry.STORED
                        )
                    val outEntry = java.util.zip.ZipEntry(entry.name)
                    if (mustStore) {
                        outEntry.method = java.util.zip.ZipEntry.STORED
                        val (sz, crcVal) = streamSizeAndCrc(inZip, entry, buf)
                        outEntry.size = sz
                        outEntry.compressedSize = sz
                        outEntry.crc = crcVal
                        outZip.putNextEntry(outEntry)
                        inZip.getInputStream(entry).use { inp ->
                            var r: Int
                            while (inp.read(buf).also { r = it } != -1) outZip.write(buf, 0, r)
                        }
                        outZip.closeEntry()
                    } else {
                        outEntry.method = java.util.zip.ZipEntry.DEFLATED
                        outZip.putNextEntry(outEntry)
                        if (!entry.isDirectory) {
                            inZip.getInputStream(entry).use { inp ->
                                var r: Int
                                while (inp.read(buf).also { r = it } != -1) outZip.write(buf, 0, r)
                            }
                        }
                        outZip.closeEntry()
                    }
                }
            }
        }
    }

    private fun streamSizeAndCrc(
        zip: java.util.zip.ZipFile,
        entry: java.util.zip.ZipEntry,
        buf: ByteArray
    ): Pair<Long, Long> {
        val crc = java.util.zip.CRC32()
        var total = 0L
        zip.getInputStream(entry).use { inp ->
            var r: Int
            while (inp.read(buf).also { r = it } != -1) {
                crc.update(buf, 0, r)
                total += r
            }
        }
        return total to crc.value
    }

    override suspend fun signApk(inputApk: File, signedApk: File, keyAlias: String): Boolean {
        signedApk.parentFile?.mkdirs()
        val success = com.orion.store.morphe.store.KeyStoreManager.signApkFile(inputApk, signedApk, keyAlias)
        if (!success) {
            throw IllegalStateException("Failed to sign APK: ${inputApk.name} with key '$keyAlias'. Verify input APK is a valid zip archive.")
        }
        return true
    }
}
