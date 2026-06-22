# Koharia - AI Agent Guide

Koharia is an Android reader forked from Mihon `0.19.9` and adapted into a Komga-focused client. Stack: Kotlin/JVM 17, Jetpack Compose + Material3, Voyager navigation, SQLDelight, Injekt DI, moko-resources. App namespace remains `eu.kanade.tachiyomi`; `applicationId` is `app.koharia`.

This file is for AI coding agents working in this repository. Follow it before making changes.

---

## Mandatory Rules

### Preserve User Work

- The worktree may contain user changes. Do not revert, overwrite, or reformat unrelated files.
- Keep edits narrowly scoped to the requested task.
- Do not run destructive git commands (`reset --hard`, `checkout --`, force push) unless the user explicitly asks.
- Do not commit or push unless the user explicitly asks.

### Formatting And Verification

Use Windows commands in this workspace:

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck
.\gradlew.bat :app:compileDebugKotlin
```

For release validation:

```powershell
.\gradlew.bat :app:assembleRelease
```

Guidelines:

- Run `spotlessApply` after Kotlin/XML edits when practical.
- Run `spotlessCheck` before considering a formatting-sensitive change complete.
- Run at least `:app:compileDebugKotlin` for code/schema/resource changes.
- After `.sq` or `.sqm` edits, a compile touching `:data` is enough to regenerate SQLDelight interfaces; `:app:compileDebugKotlin` does this.

### Internationalization

- Shared strings live in `i18n/src/commonMain/moko-resources/base/strings.xml` and use `tachiyomi.i18n.MR`.
- Koharia currently has no separate `i18n-koharia` module; add new app strings to `i18n/.../base` unless a dedicated module is introduced.
- For Chinese user-facing Koharia changes, it is acceptable to update `zh-rCN` alongside `base` when the task is explicitly Chinese-facing. Avoid mass-editing other locales.
- Do not add strings directly in composables when an `MR.strings.*` resource is appropriate.
- After i18n edits, run a compile so moko-resources regenerates accessors.

---

## Project Layout

| Path | Purpose |
|------|---------|
| `app/` | Android app, Compose screens, Voyager screens, DI, workers, reader, Komga integration |
| `app/src/main/java/koharia/source/komga/` | Built-in Komga source and source settings |
| `app/src/main/java/koharia/komga/` | Komga API, repository, library UI |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/` | Reader activity, view model, loaders, viewers |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/` | Download/cache pipeline; Koharia raw Komga downloads are customized here |
| `app/src/main/java/eu/kanade/tachiyomi/data/track/komga/` | Komga progress/history sync |
| `domain/` | Domain models, repository interfaces, interactors |
| `data/` | SQLDelight schema, migrations, repository implementations |
| `source-api/` | Source ABI and source models; avoid breaking extension compatibility casually |
| `source-local/` | Local source implementation |
| `core/common/` | Shared utilities, network, preferences, storage, security |
| `core/archive/` | Archive/EPUB/CBZ reading utilities |
| `core-metadata/` | ComicInfo metadata support |
| `presentation-core/` | Shared Compose components and theme resources |
| `presentation-widget/` | Glance widgets |
| `telemetry/` | Firebase/noop telemetry variants |
| `macrobenchmark/` | Benchmark and baseline profile utilities |

Version catalogs:

- `gradle/libs.versions.toml`
- `gradle/koharia.versions.toml`

SDK/JDK:

- min SDK 26
- compile SDK 37
- target SDK 36
- Java 17

---

## Architecture Notes

### DI

Koharia uses Injekt, not Hilt.

- App-level registrations: `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt`
- Domain registrations: `app/src/main/java/eu/kanade/domain/DomainModule.kt`
- Resolve dependencies with `Injekt.get<T>()` or `injectLazy<T>()`.

### UI And Navigation

- Voyager `Screen` classes are under `eu.kanade.tachiyomi.ui.*`, `koharia.*`, and renamed `koharia.feature.*` packages.
- Presentation composables are usually under `eu.kanade.presentation.*`.
- Prefer existing `StateScreenModel<State>` patterns.
- Use `screenModelScope`, `launchIO`, and `withIOContext` for async work.

### Database

SQLDelight files live under:

- `data/src/main/sqldelight/tachiyomi/data/*.sq`
- `data/src/main/sqldelight/tachiyomi/view/*.sq`
- `data/src/main/sqldelight/tachiyomi/migrations/*.sqm`

Rules:

- Every schema change needs a migration.
- Current latest Koharia SQLDelight migration is `11.sqm`; the next schema migration should be `12.sqm`.
- Register custom column adapters in `AppModule.kt`.
- Keep mapper signatures in sync with generated SQLDelight query output.

### Preferences And App Migrations

App preference migrations are under:

- `app/src/main/java/koharia/core/migration/migrations/`

Use these for preference cleanup or app-state migration, not SQL schema migration.

---

## Koharia-Specific Constraints

Koharia is Komga-first. Before porting upstream Mihon features, check whether they make sense for a dedicated Komga client.

Important custom areas:

- `KomgaSource` is the only built-in network source currently registered by `AndroidSourceManager`.
- Komga library browsing is customized from Mihon browse/source screens.
- Downloads are customized for Komga raw-file caching and resumable behavior.
- Komga progress/history sync is integrated into reader, manga, and history flows.
- Many `mihon.*` package roots have been renamed to `koharia.*`; preserve current package names.

Avoid casually restoring removed upstream browse/extension UI unless the task explicitly asks for it.

---

## Upstream Mihon Merge State

Current upstream review baseline:

- Reviewed through Mihon `mihon/main` commit `c7e782c17` (`Update markdown to v0.42.0`, 2026-06-20).
- Future upstream review should start after `c7e782c17`.

Important caveat:

- Koharia did not wholesale merge all changes up to that commit.
- Priority items selectively merged include reader vertical chapter navigator, Shizuku receiver safety, Nord `outlineVariant`, manga notes text-limit removal, and TachiyomiX 1.6 `memo` backup/model persistence.
- Extension-store refactors and the large Source API `getMangaUpdate` refactor were intentionally not wholesale merged because they conflict with the Komga-only direction and current source flow.

When merging future Mihon upstream:

- Prefer cherry-picking or manual porting over full branch merge.
- First classify changes as reader/core/bugfix/dependency vs extension-store/browse/source-ecosystem.
- Be extra careful around:
  - `source-api/`
  - `AndroidSourceManager`
  - `DownloadManager`, `Downloader`, `DownloadJob`
  - `KomgaSource`
  - `KomgaProgressSyncService`
  - backup models and SQLDelight migrations

---

## Build Variants And Flags

Build types:

- `debug`: `app.koharia.dev`
- `release`: production package
- `foss`: release-derived, `.foss`
- `preview`: release-derived, `.debug`
- `benchmark`: profileable benchmark package

Gradle project flags:

| Flag | Effect |
|------|--------|
| `-Pinclude-telemetry` | Include Firebase/Crashlytics telemetry |
| `-Penable-updater` | Enable updater code path |
| `-Pdisable-code-shrink` | Disable R8/resource shrinking |

Release signing uses `keystore.properties` when present.

---

## Common Commands

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleRelease
.\gradlew.bat test
```

Useful focused checks:

```powershell
.\gradlew.bat :data:generateDebugDatabaseInterface
.\gradlew.bat :domain:test
```

If Gradle state is stale:

```powershell
.\gradlew.bat --stop
```

---

## Coding Conventions

- Match nearby code style and local abstractions.
- Prefer existing interactors/repositories over adding service-style shortcuts.
- Prefer structured parsers/APIs over ad hoc string parsing.
- Use `logcat` helpers from `tachiyomi.core.common.util.system.logcat`; avoid raw `android.util.Log`.
- Keep comments sparse and useful.
- Do not introduce new libraries unless the existing stack cannot reasonably solve the task.
- For Compose UI, follow existing Material3/Voyager patterns and reuse `presentation-core` components.
- For source model changes, preserve source ABI compatibility where possible.

---

## High-Risk Areas

Treat these as high-risk and verify with compile/tests:

- SQLDelight schema or migrations
- Backup/restore models and proto numbers
- Reader loading/viewer logic
- Download queue/resume/delete behavior
- Komga progress sync and history sync
- Source API or extension loading
- Build logic and version catalogs

---

## Key Files

- `README.md` - project overview
- `app/build.gradle.kts` - app id, variants, signing, version
- `settings.gradle.kts` - module list
- `app/src/main/java/eu/kanade/tachiyomi/App.kt` - app bootstrap
- `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt` - app DI
- `app/src/main/java/eu/kanade/domain/DomainModule.kt` - domain DI
- `app/src/main/java/koharia/source/komga/KomgaSource.kt` - Komga source
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt` - reader shell
- `data/src/main/sqldelight/tachiyomi/` - DB schema and migrations
