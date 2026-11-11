# Orion Store 2.0 — Morphe Patching Pipeline, Storage Architecture & Installation Engine

> **Design Specification**  
> **Date**: 2026-11-11  
> **Target Device**: Samsung Galaxy S25 Ultra (One UI 7 / Android 15), generic Android 8.0+ devices  
> **Status**: Approved for Implementation Plan

---

## 1. Overview & Objectives

Orion Store integrates an on-device bytecode patching engine powered by the **Morphe** architecture (`MorpheApp/morphe-manager`, `Morphe DexPatcher Core`, ReAndroid). 

The goal of this architectural overhaul is to eliminate all remaining failure points across the end-to-end lifecycle:
1. **Multi-Source Acquisition Cascade**: Ensure ≥ 99.9% APK acquisition success rate via a 5-tier cascade (Orion Store mirrors → Google Play API → APKMirror → APKPure/Aptoide → Local file picker).
2. **Unified Split & Bundle Handling**: Transparently handle monolithic APKs, split APK bundles (`.apks`, `.xapk`, `.apkm`), extracting device-matching ABIs (`arm64-v8a`) and density splits without missing split errors (`INSTALL_FAILED_MISSING_SPLIT`).
3. **Storage & Cache Hierarchy**: Logically store and persist downloaded base APKs and patch bundles (`.mpp`) across patching sessions; retain built patched APKs permanently in `Downloads/` for re-installation; provide one-tap cache reclaim in Settings.
4. **Deep Queue Removal & Clean**: When an app is removed from the Queue, automatically purge the job, cancel active tasks, and wipe that app's cached base APKs and temporary session files.
5. **Package Conflict & Renaming Engine**: Resolve signature mismatches (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) with three options: "Proceed Anyway / Force Install", "Uninstall Original", or "Clone App with Custom Package Name" (rewriting binary `AndroidManifest.xml` package and provider authorities).
6. **Robust Installation Execution**: Guarantee post-patching installation prompts or silent execution across Shizuku, Dhizuku, Root, Wireless ADB, and Session PackageInstaller with foreground Activity routing on Android 14/15.
7. **Storage File Explorer Integration**: Surface a one-tap "Show in Folder" action to open the system file manager directly at the patched APK location.

---

## 2. Morphe Android Research & Architectural Foundations

### 2.1 Split APK Preparation (`SplitApkPreparer` & ReAndroid)
In `MorpheApp/morphe-manager`:
- `SplitApkPreparer.kt` checks incoming archives for multi-module structures.
- It detects split APKs while filtering out non-split embedded APKs (such as Wear OS companion apps in `res/raw/*.apk`).
- It applies device-specific filters (`shouldSkipModuleForDevice`) to discard non-matching CPU architectures (e.g. x86, armeabi-v7a) and extra language packs, leaving only the primary ABI (`arm64-v8a`) and the device screen density.
- In Orion Store, normalized splits must be preserved as a set:
  - Cache: `context.filesDir/base_apk_cache/<packageName>/bundle_<version>/` containing `base.apk` + `split_*.apk`.
  - Output: `Downloads/Patched_<App>_v<Version>.apks` (an uncompressed zip container holding signed `base.apk` and signed `split_*.apk`), unpackable by `Bundles.parts()`.

### 2.2 Bytecode Injection & Android 15 Page Alignment
In `MorpheEngineImpl.kt`:
- Multi-dex Dalvik bytecode injection appends extension classes as `classesN.dex`.
- `ZipEntry.STORED` is strictly enforced for `resources.arsc` and all `lib/**/*.so` native libraries with precomputed CRC-32 and byte sizes. This satisfies Android 11+ uncompressed resource rules and Android 15 16 KB memory page alignment requirements, preventing `INSTALL_FAILED_INVALID_APK (-124)`.

---

## 3. Subsystem Architecture & Component Design

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       MULTI-SOURCE APK ACQUISITION                          │
│  [Tier 1: Orion Mirrors]                                                    │
│        │ (fallback)                                                         │
│  [Tier 2: Google Play API (goopdl)]                                         │
│        │ (fallback)                                                         │
│  [Tier 3: APKMirror Resolver]                                               │
│        │ (fallback)                                                         │
│  [Tier 4: APKPure & Aptoide Resolvers]                                      │
│        │ (fallback)                                                         │
│  [Tier 5: Local Storage File Picker]                                        │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    STORAGE & CACHE MANAGER (PERSISTENT)                     │
│  • Base APK Cache: context.filesDir/base_apk_cache/<pkg>/bundle_<ver>/      │
│  • Patch Bundle Cache: context.filesDir/mpp_cache/                          │
│  • Output Patched APKs: Downloads/Patched_<App>_v<Ver>.apk (or .apks)       │
│  • Settings: One-Tap "Clear Cache" (Metrics: XX MB cached, Reclaim button)  │
│  • Deep Queue Removal: Purges job + cancels task + wipes app's cache        │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                       MORPHE SPLIT & CLONING ENGINE                         │
│  • Split Normalizer: Resolves device-matching ABI (arm64-v8a) & screen DPI │
│  • Binary AXML Package Cloner: Rewrites package & provider authorities      │
│  • Multi-Dex Bytecode Injector (ZipEntry.STORED for .so & resources.arsc)   │
│  • Deterministic v2/v3 Signing Engine (KeyStoreManager)                     │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     RESILIENT INSTALLATION & QUEUE UI                       │
│  • Privilege Cascade: Shizuku -> Dhizuku -> Root -> Direct PackageInstaller │
│  • Queue & My Apps Complete Dropdown Controls:                              │
│    ├─ [Install Built APK]                                                   │
│    ├─ [Show in Folder in Storage] (Intent.ACTION_VIEW folder)               │
│    ├─ [View Live Logs & Console]                                            │
│    ├─ [Clone with Custom Package Name] (Repatch)                            │
│    ├─ [Uninstall Original App]                                              │
│    └─ [Remove from Queue & Wipe Cache]                                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Detailed Component Specifications

### 4.1 Subsystem 1: Multi-Source Acquisition Cascade (`DownloadOrchestrator`)
The acquisition pipeline ensures an APK is always retrieved:
1. **Tier 1 (Orion Store Repository)**: Checks `apps.json` and mirror releases for official signed APKs with SHA-256 validation.
2. **Tier 2 (Google Play API)**: Queries Google Play via tokenized `goopdl` endpoints to retrieve authentic base APKs and split configs.
3. **Tier 3 (APKMirror Resolver - New `ApkMirrorResolver.kt`)**:
   - Searches `https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=<query>`
   - Resolves matching version and architecture (`arm64-v8a`, `universal`, or `nodpi`).
   - Scrapes the intermediate download link and tokenized download page to stream the APK/bundle.
4. **Tier 4 (APKPure & Aptoide Resolvers)**:
   - Queries APKPure `/b/APK/` and `/b/XAPK/` endpoints.
   - Inspects zip containers to unpack inner APKs if delivered under `.apk` headers.
   - Falls back to Aptoide API v7 search and direct download mirrors.
5. **Tier 5 (Local File Picker Fallback)**:
   - If network acquisition fails, preserves the user's selected patch configuration in `JobStore`.
   - Prompts the user with an "Open Document" picker. When selected, caches the provided APK and immediately resumes patching.

---

### 4.2 Subsystem 2: Storage Architecture & Cache Lifecycle

#### Storage Locations:
1. **Base APK Cache**:
   `context.filesDir/base_apk_cache/<packageName>/bundle_<version>/`
   - Contains `base.apk` and any `split_*.apk`.
   - Reused across patching attempts: changing patches or retrying never re-downloads the 150MB+ base APK.
2. **Patch Bundle Cache**:
   `context.filesDir/mpp_cache/`
   - Caches downloaded `.mpp` files (e.g. `hushgram_0.0.19.mpp`, `piko_*.mpp`) indexed by bundle name and version.
3. **Built Patched APKs**:
   `Environment.DIRECTORY_DOWNLOADS/Orion/Patched/` (via `Downloads.dir()`)
   - Monolithic: `Patched_<AppName>_v<Version>.apk`
   - Split-dependent: `Patched_<AppName>_v<Version>.apks`
   - Retained permanently for manual re-installation or sharing.
   - `PatchStorageCleaner.cleanOlderPatchedApks()` automatically deletes superseded builds of the same app, keeping only the single latest version.

#### Cache Reclaim & Settings:
- In `Settings.kt` under "Storage & Cache":
  - **Metrics**: Computes total storage consumed by `base_apk_cache/`, `mpp_cache/`, and temporary sessions.
  - **Action**: "Clear Patcher Cache" button: deletes cached base APKs and `.mpp` bundles, freeing storage without touching user's built APKs in Downloads.

#### Deep Queue Removal:
- When the user selects **"Remove from Queue"** on any job card in `QueueTab.kt` or `MyApps.kt`:
  1. Cancels any active coroutine or worker via `PatchJobRunner.cancel(job.id)`.
  2. Removes the job entry from `JobStore`.
  3. Invokes `PatchStorageCleaner.purgeAppCache(context, packageName)`:
     - Deletes `base_apk_cache/<packageName>/`
     - Deletes active session folders in `cacheDir/patcher_session/<packageName>/`
     - Deletes associated app-specific `.mpp` downloads.

---

### 4.3 Subsystem 3: Package Conflict & Custom Renamer Engine

When installing a patched APK over a pre-installed app, signature conflicts occur (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Orion provides three explicit resolutions:

#### Option A: Proceed Anyway / Force Install
- For privileged backends (Shizuku, Root, Wireless ADB): executes `pm install -r -d` with elevated flags.
- For standard PackageInstaller: explains that Android enforces signature matching and offers Option B or C.

#### Option B: Uninstall Original
- Prompts system uninstallation for `job.params.packageName`.
- Upon successful uninstallation broadcast, automatically resumes installation of the built patched APK.

#### Option C: Clone App with Custom Package Name (`ApkCloner.kt`)
- Allows the patched app to be installed side-by-side with the original app.
- **Binary XML Modification**:
  - Parses binary `AndroidManifest.xml` string pool.
  - Replaces `packageName` (e.g. `com.instagram.android`) with `com.instagram.android.morphe` (or user-chosen suffix).
  - Updates all `<provider android:authorities="...">` entries (e.g. appending `.morphe`) to prevent `INSTALL_FAILED_CONFLICTING_PROVIDER`.
- Re-signs with Orion's deterministic key and installs smoothly alongside the original app.

---

### 4.4 Subsystem 4: Post-Patch Installation Execution

#### Root Cause of Previous Hangs:
- In `QueueTab.kt`, completed jobs (`SUCCEEDED`) did not have an "Install" button.
- In `PatchJobRunner.kt`, when `autoInstall = true`, `SessionInstall.commit` sent an IntentSender that on Android 14/15 required an active Activity to launch the confirmation dialog; background services could be blocked by Android's Background Activity Launch (BAL) restrictions.

#### Hardened Execution Flow:
1. **Elevated Silent Backends**:
   - If Shizuku or Dhizuku or Wireless ADB or Root is available and enabled: commits install silently in background.
2. **Interactive SessionInstall with Activity Host**:
   - If user confirmation is required, routes the `PendingIntent` or `Intent(Intent.ACTION_VIEW)` through the active `OrionActivity` (or `ApkInstallActivity`), ensuring the system installer dialog pops up immediately without BAL blocking.
3. **Queue & My Apps Install Button**:
   - Every job with an `outputApkPath` (whether `SUCCEEDED` or `FAILED`) renders a prominent **"Install Built APK"** button.
   - Tapping it re-opens the installer session with the cached `.apk` or `.apks` bundle.

---

### 4.5 Subsystem 5: "Show in Folder in Storage" Action

Provides a native intent to open the file in the device's default file manager:
```kotlin
fun showInFolder(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val parentDir = file.parentFile ?: file
    val folderUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
    
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        // Fallback to viewing the APK file directly with a file manager chooser
        val viewFileIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(viewFileIntent, "Open Folder with...").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
```

---

### 4.6 Subsystem 6: Queue & My Apps Complete Dropdown UI

In both `QueueTab.kt` and `MyApps.kt`:

1. **Job Card Action Row**:
   - Primary Action Button:
     - Active: `Logs`
     - Succeeded or Failed with Built APK: `Install Built APK`
     - Failed (missing APK): `Provide APK to Retry`
   - 3-Dots Overflow Menu:
     - 📂 **Show in Folder**: Reveals file in storage.
     - 📋 **View Live Logs**: Opens `PatchConsoleLogSheet`.
     - 🔄 **Clone & Repatch**: Opens dialog to set custom package name and repatch.
     - 🗑️ **Uninstall Original App**: Launches uninstallation of conflicting app.
     - ❌ **Remove from Queue**: Cancels job and deletes app's APK and patch caches from device.

---

## 5. Testing & Verification Protocol

1. **Unit & Integration Tests**:
   - `PatchStorageCleanerTest.kt`: Verify base APK caching, old build cleanup, cache size calculation, and deep queue removal.
   - `DownloadOrchestratorTest.kt`: Verify cascade order, APKMirror resolver, XAPK inner APK extraction, and split bundle preservation.
   - `MorpheEngineTest.kt`: Verify multi-dex bytecode injection, STORED resource/so alignment, and binary AXML package renaming.
2. **Live Device Verification (Samsung Galaxy S25 Ultra)**:
   - Install fresh Orion Store build via adb.
   - Test Instagram HushGram patching: verify download cascade, split bundle assembly, patch application, deterministic signing, and instant installation prompt.
   - Test "Show in Folder": verify Samsung "My Files" opens directly to `Downloads/Patched_Instagram_v*.apks`.
   - Test "Clone & Repatch": clone package to `com.instagram.android.morphe`, patch, and verify side-by-side coexistence with stock Instagram.
   - Test "Remove from Queue": verify job is removed and `base_apk_cache/` + `patcher_session/` for that package are wiped.
   - Test Settings "Clear Patcher Cache": verify cached base APKs are cleared and freed storage metric updates.
