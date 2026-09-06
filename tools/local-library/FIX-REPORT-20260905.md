# 本地媒体问题修复与回归 · 2026-09-05

后续默认字号修复及最新 APK 见[字号修复记录](FONT-SIZE-FIX-20260905.md)。下文保留此前构建的历史验收结果。

针对[原测试报告](TEST-REPORT-20260905.md)的 15 个失败用例及空章节名观察项进行修复。**159 项相关单元测试、5 项设备回归测试通过；原失败路径完成定向复测。** 原始 68 用例/400 检查点记录保留为修复前基线，本轮没有把未全量重跑的 68 用例改写成“全部通过”。

## 修复结果

| 问题 | 最终修改 | 复测证据 |
|---|---|---|
| MOBI/PRC/AZW/AZW3 正文截断，8 用例 | 按声明的解压长度读取，保留 256 MiB 上限；先剥离记录尾部附加数据和多字节重叠区；KF8 按 FDST 表读取正文流，避免把 CSS 附在文末 | [8 个样本文末](../../artifacts/local-media-runs/20260905-takeover/fixed-kindle-grid.jpg)、[长度审计](../../artifacts/local-media-runs/20260905-takeover/fixed-mobi-audit.json) |
| 图集拖动进度条崩溃，1 用例 | 页面回收、替换或脱离窗口时取消延迟缩放；执行前检查当前视图及图像是否就绪，取消动画构造器的强制解引用 | [12 次重复拖动记录](../../artifacts/local-media-runs/20260905-takeover/fixed-drag-results.json)、[结束画面](../../artifacts/local-media-runs/20260905-takeover/evidence/series-image-jxl/fixed-drag-complete.png) |
| GIF/动画 WebP 缩放和平移失败，4 用例 | Pager 不截获多指手势；PhotoView 向翻页容器报告可滚动范围，并支持阅读器的左右平移操作 | [单文件 GIF](../../artifacts/local-media-runs/20260905-takeover/fix-gif-after.jpg)、[系列 GIF](../../artifacts/local-media-runs/20260905-takeover/fixed-series-image-gif.jpg)、[单文件 WebP](../../artifacts/local-media-runs/20260905-takeover/fixed-individual-image-webp-animated.jpg)、[系列 WebP](../../artifacts/local-media-runs/20260905-takeover/fixed-series-image-webp-animated.jpg) |
| EPUB 最后目录项无法跨册，1 用例 | 同一 XHTML 的多个目录项按当前可见锚点定位；章节导航前重新解析当前目录项，再决定进入下一项或下一册 | [第十二章 → 许可页 → 下一册](../../artifacts/local-media-runs/20260905-takeover/verified-epub-adjacent.jpg)、[下一册标题](../../artifacts/local-media-runs/20260905-takeover/evidence/series-book-epub/verified-next-title.png) |
| EPUB 偶发触摸无响应，1 用例 | 原因已定位为关闭的目录抽屉抢占横向手势。抽屉仅在打开时启用拖动，关闭时由阅读器处理；“目录”按钮仍可打开抽屉 | [菜单显示时连续翻页](../../artifacts/local-media-runs/20260905-takeover/drawer-fixed-visible-menu.jpg)、[书内目录链接进入正文后翻页](../../artifacts/local-media-runs/20260905-takeover/drawer-fixed-directory-link.jpg) |
| 单文件章节名称为空 | 移除重复书名后若名称变空，保留原始章节名；已有记录刷新章节列表后更新 | [刷新后章节名](../../artifacts/local-media-runs/20260905-takeover/evidence/individual-image-gif/fixed-name-refresh.png) |

MOBI/PRC 样本的原始正文解压长度为 228,070 字节；AZW/AZW3 为 237,190 字节，其中正文流 234,180 字节，辅助流 3,010 字节。修复后的审计都能找到故事结束标记。KF8 原始正文由骨架和片段组成，不能用“是否以 `</html>` 结尾”判断完整性；本轮核对声明长度、FDST 范围和实际故事/许可内容，不宣称新增完整 KF8 排版支持。

动画问题通过设备测试稳定复现：相同 GIF 在独立图片组件中可以缩放，放进三页 Pager 后比例停在 1.0；修改后独立组件、Pager 以及实际四个动画样本均通过。动画继续播放，放大后拖动不会直接切到章节边界。

进度条重复拖动前后应用 PID 均为 10699，12 次操作后仍在 ReaderActivity；[该进程的崩溃缓冲区](../../artifacts/local-media-runs/20260905-takeover/fixed-drag-crash-log.txt)没有崩溃记录。该问题仍按触发时的 JXL 用例记录，修复的是页面生命周期，不是 JXL 解码器。

EPUB 触摸异常在修复过程中再次出现，未被前两轮成功覆盖。通过 Android WebView 调试确认：直接调用 Readium 翻页能够移动正文；菜单显示时关闭的 Compose 抽屉截走手势。最终修复后，保持菜单显示可从 156 连续翻到 159；从书内目录链接进入正文后又验证了 154 → 155 → 156。诊断期间试过的截图超时防护已移除，最终修改针对手势冲突。

## 自动化验证

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck :app:compileDebugKotlin :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:testDebugUnitTest --tests 'koharia.document.*' --tests 'koharia.epub.*' --tests 'koharia.media.LocalMediaFormatsTest' --tests 'eu.kanade.domain.chapter.interactor.ChapterSanitizerTest' --tests 'eu.kanade.tachiyomi.ui.reader.viewer.pager.*'
```

以上检查通过。159 项 JVM 测试为 0 失败、0 错误、0 跳过；Android 设备测试 5 项通过。新增覆盖压缩正文大于文件、声明长度与填充、无符号超大长度、记录尾部数据、KF8 辅助流、章节名、页面回收、PhotoView/Pager 手势和同资源目录锚点。

- [最终构建与单测日志](../../artifacts/local-media-runs/20260905-takeover/fix-final-verified-build.log)
- [单测计数](../../artifacts/local-media-runs/20260905-takeover/fix-unit-summary.json)
- [设备测试日志](../../artifacts/local-media-runs/20260905-takeover/fix-final-device-tests.log)

## 构建与环境

修复 APK：[Koharia-v0.4.2-7898-debug-x86_64.apk](../../app/build/outputs/apk/debug/Koharia-v0.4.2-7898-debug-x86_64.apk)。基础提交仍为 `826d4293a7b87f5ae52c922b5a6cf47e20b171ea`，修改留在工作区，未提交或推送。

SHA-256：`3666a53727510e0f0fc6f11f21b3fda140b1663fcccbafd2347753a1959963bc`。已确认模拟器安装包与该文件哈希一致，见[安装包核对](../../artifacts/local-media-runs/20260905-takeover/fix-apk-identity.json)。

设备为 `emulator-5554`，Android 17 / SDK 37，x86_64，16 KB 内存页，1080×2400。本轮观察到的渲染后端为 OpenGL ES Translator / NVIDIA RTX 3070，与原性能采样使用的软件渲染不同；本轮结论用于功能回归，不直接比较两轮性能数值。未操作物理手机，未验证 release/R8 或墨水屏真机刷新效果。

## 数据与记录

原始截图、首次失败及中间复测保留在 `artifacts/local-media-runs/20260905-takeover/`；最终验证使用本报告链接的 `fixed-*`、`verified-*`、`drawer-fixed-*` 证据。未删除样本、手动下载或缓存，未清空应用数据。

测试结束后，仅对本轮涉及且 ID/所属书籍/URL 核对一致的 13 个章节，恢复原任务备份的 `read`、`bookmark`、`last_page_read`，恢复前另存当前值并逐项验证。原生 EPUB locator 和阅读历史缺少原始备份，仍保留测试状态；章节名和修复后页数等元数据保持更新。见[恢复结果](../../artifacts/local-media-runs/20260905-takeover/fix-progress-restore-result.json)。

格式字段核对参考：[Calibre MOBI 头读取](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/ebooks/mobi/reader/headers.py)、[记录附加数据处理](https://github.com/kovidgoyal/calibre/blob/master/src/calibre/ebooks/mobi/reader/mobi6.py)。实现使用当前 Kotlin 解码器和现有 Readium/PhotoView，未增加应用依赖。
