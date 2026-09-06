# 本地媒体格式测试报告 · 2026-09-05

后续修复与定向回归已完成，见[修复报告](FIX-REPORT-20260905.md)。下文保留修复前测试基线。

接手任务“规划全App E-link模式”（`01a05241-55b7-7ef2-8776-7dae0390f627`）中最后要求的本地媒体测试，按步骤操作并检查画面。**68 个用例全部执行，53 个通过、15 个失败；400 个检查点已判定，无阻塞或待测项。** 测试完成不代表产品验收通过：发现 4 类可复现问题和 1 项偶发触摸异常，尚未修改产品代码。

覆盖清单声明的 26 种扩展名、系列/单文件组织、文本编码、动画图片、空归档、独立图片 EPUB 回归以及 3 个大文件。397 个原始检查点之外，补充了 2 个动画 WebP 缩放检查和 1 个 EPUB 触摸检查。58 个独立章节承载 68 个用例，系列图集中的多种图片共享章节。

- [68 项结果矩阵](../../artifacts/local-media-runs/20260905-takeover/summary.md)
- [逐检查点结论、说明与证据路径](../../artifacts/local-media-runs/20260905-takeover/reviewed-checkpoints.jsonl)
- [CSV](../../artifacts/local-media-runs/20260905-takeover/matrix.csv) · [详细发现](../../artifacts/local-media-runs/20260905-takeover/FINDINGS.md)

证据位于本机 `artifacts/local-media-runs/20260905-takeover/`，属于忽略的测试产物，未提交仓库。初次失败和冷启动复测都保留；后续成功不会覆盖初次失败。

## 环境与基线

| 项目 | 实际测试值 |
|---|---|
| Git HEAD | `826d4293a7b87f5ae52c922b5a6cf47e20b171ea` |
| 包名 / 版本 | `app.koharia.dev` / `v0.4.2-7898`，versionCode 9 |
| APK | `app/build/outputs/apk/debug/Koharia-v0.4.2-7898-debug-x86_64.apk` |
| APK SHA-256 | `c4be55f671776856e45b7b5c31622c13dd7dd405d43b912397b81950000fa1ef` |
| 设备 | `emulator-5554`，Android 17 / SDK 37，x86_64，16 KB 内存页 |
| 画面 | 1080 × 2400，density 420；旋转检查后回到竖屏 |
| 渲染 | 同一 AVD 临时使用 software GPU / SwiftShader 冷启动，未清数据 |

已核对已安装 APK 与本地 APK 的哈希相同；68 个源样本哈希与清单一致，所有目标章节定位唯一。接手时模拟器出现 Launcher ANR 和严重 GPU 卡顿，Android 重启后仍异常，改用临时软件渲染冷启动后继续测试。该环境故障与下面可重复的应用崩溃分开记录。未操作连接的物理手机。

## 失败用例与复现

### P1：MOBI / PRC / AZW / AZW3 正文被截断（8 个用例）

影响 `series-book-*`、`individual-book-*` 中上述四种格式。打开 Alice 样本，拖到文末：MOBI/PRC 在第 61 页的 `at on` 处中断；AZW/AZW3 在第 64 页的审判诗句处提前结束，却显示已读完。8 个用例分别冷启动复测，截断一致。

`DocumentEngine.kt:482` 将 PalmDOC 声明的解压文本长度用 `coerceAtMost(bytes.size)` 限制为压缩文件大小，517 行再按此长度截断解压数据。MOBI/PRC 的声明长度 228,070 字节，文件 189,794 字节，少读 38,276 字节；AZW/AZW3 分别为 237,190 和 203,509 字节，少读 33,681 字节。保留完整解压长度的诊断结果含故事结尾 `happy summer days`，当前截断结果不含。

“能打开、能翻页、已读完”不能证明正文完整；此前的末页通过判断已根据内容审计改判失败。其余通过项仅描述实际加载部分。四种格式在原清单中标为实验性，仍记录实际缺陷。

证据：[长度审计](../../artifacts/local-media-runs/20260905-takeover/mobi-length-audit.json)、[MOBI 冷启动末页](../../artifacts/local-media-runs/20260905-takeover/evidence/individual-book-mobi/retry-end.png)。其余 7 个用例的 `retry-end.png` 位于对应 evidence 目录。

### P1：漫画进度条拖动触发空指针崩溃（1 个用例）

在系列图集 `Vol.01` 的 JXL 用例中打开阅读菜单，执行进度条拖动：

```powershell
adb -s emulator-5554 shell input swipe 540 2127 222 2127 400
```

两次进入 CrashActivity，包括 AVD 冷启动后的复测。堆栈指向 `ReaderPageImageView.landscapeZoom` 的延迟回调；代码 141–153 行延迟 500 ms 后使用 `config!!` 和 `animateScaleAndCenter(...)!!`，需要检查页面切换/回收生命周期。**这是图集导航中观察到的崩溃，尚未证明与 JXL 解码器有关。** JXL 本身可显示、缩放和旋转。

证据：[首次日志](../../artifacts/local-media-runs/20260905-takeover/failures/series-image-jxl-first/crash-logcat.txt)、[冷启动复现截图](../../artifacts/local-media-runs/20260905-takeover/evidence/series-image-jxl/retry-last-page-drag.png)。

### P2：动画 GIF / WebP 不能双指缩放和平移（4 个用例）

系列与单文件 GIF、动画 WebP 均能播放，但双指外扩后尺寸不变，随后横向拖动触发翻页或“前面没有啦”，没有平移放大的图像。四项均冷启动复测，同一注入手势用于静态 JPEG/PNG/WebP/AVIF/HEIC/HEIF/JXL 可以缩放。

定位范围为 `ReaderPageImageView` 的 PhotoView 动画图片路径和父级手势拦截，具体原因未定。动画 WebP 原矩阵未要求缩放，此次补充两项失败检查。

证据：[单文件 GIF](../../artifacts/local-media-runs/20260905-takeover/gif-retry.jpg)、[系列 GIF](../../artifacts/local-media-runs/20260905-takeover/series-gif-retry.jpg)、[单文件 WebP](../../artifacts/local-media-runs/20260905-takeover/individual-webp-retry.jpg)、[系列 WebP](../../artifacts/local-media-runs/20260905-takeover/series-webp-retry.jpg)。

### P2：系列 EPUB 最后目录项无法进入下一册（1 个用例）

在 `[Public Test] Alice and Formats` 的 `01 Alice Public Domain` 中选择最后目录项 `THE FULL PROJECT GUTENBERG™ LICENSE`，再点击可用的“下一章”，仍停留同一许可段（91%），未进入实际存在的 `02 Alice KF8`；冷启动复测一致。

`EpubReaderViewModel.adjacentTocEntries` 对同资源锚点的识别、`EpubReaderActivity.navigateAdjacentChapter` 先目录项后相邻册的选择是待调查范围，尚未证明具体修复方式。正文目录选择、页内导航、段落恢复及直接抵达许可末页通过。

证据：[最后目录项](../../artifacts/local-media-runs/20260905-takeover/evidence/series-book-epub/last-toc.png)、[跨册复测](../../artifacts/local-media-runs/20260905-takeover/evidence/series-book-epub/retry-next-book.png)。

### P2 偶发观察：大 EPUB 首轮触摸翻页无响应（1 个用例）

正确打开 `17机翻` 后，首轮在封面和目录定位后的正文中，多次点击/滑动不推进页码；设置中已确认“从左到右”，目录和设置仍可使用。冷启动后点击可以推进，随后收起菜单后的连续五次滑动进入不同正文页，最终第 22 页；返回、详情菜单、同进程重开均正常。

作为附加 `touchPageNavigation` 失败保留，**尚无稳定复现步骤或确定根因**，不能把此观察直接归因于大文件性能。原始四项性能检查在复测中完成并通过。第一次只有画面或菜单变化的五次操作没有计为连续翻页成功。

证据：[首轮正文触摸](../../artifacts/local-media-runs/20260905-takeover/epub-body-navigation.jpg)、[冷启动点击恢复](../../artifacts/local-media-runs/20260905-takeover/epub-navigation-retry.jpg)、[五次真实翻页](../../artifacts/local-media-runs/20260905-takeover/epub-five-swipes-retry.jpg)、[返回与重开](../../artifacts/local-media-runs/20260905-takeover/epub-responsive.jpg)。

另有可用性观察：单文件图像/归档详情的章节名称为空，只剩日期和阅读标记；可以从该行打开，但入口难识别。未将此项额外计为格式功能失败。

## 性能采样

计时从点击已定位的章节行开始，以人工确认的首个可读截图为准，含 ADB 截图开销。三个用例的清单超时预算均为 90 秒。PSS 是打开及翻页期间离散采样的**应用主进程**最大值，不是瞬时峰值，也不含全部 WebView 子进程。

| 样本 | 文件大小 | 首可读画面采样上界 | 主进程 PSS 采样最大值 |
|---|---:|---:|---:|
| 大 CBZ | 284.5 MiB | 2.406 s | 238.8 MiB |
| 大 PDF | 99.7 MiB | 2.359 s | 273.3 MiB |
| 大 EPUB（17机翻） | 12.5 MiB | 5.250 s | 276.3 MiB |

三者均检查五次实际翻页及返回/菜单/重开。CBZ 先缩小到适配尺寸，避免把自动放大后的平移误认为翻页。EPUB 的首轮异常如上保留。原始毫秒采样边界、字节数和 PSS 在各用例 `performance-review.json`、`*memory-samples.json` 中。

## 通过内容与验证边界

- 8 种归档格式的两种组织方式可加载；空 RAR/CBR 的两种组织方式均能提示没有图片并回到可操作界面。系列 CBR 跨入空 RAR 时显示预期无图片错误，章节顺序正确。
- 静态图片可显示、缩放和平移，旋转后可继续使用；动画播放正常，缩放失败单独记录。
- TXT、PDF、DjVu/DJV、原生 EPUB 均检查了实际内容、前后导航及冷启动恢复。长篇正文补证包括 TXT 第 41/84 页、PDF 第 108/208 页、原生 EPUB 第 63/131 页的相同内容；页数依赖本机排版，不作为云端进度。
- 两种独立图片 EPUB 回归均通过：首屏适配、预览缩放/关闭、下一页标记和恢复。冷启动视频按 30 fps 提取并检查图像出现前后的过渡帧，未观察到原尺寸显示后再缩小；此结论受采样分辨率限制。
- 归档样本多为两页，原验收允许恢复误差一页，末页重开首页也可能满足条件，因此这些 PASS 不能独立证明长篇中间页恢复。单图没有非首页进度。
- 编码样本以英文为主，不能据此声称覆盖 GB18030 的完整中文字符范围。TXT/MOBI 的“目录”是书籍/册列表，不能等同于内部正文目录。
- 这是单一 Android 17 软件渲染模拟器的 debug APK 验证；不包含墨水屏真机残影/刷新、其他 Android 版本、release/R8 或全 App E-link 体验验收。

## 构建与单元测试

下列命令通过，相关 8 个测试类合计 **30 项单测，0 失败、0 错误、0 跳过**。单测通过不覆盖上述设备端失败。

```powershell
.\gradlew.bat spotlessCheck :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest --tests 'koharia.document.*' --tests 'koharia.media.LocalMediaFormatsTest' --tests 'koharia.epub.EpubImage*' --tests 'koharia.epub.EpubLocatorProgressStabilityTest'
```

日志：[格式与编译](../../artifacts/local-media-runs/20260905-takeover/gradle-validation.log)、[相关单测](../../artifacts/local-media-runs/20260905-takeover/focused-unit-tests.log)、[单测计数](../../artifacts/local-media-runs/20260905-takeover/unit-test-summary.json)。本次没有 Kotlin/XML/资源修改，无需执行格式写入。

## 状态恢复与交付

测试结束后，核对章节 ID、所属书籍和 URL，将原任务保存的 58 个测试章节的 `read`、`bookmark`、`last_page_read` 恢复并逐项验证；恢复前另存当前值备份。没有整库回滚。原任务未备份原生 EPUB locator 或阅读历史，这两项仍保留测试期间的状态，不能声称阅读状态完全恢复。

[恢复结果](../../artifacts/local-media-runs/20260905-takeover/progress-restore-result.json)。样本、手动下载和应用数据未清空；未提交、推送或修改产品代码。原有测试脚本保留，本次增加报告和 README 入口；采集辅助工具、截图、视频及日志留在上述 artifacts 目录。
