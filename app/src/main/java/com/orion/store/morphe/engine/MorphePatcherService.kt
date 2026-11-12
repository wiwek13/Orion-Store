package com.orion.store.morphe.engine

import android.app.Service
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherProgress
import app.morphe.patcher.PatcherTask
import app.morphe.patcher.apk.ApkMerger
import app.morphe.patcher.logging.LogLevel
import app.morphe.patcher.logging.Logger
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.SupportedAbi
import app.morphe.patcher.patch.loadPatchesFromDex
import app.morphe.patcher.resource.CpuArchitecture
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.logging.Handler as JulHandler
import java.util.logging.Level as JulLevel
import java.util.logging.LogRecord
import java.util.logging.Logger as JulLogger

/**
 * Isolated `:patcher` Service process (`android:largeHeap="true"`) that runs real
 * `app.morphe:morphe-patcher` bytecode & resource patching off the main UI process.
 */
class MorphePatcherService : Service() {

    companion object {
        const val MSG_RUN_PATCH = 1
        const val MSG_CANCEL = 2
        const val MSG_INSPECT_MPP = 3
        const val MSG_LOG = 10
        const val MSG_PROGRESS = 11
        const val MSG_RESULT = 12
        const val MSG_INSPECT_RESULT = 13

        const val KEY_INPUT_APK = "input_apk"
        const val KEY_SPLIT_APKS = "split_apks"
        const val KEY_MPP_FILE = "mpp_file"
        const val KEY_OUTPUT_APK = "output_apk"
        const val KEY_WORK_DIR = "work_dir"
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_SELECTED_PATCHES = "selected_patches"
        const val KEY_OPTIONS_JSON = "options_json"
        const val KEY_SKIP_FAILING_PATCHES = "skip_failing_patches"
        const val KEY_ALLOW_UNSUPPORTED_VERSION = "allow_unsupported_version"

        const val KEY_LOG_LINE = "log_line"
        const val KEY_STAGE = "stage"
        const val KEY_PERCENT = "percent"
        const val KEY_SUCCESS = "success"
        const val KEY_ERROR = "error"
        const val KEY_APPLIED_PATCHES = "applied_patches"
        const val KEY_SKIPPED_PATCHES = "skipped_patches"
        const val KEY_RECOMMENDED_VERSION = "recommended_version"
        const val KEY_VERSION_CODES_JSON = "version_codes_json"
        const val KEY_PATCH_COUNT = "patch_count"

        /**
         * Maps device ABIs (e.g. `arm64-v8a`) into `morphe-patcher` [CpuArchitecture] flags.
         */
        internal fun resolveDeviceArchitectures(supportedAbis: Array<String>): Set<CpuArchitecture> {
            val out = linkedSetOf<CpuArchitecture>()
            for (abi in supportedAbis) {
                when (abi.trim().lowercase()) {
                    "arm64-v8a" -> out.add(CpuArchitecture.ARM64_V8A)
                    "armeabi-v7a" -> out.add(CpuArchitecture.ARMEABI_V7A)
                    "armeabi" -> out.add(CpuArchitecture.ARMEABI)
                    "x86_64" -> out.add(CpuArchitecture.X86_64)
                    "x86" -> out.add(CpuArchitecture.X86)
                }
            }
            return out
        }

        internal fun resolvePrimarySupportedAbi(supportedAbis: Array<String>): SupportedAbi? {
            for (abi in supportedAbis) {
                when (abi.trim().lowercase()) {
                    "arm64-v8a" -> return SupportedAbi.ARM64_V8A
                    "armeabi-v7a" -> return SupportedAbi.ARMEABI_V7A
                    "x86_64" -> return SupportedAbi.X86_64
                    "x86" -> return SupportedAbi.X86
                }
            }
            return null
        }

        internal fun resolveTargetVersionCode(
            versionCodes: Map<SupportedAbi, Int>,
            supportedAbis: Array<String>
        ): Int? {
            if (versionCodes.isEmpty()) return null
            for (abi in supportedAbis) {
                val mapped = when (abi.trim().lowercase()) {
                    "arm64-v8a" -> SupportedAbi.ARM64_V8A
                    "armeabi-v7a" -> SupportedAbi.ARMEABI_V7A
                    "x86_64" -> SupportedAbi.X86_64
                    "x86" -> SupportedAbi.X86
                    else -> null
                }
                if (mapped != null) {
                    versionCodes[mapped]?.let { return it }
                }
            }
            return versionCodes[SupportedAbi.ARM64_V8A] ?: versionCodes.values.firstOrNull()
        }

        /**
         * Applies serialized string option overrides onto a loaded `morphe-patcher` [Patch].
         */
        @Suppress("UNCHECKED_CAST")
        internal fun applyPatchOptions(patch: Patch, optionsMap: Map<String, String>, onLog: (String) -> Unit) {
            if (optionsMap.isEmpty() || patch.options.isEmpty()) return
            for ((optKey, opt) in patch.options) {
                val raw = optionsMap[optKey] ?: continue
                try {
                    val typedOpt = opt as Option<Any?>
                    val converted: Any? = when (opt.default) {
                        is Boolean -> raw.trim().lowercase().toBooleanStrictOrNull() ?: continue
                        is Int -> raw.trim().toIntOrNull() ?: continue
                        is Long -> raw.trim().toLongOrNull() ?: continue
                        is Float -> raw.trim().toFloatOrNull() ?: continue
                        else -> raw
                    }
                    typedOpt.value = converted
                    onLog("[OPT] ${patch.name}.$optKey = $converted")
                } catch (t: Throwable) {
                    onLog("[OPT WARN] Failed setting ${patch.name}.$optKey: ${t.message}")
                }
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    private val incomingHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_RUN_PATCH -> {
                    val replyTo = msg.replyTo ?: return
                    val data = msg.data ?: return
                    activeJob?.cancel()
                    activeJob = serviceScope.launch {
                        executePatchTask(data, replyTo)
                    }
                }
                MSG_INSPECT_MPP -> {
                    val replyTo = msg.replyTo ?: return
                    val data = msg.data ?: return
                    serviceScope.launch {
                        executeInspectTask(data, replyTo)
                    }
                }
                MSG_CANCEL -> {
                    activeJob?.cancel()
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private val messenger = Messenger(incomingHandler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun sendLog(replyTo: Messenger, line: String) {
        val m = Message.obtain(null, MSG_LOG)
        m.data = Bundle().apply { putString(KEY_LOG_LINE, line) }
        runCatching { replyTo.send(m) }
    }

    @Volatile private var lastProgressStage: String = ""
    @Volatile private var lastProgressPercent: Int = -1
    @Volatile private var lastProgressSentAt: Long = 0L

    private fun resetProgressThrottle() {
        lastProgressStage = ""
        lastProgressPercent = -1
        lastProgressSentAt = 0L
    }

    private fun ensureReadOnlyMpp(mppFile: File, workDir: File): File {
        mppFile.setReadOnly()
        if (!mppFile.canWrite()) return mppFile
        val roCopy = File(workDir, "ro_${mppFile.name}")
        if (roCopy.exists()) {
            roCopy.setWritable(true)
            roCopy.delete()
        }
        mppFile.copyTo(roCopy, overwrite = true)
        roCopy.setReadOnly()
        return roCopy
    }

    private fun sendProgress(replyTo: Messenger, stage: String, percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val now = System.currentTimeMillis()
        if (stage == lastProgressStage) {
            if (clamped == lastProgressPercent) return
            if (clamped < 100 && (now - lastProgressSentAt) < 150L) return
        }
        lastProgressStage = stage
        lastProgressPercent = clamped
        lastProgressSentAt = now
        val m = Message.obtain(null, MSG_PROGRESS)
        m.data = Bundle().apply {
            putString(KEY_STAGE, stage)
            putInt(KEY_PERCENT, clamped)
        }
        runCatching { replyTo.send(m) }
    }

    private fun sendResult(
        replyTo: Messenger,
        success: Boolean,
        applied: ArrayList<String>,
        skipped: ArrayList<String> = arrayListOf(),
        error: String?
    ) {
        val m = Message.obtain(null, MSG_RESULT)
        m.data = Bundle().apply {
            putBoolean(KEY_SUCCESS, success)
            putStringArrayList(KEY_APPLIED_PATCHES, applied)
            putStringArrayList(KEY_SKIPPED_PATCHES, skipped)
            if (error != null) putString(KEY_ERROR, error)
        }
        runCatching { replyTo.send(m) }
    }

    private fun readArchivePackageInfo(apkFile: File): PackageInfo? {
        if (!apkFile.exists() || !apkFile.isFile) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
        }.getOrNull()
    }

    private fun executeInspectTask(data: Bundle, replyTo: Messenger) {
        val mppPath = data.getString(KEY_MPP_FILE).orEmpty()
        val workDirPath = data.getString(KEY_WORK_DIR).orEmpty()
        val targetPackage = data.getString(KEY_PACKAGE_NAME).orEmpty()
        try {
            val mppFile = File(mppPath)
            val workDir = File(workDirPath).apply { mkdirs() }
            require(mppFile.exists()) { "Patch bundle .mpp not found: $mppPath" }

            val readOnlyMpp = ensureReadOnlyMpp(mppFile, workDir)
            val dexOptDir = File(workDir, "dexopt").apply { mkdirs() }
            val loadedPatches = loadPatchesFromDex(setOf(readOnlyMpp), dexOptDir)

            val compatiblePatches = loadedPatches.filter { patch ->
                val compatPkgs = patch.compatibility?.map { it.packageName }.orEmpty()
                compatPkgs.isEmpty() || targetPackage.isBlank() || compatPkgs.any { it.equals(targetPackage, ignoreCase = true) }
            }

            val versionToCode = linkedMapOf<String, Long>()
            var recommendedVersion = ""
            var experimentalFallback = ""
            for (patch in compatiblePatches) {
                for (compat in patch.compatibility.orEmpty()) {
                    if (targetPackage.isNotBlank() && !compat.packageName.equals(targetPackage, ignoreCase = true)) {
                        continue
                    }
                    for (target in compat.targets) {
                        val ver = target.version?.trim().orEmpty()
                        if (ver.isNotEmpty()) {
                            if (!target.isExperimental) {
                                recommendedVersion = ver
                            } else if (experimentalFallback.isEmpty()) {
                                experimentalFallback = ver
                            }
                            val code = resolveTargetVersionCode(target.versionCodes.orEmpty(), Build.SUPPORTED_ABIS)
                            if (code != null && code > 0) {
                                versionToCode.putIfAbsent(ver, code.toLong())
                            }
                        }
                    }
                }
            }
            if (recommendedVersion.isEmpty()) {
                recommendedVersion = experimentalFallback
            }

            val m = Message.obtain(null, MSG_INSPECT_RESULT)
            m.data = Bundle().apply {
                putBoolean(KEY_SUCCESS, true)
                putString(KEY_RECOMMENDED_VERSION, recommendedVersion)
                putString(KEY_VERSION_CODES_JSON, Json.encodeToString(versionToCode))
                putInt(KEY_PATCH_COUNT, compatiblePatches.size)
            }
            runCatching { replyTo.send(m) }
        } catch (t: Throwable) {
            val m = Message.obtain(null, MSG_INSPECT_RESULT)
            m.data = Bundle().apply {
                putBoolean(KEY_SUCCESS, false)
                putString(KEY_ERROR, "${t.javaClass.simpleName}: ${t.message ?: "Inspection failed"}")
            }
            runCatching { replyTo.send(m) }
        }
    }

    private suspend fun executePatchTask(data: Bundle, replyTo: Messenger) {
        val inputApkPath = data.getString(KEY_INPUT_APK).orEmpty()
        val splitPaths = data.getStringArrayList(KEY_SPLIT_APKS).orEmpty()
        val mppPath = data.getString(KEY_MPP_FILE).orEmpty()
        val outputApkPath = data.getString(KEY_OUTPUT_APK).orEmpty()
        val workDirPath = data.getString(KEY_WORK_DIR).orEmpty()
        val targetPackage = data.getString(KEY_PACKAGE_NAME).orEmpty()
        val selectedNames = data.getStringArrayList(KEY_SELECTED_PATCHES).orEmpty()
        val optionsJson = data.getString(KEY_OPTIONS_JSON).orEmpty()
        val skipFailingPatches = data.getBoolean(KEY_SKIP_FAILING_PATCHES, true)
        val allowUnsupportedVersion = data.getBoolean(KEY_ALLOW_UNSUPPORTED_VERSION, false)

        resetProgressThrottle()
        val appliedList = arrayListOf<String>()
        val skippedList = arrayListOf<String>()
        val attachedLoggers = mutableListOf<Pair<JulLogger, JulHandler>>()

        try {
            val rawInputApk = File(inputApkPath)
            val mppFile = File(mppPath)
            val outputApk = File(outputApkPath)
            val workDir = File(workDirPath).apply { mkdirs() }
            require(rawInputApk.exists()) { "Input APK not found: $inputApkPath" }
            require(mppFile.exists()) { "Patch bundle .mpp not found: $mppPath" }

            // Attach JUL bridge so internal ARSCLib/morphe-patcher messages stream live (INFO+ to avoid Binder spam)
            val julHandler = object : JulHandler() {
                override fun publish(record: LogRecord?) {
                    if (record == null) return
                    if (record.level.intValue() < JulLevel.INFO.intValue()) return
                    val msg = record.message ?: return
                    val prefix = if (record.level.intValue() >= JulLevel.WARNING.intValue()) "[WARN]" else "[CORE]"
                    sendLog(replyTo, "$prefix $msg")
                }
                override fun flush() {}
                override fun close() {}
            }
            for (loggerName in listOf("app.morphe", "com.reandroid")) {
                val jl = JulLogger.getLogger(loggerName)
                jl.addHandler(julHandler)
                attachedLoggers.add(jl to julHandler)
            }

            // Skip TRACE to prevent libbinder oneway spamming during ApkMerger's thousands of entry writes
            val morpheLogger = object : Logger() {
                override fun log(level: LogLevel, message: String) {
                    if (level == LogLevel.TRACE) return
                    sendLog(replyTo, "[${level.name}] $message")
                }
            }

            // Step 1: Load compiled DEX patches from read-only .mpp BEFORE expensive split merge
            sendProgress(replyTo, "Loading patches from ${mppFile.name}...", 8)
            sendLog(replyTo, "[LOAD] Loading compiled patch DEX from ${mppFile.name}...")
            val readOnlyMpp = ensureReadOnlyMpp(mppFile, workDir)
            val dexOptDir = File(workDir, "dexopt").apply { mkdirs() }
            val loadedPatches = loadPatchesFromDex(setOf(readOnlyMpp), dexOptDir)
            sendLog(replyTo, "[LOAD] Discovered ${loadedPatches.size} patch(es) inside bundle")

            // Step 2: Filter patches for targetPackage & user selection
            val selectedNorm = selectedNames.map { it.trim().lowercase() }.toSet()
            val selectedSlug = selectedNames.map { it.trim().lowercase().replace(" ", "-") }.toSet()

            val compatiblePatches = loadedPatches.filter { patch ->
                val compatPkgs = patch.compatibility?.map { it.packageName }.orEmpty()
                compatPkgs.isEmpty() || targetPackage.isBlank() || compatPkgs.any { it.equals(targetPackage, ignoreCase = true) }
            }

            val patchesToApply = if (selectedNames.isEmpty()) {
                compatiblePatches.filter { it.default }.toSet()
            } else {
                compatiblePatches.filter { patch ->
                    val nameNorm = patch.name.orEmpty().trim().lowercase()
                    val nameSlug = nameNorm.replace(" ", "-")
                    nameNorm.isNotEmpty() && (nameNorm in selectedNorm || nameSlug in selectedSlug || nameNorm in selectedSlug)
                }.toSet()
            }

            if (patchesToApply.isEmpty()) {
                val hint = compatiblePatches.take(6).mapNotNull { it.name }.joinToString()
                error("No selected patches matched bundle '${mppFile.name}' for $targetPackage (selected=${selectedNames.size}, available sample=[$hint])")
            }

            // Step 2.5: Pre-flight APK manifest & bundle target compatibility check
            val pkgInfo = readArchivePackageInfo(rawInputApk)
            if (pkgInfo != null) {
                if (targetPackage.isNotBlank() && !pkgInfo.packageName.equals(targetPackage, ignoreCase = true)) {
                    error("Input APK package (${pkgInfo.packageName}) does not match target package ($targetPackage)")
                }
                val actualVer = pkgInfo.versionName?.trim().orEmpty()
                val actualCode = PackageInfoCompat.getLongVersionCode(pkgInfo)
                sendLog(replyTo, "[PRECHECK] Input APK: ${pkgInfo.packageName} v$actualVer ($actualCode)")

                val requiredVersions = linkedSetOf<String>()
                for (patch in patchesToApply) {
                    for (compat in patch.compatibility.orEmpty()) {
                        if (targetPackage.isBlank() || compat.packageName.equals(targetPackage, ignoreCase = true)) {
                            for (target in compat.targets) {
                                val v = target.version?.trim().orEmpty()
                                if (v.isNotEmpty()) requiredVersions.add(v)
                            }
                        }
                    }
                }
                if (requiredVersions.isNotEmpty() && actualVer.isNotEmpty() &&
                    requiredVersions.none { it.equals(actualVer, ignoreCase = true) }
                ) {
                    val reqLabel = requiredVersions.joinToString(", ")
                    if (!allowUnsupportedVersion) {
                        error(
                            "APK version mismatch before patching: input APK is v$actualVer ($actualCode), " +
                                "but '${mppFile.name}' targets [$reqLabel]. Purge cached base APK or enable 'Allow nearest APK version' in Morphe Settings."
                        )
                    } else {
                        sendLog(
                            replyTo,
                            "[WARN] Input APK v$actualVer ($actualCode) differs from bundle target(s) [$reqLabel]; continuing in nearest-version mode."
                        )
                    }
                }
            }

            val optionsMap = parseOptionsJson(optionsJson)
            patchesToApply.forEach { applyPatchOptions(it, optionsMap) { l -> sendLog(replyTo, l) } }

            // Step 3: Merge split APKs into a monolithic APK via ARSCLib ApkMerger when splits are present
            val effectiveInputApk: File = if (splitPaths.isNotEmpty()) {
                val validSplits = splitPaths.map(::File).filter { it.exists() && it.length() > 0L }
                if (validSplits.isNotEmpty()) {
                    sendProgress(replyTo, "Merging ${validSplits.size + 1} split APKs...", 14)
                    sendLog(replyTo, "[MERGE] Merging base + ${validSplits.size} config split(s) via ARSCLib ApkMerger...")
                    val splitStaging = File(workDir, "merge_staging").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    rawInputApk.copyTo(File(splitStaging, "base.apk"), overwrite = true)
                    validSplits.forEachIndexed { idx, sf ->
                        val safeName = sf.name.takeIf { it.endsWith(".apk", true) } ?: "split_$idx.apk"
                        sf.copyTo(File(splitStaging, safeName), overwrite = true)
                    }
                    val mergedApk = File(workDir, "merged_input.apk")
                    if (mergedApk.exists()) mergedApk.delete()
                    ApkMerger(morpheLogger).merge(splitStaging, mergedApk)
                    splitStaging.deleteRecursively()
                    require(mergedApk.exists() && mergedApk.length() > 1024L) {
                        "Split APK merge failed to produce valid merged_input.apk"
                    }
                    sendLog(replyTo, "[MERGE] Monolithic APK ready (${mergedApk.length() / (1024 * 1024)} MB)")
                    mergedApk
                } else {
                    rawInputApk
                }
            } else {
                rawInputApk
            }

            sendLog(replyTo, "[PATCHER] Executing ${patchesToApply.size} patch(es) in isolated :patcher process...")
            sendProgress(replyTo, "Decoding APK & resources...", 28)

            val tempPatcherDir = File(workDir, "patcher_tmp").apply {
                deleteRecursively()
                mkdirs()
            }

            val progressListener = object : PatcherProgress {
                override fun onProgress(task: PatcherTask, current: Long, total: Long) {
                    val ratio = if (total > 0L) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
                    val (label, base, span) = when (task) {
                        PatcherTask.DECODING_DEX -> Triple("Decoding DEX...", 28, 12)
                        PatcherTask.EXTRACTING_RESOURCES -> Triple("Extracting resources...", 36, 8)
                        PatcherTask.DECODING_RESOURCES -> Triple("Decoding ARSC resources...", 42, 12)
                        PatcherTask.INDEXING_CLASSES -> Triple("Indexing classes...", 52, 8)
                        PatcherTask.COMPILING_DEX -> Triple("Compiling patched DEX...", 76, 12)
                        PatcherTask.COMPILING_RESOURCES -> Triple("Compiling ARSC resources...", 86, 6)
                        PatcherTask.WRITING_APK -> Triple("Writing patched APK...", 92, 6)
                    }
                    sendProgress(replyTo, label, base + (ratio * span).toInt())
                }
            }

            val keepAbis = resolveDeviceArchitectures(Build.SUPPORTED_ABIS)
            val config = PatcherConfig(
                apkFile = effectiveInputApk,
                temporaryFilesPath = tempPatcherDir,
                keepArchitectures = keepAbis,
                progress = progressListener
            )

            var executedCount = 0
            val failures = mutableListOf<String>()

            Patcher(config).use { patcher ->
                patcher += patchesToApply
                patcher { executing ->
                    val patchName = executing.name?.trim().orEmpty()
                    if (patchName.isNotEmpty()) {
                        executedCount++
                        val pct = 60 + ((executedCount.toFloat() / patchesToApply.size.coerceAtLeast(1)) * 18f).toInt()
                        sendProgress(replyTo, "Applying: $patchName", pct.coerceAtMost(78))
                        sendLog(replyTo, "[EXEC] Applying '$patchName'...")
                    }
                }.collect { result ->
                    val ex = result.exception
                    val rawName = result.patch.name?.trim()
                    val pName = rawName?.takeIf { it.isNotEmpty() } ?: "Dependency (${result.patch.javaClass.simpleName})"
                    if (ex != null) {
                        val root = ex.cause?.message ?: ex.message ?: ex.javaClass.simpleName
                        val msg = "Patch '$pName' failed: $root"
                        failures.add(msg)
                        if (!rawName.isNullOrEmpty()) {
                            skippedList.add(rawName)
                        }
                        sendLog(replyTo, "[${if (skipFailingPatches) "WARN" else "ERROR"}] $msg")
                    } else {
                        if (!rawName.isNullOrEmpty()) {
                            appliedList.add(rawName)
                        }
                        sendLog(replyTo, "[OK] Applied '$pName'")
                    }
                }

                if (failures.isNotEmpty()) {
                    if (!skipFailingPatches) {
                        error(failures.first())
                    }
                    if (appliedList.isEmpty()) {
                        error("All selected patches failed (${failures.first()})")
                    }
                    sendLog(
                        replyTo,
                        "[WARN] ${failures.size} patch(es) failed and were skipped; building APK with ${appliedList.size} applied patch(es)."
                    )
                }

                sendProgress(replyTo, "Packaging patched APK...", 90)
                if (outputApk.exists()) outputApk.delete()
                outputApk.parentFile?.mkdirs()
                patcher.writeApk(outputApk)
            }

            tempPatcherDir.deleteRecursively()
            require(outputApk.exists() && outputApk.length() > 1024L) {
                "Patcher exited without producing ${outputApk.name}"
            }

            sendLog(replyTo, "[BUILD] Patched APK written (${outputApk.length() / (1024 * 1024)} MB, ${appliedList.size} patches, ${skippedList.size} skipped)")
            sendResult(replyTo, success = true, applied = appliedList, skipped = skippedList, error = null)
        } catch (t: Throwable) {
            val detail = "${t.javaClass.simpleName}: ${t.message ?: "Unknown patcher error"}"
            sendLog(replyTo, "[FATAL] $detail")
            sendResult(replyTo, success = false, applied = appliedList, skipped = skippedList, error = detail)
        } finally {
            for ((jl, h) in attachedLoggers) {
                runCatching { jl.removeHandler(h) }
            }
        }
    }

    private fun parseOptionsJson(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            obj.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap()
        }.getOrDefault(emptyMap())
    }
}
