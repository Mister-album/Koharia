# LANraragi 添加与编辑页统一（2026-09-10）

- 参考 KomgaServerSettingsScreen 和 LocalFolderSettingsScreen，使用共享设置项组件、分组标题、弹窗编辑、顶部帮助及固定底部保存按钮。
- 名称、地址、API Key 改为点击设置项编辑；名称/地址校验后确认。API Key 在列表中固定长度遮罩，编辑内容不进入保存的屏幕状态，允许留空或清除。
- 连接测试使用当前未保存的配置，只读取服务端信息和分类；展示版本及进度记录能力，不提前修改连接设置。
- 新增连接级默认分类和默认合集展示，保留 Archive 直接阅读/分页预览选择。默认分类对应 Category，合集展示对应 Tankoubon，未混淆两者。
- 展示本地缓存最后更新时间和离线用途；帮助说明 LANraragi 版本、地址子路径、鉴权及本地未读覆盖行为。
- 保存后配置生效；返回时若有修改，提供丢弃确认。取消新建只移除当前新建草稿，编辑取消不删除原连接。Provider 传入的标题覆盖现已生效。

验证：spotlessApply、spotlessCheck、:app:compileDebugKotlin 和 38 项 LANraragi 单元测试通过。

LanraragiSettingsDeviceTest 的 2 项设备测试通过：

1. 使用未保存的地址和测试 Key 验证连接；加载并选择默认分类，修改合集展示与打开方式；断言保存前配置不变、保存后书架读取默认值，其他连接设置不变；再次编辑并丢弃后原值仍保留。
2. 新建草稿修改后丢弃，只移除本次草稿，原连接列表保持不变。

目标为 Resizable_Experimental / emulator-5554（Android 17、x86_64、16 KB），仅使用 app.koharia.dev.devicefixture（Koharia Auto Tests）。应用与测试 APK 保留，未操作手动安装包及其书库数据。

截图保存在本机 .codex-work/lanraragi-settings/，测试结果在 app/build/lanraragi/device-results/koharia.lanraragi.LanraragiSettingsDeviceTest/。

## 保存流程简化（同日后续调整）

- 移除底部本地缓存信息与独立测试连接入口。
- API Key 标题不再带“可选”，仍允许留空或清除。
- 作品打开方式选项改为“直接进入阅读器”“先查看作品预览”。
- 点击保存时使用当前草稿检测连接，单次检测最多 10 秒（包含 HTTP 调用总时限）；成功直接保存，失败弹出“连接失败，是否仍要保存此连接？”。
- 取消保持草稿不写入配置；“继续保存”跳过检测并写入，允许离线地址或空 Key。默认分类使用既有分类缓存，新连接保存并同步后可选择。
- 保存失败仍显示错误提示，避免持久化失败被误认为已保存。

本轮通过 spotlessApply、spotlessCheck、:app:compileDebugKotlin；LanraragiSettingsDeviceTest 的 3 项设备测试通过，覆盖成功保存、失败取消/继续保存空 Key、取消草稿以及其他连接隔离。已检查更新后的设置页和失败提示截图。
