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
- When reporting a bug, include the Koharia version, Android version, device model, Komga server version if relevant, and clear reproduction steps.

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
