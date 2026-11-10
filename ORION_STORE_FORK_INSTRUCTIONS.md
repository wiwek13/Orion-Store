# Orion Store Fork — Antigravity CLI Agent Instructions & Bug-Free Patching Specification

**Target Repository Context**: Native Android fork of [RookieEnough/Orion-Store](https://github.com/RookieEnough/Orion-Store) (Kotlin + Jetpack Compose, Material 3 Expressive) integrated with [RookieEnough/Orion-Data](https://github.com/RookieEnough/Orion-data), on-device [MorpheApp/morphe-patcher](https://github.com/MorpheApp/morphe-patcher) / [MorpheApp/morphe-manager](https://github.com/MorpheApp/morphe-manager) engine, community source aggregation from [MétaMorphe Sources](https://metamorphe.vercel.app/#sources) ([nvbangg/awesome-morphe](https://github.com/nvbangg/awesome-morphe)), and fallback APK ingestion adapted from [wiwek13/morpheflow-portal](https://github.com/wiwek13/morpheflow-portal) ([MorpheFlow Architecture](https://www.reddit.com/r/MorpheApp/comments/1xm7odg/release_morpheflow_automated_serverless_android/)).

---

## 1. Strict Anti-Regression Rules for Antigravity CLI Agents

Previous agent sessions introduced regressions, duplicate UI state, broken patch executions, and orphaned files by making uncoordinated edits across `MainViewModel` and platform services. Follow these rules on every task:

1. **Respect the 3-Layer Orion Store Architecture** ([RookieEnough/Orion-Store](https://github.com/RookieEnough/Orion-Store)):
   - `app/src/main/java/com/orion/store/ui/`: Jetpack Compose screens, Material 3 Expressive motion, My Apps progress bars, live log sheets, and `MainViewModel` state holders. **Never run blocking network, file I/O, or bytecode patching on Dispatchers.Main.**
   - `app/src/main/java/com/orion/store/data/`: Catalog processing (`apps.json`, `mirror_config.json`, `release_notes.json` from [RookieEnough/Orion-Data](https://github.com/RookieEnough/Orion-data)), [MétaMorphe](https://metamorphe.vercel.app/#sources) bundle ingestion, DataStore preferences, per-app patch selection persistence, and SemVer/channel version comparison.
   - `app/src/main/java/com/orion/store/platform/`: Package tracking, primary Orion download service + [MorpheFlow](https://github.com/wiwek13/morpheflow-portal) fallback downloaders, isolated [Morphe Patcher](https://github.com/MorpheApp/morphe-patcher) worker process, deterministic keystore signing, Shizuku/Root/Dhizuku/Wireless ADB silent installer ([Morphe ShizuStore Integration](https://shizustore.com/apps/morphe)), and transactional cache cleanup.

2. **Reuse Existing Skills & Tested Modules**:
   - Do not rewrite working Kotlin/Compose components or invent mock patchers.
   - Port the proven provider cascade and 4-way patch studio filtering (`All`, `App-Specific`, `Universal`, `Selected`) from [wiwek13/morpheflow-portal](https://github.com/wiwek13/morpheflow-portal).
   - Port the 3-stage patching lifecycle (`Preparing` → `Patching` → `Saving`) from [MorpheApp/morphe-manager](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patching-process.md).

3. **Single Source of Truth & Idempotent State**:
   - Every app entry in memory and disk cache must be keyed by canonical `packageName` (e.g., `com.instagram.android`, `com.google.android.youtube`).
   - Never append raw list items from multiple catalogs or local file scans without merging by `packageName`.

4. **Verify Before Completing Any Step**:
   - Run `./gradlew :app:compileDebugKotlin` and `./gradlew :app:lintDebug` after every file change.
   - Test on-device/emulator via `adb logcat` before marking any task done.

---

## 2. Bug-Free Scraping & Ingestion from https://metamorphe.vercel.app/#sources

### Why Previous Scraping Failed
[MétaMorphe (https://metamorphe.vercel.app/#sources)](https://metamorphe.vercel.app/#sources) is a Next.js Single-Page Application powered by [nvbangg/awesome-morphe](https://github.com/nvbangg/awesome-morphe) ([CONTRIBUTING.md](https://github.com/nvbangg/awesome-morphe/blob/main/CONTRIBUTING.md)). The `#sources` fragment is a client-side router anchor (`<h2 id="sources">What's new</h2>`) that dynamically renders tabs (*New bundles*, *New patches*, *Updates*, *Recent releases*, *Registry*, *Discover*, *Sources & scan*). Scraping raw HTML from `https://metamorphe.vercel.app/#sources` via Jsoup/OkHttp only fetches the unhydrated shell or duplicates items across tabs.

### Correct Data-Layer Ingestion Architecture
Instead of brittle DOM scraping, fetch the structured JSON catalogs generated hourly by the [nvbangg/awesome-morphe](https://github.com/nvbangg/awesome-morphe/blob/main/CONTRIBUTING.md) pipeline (`discover.py` → `fetch.py` → `update.py`):

| Data Target | Primary Endpoint | Fallback Endpoint (GitHub Raw) | Purpose in Orion Store |
|---|---|---|---|
| **Active Bundles, Patches & App Compatibility** | `https://metamorphe.vercel.app/bundles.json` | `https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/web/public/bundles.json` | Full list of 590+ community patch bundles, `.mpp` download URLs, `patches-list.json`, target `packageName`s, and compatible APK versions |
| **#sources Scan & Registry Changelog** | `https://metamorphe.vercel.app/whats-new.json` | `https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/web/public/whats-new.json` | Populates the `#sources` What's New / Registry / Scan status view (new bundles, updated patches, recent releases) |
| **Monitored Source Repositories** | `https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/data/repos.json` | `https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/data/official-bundles.json` | Canonical repository URL keys (`https://github.com/owner/repo`, GitLab, Codeberg), `main`/`dev` branch commit hashes, archive status, and official bundle priority |

### Implementation Requirements for `MetaMorpheSourceScraper.kt`
1. **Multi-Endpoint Fetch with ETag / Hash Caching**:
   - Fetch `bundles.json` and `whats-new.json` using Orion Store's resilient HTTP client (with DoH fallback so networks blocking `vercel.app` or `raw.githubusercontent.com` seamlessly fail over, matching [RookieEnough/Orion-Store Releases](https://github.com/RookieEnough/Orion-Store/releases)).
   - Compare branch hashes (`main`, `dev`) from `data/repos.json` before re-downloading `.mpp` bundle files.
2. **Branch & Pre-Release Selection Rules** (Matching [awesome-morphe update.py](https://github.com/nvbangg/awesome-morphe/blob/main/CONTRIBUTING.md)):
   - Each normalized repository URL (`https://github.com/<owner>/<repo>`, `https://gitlab.com/<owner>/<repo>`, `https://codeberg.org/<owner>/<repo>`) produces at most one bundle entry per channel (stable vs prerelease).
   - Ignore branches with null hashes, `archived: true` flags, empty/template-only patch lists, or HTTP 404/451 `.mpp` URLs.
   - Select `dev` when it is newer than `main` or when no valid `main` exists (marking `isPrerelease = true`); otherwise select `main`.
   - Prioritize official bundles from `data/official-bundles.json` (`MorpheApp/morphe-patches` first, followed by `hotRank`).
3. **Fallback `.mpp` Manifest Inspection**:
   - When a custom or newly added repository in `#sources` lacks `patches-list.json`, download the `.mpp` archive into temporary cache and inspect its embedded manifest/patch classes using the [Morphe Patcher library](https://github.com/MorpheApp/morphe-patcher) (`PatchLoader`) rather than failing or dropping the source.

---

## 3. Fixing Duplicates & Auto-Cleaning Local Files Across All Apps

### Root Cause of Duplicates
- **Unmerged Multi-Source App Entries**: Storefront and My Apps were emitting separate items for the same `packageName` when an app existed in [RookieEnough/Orion-Data](https://github.com/RookieEnough/Orion-data) (`apps.json`), multiple [MétaMorphe](https://metamorphe.vercel.app/#sources) bundles (`bundles.json`), and local storage (Downloaded APKs).
- **Local APK Scanner Re-Indexing Temp Files**: Raw base APKs (`base.apk`), split-merge outputs (`merged.apk`), and previous patched APKs (`*_patched.apk`) left in `filesDir`/`cacheDir`/`Downloads` were scanned as separate standalone apps in My Apps.

### Required Deduplication & Auto-Clean Implementation
1. **Canonical Entity Merging** (`CatalogRepository.kt` & `MainViewModel.kt`):
   - Key all app state maps by `packageName: String`.
   - Aggregate all compatible patch bundles (`List<PatchBundleSource>`) and available APK versions (`List<ApkVersionCandidate>`) inside a single `UnifiedAppModel` per `packageName`.
   - Deduplicate patches within a bundle by `(bundleRepoUrl, patchName)` and across bundles by warning when two active bundles enable conflicting patches (such as duplicate "Change package name" or "GmsCore support" patches, per [Reddit r/MorpheApp](https://www.reddit.com/r/MorpheApp/comments/1ri3zvo/revanced_migrated_patches_for_morphe_are_now/)).
2. **Deterministic Local File Auto-Clean** (`PatchStorageCleaner.kt`):
   - Isolate all patching work inside `context.cacheDir.resolve("patcher_session/<packageName>/<sessionId>/")`.
   - **Immediate Post-Install / Post-Failure Cleanup**: In a `try { ... } finally { ... }` block at the end of every patch session (and inside the PackageInstaller / Shizuku install completion callback):
     - Delete raw downloaded base APKs (`*base*.apk`, `*.xapk`, `*.apkm`, `*.apks`).
     - Delete split APK extraction and merge directories (`splits_temp/`, `merged_input.apk`).
     - Delete `app_ephemeral/patcher/patched/resources/` and temporary `*.idsig` signature files ([Morphe Patches Issue #46](https://github.com/MorpheApp/morphe-patches/issues/46)).
     - If "Keep patched APKs" is disabled (default: auto-clean enabled), delete the output `*_patched.apk` immediately after installation succeeds; if enabled, retain only the single latest patched APK per `packageName` and purge older builds.
   - **Startup & Periodic Sweep**: On app launch and before starting any patch run, purge all `patcher_session/` folders older than 1 hour and verify at least 1 GB of free storage before starting (matching [Morphe Manager's storage guard](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patching-process.md)).

---

## 4. Download Pipeline: Orion Store Primary → MorpheFlow Fallback

When downloading an APK (either pre-built or an unpatched base APK for on-device patching), enforce this strict two-tier cascade in `DownloadOrchestrator.kt`:

### Tier 1: Orion Store Native Downloader (Primary)
- Query [RookieEnough/Orion-Data](https://github.com/RookieEnough/Orion-data) (`apps.json` and `mirror_config.json`) and Orion Store's native release resolvers (GitHub, GitLab, Codeberg, and configured mirrors).
- If Orion Store has a matching release for the target `packageName` and requested `versionName` (and matching CPU ABI `arm64-v8a` / `armeabi-v7a`), download using Orion Store's foreground download service (including stall-recovery connection switching and SHA-256 verification from [RookieEnough/Orion-Store Releases](https://github.com/RookieEnough/Orion-Store/releases)).

### Tier 2: MorpheFlow Base APK Ingestion Cascade (Fallback)
If Orion Store does not host the unpatched base APK, fails with HTTP 404/rate-limit, or does not have the exact recommended version required by the selected patch bundle, automatically fall back to the base APK cascade from [wiwek13/morpheflow-portal](https://github.com/wiwek13/morpheflow-portal) ([MorpheFlow Architecture](https://www.reddit.com/r/MorpheApp/comments/1xm7odg/release_morpheflow_automated_serverless_android/)):

| Fallback Order | Provider | Resolution Logic |
|---|---|---|
| **Fallback 1** | **Google Play** (`goopdl` / GPlay API) | Request exact `versionCode` / `versionName` for device ABI (`Build.SUPPORTED_ABIS.first()`) and screen DPI |
| **Fallback 2** | **Uptodown Resolver** | Resolve app slug by `packageName`, query version history list for target `versionName` (or latest compatible), and fetch direct APK/XAPK stream |
| **Fallback 3** | **APKCombo Resolver** | Query `packageName` + target `versionName` + `arm64-v8a`, prioritizing standalone `.apk` over split bundles |
| **Fallback 4** | **APKPure Resolver** | Resolve version-pinned download URL for `packageName` and verify package certificate/manifest before patching |
| **Fallback 5** | **Local APK Picker** | Allow user to pick any local `.apk`, `.xapk`, `.apkm`, or `.apks` file from device storage |

- **Split Bundle Normalization**: Whenever Orion Store or a MorpheFlow fallback returns a split bundle (`.xapk`, `.apkm`, `.apks`, or zip of APKs), run the split-APK merger before invoking the patcher, keeping only splits matching the device's CPU architecture, locale, and screen density ("Optimize for device architecture", per [Morphe Manager](https://github.com/MorpheApp/morphe-manager)).

---

## 5. Fixing Instagram Patching & the Complete End-to-End Patching Flow

### Why Instagram (`com.instagram.android`) Patching Failed
1. **`lateinit property resourceMappings has not been initialized`** ([Reddit r/MorpheApp](https://www.reddit.com/r/MorpheApp/comments/1sgstnh/distractionfree_instagram/)):
   - In [MorpheApp/morphe-patcher](https://github.com/MorpheApp/morphe-patcher), bytecode patches that reference Android resource IDs depend on `ResourcePatchContext`. If `PatcherConfig` is initialized without enabling resource decoding (ARSCLib) when resource-dependent patches are selected, `resourceMappings` crashes immediately.
2. **Fingerprint Mismatch on Unsupported APK Versions** ([Reddit r/MorpheApp](https://www.reddit.com/r/MorpheApp/comments/1tdn5oj/instagram_patches_keep_returning_this_error_no/)):
   - Community Instagram patch bundles (e.g., Piko / Crimera / De-ReVanced) target specific Instagram versions. Downloading the latest store version instead of the bundle's `compatiblePackages` recommended version triggers `PatchException: Failed to match the fingerprint` or `List contains no element matching the predicate` ([Reddit r/MorpheApp](https://www.reddit.com/r/MorpheApp/comments/1sgkb2q/best_instagram_patches_320_dev_4/)).
3. **Unmerged Split APK Input**:
   - Passing an unmerged `.apkm`/`.xapk` bundle of Instagram directly to Patcher causes missing DEX classes or fails right after "Merging split APK" ([Morphe Manager Troubleshooting](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patching-process.md)).
4. **Heap Exhaustion (OOM) on Multi-Dex Instagram**:
   - Running Patcher on the main app heap hits Android's 256–512 MB limit. It must run in a dedicated `:patcher` process with `android:largeHeap="true"` and embedded JVM support (1 GB–3 GB heap, per [Morphe Manager Patching Process](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patching-process.md)).

### Step-by-Step Interactive Patching Flow (Select App → Select Version → Select Patches → Patch & Log → Install)

1. **Step 1 — Select App & APK Version** (`AppDetailScreen.kt` / `VersionSelectorSheet.kt`):
   - Display the app's compatible patch bundles scraped from [MétaMorphe](https://metamorphe.vercel.app/#sources).
   - Compute the **Recommended APK Version** from the selected bundle's `compatiblePackages` metadata (highest version explicitly supported by the selected patches).
   - Provide an APK Version Selector dropdown/sheet with 4 explicit choices:
     1. **Recommended (`vX.Y.Z`)** — Default (prevents Instagram fingerprint failures).
     2. **Specific Version** — Lists all versions discovered from Orion Store + MorpheFlow providers (highlighting compatible versions with a badge, and allowing experimental/all versions when Expert Mode is on, per [Morphe Expert Mode Docs](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patching-expert-mode.md)).
     3. **Latest Upstream Version** — With a clear warning if it exceeds the patch bundle's tested version range.
     4. **Select Local APK / Split Bundle** — File picker for `.apk`, `.xapk`, `.apkm`, `.apks`.

2. **Step 2 — Select Patches** (`PatchStudioSheet.kt`):
   - Support **Simple Mode** (one-tap curated defaults via "Enable recommended patches") and **Expert Mode** ([Morphe Expert Mode Docs](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patching-expert-mode.md)).
   - Include the **4-way filter pills** from [wiwek13/morpheflow-portal](https://github.com/wiwek13/morpheflow-portal): **`All`**, **`App-Specific`**, **`Universal`**, **`Selected`**.
   - Automatically resolve patch dependencies (e.g., if "Download media" depends on a base bytecode/resource hook, auto-enable the dependency; if multiple sources provide "Change package name", auto-deselect duplicates, per [Reddit r/MorpheApp](https://www.reddit.com/r/MorpheApp/comments/1ri3zvo/revanced_migrated_patches_for_morphe_are_now/)).
   - Persist the user's selected patches and per-patch options per `packageName` in DataStore (`PatchSelectionsStore`).

3. **Step 3 — Execute Patching with Live Logs & Progress Bar in My Apps** (`PatcherForegroundService.kt` & `MyAppsScreen.kt`):
   - Run the 3 stages documented in [Morphe Manager Patching Process](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patching-process.md) and stream `PatchSessionState` via `StateFlow`:
     - **Stage 1: Preparing (0% – 20%)**: Download base APK (Orion → MorpheFlow fallback), verify package name & version, merge split APK modules (`.xapk`/`.apkm`/`.apks`) into a single full base APK, load `.mpp` patches, and initialize ARSCLib resource decoding whenever any selected patch or dependency requires resources (`resourceMappings`).
     - **Stage 2: Patching (20% – 85%)**: Execute each patch sequentially (Patch X of N), emitting real-time verbose log lines (`[INFO]`, `[DEX]`, `[RES]`, `[WARN]`, `[ERROR]`) and live RAM/heap usage (Used MB / Max Heap MB).
     - **Stage 3: Saving & Signing (85% – 100%)**: Write patched resources and DEX files, strip unused native libraries if "Optimize for device architecture" is enabled, zipalign, and sign using the persistent deterministic keystore (matching [wiwek13/morpheflow-portal](https://github.com/wiwek13/morpheflow-portal) so future updates install cleanly in-place without signer clash).
   - **Morphe-Style UI in My Apps**:
     - In My Apps (*All*, *Installed*, *Downloaded*, *Updates*), any app currently being downloaded or patched must display an active progress bar card showing:
       - Current stage label (`Downloading Base APK...`, `Preparing & Merging Splits...`, `Applying Patch 14/22: Hide Ads...`, `Signing APK...`)
       - Determinate progress bar (0% – 100%) + live RAM usage badge
       - Expandable Live Patcher Console Log view (monospaced auto-scrolling terminal identical to [Morphe Manager Expert Mode](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patching-expert-mode.md) with header summary: App version, Patches version, APK size, Patch count, Split APK, Runtime & heap limit, Free RAM/Storage)
       - Action buttons: Copy Logs, Export Debug Logs, and Cancel (with confirmation dialog).

---

## 6. Auto Background Patching, Version Pinning & User Toggle Controls

### Trigger Conditions for Background Auto-Patching (`AutoPatchWorker.kt`)
Run a periodic WorkManager job (and optional on-launch check) that inspects all installed patched apps tracked by Orion Store. Trigger a background re-patch for `packageName` when either condition is met:
1. **Patch Bundle Updated**: The upstream patch source repository in [MétaMorphe bundles.json](https://metamorphe.vercel.app/#sources) has a newer release version or commit hash (`remoteBundleVersion != installedPatchMetadata.bundleVersion`).
2. **Recommended App Version Updated**: The patch bundle's `compatiblePackages` metadata specifies a newer recommended base APK version than the currently installed base version (`SemVer(remoteRecommendedApkVersion) > SemVer(installedPatchMetadata.baseApkVersion)`), or—for unpinned universal patches—a newer stable app release is available.

### Required User Controls & Settings
Provide full user autonomy over background patching and versions:
- **Global Background Patching Toggle** (Settings → Patcher & Updates):
  - *Auto-patch updates in background* (Switch: ON/OFF).
  - *Auto-install patched builds* (Switch: ON/OFF — uses Root, Shizuku, Dhizuku, or Wireless ADB silent install session, waiting for Shizuku connection in background rather than failing immediately, matching [RookieEnough/Orion-Store Releases](https://github.com/RookieEnough/Orion-Store/releases)).
- **Per-App Background Patching Toggle** (AppDetailScreen & My Apps ⋮ Menu):
  - *Auto-patch this app in background* (Switch: ON/OFF per `packageName`).
  - When turned OFF, Orion Store surfaces an "Update Available (Patch / Base APK)" badge in My Apps → Updates (like Morphe's re-patch banner in [MorpheApp/morphe-manager](https://github.com/MorpheApp/morphe-manager)) but never runs background patching automatically for that app.
- **APK Version Selection & Version Locking**:
  - Allow selecting any APK version (Recommended, Latest, or a specific historical version from the version picker).
  - Add a **"Pin selected APK version"** toggle per app: if pinned to `vX.Y.Z`, background patching will only re-patch when the patch bundle updates (re-using version `vX.Y.Z`), and will not upgrade the base APK version until unpinned.

---

## 7. End-to-End Verification Protocol (Mandatory Before Completion)

The coding agent must execute and verify all four test scenarios before reporting completion:

| Test Case | Step-by-Step Verification Procedure | Expected Pass Criteria |
|---|---|---|
| **1. MétaMorphe Source Scraping & Deduplication** | Trigger source sync from [MétaMorphe (https://metamorphe.vercel.app/#sources)](https://metamorphe.vercel.app/#sources) (`bundles.json` + `whats-new.json`) twice in a row. Inspect Storefront, Sources list, and My Apps. | 590+ community bundles load cleanly with zero duplicate app cards, zero duplicate patch entries, and zero UI freeze. |
| **2. Interactive Instagram Patching & Live Logs** | Open Instagram (`com.instagram.android`), verify the Recommended APK Version is auto-selected, choose patches in Patch Studio, and tap Patch. Watch the My Apps progress bar and expand the live log viewer. | Download cascades cleanly (Orion Store → [MorpheFlow](https://github.com/wiwek13/morpheflow-portal) fallback), split APKs merge if needed, `resourceMappings` initializes without error, live logs stream every patch step in My Apps, signed APK installs cleanly, and all temp files auto-clean. |
| **3. Old Version Install → Background Auto-Patch Upgrade** | 1. Select an older compatible APK version (or seed an older `installedBundleVersion` / `baseApkVersion` in `InstalledPatchMetadataStore`).<br>2. Patch and install that older version.<br>3. Trigger `AutoPatchWorker` (via Debug menu or `adb shell cmd jobscheduler run`). | Orion Store detects that a newer recommended APK version or newer patch bundle exists, downloads the updated base APK in the background, re-applies the user's saved patch selection, shows background progress & logs in My Apps and system notification, updates the app in-place using the deterministic keystore without signature conflict, and deletes all temporary files. |
| **4. Disable Background Patching & Version Pinning** | Toggle OFF "Auto-patch in background" for the app (or globally in Settings), and test "Pin APK version". Re-trigger `AutoPatchWorker`. | Background worker skips the disabled app completely (only showing a manual update badge in My Apps), and respects the pinned APK version when manually or automatically patching. |

