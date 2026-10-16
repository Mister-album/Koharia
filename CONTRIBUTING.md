Looking to report an issue/bug or make a feature request? Please refer to the local [README](./README.md) first and then use the issue tracker configured for this repository.

---

Thanks for your interest in contributing to Koharia!


# Code contributions

Pull requests are welcome!

If you're interested in taking on an open issue, please comment on it so others are aware.
You do not need to ask for permission nor an assignment.

Koharia is a derivative work based on [Mihon](https://github.com/mihonapp/mihon). Contributions to this repository are made to the Koharia project and should preserve existing Apache-2.0 attribution and derivative-work notices.

## Prerequisites

Before you start, please note that the ability to use following technologies is **required** and that existing contributors will not actively teach them to you.

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)

### Tools

- [Android Studio](https://developer.android.com/studio)
- Emulator or phone with developer options enabled to test changes.

## Getting help

- Review the local [README](./README.md), existing issues, and recent changes before opening a new report.
- When reporting a bug, include the Koharia version, Android version, device model, provider type and server version if relevant, content format, entry point, relevant account permissions, and clear reproduction steps. Comparing other libraries is helpful when available, but reporters do not need access to every provider.

## Shared features and provider integration

[AGENTS.md](./AGENTS.md) defines the development, product, and verification contracts for contributors and coding agents. Read its cross-provider, series, settings, shelf-entry, and cache contracts before changing those areas.

1. Determine whether the request concerns shared behavior or a provider-specific protocol. Inspect the current `ConnectionRegistry` registration and relevant working-tree implementations; distinguish development from released support.
2. For affected providers and routes, identify what is applicable and implemented, applicable but missing, or not applicable with a reason. A report mentioning one library does not limit a shared fix to that library.
3. Implement generic presentation, state, and behavior through shared components and capability adapters. Integrate every applicable consumer within the requested scope; retain provider-specific protocols, identities, and permission rules. Protocol-only fixes need no unrelated refactor.
4. Verify the changed shared behavior and its provider wiring with appropriate contract tests/fixtures and any necessary UI checks. A shared widget test or successful compilation alone cannot demonstrate that each provider uses it.
5. Write the PR title and description in Chinese and English. Summarize applicability, shared implementation, checks actually run, and unfinished/unverified items using the PR template. Keep task-local matrices and evidence under `.test-artifacts/<task>/` or in the PR; do not commit per-run reports.

New providers must satisfy the completion checklist in `AGENTS.md`; default-hidden settings, omitted card state, and no-op callbacks do not count as implemented features. Shared capability work is compatible with the personal-media-library direction and does not authorize restoring public content-source or extension-store ecosystems.

Every feature addition, modification, or fix must follow the authoritative [E-Ink Motion Policy](./AGENTS.md#e-ink-motion-policy), including small loading indicators and screens outside the readers. Reuse its shared components and run the following against the final working tree before completion and any authorized commit/push:

```powershell
.\gradlew.bat verifyEInkMotion
```

Compilation and formatting checks do not run or replace this gate. Record its result and the applicable E-Ink enabled/disabled behavior checks in the PR; distinguish static verification from runtime coverage and disclose gaps. The policy defines the documentation-only exemption and proportional device validation requirements.

Device validation must follow [AGENTS.md](./AGENTS.md), including **Staged Dev Update And Read-Only Validation**: use the existing data-preserving dev update authorization, distinguish installation/startup from online validation, and resolve or report concrete prerequisites rather than requesting the same permission again. Remote progress/history writes are not included in that authorization. Keep instrumentation, destructive scenarios, and fault injection in isolated fixture packages. Explicitly select the device, retain installed packages and existing data, and distinguish installed-dev results from validation of changed builds. Documentation-only changes need the relevant whitespace, link, and configuration syntax checks, not an app build.

Security repairs follow the **Security Repair And Validation Workflow** in `AGENTS.md`. Reuse established defect evidence, prioritize fixes and compatibility checks, inspect suites before running specialized reproducers, and report blocked or omitted verification honestly. Do not retry a platform-rejected operation through another wording, task, tool or model.

For shrinking regressions, build the isolated non-debuggable variant with:

```powershell
.\gradlew.bat -PdeviceTestFixture=true -PminifiedDeviceTestFixture=true :app:assembleMinifiedFixture :app:assembleMinifiedFixtureAndroidTest
```

This variant inherits Release rules, enables code/resource shrinking, and uses the debug signing key and `app.koharia.dev.devicefixture` package. It includes the fixture Activity hosts and tracing entry points required by the test runner; these additions are fixture-only. Before installing with `adb -s <serial> install -r`, verify both APK package IDs and the instrumentation target; retain data and stop on signature/version incompatibility. The regular fixture runner guard still applies. Default Release builds and user packages are unaffected. Record runtime coverage and do not treat a successful build as runtime validation.

Use `koharia.diagnostics.MinifiedRuntimeDeviceTest` for platform-entry-point smoke checks. Tests that call app internals directly belong in the regular Debug fixture: R8 can inline or remove those methods within the target APK, while a separately compiled test APK still references their original signatures. Do not broaden production keep rules to make such tests pass. Supplement minified smoke tests with actual reader/navigation checks and report their coverage separately.

# Translations

Translations currently live in the repository under `i18n/`. If translation workflow changes later, document it in this file or the project README.


# Forks

Forks are allowed so long as they abide by the project's [LICENSE](./LICENSE) and [NOTICE](./NOTICE).

When creating a fork, remember to:

- To avoid confusion with the main app:
    - Change the app name
    - Change the app icon
    - Change or disable the [app update checker](./app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt)
    - Avoid implying official affiliation with Koharia, Mihon, or Komga
- To avoid installation conflicts:
    - Change the `applicationId` in [app/build.gradle.kts](./app/build.gradle.kts)
- To avoid having your data polluting the main app's analytics and crash report services:
    - If you want to use Firebase analytics, replace [app/google-services.json](./app/google-services.json) with your own
- To comply with Apache-2.0 redistribution requirements:
    - Keep the original license text
    - Retain relevant copyright and attribution notices
    - Mark modified files with prominent notices that you changed them
