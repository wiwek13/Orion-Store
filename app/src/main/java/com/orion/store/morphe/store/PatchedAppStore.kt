package com.orion.store.morphe.store

import com.orion.store.OrionApp
import com.orion.store.morphe.model.PatchedApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Registry of installed patched applications managed by Morphe.
 * Persisted to disk via JSON so patched apps survive across app restarts.
 */
object PatchedAppStore {

    private const val FILE_NAME = "morphe_patched_apps.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val _apps = MutableStateFlow<List<PatchedApp>>(emptyList())
    val apps: StateFlow<List<PatchedApp>> = _apps.asStateFlow()

    private val _attentionCount = MutableStateFlow(0)
    val attentionCount: StateFlow<Int> = _attentionCount.asStateFlow()

    private val storageFile: File
        get() = File(OrionApp.instance.filesDir, FILE_NAME)

    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private val writeLock = Any()

    init {
        loadFromDisk()
        ioScope.launch {
            syncWithStorageFiles()
        }
    }

    private fun loadFromDisk() {
        try {
            val file = storageFile
            if (file.exists() && file.length() > 0L) {
                val list = json.decodeFromString<List<PatchedApp>>(file.readText())
                _apps.value = list
                recalculateAttention()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Reads applied_patches.json from Download/OrionStore/<appName>/ and syncs them into the app.
     * Automatically handles changes made externally to the patch files.
     */
    fun syncWithStorageFiles() {
        try {
            com.orion.store.platform.OrionStorageManager.migrateLegacyDownloads()
            val manifests = com.orion.store.platform.OrionStorageManager.scanAllAppFolders()
            if (manifests.isNotEmpty()) {
                val current = _apps.value.associateBy { it.packageName }.toMutableMap()
                var changed = false
                for (m in manifests) {
                    val existing = current[m.packageName]
                    if (existing == null) {
                        current[m.packageName] = PatchedApp(
                            packageName = m.packageName,
                            appName = m.appName,
                            installedVersion = m.version,
                            targetVersion = m.version,
                            patchBundleId = m.bundleName,
                            patchBundleVersion = "",
                            appliedPatches = m.appliedPatches,
                            installedAt = m.lastPatchedAt
                        )
                        changed = true
                    } else if (existing.appliedPatches != m.appliedPatches) {
                        // File on disk has updated patches! Sync them into app
                        current[m.packageName] = existing.copy(appliedPatches = m.appliedPatches)
                        changed = true
                    }
                }
                if (changed) {
                    _apps.value = current.values.toList()
                    recalculateAttention()
                    saveToDisk()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveToDisk() {
        val snapshot = _apps.value
        ioScope.launch {
            synchronized(writeLock) {
                try {
                    val file = storageFile
                    file.parentFile?.mkdirs()
                    val text = json.encodeToString(snapshot)
                    val atomic = android.util.AtomicFile(file)
                    val stream = atomic.startWrite()
                    try {
                        stream.write(text.toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(stream)
                    } catch (e: Exception) {
                        atomic.failWrite(stream)
                        throw e
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun recalculateAttention() {
        _attentionCount.value = _apps.value.count { it.needsAttention }
    }

    fun registerApp(app: PatchedApp) {
        _apps.update { list ->
            listOf(app) + list.filterNot { it.packageName == app.packageName }
        }
        recalculateAttention()
        saveToDisk()

        // Also ensure applied_patches.json is saved/updated on disk in Download/OrionStore/<appName>/
        try {
            com.orion.store.platform.OrionStorageManager.saveAppliedPatches(
                com.orion.store.platform.AppPatchManifest(
                    packageName = app.packageName,
                    appName = app.appName,
                    version = app.installedVersion,
                    bundleName = app.patchBundleId,
                    bundleRepoUrl = "",
                    appliedPatches = app.appliedPatches,
                    patchOptions = emptyMap(),
                    lastPatchedAt = app.installedAt
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun removeApp(packageName: String) {
        val app = getApp(packageName)
        _apps.update { list -> list.filterNot { it.packageName == packageName } }
        recalculateAttention()
        saveToDisk()
        if (app != null) {
            com.orion.store.platform.OrionStorageManager.purgeAppStorage(app.appName, packageName)
        }
    }

    /** Removes the patched app registry entry without purging files on disk. */
    fun removeRecordOnly(packageName: String) {
        _apps.update { list -> list.filterNot { it.packageName == packageName } }
        recalculateAttention()
        saveToDisk()
    }

    fun getApp(packageName: String): PatchedApp? {
        return _apps.value.firstOrNull { it.packageName == packageName }
    }
}
