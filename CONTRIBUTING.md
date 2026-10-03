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

Device validation must follow `AGENTS.md`: use an explicitly selected device and an isolated fixture package, retaining installed packages and existing app data. Documentation-only changes need the relevant whitespace, link, and configuration syntax checks, not an app build.

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
