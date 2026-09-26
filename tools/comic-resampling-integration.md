# 高分辨率区域解码 + Mitchell 接入说明

本轮将 [技术验证报告](comic-resampling-evaluation.md) 的高分辨率区域输入方案接入漫画阅读器。设置入口为「设置 → 漫画阅读 → 抗摩尔纹」以及漫画阅读器「更多设置 → 阅读器」。开关默认关闭；启用后的默认阈值为 50%，两个入口使用同一份共享阅读偏好，修改开关或阈值会刷新当前阅读页面。

## 启用阈值

阈值可选 25%、33%、50%、75%、100%，指图片的实际显示尺寸与原始像素尺寸的线性比例，不是像素总数或屏幕 DPI。例如原图宽 2160 像素显示为 1080 像素时为 50%，包含边界；100% 表示所有缩小比例，原尺寸及放大不滤波。条漫的父容器缩放也计入比例，裁边/旋转/双页按各自传入阅读视图的图片坐标计算。

判断依据是当前显示比例，而非 tile 的离散采样档位。即使缩放仍处于同一档位，跨过阈值也会切换区域解码方式。替换期间保留已有图片块，丢弃过时任务结果；手势结束后完成所需清晰度的加载。它是用户选择的保守触发条件，不是对内容是否存在摩尔纹的自动检测。

超过阈值时跳过高分辨率补采样和 Mitchell，使用原 codec 的普通整数采样。仍保留启用开关时的区域解码器、编码字节和视口校准分块结构，因此不承诺与完全关闭开关具有相同内存占用、耗时或任意缩放下逐像素相同的显示。

## 实现范围

- JPEG、PNG、静态 WebP 的原始图片流进入 SSIV 分块显示。在目标分辨率之上保留至少一档解码输入，再用固定 Mitchell（B=C=1/3）缩小。原图分辨率不足两倍目标时直接解码原始像素：25% 对应 sample=2，33%/50%/75% 对应 sample=1。
- 继续使用 SSIV 的可见区域和缩放层级，不生成整页预缩小图。适屏档位按视口校准；放大到 1:1 后不再滤波。各块在同一幅图片的坐标网格上采样，输入含滤波边缘，最终绘制按像素对齐并覆盖双页外边界。
- 动画、Coil 兜底格式、直接传入的 Bitmap 不使用这条高分辨率区域路径。AVIF/HEIF/JXL 等保留原有解码器处理，不能宣称已获得同样的抗摩尔纹效果。
- 每次最多两个区域任务同时工作；单次输入 Bitmap 预检上限 2 MP，输出 tile 上限 1 MP，内部按 256×256 输出块处理，基础层约束为约 2 MP。这些数值不是整个进程的内存上限：编码后的原文件、原生 codec 内部缓冲、其他页面及已缓存 tiles 另计。
- JPEG/PNG/WebP 保留编码字节，每块使用独立的现有 ImageDecoder 并及时 recycle，释放颜色转换对象、避免多线程共享原生可变状态。代价是重复读取编码字节与解析元数据，裁边时还会重复边界检测。没有升级或替换 codec 版本。
- 继续传入当前显示 ICC 配置。对现有 decoder 输出的直通 RGBA 做预乘后滤波，保持 Android Bitmap 的透明度约定；不新增 gamma 转换或减淡处理。
- 取消检查覆盖排队、区域解码之间、滤波前后；切页/旋转使用 generation 丢弃并回收旧结果。普通分配或滤波失败尝试原有采样档位，不发布半张 tile。

## 依赖与局部补丁

`reader-image-view/` 从 SSIV `66e0db195d1e41436b8bc3a22fea551e5d457db8` 引入，保留上游包名和 Apache-2.0 许可，加入 decoder 工厂、可取消滤波接口、视口档位和旧结果回收。关闭开关时仍用原 decoder 与档位策略。

`stb_image_resize2.h` 固定在 `2c980bb59875b0d32144a71867fbdebb2f77cd20`（v2.18），MIT/公有领域许可随源码和应用资源保留。只有一处算法库补丁：横纵 sampler 复用必须同时满足输入尺寸相同。未修补的 subrect 路径可把 268×262 输入的横向边界用于纵向，导致末行越界；设备回归覆盖该几何条件。

WebP 原有区域 decoder 会把裁剪尺寸缩到整数目标尺寸。输入矩形保持偶数起点、尺寸为 sample 的整数倍，避免奇数边缘让每块的缩放比例不同。奇数末行/列在 sample>1 时遵守整数缩采样边界，不承诺保留每个原始边缘像素。

## 验证与证据

验证环境为 Android Studio `emulator-5554`，Android 17 / x86_64 / 16 KB 内存页，Debug 隔离包。

- `spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin` 和 3 项 `RegionResamplingPlanTest` 通过（`final-build-unit.log`）。
- 四种 ABI 的 fixture APK 和 instrumentation APK 构建通过（`device-build-final.log`）。
- 15 项设备测试通过（`device-tests-8.log`，83.79 s）；最后加入输入大小预检和屏幕/完整区域像素比较后，定向复跑 8 项解码与阅读测试通过（`device-tests-final.log`，23.883 s）。双页显示代码在两次运行间没有变化。
- PixelCopy 的适屏输出与完整区域滤波在四种比例下最大红通道差 ≤2/255，验证分块绘制没有额外改变采样位置；这一对照使用不透明灰度网点样本。

以下质量对照使用所有缩小比例均滤波（现对应阈值 100%），不是默认 50% 阈值在 75% 时的行为。1024² 周期网点在本次真实阅读视图中的低频干扰指标（8×8 均值的标准差）：

| 缩放 | 原显示 | 开启 Mitchell |
| --- | ---: | ---: |
| 25% | 9.90 | 2.58 |
| 33%（337/1024） | 8.51 | 5.50 |
| 50% | 9.85 | 8.50 |
| 75% | 18.11 | 17.46 |

指标仅衡量该周期样本的低频干扰，不是一般画质评分。33% 实际视口宽 337 像素，与离线报告的取整尺寸不同，不应将两份报告的该比例数字直接相减。

本轮开启后的单次首屏 ready 测量为 50/57/79/58 ms；它不包含等待绘制后的 400 ms 截图稳定时间，也不是帧耗时。关闭后的对应值为 228/167/59/127 ms，但预热状态、解码档位和软硬件 Bitmap 路径不同，不能据此宣称新路径普遍更快。

长图测试在 513×4097 PNG 的末端区域（513×1026 → 128×256）预热 8 次，再用 4 个调用线程、2 个处理许可执行 40 次。含许可等待、解码器创建、区域解码、滤波与回收的耗时 p50/p95/max 为 21.11/30.58/31.44 ms；运行前后 native heap 已分配量净增 37,696 bytes。它不是峰值内存，也不代表物理低端设备性能。

新增 `libkoharia_resampling.so` 的 Debug strip 后大小：ARM64 668,992 bytes，ARMv7 798,752 bytes，x86 655,264 bytes，x86_64 691,312 bytes。ELF LOAD 对齐为 16 KB；模拟器实际加载通过。上述数字只计该动态库，不是整包或发布包的体积增量。

原始日志、图像、越界复现程序均位于 `.test-artifacts/moire-integration/`，不会纳入版本控制。`emulator-final/quarter-comparison.png` 是未二次缩放的 25% 对照，`display.csv`、`region-performance.csv`、`native-growth.txt` 保存本次数字。

永久测试：

- `RegionResamplingPlanTest`：输入档位、奇数分块、WebP 对齐与极窄输入。
- `MitchellRegionDecoderDeviceTest`：PNG/JPEG/WebP 的 25%/33%/50%/75% 分块一致性（预乘通道误差 ≤2）、非方形输入回归、透明色和 1:1 保真、取消后重用、长图并发及原生分配释放、独立完整输入滤波对照。
- `MitchellReaderDeviceTest`：真实 ReaderPageImageView 的 PixelCopy 对照，以及快速替换页面、旋转、缩放和回收。
- `DoublePageActionsDeviceTest`：原有左右阅读/反转/保存检查，以及开启抗摩尔纹后的左右双页接缝和缩放检查。

## 阈值与阅读器设置增补验证

- `MoireReductionPolicyTest` 与原 `RegionResamplingPlanTest` 共 6 项单元测试通过，覆盖默认 50%、所有预设边界、100% 下原尺寸仍不处理、无效比例与旧偏好回退。
- Android Studio `emulator-5554` 共验证 19 项设备测试：2 项阈值视图测试、1 项设置交互测试、7 项解码测试、2 项阅读渲染测试、7 项双页操作测试。
- 阈值视图测试在同一采样层级内反复越过 50%，验证取消旧任务、替换期间保留旧图、返回阈值后恢复滤波和原尺寸旁路；条漫测试验证父容器缩放参与判断。解码测试将旁路输出与原 codec 的普通整数采样逐像素对照。
- 设置测试操作实际 Compose 开关和阈值选项，确认默认 50%、选择 75% 后关闭再开启仍保留选择，并确认活动 viewer 收到刷新通知。原四档质量对照和双页滤波回归显式设为 100%，结束后恢复原偏好。
- 首轮 19 项中有 1 项保存按钮查找超时。模拟器有两个显示屏，原测试只查活动窗口；测试查找已限定到隔离应用的各显示屏窗口，保留应用数据，随后 7 项双页测试全部通过。没有为此修改保存功能。
- `spotlessApply`、`spotlessCheck`、正常 `:app:compileDebugKotlin`、隔离包四 ABI 构建及 instrumentation 构建通过。日志在 `.test-artifacts/moire-threshold/`：`unit-build.log`、`device-build.log`、`device-tests.log`、`device-test-build-final.log`、`double-page-final.log`。首轮失败日志保留，不将其记录为一次性全通过。

## 长图网格修复

原 SSIV 网格算法用 `分块尺寸 + 分块数量 + 1` 估算最后一块的余量。在上限为 256 时，16384 像素及以上的边长在原尺寸采样层没有满足条件的块数，初始化会在主线程持续循环。每次初始化都会创建到该层，因此无需用户放大，也不受启用阈值保护。

现改为按解码尺寸上限直接计算块数，使用 64 位乘法计算均匀分布的源坐标边界，使相邻块共用边界、最后一块不积累余数。抗摩尔纹仍保留 256 像素分块上限；网格按阈值旁路时可能使用的较高普通解码分辨率计算，输入/输出内存预检保持不变。默认 decoder 和 LOD 选择策略不变，普通阅读也使用有界的网格计算。

`TileGridTest` 覆盖 16383/16384、20000/20001、100001 像素、不同采样档位及整数极限，校验边界完整、区间非空和解码尺寸上限。`LongImageTileGridDeviceTest` 使用真实 PNG 和 ReaderPageImageView，分别读取 257×20001 条漫与 20001×257 横图，在 50%/25% 阈值下检查加载、每层覆盖面积、实际 Bitmap 上限和放大后的末端显示。

修复后 `spotlessApply`、`spotlessCheck`、`:app:compileDebugKotlin`、8 项相关单元测试及四 ABI 隔离包构建通过。Android Studio `emulator-5554` 的 20 项设备测试全部通过（227.737 s），含上述长图、阈值切换、解码、原四档分块像素对照及双页操作回归。日志保存在 `.test-artifacts/moire-long-grid/unit-build.log`、`device-build.log`、`device-tests.log`。测试已恢复原偏好、删除自己生成的长图，并保留安装包和应用数据。

## 复现

使用 Android Studio 的 `emulator-5554`，隔离包 `app.koharia.dev.devicefixture`；不安装覆盖用户阅读包、不卸载或清空应用数据。APK 文件名按当前版本号读取 `app/build/outputs/apk/debug/output-metadata.json`。

```powershell
.\gradlew.bat spotlessApply spotlessCheck :app:compileDebugKotlin :app:testDebugUnitTest --tests koharia.reader.resampling.RegionResamplingPlanTest
.\gradlew.bat -PdeviceTestFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r <fixture-x86_64-apk>
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class koharia.reader.resampling.MitchellRegionDecoderDeviceTest,koharia.reader.resampling.MitchellReaderDeviceTest,eu.kanade.tachiyomi.ui.reader.viewer.pager.DoublePageActionsDeviceTest app.koharia.dev.devicefixture.test/koharia.testing.KohariaDeviceTestRunner
adb -s emulator-5554 pull /sdcard/Android/data/app.koharia.dev.devicefixture/files/moire-integration .test-artifacts/moire-integration/emulator-final
```

## 结论边界

这是可开关的生产接入，不能等同于原 issue 的所有图片都已复现或消除摩尔纹。当前证据主要是生成样本，未取得报告者原书页；没有真实低分辨率面板盲测、中文小字可读性评估、完整 ICC/HDR 测试和长时间耗电/热稳态测量。

按用户要求，最终界面验证使用 Android Studio 虚拟机。早期 ARM64 解码检查之后因锁屏停止实机界面测试，不报告实机端到端阅读性能。重复创建区域 decoder 的成本、超长图片的 codec 内部内存，以及非适屏连续缩放时的效果仍是后续优化重点；不以软件指标代替实际观看体验。
