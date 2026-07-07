# 多服务器快速切换功能 Spec

## Why
目前 Koharia 仅支持配置单一的 Komga 服务器。对于同时拥有多个 Komga 服务器（例如本地局域网和远程服务器，或不同实例）的用户，若直接在现有设置中修改服务器地址，会导致原本的数据库缓存、下载记录和历史进度发生错乱（因为它们共享同一个 `Source ID`）。为了在保证数据安全隔离的前提下提供多服务器支持，我们需要引入多配置（Profile）系统，并巧妙利用底层原生的 `Source ID` 隔离机制。

## What Changes
- **新增多服务器偏好存储**：引入 `KomgaServerPreferences`，用于持久化存储多个服务器配置（Profile）以及当前激活的服务器 ID。
- **动态注册数据源**：`AndroidSourceManager` 将根据存储的配置列表，动态实例化多个 `KomgaSource`。每个实例拥有独立的 `Source ID` 和名称。
- **服务器管理 UI**：在“更多”页面中，将原有的单一“服务器设置”替换为“服务器管理”列表页，支持新增、删除、重命名和修改各自的连接参数。
- **书架快速切换**：在书架页（Library）顶部工具栏最右侧，新增一个服务器切换按钮，点击可快速切换当前查看的服务器书架。

## Impact
- **数据安全隔离**：通过为每个服务器分配独立的 `Source ID`，完美实现了数据库（漫画、章节、历史）和本地下载目录的物理隔离。
- **向后兼容**：现有的/首个服务器将强制保留原始的 `KomgaSource.ID`，确保老用户的本地数据和下载记录在升级后不会丢失。
- **Affected code**: 
  - `AndroidSourceManager.kt`
  - `KomgaSource.kt`
  - `LibraryTab.kt`
  - `KomgaLibraryToolbar.kt`
  - `MoreScreen.kt`

## ADDED Requirements
### Requirement: 服务器配置管理
系统应该允许用户添加、查看和删除多个 Komga 服务器配置。

#### Scenario: 添加新服务器
- **WHEN** 用户在“服务器管理”页面点击“添加”并输入名称
- **THEN** 系统生成一个新的、具有唯一 `Source ID` 的配置，并自动在后台注册该数据源，随后用户可为其配置 URL 和账号密码。

### Requirement: 书架快速切换
系统应允许用户在书架页快速在不同服务器间切换，而无需进入深层设置。

#### Scenario: 切换服务器
- **WHEN** 用户点击书架工具栏右侧的“切换”图标并选择另一个服务器
- **THEN** 系统更新当前激活的服务器 ID，书架内容立刻刷新为目标服务器的缓存和数据。

## MODIFIED Requirements
### Requirement: 下载目录隔离
原有的下载目录命名逻辑依赖于 `Source.name`。
- **Migration**: 为避免不同服务器的下载文件混淆，`KomgaSource` 必须根据其 Profile 的名称返回不同的 `name`（例如 "Komga - 本地"），从而使下载管理器自动将它们存入不同的文件夹。
