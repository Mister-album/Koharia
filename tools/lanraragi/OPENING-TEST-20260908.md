# Archive 入口与分页预览设备回归（2026-09-08）

## 环境与隔离

- Android Studio AVD：`Resizable_Experimental`，`emulator-5554`，Android 17 / x86_64 / 16 KB。
- 自动测试安装：`Koharia Auto Tests`，包名 `app.koharia.dev.devicefixture`，测试包 `.devicefixture.test`。两者均保留安装。
- 使用生成的三页测试书验证进度写入和离线行为；官方 Demo 仅执行读取与下载，不写入远端进度。
- 未操作已连接的实体设备。未安装、卸载、清除或修改手动应用及其连接。
- 手动包更新时间在测试前后保持一致：`app.koharia.dev` 为 `2026-09-08 13:30:31`；`app.koharia.foss` 为 `2026-09-06 12:12:35`。

## 验证结果

6 项设备测试通过（分轮执行）：

1. Archive 默认直接进入真实 ReaderActivity，确认图片 Ready 及实际页码；Tankoubon 进入 MangaScreen，显示 3 个 Archive 章节。
2. 切换为分页预览，确认三页图片按顺序展示；点击第 1、2、3 页均进入对应页，右下角开始按钮也可进入阅读器。预览期间服务端 progress 为 0、isnew 保持 true，本地页码不变且没有待上传记录。
3. 顶部分类可切换，并可返回全部。
4. More 页面观察连接时，添加第二个未配置服务器及无效地址均不崩溃。
5. 新旧协议完整目录、共享归档进度；下载后关闭测试服务仍可读取 CBZ。额外通过预览页实际使用的 Coil fetcher 并发解码两轮，禁用内存缓存，核对三页颜色及顺序，验证流与解码器释放。
6. 官方 Demo：读取目录、分类和 Tankoubon；实际阅读 7 页作品的第一页、第二页及末页；完成 7 页下载，关闭连接 API 后从本地文件读取并解码。日志中对 Demo 的 PUT/POST/PATCH/DELETE 请求数为 0。

第 5 项覆盖两个独立测试方法；第 1、2 项合并在一个 UI 测试方法中，因此合计 6 个测试方法。

同时通过 `spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin` 及 36 项 LANraragi/阅读进度定向单元测试。

## 测试发现与修复

- 返回预览页后，开始按钮虽然可见，但无障碍树缺少名称。为按钮设置随状态变化的“开始／继续”描述，按名称点击的设备测试通过。
- 官方 Demo 初次下载在 120 秒内未完成，延长至 300 秒后仍发生图片重试。定位到共用网络客户端的单次调用总时限为 2 分钟；宿主机获取同一张跨页图片，25 秒仅收到 153792 / 789652 字节，慢速响应会超过总时限。
- LANraragi 图片请求现使用独立客户端配置，取消整体调用时限，保留连接/读取空闲超时，并共享取消调度器。元数据客户端时限不变。
- 新增真实慢速 HTTP 响应单元测试，证明图片响应可超过元数据时限，元数据仍按时超时；修复后 Demo 完整下载和本地解码通过。公共 Demo 下载测试上限保留为 5 分钟，完整性断言不变。
- 修正 UI 自动化对过渡期间失效无障碍节点的等待，并等待阅读器销毁后再操作返回页面。

## 重跑与产物

使用 `tools/lanraragi/verify-device.ps1`，明确指定 `-Serial emulator-5554` 和测试类；官方 Demo 另加 `-OfficialDemo`。本地协议测试需要先启动 `fixture_server.py` 并设置仅针对该模拟器的 ADB reverse。

- `LanraragiArchiveOpeningDeviceTest`
- `LanraragiCategoryTabsDeviceTest`
- `LanraragiDraftConnectionDeviceTest`
- `LanraragiConnectionDeviceTest`
- `LanraragiOfficialDemoTest`

截图和各轮结果归档在本机 `.codex-work/lanraragi-preview/`。预览截图使用生成的红、绿、蓝三页；蓝色第三页为长图，网格保留完整画面。阅读器验证以页面就绪/显示状态为依据，不将隐身模式下受 FLAG_SECURE 保护的截图当作正文证据。
