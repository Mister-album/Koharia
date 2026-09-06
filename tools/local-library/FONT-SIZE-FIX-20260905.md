# TXT / MOBI 默认字号修复 · 2026-09-05

已修复 TXT、MOBI、PRC、AZW/AZW3 共用文本渲染器的字号换算，并按用户反馈将默认基准从 24 sp 调整为 **16 sp**。阅读器倍率仍为 100%；通过 Android `TypedValue.applyDimension(COMPLEX_UNIT_SP, …)` 转换，支持屏幕密度、阅读器倍率和系统字体缩放。420 dpi、系统字体倍率 1.0 时，默认绘制字号为 42 px，150% 阅读器倍率对应 63 px。

原实现位于 `TextPaint.apply` 中，未限定的 `density` 实际读取 `TextPaint.density`（默认 1），遮蔽了外层屏幕密度。420 dpi、系统字体倍率 1.0 时，本应为 63 px 的字号被绘制成 24 px。新实现使用单独的画笔工厂，明确设置 `TextPaint.density`，并为每份文档保存显示参数快照，使分页和最终绘制使用一致的参数。

验证结果：

- `spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin`、Debug APK 和设备测试 APK 构建通过。
- `koharia.document.*` 的 10 项 JVM 单测通过。
- 新增 5 项设备测试通过：默认字号与阅读器倍率、系统字体倍率 1.3/2.0、TXT/MOBI 实际位图字高、字号变化后的重新分页与恢复。
- 首次换算修复时，新增的 TXT/MOBI 字高测试在旧安装包上两项均失败（24 sp 基准预期字高 45 px，实际 17 px），证明能检出旧问题。本轮将预期同步为 16 sp 对应的实际位图字高，5 项设备测试再次通过。
- Android 17 / SDK 37、420 dpi 模拟器上的真实 TXT/MOBI 显示检查通过。测试涉及的两本书的已读、书签、页码字段已恢复，未更改设备系统字体设置或阅读器字号偏好。

[16 sp 显示效果](../../artifacts/local-media-runs/20260905-takeover/font16-verified-readers.jpg) · [旧版失败日志](../../artifacts/local-media-runs/20260905-takeover/font-before-device-tests.log) · [16 sp 设备测试](../../artifacts/local-media-runs/20260905-takeover/font16-verified-device-tests.log) · [构建和单测](../../artifacts/local-media-runs/20260905-takeover/font16-build.log)

字号调整会重新分页；页数由当前设备、系统字体缩放和阅读器排版设置共同决定。

[最新 Debug APK](../../app/build/outputs/apk/debug/Koharia-v0.4.2-7898-debug-x86_64.apk)

SHA-256：`b0da994526808e350aba4a8620fd8fbd8a751f6bdce3de4e3bb2e46ce7f5d6be`。已核对模拟器安装包与本地 APK 一致。

本次只修改共用文本画笔初始化并新增字号设备测试，保留此前其他修复；没有提交或推送。未验证 release/R8 或物理设备。
