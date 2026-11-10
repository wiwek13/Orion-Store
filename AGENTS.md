# AGENTS.md — Orion Store Contributor & Agent Guide

> **Universal operating instructions, architecture reference, and skill catalog for AI agents and human developers collaborating on Orion Store 2.0 (`Orion-RE`).**

---

## 1. Project Mission & Overview

**Orion Store** is a native Android application store built with modern Jetpack Compose. It is a full native rewrite and evolution of the legacy Capacitor/web storefront (`Orion Legacy/`).

### Core Value Pillars
- **Zero-overhead native performance**: 100% Jetpack Compose with Material 3 Expressive (`MaterialExpressiveTheme`, `MotionScheme.expressive()`).
- **Privileged, frictionless installation**: Native integration with **Shizuku**, **Dhizuku** (Device Owner), **Root**, and built-in **Wireless ADB** for silent/background package installs, plus fallback to Android Session PackageInstaller.
- **Security & Integrity**: On-device signature verification, package hash checking, and VirusTotal integration (`platform/VirusTotal.kt`, `platform/Verifier.kt`, `platform/ApkInsights.kt`).
- **Customizable, accessible design**: Dynamic theming with Material You, custom tonal schemes (Brand Indigo + Acid Lime tertiary), live shape morphing, custom pixel avatars, and rich haptics.

---

## 2. Codebase Architecture & Key Files

The project is structured as a Single-Activity Jetpack Compose application following MVVM principles and unidirectional data flow.

```
app/src/main/java/com/orion/store/
├── OrionApp.kt                  # Application class, lifecycle, crash logging, init
├── OrionActivity.kt             # Main Activity, edge-to-edge, splash screen, deep links
├── AutoUpdate.kt                # In-app self-update engine
│
├── data/                        # Data layer & persistence
│   ├── Models.kt                # Data classes (AppItem, Release, Catalog, Config)
│   ├── Stores.kt                # Persisted state (AtomicFile JSON, Zustand equivalents)
│   ├── Repository.kt            # Catalog repository, mirrors, cached metadata
│   ├── CatalogProcessor.kt      # Catalog ingestion, filtering, and normalization
│   ├── Net.kt / Logic.kt        # OkHttp 5 HTTP client & business logic helpers
│   ├── Orbit.kt                 # Decentralized/Orbit catalog feeds
│   └── RepoDetect.kt            # Automated repo & update source detector
│
├── platform/                    # Platform & hardware integrations
│   ├── SessionInstall.kt        # Standard Android PackageInstaller session flow
│   ├── Shizuku / Dhizuku.kt     # Privileged package manager bridges (silent install)
│   ├── WirelessAdb.kt           # Embedded wireless ADB pairing and installation
│   ├── DownloadService.kt       # Foreground download engine with progress notifications
│   ├── ApkInsights.kt           # APK manifest parser, permissions, and metadata inspection
│   ├── Verifier.kt              # Signature, hash, and tamper verification
│   ├── VirusTotal.kt            # VirusTotal API integration for threat scans
│   ├── Bundles.kt               # Split APK (.apks, .xapk) assembly and install
│   └── OrionAi.kt / LocalLlm.kt # AI-driven app summaries and assistant
│
└── ui/                          # Jetpack Compose UI
    ├── Root.kt                  # App shell, ShortNavigationBar, Tab router, BottomSheet host
    ├── MainViewModel.kt         # Central orchestrator VM (Zustand -> StateFlow)
    ├── StoreScreen.kt           # App discovery, hero carousel, swimlanes, app cards
    ├── Detail.kt                # App detail page, screenshots, versions, permissions
    ├── MyApps.kt                # Installed apps, update manager, batch actions
    ├── Inspect.kt               # Deep APK inspector (permissions, activities, signatures)
    ├── Settings.kt              # App settings, download dirs, installer backend selector
    ├── Sheets.kt                # ModalBottomSheet overlays (notices, FAQ, reviews)
    ├── Theme.kt / Palettes.kt   # Material 3 Expressive theme, color schemes, OLED mode
    ├── Symbols.kt               # Generated Material Symbols Rounded vectors
    └── Games.kt / Pixel.kt      # Dino mini-game, pixel avatars, Easter eggs
```

---

## 3. Tooling & Development Workflow

### Commands
```bash
# Build & assemble debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test
./gradlew testDebugUnitTest

# Linting & code checks
./gradlew lint
./gradlew check

# Generate UI vectors and color schemes
node tools/gen-symbols.mjs
node tools/gen-scheme.mjs
```

### Build Conventions
- Target SDK: 35 | Min SDK: 26 | JVM Target: 17/21
- Compose BOM: `2026.09.00` with Material 3 `1.5.0-beta01`
- Network: OkHttp 5 + `kotlinx.serialization`
- Image Loading: Coil 3

---

## 4. Skills Catalog & Workflow Orchestration

This project is pre-configured with **Superpowers** and domain-specific skills located in `.agents/skills/` (mirrored in `.claude/skills/`, `.agent/skills/`, and `skills/`).

### 4.1 Superpowers (Process Discipline)

When executing engineering work on this codebase, invoke these skills before taking arbitrary action:

| Trigger | Skill to Use | Purpose |
|---|---|---|
| Starting a new feature or complex refactor | `brainstorming` | Explore alternatives, user experience, and architecture tradeoffs before writing code. |
| Prior to writing multi-step code changes | `writing-plans` | Create a clear, atomic implementation plan with verification checkpoints. |
| Executing an approved plan | `executing-plans` | Execute plan tasks iteratively with continuous testing and review gates. |
| Implementing any feature or bug fix | `test-driven-development` | Write failing tests first (RED), implement minimum code (GREEN), then refactor. |
| Investigating bugs, crashes, or ANRs | `systematic-debugging` | Root-cause analysis: isolate reproduction, trace state, verify hypotheses. |
| Reviewing code or preparing commits | `verification-before-completion` | Run test suite, check lint, and ensure no regressions before declaring completion. |
| Complex multi-agent execution | `subagent-driven-development` | Delegate scoped work units to focused subagents with strict review gates. |

---

### 4.2 Android Reverse Engineering (`android-reverse-engineering`)

**Location**: `.agents/skills/android-reverse-engineering/SKILL.md`  
**When to Use**:
- Analyzing external APKs, XAPKs, or split bundles for catalog inclusion.
- Investigating installer issues, certificate mismatches, or obfuscated classes.
- Extracting API endpoints, network hostnames, or hidden permission requirements.
- Auditing third-party apps for tracker SDKs, malware, or policy violations.

**Key Tools & References**:
- `scripts/check-deps.sh`: Validates `jadx`, `vineflower`, `dex2jar`, `aapt2`.
- `scripts/decompile.sh`: Decompiles APK/XAPK into Java/Smali with Jadx or Fernflower.
- `scripts/recover-kotlin-names.sh`: Recovers `@Metadata` Kotlin symbol names.
- `scripts/find-api-calls.sh`: Extracts HTTP endpoints, headers, and endpoints.
- Integration point in code: `com.orion.store.platform.ApkInsights` and `com.orion.store.ui.Inspect`.

---

### 4.3 Mobile App UI/UX Design (`mobile-app-ui-design`)

**Location**: `.agents/skills/mobile-app-ui-design/SKILL.md`  
**When to Use**:
- Designing new screens, bottom sheets, dialogs, or hero cards.
- Refining Material 3 Expressive motion curves, micro-interactions, and animations.
- Ensuring compliance with thumb-zone ergonomics and touch targets (≥ 48dp).

**Core Design Rules**:
- **60/30/10 Color Rule**: 60% neutral surface (`surfaceContainer`), 30% structural contrast (`surfaceVariant`, card containers), 10% accent (`primary` brand indigo / `tertiary` acid-lime).
- **8-Point Spacing Grid**: All margins, paddings, and component heights adhere to 4dp/8dp/16dp/24dp/32dp increments.
- **Expressive Motion**: Utilize `MotionScheme.expressive()` and spring physics for sheet transitions, card press morphing, and expand/collapse states.
- **Edge-to-Edge**: Always account for system status and navigation bar insets using Compose `WindowInsets`.

---

### 4.4 Android Core Skills (`android-skills`)

A complete suite of deep Android development skills located in `.agents/skills/`:

| Skill | Focus Area |
|---|---|
| `compose` | State hoisting, `derivedStateOf`, recomposition avoidance, custom layouts, `CompositionLocal` usage. |
| `kotlin-coroutines` & `kotlin-flows` | Structured concurrency, `viewModelScope`, `SharingStarted.WhileSubscribed(5_000)`, backpressure handling. |
| `android-data-layer` | Clean architecture repositories, offline-first caching, remote vs local data synchronization. |
| `datastore` | Migrating from SharedPreferences to Jetpack Preferences / Proto DataStore. |
| `paging` | Paging 3 integration for infinite scroll catalog listings and memory-efficient list views. |
| `android-retrofit` / `kmp-ktor` | Resilient network clients, interceptors, error mapping, and serialization. |
| `coil-compose` | High-performance image and vector caching, SVG renderers, placeholder transitions. |
| `android-debugging` | Resolving ANRs, memory leaks (LeakCanary), Logcat filtering, StrictMode violations. |
| `android-testing` | Unit testing ViewModels (`Turbine`, `kotlinx-coroutines-test`), Compose UI testing with test tags. |
| `android-ux` | Modern Android 14/15 UX: predictive back gesture, dynamic themed app icons, insets. |
| `modularization` | Feature-by-module architecture, reducing build times, enforcing clean dependency boundaries. |
| `gradle-build-performance` & `android-gradle-logic` | Configuration cache, build cache, convention plugins, and Kotlin DSL optimization. |

---

## 5. Engineering Guardrails for AI Agents

1. **Safety with Privileged Installer APIs**:
   - Never run unverified root commands.
   - Always validate Shizuku / Dhizuku IPC connection state before invoking binder transactions (`com.orion.store.platform.Dhizuku.isAvailable()`).
   - All package installs must catch `SecurityException` and fallback gracefully to `SessionInstall.kt`.

2. **Network & Serialization Resilience**:
   - The app must **never crash** on malformed remote JSON. Always use safe deserialization with default values in `data/Models.kt`.
   - Implement exponential backoff and timeout handling on all network calls (`data/Net.kt`).

3. **Compose Best Practices**:
   - Avoid executing non-trivial computations inside composable functions without `remember`.
   - Never invoke ViewModel methods that mutate state directly inside composition without `LaunchedEffect`.
   - Always expose immutable UI state (`StateFlow<T>`) from `MainViewModel`.

4. **Preserve Existing Comments & Architecture**:
   - Do not remove existing comments, docstrings, or license headers.
   - Ensure backwards compatibility with legacy settings stored in AtomicFile JSON (`data/Stores.kt`).

---

## 6. Orion Store Fork — Agent Instructions & Bug-Free Patching Specification

> Complete specification mirrored in [INSTRUCTIONS.md](file:///Users/wi15d0m/Documents/Projects/orion-fork/INSTRUCTIONS.md) and [ORION_STORE_FORK_INSTRUCTIONS.md](file:///Users/wi15d0m/Documents/Projects/orion-fork/ORION_STORE_FORK_INSTRUCTIONS.md).

### 6.1 Strict Anti-Regression Rules for Antigravity CLI Agents
1. **Respect the 3-Layer Orion Store Architecture**:
   - `ui/`: Compose UI, M3 Expressive, My Apps progress bars, live log sheets, `MainViewModel`. Never block `Dispatchers.Main`.
   - `data/`: Catalogs (`apps.json`, `mirror_config.json`, `release_notes.json`), MétaMorphe bundle ingestion, DataStore preferences, per-app patch selection persistence, SemVer comparison.
   - `platform/`: Package tracking, Orion download service + MorpheFlow fallback, isolated Morphe Patcher worker process, deterministic keystore signing, silent installer (Shizuku/Root/Dhizuku/Wireless ADB), cache cleanup.
2. **Reuse Existing Skills & Tested Modules**:
   - Port provider cascade & 4-way patch studio filter (`All`, `App-Specific`, `Universal`, `Selected`) from `wiwek13/morpheflow-portal`.
   - Port 3-stage patching lifecycle (`Preparing` → `Patching` → `Saving`) from `MorpheApp/morphe-manager`.
3. **Single Source of Truth & Idempotent State**:
   - Key all app state maps by canonical `packageName`.
   - Never append raw list items from multiple catalogs or local scans without merging by `packageName`.
4. **Verify Before Completing Any Step**:
   - Run `./gradlew :app:compileDebugKotlin` and `./gradlew :app:lintDebug` after every file change. Test via `adb logcat`.

### 6.2 Bug-Free Ingestion from https://metamorphe.vercel.app/#sources
- Never scrape raw HTML from the SPA shell. Fetch structured JSON catalogs:
  - Active Bundles: `https://metamorphe.vercel.app/bundles.json` (fallback: `https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/web/public/bundles.json`)
  - Changelog & Scan: `https://metamorphe.vercel.app/whats-new.json` (fallback: `https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/web/public/whats-new.json`)
  - Source Repos: `https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/data/repos.json` / `official-bundles.json`
- Apply ETag / branch commit hash caching, deduplicate branches (at most 1 bundle entry per channel per repo), prioritize official bundles, and fallback to `.mpp` manifest inspection (`PatchLoader`) when `patches-list.json` is missing.

### 6.3 Fixing Duplicates & Auto-Cleaning Local Files Across All Apps
- **Canonical Merging**: Aggregate compatible patch bundles and APK versions into a single `UnifiedAppModel` keyed by `packageName`. Deduplicate patches by `(bundleRepoUrl, patchName)` and warn on conflicting patches across bundles.
- **Auto-Clean (`PatchStorageCleaner.kt`)**:
  - Isolate patching inside `context.cacheDir.resolve("patcher_session/<packageName>/<sessionId>/")`.
  - In a `finally` block post-install/failure: purge base APKs, `splits_temp/`, `merged_input.apk`, `app_ephemeral/patcher/patched/resources/`, and `*.idsig`.
  - Delete `*_patched.apk` post-install unless "Keep patched APKs" is enabled (retaining only the single latest build).
  - On startup & periodic sweep: purge `patcher_session/` folders older than 1 hour; guard with 1 GB minimum free storage check.

### 6.4 Download Pipeline: Orion Store Primary → MorpheFlow Fallback
- **Tier 1 (Primary)**: Query Orion-Data (`apps.json`, `mirror_config.json`) and release resolvers (GitHub, GitLab, Codeberg, mirrors) with SHA-256 verification.
- **Tier 2 (MorpheFlow Fallback)**: If missing, 404, or recommended version not hosted, cascade through:
  1. Google Play (`goopdl` / GPlay API)
  2. Uptodown Resolver
  3. APKCombo Resolver
  4. APKPure Resolver
  5. Local APK Picker
- Normalize split bundles (`.xapk`, `.apkm`, `.apks`) by merging device-matching ABI, locale, and DPI splits before patching.

### 6.5 Fixing Instagram Patching & End-to-End Flow
- **Root Cause Fixes**:
  - `lateinit property resourceMappings`: Always enable ARSCLib resource decoding when resource-dependent patches or dependencies are selected.
  - Fingerprint mismatch: Auto-select bundle's `compatiblePackages` Recommended APK Version by default.
  - Split APK input: Always merge splits prior to feeding Patcher.
  - Heap OOM: Run Patcher in dedicated `:patcher` process with `android:largeHeap="true"` (1 GB–3 GB heap).
- **Interactive Flow**: Select App & Version (Recommended default / Specific / Latest / Local) → Select Patches (4-way pills, dependency resolution, DataStore persistence) → Execute Patching (3 stages, live logs & RAM gauge in My Apps, deterministic keystore signing).

### 6.6 Auto Background Patching, Version Pinning & User Controls
- Trigger WorkManager job when upstream patch bundle updates or recommended app version updates.
- Support global auto-patch & auto-install toggles, per-app auto-patch toggles, and "Pin selected APK version" locking.

### 6.7 Mandatory Verification Protocol Before Completion
1. MétaMorphe double-sync: 590+ bundles, 0 duplicate cards, 0 UI freeze.
2. Instagram patching: Recommended version auto-selected, clean download cascade, resourceMappings initialized, live logs stream, signed APK installs, temp files cleaned.
3. Older version install → Background auto-patch upgrade: Deterministic keystore in-place update, notification & progress stream, cache purged.
4. Disable auto-patch & version pinning: Background worker skips disabled apps, respects pinned version.

