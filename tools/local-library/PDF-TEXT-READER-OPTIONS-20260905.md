# PDF 文字阅读器接入选型 · 2026-09-05

**后续方向已收敛：优先 PDFium，派生 EPUB 交给 Readium；不采用 TXT/MOBI 渲染器作为正式图文重排主线，MuPDF 暂不考虑。** 见[当前建议方案](PDF-REFLOW-READIUM-PLAN-20260905.md)。以下保留早期选型与实机抽样记录。

## 项目现状与实机验证

当前项目没有接入 PDF 文字解析/重排引擎。`DocumentEngines` 注册 TXT、MOBI、DjVu；PDF 由 `PdfPageLoader` 生成页面位图。`EpubReaderLauncher` 只对 EPUB/TXT/MOBI 等可重排扩展名启用文字阅读设置，直接修改路由不会自动获得 PDF 文本。

已用独立、只读的 Android API 探针检查实机上当前这本《邻家天使》第 1 卷 PDF。未安装新应用、修改文件或改变阅读位置。文件共 296 页，抽样结果如下：

| PDF 页号 | 提取字符数（含空白） | 中文字符数 |
|---|---:|---:|
| 1 | 125 | 53 |
| 10 | 427 | 342 |
| 100 | 386 | 292 |
| 165 | 389 | 302 |
| 166 | 346 | 248 |
| 250 | 433 | 334 |

这些正文样本有可用文字层，无需先 OCR。提取结果仍包含页眉、页码和排版硬换行；不能直接把返回字符串拼接后视为完整的电子书排版。[本机探针结果](../../artifacts/pdf-device-diagnosis/native-text-probe.txt)

## 可选方案

| 方案 | 能力 | 接入评价 |
|---|---|---|
| Android `PdfRenderer.Page.getTextContents()` | API 35 起提取页面文字；系统还提供图片内容接口 | 当前 Android 16 实机已验证可用，不增加第三方原生引擎；本项目最低 API 26，仍需要旧系统方案 |
| PdfBox-Android | `PDFTextStripper` 提取文字，可取得文本位置并组织输出 | 适合先让文字小说进入现有 TXT/MOBI 文字引擎；Apache-2.0；官方 README 当前基于 PDFBox 2.0.27，接入前须评估维护和样本兼容性 |
| PdfiumAndroidKt | Kotlin/协程包装，提供 `PdfTextPage`、文字、字符边框及 PDF 页面渲染 | 适合统一跨版本 PDF 文本/位置能力；包装层 Apache-2.0，PDFium 另含 BSD 许可；需验证 JNI 生命周期、APK 体积及 16 KB 内存页兼容性 |
| MuPDF | Java 绑定可输出结构化文字、行、字符坐标、HTML/JSON/Text，也提供渲染 | 图文结构处理能力较完整；AGPL 或商业授权，不应当作普通宽松许可证依赖直接引入当前 Apache-2.0 项目 |
| KOReader / K2pdfopt | KOReader 对固定版式 PDF/DjVu 提供重排，包括扫描页 | 适合作为复杂版面和扫描文档的实现参考；包含原生组件，图块/版面重排不等同于 TXT 式换字体，独立接入成本较大 |

原始资料：

- [Android PDF 文本接口，API 35 起](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page#getTextContents())。官方说明其文字内容按从左至右、从上至下返回；这不是多栏或竖排语义阅读顺序的保证。
- [PdfBox-Android 项目、许可证和 Android 要求](https://github.com/TomRoush/PdfBox-Android)，[官方文本提取示例](https://github.com/TomRoush/PdfBox-Android/blob/master/sample/src/main/java/com/tom_roush/pdfbox/sample/MainActivity.java)。
- [PdfiumAndroidKt 项目](https://github.com/johngray1965/PdfiumAndroidKt)，[PdfTextPage API](https://github.com/johngray1965/PdfiumAndroidKt/blob/main/pdfiumandroid/src/main/java/io/legere/pdfiumandroid/PdfTextPage.kt)，[许可证](https://github.com/johngray1965/PdfiumAndroidKt/blob/main/LICENSE)。
- [MuPDF Java StructuredText](https://github.com/ArtifexSoftware/mupdf/blob/master/platform/java/src/com/artifex/mupdf/fitz/StructuredText.java)，[授权方式](https://mupdf.com/releases)。
- [KOReader 功能说明](https://github.com/koreader/koreader)，[PDF 引擎接入](https://github.com/koreader/koreader/blob/master/frontend/document/pdfdocument.lua)，[K2pdfopt](https://github.com/koreader/libk2pdfopt)。

## 建议的实现方式

对当前“文字小说 PDF 使用文字阅读器”的需求，优先评估 **PdfBox-Android + 现有文本分页引擎**，覆盖项目支持的 Android 8 及以上设备；API 35 以上的系统提取接口可作为先行验证或另一后端。如果需要统一 PDF 渲染、字符定位与交互能力，则优先比较 PdfiumAndroidKt。以上排序为结合当前项目结构的工程判断，尚未对第三方库做性能排名。

建议实现 `PdfTextExtractor` 与 `PdfReflowDocumentSession`，让提取与显示分离：

1. **检测**：抽样多页，根据有效正文、可读字符比例和图片覆盖情况判断；不能只看扩展名或“存在任意文字”，也不能只检查封面。
2. **文字型 PDF**：提取正文，处理页眉页脚、断行、段落和跨页连接，接入现有 16 sp 默认字号、字体、行距和主题设置；保留插图页。
3. **漫画/扫描/复杂版面**：保留原版 PDF 阅读；仅在 OCR 或重排能力明确可用时另行启用对应模式。
4. **用户覆盖**：每本书提供“文字阅读 / 原版 PDF”，识别结果不确定时保留原版，并记住用户选择。
5. **进度**：保存原 PDF 页号、页内文字偏移到重排位置的映射。不能把字号变化后的显示页码当成原 PDF 页码写回同步。
6. **原版兜底**：无论选择哪个解析器，原版 PDF 位图都必须具有不透明纸面，独立修复已经证实的透明底黑屏问题。

本轮按“项目缺少对应解析器时介绍开源方案”的要求，完成代码检查、开源选型及实机文本提取验证。没有新增应用依赖或切换正式阅读器路由。
