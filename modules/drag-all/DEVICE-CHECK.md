# 2026-11-16 设备核对、部署与 PiliBro 排查

**最新暂停状态：手机为 0.1.10，其原生提取功能因字段锚点错误未加载；本地 0.1.11 已修正、构建通过，但尚未安装。0.1.9 的无客户端 hook 文字拖拽已获用户确认；图片修复与其他 TG 客户端运行仍待验证。ExteraLess scope 保持移除，模糊与分享未改。完整接续见 [HANDOFF-2026-11-16.md](HANDOFF-2026-11-16.md)。** 下方按发生顺序保留历史记录，不代表全部为当前版本。

约束：用户明确禁止重启手机，以及可能触发重启的操作。初始核对阶段只查询设备状态、读取已有日志，并把已安装的宿主 APK 拉取到电脑。随后用户授权标准安装，以及增加并执行当前作用域进程重建；按阶段记录如下。没有执行卸载、PACKAGE 广播、force-stop、手机或框架重启。

最新状态：手机重新连接后已安装 0.1.3、0.1.4，拖放直达确认加载新版，用户确认玻璃外观满意。PiliBro 的请求已通过拖放直达白名单，实机失败码为内容提取服务返回的 60006。已加入内容提取服务作用域及对应 hook，但其旧主进程未自然退出；未对其系统 UID 进程执行结束或重建。0.1.5 处理分享图标与玻璃重复刷新，状态见文末。

## 安装前只读现场

| 项目 | 结果 | 本地证据 |
|---|---|---|
| ADB | USB 序列 `10AG632QLS002CF`，device | 本轮 ADB 查询；目标与根规则一致 |
| 系统 | OriginOS 7 / Android 17 / API 37 / `PD2545D_A_17.1.13.20.W10` | [identity](logs/device-identity.txt) |
| 屏幕 | Awake；keyguard `showing=false` | [power](logs/device-screen.txt)、[keyguard](logs/keyguard-state.txt) |
| root | `su -c id` 返回 uid 0 / `u:r:ksu:s0`；KernelSU LKM 为 Live | 本轮命令输出、[modules](logs/kernel-modules.txt) |
| Vector | v2.2 / 3080 / `88f8e1fa`；一个 vectord（23780） | [version](logs/vector-version.txt)、[processes](logs/device-processes.txt) |
| 桥接相关服务 | activity 存在，serial 是 `android.hardware.serial.ISerialManager` | [services](logs/vector-bridge-state.txt)；不能仅据此证明新模块可注入 |
| 宿主 | `com.vivo.globaldragdrop`，versionName 4.1.3.0，versionCode 4010300，appId 10113，进程 4667 | [package](logs/host-package.txt)、[processes](logs/device-processes.txt) |
| drag-all | 未安装 | [module package](logs/module-package.txt)、[user 0 packages](logs/user0-module-packages.txt) |

Vector 当前留存 verbose 文件尾部仍是启动期记录，没有本模块的加载/命中证据。不能把 root 可用或 daemon 存在当成 drag-all 已生效。[日志尾部](logs/vector-verbose-tail.txt)

## 宿主样本与实际签名

APK：`apk/globaldragdrop-4.1.3.0.apk`，13,743,002 字节。SHA-256：
`3a387200a8a842981cbc3ad08d216b75dbfb0be0b1a4b8fe6061fb6934f8f680`。

样本由 `adb pull` 读取设备已安装 APK 得到。JADX 输出只保存在本机 `analysis/globaldragdrop-4.1.3.0/`，不入库。以下路径均相对于其 `sources/`。

完整 JADX 转换报告了 68 个方法处理错误，因此不能把整个输出当成可编译源码。已另用 [verify-host.py](tools/verify-host.py) 直接解析原始 DEX 的 class_data、方法原型和字段定义，核验目标 15 个类、32 个成员，全部匹配；包括 `ge.a.b` 的 boxed Boolean 返回值。证据：[原始 DEX 核验](logs/host-dex-validation.json)。该检查只证明静态签名，不证明运行命中或副作用安全。

| 功能 | 实包核对结果 | 反编译证据 |
|---|---|---|
| 拖拽调用范围 | `yb.f.a(Message):void` 和 `handleMessage(Message):void`；`a` 内先查配置是否 null，再调用闸门 | `yb/f.java:66,239,275,736` |
| 拖拽闸门 | `vb.p.h(String,String):boolean` | `vb/p.java:2631` |
| 配置占位 | `bb.a.a(String,String):Object`；闸门内部会强转真实配置，因此占位与闸门短路必须同组 | `bb/a.java:36`、`vb/p.java:2637,2647` |
| 设置列表 | `jh.z` 是 Runnable，`run():void` 内调用 `bb.a.c():List` 和 `kb.i1.b(Context,String):boolean`，再按 `y0.b()` 恢复用户关闭状态 | `jh/z.java:34,64`、`bb/a.java:118`、`kb/i1.java:51` |
| 分享消费 | `ne.f.a(TransIslandInfo,IntentionService,ee.a):void`；`getClipdata():ClipData`；`ee.a` 为 marker 接口 | `ne/f.java:42`、`TransIslandInfo.java:173`、`ee/a.java` |
| 分享能力 | **`ge.a.b(TransIslandInfo,String,IntentionService):java.lang.Boolean`**，不是 primitive boolean | `ge/a.java:117` |
| 分享显示过滤 | `he.n.q(IntentionServiceBaseConfig):boolean` | `he/n.java:691` |
| 分享对象 | 13 参数 config 构造器、4 参数 info 构造器、app/service 无参构造器及所需 setter 存在 | `com/vivo/intentservice/bean/IntentionServiceBaseConfig.java:624`、`IntentionServiceInfo.java:115`、`IntentionService.java:17,80,84`、`AIEAppInfo.java:40,308` |
| 拖拽岛 | `SpringTransIslandView.g():void`，字段 `h:Paint`；原代码设置黑色渐变，onDraw 含厂商 blur 路径 | `SpringTransIslandView.java:129,798,840,1336` |

## 0.1.1 的本地修正

0.1.0 把 `ge.a.b` 的返回类型声明为 `boolean.class`，严格签名检查会因此拒绝整组分享 hook。0.1.1 改成实包的 `Boolean.class`，并把分享 skill 从结构搜索收紧为上述精确三个参数。补齐合成 service 的 `cacheIconRes` 和 `canSupportShare=1` 初始化，避免后续缓存读取重新按假目标包判定能力。

这些是实包静态核对和本地代码修正，**没有在手机运行 hook**。

0.1.1 已完成构建、lint（0 errors / 7 warnings）、APK scope/入口/类定义检查和 v2 签名验证。证据：[构建](logs/build-0.1.1.txt)、[打包检查](logs/apk-validation-0.1.1.json)、[签名](logs/apk-signature-0.1.1.txt)。产物为 `dist/drag-all-0.1.1-debug.apk`，SHA-256：`6d6099161257809320b2ef415a9edd9328a012d115add2ad3d70796d697184f8`。

结束时再次只读核对：uptime 从 45347.41 增至 45937.86 秒，`system_server=23961`、`zygote64=23773`、`vectord=23780`、拖放直达 `4667` 均与开始时一致。证据：[结束状态](logs/device-final-state.txt)、[开始进程](logs/device-processes.txt)。

## 安装前的授权边界

安装会触发系统包事件；本机已有 PackageInstaller 卡死和 vivo 备份包事件崩溃的 [事故记录](../../root/INCIDENT-2026-11-15-packageinstaller.md)。亮屏、解锁、root 正常只能确认当前条件，不能排除该路径的已知风险。因此初始核对停在安装前，等待用户明确对本次安装作例外授权。

## 后续单次安装授权与结果

用户明确回复“当然允许本次安装。。。”后，重新确认屏幕 Awake、keyguard `showing=false`、用户在场，并核对 APK SHA-256 与上文一致，仅执行了一次标准 `adb install`。

- 安装返回 `Success`，退出码 0：[安装日志](logs/install-0.1.1.txt)。
- 已安装包 `dev.ganvivo.dragall`，versionName `0.1.1` / versionCode `2`，user 0 `installed=true`：[安装后包信息](logs/installed-package-0.1.1.txt)。
- 安装前后 uptime 为 46051.90 → 46157.50 秒；`system_server=23961`、`zygote64=23773`、`vectord=23780`、拖放直达 `4667` 均未变化；`su -c id` 仍为 uid 0 / KSU 上下文：[安装后状态](logs/post-install-state-0.1.1.txt)。
- 未执行强停、进程重启或系统重启。此次只确认安装成功与上述状态保持，尚未确认 Vector 模块启用/scope、目标进程加载和功能命中。

## 用户启用后仍无效果：首次加载阻断

用户报告已在 Vector 启用但无效果后，进行了以下只读核对：

- Vector 活跃数据库通过 `sqlite3 -readonly` 查询：模块 mid=469，`enabled=1`，作用域为 `com.vivo.globaldragdrop`、user 0，APK 路径与已安装 0.1.1 相同。[scope](logs/activation-scope.json)
- 模块配置表没有该模块的覆盖设置，代码默认开启拖拽放行与应用列表。模块 App 已有进程 1718。[processes](logs/activation-processes.txt)
- 宿主仍为 PID 4667，与安装前相同；`/proc/4667/stat` 的启动 ticks 为 4292913，设备 `CLK_TCK=100`，即开机后 42929.13 秒启动，早于本次安装前的 uptime 46051.90 秒。[stat](logs/activation-host-stat.txt)
- 没有取得 DragAll 在宿主中的加载、挂载或命中记录；当前落盘 module log 仅 21 字节，不能单凭日志缺失下结论。[module log](logs/activation-module-log.txt)、[live log](logs/activation-live-log.txt)

固定版本源码说明了为何启用 scope 不会把新模块自动补进这个旧进程：[XposedInit.loadModules](https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/legacy/src/main/java/de/robv/android/xposed/XposedInit.java#L219-L235) 的正常装载只执行一次；[VectorModuleManager 热重载](https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/core/VectorModuleManager.kt#L229-L239) 要求目标进程已经有该模块的 generation，没有则拒绝。

当前应解决的是让拖放直达获得一次新进程生命周期，而不是重新安装或修改作用域。这个步骤只需针对拖放直达，不要求重启整个手机、zygote 或 system_server；但它仍属于根规则禁止擅自执行的系统应用进程变更，现阶段尚未执行。之后仍需分别确认模块加载、hook 注册和实际拖拽命中，不能用 PID 变化代替功能验收。

## 0.1.2 安装、作用域按钮与配置服务

用户随后明确要求加入“重启当前作用域”、重新安装并启用。0.1.2 已标准安装成功；Vector 官方 CLI 及活跃数据库均确认模块启用，唯一作用域为 `com.vivo.globaldragdrop`、user 0。[模块](logs/vector-module-0.1.2.json)、[scope](logs/vector-scope-0.1.2.json)

作用域按钮固定目标包，核对 UID、cmdline 和进程启动 ticks 后，仅向精确 PID 发 SIGTERM，再以 root 打开目标受权限保护的设置页。按钮执行成功：[界面](logs/restart-button-result.png)。宿主进程日志确认 `module=0.1.2`、四组 hook 注册成功：[加载日志](logs/host-log-after-restart.txt)。后续宿主 PID 10861 也确认成功加载，应用列表由 241 增到 455，并命中系统分享调用与旧玻璃 shader：[业务日志](logs/pilibro-drag-trace.txt)。这些记录不代表 PiliBro 拖拽成功。

退出记录中的 4667、8615 由 SIGTERM 结束；中间的 3433 由 OriginOS `single-cleaner` 回收，不能一概作为 hook 崩溃。[exit-info](logs/host-exit-info-0.1.2.txt) 本轮对应核对中 `system_server=23961`、`zygote64=23773`、`zygote=19127`、`vectord=23780` 保持不变。

“已启用但尚未连接”的配置服务问题已单独复现：启用/scope 正确，旧模块 App 进程未收到 Binder。冷启动模块 App 后，10:56:13 的 `XposedProvider` 与 `VectorModuleAppService` 记录 Binder 收发，界面恢复已连接。[日志](logs/service-after-app-relaunch.txt)、[界面](logs/service-after-app-relaunch.png)。本机 service 101 的连接已实测，无需据此更换 Vector 或重启 daemon。

## PiliBro 长按无反应：已定位机制与证据边界

来源包为 `org.BroTech.gege.pilibro`，UID 10537；进程映射确认使用 Flutter。用户多次确认长按无反应，旧日志没有能证明该包实际拖拽成功的记录。

1. 0.1.2 只在 `yb.f` 消息处理的 ThreadLocal 范围内改入口闸门和缺省配置；实际抓取发生在异步的 `vb.o.p(Object)`，后者会再次读 `EnhancedDragWhiteListV2` 并执行 `EnhancedDragWhiteList.Companion.checkWhiteList`。异步步骤已离开原调用范围，构成实际漏改。证据：本机反编译 `analysis/globaldragdrop-4.1.3.0/sources/yb/f.java:239`、`vb/o.java:926`，成员已由 DEX 复核。
2. 本机 `vivo-framework.jar` 中，`DragManager` 把服务回复 26/27 记录为应用/Activity 不支持。`DragNotSupportDataManager` 在应用进程内保留最长 86400000ms 的缓存；后续长按在发送请求之前就被拦截。修复宿主之后还需要来源应用的新进程才能立即消除旧缓存。证据：`analysis/vivo-framework/sources/com/vivo/vdragdrop/manager/DragManager.java:169`、`:331` 与 `DragNotSupportDataManager.java:12`。尚未直接读取 PiliBro 内存确认其缓存值，不能把此机制当作唯一根因。
3. 客户端对非 TextView/ImageView 使用通用 widgetType `6`，并发送实际视图类名，Flutter 没有在此入口被专门排除。通过请求与白名单仍不等于厂商抓取器能抽取 Flutter 内容。证据：同目录 `DragManager.java:580`。下一步应先确认 PiliBro 的 `drag_gate`、`drag_profile`、`drag_capture`，再检查实际拖出内容。

厂商 `Logger` 的开关来自 `persist.sys.log.ctrl`，未开启时缺少日志不能证明没有请求。本轮未修改该全局属性或发送广播；新增的按包诊断用于后续核验。

## 0.1.3 修正与待验收项

- 拖拽：加入 `vb.o.p(Object)` 异步范围；用真实 `EnhancedConfigBean` 的 `strictLevel="package"` 放行通用规则，复制已有配置的 label、disabled 及六组列表，保留厂商黑名单与专用规则，不修改原缓存。全局与每应用用户开关继续由厂商处理。
- 玻璃：旧白色渐变使浅色文字难读，且没有控制实际模糊。改为 RGB(12,16,22) 半透明底色；模糊强度 0–100（默认 30）、底色浓度 0–90（默认 25）分开同步。通过本机 `View.setMaterialBlurRadius(float)` 调节背景，记录并恢复原 shader/半径；未对文字图标施加 RenderEffect。
- 作用域重建：不再依赖模块 App 的配置 Binder；root 脚本先只读核验 Vector 中已启用且 scope 唯一正确，校验失败停止。PC 工具默认 preview，与按钮执行同一脚本。首次 preview 因设备断开而未到达设备，尚未实机验证新增 SQL guard。
- 本地：`exportDebugApk lintDebug` 成功；APK 打包与签名验证通过；实机 APK/framework.jar 的 19 个类、58 个成员核验通过。[构建](logs/build-0.1.3.txt)、[打包](logs/apk-validation-0.1.3.json)、[成员签名](logs/host-validation-0.1.3.json)、[APK 签名](logs/apk-signature-0.1.3.txt)
- 部署：ADB 设备列表为空，**0.1.3 尚未安装**。待连接后先确认亮屏解锁，标准安装失败立即停止；成功后打开模块 App，验证配置服务及 scope，重建目标作用域。然后按精确 PID/UID 校验结束并重新打开 PiliBro，清除进程内的旧缓存，进行实际长按测试。
- 验收：记录 PiliBro 真实可拖动内容及相关包诊断；分别调整两个玻璃滑条，确认 `native=true`、实际 radius 随配置变化及文字清晰度，关闭后恢复原表现。安装和目标进程变化前后再次核对系统与 daemon PID。

## 重新连接后的 0.1.3 实测

亮屏解锁后标准安装返回 Success，版本 code 4/name 0.1.3；作用域 preview 与重建脚本成功，核心 PID 保持 23961/23773/19127/23780。[安装](logs/install-0.1.3.txt)、[安装后状态](logs/post-install-state-0.1.3.txt)、[scope preview](logs/scope-preview-0.1.3.txt)、[重建](logs/scope-restart-0.1.3.txt)

宿主 PID 5339 确认加载五个拖拽 hook，但随后被系统 single-cleaner 停止。另一次针对旧 PiliBro PID 21970 的身份检查因该进程已经不存在而停止，未发送信号；用户自行重开后的 PiliBro 为 PID 3083。[退出记录](logs/host-exit-info-0.1.3.txt)、[来源与宿主进程](logs/pilibro-ready-processes-0.1.3.txt)

重新打开拖放直达后，宿主 PID 6701 的日志明确显示 `drag_gate:org.BroTech.gege.pilibro`、`drag_profile` 和 `drag_capture` 命中。因此此时入口和异步白名单均已通过，不能继续用缺少 scope、模块未加载或旧缓存解释全部失败。[命中记录](logs/pilibro-longpress-0.1.3.txt)

实际内容提取回调两次返回 **resultCode=60006、data=Bundle[{}]**，组件为 PiliBro MainActivity。[回调证据](logs/capture-buffers-0.1.3.txt) 模块 App 的配置 Binder 也重新连接成功。[配置连接](logs/module-connection-0.1.3.txt)

## 内容提取服务的第二层白名单

只读取得 `/system/app/AccessibilityEnhance/AccessibilityEnhance.apk`，本地 `apk/accessibilityenhance-device.apk`，版本 3.0.3.2/code 3000302。其主进程 UID 1000，另有 `:persist`；均未主动结束。

反编译证据相对 `analysis/accessibilityenhance/sources/`：

- `com/vivo/accessibilityenhance/dc/sj0.java:30`：SecondAppControlStage 拒绝来源应用时返回 60006。
- `dc/x8.java:46`：调用方、接口、用户开关与授权通过后，服务端自行写入 supportAppConfig，不能靠在拖放直达构造参数覆盖该名单。
- `dc/zb.java:252`：`q1(Class,String,ComponentName)` 查询配置中的来源应用，缺少 appBean 就拒绝；`N(...)` 在此之外还保留第二层黑名单。
- `process/analyse/ProcessFlutterNodeAnalyseStage.java:50`：Flutter 节点归类为 8。`process/process/GrabDragDropContentProcess.java:156` 对类型 8 返回失败。此为静态确认的后续兼容障碍，尚未取得 PiliBro 经过第二层白名单后的真实节点结果。

0.1.4 新增内容提取服务主进程 hook，仅在来自 `com.vivo.globaldragdrop`、业务为 `dragdrop`、配置类为 `DragDropWhiteList` 的调用范围内放行 q1；仍执行 N 中原有黑名单。没有修改其他调用方、识屏业务、权限或系统设置，排除 `:persist` 和 system_server。

## 0.1.4 部署与当前生效边界

标准安装成功；Vector 官方 CLI 已追加 `com.vivo.accessibilityenhance/0`，scope 只包含两个拖拽相关包。[安装](logs/install-0.1.4.txt)、[scope](logs/vector-scope-0.1.4.json) 只重建拖放直达，PID 16149 确认加载 0.1.4，内容提取服务仍保持加入 scope 前的 **PID 9789**，未取得该进程加载新模块的证据。[目标重建](logs/scope-restart-0.1.4.txt)、[加载与仍为 60006 的回调](logs/host-start-0.1.4.txt)、[旧服务进程](logs/catcher-existing-process-0.1.5.txt)

本轮授权的重建按钮始终拒绝系统 UID；没有借新增 scope 扩大该按钮到 UID 1000。即使模块已安装、scope 已选，新服务 hook 也不能凭此进入仍运行的旧进程。

用户明确反馈“液态玻璃确实不错了，我认为 ok”。因此后续保持已认可的底色与默认原生模糊外观。

## 0.1.5 分享图标与刷新修正

用户反馈系统分享入口图标错位。原代码使用旧式 `android.R.drawable.ic_menu_share`，且把 iconLink 设置为非空的 `android`，使 `ih.q` 误走 Glide 加载分支。现在使用居中的 48dp 蓝色圆角分享图标，清空 iconLink，直接交给原菜单 ImageView 显示；保留原分享动作。

实机玻璃日志出现 `hit=600 blur=30 appliedRadius=0.25`，说明每帧都重试写入且未改变实际半径。框架实际采用比例半径，并要求自定义 style 才接受 window radius。0.1.5 以原生半径为基准，默认强度 30 保持用户已认可的原外观；仅配置或原生状态变化时尝试写入，在 finally 恢复 style，失败不循环 invalidate。[异常刷新证据](logs/host-start-0.1.4.txt)

本地核验 24 类、67 成员通过。[签名核验](logs/host-validation-0.1.5.json) 构建/安装与最终现场验证另记实际结果，不把新代码等同于 PiliBro 已生效。

一次打开 Chrome 公共测试页的命令被自动审批阻止，返回仅“blocked by policy”，没有执行。没有重试该操作或改用其他工具绕过；随后使用用户正在操作的拖拽组件与已有日志继续核验。

### 0.1.5 安装与最新用户确认

标准覆盖安装成功，包版本 0.1.5/code 6，SHA-256 `97163ff8473a9e37deed6c4ed9608f18f125b177c6e179fe8069167f39ec73c9`。只重建拖放直达，PID 32685 确认模块加载和四组功能注册。[安装](logs/install-0.1.5.txt)、[重建](logs/scope-restart-0.1.5.txt)、[加载](logs/host-start-0.1.5.txt)

在用户使用的相册里拖动图片到岛，实机确认新分享图标与 QQ、微信图标的尺寸及位置对齐。[图标界面](logs/share-icon-expanded-0.1.5.png) 模糊强度从 30 调到 60 后背景模糊视觉上增强，随后已恢复 30，底色浓度保持 25；活跃 Vector 配置整数末尾为 `0000001E`。[测试](logs/blur-strength-60-0.1.5.png)、[恢复配置](logs/blur-pref-final-0.1.5.json)

用户最新明确要求：**模糊与系统分享已经满意，不再改；只解决以 PiliBro 为代表的白名单外应用**。后续不要再分散到图标或玻璃迭代。

最新只读核验：PiliBro 已是 PID 6252，内容提取服务主进程仍为 PID 9789 / UID 1000，`:persist` 为 6574；scope 两包正确。未见内容提取服务加载 drag-all 的日志。核心 PID 始终为 system_server 23961、zygote64 23773、zygote 19127、vectord 23780。[现场](logs/whitelist-current-processes.txt)、[scope](logs/whitelist-current-scope.json)、[进程身份](logs/whitelist-process-identity.txt)

当前阻点是新增 hook 尚未进入这个旧服务进程。此前被授权并执行的是拖放直达进程重建；系统 UID 服务主进程的结束动作尚未取得单独例外授权，依根 AGENTS.md §1.0/§1.1 未执行。不要用再次重开 PiliBro、重复安装或重启手机来代替这项判断。通过第二层名单后仍需取得真实节点结果，再决定 Flutter 兼容实现，不能宣称该应用已可拖。

## 内容提取服务单次授权重建后的结果

用户明确同意“重启一下这个服务试试”后，重新核验主进程 9789 的 UID=1000、完整 cmdline、启动 ticks=74468；同时校验 boot_id、system_server、两个 zygote、vectord 和 `:persist`，仅对主进程发一次 SIGTERM。旧进程退出，exit-info 为 status=15；未强停包、发送广播、强杀或重建 `:persist`。[执行](logs/catcher-restart-once.txt)、[退出记录](logs/catcher-restart-exit-info.txt)

PiliBro 正常长按触发内容提取服务重新启动为 PID **27504**，日志确认 `module=0.1.5 process=com.vivo.accessibilityenhance`，catcher_drag 三个 hook 安装成功。[加载](logs/catcher-restarted-first-drag.txt)

下一次手势实际命中 `catcher_gate:org.BroTech.gege.pilibro original=false`、`catcher_authorize allowed=true`；回调由 60006 变为 200，节点为 **kind=3**、`viewClassPath=rq,android.view.SurfaceView`、`imageRect=Rect(0,0 - 2312,2504)`。[业务日志](logs/catcher-second-drag.txt) 拖到顶部岛的菜单确实展开，但拖动缩略图包含整块 Flutter 画面。[界面](logs/pilibro-catcher-second-drag.png)

这纠正了先前仅凭类名判断的 Flutter type=8 推测：本版 PiliBro 混淆了 FlutterSurfaceView 为 `rq`，实际被厂商识别成一般 SurfaceView 图片节点，因而成功抓取整画布。白名单问题已经通过，精确元素提取仍未完成。

执行前后 boot_id 都为 `450f80a2-31b3-4a7f-8cb6-75d63727795f`，system_server=23961、zygote64=23773、zygote=19127、vectord=23780、`:persist`=6574 全部未变；KernelSU 模块仍 Live，root 正常。[结束状态](logs/catcher-restart-final-state.txt)

## 0.1.6 按手指位置提取的实现与待验收

用户明确要求按所按内容提取具体文字或图片，不能把整块画布当成完成。只读取得 PiliBro 2.7.9/code 263170170 APK，反编译确认 FlutterView 为 `zq`、AccessibilityNodeProvider 为 `io.flutter.view.b`、语义节点 map 字段为 `g`。provider 返回具体 ImageView 标记、文字描述和屏幕 bounds，映射原生虚拟语义节点。

0.1.6 新增 PiliBro 应用 scope，触摸开始时读取包含触点的最小内容节点；在应用自己的长按预览出现前对图片节点的精确区域 PixelCopy，文字保留文本。限制节点数、文本长度、图片尺寸及传输字节数，跳过安全窗口/密码节点，日志只记录类型、长度和坐标，不记录文本正文。

选择结果通过已有拖拽 Messenger 请求携带；拖放直达校验来源 UID、包名与 requestId，在原生成功回复中使用具体文本或独立图片 URI/缩略图。没有有效选择时取消该次拖拽，拒绝退回整块 Surface 图片。图片只存到宿主自己的专用外部缓存目录并授予来源应用读取权限。

0.1.6 构建、lint、签名与 APK 校验通过；PiliBro、宿主及 framework 实包 28 个类、72 个成员核验通过。[构建](logs/build-0.1.6.txt)、[接口](logs/host-validation-0.1.6.json)、[APK](logs/apk-validation-0.1.6.json)、[签名](logs/apk-signature-0.1.6.txt)。APK SHA-256：`aee34f34e70e2cd447f6c34db764e134c5165eb79e374d920ebc1da4001024bc`。安装预检发现 Asleep/showing=true，未执行安装，待用户解锁后继续实机验证。

## 0.1.6 上机与跨应用粗查

解锁后标准安装 0.1.6 成功，追加 PiliBro scope，重建拖放直达与来源应用，内容提取服务保留 0.1.5 已加载 hook。来源 PID 1847 日志确认语义节点选择，宿主 PID 30417 记录 selected_text 与 selected_image；用户反馈基本修复，但图片组件仍不够准确。[安装](logs/install-0.1.6.txt)、[来源命中](logs/source-first-text-0.1.6.txt)、[输出替换](logs/selected-first-text-0.1.6.txt)

用户要求先粗查问题是 Flutter 还是通用问题。用原本不在名单内的 drag-all 自身 TextView 实测：catcher_gate original=false、catcher_authorize allowed=true，kind=5、回调 200，顶部预览为该段真实文字。因此通用名单放行已生效，整页取图并非所有应用的统一行为。[日志](logs/survey-native-text.txt)、[界面](logs/survey-native-text.png)

用户明确停止酷安测试，指定只用 ExteraLess。TG 客户端的 ChatMessageCell 使用自绘文字和 ImageReceiver；只能拖时间的症状与内部正文/图片不作为独立原生 View 暴露相符。尚未完成实机组件精准提取，不能从反编译接口存在推导成功。

## 0.1.7 ExteraLess 启动崩溃与恢复

0.1.7 加入 ExteraLess 13.2.1-979e175/code 1261 直接注入，尝试 ChatMessageCell.onTouchEvent、Messenger.send 与 DragClientProxy 请求范围。实包 32 类、94 成员核验及构建成功，标准安装 Success，追加作用域后重开客户端。[安装](logs/install-0.1.7.txt)、[静态核验](logs/host-validation-0.1.7.json)、[加载](logs/telegram-load-0.1.7.txt) 静态检查没有覆盖与应用自身运行环境的 native 兼容性。

实际启动后多次 native SIGSEGV，故障 PC 相同 `0x2fd61f0220`，分别来自 ART ClassLinker::SetupClass / MarkClassInitialized，涉及消息数据库线程及 Chaquopy / `com.extera.plugins.scrolltomedia.MediaScrollCore.load`。在取图业务 callback 命中前即崩溃。**模块注入与崩溃有明确开关关联，但具体哪项 native hook 冲突尚未隔离，不应断言为某个用户插件缺陷。** [崩溃记录](logs/exteraless-crash-0.1.7.txt)、[退出记录](logs/telegram-reopen-inspection-0.1.7.txt)

用户报告无法打开后，立即通过 Vector 官方 CLI 只移除 `com.exteraless.app/0`，scope 复核仅剩拖放直达、内容提取服务与 PiliBro。随后客户端冷启动成功、显示聊天列表，新 PID **28717** 后续只读复核保持存活。[撤销](logs/exteraless-emergency-scope-remove.json)、[scope 核验](logs/exteraless-emergency-scope-verified.json)、[启动](logs/exteraless-emergency-open-state.txt)、[恢复界面](logs/exteraless-recovered.png)、[后续状态](logs/exteraless-recovery-confirmed.txt)

未清除应用数据、卸载客户端、禁用用户插件或重启系统。system_server=23961、zygote64=23773、zygote=19127、vectord=23780 保持不变。

0.1.8 已删除推荐 ExteraLess scope 并拒绝该进程/包的业务初始化，将实验类从 src 移到 `notes/experimental/TelegramSelectionFeature.java.disabled`，APK 验证显式禁止包含该类。避免通过“延迟一点再装 hook”继续试错。模糊与分享代码没有改动。构建日志 `logs/build-0.1.8.txt`，打包与签名校验另记；部署前 ADB 设备消失，未执行新的安装或客户端重开。设备恢复以已完成的 scope 撤销为依据，不能把本地修正版写成已经安装。
