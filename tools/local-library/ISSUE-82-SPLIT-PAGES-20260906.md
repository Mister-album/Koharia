# Issue #82：拆分宽页抢占阅读位置

Issue：https://github.com/Mister-album/Koharia/issues/82

## 修复

- `PagerViewerAdapter.onPageSplit` 不再把解码完成的页面当作导航目标。相邻页预加载拆分后，以当时实际可见的页或半页保持位置。
- 已拆分页按原始 ReaderPage 对象关联，覆盖当前及相邻章节；章节预加载刷新复用同一 InsertPage，避免清掉半页后反复重建。不同章节相同页索引不会再共用预处理记录。离开活动章节范围的记录及时释放。
- 拆分回调在拖动、翻页尚未结束时排队，空闲后再更新页面列表。关闭拆分及销毁阅读器会清理待处理项，失效阅读器忽略后续回调。
- 保留已有 Room/R8 启动修复，未调整版本号、GitHub 标签或发布资源。

## 自动验证

`spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过。

分页相关 JVM 测试 36 项通过。

当前连接的模拟器为 emulator-5556，Pixel Tablet / Android 15。新增 `PagerSplitDeviceTest` 两项设备测试通过：

- 通过正常数据库、临时本地文件和真实 ReaderActivity 打开 6 张宽图的 CBZ，分别覆盖右向左、左向右阅读。
- 每种方向进入阅读器两次，每次正向遍历 12 个半页，再反向回到开始；共 88 次翻页，检查当前半页标识及图像准备状态持续稳定。
- 使用真实 ViewPager fake-drag 接口进入拖动状态，在此期间触发远处宽页拆分，确认页面数在拖动结束前不变，结束后当前位置仍然保留。
- 停在第二半页时重新设置章节，确认半页选择不变；完整遍历后刷新章节，确认已拆半页及其顺序保留。
- 关闭阅读器重新进入后重复以上操作。此为阅读器退出重进，不是 Android 进程重启测试。

测试创建专用临时连接、漫画及 CBZ，结束后移除漫画和测试连接、恢复读者设置并删除仅本次创建的临时目录；连接 ID 的保留记录遵循现有不可复用规则。

## 交付与手测

Debug APK 已安装到 emulator-5556；未安装到实机，未提交、推送或更新 issue。

手动检查：竖屏开启拆分双页，右向左阅读连续宽图，确认“第 1 张右半 → 左半 → 第 2 张右半”的顺序，以及开启功能、滑动和重进时是否仍有可见黑屏闪烁。自动化验证页面选择和图像准备状态，不将其等同于所有设备的视觉卡顿验证。

证据：`artifacts/issue82-fix-build.log`、`issue82-final-build.log`、`issue82-pager-unit-tests.log`、`issue82-final-device-tests.log`。
