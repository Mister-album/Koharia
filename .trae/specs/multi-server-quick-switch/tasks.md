# Tasks

- [x] Task 1: 基础模型与偏好设置
  - [x] SubTask 1.1: 创建 `KomgaServerProfile` 数据类（包含 `id` 和 `name`）。
  - [x] SubTask 1.2: 创建 `KomgaServerPreferences` 类，使用 `PreferenceStore` 存储 `profiles` (Set<String> 序列化) 和 `activeServerId` (Long)。
  - [x] SubTask 1.3: 在 `AppModule` 中注入并初始化 `KomgaServerPreferences`。确保默认情况（空列表时）自动生成一个包含原始 `KomgaSource.ID` 的默认配置。

- [x] Task 2: 改造 Source 注册逻辑
  - [x] SubTask 2.1: 修改 `KomgaSource` 构造函数，支持传入 `id` 和 `customName`。重写 `name` 属性返回 `customName` 以确保下载目录隔离。
  - [x] SubTask 2.2: 修改 `AndroidSourceManager` 的 `init` 逻辑，从 `KomgaServerPreferences` 读取配置，动态生成并注册多个 `KomgaSource` 实例。
  - [x] SubTask 2.3: 在 `AndroidSourceManager` 中提供方法（或监听 Preferences 变更），以便在新增/删除服务器时动态更新 `sourcesMapFlow` 和存根源。

- [x] Task 3: 更多页面的服务器管理 UI
  - [x] SubTask 3.1: 创建 `KomgaServerProfilesScreen`，展示已保存的服务器列表。
  - [x] SubTask 3.2: 在该页面实现“添加服务器”弹窗逻辑（生成新的不重复 Long ID，保存至 Preferences 并通知 SourceManager 更新）。
  - [x] SubTask 3.3: 在该页面实现“删除服务器”逻辑（需确保至少保留一个服务器，且防误删提示）。点击某项时，跳转到现有的 `KomgaServerSettingsScreen`。
  - [x] SubTask 3.4: 修改 `MoreScreen` 和 `MoreTab`，将原“服务器设置”入口指向新的 `KomgaServerProfilesScreen`。

- [x] Task 4: 书架页的快速切换功能
  - [x] SubTask 4.1: 修改 `LibraryTab.kt`，使其收集 `KomgaServerPreferences.activeServerId` 的状态，并将该 ID 传递给 `KomgaLibraryScreen`。
  - [x] SubTask 4.2: 修改 `KomgaLibraryToolbar`，在右侧操作区（如过滤按钮旁）新增一个切换服务器的图标按钮。
  - [x] SubTask 4.3: 实现点击切换按钮后弹出的下拉菜单（DropdownMenu），展示所有服务器。点击列表项时更新 `activeServerId` 偏好，触发书架数据的无缝刷新。

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1] and [Task 2]
- [Task 4] depends on [Task 1]
