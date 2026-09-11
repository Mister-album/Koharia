# LANraragi 接入与验证

## 已实现

- 新增内置 `lanraragi` Provider，通过连接注册表、首次启动向导和连接管理页接入。
- 每个连接独立保存地址与 API Key；支持反向代理子路径。API Key 使用私有偏好键，遵循现有敏感设置备份开关。
- 兼容 0.9.70 的 `include_full_data` 合集接口与 0.9.80 的 `/full` 接口，成员列表完整分页。
- 完整离线元数据索引，包括归档、合集、有序成员、静态分类和动态分类的匹配快照。归档流式解析并分批暂存，成功后整批发布，失败保留旧索引。
- 书库支持合集分组、标题和标签搜索、分类、新加入、阅读状态、时间排序及随机浏览；服务端高级搜索明确标记为联网功能。
- 整个 archive 对应一章；合集按成员顺序展开，检测嵌套循环，缺失成员标记不可用。
- 在线图片阅读与现有下载队列、图片目录/CBZ 离线阅读共用。LANraragi 下载保留原始页边界，避免全局长图文件拆分改变服务端页码；阅读器的显示方式设置不受此限制。
- 真实显示页面产生本地阅读事件；按连接和 archive 合并持久化待同步进度，联网后比较实际阅读时间再同步，同一 archive 的多个章节映射共享状态。
- 标记已读同步最后一页；标记未读仅保存在本地，并保留覆盖标记直到再次实际阅读。
- 备份恢复期间暂停连接进度回写，只清理正在恢复的章节对应的待同步状态；恢复的未读覆盖从现有章节 memo 重建。
- 移除连接清理其任务、索引和凭据，保留手动下载与本地历史。

首版不包括服务器内容管理、ToC 拆章、原始压缩包直接下载、跨条目下载文件去重，以及新版 Tankoubon 整体页码同步。

## 数据与边界

迁移 `17.sqm` 新增 `lanraragi_catalog`、`lanraragi_members`、`lanraragi_sync` 和 `lanraragi_read_state`。
同时补齐已有数据库修复桥的表定义，兼容旧数据库升级和辅助表修复。

持久化资源 URL 为 `/lanraragi/{connectionId}/{archive|tank}/{resourceId}`，不包含地址或凭据。
全文目录是可重建索引，通用 Manga/Chapter 只在打开、下载或导入历史时创建。
索引首次连接时同步，进入书库后超过 24 小时更新，也可手动刷新。
离线索引包含元数据，不代表已经下载正文；封面按访问缓存。

为了处理同一 archive 在多个合集中的映射，新增按章节 URL 和来源 ID 查询所有章节的 repository 方法。
Source API 本身保持兼容；新增能力均位于 Koharia 的连接接口层。

设备验证还暴露并修复了已有 `ArchiveInputStream` 的读取契约问题：
`ByteBuffer.clear()` 会丢失 `read(byte[], offset, length)` 指定的窗口。
现在使用切片限制读取范围，正确处理零长度读取，并串行化 native 读取与关闭。
修复由真实 ZIP 流测试和下载 CBZ 后离线解码测试覆盖。

## 自动检查

在项目根目录运行：

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck
.\gradlew.bat :data:generateDebugDatabaseInterface :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest :domain:test
python tools/lanraragi/verify_sqlite.py
```

LANraragi 单元测试位于 `app/src/test/java/koharia/lanraragi/`，覆盖鉴权、子路径、两个版本的完整分页、流式解析、空结果、离线筛选、循环合集、页码与时间冲突、失败重试及未读覆盖。
连接恢复暂停逻辑另有独立测试。

`verify_sqlite.py` 直接执行项目中的 SQLDelight 查询与迁移，用临时内存数据库验证索引发布、失败保留、连接隔离、进度版本及迁移一致性。

## 虚拟机联调

项目默认保留设备测试安装的应用和测试 APK，不在结束或失败时卸载，也不通过卸载来解决签名冲突。
同包名 APK 仍会更新安装；已有数据应保留。测试仅移除自己创建的连接、文件和临时设置，不执行 `pm clear`。
应用设备测试必须使用 `-PdeviceTestFixture=true`，包名为 `app.koharia.dev.devicefixture`，桌面名称为 `Koharia Auto Tests`。
Gradle 执行入口和测试 Runner 都会拒绝在手动调试包中运行；保留 APK 并不能防止测试修改同一包里的连接与书库数据。

优先使用强制隔离且必须指定设备的入口：

```powershell
.\tools\lanraragi\verify-device.ps1 -Serial emulator-5554 -TestClass koharia.lanraragi.LanraragiDraftConnectionDeviceTest
```

该脚本不提供手动包作为测试目标的选项，不卸载或清数据。需要真实 Demo 时额外显式传入 `-OfficialDemo` 和对应测试类。

`fixture_server.py` 是协议测试服务，使用生成的图片和公开测试凭据 `fixture-key`，不需要真实书库。
包含 0.9.70 / 0.9.80 两套接口、嵌套合集、动态分类、长图和可切换的离线状态。
它验证客户端与协议的集成，不能代替真实 LANraragi 实例的部署验收。

1. 启动测试服务，记下打印的端口：

   ```powershell
   python tools/lanraragi/fixture_server.py --port 0
   ```

2. 将虚拟机的固定测试端口映射到该端口；将下面的 `HOST_PORT` 替换为实际端口：

   ```powershell
   adb -s emulator-5554 reverse tcp:38709 tcp:HOST_PORT
   ```

3. 定向运行虚拟机测试，避免操作其他已连接设备：

   ```powershell
   $previousSerial = $env:ANDROID_SERIAL
   try {
       $env:ANDROID_SERIAL = 'emulator-5554'
       .\gradlew.bat :data:connectedDebugAndroidTest
       .\gradlew.bat -PdeviceTestFixture=true :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=koharia.lanraragi.ArchiveStreamDeviceTest,koharia.lanraragi.LanraragiConnectionDeviceTest'
   } finally {
       $env:ANDROID_SERIAL = $previousSerial
   }
   ```

4. 下载测试要求队列为空，使用独立测试目录，临时适配虚拟机的非 Wi-Fi 网络，并在结束时恢复设置、移除测试连接和测试文件。
   书库截图保存在虚拟机 `/data/local/tmp/lanraragi-test-library.png`，可用 ADB 拉取。

## 本次验证记录（2026-09-08）

- 格式检查、SQLDelight 生成、Debug Kotlin 编译通过。
- app 与 domain 全部单元测试通过。
- 宿主 SQLite 检查：6 项通过。
- Android 17 / 16 KB 页大小虚拟机：14 项数据层测试通过。
- 同一虚拟机：3 项应用集成测试通过，包含两个 API 版本的目录与共享进度、备份恢复写入抑制、下载后断开服务仍可读取 CBZ、长图页数一致性、ZIP 流偏移/长度读取契约。
- 已检查实际书库截图：连接标题、查询输入、分组与下载筛选、快照时间及封面网格正常显示。
- 已补充官方 Demo 0.9.81 的真实接口模拟器验证，详见 [Demo 测试记录](DEMO-TEST-20260908.md)。私有实例上的写入权限、代理部署和大书库性能仍需按下列步骤验收。

## 真实实例验收

- 分别连接 0.9.70 和 0.9.80；验证错误 Key、No-Fun Mode、关闭/开启服务端进度、HTTP/HTTPS 与子路径。
- 同步包含多页合集和动态分类的书库，断网重启后搜索之前未浏览的条目；正文未下载时应提示不可用。
- 从独立归档和不同合集阅读同一本书，检查双方页码一致；离线阅读后重连，确认较新的进度获胜。
- 检查首尾页、双页和拆页显示；隐身阅读、预加载和下载本身不回写进度。
- 修改连接名称或地址、恢复备份、移除连接，确认条目身份稳定且已有手动下载保留。

参考：[Keiyoushi LANraragi](https://github.com/keiyoushi/extensions-source/tree/main/src/all/lanraragi)、[LANraragi 0.9.70 API](https://github.com/Difegue/LANraragi/blob/v.0.9.70/tools/openapi.yaml)、[LANraragi 0.9.80 API](https://github.com/Difegue/LANraragi/blob/v.0.9.80/tools/openapi.yaml)。

## Archive 打开模式与分页预览验收

- 默认在书架点击 Archive 直接进入阅读器；再次打开从本地保存的位置继续。Tankoubon 仍打开包含多个 Archive 的详情，Category 仍在顶部分类栏。
- 每个 LANraragi 连接可在服务器设置中的“Archive 作品打开方式”选择“详情与分页预览”。长按 Archive 也可打开预览，不必更改默认模式。
- 预览页上方为封面、元数据、标签和笔记，下方按阅读器页序显示所有页的网格。图片按可见范围加载，并发上限为 2；仅浏览预览不写阅读历史或远端进度。
- 点击第 1 页、中间页和最后一页，应分别进入对应页；右下角“开始／继续”使用正常续读位置。返回后按钮应反映最新阅读状态。
- 通过顶部下载按钮下载完成后，断网打开预览并点击任意页，确认图片目录与 CBZ 均可读。未下载图片只可使用已有临时缓存，不能显示为已下载。
- 两个连接分别选择不同模式，重启后确认互不影响；切换分类后以上入口行为不变。

已在 `Resizable_Experimental`（`emulator-5554`，Android 17）执行入口、预览、分类、第二连接和离线下载回归，5 项设备测试全部通过。使用独立 `app.koharia.dev.devicefixture` 测试包并保留安装，不操作手动安装包和用户连接。详见 [入口与预览测试记录](OPENING-TEST-20260908.md)。

## 2026-09-09 浏览与首图性能更新

筛选、自动联网搜索、本地缓存刷新以及首图传输计时的改动和验证见 [浏览与性能测试记录](BROWSE-TEST-20260909.md)。书架不再显示快照行，旧验证记录中的截图布局仅代表当时版本。

## 2026-09-10 添加与编辑页

LANraragi 的设置列表、底部保存、未保存修改提示及默认书架设置已统一，详见 [设置页测试记录](SETTINGS-TEST-20260910.md)。

## 2026-09-10 启动与空页刷新

统一空页、下拉和菜单刷新入口，补齐首次加载与前台刷新，并共享网络监听。冷启动与空页重试验证见 [启动测试记录](STARTUP-TEST-20260910.md)。

## Review 修复

六项审阅意见的触发条件、处置与回归结果见 [Review 复核与修复](REVIEW-FIXES-20260910.md)。首次页面待比对标记使用新增迁移 `18.sqm` 持久化。

## 更新后首次仅显示分类的现场定位

当前运行开发版抓取到了临时下载目录来源并行同步、相互删除暂存批次的问题，根因及修复见 [更新启动现场报告](UPDATE-STARTUP-ROOT-CAUSE-20260910.md)。该报告补充了此前冷启动测试未覆盖的更新路径。
