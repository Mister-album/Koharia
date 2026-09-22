# smanga 专用实例联调记录

日期：2026-09-22。目标为用户提供的局域网专用实例；凭据不记录在报告或测试代码中。

## 环境与范围

- Android 目标：`emulator-5554`，隔离包 `app.koharia.dev.devicefixture`，使用 `-PdeviceTestFixture=true` 构建。
- 使用专用实例提供的管理账户；实际返回 1 个媒体库、11 部漫画、14 个章节，其中 ZIP 6 个、PDF 8 个。
- 未核验部署对应的后端 commit，不能据此宣称所有 4.3 镜像均兼容。
- 使用 `adb install -r` 和显式 instrumentation runner；没有卸载、清除应用数据或操作已连接的实体设备。

## 已通过的协议与缓存检查

`SmangaLiveProtocolTest` 的 3 个测试通过，包括：

- 正确登录、错误密码拒绝、未认证 OPDS 请求拒绝。
- 当前媒体库内的分页、标题搜索、排序、漫画与章节范围校验，以及代表性漫画图片的解码。
- 真实响应写入 SQLDelight 后关闭客户端，新建 catalogue 从非空缓存和成功空缓存读取；显式刷新失败仍保留原结果与 generation。

这些检查验证的是应用内部协议和 Repository 行为。连接设置表单的全部手动操作、服务器重启和系统级断网切换未包含在这三个测试中。

## 原生阅读与下载结果

`SmangaLiveReaderTest` 最终通过，运行约 486 秒。两类样本均通过下列操作：原生阅读器显示首页、中间页和末页；按实际显示页回写精确页码与总页数；末页完成状态；阅读历史；标记未读后再次标记已读；完整手动下载。

| 样本 | 页数 | 手动下载耗时 | 离线验证 |
| --- | --- | --- | --- |
| ZIP 漫画章节 | 106 | 369.496 秒 | 106 页下载清单正确，图片可解码 |
| PDF 原文件 | 20 个物理页 | 28.510 秒 | PdfPageLoader 页数一致，页面可渲染 |

离线验证关闭了该测试连接的 API 客户端，再直接使用现有 `DownloadPageLoader` 打开下载内容。PDF 首次阅读产生的完整原文件缓存，在执行手动下载前也验证为可读取且不计入“已下载”。这证明这些离线加载路径不需要网络，不等于已覆盖飞行模式下的全部界面行为。

106 页 ZIP 的首次下载验证因原定 240 秒测试限时失败；期间文件数持续增长。将测试上限调整为 600 秒后，相同样本完成下载。上述耗时是这台模拟器与专用实例的实测值，包含调度、文件写入和完成校验，且部分漫画页已有阅读缓存，不作为通用吞吐量承诺。

## 联调发现与修复

实际阅读后执行“标记未读”时，客户端仍用首次读取的旧远端状态比较，误将自己刚上传的进度判成冲突。修复后以服务端写入响应更新比较基准，并保留原有本地 revision CAS、账户检查和冲突确认。

相关回归同时覆盖：自己的写入在服务端时钟领先时允许继续翻页；其他远端变化仍需确认；延迟读取不能覆盖已确认的写入；并发读取必须继续提供正确的远端冲突候选；写入响应缺少页码、总页数、完成状态或有效时间时不得确认成功。

本次 `koharia.smanga.*` 共 90 项单元测试全部通过。`spotlessApply`、`spotlessCheck`、`:data:generateDebugDatabaseInterface`、`:app:compileDebugKotlin` 及隔离 app/test APK 构建通过。

## 服务端问题与覆盖限制

代表性 PDF 的 OPDS 漫画封面及章节封面返回 HTTP 404；相同章节的原文件接口返回 HTTP 200、`application/pdf`，长度为 32,441,017 字节。App 保留封面占位图，不改用绕过鉴权的文件接口。

该库没有 RAR、7z 或目录章节，本次未验证对应解压 worker。未创建额外账户，因此受限账户媒体库权限、同地址不同真实账户切换、反向代理子路径仍依靠协议/单元测试覆盖，不能标为本实例实测通过。损坏 PDF、取消、无 Range 重试和迟到响应等异常情况由隔离 fixture 或单元测试覆盖；没有破坏或修改服务器原文件来制造这些故障。

## 诊断证据

原始日志和截图保存在 Git 忽略的 `.test-artifacts/smanga-live/`：`protocol-device.log`、`reader-device-7.log`、`reader-timing-final.log`、`regression-6.log`、`build-7.log`、`opds-probe-summary.json` 及 `screenshots/`。测试失败日志保留用于说明定位过程，不将失败的首次运行记为通过。

结束时独立复查服务器：14 个章节均无进度记录，本次选择的两个章节均无遗留历史。临时连接、下载文件及其空目录已清理；共享设置恢复。主机和设备私有目录中的临时凭据文件已删除，测试连接的 preferences 中没有残留密码。代码和完成的诊断文件共检查 97 个，未发现测试密码。已安装的隔离 app/test 包和原有应用数据保留。相关证据为 `final-server-state.json`、`credential-audit.json`、`cleanup-summary.json`。

## 书架冷启动回归修复（同日补充）

用户在 Android Studio 虚拟机实际添加连接后，媒体库列表可见，但首次书架显示“书架请求不可用”，中央区域暴露 `SmangaShelfException`。此前联调在进入 UI 前准备过 catalogue 缓存，没有覆盖真实分页消费者的冷请求路径。

新增 `SmangaShelfColdStartTest` 使用延迟返回、超过缓冲区大小的响应正文，并通过 Main 上的真实分页消费者加载。修复前在 `emulator-5554` 明确复现 `SmangaShelfException -> NetworkOnMainThreadException`：OkHttp `await()` 只异步等待响应头，后续读取正文仍在调用线程执行；外层 Flow 的 IO dispatcher 不能保证 `PagingSource.load` 运行于 IO。

修复将 API 正文读取、OPDS 验证及分页加载放到 IO。错误展示复用 `EmptyScreen`，移除顶部重复错误段落，中央显示中文原因和“重试 / 连接设置”；已有内容刷新失败时保留内容并提供可重试提示。请求条件改变后清除旧媒体范围的刷新错误，避免成功空搜索仍显示旧错误。

验证结果：

- 94 项 smanga 单元测试通过，包括错误类型映射、不展示原始响应和循环异常链。
- 两项隔离设备测试通过：冷请求、刷新 503 保留原内容、成功空搜索清除旧错误、Main 调用 OPDS 验证、关闭 API 后新模型读取非空缓存且零请求；中央错误文本边界位于内容区域中间，重试与连接设置按钮均触发对应操作。
- `spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin`、隔离 app/test APK 与普通 debug APK 构建通过。
- 用 `adb install -r` 更新模拟器已有 `app.koharia.dev`，保留其连接和数据。普通启动后，用户原有“测试库1”成功显示漫画列表。普通包没有运行 instrumentation；所有自动化用例均在 `app.koharia.dev.devicefixture` 内执行。

设备测试首次修复后运行还发现两处测试设施问题：未注册的 Voyager 模型复用了已取消作用域，以及 Espresso 输入注入反射不兼容当前系统。测试改用独立 Voyager holder 和现有项目采用的 `UiAutomation` 后通过；这些首次失败未计为成功。

证据位于 `.test-artifacts/smanga-shelf-fix/`：`red-device.log` 为修复前失败，`green-device-2.log` 为两项设备测试成功，`unit-tests.log` 为单测结果，`before-4619827259835644672.png` 与 `after.png` 为用户应用实际页面。ADB 的 `am start -W` 曾报告等待超时，最终成功显示以实际截图确认；不将该等待状态视为启动成功证据。

## Logo 与双地址设置（同日补充）

采用 smanga 当前默认分支 `e18b39c` 的侧栏紧凑 Logo 原文件，保留来源与许可证。地址弹窗从 LANraragi 原实现提取为共享 `ConnectionAddressSetting`，两者复用相同输入框和已有文案；smanga 使用共享地址路由，保存时以两端共同接受新登录 token 验证同一数据库，再检查 OPDS。

- 148 项单元测试通过，包含 smanga 全套测试和 Komga/LANraragi 的共享地址路由、地址校验回归。新增覆盖子路径路由、匿名探测、公网回退、禁止携密重定向、历史写入不重放及双地址身份不匹配。
- `emulator-5554` 上的 `SmangaConnectionSettingsTest` 通过，只使用 `-PdeviceTestFixture=true` 的隔离包。实测共享弹窗的高级设置、取消编辑、保存与重新读取局域网地址；确认修改局域网地址不改变账户缓存标识、媒体库选择和排序。测试仅删除自己创建的独立 preference 文件，保留安装包及既有数据。
- `spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin`、普通及隔离 Debug APK 构建通过。证据位于 `.test-artifacts/smanga-connection-settings/`。

双地址网络异常和同库验证使用本机受控 HTTP fixtures；本次未获得实际部署的两个不同访问地址，因此不标为真实双地址联调通过。

## 多媒体库刷新兼容性修复（同日补充）

专用实例调整为 2 个授权媒体库、共 8 部漫画后，刷新“全部”提示数据不完整或不兼容。实际分页数量、ID 和媒体库范围均正确；服务端使用 PostgreSQL，其中 `peppercarrot` 排在 `Space Adventures` 前。客户端却按 UTF-8 字节顺序验证名称单调性，错误拒绝了这份有效响应。两项新增回归测试在修复前均复现 `SmangaException`。

修复保留各媒体库的服务端名称顺序，不再用客户端名称顺序判定数据完整性。原合并比较器、游标结构和缓存 generation 均未改变，避免升级后混合不同合并规则；页长、总数、重复 ID、媒体库范围、游标偏移以及数字/日期排序检查仍然生效。服务端未提供数据库 collation，跨媒体库的整体名称顺序仍不能保证与数据库的全局语言排序完全一致。

验证结果：

- 151 项 JVM 测试通过，包括 smanga 全套与共享地址路由回归。新增覆盖大小写混排的 300 条记录、升降序、连续分页、序列化游标恢复，以及数字/日期坏序继续拒绝。
- `emulator-5554` 的隔离包中两项设备测试通过。新增真实实例测试验证两个媒体库的升降序各连续刷新两次、8 条结果无遗漏或重复、各库内部顺序保持；关闭 API 后新 catalogue 仍可读取缓存，失败刷新保留原内容。原冷启动分页、成功空搜索及刷新失败保留缓存测试同时通过。
- `spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin`、隔离 app/test APK 和普通 Debug APK 构建通过。
- `adb install -r` 更新原有 `app.koharia.dev` 后，在实际书架下拉刷新，确认“全部 / 测试库2 / 测试库1”和当前漫画封面正常显示，没有兼容性错误。未卸载或清除应用数据，普通包未运行 instrumentation。

测试仅执行登录和目录读取，未改变远端阅读状态；随机测试缓存身份已定向清理，临时私有凭据文件已删除，保留安装包及原有应用数据。证据位于 `.test-artifacts/smanga-refresh-fix/`：`red-unit.log`、`green-unit.log`、`unit-summary.json`、`device-tests.log`、`live-summary.log`、`ui-summary.json`、`before.xml` 和 `after.png`。

## 未提交更改审查修复（2026-09-22）

修复四项审查问题：

- PDF 进入相邻章节和点击重试时允许准备完整文件，后台预加载仍只读取已有缓存。同章请求串行，显式请求合并，失败不会被排队请求反复重试。长条阅读器区分布局与用户滚动，并支持已到列表底部时继续拖动进入下一章。
- 章节列表的 `latest: null` 作为远端已清除阅读状态处理，重置已同步的本地进度；保留待上传进度、映射确认和修订检查，迟到响应不得覆盖新状态。没有本地记录的章节不会被创建为新的阅读历史。
- 简介优先读取后端的 `describe`，保留旧字段回退。
- 书架刷新合并最新标题及其他展示元数据，同时保留本地 ID、收藏和阅读器设置；已下载列表继续使用本地记录，避免影响下载路径。

验证结果：179 项 JVM 测试全部通过，覆盖 smanga、PDF 章节加载、历史、下载策略及共享地址路由。`emulator-5554` 的隔离包中 10 项设备测试全部通过，包括 3 项真实 ReaderActivity PDF 连续阅读测试、6 项 PDF 缓存测试和书架冷启动分页回归。新增设备用例使用回环 HTTP 服务及生成的 PDF，验证后台零相邻原文件请求、进入相邻章节后只下载一次，以及底部拖动在无可消费滚动距离时仍能加载。

`spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin` 及普通/隔离 Debug APK 构建通过。通过 `adb install -r` 更新模拟器已有 `app.koharia.dev`，保留原有连接和数据；普通包未运行 instrumentation。

本轮远端清除阅读状态通过契约单测验证，未再次修改真实 smanga 实例的阅读记录。测试只清理自建连接、缓存和数据库记录，并恢复原有偏好；保留隔离包及既有应用数据。证据位于 `.test-artifacts/smanga-review-fixes/`：`unit-tests.log`、`unit-summary.json`、`device-tests.log`、`spotless-check.log` 和构建日志。
