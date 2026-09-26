<!-- koharia-release-notes:zh -->
> **升级前请注意：本地配置模式调整**
>
> 为降低项目维护难度，0.6.0 已取消本地配置的「独立模式」，统一使用共享配置。如果此前为每个库单独保存了一份配置，更新 App 后需要选择一次希望保留的配置，作为所有库共用的应用与阅读设置。原本已使用共享配置的用户无需重新选择。
>
> 此调整仅涉及应用与阅读设置；各库的内容、账号、筛选条件与阅读记录仍分别保留。

## Koharia 0.6.0

本次更新新增 **smanga 接入、EPUB 朗读与本地 Markdown 阅读**，并优化本地库、系列章节显示、备份恢复和阅读体验。

### EPUB 朗读

- 新增 EPUB 朗读，支持逐句高亮、上一句／下一句、暂停／继续与自动续读下一章。
- 支持锁屏及蓝牙媒体控制，并改善音频焦点切换、句间衔接与中断后的恢复。
- 默认使用 Microsoft Edge 在线语音，也可配置自己的 API Key 使用 Xiaomi MiMo TTS；可按引擎调整声音和语速。
- 修复部分中文 EPUB 的目录跳转问题。

朗读使用在线语音服务，当前章节文本会发送至所选服务进行语音合成。

小米 MiMo TTS 目前可免费试用（限时），可前往[小米 MiMo 开放平台注册试用](https://platform.xiaomimimo.com/?ref=RD7JZG)。注册并创建 API Key 后，在 Koharia 的语音引擎设置中选择 MiMo 并填写密钥即可使用。免费试用范围、额度与期限以[小米官方说明](https://mimo.mi.com/models/zh-CN/mimo-v2.5-tts)及平台最新规则为准。

### 新增 smanga 支持

- 可使用服务器地址、用户名与密码添加 smanga 连接，支持多个服务器及同一服务器的公网／局域网双地址。
- 支持媒体库浏览、搜索、排序、系列详情、漫画与 PDF 阅读，以及下载后离线阅读。
- 支持阅读进度、已读状态与历史记录同步；各连接的书架缓存和阅读状态分别保存。
- 已加载的书架可从本地缓存恢复，刷新失败时保留已有内容。

接入基于 smanga 4.3 的接口实现，服务器需要启用 OPDS。

### 本地 Markdown 阅读

- 本地库新增 Markdown 文档支持，可通过扫描、导入或外部打开进入阅读。
- 支持分页、字体与排版设置，以及标题、列表、引用和代码块等内容显示。
- 新增文档大纲导航，改善标题定位并保留代码块的缩进和空白。

### 本地库与书架

- 优化本地目录扫描、文件信息查询与索引复用，减少刷新及大量书籍加载时的重复工作。
- 修复单个不可读附属文件导致整个目录刷新失败的问题。
- 修复系列详情中的已读状态、阅读进度及部分章节显示设置未正确生效的问题。
- 系列章节的列表／网格显示模式统一跟随共享设置，不再为单个系列保存独立模式；之前设置的系列专属模式将不再使用。
- 完善书架与章节网格设置，支持分别调整横屏、竖屏列数，以及章节进度、文件大小和缺失章节提示的显示。
- 本地库模式下隐藏「离线缓存」「仅显示缓存」等不适用入口，并避免缓存筛选影响本地章节的显示与阅读。

### 阅读、连接与下载

- 整理通用阅读设置，支持自定义阅读工具栏快捷项，改善状态信息的显示与布局。
- 优化双页阅读、宽页拆分及屏幕尺寸变化后的阅读位置，改善电子墨水屏下的加载动画行为。
- 完善服务器公网／局域网双地址切换与回退，改善 LANraragi 阅读进度和预加载行为。
- 改善大文件下载、断点续传校验及失败重试，避免将异常响应或不完整文件误判为下载完成。

### 备份与恢复

- 新增备份密码加密及敏感配置的可选备份，完善连接配置、阅读状态与书签的保存和恢复。
- 改善 EPUB 位置、朗读位置及 LANraragi／smanga 阅读状态恢复，并支持重新授权、映射本地目录。
- 自定义封面改为保存在用户存储目录，避免清理缓存时丢失。自定义封面和外部文件仍需另行保存，不包含在备份文件中。

### 其他

- 「更多 → 支持我们」新增 Patreon 入口，并简化对原项目 Mihon 的赞助提醒。
- 更新项目说明，补充 smanga 与 Markdown 支持介绍。

### 贡献者致谢

感谢以下贡献者为 0.6.0 带来的功能与改进：

- [@CurrenWong](https://github.com/CurrenWong)：贡献 EPUB 朗读与中文 EPUB 目录跳转修复（[#90](https://github.com/Mister-album/Koharia/pull/90)）、构建依赖修复（[#92](https://github.com/Mister-album/Koharia/pull/92)），以及本地 Markdown 阅读与大纲导航（[#95](https://github.com/Mister-album/Koharia/pull/95)）。
- [@Mister-album](https://github.com/Mister-album)：负责 smanga 接入、共享配置、备份恢复、本地库与阅读器优化，以及功能整合和版本发布。

也感谢参与测试、反馈问题与提出建议的各位用户！

### 下载与安装

- [GitHub 下载](https://github.com/Mister-album/Koharia/releases/tag/v0.6.0)：不确定设备架构时，可选择通用安装包 `Koharia-v0.6.0-release.apk`；也提供各架构安装包。
- [夸克网盘](https://pan.quark.cn/s/f80624cde564?pwd=8tbp)，提取码：`8tbp`。
- [百度网盘](https://pan.baidu.com/s/1DlOuovGpIkaQh6NSo7b4cw?pwd=6s2g)，提取码：`6s2g`。
- FOSS 版：`Koharia-v0.6.0-foss.apk`，不含遥测组件，使用独立包名，可与普通版共存，不能直接覆盖普通版。

同一渠道、相同签名的版本可直接覆盖升级，无需卸载或清除数据。

### 交流与反馈

欢迎加入 **QQ 群：625289125**，交流使用体验或反馈问题。

<!-- koharia-release-notes:en -->
> **Before updating: shared app configuration**
>
> To reduce project maintenance complexity, version 0.6.0 removes the independent mode for locally stored app settings. All libraries now share one app and reader configuration. If you previously kept a separate configuration for each library, you will need to choose once after updating which configuration to keep and use across all libraries. Users already using shared configuration do not need to make this choice again.
>
> This change only affects app and reader settings. Each library retains its own content, credentials, filters, and reading records.

## Koharia 0.6.0

This release adds **smanga integration, EPUB read-aloud, and local Markdown reading**, alongside improvements to local libraries, chapter display, backup and restore, and the reading experience.

### EPUB read-aloud

- Read EPUBs aloud with sentence highlighting, previous/next sentence controls, pause/resume, and automatic continuation into the next chapter.
- Use lock-screen and Bluetooth media controls, with improved audio focus handling, sentence transitions, and recovery after interruptions.
- Microsoft Edge online voices are the default. Xiaomi MiMo TTS is also available with your own API key, with voice and speed options depending on the engine.
- Fixed table-of-contents navigation in some Chinese EPUBs.

Read-aloud uses online speech services. The current chapter's text is sent to the selected service for speech synthesis.

Xiaomi MiMo TTS currently offers a free trial for a limited time. [Register on the Xiaomi MiMo platform](https://platform.xiaomimimo.com/?ref=RD7JZG) to try it. After registering and creating an API key, select MiMo in Koharia's speech engine settings and enter your key. Trial availability, quotas, and duration are subject to [Xiaomi's official information](https://mimo.mi.com/models/zh-CN/mimo-v2.5-tts) and the platform's latest terms.

### smanga support

- Add smanga connections using a server URL, username, and password. Multiple servers and public/LAN addresses for the same server are supported.
- Browse, search, and sort media libraries; view series details; read comics and PDFs; and download content for offline reading.
- Sync reading progress, read status, and history, with separate shelf caches and reading state for each connection.
- Restore previously loaded shelves from local cache and retain existing content if a refresh fails.

The integration targets the smanga 4.3 API and requires OPDS to be enabled on the server.

### Local Markdown reading

- Added Markdown document support to local libraries, including scanning, importing, and opening files from other apps.
- Read with pagination, font and layout settings, and support for headings, lists, quotations, and code blocks.
- Added document outline navigation, improved heading positions, and preserved code-block indentation and whitespace.

### Local libraries and shelves

- Optimized directory scanning, file metadata queries, and index reuse to reduce repeated work during refreshes and when loading large libraries.
- Fixed an unreadable ancillary file causing an entire directory refresh to fail.
- Fixed missing read status and reading progress in series details, along with chapter display settings that did not take effect correctly.
- Chapter list/grid layout now follows the shared setting. Layout is no longer saved separately for each series, and previous per-series layouts are no longer used.
- Improved shelf and chapter grid controls, including separate portrait/landscape column counts and visibility options for chapter progress, file sizes, and missing-chapter indicators.
- Hid inapplicable offline-cache and cached-only controls in local-library mode, and prevented cache filters from affecting local chapter display and reading.

### Reading, connections, and downloads

- Reorganized common reader settings, added customizable toolbar shortcuts, and improved reading-status display and layout.
- Improved double-page reading, wide-page splitting, and reading-position handling after window-size changes, as well as loading animations on E-Ink devices.
- Improved public/LAN server address switching and fallback, along with LANraragi progress handling and preloading.
- Improved large-file downloads, resume validation, and retry recovery so invalid responses or incomplete files are not treated as completed downloads.

### Backup and restore

- Added password-encrypted backups and optional inclusion of sensitive configuration, with improved preservation of connection settings, reading state, and bookmarks.
- Improved restoration of EPUB positions, read-aloud positions, and LANraragi/smanga reading state, with support for reauthorizing and remapping local folders.
- Custom covers are now stored in user storage so clearing caches does not remove them. Custom covers and external files still need to be saved separately; they are not included in backup files.

### Other changes

- Added a Patreon link under More → Support us and simplified the reminder to support the original Mihon project.
- Updated the project documentation with smanga and Markdown support details.

### Contributors

Thank you to the contributors who helped make 0.6.0 possible:

- [@CurrenWong](https://github.com/CurrenWong): contributed EPUB read-aloud and Chinese EPUB table-of-contents fixes ([#90](https://github.com/Mister-album/Koharia/pull/90)), a build dependency fix ([#92](https://github.com/Mister-album/Koharia/pull/92)), and local Markdown reading with outline navigation ([#95](https://github.com/Mister-album/Koharia/pull/95)).
- [@Mister-album](https://github.com/Mister-album): developed smanga integration, shared configuration, backup and restore improvements, and local-library and reader optimizations, and handled integration and release preparation.

Thanks also to everyone who tested the app, reported issues, and shared suggestions!

### Downloads and installation

- [GitHub downloads](https://github.com/Mister-album/Koharia/releases/tag/v0.6.0): choose the universal `Koharia-v0.6.0-release.apk` if you are unsure of your device architecture. Architecture-specific APKs are also available.
- [Quark mirror](https://pan.quark.cn/s/f80624cde564?pwd=8tbp), access code: `8tbp`.
- [Baidu mirror](https://pan.baidu.com/s/1DlOuovGpIkaQh6NSo7b4cw?pwd=6s2g), access code: `6s2g`.
- FOSS edition: `Koharia-v0.6.0-foss.apk`, without telemetry components. It uses a separate application ID and can coexist with the regular edition, but cannot update it in place.

Builds from the same channel with the same signing certificate can be updated in place. There is no need to uninstall the app or clear its data.

### Community and feedback

Join the [Komga Discord server](https://discord.gg/komga-678794935368941569) and visit the **Koharia** channel to share feedback and discuss the app.

<!-- koharia-release-notes:end -->

**文件校验 / File verification:** SHA-256 checksums are included in the attached `SHA256SUMS.txt` / SHA-256 校验值见附件 `SHA256SUMS.txt`。

**完整变更 / Full changelog:** [v0.5.0...v0.6.0](https://github.com/Mister-album/Koharia/compare/v0.5.0...v0.6.0)
