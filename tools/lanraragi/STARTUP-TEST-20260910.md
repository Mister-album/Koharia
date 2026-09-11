# LANraragi 启动与空页刷新修复（2026-09-10）

## 修复

- 空页的重试按钮原先只调用 LazyPagingItems.refresh()，而 LANraragi 使用 PagingData.from() 构造静态分页，没有真正的 PagingSource 负责远端刷新。新增可选刷新回调，LANraragi 将空页按钮、下拉刷新和顶部菜单统一接入 ScreenModel.refresh()；其他来源保留默认分页刷新行为。
- 手动刷新不再被可能滞后的网络状态拦截，直接尝试连接服务器；自动刷新仍参考网络状态并保留合并策略。
- 首次缓存读取完成前使用加载态，列表显式发出初始下载状态信号；页面已经处于 RESUMED 时也立即触发自动刷新检查，避免只等待后续生命周期事件。底层 DownloadCache 已有初始广播，因此没有将下载事件认定为所有冷启动故障的唯一原因。
- 真正冷启动验证发现，独立测试包积累较多 LANraragi 连接后，每连接一个网络回调耗尽 Android 请求配额，引发 TooManyRequestsException。改用 Injekt 单例 LanraragiNetworkMonitor，共享一个系统监听；各连接只订阅状态，关闭或重载仍取消各自任务。

## 验证

- spotlessApply、spotlessCheck、:app:compileDebugKotlin 通过。
- 43 项 LANraragi 与本地书库加载定向单元测试通过。
- 冷启动：预先同步测试书库，保留数据库，令测试服务器返回不可用响应，force-stop 仅独立测试包后重新运行 MainActivity。无需点击刷新即可看到 Fixture book 5 与合集；最后成功同步时间没有变化，证明读取的是持久化缓存。最终测试约 4.3 秒通过。
- 空页刷新：新连接首次同步失败后显示空页，等待自动请求结束；恢复测试服务器，故意将该连接的网络状态提示设为离线，再点击页面中央的重试按钮。新的完整目录发布并显示，验证该按钮确实调用了远端刷新。
- 网络监听修复后，同一批保留的连接能够完成冷启动，没有为规避配额问题删除历史测试连接。

目标为 Resizable_Experimental / emulator-5554，Android 17，x86_64 / 16 KB。仅操作 app.koharia.dev.devicefixture（Koharia Auto Tests），应用及测试 APK、用户手动安装包和数据均保留。未清除应用数据或卸载应用。

LanraragiStartupRefreshDeviceTest 的 seedColdStart 是准备步骤，coldStartShowsCacheWithoutRefresh 和 emptyPageRetryRefreshesServerAndPublishesLibrary 是两个行为验证。冷启动原始结果、失败调查与最终结果位于 .codex-work/lanraragi-startup-*.txt；截图位于 .codex-work/lanraragi-startup/。
