# Koharia - AI Agent Guide

Koharia is a Komga-focused Android reader forked from Mihon `0.19.9`. It uses Kotlin/JVM 17, Jetpack Compose + Material3, Voyager, SQLDelight, Injekt, and moko-resources. The namespace remains `eu.kanade.tachiyomi`; `applicationId` is `app.koharia`.

## Mandatory Rules

### Preserve User Work And Secrets

- Preserve unrelated user changes; keep edits narrowly scoped.
- Do not use destructive Git commands, force-push, commit, or push unless explicitly requested.
- Treat `local.properties`, `keystore.properties`, `*.jks`, API keys, and tokens as secrets. Never print, log, or commit them.
- Confirm the destination before pushing: `github` targets GitHub, while `origin` targets the self-hosted repository.
- Pull request titles and descriptions must be written bilingually in Chinese and English.
- When debugging an emulator or physical device, prefer ADB commands. Do not use the `computer-use` skill unless the user explicitly requests device operation for validation or debugging; otherwise, provide manual validation and debugging instructions.

### Formatting And Verification

Use Windows commands:

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck
.\gradlew.bat :app:compileDebugKotlin
```

- Run `spotlessApply` after Kotlin/XML edits when practical.
- Run `spotlessCheck` for formatting-sensitive work and at least `:app:compileDebugKotlin` for code, schema, or resource changes unless the user requests a lighter workflow.
- For release validation use `.\gradlew.bat :app:assembleRelease`.
- Focus checks when appropriate: `:data:generateDebugDatabaseInterface`, `:domain:test`, or relevant module tests.
- If Gradle state is stale, use `.\gradlew.bat --stop` before broader cleanup.

### Internationalization

- Shared strings live in `i18n/src/commonMain/moko-resources/base/strings.xml` and use `tachiyomi.i18n.MR`.
- Chinese-facing changes may update `zh-rCN` with `base`; avoid mass-editing other locales.
- Do not hardcode user-facing strings in composables when an `MR.strings.*` resource fits.
- Compile after i18n edits to regenerate moko-resources accessors.

## Project Map

| Path | Purpose |
|------|---------|
| `app/src/main/java/koharia/epub/` | Native EPUB/Readium reader, cache, pagination, settings, progress |
| `app/src/main/java/koharia/source/komga/` | Built-in Komga source, server profiles, scoped configuration |
| `app/src/main/java/koharia/komga/` | Komga API, repository, downloads, library UI |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/` | Comic pager/webtoon reader |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/` | Download and page-cache pipeline |
| `app/src/main/java/eu/kanade/tachiyomi/data/track/komga/` | Komga comic progress/history sync |
| `domain/`, `data/` | Domain contracts/interactors and SQLDelight implementations |
| `source-api/`, `source-local/` | Source ABI and local-source implementation |
| `core/`, `presentation-core/`, `presentation-widget/` | Shared utilities, archive support, UI, widgets |

Dependency versions are defined in `gradle/libs.versions.toml`; SDK, NDK, and Java versions are defined in `gradle/koharia.versions.toml`.

## Architecture

### Dependency Injection And UI

- Koharia uses Injekt, not Hilt.
- Registrations live in `AppModule.kt`, `PreferenceModule.kt`, and `DomainModule.kt`.
- Resolve with `Injekt.get<T>()` or `injectLazy<T>()`.
- Follow existing Voyager `Screen` and `StateScreenModel` patterns; use `screenModelScope`, `launchIO`, and `withIOContext` for asynchronous work.

### Database And Preferences

- SQLDelight schema, views, and migrations live under `data/src/main/sqldelight/tachiyomi/`.
- Every schema change needs a migration. Inspect existing numeric `.sqm` files and use the next consecutive number; never trust a number copied from documentation.
- Keep adapters, generated query mappers, and repository signatures aligned.
- App/preference migrations live under `app/src/main/java/koharia/core/migration/migrations/`.
- Most user configuration uses `ScopedPreferenceStore` per Komga server. Truly global settings must explicitly use the unscoped `PreferenceStore`.

## Koharia-Specific Boundaries

- Koharia is Komga-first; do not restore removed extension/browse ecosystems unless requested.
- `KomgaSource` is the only built-in network source registered by `AndroidSourceManager`.
- Confirm the launch path before reader changes: EPUB/Readium is under `koharia/epub`; comic paging/webtoon is under `ui/reader`.
- Manual downloads, EPUB book cache, and comic page cache are distinct. Cache state must not become download state, and clearing caches must not delete manual downloads.
- Komga sync uses stable locator/progression data. EPUB visual page counts depend on device and layout, remain local, and must not replace cloud progress.
- Komga browsing, raw-file downloads, resumable transfers, progress, and history contain project-specific behavior; prefer existing managers/repositories over parallel implementations.
- Preserve current `koharia.*` package names instead of reintroducing removed `mihon.*` roots.

## Upstream Mihon Sync

- Review resumes after Mihon commit `c7e782c17` (2026-06-20).
- Selectively port compatible reader, core, bug-fix, and dependency changes; do not wholesale merge upstream.
- Treat extension-store, Source API, download, backup, and database changes as compatibility-sensitive and check them against the Komga-only direction.

## Coding Conventions

- Match nearby style and abstractions.
- Prefer interactors and repositories over service-style shortcuts.
- Prefer structured APIs/parsers over ad hoc string parsing.
- Use `tachiyomi.core.common.util.system.logcat`; do not copy existing raw `android.util.Log` usage into new code.
- Keep comments sparse and useful; do not add dependencies unless the current stack is insufficient.
- Reuse existing Material3/Voyager and `presentation-core` components.
- Preserve Source API compatibility unless a breaking change is explicitly required.
- Build variants, flags, shrinking, and signing rules are defined in `app/build.gradle.kts`; inspect it for release-specific work.

## High-Risk Areas

Use proportional compile/tests for:

- SQLDelight migrations, backup models, and proto numbers
- EPUB/Readium or comic loading, pagination, and caches
- Download queue, resume, deletion, and download/cache boundaries
- Komga progress/history synchronization
- Scoped/global preferences and server deletion cleanup
- Source API, extension loading, build logic, and version catalogs
