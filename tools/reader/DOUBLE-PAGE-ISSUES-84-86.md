# Issues 84–86：双页保存与阅读复核（2026-09-11）

## 实现

- #84：双页长按菜单新增“保存合并页”。仅当前槽位有两张已就绪且可读取原图的页面时显示。
- 捕获当前两页及其物理左右顺序，兼容从左到右、从右到左和交换双页。后台按比例统一至较高原图的高度，无间隙输出 PNG，沿用相册保存、分漫画目录、通知与错误反馈。
- 单页保存仍保留。导出不包含阅读器黑边、菜单、缩放或裁边效果；不会修改原始文件或阅读进度。
- 合并前检查尺寸及内存预算，内存不足提示失败，不静默降低分辨率。输出先写临时文件，保存成功或失败后均释放位图并清理本次临时文件。文件名保留页码范围并遵守长度限制。

## 复核结论

- #85：现有 bc926c51f 已通过 DoublePageLayout 按图片比例安排相邻边界。本次使用 600×900 与 601×900 的无黑边红绿图片，在真实阅读器检查两页边界误差不超过约 1 像素，并检查中线左右 5 列像素无黑线。四种阅读方向/交换顺序组合均覆盖。原图自带边框、裁边设置和不同缩放策略仍需按具体图片区分，不能保证所有原图都没有黑线。
- #86：问题存在。PagerPageHolder.renderTiledPair 为两页分别建立 ReaderPageImageView，每个内部拥有独立 SubsamplingScaleImageView；onScaleChanged 仅隐藏菜单。双击和双指放大左侧控件后，右侧比例保持不变。该问题随后已修复：DoublePageLayout 统一处理双击、双指缩放和平移；两张分块解码图像使用相同的跨页坐标变换，并以共同中缝裁剪显示。
- 同步缩放保留两张原图的分块解码，不生成大尺寸合并位图。同步更新点击页分界、左右平移余量和翻页判断；裁边后的解码尺寸用于重新计算跨页比例。

## 验证方法

使用 emulator-5554（Resizable_Experimental），仅 app.koharia.dev.devicefixture / Koharia Auto Tests。测试使用随机独立本地连接和生成的 CBZ；结束仅移除当前测试创建的连接、漫画记录、源文件与相册图片，保留 APK 和既有数据。

模拟器忽略应用方向请求，因此测试期间显式锁定系统横屏，finally 恢复 rotation 0 与 free。测试断言实际阅读器宽度大于高度，避免误把竖屏作为横屏验收。

DoublePageActionsDeviceTest 包含四种实际阅读器组合及一项导出边界测试：中缝截图像素、长按菜单动作存在、实际点击保存、相册 PNG 尺寸/顺序、不同高度归一化、无效输入拒绝、双击/双指同步缩放、放大后的中缝、点击页定位、平移边界和缩回后翻页。

## 人工验收

1. 横屏打开逐页漫画，选择双页布局，等待两页加载完成。
2. 长按任一页，点击“保存合并页”；在相册查看两页顺序、拼接位置和清晰度。
3. 切换阅读方向和交换双页，再次保存；结果应与画面左右顺序一致。
4. 切到单页或未配对页面，确认没有合并保存入口，原保存按钮正常。
5. 在无原生黑边的跨页漫画上检查中缝；双击或捏合任意一页，两页应作为整体放大；拖动时中缝不分离，缩回后可正常翻页。

验证结果：spotlessApply、spotlessCheck、:app:compileDebugKotlin、:app:testDebugUnitTest 通过；最终横屏设备测试 5/5 通过。截图保存在本机 .codex-work/double-page/rtl-landscape.png；构建与测试日志位于 .codex-work/double-page-*.log。未发布 issue 评论，未提交或推送本次修改。

同步缩放修复验证：spotlessApply、spotlessCheck、:app:compileDebugKotlin、:app:testDebugUnitTest 通过；横屏设备 5/5 通过。手势事件经过 Pager 分发，覆盖左右阅读和交换双页。日志为 .codex-work/spread-zoom-final-build.log 与 .codex-work/spread-zoom-device.log。
