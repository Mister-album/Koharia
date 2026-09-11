# LANraragi 作品预览页顶部背景恢复（2026-09-10）

- 原预览页使用固定实色 AppBar，整个网格应用顶部 padding，封面背景无法延伸到导航栏后。
- 改用 LazyGridState 的实际首项与偏移判断顶部状态，导航栏背景与标题透明度随滚动位置变化，沿用系列详情页及 E-Ink 动画策略。
- 网格保留横向安全边距，顶部间距移入 MangaInfoBox 的内容布局，让封面背景覆盖导航栏后方；底部仍预留阅读按钮与系统导航空间。
- spotlessApply、spotlessCheck、:app:compileDebugKotlin 通过。
- LanraragiPreviewToolbarDeviceTest 在 emulator-5554 / app.koharia.dev.devicefixture 通过。使用 60 个生成预览页进行两轮向下滚动、向上回顶，以实际截图像素验证顶部封面色调恢复，并人工核对初始、滚动和恢复截图。
- 未修改手动安装包和用户书库，测试应用及测试 APK 保留。

截图位于本机 .codex-work/lanraragi-toolbar/：initial.png、scrolled.png、restored.png。
