# drag-all 0.1.11

**2026-11-16 暂停现场：手机安装 0.1.10，其原生提取组因误用 JADX 字段名而未加载；
本地 0.1.11 已改为 DEX 真实字段 `vb.p.n`，并扩展三个接口兼容的 TG 客户端，构建和检查通过，尚未安装。
0.1.9 的无客户端 hook 文字拖拽已获用户确认；图片回传修复仍待实机验证。**

接续请先读 [HANDOFF-2026-11-16.md](HANDOFF-2026-11-16.md)，其中区分设备现场、已确认功能、回归、待安装产物及授权边界。用户暂时离开，停止新的设备变更。

新增宿主侧 `NativeSelectionFeature`，仅在拖放直达处理 ExteraLess 请求期间，改用厂商已授权的
`generalizeGrab`。通过 vivo 自带表达式引擎按触点定位 `ChatMessageCell`，读取正文/说明，
用 `getPhotoImage().isInsideImage()` 判断是否按到图片，再把实际 Bitmap 写入已授权的 URI。
另有 PhotoViewer 当前图片规则，全屏手势能否进入宿主仍待实机验证。

**该候选方案不向 ExteraLess 加 Vector scope、不安装客户端 hook、不要求结束内容提取服务。**
厂商原有提取代码仍在来源应用内执行。拒绝秘密媒体、禁止转发的消息及未揭示剧透，
不以整条气泡或整屏截图充当精确图片。PiliBro 的精确提取仍保留旧客户端 hook。

本地构建、lint、44 类/119 成员签名和厂商原始权限配置核验通过；
`logs/host-validation-0.1.9.json`、`logs/native-route-static-0.1.9.json` 仅证明静态条件。
是否实际安装、生效与输出正确，以 `DEVICE-CHECK.md` 的部署记录为准。

面向本机 OriginOS 7 / Vector 2.2 的独立拖拽模块，包名 `dev.ganvivo.dragall`，使用 libxposed API 102。

**基础作用域为 `com.vivo.globaldragdrop` 与 `com.vivo.accessibilityenhance`，PiliBro 精确提取另选 `org.BroTech.gege.pilibro`；不选择 `system` 或 ExteraLess。** 内容提取服务拥有独立白名单，实机返回码 60006 确认只改拖放直达不够。模块仅接受内容提取服务的主进程，排除其 `:persist` 进程，并拒绝 system_server。没有桌面、最近任务、分屏或工作台 hook。

恢复历史：0.1.7 的 ExteraLess 直接注入导致启动 native 崩溃，已立即从 Vector 实际 scope 移除该包，客户端冷启动成功并持续运行为 PID 28717，系统关键 PID 不变。**不要重新选择 ExteraLess scope，也不要安装 0.1.7 进行该实验。** 0.1.8 起已从代码入口、推荐 scope 和 APK 中移除这条实验实现，原代码移到 `notes/experimental/` 供离线分析。当前设备已更新到 0.1.10，最新边界以上方接续记录为准。完整历史见 [DEVICE-CHECK.md](DEVICE-CHECK.md)。

已验证范围：内容提取服务在单次授权重建后成功加载通用白名单 hook；名单外的原生 TextView 已正确拖出文字，PiliBro 0.1.6 的按节点文本与局部图片替换已取得业务命中，用户反馈基本修好但图片识别仍需优化。模糊与系统分享已获用户认可，后续保持不动。Telegram 客户端的消息正文、图片与全屏图片精确提取仍未解决，不能用时间文本可拖或名单放行冒充功能完成。

## 功能

| 开关 | 默认 | 行为 |
|---|---|---|
| 放开应用拖拽 | 开 | 覆盖拖放直达入口/异步抓取配置，并在内容提取服务中放行来自拖放直达、业务为 dragdrop 的应用名单；保留原黑名单、调用方校验和用户开关 |
| 扩展设置中的应用列表 | 开 | 合并 PackageManager 可见的桌面应用；不改厂商缓存和用户原有应用开关；查询缓存 30 秒 |
| 加入系统分享 | 关 | 在超级拖拽菜单加入系统 chooser；使用居中的蓝色圆角分享图标；支持文本、单个/多个 content URI，保留读取授权 |
| 拖拽岛玻璃效果 | 关 | 把黑色背景改为深色半透明渐变，调节现有原生背景模糊；关闭时恢复记录的 shader 和模糊半径 |
| 背景模糊强度 | 30 | 范围 0–100，通过本机 `View.setMaterialBlurRadius(float)` 调节背景，不对文字或图标应用模糊 |
| 玻璃底色浓度 | 25 | 范围 0–90，单独控制深色底层透明度；两个滑条松手后同步 |

设置通过 libxposed Remote Preferences 同步。配置读取失败时关闭增强；首次成功读取空配置时采用上表默认值。模块 App 未连接 Vector 时禁用设置控件，不做“看似保存、实际只写本地”的兜底。

“重启当前作用域”按钮仅重建拖放直达的应用进程并打开其设置页。按钮可在配置服务未连接时使用，但执行前必须取得 root、只读核验 Vector 启用状态、包含拖放直达且没有上述三个包以外的 scope，并校验目标 UID、进程名和启动时间。只对精确 PID 发 SIGTERM；等待超时停止，没有 SIGKILL 或自动重试。**按钮继续拒绝系统 UID，不处理内容提取服务或 PiliBro 的进程**。新增的内容提取服务 scope 要等该服务的新生命周期才能装载。

开关控制已经装入目标进程的 callback，不需要为每次开关修改重启系统服务。正在执行的拖拽使用本次调用开始时的开关状态；更改会作用于下一次操作。关闭分享后，已经出现的本模块菜单项仍由本模块消费，不转交给厂商假 skill。

## 兼容边界

- 当前接受拖放直达 `4.1.3.0`、内容提取服务 `3.0.3.2`；此外检查方法参数、返回值和构造器。其他版本记录 `unsupported host version`，不安装业务 hook。
- 接受拖放直达的主进程及私有子进程，以及内容提取服务主进程；排除内容提取服务的 `:persist`、system_server 和其他包。
- 在 `onPackageReady` 使用最终 ClassLoader，并等 Application Context 就绪后安装。按真实锚点 Class 身份记录成功功能，避免委托加载器导致重复挂载；失败功能可在后续 ready 事件重试。
- 拖拽/设置列表使用可嵌套的 ThreadLocal 调用范围，不遍历调用栈。仅对已定位的 caller 去优化，避免小方法内联造成零命中。
- 每组先完整解析，再注册；全部注册成功前 callback 不改变行为，失败时逆序撤销本组。分享消费链先于菜单注入注册。
- 使用 PROTECTIVE 异常模式并保留原方法结果；这不是对系统组件稳定性的保证。
- 未启用代码热重载。配置开关与代码热重载是不同能力。

锚点已对 2026-11-16 从本机只读拉取的两个宿主 APK 与 framework.jar 复核，共 24 个类、67 个成员。具体证据见 [设备与宿主核对记录](DEVICE-CHECK.md)。PiliBro 已确认进入拖拽入口与异步抓取，但提取服务返回 60006；服务源码还把 Flutter 节点归为类型 8，并在拖拽抓取流程中拒绝它。当前没有伪造抓取成功，也没有把整个屏幕截图冒充所按内容，Flutter 适配尚未完成。

本机框架会在来源应用的内存里缓存“不支持此应用/Activity”的回复，最长 24 小时，并在发送新请求前拦截。修复加载后，已有此缓存的来源应用需结束进程后再打开；仅返回桌面或重建拖放直达进程无法清除来源应用的缓存。当前没有修改来源应用或 system_server 的 hook，也没有清除应用数据。

## 本地构建

需要 JDK 17+（本机使用 21）、Android SDK platform 36。Gradle Wrapper 固定 9.3.1，AGP 9.1.0；hook API 为 compileOnly `102.0.0`，配置通信使用 `service:101.0.0`（与仓库已运行的 XHook 路线一致）。本模块没有调用要求 service 102 的热重载接口。

Windows PowerShell：

```powershell
.\gradlew.bat --no-daemon exportDebugApk lintDebug
python .\tools\verify-apk.py .\dist\drag-all-0.1.8-debug.apk
python .\tools\verify-host.py .\apk\globaldragdrop-4.1.3.0.apk --framework .\apk\framework-device.jar --catcher .\apk\accessibilityenhance-device.apk
```

Gradle 自动解析 Android SDK（local.properties / `ANDROID_HOME` / 常见安装位置）；只有探测不到时才需要手动 `$env:ANDROID_HOME = '<你的 SDK 目录>'`。

产物 `dist/drag-all-0.1.8-debug.apk` 使用本机 Android debug 签名。后续覆盖更新需要相同签名；这是本地验证包。

## 验证与设备约束

本地构建、lint 和 APK 打包校验**不等于真机功能验收**。业务 hook 不执行 root 命令；仅用户主动点击作用域重启按钮并确认后，执行固定目标的 root 辅助脚本。

根 `AGENTS.md` §1 的设备红线适用。用户已在本次会话授权标准安装、启用和目标作用域进程重建；其他任务不能沿用此次授权。安装前必须确认亮屏解锁和用户在场，仅用标准 `adb install`，失败停止。禁止手机、框架或 daemon 重启，禁止 root `pm install`、`force-stop` 和 PACKAGE 广播。不要与 OriginTweaks、FuckDrag、DragDropPlus 的相同拖拽功能同时叠加。

PC 辅助工具 `tools/invoke-scope-helper.ps1` 默认 `-Mode preview`，只读核验；`-Mode restart` 使用 APK 内的同一脚本，需已获对应设备变更授权。预检失败不会结束目标进程。

后续验收应记录：目标版本与 APK 路径、目标进程、最终 ClassLoader、每组实际挂载方法/handle 数、命中及关闭开关后的行为。优先检查 Vector 留存的 `verbose_*.log`；本次落盘文件停在启动期，已另取得当前宿主 PID 的 `VectorModuleManager` 与 `DragAll` 加载记录。不能从陈旧文件缺少新记录推断未加载。业务日志 tag 为 `DragAll`，证据存入本目录 `logs/`，不要清理设备日志来验证。

日志含义：

| 日志 | 说明 |
|---|---|
| `module=... process=... framework=...` | 目标进程创建了模块实例 |
| `host version=... code=... apk=...` | 版本检查通过、Context 已就绪 |
| `<feature> registered ...` | 一个方法获得 hook handle，整组尚可能失败 |
| `<feature> installed hooks=N` | 该功能整组完成注册，不代表业务已命中 |
| `<feature> not installed` | 类/签名/安装失败，本组不启用 |
| `drag_gate:<package> hit=...` | 对应包的入口白名单闸门实际命中 |
| `drag_profile:<package> hit=... generic=...` | 对应包获得真实的 package 级配置；generic 表示原配置不存在 |
| `drag_capture:<package> hit=...` | 对应包进入异步抓取；仍不证明最终拖拽成功 |
| `catcher_gate:<package>`、`catcher_authorize:<package>` | 内容提取服务的拖拽应用白名单命中，以及保留其他原检查后的结果 |
| `catcher_nodes:<package> kind=... count=...` | 内容提取服务返回的节点类型与数量，不记录具体用户内容 |
| `island_glass hit=... native=... appliedRadius=...` | 是否找到原生背景模糊对象及设置后实际半径 |
| `share_menu hit=... item added` | 返回列表加入分享项 |
| `system_share hit=... chooser launch requested` | 已发起 Activity 启动请求，仍需确认 chooser 与接收方实际表现 |

首次把模块装入目标进程与之后修改开关不同；是否需要目标的新生命周期取决于现场状态，不能从“无 system scope”推断安装后立即作用于所有已运行进程。

Vector 2.2 的模块配置 Binder 依赖模块 App 的新 UID 生命周期；已启用但旧 App 进程没有 Binder 时，仅重复打开 Activity 可能无效。本次冷启动模块 App 后连接成功，service 101 与该 Vector 的通信已实测；不要仅凭“尚未连接”推断 scope 错误或升级框架。

## 0.1.0 本地验证记录（2026-11-16）

- `exportDebugApk lintDebug` 成功；lint 为 0 errors / 7 warnings，保留了非 SDK 反射、目标/依赖版本及备份规则提示。证据：[构建日志](logs/build-validation.txt)、[lint 报告](build/reports/lint-results-debug.txt)。
- APK 内容校验通过：唯一 scope 为 `com.vivo.globaldragdrop`，单一 modern Java 入口，未打包 hook API/classic API 实现或旧系统功能类。证据：[打包检查](logs/apk-validation.json)。
- APK v2 签名验证通过，包名、版本和启动 Activity 已核对。证据：[签名结果](logs/apk-signature.txt)、[包信息](logs/apk-badging.txt)。
- 产物大小：2,589,393 字节；SHA-256：`45380a5c921ceea01df26f0b9318c884d9f963fd16d105e708ec7813a3ebe4a3`。
- 未安装到设备；四项功能的现场命中与界面效果均待验证。

## 0.1.1 实包核对与本地验证（2026-11-16）

- 已从连接的手机只读取得拖放直达 4.1.3.0；原始 DEX 的 15 个目标类、32 个成员签名检查通过：[结果](logs/host-dex-validation.json)。可在本地复核：`python tools/verify-host.py apk/globaldragdrop-4.1.3.0.apk`。
- 已修正 `ge.a.b` 的 boxed `Boolean` 返回类型，精确绑定三参数分享处理器，并补齐合成分享项缓存状态。
- 构建与 lint 成功（0 errors / 7 warnings），作用域与签名验证通过：[构建日志](logs/build-0.1.1.txt)、[APK 校验](logs/apk-validation-0.1.1.json)、[签名](logs/apk-signature-0.1.1.txt)。
- 最新 APK：`dist/drag-all-0.1.1-debug.apk`；SHA-256：`6d6099161257809320b2ef415a9edd9328a012d115add2ad3d70796d697184f8`。
- 初始核对停在安装前；用户随后明确授权本次标准 `adb install`，已成功安装 0.1.1。安装前后 uptime 递增、关键 PID 不变，临时 root 仍可用；未停止或重启进程。详见 [DEVICE-CHECK.md](DEVICE-CHECK.md)。
- 用户启用后已只读确认 `enabled=1` 且 user 0 的 scope 正确。当前拖放直达仍是安装前 PID 4667，新模块尚未获得目标新进程的首次加载机会；待授权处理目标生命周期后继续验证加载与命中。详见 [首次加载阻断记录](DEVICE-CHECK.md#用户启用后仍无效果首次加载阻断)。
