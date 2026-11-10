package com.orion.store.morphe.store

import com.orion.store.OrionApp
import com.orion.store.morphe.model.PatchedApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    init {
        loadFromDisk()
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

    private fun saveToDisk() {
        try {
            val file = storageFile
            val text = json.encodeToString(_apps.value)
            file.writeText(text)
        } catch (e: Exception) {
            e.printStackTrace()
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
    }

    fun removeApp(packageName: String) {
        _apps.update { list -> list.filterNot { it.packageName == packageName } }
        recalculateAttention()
        saveToDisk()
    }

    fun getApp(packageName: String): PatchedApp? {
        return _apps.value.firstOrNull { it.packageName == packageName }
    }
}
