# Komga 专属客户端改造实施路线图

## Summary

- 目标：基于当前 `Koharia` Android 客户端，做一个深度定制的 `Komga` 专属客户端。
- 方向调整：不以“复用或搬运 `extensions-source/src/all/komga` 扩展代码”为主线，而是以 **Komga 官方 API** 为主设计客户端适配层；扩展代码只作为接口映射、筛选语义、页面读取策略的参考样例。
- 首版能力要求：优先完成 **单服务器** Komga 客户端能力，包括 `Series / Read lists / Books`、动态筛选、`API Key / 用户名密码` 认证、章节/分页/阅读、阅读进度回写。多服务器适配不做首期目标，只作为后续增强阶段考虑。
- 核心结论：技术上可行，且比“直接把扩展塞进 App”更合理。最优路线是构建一个独立的 `Komga API Client + 领域适配层`，再把 Koharia 的阅读器、书库、下载、历史、同步链路挂接到这一层。

## Current State Analysis

### 1. Koharia 现有通用能力可以继续复用

- 来源注册中心在 `app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt`，目前来源由 `LocalSource` 与扩展来源组成。
- 扩展管理在 `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt`，它是“多来源生态”的核心，而不是 Komga 客户端的必需能力。
- 浏览、详情、章节同步、阅读器本身都是来源无关的通用层：
  - 浏览页：`app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModel.kt`
  - 分页：`data/src/main/java/tachiyomi/data/source/SourcePagingSource.kt`
  - 详情页：`app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`
  - 章节同步：`app/src/main/java/eu/kanade/domain/chapter/interactor/SyncChaptersWithSource.kt`
  - 阅读器：`app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
  - 在线页加载：`app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt`
- 结论：阅读器和书库链路不需要按 Komga 重写，只需要让 Komga 数据能以稳定的 Source/Domain 形式接入。

### 2. Komga 扩展当前实现“能用”，但不适合作为长期主架构

- 现有扩展主实现位于 `extensions-source/src/all/komga/src/eu/kanade/tachiyomi/extension/all/komga/Komga.kt`。
- 它已经覆盖：
  - 地址、API Key、Basic Auth
  - 多实例 Source
  - `Series / Read lists / Books`
  - 动态筛选
  - 章节与页面读取
  - 图片格式降级
- 但它的问题也很明确：
  - 实现完全围绕 `HttpSource` 生命周期写在一个类里，网络、DTO、过滤、设置、页面映射耦在一起
  - 主要使用的是旧式 `GET /api/v1/series`、`GET /api/v1/books` 风格
  - 根据 Komga 官方 API 文档，`GET /api/v1/series` 和 `GET /api/v1/books` 已被标记为 deprecated，推荐使用 `POST /api/v1/series/list`、`POST /api/v1/books/list`
- 结论：扩展代码适合作为“接口映射参考”和“已有业务语义参考”，不适合作为专属客户端的主实现骨架。

### 3. Komga 官方 API 已足够支撑专属客户端方案

- 官方 OpenAPI 文档显示，Komga 提供了稳定的 REST API 与 Swagger/OpenAPI 描述。
- 已确认的重要能力：
  - 认证：`Basic Auth`、`X-API-Key`
  - Session：`KOMGA-SESSION` Cookie 或 `X-Auth-Token`
  - 系列：`/api/v1/series/*`
  - 图书：`/api/v1/books/*`
  - 阅读列表：`/api/v1/readlists/*`
  - 库与筛选元数据：`/api/v1/libraries` 等
  - 阅读进度接口：Koharia 当前追踪实现已使用 `/api/v2/series/.../read-progress/tachiyomi`
- 这意味着专属客户端可以围绕官方 API 自行设计更适配的客户端层，而不必受扩展类结构牵制。

### 4. Koharia 已有 Komga 增强追踪，可继续复用，但需要解耦

- 当前实现位于 `app/src/main/java/eu/kanade/tachiyomi/data/track/komga/Komga.kt` 与 `KomgaApi.kt`。
- 现状优点：
  - 已实现阅读进度回写
  - 已实现自动匹配与刷新
- 现状问题：
  - `getAcceptedSources()` 写死为扩展类名 `eu.kanade.tachiyomi.extension.all.komga.Komga`
  - 跟踪逻辑默认前提是“Komga 由扩展提供”
- 结论：追踪能力可以保留，但要改成面向“Komga 领域来源”而不是“Komga 扩展类”。

### 5. 真正的改造重点是“接口驱动重构”而不是“扩展迁移”

- 如果继续沿用扩展代码作为主骨架，后续会一直受限于：
  - 扩展偏好设置模型
  - `HttpSource` 单类大实现
  - 已废弃接口调用
  - 多来源产品语义
- 如果改为 Komga API 驱动架构：
  - 网络层可独立升级
  - UI/Reader/Library 只依赖稳定领域模型
  - 后续适配 Komga API 版本变化更低风险

## Feasibility Assessment

### 可行性结论

- 技术可行性：高
- 复用率：高，但复用的重点是 Koharia 通用能力，不是直接复用 Komga 扩展类
- 实施复杂度：中偏高
- 长期收益：高

### 推荐技术路线

- 推荐：**API 优先 + 扩展参考**
- 不推荐：**直接把扩展类整体搬进主工程**

### 主要理由

- Komga 官方 API 已提供可直接建模的契约，适合构建专属客户端。
- 扩展代码虽然能快速跑通，但结构上更像“兼容 Tachiyomi Source 接口的适配脚本”，不是长期可维护的 App 内部模块。
- Koharia 的核心价值在阅读器、书库、章节同步、下载与 UI 基础设施，这些应继续复用。

### 主要风险

- 需要自行定义一套 Komga 领域模型与 API Client，前期设计工作比“搬扩展代码”多。
- Koharia 浏览链路天然基于 `Source` 抽象，若 API 层与 Source 层设计不好，会出现重复映射。
- 即使先做单服务器，后续如果补多服务器，仍需提前避免把认证和 Source 标识设计得过于写死。

## Proposed Changes

### Phase 1. 先建立 Komga API 契约层，而不是先搬扩展代码

#### 目标

- 以 Komga 官方 OpenAPI/Swagger 为主，建立稳定的 App 内部 API Client。

#### 涉及文件

- 新增 API 层，建议落在：
  - `app/src/main/java/mihon/komga/api/KomgaApiClient.kt`
  - `app/src/main/java/mihon/komga/api/KomgaAuthProvider.kt`
  - `app/src/main/java/mihon/komga/api/KomgaEndpoints.kt`
  - `app/src/main/java/mihon/komga/api/dto/*.kt`
- 参考文件：
  - `extensions-source/src/all/komga/src/eu/kanade/tachiyomi/extension/all/komga/Komga.kt`
  - `extensions-source/src/all/komga/src/eu/kanade/tachiyomi/extension/all/komga/dto/*.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/data/track/komga/KomgaApi.kt`

#### 怎么做

- 不直接照搬扩展里的请求拼接逻辑，而是先按官方 API 重新梳理端点分组：
  - 认证
  - Libraries
  - Series
  - Books
  - Readlists
  - Filters metadata
  - Read progress
- 优先采用官方未废弃接口，特别是列表类接口优先评估 `POST /list` 风格，而不是沿用旧 GET 查询串方案。
- 为 API 层建立独立 DTO，不让 `SManga / SChapter / Page` 直接侵入网络层。

#### 为什么

- 这是“少绕弯路”的关键。如果 API 契约层独立，后续 UI、阅读器、追踪、设置都能围绕稳定的数据模型开发。

### Phase 2. 建立 Komga 领域适配层，把官方 API 结果转成 App 可消费模型

#### 目标

- 在 API 层与 Koharia Source/UI 之间增加一层领域适配，隔离 Komga 服务端模型变化。

#### 涉及文件

- 新增领域层，建议落在：
  - `app/src/main/java/mihon/komga/domain/model/*.kt`
  - `app/src/main/java/mihon/komga/domain/repository/KomgaRepository.kt`
  - `app/src/main/java/mihon/komga/domain/interactor/*.kt`
  - `app/src/main/java/mihon/komga/domain/mapper/*.kt`

#### 怎么做

- 定义面向客户端的核心模型：
  - `KomgaConnection`
  - `KomgaSeries`
  - `KomgaBook`
  - `KomgaReadList`
  - `KomgaLibrary`
  - `KomgaPage`
  - `KomgaFilterOptions`
- 在 mapper 中完成：
  - `Komga DTO -> Komga Domain`
  - `Komga Domain -> SManga / SChapter / Page`
- 保留扩展代码中的有价值语义作为参考：
  - 章节命名模板
  - EPUB 可读性筛选
  - 不支持图片格式时 `?convert=png`
  - `Series / Read lists / Books` 三类对象的统一展示策略

#### 为什么

- 这样可以避免把 Komga 的服务端细节直接散落到 Koharia UI 与 Source 各层。

### Phase 3. 再实现 KomgaSource 适配层，把 Koharia 通用能力接上

#### 目标

- 让 Koharia 现有浏览、详情、章节同步、阅读器链路继续工作，但其底层数据来自新的 Komga 领域层。

#### 涉及文件

- 新增/改造 Source 适配层，建议落在：
  - `app/src/main/java/mihon/source/komga/KomgaSource.kt`
  - `app/src/main/java/mihon/source/komga/KomgaSourceFactory.kt`
  - `app/src/main/java/mihon/source/komga/KomgaFilterAdapter.kt`
- 注册点：
  - `app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt`

#### 怎么做

- `KomgaSource` 不再自己拼 API 请求，而是调用 `KomgaRepository`。
- `popular/latest/search/details/chapters/pages` 全部通过 repository 返回。
- `AndroidSourceManager` 直接注册内置 Komga Source；扩展仅作为旧体系存在，不再是产品主路径。

#### 为什么

- 这样可以让 Koharia 的通用浏览与阅读能力“复用到位”，同时不把架构拉回扩展实现方式。

### Phase 4. 重做 Komga 追踪与阅读进度同步，使其依赖领域能力而不是扩展类名

#### 目标

- 保留阅读进度回写与自动绑定体验，但解除对旧扩展类名的耦合。

#### 涉及文件

- `app/src/main/java/eu/kanade/tachiyomi/data/track/komga/Komga.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/track/komga/KomgaApi.kt`
- `app/src/main/java/eu/kanade/domain/track/interactor/AddTracks.kt`

#### 怎么做

- 修改 Tracker 的来源接受策略，改为接受新的内置 `KomgaSource` 或稳定 Source ID 前缀。
- 评估是否保留现有 `KomgaApi.kt`，或将其逻辑并入新的 `KomgaApiClient`，避免出现两套 Komga 网络实现。
- 统一阅读进度写回链路，避免 Source 侧与 Tracker 侧各自维护一套认证和 URL 拼接。

#### 为什么

- 对专属客户端来说，Komga API 调用应该只有一套真相来源。

### Phase 5. 把服务器与认证配置提升为 App 级能力

#### 目标

- 不再把 Komga 配置塞在“扩展偏好设置”里，而是变成专属客户端的核心设置。

#### 涉及文件

- 新增设置与存储层，建议落在：
  - `app/src/main/java/mihon/komga/settings/KomgaServerSettingsStore.kt`
  - `app/src/main/java/mihon/komga/settings/model/*.kt`
  - `app/src/main/java/eu/kanade/presentation/more/settings/screen/KomgaSettingsScreen.kt`
  - `app/src/main/java/eu/kanade/presentation/more/settings/screen/KomgaSettingsScreenModel.kt`

#### 怎么做

- 用 App 级存储管理：
  - 单服务器地址
  - 认证方式
  - 会话策略
  - 默认 Libraries
  - 章节命名模板
- 认证策略优先顺序建议为：
  - API Key
  - Basic Auth
  - 会话复用
- 设置结构保留未来扩展到多服务器的余地，但首版 UI 与数据存储只服务单服务器。
- 扩展设置仅作为迁移参考，不再作为最终宿主。

#### 为什么

- 专属客户端的核心入口不应是“某个来源设置页”，而应是应用自己的服务器管理能力。

### Phase 6. 裁剪扩展生态与多来源产品语义

#### 目标

- 让产品真正看起来是 Komga 客户端，而不是 Koharia 改壳。

#### 涉及文件

- 扩展与来源入口：
  - `app/src/main/java/eu/kanade/tachiyomi/extension/*`
  - `app/src/main/java/eu/kanade/tachiyomi/ui/browse/extension/*`
  - `app/src/main/java/eu/kanade/presentation/browse/ExtensionsScreen.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/*`
- 仓库与设置：
  - `app/src/main/java/eu/kanade/presentation/more/settings/screen/browse/*`
  - `domain/src/main/java/mihon/domain/extensionrepo/*`
  - `data/src/main/sqldelight/tachiyomi/data/extension_repos.sq`

#### 怎么做

- 先隐藏扩展页、扩展仓库页、来源选择页、来源语言设置。
- 再逐步删除不再被使用的扩展安装、仓库、信任、更新基础设施。
- 保留必要的 Source 抽象，但去掉多来源产品语义。

#### 为什么

- 这一步是产品收口，不是技术底座；必须排在 API 层与 Source 适配层稳定之后。

### Phase 7. 做 Komga 专属导航与首页重构

#### 目标

- 让 UI 按 Komga 内容模型组织，而不是按 Koharia 的“来源浏览器”组织。

#### 涉及文件

- 主导航与首页：
  - `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/ui/browse/BrowseTab.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesTab.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryTab.kt`
  - `app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt`
- 品牌资源：
  - `app/src/main/res/drawable/*`
  - `app/src/main/res/mipmap*/*`
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/java/eu/kanade/tachiyomi/AppInfo.kt`

#### 怎么做

- 首页入口建议改为：
  - Libraries
  - Series
  - Read lists
  - Continue Reading
  - Downloads / History / Settings
- 保留成熟能力：
  - Reader
  - Library
  - History
  - Downloads
- 品牌、文案、图标、`User-Agent` 统一改为专属客户端标识。

#### 为什么

- 用户要的是“更适配 Komga”，而不是“装了 Komga 扩展的 Koharia”。

## Assumptions & Decisions

- 决策：以 **Komga 官方 API** 为主进行客户端适配设计。
- 决策：`extensions-source` 中的 Komga 扩展只作为参考实现，不作为主骨架。
- 决策：首版保留 Komga 核心能力，但 **不做多服务器适配**；多服务器仅作为后续增强方向。
- 决策：Koharia 的阅读器、书库、下载、历史、章节同步继续复用。
- 决策：优先消除已废弃接口依赖，尽量基于 Komga 当前官方推荐接口建模。
- 假设：当前目标平台仍为 Android，短期不追求跨端。

## Verification Steps

### 阶段验收 1：API 契约层可独立工作

- 在不依赖 `HttpSource` 的情况下，`KomgaApiClient` 能完成：
  - 登录/认证
  - 获取 libraries
  - 获取 series/books/readlists 列表
  - 获取详情
  - 获取页面列表
- 列表类调用优先验证官方推荐接口是否能覆盖现有扩展需求。

### 阶段验收 2：Source 适配层打通 Koharia 主链路

- 应用启动后无需安装扩展即可看到 Komga 内置来源。
- `Popular / Latest / Search / Details / Chapters / Pages` 均由新的 repository 驱动。
- 阅读器能正常翻页，图片格式降级逻辑仍有效。

### 阶段验收 3：同步能力保留

- 收藏到书库后可自动绑定 Komga 跟踪。
- 阅读进度可写回 Komga。
- 刷新书库、同步章节、恢复阅读状态不报错。

### 阶段验收 4：产品收口完成

- 用户无需理解“扩展/来源安装”概念。
- 导航中不再出现扩展中心、扩展仓库、来源语言等无关入口。
- App 在品牌、文案、首页结构上体现为 Komga 专属客户端。

## Recommended Execution Order

1. 先做 `KomgaApiClient + DTO + Repository`，把官方 API 契约建稳。
2. 再做 `KomgaSource` 适配层，接入 Koharia 浏览/详情/阅读链路。
3. 然后统一 Komga 跟踪与进度同步逻辑，避免双套网络实现。
4. 再把服务器、认证与会话提升为 App 级设置，首版只支持单服务器。
5. 最后裁剪扩展生态并做专属导航、首页、品牌重构。

## Post-MVP Enhancements

- 多服务器管理
- 服务器切换与隔离书库策略
- 多连接下的 Source ID 稳定策略
- 单连接设置向多连接设置的迁移方案

## Final Recommendation

- 建议执行，但执行方式应从“迁移扩展”改成“API 驱动重构”。
- 最优方案不是把 `Komga.kt` 扩展类整体搬进主工程，而是：
  - 用 Komga 官方 API 定义客户端契约
  - 用扩展代码参考已有语义和边界条件
  - 用 Koharia 复用阅读器、书库、下载、同步与 UI 基础能力
- 这样更贴合你的目标：更适配 Komga，少走 Tachiyomi 扩展历史包袱的弯路。
