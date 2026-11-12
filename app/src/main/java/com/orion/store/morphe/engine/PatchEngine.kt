package com.orion.store.morphe.engine

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Patch option / parameter definition (e.g. custom client spoof, hide shorts, bypass restrictions).
 */
@Serializable
data class PatchOption(
    val key: String,
    val title: String,
    val description: String,
    val type: OptionType = OptionType.BOOLEAN,
    val defaultBoolean: Boolean = true,
    val defaultString: String = "",
    val defaultInt: Int = 0,
    val currentBoolean: Boolean = true,
    val currentString: String = "",
    val currentInt: Int = 0
)

@Serializable
enum class OptionType {
    BOOLEAN, STRING, INT, SELECTION
}

/**
 * Individual patch metadata within a patch bundle (.mpp).
 */
@Serializable
data class MorphePatch(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val category: String = "",
    val compatiblePackages: List<String> = emptyList(),
    val compatibleVersions: List<String> = emptyList(), // empty = any version
    val isUniversal: Boolean = false,
    val enabledByDefault: Boolean = true,
    val options: List<PatchOption> = emptyList()
)

/**
 * Patch bundle representing an ecosystem repository release.
 */
@Serializable
data class PatchBundle(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val website: String = "",
    val repositoryUrl: String = "",
    val releaseDate: String = "",
    val patches: List<MorphePatch> = emptyList(),
    val signatureSha256: String = ""
)

/**
 * Result of a patching operation.
 */
sealed class PatchResult {
    data class Success(
        val outputFile: File,
        val logs: List<String>,
        val appliedPatches: List<String> = emptyList(),
        val skippedPatches: List<String> = emptyList()
    ) : PatchResult()
    data class Failure(val reason: String, val error: Throwable? = null, val logs: List<String> = emptyList()) : PatchResult()
}

/**
 * Compatibility verification report.
 */
data class CompatibilityReport(
    val isCompatible: Boolean,
    val targetPackage: String,
    val targetVersion: String,
    val warnings: List<String> = emptyList(),
    val supportedPatches: List<MorphePatch> = emptyList(),
    val incompatiblePatches: List<MorphePatch> = emptyList()
)

/**
 * Decoupled PatchEngine interface.
 * Can be mocked or substituted with native dex/bytecode patchers (e.g. smali, baksmali, lspatch, revanced).
 */
interface PatchEngine {
    val engineVersion: String
    val engineName: String

    suspend fun verifyCompatibility(
        apkFile: File,
        packageName: String,
        versionName: String,
        patches: List<MorphePatch>
    ): CompatibilityReport

    suspend fun applyPatches(
        inputApk: File,
        selectedPatches: List<MorphePatch>,
        options: Map<String, String>,
        outputApk: File,
        splitApks: List<File> = emptyList(),
        onLog: (String) -> Unit = {},
        onProgress: (stage: String, percent: Int) -> Unit
    ): PatchResult

    suspend fun zipalign(
        inputApk: File,
        alignedApk: File
    ): Boolean

    suspend fun signApk(
        inputApk: File,
        signedApk: File,
        keyAlias: String
    ): Boolean
}
