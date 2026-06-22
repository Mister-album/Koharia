<div align="center">

<img src="./.github/assets/logo.png" alt="Koharia logo" title="Koharia logo" width="80"/>

# Koharia

面向 Komga 服务器的第三方 Android 阅读器

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-0877d2?labelColor=27303D)](./LICENSE)
[![Latest release](https://img.shields.io/github/v/release/Mister-album/Koharia?label=release)](https://github.com/Mister-album/Koharia/releases/latest)

</div>

## 项目简介

Koharia 是一个基于 [Mihon](https://github.com/mihonapp/mihon) 的 Android 阅读器分支，目标是为使用 [Komga](https://komga.org/) 管理漫画、书籍或图像类内容的用户提供更直接的移动端阅读体验。

它不是 Mihon 或 Komga 的官方项目，也不托管任何内容。你能看到和阅读的内容，取决于你连接的 Komga 服务器、服务器中的媒体库，以及账号拥有的访问权限。

## 适合谁使用

如果你已经在使用 Komga 作为个人或家庭内容服务器，并希望在 Android 设备上阅读、缓存和同步阅读进度，Koharia 会更贴近这个使用场景。

如果你只是想浏览公共在线源，或依赖传统扩展生态，Koharia 可能不是最合适的选择。这个项目的方向是 Komga 优先，而不是还原完整的上游 Mihon 使用方式。

## 主要功能

- 浏览已配置 Komga 服务器中的媒体库、系列、书籍和章节。
- 使用继承自 Mihon 的成熟 Android 阅读器界面。
- 与 Komga 同步阅读进度和阅读历史。
- 支持离线缓存和下载，便于在网络不稳定或无网络环境下阅读。
- 在服务器和文件格式支持时，优先使用 Komga 原始文件下载；不适合直接读取的内容会回退到页面缓存方式。
- 保留部分底层兼容能力，以便继续使用现有阅读器、数据库、备份和迁移基础设施。

## 下载

1. 打开 [GitHub Releases](https://github.com/Mister-album/Koharia/releases/latest)。
2. 在最新版本的 `Assets` 区域下载 APK 文件。

## 构建项目

本项目面向 Android 8.0 及以上系统。推荐使用 Android Studio 打开项目，也可以在 Windows PowerShell 中使用 Gradle 构建。

常用验证命令：

```powershell
.\gradlew.bat spotlessCheck
.\gradlew.bat :app:compileDebugKotlin
```

生成发布包：

```powershell
.\gradlew.bat :app:assembleRelease
```

发布签名会读取本地的 `keystore.properties`。如果你只是本地开发或调试，通常不需要配置发布签名。

## 项目来源与声明

Koharia 基于 [Mihon](https://github.com/mihonapp/mihon) 开发，并遵循 Apache License 2.0。许可证与署名信息见 [LICENSE](./LICENSE) 和 [NOTICE](./NOTICE)。

贡献相关说明见 [CONTRIBUTING.md](./CONTRIBUTING.md) 与 [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)。再分发或制作衍生版本时，请保留必要署名，并避免将其描述为 Mihon 或 Komga 的官方版本。

## 致谢

Koharia 建立在 Javier Tomas 最初完成的工作、Mihon 项目贡献者的持续投入，以及 Komga 社区提供的服务器生态之上。感谢所有继续改进这一衍生版本的贡献者。

## 支持

Koharia 是一个个人维护的开源项目。持续维护需要投入时间处理上游变更、Komga 适配、下载与同步问题、Android 版本兼容，以及日常测试和发布工作。

如果 Koharia 对你的阅读流程有帮助，欢迎通过爱发电支持项目。你的支持会直接帮助项目保持更新，并让我能更稳定地投入到修复问题、打磨体验和跟进 Komga 相关改进上。

- 爱发电：[https://ifdian.net/a/album-Koharia](https://ifdian.net/a/album-Koharia)

## 免责声明

Koharia 不提供、不托管、不索引任何内容。应用只负责连接你配置的 Komga 服务器并展示你有权访问的内容。请确保你对服务器中的内容拥有相应的使用权限，并遵守所在地法律法规。

## 许可证

Copyright (C) 2015 Javier Tomas

Copyright (C) Mihon contributors

Copyright (C) 2026 Koharia contributors

本项目基于 Apache License, Version 2.0 授权。详情见 [LICENSE](./LICENSE) 与 [NOTICE](./NOTICE)。
