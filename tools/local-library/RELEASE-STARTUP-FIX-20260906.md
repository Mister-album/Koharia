# 0.4.5 发布包启动崩溃修复

## 原因与修改

实机 app.koharia 0.4.5 在 AndroidX Startup 初始化 WorkManager 时抛出 `NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []`，尚未进入应用页面或书库迁移。

此前为兼容部分厂商系统，将 WorkManager 2.11.2 调整为 2.10.5，其 Room 依赖也从 2.7.0 变为 2.6.1。Room 2.6.1 的消费者规则仅保留数据库实现类，没有显式保护反射使用的无参构造函数。直接检查实机发布 APK 的 DEX，确认该构造函数缺失。

`app/proguard-rules.pro` 现在显式保留 RoomDatabase 子类及其 public 无参构造函数；保持现有 WorkManager 兼容版本和 Release/FOSS 的 R8 裁剪。

## 发布包验证

- `spotlessCheck`、`:app:compileDebugKotlin` 通过。
- Release 使用线上普通版参数 `-Pinclude-telemetry -Penable-updater` 构建。首次在 Crashlytics 符号上传阶段因连接超时失败，随后仅排除 `:app:uploadCrashlyticsMappingFileRelease`，构建通过；未禁用遥测代码或 R8。
- FOSS 使用 `:app:assembleFoss -Penable-updater` 构建通过。
- SDK apkanalyzer 检查最终 Release ARM64 APK 和 FOSS 通用 APK，均存在 `.method public constructor <init>()V`。
- 模拟器先安装并启动 GitHub 官方 0.4.2 x86_64 普通版，再以 `adb install -r` 覆盖至修复后的 0.4.5；版本码由 9 变为 10，首次安装时间不变，强制停止后冷启动成功，进程持续存活且本次 PID 的 crash 缓冲区为空。
- 实机 Android 16 / Xiaomi 2106118C：修复包与已安装包签名 SHA-256 相同，以 `adb install -r` 覆盖。首次安装时间仍为 2026-05-25，未清空应用数据或卸载。两次冷启动均进入 MainActivity，对应进程持续存活且 crash 缓冲区为空。
- FOSS 通用 APK 在模拟器全新安装后冷启动成功。

普通版实机修复 APK：`app/build/outputs/apk/release/Koharia-v0.4.5-release-arm64-v8a.apk`。

SHA-256：`33e5b7a95bcd08ec57aa0dd0e1e583eaf79a172cf0f32d19478a7432450d2985`。

## 证据与范围

诊断、最终 APK 的构造函数输出、构建日志、升级前后包信息和对应进程崩溃记录保存于 `artifacts/v0.4.5-startup-*`。最初损坏 APK 的 DEX 输出为 `artifacts/v0.4.5-workdatabase-dex.txt`。

此次验证覆盖发布包实际启动、普通版 0.4.2 升级和用户实机已有数据下的启动；未自动操作书架或阅读内容，书库及阅读记录的视觉确认留给用户。FOSS 验证为全新安装启动，未声称覆盖 FOSS 旧版本升级。

版本仍为 0.4.5 / versionCode 10，修改未提交、推送或替换 GitHub 发布资源及标签。后续发布验证应包含启用 R8 的 APK 覆盖升级和冷启动，不能仅依赖 Debug/单元测试及编译成功。
