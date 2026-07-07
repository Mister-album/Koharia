# 多服务器配置调整实施计划

## Summary

本次调整是在现有“多服务器 + 快速切换”实现基础上的第二阶段收口，目标除了补齐“本地配置如何共享”“空服务器状态”“管理页切换入口”“下载目录共享策略”，还要优先修复项目里大量“默认 `KomgaSource.ID` 就是唯一 Komga 源”的残留路径，避免继续把多服务器实现建立在不一致的基础上。

本轮范围明确分成两类：

- 服务器层：允许删空、管理页切换当前服务器、添加后立即进入编辑页。
- 本地配置层：把除远端 Komga 配置、认证、远端数据外的本地配置统一纳入“共享 / 独立”作用域，并允许在服务器管理页修改。
- 一致性修复层：优先清理默认 `KomgaSource.ID` 唯一源假设，让多服务器入口、详情、阅读器、同步和追踪链路至少先达到一致可维护状态。

已确认的产品决策：

- “本地同一套配置”指除远端 Komga 配置、认证、默认库、远端数据同步状态外的整套本地配置；“书架、阅读器、下载设置”只是示例，不是范围上限。
- 删除所有服务器后进入空状态，不强制保留默认服务器。
- 服务器管理页使用“单选圆点切换当前服务器，点整行进入设置页”的交互。
- 下载目录共享只先做目录策略和设置入口；跨服务器“识别为同一文件已下载”必须等待 Komga 提供稳定文件标识后再做。
- “本地配置共用/独立”按全局一刀切处理，不做每服务器单独指定，也不做配置分组。

## Current State Analysis

### 已有多服务器实现

- [KomgaServerPreferences.kt](file:///E:/project/Koharia/app/src/main/java/koharia/source/komga/KomgaServerPreferences.kt) 当前只维护 `profiles` 和 `activeServerId`，并通过 `ensureDefaultProfile()` 强制至少保留一个 profile。
- [AndroidSourceManager.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt) 已能按 `profiles` 动态注册多个 `KomgaSource`。
- [KomgaServerProfilesScreen.kt](file:///E:/project/Koharia/app/src/main/java/koharia/source/komga/KomgaServerProfilesScreen.kt) 已支持新增/删除服务器、进入对应设置页，但：
  - 不允许删空；
  - 没有“当前服务器”单选切换；
  - 添加后不会跳到新建服务器的设置页；
  - 没有“本地配置共享模式 / 下载目录共享模式”的设置区。
- [LibraryTab.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt) 与 [KomgaLibraryToolbar.kt](file:///E:/project/Koharia/app/src/main/java/koharia/komga/ui/library/components/KomgaLibraryToolbar.kt) 已支持书架页顶部切换服务器。

### 当前“本地配置”实际仍是全局单份

- [PreferenceModule.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/di/PreferenceModule.kt) 目前把 `BasePreferences`、`SourcePreferences`、`LibraryPreferences`、`ReaderPreferences`、`DownloadPreferences` 都注入为基于同一个全局 `PreferenceStore` 的单例。
- 这些偏好类中的关键项目前都没有按服务器隔离：
  - [BasePreferences.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/domain/base/BasePreferences.kt)：`downloadedOnly`
  - [SourcePreferences.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/domain/source/service/SourcePreferences.kt)：`sourceDisplayMode`
  - [LibraryPreferences.kt](file:///E:/project/Koharia/domain/src/main/java/tachiyomi/domain/library/service/LibraryPreferences.kt)：书架列数、筛选、章节默认显示等
  - [ReaderPreferences.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt)：阅读器全套偏好
  - [DownloadPreferences.kt](file:///E:/project/Koharia/domain/src/main/java/tachiyomi/domain/download/service/DownloadPreferences.kt)：下载并发、自动下载、删除策略等

### 当前下载目录策略与共享目录风险

- [DownloadProvider.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadProvider.kt) 通过 `source.toString()` 生成源目录名，因此当前天然是“每服务器一个目录”。
- [DownloadCache.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt) 会把“源目录名”映射为单个 `sourceId`。如果多个服务器共用同一目录名，现有缓存模型会冲突，不能只改目录命名而不改缓存索引。
- [DownloadManager.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt) 的 `renameSource()` 当前默认认为“一个源对应一个物理目录”，共享目录模式下需要额外保护。

### 当前存在的多服务器高风险热点

项目里仍有大量“默认 `KomgaSource.ID` 就是唯一 Komga 源”的路径，这不是可以继续回避的小问题，而是本轮必须优先收口的主风险：

- [MoreTab.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt) 刷新用户信息时仍固定取默认 `KomgaSource.ID`
- [KomgaApi.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/data/track/komga/KomgaApi.kt) 的追踪请求仍固定依赖默认服务器
- [KomgaProgressSyncService.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/data/track/komga/KomgaProgressSyncService.kt) 仍大量使用默认 `KomgaSource.ID`
- [MangaScreenModel.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt)、[MangaScreen.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt)、[presentation MangaScreen.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt) 仍有默认 ID 分支

本轮计划要求把这些问题至少收敛到“核心 Komga 路径不再依赖默认 ID 才能工作”的水平，最低验收标准包括：

- 新增的“空服务器”状态不会触发崩溃；
- 服务器管理页、书架页、更多页信息、详情页、阅读器、Komga 同步/追踪入口都不再把默认 ID 当作唯一服务器；
- 新增的“本地配置共享模式”建立在 `activeServerId` 和配置作用域之上，而不是建立在默认 ID 假设之上。

## Proposed Changes

### 1. 改造服务器偏好模型，允许“初始化默认一次 + 之后可删空”

#### 文件

- [KomgaServerPreferences.kt](file:///E:/project/Koharia/app/src/main/java/koharia/source/komga/KomgaServerPreferences.kt)
- [AppModule.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt)

#### What

- 扩展 `KomgaServerPreferences`，新增以下全局偏好：
  - `localConfigMode`: `Shared` / `Separate`
  - `downloadDirectoryMode`: `PerServer` / `Shared`
  - `hasInitializedProfiles`: 是否已完成“一次性默认 profile 初始化”
- 将 `activeServerId` 从“永远有值”改为“允许无活动服务器”，用 `NO_ACTIVE_SERVER = -1L` 作为空状态哨兵值。
- `ensureDefaultProfile()` 改为 `ensureProfilesInitialized()`：
  - 仅当“从未初始化过 profiles”时补入默认服务器；
  - 如果用户之后主动删空，则不再自动重建默认服务器。

#### Why

- 这是“允许删掉所有服务器”和“保持老用户升级兼容”的共同前提。

#### How

- 首次升级或首次安装时，保留当前兼容行为：自动生成默认服务器（`KomgaSource.ID`）。
- 之后 `profiles` 可以为空；为空时同步把 `activeServerId` 置为 `NO_ACTIVE_SERVER`。
- `setProfiles()` 不再强行 `ifEmpty { defaultProfile() }`，而是允许空列表。
- `AppModule` 启动时只调用一次性初始化方法，不再用“永远保底一台服务器”的语义。

### 2. 引入“本地配置作用域”基础设施，覆盖除远端 Komga 配置/数据外的本地配置

#### 文件

- 新增 `app/src/main/java/koharia/source/komga/KomgaLocalConfigManager.kt`
- 新增 `core/common/src/main/kotlin/tachiyomi/core/common/preference/ScopedPreferenceStore.kt`
- 修改 [PreferenceModule.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/di/PreferenceModule.kt)
- 复用现有偏好类：
  - [BasePreferences.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/domain/base/BasePreferences.kt)
  - [SourcePreferences.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/domain/source/service/SourcePreferences.kt)
  - [LibraryPreferences.kt](file:///E:/project/Koharia/domain/src/main/java/tachiyomi/domain/library/service/LibraryPreferences.kt)
  - [ReaderPreferences.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt)
  - [DownloadPreferences.kt](file:///E:/project/Koharia/domain/src/main/java/tachiyomi/domain/download/service/DownloadPreferences.kt)
  - [NetworkPreferences.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/network/NetworkPreferences.kt)
  - [SecurityPreferences.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/core/security/SecurityPreferences.kt)
  - [PrivacyPreferences.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/core/security/PrivacyPreferences.kt)
  - [TrackPreferences.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/domain/track/service/TrackPreferences.kt)
  - [BackupPreferences.kt](file:///E:/project/Koharia/domain/src/main/java/tachiyomi/domain/backup/service/BackupPreferences.kt)
  - [StoragePreferences.kt](file:///E:/project/Koharia/domain/src/main/java/tachiyomi/domain/storage/service/StoragePreferences.kt)
  - [UiPreferences.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/domain/ui/UiPreferences.kt)

#### What

- 引入“按当前活动服务器动态解析 key 前缀”的 `ScopedPreferenceStore`。
- 让除“远端 Komga 配置 / 认证 / 默认库 / 远端数据”外的本地配置统一走“共享 / 独立”作用域。
- 作用域化范围按 `PreferenceModule` 中当前注入的本地偏好包装类来收敛，包含：
  - `BasePreferences`
  - `SourcePreferences`
  - `LibraryPreferences`
  - `ReaderPreferences`
  - `DownloadPreferences`
  - `NetworkPreferences`
  - `SecurityPreferences`
  - `PrivacyPreferences`
  - `TrackPreferences`
  - `BackupPreferences`
  - `StoragePreferences`
  - `UiPreferences`
- 不纳入作用域化的只有两类：
  - 每个 Komga source 自己的 `sourcePreferences()`，即地址、认证、API key、默认库、筛选持久化等服务器配置
  - 数据库、本地缓存文件、下载文件本体、远端同步结果等“数据”而非“配置”的内容

#### Why

- 用户的真实意图是“除远端 Komga 配置、数据外，其余本地配置文件都走共享 / 独立作用域”，不能只截取几个示例设置。
- 既然已经决定提供“共享 / 独立”模式，就应把本地配置语义做完整，避免用户切换后仍然出现一半共享、一半全局的混乱状态。

#### How

- `KomgaLocalConfigManager` 负责根据 `activeServerId + localConfigMode` 生成逻辑作用域键：
  - `shared` 模式：固定作用域 `komga_local_shared`
  - `separate` 模式：作用域 `komga_local_server_<activeServerId>`
- `ScopedPreferenceStore` 不新建第二套 SharedPreferences 文件，而是对原有 key 做前缀转换：
  - 例如 `pref_reader_flash` 变为 `komga_local_shared::pref_reader_flash`
  - 或 `komga_local_server_123::pref_reader_flash`
- `PreferenceModule` 中：
  - 继续保留一个原始全局 `PreferenceStore`
  - 新增一个“本地配置作用域”的 `PreferenceStore`
  - 除 Komga 服务器自身 `sourcePreferences()` 之外，其余本地偏好包装类统一改为从作用域 store 构造
- 迁移策略：
  - 现有安装默认视为 `Shared`，这样现有行为不变；
  - 第一次切到 `Separate` 时，立即为每个现有服务器创建一套独立作用域，并把当前 shared 作用域完整复制到每个服务器自己的作用域，形成可直接使用的初始独立配置；
  - 如果此时没有服务器，则仅切换模式，不生成虚假的服务器作用域；
  - 之后在 `Separate` 模式下新增服务器时，也要立刻为该服务器创建完整的独立配置快照；
  - 从 `Separate` 切回 `Shared` 时不做合并，只恢复读取 shared 作用域，单服务器作用域数据保留但休眠。

### 3. 服务器管理页新增“本地配置模式 + 下载目录模式”设置区

#### 文件

- [KomgaServerProfilesScreen.kt](file:///E:/project/Koharia/app/src/main/java/koharia/source/komga/KomgaServerProfilesScreen.kt)
- 可能补充文案到：
  - `i18n/src/commonMain/moko-resources/base/strings.xml`
  - `i18n/src/commonMain/moko-resources/zh-rCN/strings.xml`

#### What

- 在服务器管理页顶部新增一个设置区，至少包含：
  - 本地配置模式：共用 / 独立
  - 下载目录模式：按服务器分开 / 多服务器共用
- 该设置区是后续“有地方可以修改这个配置”的唯一入口，不再把这个决策藏在添加弹窗里。

#### Why

- 用户要求“添加第二个服务器时提示选择”，同时要求“之后也有地方可以修改这个配置”。
- 放在服务器管理页最符合语义，也不需要新增独立设置页。

#### How

- 服务器管理页结构调整为：
  - 顶部说明卡片/设置卡片
  - 服务器列表
  - 空状态视图（当列表为空时）
- 添加第二台服务器时，如果当前只有 1 台服务器：
  - 先弹出“本地配置模式选择”对话框
  - 保存选择后再真正创建第二台服务器
  - 若用户选择 `Separate`，则在创建第二台服务器前先完成“为现有每台服务器创建独立配置”的迁移，再继续新增和跳转
- 如果当前已是 2 台及以上服务器，则新增服务器时不再重复询问，直接沿用现有全局模式

### 4. 服务器管理页支持“切换当前服务器 + 点击行进入设置”

#### 文件

- [KomgaServerProfilesScreen.kt](file:///E:/project/Koharia/app/src/main/java/koharia/source/komga/KomgaServerProfilesScreen.kt)
- [MoreTab.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt)

#### What

- 列表每行加入单选圆点，单击圆点切换当前服务器。
- 保持“点击整行进入该服务器设置页”的行为不变。
- 切换成功后，书架页、更多页用户信息等都以新的 `activeServerId` 为准。

#### Why

- 这是你明确选择的交互方式，且与书架页顶部切换形成互补。

#### How

- `KomgaServerProfilesScreen` 中：
  - 行组件拆成“单选控件区域 + 文本区域 + 删除控件”
  - 单选控件调用 `activeServerId.set(profile.id)`
- `MoreTab.refreshUser()` 不再固定读取 `KomgaSource.ID`，改为读取当前活动服务器：
  - `activeServerId == NO_ACTIVE_SERVER` 时直接显示 `null`
  - 有活动服务器时从 `sourceManager.get(activeServerId)` 取 `KomgaSource`

### 5. 添加服务器后立即进入该服务器设置页

#### 文件

- [KomgaServerProfilesScreen.kt](file:///E:/project/Koharia/app/src/main/java/koharia/source/komga/KomgaServerProfilesScreen.kt)
- [KomgaServerSettingsScreen.kt](file:///E:/project/Koharia/app/src/main/java/koharia/source/komga/KomgaServerSettingsScreen.kt)

#### What

- 新服务器创建成功后，立即 `push` 到对应的 `KomgaServerSettingsScreen(sourceId = newId, titleOverride = profile.name)`。

#### Why

- 新建服务器的后续动作几乎必然是立刻填写地址/认证，直接跳转能减少错误配置和二次点击。

#### How

- `AddServerDialog` 的确认回调先创建 `profile`，然后先保存，再导航。
- 如果创建第二台服务器时需要先询问本地配置模式，则在模式确认后完成同样流程。

### 6. 允许删除所有服务器，并落到明确空状态

#### 文件

- [KomgaServerProfilesScreen.kt](file:///E:/project/Koharia/app/src/main/java/koharia/source/komga/KomgaServerProfilesScreen.kt)
- [AndroidSourceManager.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt)
- [LibraryTab.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryTab.kt)
- [MoreTab.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt)

#### What

- 删除最后一台服务器合法。
- 删空后进入空状态，而不是自动生成默认服务器。

#### Why

- 这是本次明确需求，同时也是多服务器管理行为闭环必须补齐的场景。

#### How

- `KomgaServerProfilesScreen` 删除逻辑移除“至少保留一台”的限制。
- 删除当前活动服务器时：
  - 若删除后还有剩余服务器，则切到列表中的第一台
  - 若删除后没有服务器，则把 `activeServerId` 设为 `NO_ACTIVE_SERVER`
- `AndroidSourceManager` 支持 `profiles = emptyList()`，此时 `sourcesMapFlow` 中不再注册 Komga source。
- `LibraryTab` 在 `NO_ACTIVE_SERVER` 时不实例化 `KomgaLibraryScreen`，改为显示一个空状态屏：
  - 文案说明“暂无服务器”
  - CTA 跳到服务器管理页
- `MoreTab` 用户信息为空时不报错，保持“更多”页其余设置可访问。

### 7. 下载目录共享只做“目录策略”，不做跨服务器已下载合并识别

#### 文件

- [KomgaServerPreferences.kt](file:///E:/project/Koharia/app/src/main/java/koharia/source/komga/KomgaServerPreferences.kt)
- [DownloadProvider.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadProvider.kt)
- [DownloadCache.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt)
- [DownloadManager.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt)

#### What

- 新增全局 `downloadDirectoryMode`：
  - `PerServer`：维持当前行为
  - `Shared`：多个 Komga 服务器共用同一个源级目录
- 本轮不实现“跨服务器自动识别为同一文件已下载”，因为当前没有可安全依赖的 Komga 稳定文件标识。

#### Why

- 用户要求入口先具备可配置能力，但也明确选择“等待 Komga 标识”，不接受本地弱匹配误判。

#### How

- `DownloadProvider.getSourceDirName(source)` 改为：
  - 非 Komga：保持原逻辑
  - Komga 且 `PerServer`：保持按 `source.toString()`
  - Komga 且 `Shared`：返回固定共享目录名（例如 `Komga`）
- `DownloadCache` 不能再假定“一个目录名只对应一个 `sourceId`”：
  - 根目录扫描层改为先按“目录名”建立物理目录缓存
  - 再把多个 Komga `sourceId` 映射到同一个物理目录对象
- `DownloadManager.renameSource()` 在 Komga 共享目录模式下禁止重命名共享目录，避免一个服务器改名影响整个共享目录
- 由于不做跨服务器同文件识别：
  - 共享目录只改变物理存放位置
  - `getDownloadCount()` 和 `findChapterDir()` 仍只按当前服务器自己的章节 URL/文件命名规则判断是否已下载

### 8. 优先修复默认 `KomgaSource.ID` 唯一源假设

#### 文件热点

- [MoreTab.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/more/MoreTab.kt)
- [KomgaApi.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/data/track/komga/KomgaApi.kt)
- [KomgaProgressSyncService.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/data/track/komga/KomgaProgressSyncService.kt)
- [SetMangaViewerFlags.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/domain/manga/interactor/SetMangaViewerFlags.kt)
- [MangaScreenModel.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt)
- [MangaScreen.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt)
- [presentation MangaScreen.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt)
- [HistoryScreenModel.kt](file:///E:/project/Koharia/app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryScreenModel.kt)
- [KomgaSseClient.kt](file:///E:/project/Koharia/app/src/main/java/koharia/komga/api/KomgaSseClient.kt)

#### What

- 把“默认 `KomgaSource.ID` 就是唯一 Komga 源”的写死逻辑，优先替换为以下几类真实语义：
  - 当前活动服务器：使用 `activeServerId`
  - 当前作品所属服务器：使用 `manga.source` 或当前 `source.id`
  - 任意 Komga 源判断：使用 `source is KomgaSource`
  - 当前服务器偏好：使用对应 `sourceId` 的 `sourcePreferences()`

#### Why

- 如果不先清理这些路径，多服务器功能会继续建立在“入口支持切换，但核心链路仍只认默认服务器”的半成品状态上，后续只会越来越难维护。
- 这是避免留下烂摊子的优先事项，不应该继续放到后续。

#### How

- **第一优先级**：修复直接影响当前体验的入口
  - `MoreTab` 用户信息获取
  - `MangaScreenModel` / `MangaScreen` / `presentation MangaScreen` 的 Komga 特化判断
  - `SetMangaViewerFlags` 的阅读器偏好回写
- **第二优先级**：修复同步与追踪链路
  - `KomgaApi` 改为接收明确的 `sourceId` 或从调用侧传入当前 Komga 源，而不是内部固定取默认 ID
  - `KomgaProgressSyncService` 改为基于当前 manga/source 或事件来源映射到实际服务器
  - `KomgaSseClient` 改为跟随当前活动服务器，或明确只监听活动服务器并在切换时重连
- **第三优先级**：修复剩余 UI/流程判断
  - `HistoryScreenModel`、reader/manga 相关“默认 ID 才是 Komga”分支全部替换为真实语义判断

### 9. 本轮不扩展的范围

#### What / Why

- 本轮仍然不做两类扩展：
  - 不实现跨服务器“同一文件已下载”的自动识别
  - 不改动数据库、本地缓存文件、下载文件本体的存储模型本身
- 但“默认 `KomgaSource.ID` 唯一源假设”不再属于可延期项，本轮要优先处理。

#### How

- 计划内聚焦配置作用域、服务器管理、活动服务器切换、默认 ID 一致性清理和下载目录模式。
- 数据库存储结构、下载文件去重算法、Komga 文件唯一标识对接留到后续专项。

## Assumptions & Decisions

- 继续保留“首次初始化默认服务器”的兼容行为，但这是一次性初始化，不再是永久保底。
- “本地配置模式”默认保持 `Shared`，因为当前线上行为本就是共享；这样升级后不会突变。
- “下载目录模式”默认保持 `PerServer`，因为当前线上行为本就是分目录；这样不会改变现有下载目录结构。
- “本地配置模式”的选择入口只在“从 1 台增加到 2 台”时强提醒一次，后续都在服务器管理页修改。
- 本轮“本地配置”覆盖范围是除 Komga 远端配置和数据外的整套本地配置包装类，而不是只覆盖几个示例设置。
- Komga 地址、认证、默认库、筛选持久化等仍然按每个 `sourceId` 自己的 `sourcePreferences()` 存储，不纳入共享。
- 从 `Shared` 切到 `Separate` 时，必须立即为每个现有服务器创建独立配置作用域并写入初始配置，不能等到用户第一次进入该服务器时再懒创建。
- 共享下载目录不代表跨服务器自动复用“已下载状态”；这部分等待 Komga 稳定文件标识后单独做。

## Verification Steps

### 编译与静态检查

- 运行 `.\gradlew.bat spotlessApply`
- 运行 `.\gradlew.bat spotlessCheck`
- 运行 `.\gradlew.bat :app:compileDebugKotlin`
- 对以下改动文件执行诊断检查：
  - `KomgaServerPreferences.kt`
  - `KomgaLocalConfigManager.kt`
  - `ScopedPreferenceStore.kt`
  - `PreferenceModule.kt`
  - `KomgaServerProfilesScreen.kt`
  - `LibraryTab.kt`
  - `MoreTab.kt`
  - `KomgaApi.kt`
  - `KomgaProgressSyncService.kt`
  - `SetMangaViewerFlags.kt`
  - `MangaScreenModel.kt`
  - `MangaScreen.kt`
  - `DownloadProvider.kt`
  - `DownloadCache.kt`

### 手动回归场景

- 初次升级到新版本时，已有默认服务器仍保留，原服务器设置与下载目录不丢失
- 从 1 台服务器新增第 2 台服务器时，出现“本地配置共用/独立”选择提示
- 新增服务器后立即跳转到对应设置页
- 服务器管理页可通过单选圆点切换当前服务器，点击整行仍进入设置页
- 删除非最后一台服务器后，活动服务器切换行为正确
- 删除最后一台服务器后：
  - 服务器管理页显示空状态
  - 书架页显示空状态，不崩溃
  - 更多页用户信息为空但页面仍可正常使用
- 从 `Shared` 切到 `Separate` 后，现有每台服务器都立即拥有一套已创建完成的独立本地配置，不会出现首进某台服务器时再补建、或读取空默认值的情况
- 在 `Shared` 模式修改任意本地配置后，切换服务器仍看到同一套本地配置
- 在 `Separate` 模式下，不同服务器的本地配置互不影响，范围覆盖除 Komga 远端配置/数据外的本地配置包装类
- 下载目录在 `PerServer` 与 `Shared` 两种模式下都能正常下载，不出现目录重命名异常
- `Shared` 下载目录模式下，不做跨服务器同文件自动识别；已下载判断保持当前服务器范围内逻辑
- 详情页、阅读器、更多页、同步/追踪入口在多服务器场景下不再依赖默认 `KomgaSource.ID` 才能正确工作
