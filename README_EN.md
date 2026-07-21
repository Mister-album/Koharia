<div align="center">

<img src="./.github/assets/logo.png" alt="Koharia logo" title="Koharia logo" width="80"/>

<p><a href="./README.md">简体中文</a> · <strong>English</strong></p>

# Koharia

An Android reader for both comics and EPUB books

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-0877d2?labelColor=27303D)](./LICENSE)
[![Latest release](https://img.shields.io/github/v/release/Mister-album/Koharia?label=release)](https://github.com/Mister-album/Koharia/releases/latest)

</div>

## Overview

Koharia is a third-party Android client and reader for [Komga](https://komga.org/) servers. It provides dedicated reading experiences for comics, scanned image content, and reflowable EPUB books. Browsing, series details, reading progress, offline access, and reader customization are brought together in one app, while comics and books are each presented in the way that best suits them.

The project is built on the mature Android reading foundation of [Mihon](https://github.com/mihonapp/mihon). Koharia does not provide or host any content. What you can browse depends on the media libraries you connect to and the permissions of your account.

<table>
  <tr>
    <td align="center" width="25%">
      <img src="./.github/assets/screenshots/library.png" alt="Koharia library screen" width="150"/><br/>
      <sub>Library</sub>
    </td>
    <td align="center" width="25%">
      <img src="./.github/assets/screenshots/series-details.png" alt="Koharia series details screen" width="150"/><br/>
      <sub>Series details</sub>
    </td>
    <td align="center" width="25%">
      <img src="./.github/assets/screenshots/comic-reader.png" alt="Koharia comic reader" width="150"/><br/>
      <sub>Comic reader</sub>
    </td>
    <td align="center" width="25%">
      <img src="./.github/assets/screenshots/epub-reader.png" alt="Koharia EPUB reader" width="150"/><br/>
      <sub>Book reader</sub>
    </td>
  </tr>
</table>

## Who is it for?

- Readers who want comics and EPUB books in a single Android app.
- People with a personal or family media library who need cover browsing, series details, reading history, and progress synchronization.
- Readers who value control over reading direction, typography, background colors, page turning, and offline access.
- Users who want manual downloads, book caching, and comic page caching to be managed separately.

Koharia focuses on reading from personal media libraries. It does not provide public online content sources and is not intended to restore the traditional extension ecosystem.

## Key features

### Unified comic and book management

- Optionally split media libraries into Comics and Books, or keep everything in a combined library.
- Cover grid and list views, search, filters, sorting, series details, reading history, and quick switching between servers.
- Server library classification is kept separate from local preferences, making it easier to organize different media libraries.

### Comic reading

- Paged, continuous scrolling, left-to-right, right-to-left, and dual-page reading modes.
- Common controls for zooming, rotation, cropping, reading direction, chapter navigation, and progress seeking.
- Prioritizes the current page and prefetches nearby pages according to the reading direction for smoother navigation.
- Supports per-page caching and manual downloads, so cached content remains available when the network is unstable.

### EPUB book reading

- A native EPUB reading flow with paged and continuous scrolling modes in multiple directions.
- Adjustable font size, font family, line height, paragraph spacing, page margins, first-line indentation, and reading area.
- Custom background colors, brightness, publisher styles, volume-key page turning, and display cutout support.
- Table of contents, bookmarks, full-text search, chapter navigation, reading percentage, and visual page counts.
- Recalculates the current and total visual pages after layout changes, and reuses pagination results for matching device and layout settings.

### Progress, offline access, and data management

- Saves local reading positions, history, and bookmarks, and synchronizes supported reading progress with the server.
- Manual downloads, book cache, and comic page cache use separate policies; cached content is never incorrectly marked as downloaded.
- Cache size limits, on-demand resource loading, offline access, and server-specific download directories.
- Multiple server profiles, independent reader settings, backup and restore, and database migration from older releases.

## Download

| Channel | Download | Notes |
| --- | --- | --- |
| GitHub Releases | [Download the latest release](https://github.com/Mister-album/Koharia/releases/latest) | Recommended; includes complete release notes and APK assets |
| Quark Drive | [Open download link](https://pan.quark.cn/s/f80624cde564?pwd=8tbp) | Access code: `8tbp` |
| Baidu Netdisk | [Open download link](https://pan.baidu.com/s/1DlOuovGpIkaQh6NSo7b4cw?pwd=6s2g) | Access code: `6s2g` |

Before installing, make sure the APK was published by this project. GitHub Releases is the source of truth for version information and release notes.

## Building the project

Koharia supports Android 8.0 and later. Android Studio is recommended, but the project can also be built from Windows PowerShell with Gradle.

Common validation commands:

```powershell
.\gradlew.bat spotlessCheck
.\gradlew.bat :app:compileDebugKotlin
```

Build a release APK:

```powershell
.\gradlew.bat :app:assembleRelease
```

Release signing reads the local `keystore.properties` file. You normally do not need to configure release signing for local development or debugging.

## Origins and attribution

Koharia is based on [Mihon](https://github.com/mihonapp/mihon) and is distributed under the Apache License 2.0. License and attribution details are available in [LICENSE](./LICENSE) and [NOTICE](./NOTICE).

See [CONTRIBUTING.md](./CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md) for contribution guidelines. If you redistribute Koharia or create a derivative project, retain the required attribution and do not describe it as an official Mihon or Komga release.

## Acknowledgements

Koharia builds on the original work by Javier Tomas, the continuing contributions of the Mihon community, and the server ecosystem maintained by the Komga community. Thank you to everyone who continues to improve this derivative project.

## Community and feedback

Join the [Komga Discord server](https://discord.gg/komga-678794935368941569) and visit the `Koharia` channel to discuss the app, share your reading experience, and report issues you encounter.

## Support

Koharia is an independently maintained open-source project. Ongoing maintenance requires time for upstream changes, reader improvements, downloads and synchronization, Android compatibility, testing, and releases.

If Koharia is useful to your reading workflow, you can support the project through Afdian. Your support helps keep the project updated and makes it possible to spend more time fixing issues and improving the comic and book reading experience.

- Afdian: [https://ifdian.net/a/album-Koharia](https://ifdian.net/a/album-Koharia)

## Disclaimer

Koharia does not provide, host, or index any content. The app only connects to personal media servers configured by the user and displays content the account is authorized to access. Make sure you have the right to use the content in your libraries and comply with the laws applicable in your region.

## License

Copyright (C) 2015 Javier Tomas

Copyright (C) Mihon contributors

Copyright (C) 2026 Koharia contributors

This project is licensed under the Apache License, Version 2.0. See [LICENSE](./LICENSE) and [NOTICE](./NOTICE) for details.
