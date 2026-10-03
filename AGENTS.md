# Koharia - AI Agent Guide

Koharia is an Android reader for personal server and local media libraries, forked from Mihon `0.19.9`. It uses Kotlin/JVM 17, Jetpack Compose + Material3, Voyager, SQLDelight, Injekt, and moko-resources. The namespace remains `eu.kanade.tachiyomi`; `applicationId` is `app.koharia`.

## Mandatory Rules

### Preserve User Work And Secrets

- Preserve unrelated user changes; keep edits narrowly scoped. A bug reported against one provider may affect a shared feature: inspect all applicable providers before choosing the fix location. Narrow scope must not leave the same shared defect unfixed; provider-specific protocol fixes do not require unrelated cross-provider refactoring.
- Device tests must retain installed app/test APKs and existing app data after success or failure. Do not automatically run `adb uninstall` or `pm clear`, or uninstall incompatible packages to retry installation, unless the user explicitly requests it. Clean up only data created by the current test; select the target device explicitly.
- App instrumentation tests must use `-PdeviceTestFixture=true` (`app.koharia.dev.devicefixture`, launcher label `Koharia Auto Tests`) or the existing isolated E-Ink fixture. Never run tests against the user's `app.koharia.dev`, release, FOSS, or preview packages. Package retention alone does not isolate preferences, connection inventory, or library data.
- Do not use destructive Git commands, force-push, commit, or push unless explicitly requested.
- Treat `local.properties`, `keystore.properties`, `*.jks`, API keys, and tokens as secrets. Never print, log, or commit them.
- Confirm the destination before pushing: `github` targets GitHub, while `origin` targets the self-hosted repository.
- Pull request titles and descriptions must be written bilingually in Chinese and English.
- When debugging an emulator or physical device, prefer ADB commands. Do not use the `computer-use` skill unless the user explicitly requests device operation for validation or debugging; otherwise, provide manual validation and debugging instructions.

### Formatting And Verification

- Store task-local plans, draft documents, investigation notes, test run reports, generated test matrices, logs, screenshots, recordings, downloaded fixtures, temporary scripts, extracted sources, and diagnostic dumps under `<repo>/.test-artifacts/<task-or-date>/`. Create the directory before use; do not scatter these files in the repository root or create new `.codex-tmp`, `.codex-work`, `artifacts`, or `local-library-test` directories.
- `.test-artifacts/` is Git-ignored. Do not force-add its contents. Keep permanent app/module tests in the source test directories; reusable standalone verification tools may live in `tools/` with concise usage instructions. Do not put dated implementation plans, debugging diaries, or per-run validation reports in `tools/`. Summarize changes and validation in the task response and, when applicable, the relevant issue or pull request; update an existing maintained document only when the information is needed for future work. Keep committed documents secret-free and remove local paths, device identifiers, and user-content details that are not needed to reproduce the work. Standard Gradle outputs and caches may remain in their existing ignored build directories.
- Use explicit output paths for test commands and scripts. After testing, remove disposable files belonging to that task; retain only useful diagnostic evidence in its task directory. Before recursive cleanup, verify resolved paths remain inside the intended directory and preserve Git worktrees, user files, credentials, installed device packages, and device app data.

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
- Compilation alone does not verify provider integration. Follow the cross-provider verification contract below for shared behavior or provider changes. Documentation-only changes need whitespace, link, and any edited configuration syntax checks; they do not require an app build.

### Internationalization

- Shared strings live in `i18n/src/commonMain/moko-resources/base/strings.xml` and use `tachiyomi.i18n.MR`.
- Chinese-facing changes may update `zh-rCN` with `base`; avoid mass-editing other locales.
- Do not hardcode user-facing strings in composables when an `MR.strings.*` resource fits.
- Compile after i18n edits to regenerate moko-resources accessors.

### GitHub Release Notes

- Release bodies are the source for localized in-app update notes: Chinese app locales use the Chinese block, while every other locale uses English.
- Write both blocks with the exact markers below. Do not place `---` between them; put checksums or other non-changelog content after the end marker.

```markdown
<!-- koharia-release-notes:zh -->
中文更新说明
<!-- koharia-release-notes:en -->
English release notes
<!-- koharia-release-notes:end -->
```

## Project Map

| Path | Purpose |
|------|---------|
| `app/src/main/java/koharia/connection/` | Provider registry, capability adapters, shared preferences, cache policy, connection UI |
| `app/src/main/java/eu/kanade/presentation/browse/`, `app/src/main/java/eu/kanade/presentation/library/components/` | Shared shelf content, cards, badges, and reading progress presentation |
| `app/src/main/java/eu/kanade/tachiyomi/ui/manga/`, `app/src/main/java/eu/kanade/presentation/manga/` | Standard series details, state, and chapter presentation |
| `app/src/main/java/eu/kanade/presentation/more/settings/` | Shared settings screens and preference components |
| `app/src/main/java/koharia/epub/` | Native EPUB/Readium reader, cache, pagination, settings, progress |
| `app/src/main/java/koharia/source/komga/` | Built-in Komga source, server profiles, scoped configuration |
| `app/src/main/java/koharia/komga/` | Komga API, repository, downloads, library UI |
| `app/src/main/java/koharia/source/lanraragi/`, `app/src/main/java/koharia/lanraragi/` | LANraragi connections, API compatibility, offline catalogue, reader progress |
| `app/src/main/java/koharia/source/kavita/`, `app/src/main/java/koharia/kavita/` | Kavita connection, catalogue, publications, organization, and synchronization |
| `app/src/main/java/koharia/source/smanga/`, `app/src/main/java/koharia/smanga/` | Smanga connection, catalogue, pages, and synchronization |
| `app/src/main/java/koharia/source/local/` | Local folder connections, indexing, metadata, and library UI |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/` | Comic pager/webtoon reader |
| `app/src/main/java/eu/kanade/tachiyomi/data/download/` | Download and page-cache pipeline |
| `app/src/main/java/eu/kanade/tachiyomi/data/track/komga/` | Komga comic progress/history sync |
| `domain/`, `data/` | Domain contracts/interactors and SQLDelight implementations |
| `source-api/`, `source-local/` | Source ABI and local-source implementation |
| `core/`, `presentation-core/`, `presentation-widget/` | Shared utilities, archive support, UI, widgets |

Dependency versions are defined in `gradle/libs.versions.toml`; SDK, NDK, and Java versions are defined in `gradle/koharia.versions.toml`.
Discover additional and in-development providers through the `ConnectionRegistry` registration in `AppModule.kt` and the current working tree; this map is not an exhaustive provider inventory. Working-tree implementations are not evidence of released support.

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
- All connections share app and reader configuration through `SharedAppPreferences` (the fixed `connection_shared::` scope). Do not introduce per-connection app/reader configuration. Credentials, provider-specific options, filters, cached catalogues and reading state remain connection-isolated; use the unscoped `PreferenceStore` only for explicitly global state or keys with explicit connection identity.

### Cross-Provider Development Contract

- For shelf, card, series, settings, download, navigation, or progress changes, classify the behavior as shared or provider-specific. Inspect the current registry and relevant call sites, including providers under development. Record each affected provider/entry point as applicable and implemented, applicable but missing, or not applicable with a concrete reason. Keep task matrices in the task response/PR or `.test-artifacts/<task>/`.
- A missing implementation is not an unsupported capability. Unless the user explicitly limits scope, a shared feature or fix is complete only when all applicable providers and affected entry points are integrated and verified. Disclose any remaining gaps and unverified behavior; do not claim parity based only on a common component existing.
- Reuse existing `Connection...Adapter` interfaces and shared components. Share presentation, derived state, and interaction orchestration; providers own protocol calls, stable identities, data mapping, permission checks, and genuinely specialized behavior. Do not copy another provider's screen/state logic to add a generic feature.
- Required shared behavior must not silently disappear through omitted nullable callbacks, default `false`, or no-op implementations. Use required state, a shared host, or an integration check. Optional capabilities may remain independently opt-in; distinguish unsupported, permission-denied, temporarily unavailable, and not-yet-loaded states where behavior differs.
- Shared UI and orchestration should dispatch by capability and content type. Keep provider-type/ID branches and URL parsing inside adapters or explicit compatibility boundaries; explain any new exception. Resource state must retain connection/account identity, and late results from an obsolete session must not update the current one.
- Check affected routes such as shelves, search, collections/categories, history, details, and reader launch. Fixing one screen or the shared composable alone does not prove that the other consumers are wired correctly.
- Existing duplicate implementations may be consolidated incrementally within the requested behavior. These contracts are requirements, not a claim that all common components already exist; do not force unrelated rewrites or invent one universal protocol/algorithm for different providers.

### Shelf Entry Presentation Contract

- Reuse `BrowseSourceContent` and the common card components as the starting point. All online providers that support local manual downloads must supply the same download-state semantics and show the common bottom-right indicator in grid modes; list modes use the corresponding shared presentation.
- Distinguish no downloads, partial downloads, complete downloads, and an unknown total. A complete-series indicator requires evidence that all relevant reading units are downloaded. Server-side downloads, comic page caches, and EPUB publication caches are not local manual downloads; native local files retain their own availability semantics.
- Aggregate download/read state through shared logic and existing repositories/managers where possible. Subscribe to completion, deletion, and progress changes; do not perform a network lookup per card or couple download visibility to the reading-progress toggle. Keep equivalent state consistent across applicable entry points.

### Standard Series Details Contract

- Use the existing `MangaScreen` and its shared presentation as the canonical series/details screen. Keep cover/title, metadata order, description/tags, reading actions, and chapter/unit layout consistent across providers. Use one missing-value/empty-section policy; absent metadata does not justify a separate basic layout or a missing details route.
- Map provider metadata and reading units at the adapter boundary. A single archive/file can have one reading unit without fabricating a remote series; preserve actual ownership and progress semantics. Prefer typed common presentation data over parsing provider payloads in composables.
- Expose specialized actions through capability-based extension points such as `ConnectionSeriesActionsAdapter`, with the provider identified. Respect permissions and do not replace the standard details screen with the provider's supplementary action page.

### Settings Composition Contract

- Keep common app/reader values in `SharedAppPreferences`. Organize comic, reflowable-book, and PDF options by content/reader capability, not by provider; changing connections must not reset or clone common preferences.
- Every provider needs a connection-settings entry. Reuse shared settings hosts/components and expose dedicated options through adapters, visibly labeled with the provider. Credentials and genuinely provider-specific configuration remain connection-isolated.
- Settings outside the reader follow the selected or explicitly edited connection; reader settings follow the content's owning connection. Recompute applicable groups when connection, content capabilities, or permissions change, so unrelated provider options do not leak across contexts.
- Derive generic settings visibility from actual capabilities, including supported entry-opening modes and series behavior. Check that default capability values do not hide settings for providers already using the corresponding shared feature.

### New Provider Completion And Verification

- Before declaring a provider complete, account for connection add/edit/validation/switch/removal, account isolation, shelf/search/filter/cache behavior, card state, standard details, shared and dedicated settings, supported reader routes/offline access/progress, and applicable backup/restore/cleanup. State explicit reasons for unsupported items and list applicable missing items as unfinished work.
- Test shared regressions at the shared layer and integration omissions at the provider/call-site boundary. Reuse contract fixtures across applicable providers; keep their inventory aligned with production registration so a new provider cannot silently escape coverage. Existing `ConnectionArchitectureTest` and provider cache tests are starting points, not proof of complete integration.
- Cover dimensions relevant to the change: partial/complete/removed downloads and unknown totals; absent metadata and single/multiple units; permission restrictions; shared-setting persistence and dedicated-setting visibility after switching; connection/account identity and obsolete session results. Use the remote shelf cache contract for cache changes.
- Prefer meaningful unit/contract tests and protocol fixtures, plus representative UI/device checks where needed. Do not require all live servers or every combination for every change. Preserve the isolated device-test rules above, and report exactly which checks ran, which providers/routes they cover, and what remains unverified.
- Keep `AGENTS.md` authoritative for these contracts. Contribution guidance, PR templates, and review instructions should reference and enforce them rather than maintain competing capability lists. Update the relevant guidance and test coverage when adding a provider or changing a shared contract; do not relabel unfinished development as released support.

## Koharia-Specific Boundaries

- Koharia focuses on personal server and local media libraries. Do not restore removed public content-source/extension-store ecosystems unless requested; sharing capabilities among supported or explicitly requested providers is encouraged.
- `AndroidSourceManager` creates built-in connection sources through `ConnectionRegistry`. Inspect current registration rather than assuming a fixed provider list. Local and remote providers share applicable product behavior while retaining different storage and protocol semantics.
- LANraragi uses stable connection/archive identities, whole-archive chapters, and archive-level progress. Keep its catalogue generations and pending reading state separate from Komga caches and manual downloads.
- Confirm the launch path before reader changes: EPUB/Readium is under `koharia/epub`; comic paging/webtoon is under `ui/reader`.
- Manual downloads, EPUB book cache, and comic page cache are distinct. Cache state must not become download state, and clearing caches must not delete manual downloads.
- Komga sync uses stable locator/progression data. EPUB visual page counts depend on device and layout, remain local, and must not replace cloud progress.
- Komga browsing, raw-file downloads, resumable transfers, progress, and history contain project-specific behavior; prefer existing managers/repositories over parallel implementations.
- Preserve current `koharia.*` package names instead of reintroducing removed `mihon.*` roots.

## Upstream Mihon Sync

- Review resumes after Mihon commit `c7e782c17` (2026-06-20).
- Selectively port compatible reader, core, bug-fix, and dependency changes; do not wholesale merge upstream.
- Treat extension-store, Source API, download, backup, and database changes as compatibility-sensitive and check them against the personal-media-library direction and all applicable provider contracts.

## Coding Conventions

- Match nearby style and abstractions, while following the shared contracts above; existing provider-specific duplication is not a template for new generic behavior.
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
- All providers' progress/history synchronization and conflict/permission semantics
- Cross-provider capability wiring, card state, standard details, settings visibility, and reader routing
- Scoped/global preferences and server deletion cleanup
- Source API, extension loading, build logic, and version catalogs

### Remote Shelf Cache Contract

- All current and future server providers must persist successful shelf data and render existing cache on startup/resume without an automatic network refresh or TTL expiry.
- Fetch shelf data only when the requested cache is missing, the user explicitly refreshes/changes server settings, or an actual server update event (such as SSE) invalidates it. Connectivity recovery alone does not invalidate cached data.
- Keep valid empty caches distinct from missing caches. Retry initial failures without deleting prior successful data. Scope caches by connection/account and preserve read-progress synchronization as a separate pipeline.
- Use `ConnectionShelfCachePolicy` and `ConnectionShelfUpdates`; test warm startup without server requests, cold-cache bootstrap, refresh failure retention, and account isolation for every new network provider.
