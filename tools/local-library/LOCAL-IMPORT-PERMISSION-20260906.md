# 虚拟机本地导入目标被误判为不可写

## 原因

虚拟机 source_1 的自动创建目录保存为 `content://com.android.externalstorage.documents/tree/primary%3Akoharia/document/primary%3Akoharia`，通过相对路径 Comics、Books 定位两个书库目录。

Android 持久授权记录则是 `content://com.android.externalstorage.documents/tree/primary%3Akoharia`，读写权限均已授予。两种 URI 表示同一树授权下的目录，但 `mediaImportDestinations()` 直接比较字符串，因不相等排除了全部目录，继而显示“没有可写且支持全部所选文件的目标位置”。

## 修复

解析已配置的实际目录后，通过 `UniFile.canWrite()` 调用 Android／文件系统的权限判断，兼容树授权、树内 document URI、父目录授权及普通文件路径。保留格式筛选和只读目录过滤，不修改现有书库配置，也不补授权限。

## 验证

- 格式检查、Kotlin 编译、Debug APK 与测试包构建通过；已安装到虚拟机。
- 3 项设备测试全部通过，无跳过：现有 source_1 的全部目标正常识别；在现有父目录授权下以 document URI 配置临时目录并成功导入 TXT；普通可写目录可用且只读目录被排除。
- 真实导入测试使用独立临时目录和自建文件，测试后清理；用户书籍未用于写入验证。
- 日志：`artifacts/local-import-permission-build-final.log`、`artifacts/local-import-permission-device-tests.log`、`artifacts/local-import-permission-install-emulator.log`。

用户可重新选择文件导入，无需重建书库或重新授予现有目录权限。
