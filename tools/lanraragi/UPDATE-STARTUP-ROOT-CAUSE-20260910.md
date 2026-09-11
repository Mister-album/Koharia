# 当前运行开发版：更新后首次空书库定位（2026-09-10）

## 现场证据

只读检查 Android Studio 的 Resizable_Experimental（emulator-5554），前台为 app.koharia.dev / MainActivity，PID 27706。未对该包执行安装、重启、刷新、清数据或修改连接。其他已连接设备未操作。

- 现象：顶部分类正常，作品区显示没有结果。
- 当前连接 ID 10。持久化目录的活动批次只有 4 条 CATEGORY，0 条 ARCHIVE；阅读状态和通用漫画记录仍在。
- 通过当前配置只读请求服务器，/api/archives 返回 5 个作品，/api/tankoubons 返回 0 个合集，服务器版本 0.9.81。
- 启动日志 12:53:43–12:53:46 显示 6 轮交错的目录请求。六次 /api/archives 均返回 HTTP 200 和非空响应（日志中的 Content-Length 为 1927），不是空数组。
- 现场数据库版本为 19，分类已持久化，故本次并非单纯 UI 没订阅缓存，也不能归因于服务器没有内容或鉴权失败。

日志仅保存脱敏副本；凭据相关行和 URL 已移除。数据库副本只在临时目录内读取统计，未保留原始数据。

## 根因

DownloadProvider.migrateLegacyKomgaDirectories() 和 storageAdapter() 会通过 ConnectionRegistry.createSource() 创建用于目录查询的临时来源。此前 LanraragiSource 构造函数只检查 profile 是否存在于连接列表，临时实例也因此启动网络监听和自动目录同步。

多个实例拥有各自的 catalogMutex，但按同一个 connectionId 暂存和发布目录：

1. A、B 分别暂存自己的 archive 批次。
2. A 完成发布，removeOtherEntries 删除同一连接的所有其他批次，包括 B 尚未发布的 archives。
3. B 继续暂存 categories 并发布，只剩分类。

用仓库的实际 SQL 在内存数据库交错执行后，最终结果同样为只有 CATEGORY。这与当前运行应用的数据库现象一致。更新后的下载目录迁移/扫描扩大了该并发窗口；正常菜单刷新通常只有正式实例执行，所以又能恢复内容。

此前冷启动验证没有覆盖“临时下载目录 Source 与正式 Source 同时同步”的更新路径，因此之前的刷新按钮、生命周期和网络监听修复未消除本次根因。

## 修复

- 新增 ConnectionManagedLifecycle 注册钩子，由 AndroidSourceManager 在来源进入正式映射后调用。
- LANraragi 构造函数不再启动任务；临时目录/元数据实例保持无后台同步副作用，正式注册只激活一次。
- LanraragiCatalogSyncCoordinator 按 connectionId 提供跨实例共享锁，覆盖整个暂存、发布及清理过程；不同连接仍可并行。
- 正式实例首次启动尝试一次刷新（如已发起刷新则合并），避免把刚被覆盖为仅分类的缓存当作新鲜完整目录而跳过。失败仍保留已有目录。
- 补充不含地址或凭据的目录批次开始/发布日志，便于后续核对实例和批次行为。

## 验证与边界

- spotlessApply、spotlessCheck、:app:compileDebugKotlin 通过。
- 61 项 LANraragi 和连接架构定向单元测试通过。
- LanraragiTemporarySourceDeviceTest 通过：6 个临时来源仅访问下载目录信息时不产生同步；显式并发刷新后完整保留 5 个 archives；模拟刚写入的仅分类目录后，首次正式注册自动恢复全部作品。
- 设备测试只使用 app.koharia.dev.devicefixture（Koharia Auto Tests），APK 与既有数据保留。
- 当前运行的手动开发版保持原现场；修复代码尚未安装到该包。更新到修复版后首次启动会尝试重建受影响目录。

脱敏日志、现场截图及验证输出位于本机 .codex-work/current-startup/。
