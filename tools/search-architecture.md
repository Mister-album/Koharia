# 搜索架构核对与共用组件

核对日期：2026-09-25。范围为 Komga、LANraragi、SManga 和本地库的书库搜索。未改公共 Source API、数据库及缓存刷新策略。

## 实际能力

| 数据源 | Koharia 当前查询路径 | 标签与语法 | 排序 |
| --- | --- | --- | --- |
| Komga | ScreenModel → Repository → POST books/series/list；旧版回退 GET search | 普通词扩展为 `(关键词) OR tag:(关键词)`，覆盖标题/ISBN及标签；显式字段和布尔查询保持原意。独立标签筛选仍使用结构化条件 | 全部支持相关性、添加、修改时间；单类型另有名称、随机 |
| LANraragi | ScreenModel → `filterLanraragiCatalog` → 持久化完整目录 | 普通词按空白拆分，匹配标题、tags、简介；独立标签筛选匹配 tagList。主搜索路径不调用 `/api/search`，不实现逗号、排除、通配符、`$` 精确标签等服务端语法 | 名称、添加时间、最后阅读、随机 |
| SManga | ScreenModel → account-scoped Catalog → 并发标题查询与标签查询 → 分页归并 | `manga.keyWord` 与 `/tag` → `/tags-manga` 并发；按 mangaId 跨页去重。详情标签可点击搜索；仅下载模式匹配本地标题和已存标签 | 名称、修改、添加时间；仅下载模式尚未实现这些排序，因此共用结果栏不展示无效排序入口 |
| 本地库 | ScreenModel → `browseIndexedLibrary` → 后台本地索引 | 一次匹配标题、标签、作者、画师、简介、文件夹、章节及格式，多字段命中仍只输出一次 | 名称、添加顺序（本地 ID）、索引修改时间，支持升降序；不提供相关性、随机排序 |

LANraragi 的服务器本身支持标签及高级语法，见 [官方搜索文档](https://sugoi.gitbook.io/lanraragi/basic-operations/searching)。不能因项目保留 `LanraragiApi.search`、`searchLanraragiWithFallback` 和 `advancedResults` 等旧入口，就宣称当前书库界面已经执行了这些语法。

SManga 能展示标签不代表关键词接口会查标签。项目所适配的后端提交 `97f54771f9d1bb83c0725e5e08769706503a8856`：

- [manga_controller](https://github.com/lkw199711/smanga-adonis/blob/97f54771f9d1bb83c0725e5e08769706503a8856/app/controllers/manga_controller.ts)：分页 `keyWord` 只用于 `subTitle contains`；详情另加载 tags。
- [tags_controller](https://github.com/lkw199711/smanga-adonis/blob/97f54771f9d1bb83c0725e5e08769706503a8856/app/controllers/tags_controller.ts)：提供标签列表及 `tags_manga` 查询。后者先分页标签关联再按漫画去重，`count` 为本页去重数量，因此新实现以空页为终点，不能因不足100项就结束。
- [routes](https://github.com/lkw199711/smanga-adonis/blob/97f54771f9d1bb83c0725e5e08769706503a8856/start/routes.ts)：`/tag`、`/tags-manga` 和 `/search-mangas` 是不同入口。

以上为当前适配契约及官方源码核对，未对用户服务器版本或实际索引进行联网验证。

## 组件收敛

输入框原本已经共用 `SearchToolbar`，重复主要在上层工具栏、范围菜单、搜索结果栏及排序交互。

现在四种书库共用：

1. `ConnectionLibraryToolbar`：输入、提交、关闭、显示模式及连接菜单。搜索框最右侧统一提供“搜索”按钮，点击立即提交并收起键盘；Komga 类型选择放在该按钮左侧。Komga 的 toolbar 仅负责提供它的资源类型选项，不再重复维护工具栏实现。
2. `ConnectionSearchScope`：范围下拉，值与显示位置分离；当前 Komga 提供全部/系列/阅读列表/书籍。
3. `ConnectionSearchResults`：搜索结果标签、右侧排序图标；展开后显示排序模式、当前选中状态与方向箭头。图标保留无障碍排序说明和当前状态。
4. `ConnectionSearchSortOption`：数据源声明实际支持的选项、是否可反转、默认方向；再次选择当前项时统一切换方向。数据源 ScreenModel 只接收值和方向并更新自身筛选/持久化。

LANraragi 和 SManga 进入搜索模式后隐藏分类/媒体库栏；共用工具栏同时隐藏连接切换按钮。以 `toolbarQuery != null` 判断搜索模式，因此空输入和防抖等待期间也保持一致；退出搜索后恢复入口及原有库选择。

本地库同样隐藏搜索态的书架栏，并复用提交按钮和图标排序菜单。`LocalLibraryToolbar` 仅向共用工具栏提供导入、图片合并、书架管理动作及筛选高亮状态；排序沿用原有筛选持久化开关，不重建目录、不触发磁盘扫描。

查询文本、页游标、账号身份及缓存仍由各数据源负责。Komga 的书籍/系列归并、LANraragi 的本地目录检索、SManga 的授权媒体库归并不是同一种数据契约，不能用一个通用网络分页器替换。输入触发时机也仍保留现状：Komga 提交后搜索、LANraragi 本地即时检索、SManga 350ms 防抖。

## 标题与标签联合查询

- Komga 使用服务器 OR 查询，在服务端排序和分页前合并命中，同一本书的标题与标签命中不会产生两行。不额外拆分成两个网络往返；“全部”的书籍、系列两路仍并发，时间归并的两路页头现在也并发读取。[Komga FTS 文档](https://komga.org/docs/guides/search/)规定 CJK 至少两个字符，阅读列表仍按原名称查询（没有书籍标签字段）。
- LANraragi 一次本地遍历同时匹配标题、标签、简介，已有后台 IO 调度；增加稳定 ID 去重。无需为已有完整目录再发网络请求。
- SManga 标题分页与标签 ID 解析/关联分页并发，按当前排序归并，每个输出页最多100项。去重集合跨页保留，标题与标签、多标签、跨页关联重复均只输出一个 mangaId。输入页缓冲有界，去重 ID 集合随已显示结果增长。关联接口不支持媒体库参数，因此客户端在输出前限制为当前选中的授权媒体库；中间页全属其他媒体库时继续加载。
- SManga 搜索缓存使用 `search/title-tags-v1/`，避免误用旧标题缓存。缓存、游标及去重记录按连接、账号、授权媒体库集合、关键词、排序隔离。成功空结果仍有效；失败重试复用已成功请求；显式刷新暂存新一代结果，全部成功后原子替换。没有 TTL 或联网恢复刷新。
- 输入、排序或账号改变仍由现有 Pager / 会话取消链路处理；后台请求使用结构化协程，取消会传递到两路查询。SManga 详情标签入口使用普通联合查询，不伪装成 Komga 的高级语法。仅下载搜索取决于本地是否已经存有标签。

## 验证

- `spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin` 通过。
- 55 项定向单元测试通过：Komga 搜索状态/类型、LANraragi 目录/搜索辅助逻辑、SManga 缓存/跨库归并、共享缓存策略。
- `emulator-5554`，Android 16，使用 `-PdeviceTestFixture=true`：2 项共用组件测试通过，覆盖默认方向、重复选择反转、能力切换移除选项、资源范围使用稳定值而非位置索引。
- 当前 Espresso 与 Android 16 输入接口不兼容；最终测试通过 ActivityScenario 和 Compose 公开测试语义接口操作 fixture 进程内的窗口。未升级生产依赖，未清除应用数据或卸载 APK。
- 日志：`.test-artifacts/search-architecture/`；最终设备结果 `device-tests-5.log`。未进行真实服务器标签搜索端到端验证。

标签扩展的 `spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin` 及97项定向单元测试通过，覆盖并发互相等待验证、双向跨页去重、短标签页及媒体库过滤、失败追加重试、取消两路请求、持久缓存零请求、空缓存、刷新失败保留、连接/账号隔离，以及 Komga 新旧 API 联合条件。最终日志为 `.test-artifacts/tag-search/build-4.log`。该扩展未连接用户服务器进行真实数据验证。
