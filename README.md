<div align="center">

<img src="./.github/assets/logo.png" alt="Koharia logo" title="Koharia logo" width="80"/>

# Koharia

面向 Komga 服务器内容浏览与阅读的独立第三方 Android 阅读器。

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-0877d2?labelColor=27303D)](./LICENSE)

</div>

## Koharia 是什么

Koharia 是基于 [Mihon](https://github.com/mihonapp/mihon) 的衍生作品，针对由 Komga 提供内容的书架、阅读与离线访问场景，适配为专用 Android 客户端。

Koharia 是独立的第三方项目。它与 Mihon 项目或 Komga 项目不存在隶属、背书或维护关系。文中提及上游名称仅用于说明软件来源与兼容性。

## 功能特性

- 在专用客户端流程中浏览已配置 Komga 服务器上的书架内容。
- 使用继承自 Mihon 代码库的 Android 阅读器阅读受支持的书籍与章节。
- 为离线使用缓存内容；在服务器与文件格式支持时，可直接下载原始文件。
- 当原始文件不适合当前阅读路径时，通过页面缓存下载方式保留阅读器的自动下载兼容性。
- 保留底层应用现有支持的追踪器集成能力。

## 构建

- 需要 Android 8.0 或更高版本。
- 可在 Android Studio 中打开项目，或使用 Gradle 在本地构建。
- 推荐验证命令：

```bash
./gradlew.bat :app:compileDebugKotlin
```

## 项目来源与署名

- 原始上游项目：[mihonapp/mihon](https://github.com/mihonapp/mihon)
- 上游许可证：Apache License 2.0
- 衍生作品署名与再分发说明：[NOTICE](./NOTICE)
- 贡献指南：[CONTRIBUTING.md](./CONTRIBUTING.md)
- 社区行为预期：[CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)

如果你再分发 Koharia 或创建自己的衍生版本，请保留许可证与署名说明，为修改过的文件保留醒目的变更提示，并避免将你的构建版本表述为 Mihon 或 Komga 的官方发布版本。

## 致谢

Koharia 建立在 Javier Tomas 为 Mihon 最初完成的工作，以及后续上游 Mihon 项目贡献者的持续投入之上，同时也感谢所有继续改进这一衍生版本的贡献者。

## 免责声明

Koharia 不托管也不提供任何内容。你能访问哪些内容，取决于所连接的 Komga 服务器及其管理员授予的权限。

## 许可证

Copyright © 2015 Javier Tomas
Copyright © Mihon contributors
Copyright © 2026 Koharia contributors

本项目基于 Apache License, Version 2.0 进行授权。详见 [LICENSE](./LICENSE) 与 [NOTICE](./NOTICE)。


## Support
- Afdian: [https://ifdian.net/a/album-Koharia](https://ifdian.net/a/album-Koharia)

