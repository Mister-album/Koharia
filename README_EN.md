<div align="center">

<img src="./.github/assets/logo.png" alt="Koharia logo" title="Koharia logo" width="80"/>

<p><a href="./README.md">简体中文</a> · <strong>English</strong></p>

# Koharia

An Android comic and book reader for Komga, LANraragi, smanga, and local media libraries

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-0877d2?labelColor=27303D)](./LICENSE)
[![Latest release](https://img.shields.io/github/v/release/Mister-album/Koharia?label=release)](https://github.com/Mister-album/Koharia/releases/latest)

</div>

## Overview

Koharia is a third-party Android client and reader for [Komga](https://komga.org/), [LANraragi](https://github.com/Difegue/LANraragi), and [smanga](https://github.com/lkw199711/smanga) servers, as well as local media libraries. It provides dedicated reading experiences for comics, scanned image content, PDFs, and reflowable books such as EPUB, TXT, MOBI, and Markdown. Browsing, series details, reading progress, offline access, and reader customization are brought together in one app. Supported formats and synchronization features vary by source, as described below.

The project is built on the mature Android reading foundation of [Mihon](https://github.com/mihonapp/mihon). Koharia does not provide or host any content. What you can browse depends on the servers you connect to, your account permissions, and the local directories you explicitly grant the app access to.

<table>
  <tr>
    <td align="center" width="25%">
      <img src="./.github/assets/screenshots/epub-reader.png" alt="Koharia EPUB reader" width="180"/><br/>
      <sub>Book reader</sub>
    </td>
    <td align="center" width="25%">
      <img src="./.github/assets/screenshots/comic-reader.png" alt="Koharia comic reader" width="180"/><br/>
      <sub>Comic reader</sub>
    </td>
    <td align="center" width="25%">
      <img src="./.github/assets/screenshots/series-details.png" alt="Koharia series details screen" width="180"/><br/>
      <sub>Series details</sub>
    </td>
    <td align="center" width="25%">
      <img src="./.github/assets/screenshots/library.png" alt="Koharia library screen" width="180"/><br/>
      <sub>Library</sub>
    </td>
  </tr>
</table>

## Who is it for?

- Readers who want comics, PDFs, and multiple ebook formats in a single Android app.
- People with a personal or family media library who need cover browsing, series details, reading history, and progress synchronization.
- Users who want to link existing folders on their device or let the app create and manage comic and book directories.
- Readers who value control over reading direction, typography, background colors, page turning, and offline access.
- Users who want manual downloads, book caching, and comic page caching to be managed separately.

Koharia focuses on reading from personal media libraries. It does not provide public online content sources and is not intended to restore the traditional extension ecosystem.

## Key features

### Komga libraries

- Built-in Komga support with multiple server and account connections and quick switching from the shelf.
- Browse series and books by server library, view covers and metadata, and use search, filters, and sorting.
- Read comics in paged, continuous scrolling, or dual-page modes, and EPUB books in the reflowable reader.
- Online reading, manual downloads, offline access, and synchronization of supported reading progress and history with the server.
- Shelf data is cached locally and shown first; manual refresh and server update events retrieve changes.

### LANraragi libraries

- Built-in support without extensions. Add multiple servers using a server URL and API key; leave the key empty when authentication is disabled.
- Read each Archive as an individual entry, browse Tankoubon collections and their Archive members, and switch Categories from the top of the shelf.
- Search, tag and reading-status filters, sorting, online reading, and offline reading of manual downloads.
- Open an Archive directly in the reader or show a details page with a grid of page previews, then start reading from a selected page.
- Cached library metadata remains available for offline browsing and search. Page content requires a separate download; metadata cache does not mean a book is downloaded.
- Synchronize progress per Archive and retry pending offline reading updates after reconnecting. The server must allow progress tracking and grant the required permissions. Marking an entry unread resets local state only, leaving server progress unchanged.

### smanga libraries

- Built-in support without extensions, targeting the **smanga 4.3 API**. Connect with a server URL, username, and password, with **OPDS enabled** on the server. Saving a connection validates both login and OPDS access.
- Add multiple connections, including server URLs with reverse-proxy subpaths or an `/api` suffix. An optional LAN address for the same server is preferred on Wi-Fi.
- Browse all media libraries accessible to the account or select a single library. Search by name, sort by name, update time, or creation time, and filter to downloaded content.
- View series details and chapter lists, read comic pages and PDFs, and download chapters for offline reading. PDFs require the complete original file before pages can be rendered.
- Shelf and details data are cached persistently and shown first on startup. Previously cached content remains browsable offline; uncached queries still require a connection. Catalogue caching and content downloads are managed separately.
- Synchronize chapter progress, read status, and reading history, with pending updates stored locally for retry. Progress conflicts or changes to page mapping can prompt a choice between local and server progress. Caches and reading records are isolated by connection and account.

### Local media libraries

- Read local comic archives, image folders, PDFs, EPUB, TXT, MOBI, and **Markdown documents**. Markdown supports paginated book-style reading with headings, lists, blockquotes, and code blocks, plus adjustable fonts and typography.
- Link existing folders through Android's system directory picker without moving or deleting their files, or let Koharia create a managed `Comics`, `Books`, and `.koharia` directory structure.
- Mark local directories as comics, books, or mixed content and assign them to custom bookshelves.
- Choose between a series-based library, where each top-level folder is treated as a series, and an individual-file library, which recursively lists files and image folders that can be opened directly.
- Local indexing, pull-to-refresh, cover extraction, format filters, entry metadata editing, and first-page cover generation.
- Open supported files from Android file pickers or share actions for temporary reading, or import them into a writable local library directory.
- Consistent format detection based on file extensions, MIME types, and file signatures, including files with missing or inaccurate extensions.
- Store metadata edits only in the app database, in adjacent `ComicInfo.xml` / `metadata.opf` sidecars, or in the library's unified `.koharia/metadata` directory.

#### Supported local formats

| Content type | Extensions or form | Reader and limitations |
| --- | --- | --- |
| Comic archives | `CBZ`, `ZIP`, `CBR`, `RAR`, `7Z`, `CB7`, `TAR`, `CBT` | Comic reader with paged, continuous scrolling, and dual-page modes |
| Images and image folders | `JPG`, `JPEG`, `PNG`, `GIF`, `WEBP`, `AVIF`, `HEIF`, `HEIC`, `JXL` | Read as individual entries or organized image directories |
| EPUB | `EPUB` | Native reflowable reader with table of contents, bookmarks, search, and typography controls |
| PDF | `PDF` | Native page rendering through the paged or continuous comic reading flow |
| Plain text | `TXT` | Detects common encodings including UTF-8, UTF-16, and GB18030; supports pagination and book typography controls; 64 MiB file limit |
| Markdown | `MD`, `MARKDOWN`, `MDOWN`, `MKD`, `MKDN` | Reflowable pagination with rich text for headings, bold, italic, lists, blockquotes, and code blocks; supports pagination and book typography controls; embedded images are not rendered and tables degrade to plain text; 16 MiB file limit |
| Mobipocket / Kindle | `MOBI`, `PRC`, `AZW`, `AZW3` | Experimental PalmDOC / KF8 text and basic metadata support with reflowable pagination; DRM, complex layouts, and embedded images are not supported; 256 MiB file limit |
| DjVu | `DJVU`, `DJV` | Uses the MIT-licensed `djvu-rs` WASM decoder for JB2 / IW44 pages and displays them in the comic reader; requires WebAssembly support in Android WebView |

The DjVu decoder runs in the JavaScript / WebAssembly runtime provided by the system WebView. Chicory is not bundled or used by the current build. See [`app/src/main/assets/djvu/README.txt`](./app/src/main/assets/djvu/README.txt) for its source, license, and checksum.

### Unified comic and book management

- Optionally split media libraries into Comics and Books, or keep everything in a combined library.
- Cover grid and list views, search, filters, sorting, series details, reading history, and quick switching between servers.
- Accounts, library data, and reading records are isolated by connection. App appearance and reader settings are shared for a consistent experience when switching sources.

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
- Read aloud while following sentence highlighting, with automatic continuation and controls for the speech engine, voice, and speed.
- Recalculates the current and total visual pages after layout changes, and reuses pagination results for matching device and layout settings.

#### Read-aloud (TTS)

- Reads the current EPUB chapter sentence by sentence and continues into the next chapter automatically; sentence highlighting and reading progress stay in sync, and you can skip sentences, pause, and resume from the in-reader control bar or from the lock screen / Bluetooth media keys.
- Microsoft Edge online voices are used by default, with no API key required. You can also choose Xiaomi MiMo TTS with your own API key. Voices are stored separately per engine.
- Playback controls and frequently used options are available inside the reader; full configuration is under the speech engine section of book reader settings.
- Adjustable reading speed; inter-sentence gaps and MP3 encoder delay/padding are trimmed automatically for smooth playback.
- Read-aloud holds audio focus and coexists correctly with other players: playback resumes after a short interruption such as a phone call, and volume is lowered when ducking is requested.
- Synthesized sentences are cached on device (256 MiB cap, least-recently-used eviction), so replaying a chapter does not re-synthesize it.
- **Data disclosure**: speech is synthesized by the selected engine, so **the current chapter text is uploaded to that vendor** (MiMo or Microsoft). A one-time dialog confirms this before the first listen and the settings page keeps the notice permanently visible; use another reading mode if you would rather not share chapter text.
- API keys are stored only on the device, encrypted with the Android keystore (AES-256-GCM); no plaintext key is present in build artifacts or backups.

Xiaomi MiMo TTS is **currently available for a free, limited-time trial**. You can [register on the Xiaomi MiMo Open Platform](https://platform.xiaomimimo.com?ref=RD7JZG) to try it. Create an API key, then select MiMo and enter the key in Koharia’s speech engine settings. Trial availability, quotas, and duration are subject to [Xiaomi’s official information](https://mimo.mi.com/models/en-US/mimo-v2.5-tts) and the platform’s latest terms.

### Progress, offline access, and data management

- Komga, LANraragi, and smanga shelves show existing local cache first. Missing data is fetched on demand, with manual refresh available afterward; Komga also responds to server update events.
- Saves local reading positions, history, and bookmarks, and synchronizes supported reading progress with the server.
- Manual downloads, book cache, and comic page cache use separate policies; cached content is never incorrectly marked as downloaded.
- Cache size limits, on-demand resource loading, offline access, and server-specific download directories.
- Multiple server and local library connections, shared reader settings, backup and restore, and database migration from older releases.

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

See [CONTRIBUTING.md](./CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md) for contribution guidelines. If you redistribute Koharia or create a derivative project, retain the required attribution and do not describe it as an official Mihon, Komga, LANraragi, or smanga release.

## Acknowledgements

Koharia builds on the original work by Javier Tomas, the continuing contributions of the Mihon community, and the server ecosystem maintained by the Komga community. Thank you to everyone who continues to improve this derivative project.

## Community and feedback

Join the [Komga Discord server](https://discord.gg/komga-678794935368941569) and visit the `Koharia` channel to discuss the app, share your reading experience, and report issues you encounter.

## Support

Koharia is an open-source project improved by its maintainer and community contributors. Ongoing maintenance requires time for upstream changes, reader improvements, downloads and synchronization, Android compatibility, testing, and releases.

If Koharia is useful to your reading workflow, you can support the project through Patreon or Afdian. Your support helps sustain maintenance, bug fixes, and improvements to the comic and book reading experience. Code, documentation, testing, and issue reports are equally welcome ways to contribute.

- Patreon: [https://www.patreon.com/c/ALBUM937](https://www.patreon.com/c/ALBUM937)
- Afdian: [https://ifdian.net/a/album-Koharia](https://ifdian.net/a/album-Koharia)

## Disclaimer

Koharia does not provide or host any content. The app only connects to personal media servers configured by the user and scans or imports local files the user explicitly authorizes; the local index is used solely to organize and display that content on the device. Make sure you have the right to use the content on your servers and in your local directories, and comply with the laws applicable in your region.

## License

Copyright (C) 2015 Javier Tomas

Copyright (C) Mihon contributors

Copyright (C) 2026 Koharia contributors

This project is licensed under the Apache License, Version 2.0. See [LICENSE](./LICENSE) and [NOTICE](./NOTICE) for details.

## Contributors

Thank you to everyone who contributes code, documentation, translations, testing, and issue reports to Koharia. Read the [contribution guide](./CONTRIBUTING.md) to get involved.

<!-- koharia-contributors:start -->
<p>
  <a href="https://github.com/CurrenWong"><img src="./.github/assets/contributors/CurrenWong.svg" width="64" height="64" alt="CurrenWong" title="CurrenWong" /></a>
  <a href="https://github.com/Mister-album"><img src="./.github/assets/contributors/Mister-album.svg" width="64" height="64" alt="Mister-album" title="Mister-album" /></a>
</p>
<!-- koharia-contributors:end -->

[Report an issue or suggest an improvement](https://github.com/Mister-album/Koharia/issues)
