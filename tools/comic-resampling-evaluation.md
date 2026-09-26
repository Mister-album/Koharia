# #96：漫画缩小抗摩尔纹技术验证

验证日期：2026-09-25。以下记录的是研究阶段：当时只增加可选设备证据测试，未修改生产图片依赖、SSIV、native 解码器或正式设置。**本报告不代表 #96 已修复。**

后续已按用户要求接入高分辨率区域解码 + Mitchell，生产变更与新增验证见 [接入说明](comic-resampling-integration.md)。下文保留研究阶段的方法与结论。

## 结论与候选顺序

1. **优先验证 stb_image_resize2 Mitchell + 更高分辨率区域解码。** 合成 PNG 在 25% 缩小时，当前实际显示和已缩采样结果再滤波都出现明显干扰；从半分辨率或原始像素缩小明显改善。它需要解码器接入和带边缘的分块支持，不能只在最终 Canvas 上增加插值。
2. **libvips Lanczos3 保留为离线质量参考。** 它不是像素真值，也不直接用于 Android 生产包。较锐的输出与较少混叠之间存在取舍。
3. **libyuv ARGBScale box/bilinear 保留为性能基线。** 本机速度明显领先，但 25%/33% 的网点、斜线和小字表现不稳定；`kFilterBox` 的实际路径会根据缩放比例简化，不能当作任意比例的理想面积积分。

Mitchell 也不是所有比例的最佳选择：25% 的英文小字已只有约 4.5 像素高，不能宣称可读性完全恢复；33%/50% 比当前显示更柔和。没有引入减淡、锐化或全图预缩放来掩盖这些差异。

## 来源与接入约束

| 项目 | 固定版本 / 本轮用途 |
| --- | --- |
| [stb_image_resize2](https://github.com/nothings/stb/blob/2c980bb59875b0d32144a71867fbdebb2f77cd20/stb_image_resize2.h) | commit `2c980bb59875b0d32144a71867fbdebb2f77cd20`，header v2.18；默认缩小 Mitchell，B=C=1/3 |
| [libyuv filtering](https://chromium.googlesource.com/libyuv/libyuv/+/2124173f34062726761163018f79df085d911460/docs/filtering.md) | commit `2124173f34062726761163018f79df085d911460`；ARGBScale box / bilinear |
| [libvips resize](https://www.libvips.org/API/8.17/method.Image.resize.html) | 实际运行 Windows x64 8.18.6，`resize --kernel lanczos3`；官方 8.17 文档用于解释 shrink + resample 流程 |
| [SSIV 固定解码器入口](https://github.com/tachiyomiorg/subsampling-scale-image-view/blob/66e0db195d/library/src/main/java/com/davemorrissey/labs/subscaleview/SubsamplingScaleImageView.java) | 当前版本直接构造 Decoder；存在接口不等于已有可用的依赖注入入口 |
| [image-decoder 缩采样](https://github.com/mihonapp/image-decoder/blob/e03b81e18a/library/src/main/cpp/row_convert.cpp) | 部分转换路径采用有限像素平均；本次以 PNG 证据定位问题，不能直接外推全部 JPEG/WebP 解码器 |

stb 使用 `stbir_resize_uint8_linear`，对 8-bit 通道数值直接滤波；这不是先把 sRGB 转为线性光再滤波。本轮输入完全不透明，使用 RGBA premultiplied 路径。ICC、HDR、透明预乘边缘和灰阶面板的实际光学表现仍需后续验证。

## 环境与方法

- 当前解码与显示：`emulator-5554`，Android 16、x86_64、16 KB page size，隔离包 `app.koharia.dev.devicefixture`。
- Native 性能：ADB 明确选中 `3ef7b819`，Xiaomi 2106118C / SM8350（Snapdragon 888），Android 16，arm64-v8a。只运行独立二进制，没有操作用户阅读应用或数据。
- 构建：NDK 29.0.14206865、clang、Android API 26、`-O2 -ffp-contract=off`，静态 C++ runtime；libyuv 使用其运行时 CPU 分派，禁用 SVE/SME。
- 5 类输入：1024×1024 周期网点、斜线、18 px 英文小字、彩色梯度与网点，以及 1024×8192 长条图。均为脚本生成 PNG / PPM，未使用报告者原书页。
- 比例：25%、33%、50%、75%，整数尺寸四舍五入。网点周期 7 px，斜线周期 9 px。
- Native 每组合 1 次预热、9 次测量，共 120 组合。保存各组合中位数、最大值和进程峰值 RSS。libvips 共 20 个参考输出，其 Windows wall time 包含启动与 I/O，不与手机 kernel 耗时排名。

比较路径：

1. `display`：真正的 ReaderPageImageView / SSIV，经 PixelCopy 截取，4 类方图 × 4 比例，共 16 张。
2. `sampled`：当前 ImageDecoder 的目标档位缩采样，再由 stb 缩至目标尺寸。
3. `higher`：比上述高一档的 ImageDecoder 输入，再由 stb 缩至目标尺寸。
4. `stb`、`reference`：原始像素离线缩小；另有 Canvas、libyuv 和带边缘的 stb 条带对照。

长条图覆盖解码、缩小和分块数值对照，没有声称截取了整个长图的屏幕显示。解码实测在模拟器、native 滤波实测在手机，不能将两者相加后当作手机端到端帧耗时。

## 画质证据

证据目录为仓库下 `.test-artifacts/issues-96-98-99/resampling/`，其中 `dots-comparison.png`、`text-comparison.png`、`lines-comparison.png`、`color-comparison.png` 是未二次缩放的局部像素裁剪拼图；查看时应使用 100% 比例。原始各路径输出也保留在 `android/`、`native/`、`reference/`。

25% 周期网点的指标如下。低频指标是输出灰度按 8×8 平均后标准差，仅用于这个固定周期样本；越低不是一般图像质量越好。MAE 相对于 libvips 参考，不是真值误差。

| 路径 | 低频标准差 | 对 libvips 的 MAE（0–255） |
| --- | ---: | ---: |
| SSIV 实际显示 | 9.90 | 56.42 |
| 当前采样 + stb | 9.90 | 56.42 |
| 高一档解码 + stb | 2.58 | 1.31 |
| 原始输入 + stb | 2.84 | 2.33 |
| 原始输入 + libyuv box | 6.11 | 24.92 |
| 原始输入 + libyuv bilinear | 9.88 | 56.26 |

- 网点：25% 改善明显；33% Mitchell 仍留有周期结构，不能宣称彻底消除摩尔纹。
- 斜线：25% 当前显示出现强烈低频假纹；Mitchell 更接近均匀覆盖率。50%/75% 时仍保留应有的线条节奏，不能把全部纹理抹掉作为目标。
- 小字：25% 当前路径的断笔和闪烁结构明显；高分辨率输入降低这些伪影，但小字尺寸本身限制可读性。33%/50% Mitchell 相比当前路径更柔和；75% 差距较小。未做中文小字 OCR 或读者盲测。
- 色调：彩色合成页中，stb 与显示路径的整体灰度均值差约 0.10–0.68 / 255；小字页差小于 0.24 / 255。本轮未观察到整体明显偏色，但均值不能代替色彩管理验证。
- 振铃：人工对照未看到该组样本产生明显新光晕；黑白极值会裁剪数值，不能据此声称数学上无振铃。
- 分块：64 输出行一条带，输入各侧留 `3 × 输入/输出比例` 像素边缘，使用 subrect 保持全图采样相位。全部 20 组合与完整图 Mitchell 最大通道差为 1 / 255；未观察到接缝。本测试仍把全图输入载入内存，只验证滤波边缘和相位，不证明生产分块解码内存已解决。

## 性能与体积

下表是各组合 9 次测量的中位数范围；最大单次列用于观察调度抖动。RSS 是独立测试进程峰值，包括输入 RGB、RGBA、输出、运行时等，不是算法额外内存或 Reader 内存预算。

| 算法 | 1024² 中位数 ms | 1024×8192 中位数 ms | 最大单次 ms（方图 / 长图） | 峰值 RSS KiB（方图 / 长图） |
| --- | ---: | ---: | ---: | ---: |
| stb Mitchell | 3.492–6.354 | 28.057–46.259 | 7.930 / 46.615 | 13,764 / 79,940 |
| libyuv box | 0.246–1.138 | 2.496–6.254 | 2.354 / 6.578 | 13,392 / 79,992 |
| libyuv bilinear | 0.175–1.088 | 1.744–6.322 | 1.173 / 6.651 | 13,388 / 79,572 |
| stb 条带 | 3.562–6.346 | 29.465–47.786 | 8.165 / 47.902 | 13,716 / 79,876 |

合并三种实现的 ARM64 测试 executable：未 strip 5,893,232 bytes，strip 后 562,944 bytes。含测试读写与静态 runtime；**不是单个候选的 APK 增量**。尚未实现 JNI / ABI 打包，因此没有伪报生产包增量。

手机测量未锁定 CPU 频率，未做热稳态持续翻页或电量测试。表中范围来自本次工作负载，不应外推低端设备或墨水屏。

## 本工作区复现

研究代码、固定版本源码、原始输入和数据都保留在 `.test-artifacts/issues-96-98-99/resampling/`；它们被 Git 忽略，没有将第三方实验实现纳入生产依赖。长期设备证据入口为 `ComicResamplingEvidenceDeviceTest`，没有准备输入时会跳过。

1. 安装本机 Pillow/numpy 环境，运行 `generate.py`。脚本使用 `C:/Windows/Fonts/arial.ttf`，改变字体会改变小字基线。
2. 固定下载上表 stb header、libyuv 源码；准备 libvips 8.18.6 Windows 便携包至 `vips/vips-dev-8.18/`。`build_native.py` 中 NDK 路径应与本机一致。
3. 运行 `build_native.py` 构建独立 `bench-arm64`。将输入 PNG 推入明确选定模拟器的 `/sdcard/Android/data/app.koharia.dev.devicefixture/files/issues-96-98-99/resampling/inputs/`。
4. 用 `-PdeviceTestFixture=true` 构建 fixture 与测试 APK，使用 `adb -s emulator-5554 install -r`，再执行以下证据测试并拉取该外部文件目录的结果到 `android/`。不得卸载应用或清空数据。

```powershell
adb -s emulator-5554 shell am instrument -w -e class eu.kanade.tachiyomi.ui.reader.viewer.ComicResamplingEvidenceDeviceTest app.koharia.dev.devicefixture.test/koharia.testing.KohariaDeviceTestRunner
```

5. 在明确选定 ARM64 设备的 `/data/local/tmp/koharia-issues-96-98-99/` 准备 `bench`、`inputs/`、`output/`。检查 `run_bench.py` 中设备序列号后运行；它会运行 120 次组合并拉回原始输出，然后生成 vips 参考。
6. 运行 `analyze.py`、`inspect_details.py`，读取 `native-metrics.csv`、`android/decode.csv`、`quality.json` 及拼图。计时和图像生成脚本均使用明确的输出路径。

## 下一轮接入代价

需要先给现有 SSIV 增加可注入解码器/后处理契约，明确输入区域、输出采样相位、边缘扩展、取消及 bitmap 生命周期；再把足够高分辨率的区域交给 Mitchell。缓存键必须含采样比例、算法和区域，避免旧 tile 与新 tile 混用。随后验证非整数缩放、快速缩放与拖动、超长图、JPEG/WebP、ICC/透明图和真实低内存设备。

如果无法取得高分辨率区域输入，当前证据不支持仅靠最终绘制滤波解决 #96。全图预缩放会绕过 SSIV 的内存边界，本轮不采用。
