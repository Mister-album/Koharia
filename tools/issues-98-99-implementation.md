# #99 搜索排序与 #98 合并导出交付记录

日期：2026-09-25。未提交、推送、发布或向 issue 发评论；未改公共 Source API 或 SQLDelight schema。工作区原有 EPUB、本地书库、封面和字符串修改保留。

## #99：搜索排序

### 实现

- 搜索结果栏右侧以轻量下拉菜单显示当前排序，选择后立即应用，无需打开完整筛选弹窗。全部搜索保留原排序索引 `0 / 2 / 3`，对应相关性、服务器添加时间、服务器修改时间；单类型继续支持名称和随机。再次选择当前时间或名称项切换升降序并显示箭头，首次选择时间项默认最新在前。
- 首次搜索继承可用时间排序；名称/随机进入全部时回退相关性，不因自动回退写入持久化偏好。退出搜索恢复进入前的浏览筛选快照。
- 筛选快照深复制嵌套选项，保留离线才知道的标签/作者/书库；复制不调用网络取筛选项。打开筛选面板不修改当前 Paging 查询，取消丢弃草稿，确认时才生成新查询。
- `KomgaSortedSearchPagingSource` 由界面 Pager 创建，独占 `KomgaSortedSearchSession`。书籍和系列分别持有页号、偏移、结束标记与已取得缓冲。
- 两路 API 都发相同时间方向，并附 `sort=id,asc`。按服务器时间归并，每次最多 25 项，同时间固定书籍在系列前、同类型按 ID。缺失服务器时间排最后，离线不会用本机收藏时间冒充。
- 时间比较使用线程安全的 ISO 时间解析和 `Instant`，保留纳秒及显式时区；无时区时间沿用 UTC 语义。空关键词的“全部＋时间排序”也使用归并会话，清空关键词不会退回相关性请求。
- 输出页使用事务式游标；一路失败不提交半页，成功缓冲留待重试。已交付页可重放，避免本地映射失败后重试跳过。协程取消和账号 namespace 变化会拒绝旧输出及缓冲。
- 沿用现有请求、DTO、NetworkToLocalManga、缓存和旧 API 回退。相关性仍交替展示，不制造可跨类型比较的分数；没有新增 TTL 或网络恢复刷新。

### 验证范围

新增 `KomgaSortedSearchSessionTest`、`KomgaSearchStateTest`，扩展 `KomgaSearchTypeTest`、`KomgaApiSearchTest`、`KomgaFilterStateTest`；同时运行现有搜索、离线筛选、MetadataCacheStore、ShelfCache 与 ConnectionShelfCachePolicy 测试。

覆盖数量悬殊、一路为空、跨页、同时间跨类型边界、两个方向、修改时间、一半失败重试、取消、会话隔离、账号改变拒绝已缓存页、显式排序索引、筛选草稿与退出恢复、快速类型/关键词切换、持久化入口与作用域。缓存回归覆盖冷启动首次请求、暖缓存零新增请求、有效空缓存、刷新失败保留成功内容和账号隔离；新旧 API 请求都检查次级排序，既有旧 API 回退测试继续执行。

ScreenModel 状态测试使用 mock source 检查行为与保存调用；不是实际服务器的端到端 UI 测试。没有对一个可写测试 Komga 服务器做账号切换、SSE 与持续分页实测。服务器分页没有快照 token，因此浏览中远端数据本身变动时，无法承诺数据库级快照一致性。

Review 回归补充了同秒纳秒差异跨远端页与输出页的双向归并、显式时区、非法时间、清空关键词与退出搜索恢复；定向搜索单元测试 35 项通过。日志位于 `.test-artifacts/issues-96-98-99/review-fixes/`。

主要代码：

- `app/src/main/java/koharia/komga/domain/repository/KomgaSortedSearchSession.kt`
- `app/src/main/java/koharia/komga/ui/library/KomgaSortedSearchPagingSource.kt`
- `app/src/main/java/koharia/komga/ui/library/KomgaLibraryScreenModel.kt`
- `app/src/main/java/koharia/source/komga/KomgaFilters.kt`

## #98：合并导出

### 实现

- 双页实际成对显示时，页面操作中的“保存”默认直接保存合并图，不再弹出导出选项。设置 → 漫画阅读器 → 阅读操作中可选择合并保存或分别保存当前双页，并设置合并布局与编码；使用共享阅读偏好。分别保存时串行复制两份原始图片流，实际单页仍只保存该页。
- 默认统一高度，维持既有尺寸和页序；等高整数绘制，异高只缩放一次，提示较矮页面会放大。原始像素模式不缩放、垂直居中、空白填白。
- 默认无损自动：先 PNG，再在 Android 30+、两边均不超过 16,383 时编码无损 WebP，仅保留更小候选。另有固定 PNG 和有损 JPEG95；JPEG 透明区白底，尺寸不变。
- 导出返回实际文件、MIME 与扩展名；最终仍由 ImageSaver 识别编码字节并生成相符的文件名及 MediaStore MIME。临时文件名不决定最终格式。
- 两张输入串行解码，合成 Bitmap 只保留一份；保留内存预检，候选使用临时文件，结束回收 Bitmap、清理候选。阅读显示的 DoublePageCompositionPolicy 未修改。

格式约束与 JPEG 转无损可能增大的原因参见 [WebP 官方 FAQ](https://developers.google.com/speed/webp/faq)。无损编码是相对于合成 Bitmap；统一高度的重采样不是原图像素无损。

### 报告样本与复现边界

已尝试访问报告链接，能读到分享页的 3 个文件及显示体积：合并 PNG 21.80 MB、两张 JPEG 3.07 MB 和 2.53 MB。**未取得这 3 份原始文件**，因此没有可靠的原图尺寸或 100% 像素对照，不能宣称复现了报告者的具体损失。以下均为生成样本的验证结果。

### 设备结果与文件体积

隔离 fixture，`emulator-5554`，Android 16 / x86_64。细网点与小字输入 301×401、400×600；奇数高度差使原始模式上/下留白差 1 像素，属于整数居中的预期结果。

| 布局 | 输出尺寸 | PNG bytes | 自动 WebP bytes | JPEG95 bytes | 自动相对 PNG |
| --- | --- | ---: | ---: | ---: | ---: |
| 统一高度 | 851×600 | 62,006 | 23,156 | 379,859 | 减少 62.65% |
| 原始像素 | 701×600 | 13,487 | 3,820 | 261,476 | 减少 71.68% |

- 自动编码与 PNG 解码像素逐点相同；原始像素模式左页全部不透明像素逐点相同。
- 等高、透明区域、JPEG 白底、损坏输入拒绝，以及原有双页左右/反转顺序与接缝设备测试通过。
- 这组高频规则图对 PNG/WebP 很友好，JPEG 反而膨胀并产生局部压缩损失；不能据此推断真实彩色扫描页的收益。JPEG 对相同布局 PNG 的平均通道误差分别为 1.23 / 255、0.71 / 255，最大局部差为 158、168，集中在高频/彩色字边缘；平均数很小不等于逐像素无损。
- 内存不足由纯布局/预算单元测试验证预检拒绝，未在设备上故意耗尽内存。WebP API 版本与尺寸限制也由单元测试覆盖，未分配 16,384 像素的极限合成图来施压设备。
- 初版曾验证保存选项面板，后按产品调整移除该面板，选项全部移入阅读设置。旧失败截图仅保留为初版诊断，不代表当前交互。

原始证据位于 `.test-artifacts/issues-96-98-99/export/export/`，完整指标为 `metrics.csv`，100% 局部对照为 `comparison.png`。

最终工程结果：`spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin`、fixture/test APK 构建均成功，13 个测试类共 **65 项单元测试全部通过，无跳过**。记录为 `.test-artifacts/issues-96-98-99/final-verification-5.log`。

上一轮完整计划的设备回归 **8/8 通过**：2 项合并编码测试、5 项双页阅读/保存测试、1 项抗摩尔纹证据采集，记录为 `device-tests-final.log`。测试结束保留 fixture/test APK 与既有数据。

随后调整双页默认保存行为：扩展双页设备回归，在四种左右/反转组合中点击普通“保存”直接导出，再验证“分别保存”的两份文件与输入字节完全相同，以及单页回退。该轮检查记录位于 `save-settings/`；不重复运行与此次修改无关的搜索和抗摩尔纹基准。

此次调整的 `spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin`、fixture APK 构建及 4 项导出策略单元测试通过（`save-settings/build-2.log`）；**7 项设备测试全部通过**（`save-settings/device-tests.log`），包括新增的分别保存与单页原始字节验证。

### 工程检查与重跑

```powershell
.\gradlew.bat -PdeviceTestFixture=true spotlessApply spotlessCheck :app:compileDebugKotlin :app:testDebugUnitTest --tests '*Komga*Search*Test' --tests '*Komga*Filter*Test' --tests '*Komga*Cache*Test' --tests '*ConnectionShelfCachePolicyTest' --tests '*MergedPageExportPolicyTest'
.\gradlew.bat -PdeviceTestFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 shell am instrument -w -e class eu.kanade.tachiyomi.ui.reader.viewer.pager.MergedPageExportDeviceTest,eu.kanade.tachiyomi.ui.reader.viewer.pager.DoublePageActionsDeviceTest app.koharia.dev.devicefixture.test/koharia.testing.KohariaDeviceTestRunner
```

设备安装应使用本轮 `output-metadata.json` 指向的 fixture APK 并核实 applicationId；不得对用户包运行测试、卸载或清空数据。已安装 fixture/test APK 和既有应用数据均保留。新文案仅增补 `base`、`zh-rCN`。

#96 的完整候选比较、计时、来源和复现说明见 [抗摩尔纹技术验证](comic-resampling-evaluation.md)。
