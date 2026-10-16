# Koharia - AI Agent Guide

Koharia is an Android reader for personal server and local media libraries, forked from Mihon `0.19.9`. It uses Kotlin/JVM 17, Jetpack Compose + Material3, Voyager, SQLDelight, Injekt, and moko-resources. The namespace remains `eu.kanade.tachiyomi`; `applicationId` is `app.koharia`.

## Mandatory Rules

### Preserve User Work And Secrets

- Preserve unrelated user changes; keep edits narrowly scoped. A bug reported against one provider may affect a shared feature: inspect all applicable providers before choosing the fix location. Narrow scope must not leave the same shared defect unfixed; provider-specific protocol fixes do not require unrelated cross-provider refactoring.
- Device tests must retain installed app/test APKs and existing app data after success or failure. Do not automatically run `adb uninstall` or `pm clear`, or uninstall incompatible packages to retry installation, unless the user explicitly requests it. Clean up only data created by the current test; select the target device explicitly.
- Prefer the user's explicitly authorized emulator and its existing `app.koharia.dev` installation for ADB-based validation of real connections and media. This authorization includes data-preserving replacement installation of the current project's updated dev APK with `adb -s <authorized-device> install -r <verified-apk>`; repeated confirmation is not required within this scope. Verify the actual APK application ID, signing-certificate compatibility and non-downgrade version code before installation; record the device, APK hash, and installed versions before and after in task artifacts. Do not use uninstall, clear-data, downgrade flags, another app variant or a physical device to bypass installation failure. An older installed build does not validate uninstalled changes.
- Necessary forward database/preference migrations accompanying that dev update are allowed after inspecting the installed-to-target migration path and validating affected migrations in an isolated fixture. Before an update that migrates persistent data, retain a protected, recoverable emulator snapshot or equivalent consistent local backup; do not expose its credentials or content in diagnostics. Do not use the real dev installation for migration fault injection, destructive migrations, manual database edits, or restore experiments. If safe data preservation cannot be established, keep that update in the fixture and report the specific blocker.
- Real-server validation under this authorization is read-only: allow catalogue/search/details/content reads, connection selection, and local cache/index writes needed to display the result; do not modify remote progress/history, read flags, metadata, collections, files or server settings. Determine read-only behavior by protocol semantics, not merely HTTP verbs; read-only query/authentication requests may use POST. Reading/reader launch is allowed only when remote progress/history writes and automatic local deletion are demonstrably prevented. Do not silently create pending mutation records for later delivery as part of a read-only test.
- Check startup, APK-replacement receivers, scheduled workers, connectivity recovery, SSE handlers and pending synchronization as well as the visible UI before running the updated dev app against real servers. Prefer verified existing write suppression or an effective existing read-only server permission boundary; when insufficient, follow the staged workflow below. Merely avoiding write buttons, enabling incognito/offline mode or restoring a page position does not prove read-only behavior. Do not change existing credentials or server permissions to create that boundary. Remote-write testing requires separate explicit user authorization.
- Real-data validation must not clear data, edit connection credentials/configuration, restore backups, delete existing files/downloads/history, reset progress, or perform bulk state changes. Preserve pre-existing queued operations without dispatching, discarding or reassigning them for the test. Retain allowed local cache effects; do not automatically roll back server state or downgrade the installed app after validation. If unexpected remote mutation occurs, stop that validation path and report it without issuing compensating writes.
- App instrumentation tests and existing fixture automation must still use `-PdeviceTestFixture=true` (`app.koharia.dev.devicefixture`, launcher label `Koharia Auto Tests`) or the isolated E-Ink fixture. Preserve Gradle guards, runner allowlists, and package assertions; do not retarget existing fixture tests by changing their package name. Use isolation for account replacement, restore experiments, migration test scenarios, deletion, file mutations, fault injection, malicious inputs, and interrupted writes. This does not prohibit the checked forward migrations accompanying an authorized dev update above. Package retention alone does not isolate preferences, connection inventory, or library data.
- Collect only necessary, sanitized diagnostics from real installations. Never dump complete connection preferences or credentials. Review automation setup, teardown, and logging before reuse; scripts that inject fixtures, change settings, or clean database/files are not real-data observation tools.
- Do not use destructive Git commands, force-push, commit, or push unless explicitly requested.
- Treat `local.properties`, `keystore.properties`, `*.jks`, API keys, and tokens as secrets. Never print, log, or commit them.
- Confirm the destination before pushing: `github` targets GitHub, while `origin` targets the self-hosted repository.
- Pull request titles and descriptions must be written bilingually in Chinese and English.
- When debugging an emulator or physical device, prefer ADB commands. Do not use the `computer-use` skill unless the user explicitly requests device operation for validation or debugging; otherwise, provide manual validation and debugging instructions.

### Staged Dev Update And Read-Only Validation

- **Authorization and task scope are separate.** The authorized emulator/dev replacement and read-only checks above do not require renewed permission when needed by the active implementation or validation task. A documentation-only review does not launch device work. Historical statements such as "dev unchanged" or "read-only boundary unverified" describe evidence at that time, not permanent prohibitions. Once relevant prerequisites pass, perform the scoped update/check rather than asking again or substituting repeated fixture runs.
- **Prepare a bounded check.** Name the changed behavior, candidate APK, authorized device and representative real-data route. Reuse valid package, migration and regression evidence; reassess only what changed. Inspect background paths that can run in this installation, including other configured connections, without requiring a new whole-project audit. Record installation, startup/migration and online validation as separate stages.
- **Install with data preserved.** Verify the actual package, certificate, non-downgrade version and APK hash. Determine whether this installed-to-target update changes persistent schema/preferences; apply the migration validation and recoverable-backup requirement when it does, and record the basis when it does not. Assess replacement-triggered/background execution before installation. Verified temporary emulator network isolation may protect the installation/startup stage if needed; it is not proof of online read-only behavior. Inspect and record the existing isolation state and safe exit conditions; do not restore connectivity while an unguarded app can dispatch pending writes. Do not silently leave changed device connectivity behind. An online-route blocker does not block a separately demonstrated safe installation stage, but `install -r` alone does not prove that stage safe.
- **Resolve missing read-only protection proportionately.** Within an active authorized validation task, implementing a minimal dev-only safeguard at existing startup, worker, progress or protocol boundaries is permitted when needed for the named route. Reuse existing mechanisms; do not repurpose identity-pause state, edit connection inventory, add a general testing framework or disable fixture guards. Validate the safeguard in isolation before using real data: it must be effective before any automatic work, cover reachable background paths, preserve existing queued records without claiming/acknowledging/discarding them, prevent new deferred mutations and automatic deletion, and remain effective across relevant restarts. A server permission boundary alone does not prevent local queue changes. Keep normal Release behavior unchanged, make the dev validation state identifiable and record its behavioral differences and safe exit procedure; do not infer normal synchronization correctness from suppressed execution.
- **Run only supported online routes.** After data-preservation and read-only prerequisites pass, install the verified candidate as needed and perform the named real-data checks using ADB. Existing fixture instrumentation remains isolated. Report the installed APK identity and actual routes exercised; an old dev build, successful build, or fixture pass does not replace changed-build real-data evidence. Missing protection for reader progress may defer reader launch without deferring an independently protected catalogue route; shared startup/background risks still apply to both.
- **Report specific blockers, not blanket bans.** If a prerequisite cannot be met, record the affected stage/route, current code or environment evidence, attempted bounded checks, and the smallest required remedy. Continue independent safe stages. Ask the user only when the remedy needs a new decision or exceeds existing authorization, such as remote writes, credential changes or destructive recovery. Do not repeatedly rerun unrelated fixtures to compensate for the same unresolved real-data gap. Retain installed packages/data and report any remaining temporary safeguard or isolation state; do not automatically dispatch queued operations as cleanup.

### Formatting And Verification

- Store task-local plans, draft documents, investigation notes, test run reports, generated test matrices, logs, screenshots, recordings, downloaded fixtures, temporary scripts, extracted sources, and diagnostic dumps under `<repo>/.test-artifacts/<task-or-date>/`. Create the directory before use; do not scatter these files in the repository root or create new `.codex-tmp`, `.codex-work`, `artifacts`, or `local-library-test` directories.
- `.test-artifacts/` is Git-ignored. Do not force-add its contents. Keep permanent app/module tests in the source test directories; reusable standalone verification tools may live in `tools/` with concise usage instructions. Do not put dated implementation plans, debugging diaries, or per-run validation reports in `tools/`. Summarize changes and validation in the task response and, when applicable, the relevant issue or pull request; update an existing maintained document only when the information is needed for future work. Keep committed documents secret-free and remove local paths, device identifiers, and user-content details that are not needed to reproduce the work. Standard Gradle outputs and caches may remain in their existing ignored build directories.
- Use explicit output paths for test commands and scripts. After testing, remove disposable files belonging to that task; retain only useful diagnostic evidence in its task directory. Before recursive cleanup, verify resolved paths remain inside the intended directory and preserve Git worktrees, user files, credentials, installed device packages, and device app data.

Use Windows commands:

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck
.\gradlew.bat verifyEInkMotion
.\gradlew.bat :app:compileDebugKotlin
```

- Run `spotlessApply` after Kotlin/XML edits when practical.
- Run `spotlessCheck` for formatting-sensitive work and at least `:app:compileDebugKotlin` for code, schema, or resource changes unless the user requests a lighter workflow.
- Run `verifyEInkMotion` for every feature addition, modification, or fix as required by [E-Ink Motion Policy](#e-ink-motion-policy). Compilation and formatting checks do not run or replace this gate.
- For release validation use `.\gradlew.bat :app:assembleRelease`.
- Focus checks when appropriate: `:data:generateDebugDatabaseInterface`, `:domain:test`, or relevant module tests.
- If Gradle state is stale, use `.\gradlew.bat --stop` before broader cleanup.
- Compilation alone does not verify provider integration. Follow the cross-provider verification contract below for shared behavior or provider changes. Documentation-only changes need whitespace, link, and any edited configuration syntax checks; they do not require an app build.

### E-Ink Motion Policy

- E-Ink compatibility is part of every feature's acceptance criteria, including small UI changes and shared/provider screens outside the readers. Inspect affected loading, saving, refreshing, error/retry, dialog, tab, navigation, list, image, and reader states; copying an existing animated component does not establish compliance. Follow the cross-provider contract for all applicable consumers of changed shared UI.
- Use the shared motion layer and the current `LocalEInkDisplayPolicy` in Compose. View-based and reader code must use the existing `EInkPreferences`/`EInkDisplayPolicy` integration. E-Ink mode must provide a static or immediate state while ordinary mode retains its intended behavior. Do not introduce per-provider motion settings, overwrite the user's animation preferences, or rely only on the Material motion scheme to suppress component-specific animation.
- Use these shared replacements in feature code; their implementations live in [EInkMotion.kt](presentation-core/src/main/java/tachiyomi/presentation/core/motion/EInkMotion.kt) and [CircularProgressIndicator.kt](presentation-core/src/main/java/tachiyomi/presentation/core/components/CircularProgressIndicator.kt). Both determinate and indeterminate progress indicators must use the shared components. Disabling motion must preserve loading feedback, actual progress updates, content, actions, and accessibility semantics.

| Direct API | Required shared API |
| --- | --- |
| Material3 `CircularProgressIndicator` / `LinearProgressIndicator` | `EInkCircularProgressIndicator` / `EInkLinearProgressIndicator` |
| `AnimatedVisibility` / `AnimatedContent` | `EInkAnimatedVisibility` / `EInkAnimatedContent` |
| `Modifier.animateContentSize` | `Modifier.eInkAnimateContentSize` |
| `rememberAnimatedVectorPainter` | `rememberEInkAwareAnimatedVectorPainter` with a meaningful static image |
| `animate*AsState` | Supply `eInkAnimationSpec` for each animation |

- Other motion paths need a real policy-controlled alternative: programmatic scrolling must jump immediately in E-Ink mode; list item transitions and image crossfades must be disabled; pull-to-refresh and custom/View animators must reach their correct state without motion. Keep infinite animations inside the centralized motion layer with a static E-Ink branch that does not start the animation. Inspect ongoing animations and policy changes, including cancellation where needed; a static-looking frame with an active animation/redraw loop is not sufficient.
- Run `.\gradlew.bat verifyEInkMotion` against the final working tree for every feature addition, modification, or fix, before reporting completion and before any authorized commit/push. Run it again after subsequent relevant edits. Do not defer this check to CI or substitute `spotlessCheck`, `:app:compileDebugKotlin`, or selected unit tests. Documentation-only changes with no executable, UI resource, or build configuration changes are exempt. Record the command and result; report failures and blockers explicitly, and never count them as passes.
- The current static rules and reviewed exceptions are defined by [VerifyEInkMotionTask.kt](gradle/build-logic/src/main/kotlin/koharia/gradle/tasks/VerifyEInkMotionTask.kt); [build.gradle.kts](build.gradle.kts) registers the scanned source directories. When adding UI modules/source roots or introducing a motion API, check this coverage and extend it when needed. A passing scan does not prove runtime behavior: inspect each changed call site, not just the presence of a policy symbol somewhere in its file.
- Fix violations through the shared APIs or a reviewed policy branch. Do not remove the CI gate, weaken assertions, add arbitrary allowlist entries, or use aliases, fully qualified calls, dummy imports, or comments to evade detection. A necessary new exception must document why the shared API does not fit and include focused evidence for both E-Ink and ordinary behavior.
- For changed motion behavior, verify E-Ink enabled and disabled states using proportionate policy/component tests and affected UI routes. Cover the relevant in-progress and completion/error states, immediate navigation/scrolling, and switching policy while the screen is active where supported. Reuse existing E-Ink tests and isolated fixtures under the device rules above; do not run every provider/device combination for a small shared change. Report static checks, runtime checks, and unverified routes separately. A single screenshot, compilation, or static scan cannot establish absence of continuing animation or redraws; documentation-only work does not require device validation.

### Security Repair And Validation Workflow

- Security work here is defensive maintenance of this repository and its authorized isolated fixtures. State the affected feature, owned target, non-sensitive test data, expected behavior and original acceptance item before a security-specific test. This context explains scope; it does not override platform restrictions or guarantee approval.
- Prefer existing evidence, upstream fixes and regression cases, source-level review, normal-format compatibility tests and minimal rejection assertions. Once a defect is established, prioritize repairing it and verifying the fix; do not repeatedly demonstrate the old defect or expand it into an exploit chain, a general bypass tool or a new vulnerability-discovery project.
- Routine tests may verify valid input, missing/invalid input and expected permission denial using owned fixtures and dummy data. A policy-unit test or ordinary rejection check does not prove a complete cross-UID security boundary; disclose that coverage limit. Do not weaken existing assertions, remove security tests, or count excluded tests as passed to obtain a green result.
- Separate specialized defect reproducers from the ordinary build/test workflow. Before selecting a suite, inspect whether it includes cross-UID grant borrowing, private-resource access demonstrations, crafted memory-corruption cases, instruction-level vulnerability execution or privileged device recovery. Such steps are not implied by a generic request to continue all tests. Reuse their existing evidence when sufficient and list any still-required specialized verification explicitly. Do not silently invoke them through broad suite selection.
- If the platform flags or rejects an operation for cybersecurity risk, stop that operation and preserve its evidence and partial changes. Do not retry substantially the same operation under different wording, a different thread/model/tool, delegation, or an external script to bypass review. Record the reported reason without guessing hidden keywords. Continue only genuinely distinct permitted repair, compatibility or documentation work; if the blocked verification remains necessary, leave it unverified and explain the official access/support route shown by the platform. User ownership or authorization alone does not remove platform restrictions.
- Use supported package-management APIs and scoped app diagnostics for routine device checks. Do not add root/system package-database access or privileged recovery merely to keep a test running. If ordinary verified data-preserving installation or diagnostics cannot resolve device state, report the environment issue and keep unrelated checks moving; never uninstall or clear data as a workaround.
- Keep test names and reports technically accurate. Do not conceal security purpose, remove context or rename tests to avoid review. Record fixed behavior, reused evidence, checks actually run and remaining coverage separately. No project rule promises that an allowed defensive test will never trigger a platform warning.

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
| `source-api/` | Source ABI; current local-library implementation is under `koharia/source/local/` |
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

- Every registered provider must supply a localized `onboardingDescription`. Keep onboarding entries derived from `ConnectionRegistry`, route each entry to that provider's add/setup flow, and include all applicable storage modes in the guidance. Verify the guide inventory and setup actions against production registration so newly added providers cannot silently disappear from onboarding.
- Before declaring a provider complete, account for connection add/edit/validation/switch/removal, account isolation, shelf/search/filter/cache behavior, card state, standard details, shared and dedicated settings, supported reader routes/offline access/progress, and applicable backup/restore/cleanup. State explicit reasons for unsupported items and list applicable missing items as unfinished work.
- Test shared regressions at the shared layer and integration omissions at the provider/call-site boundary. Reuse contract fixtures across applicable providers; keep their inventory aligned with production registration so a new provider cannot silently escape coverage. Existing `ConnectionArchitectureTest` and provider cache tests are starting points, not proof of complete integration.
- Cover dimensions relevant to the change: partial/complete/removed downloads and unknown totals; absent metadata and single/multiple units; permission restrictions; shared-setting persistence and dedicated-setting visibility after switching; connection/account identity and obsolete session results. Use the remote shelf cache contract for cache changes.
- Prefer meaningful unit/contract tests and protocol fixtures, supplemented by non-destructive real-data validation on the authorized dev installation. Do not require all live servers or every combination for every change. Follow the real-data and isolated-test boundaries above; distinguish fixture, real-service, installed-dev, and changed-build results. Investigate discrepancies rather than closing an issue solely because a fixture passes, and report providers/routes covered and remaining gaps.
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

### Library Storage Modes

- A provider may expose multiple storage modes. Include every applicable mode in its integration inventory; testing the local-folder provider with a native directory does not cover WebDAV or SMB. Network-backed local libraries must follow the remote shelf cache contract and shared download, details, and settings contracts.
- Keep protocol operations behind `LibraryStorageBackend`; do not put network access in synchronous metadata getters. Resource and progress identity must not depend on the currently selected address. Verify both endpoints represent the same root before enabling failover, and never blindly retry mutations after an ambiguous response.
- Keep persistent catalogue/cover state, evictable content caches, and manual downloads distinct. Exercise interrupted writes and conflicting external changes before claiming file-management support; compilation does not validate Android descriptors or SMB runtime compatibility.
- Network roots are not SAF grants. Restore their configuration and pending progress separately from local directory permission rebinding, retain root/account isolation, and require connection validation after restore. Do not back up transient byte caches or reuse another installation's writer identity.
