# Telegram 客户端生态研究与原创插件/模块（`tg-clients`）

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

> **状态：活跃维护（主战线为本地维护版 `side_panel.py` + 跨客户端插件生态研究）。**
>
> 本目录归口管理设备上各类 Telegram 第三方客户端的机制研究、插件系统逆向、既有插件审计以及本地维护插件开发。
> 本轮核心对象：**ExteraLess（`com.exteraless.app`）与 NagramXF（`fork.risin42.nagramx`）插件系统**及本地维护插件 **`Chats Menu`（`side_panel.py` v3.2.1）**。

---

## 1. 本机 Telegram 客户端基线

采样时间：2026-11-25，设备 `10AG632QLS002CF`（vivo X Fold6，Android 17 / OriginOS 7）。

| 包名 | 客户端 | 版本 (`versionName` / `versionCode`) | 最近更新 | 备注 |
|---|---|---|---|---|
| `com.exteraless.app` | **ExteraLess** | `13.1.1-c83765c` / `1261` | 2026-11-16 | **本期主研对象**；APK 103 MiB，SHA-256 `3855d366...a3a2c5` |
| `com.exteragram.messenger` | exteraGram | `13.2.2` / `73039` | 2026-11-25 | 插件生态上游参考 |
| `com.radolyn.ayugram` | AyuGram | `13.1.1` / `72379` | 2026-11-12 | — |
| `xyz.nextalone.nnngram` | NnnGram | `v13.2.2-01f3cd9` / `1795289047` | 2026-11-25 | — |
| `xyz.nextalone.nagram` | Nagram | `13.2.2` / `1260` | 2026-11-20 | — |
| `fork.risin42.nagramx` | NagramX | `13.2.1-37a3538` / `1252` | 2026-11-12 | — |
| `momo.gram` | MomoGram | `13.2.2-4b55244698` / `11709` | 2026-11-23 | — |
| `top.nkbe.niagram` | NiaGram | `13.2.2-127b70d` / `1322` | 2026-11-23 | — |
| `ua.entaytion.entinygram` | EntinyGram | `13.2.2-04f4ca5` / `2026112300` | 2026-11-23 | — |
| `org.tele.alexgram.nightly` | AlexGram Nightly | `13.2.2-047cbb2` / `1794687` | 2026-11-23 | — |
| `org.telegram.messenger.web` | Telegram 官方直装版 | `13.3.1` / `73469` | 2026-11-25 | — |

### ExteraLess（`com.exteraless.app`）采样工件（本地 `cache/`，不入库）

- 安装路径：`/data/app/~~ovrl1VC3fQtfwHfcWJawwQ==/com.exteraless.app-gxenlWZr9IhU7ttiqsfPZA==/base.apk`（单 APK，7 个 DEX，minSdk 24，targetSdk 36）
- 本地镜像：`cache/tg-clients/exteraless/base.apk`（108,097,184 字节，SHA-256 `3855d366b9fa7b49e9219ac26a698c4307e0d4c205dae9b7188ef351d0a3a2c5`）
- JADX 反编译（`classes.dex`）：`cache/tg-clients/exteraless/jadx_plugins/sources/`
- Chaquopy Python SDK 解包（`assets/chaquopy/app.imy`）：`cache/tg-clients/exteraless/chaquopy_extracted/app.imy/`
- 本机插件副本与日志：`cache/tg-clients/exteraless/plugins/`、`cache/tg-clients/exteraless/prefs/`、`cache/tg-clients/exteraless/plugins.log`

---

## 2. ExteraLess 插件系统架构解析

ExteraLess 是一个在 Telegram / Neko / exteraGram 基础上重构的客户端，内置了**高度兼容 exteraGram（SDK `1.4.5.7`）且大幅强化了安全沙箱与稳定性防护**的进程内 Python 插件引擎。

### 2.1 底层运行时与核心组件

| 层级 | 关键组件 / 文件 | 作用 |
|---|---|---|
| **Python 引擎** | `libpython3.11.so` + `libchaquopy_java.so` + `assets/chaquopy/app.imy` | 通过 Chaquopy 在 App 进程内运行 CPython 3.11，预置 `PIL`、`lxml`、`PyYAML` 及标准库 |
| **进程内 ART Hook** | `libaliuhook.so` + `liblsplant.so` + `de.robv.android.xposed.XposedBridge` | 无需 root / LSPosed，应用内基于 AliuHook/LSPlant 直接做 Java 方法 Hook、反优化与实例分配 |
| **Java 总控层** | `app.exteraless.plugins.PluginsController`、`PythonPluginsEngine`、`PluginServices`、`MenuInjector`、`ClassProxyFactory` | 管理插件扫描、启停、生命周期分发、菜单注入、设置页渲染与动态 Java 子类代理生成 |
| **Python SDK 层** | `base_plugin.pyc`、`hook_utils.pyc`、`client_utils.pyc`、`android_utils.pyc`、`file_utils.pyc`、`intents.pyc`、`ui/{settings,alert,bulletin}.pyc`、`extera_utils/*` | 向插件暴露 `BasePlugin`、`MethodHook`、`MethodReplacement`、设置 DSL、Telegram 控制器封装与安全沙箱 |
| **Elyx 打包运行时** | `elyx_runtime/{archive,assets,loader,localization,metadata,namespace,settings,facade,dev_commands}.pyc` | 支持 `.elyx` / `.eaf` 多文件归档插件（含 `refmap`、`metainfo`、多语言、资源、wheel 与独立 `ElyxPlugins.<id>` 命名空间） |
| **兼容桥接层** | `app.exteraless.plugins.ClassAliases`、`extera_utils/class_aliases.pyc`、`com.exteragram.messenger.plugins.*` | 拦截 `Class.forName` 与 Python import，把 `com.exteragram.messenger.*` 与旧 `exoplayer2` 透明映射到 `app.exteraless.*` / `androidx.media3.*`，并对配置字段做 getter/setter 形状适配 |
| **开发与依赖工具** | `dev_server.pyc`（`127.0.0.1:42690`，令牌 `.devserver_token`）、`pip_controller.pyc` | 开发者模式开启本地 HTTP 热重载/调试服务；支持从 PyPI 拉取纯 Python wheel 到 `files/plugins/shared_libs/` |

### 2.2 插件目录、格式与配置落点

- 插件源码目录：`/data/data/com.exteraless.app/files/plugins/`
  - 支持扩展名：`.py`、`.plugin`（扫描时自动重命名为 `.py`）、`.elyx`、`.eaf`
  - 字节码与元数据缓存：`files/plugins/__pycache__/<id>.cpython-311.pyc` + `<id>.meta.json`
  - 崩溃看门狗标记：`files/plugins/.watchdog`
  - 开发者模式令牌：`files/plugins/.devserver_token`
- 日志文件：`/data/data/com.exteraless.app/files/plugin_logs/plugins.log`
- SharedPreferences：
  - `exteraless_plugins.xml`：引擎总开关（`pluginsEnabled`）、开发者模式（`pluginsDeveloperMode`）、安全模式、各插件启用状态（`plugin_enabled_<id>`）及静态能力扫描结果（`plugin_scan_<id>`）
  - `exteraless_plugins_metadata.xml`：元数据 JSON 持久化缓存（按 `path|size|mtime` 校验失效）
  - `exteraless_plugin_grants.xml`：权限与信任等级授权库（含 `sig:*` 签名）
  - `plugin_settings_<id>.xml`：每个插件独立的设置存储

### 2.3 五层安全沙箱与稳定性机制（ExteraLess 特色）

相比原版 exteraGram，ExteraLess 额外实现了完整的纵深防御体系：

1. **静态能力与载荷扫描（`extera_utils/capability_scan.py` + `PluginCapabilityScan`）**
   - 安装或打开权限页时，静态扫描 Python 源码/压缩包中的敏感导入、API 调用、混淆打包特征（`obfuscation`），以及**内嵌 Base64/字面量 DEX 载荷**（直接解析 DEX 字符串表并列出内部类名，标注 `dex: size/loaded/class`）。
   - 安装界面内置 AI 代码审查辅助工具（`PluginAiReview` / `PluginReviewTools`）。
2. **三级信任模型 + 9 项细粒度权限（`PluginTrustLevel` + `PluginPermissions`）**
   - 信任等级：`0 = ISOLATED`（仅允许 `ui`）、`1 = GATED`（可逐项授予非高危权限，禁止 `hooks`/`native`）、`2 = TRUSTED`（允许高危权限 `hooks` 与 `native`）。
   - 权限枚举：`ui`（默认）、`messages.read`、`messages.send`、`network`、`files`、`intents`、`settings`、`hooks`（高危）、`native`（高危）。若插件未声明 `__permissions__` 或被检出混淆，则默认展示全量可请求权限。
   - 另提供全局 `pluginsUnsafeMode`（关闭权限拦截，需用户手动开启并在权限页标红警示）。
3. **硬件 Keystore 签名防篡改授权库（`SignedGrantPreferences` + `PluginGrantStore`）**
   - 在 `AndroidKeyStore` 中生成不可导出的 `HmacSHA256` 密钥 `exteraless_plugin_grants_v1`，对 `exteraless_plugin_grants.xml` 中每条 `plugin_perms_*`、`plugin_level_*`、`pluginsUnsafeMode` 记录计算 `HMAC(key + \0 + typeTag + \0 + value)` 并存为 `sig:<key>`。
   - 读取时恒定时间验签，未签名或被篡改的记录直接忽略；同时 `PluginSinkGate.hookGrantStore()` 拦截插件通过 `ContextImpl.getSharedPreferences` 访问授权表或读写其他插件的 `plugin_settings_*`。
4. **Python + Java 双侧运行时拦截门**
   - **Python 侧（`audit_gate.py` + `plugin_loader.py._install_sandbox`）**：挂接 `sys.addaudithook` 拦截进程派生（`os.system`/`subprocess.Popen` 永久禁止）、`ctypes` 原生内存操作（需 `native`）、socket/HTTP（需 `network`）、`sqlite3` 与越界文件访问（限制在插件自身目录，越界需 `files`，且保护 `shared_prefs`/`databases`/`files/chaquopy`）；同时包装 `__import__`、`open`、`jclass`、`dynamic_proxy` 与 `PluginRuntime` 线程归属栈。
   - **Java 侧（`PluginSinkGate` + `XposedHooks` + `MenuInjector`）**：启动时先借 AliuHook 自身把 Java 侧关键汇点锁住——包括 `Runtime.exec`/`ProcessBuilder`、`Runtime.load0`/`loadLibrary0`、`URL.openConnection`/`Socket.connect`、`WebView.loadUrl`、`DownloadManager`、`DexClassLoader`/`PathClassLoader`、`WindowManagerImpl.addView`（系统浮窗）、`SendMessagesHelper` 19 个发送/编辑方法、`ConnectionsManager.sendRequest`（按 TL 请求前缀细分 `messages.read`/`messages.send`/`hooks`）、`Class.forName` 黑名单类（禁止反射访问 `PluginPermissions`/`PluginSinkGate` 等内部控制类）。
   - **硬性禁区**：`XposedHooks` 永久禁止 hook `org.telegram.ui.Wallet.*`、`TL_wallet`/`TL_toncenter` 及 `KeyStore`；进入钱包界面时 `WalletPluginFreeze` 会冻结全部插件回调。
   - **菜单条件表达式防注入**：`MenuInjector` 用 MVEL 评估菜单显示条件时强制 `SAFE_REFLECTIVE` 并用正则白名单校验根变量，阻断反射/赋值/循环。
5. **稳定性看门狗与 Hook 并发防崩（`PluginsWatchdog` + `HookGate`）**
   - **主线程预算**：每个插件在主线程耗时按 1s 窗口统计，超过 **250 ms/s** 连续 3 个窗口弹出「插件拖慢应用」告警（可单插件静音）。
   - **无响应检测**：任意线程单次进入插件超 **5 s** 触发「插件无响应」强制禁用弹窗。
   - **崩溃自动隔离**：进入加载或运行超 1s 写入 `.watchdog` 标记，进程异常死亡累计 **2 次**自动禁用对应插件；启动后 **15 s 内连续早崩 3 次**自动进入 `SafeMode`（停用全部插件）。
   - **AliuHook 竞态与类型保护**：`HookGate.prewarm()` 在首次 `hook0` 期间插入 `CountDownLatch` 屏障避免并发调用空 `backup`；`CheckedHookInfo` 检查原始返回类型不匹配时尝试数值转换或回退原方法；若 AliuHook 初始化导致进程连崩 2 次，自动置 `native_hooks_unsupported=true` 停用 native hook 防止循环闪退。

### 2.4 本机 ExteraLess 已安装插件体检快照

截至 2026-11-25，`/data/data/com.exteraless.app/files/plugins/` 共有 **8 个插件**（原尝试的轻量自研 `quick_chat_rail.py` 已按需废弃清理，统一收口到增强版 `side_panel.py`）：

| 插件 ID | 显示名 | 版本 | 状态判定 | 信任/实授权限 | 审计结论与建议 |
|---|---|---|---|---|---|
| `side_panel` | **Chats Menu（聊天快捷菜单）** | `3.2.3` | **🟢 正常（本地维护版）** | `2` (`messages.read, messages.send, hooks`) | 已完整汉化、修复 5 个滑块/搜索钮重叠/泄漏、新增本地置顶与收藏夹同组卡片分离、整体放大 +10%、纯 Quick Bar「最近打开」排序、全屏高度伸展贴合顶栏与输入框 |
| `advanced_search` | 高级搜索 | `3.56.2`（设备已跑通 `3.56.1`，见 §6） | **🟢 正常（本地维护版）** | `2` (`hooks` 已补授) | 此前因缺 `hooks` 报错 56 次，补授 `Trusted + hooks` 后 354KB 内嵌 DEX 正常挂载并扫描对话；折叠屏双栏下结果开在左栏的问题已修复并实机验收，`3.56.2` 顺手修掉替换时 `discardDialog` 反射调用 |
| `all_chats_loader` | All Chats Loader | `2.0.5` | **🟡 半失效** | `1` (`messages.read, network`) | L302 写错 `ChatActivity.getDeclaredMethod`（漏 `.getClass()`，报错 41 次）且无 `hooks`，加载期防进聊天保护失效；属一次性工具，用完建议关闭 |
| `ToolCollection` | 实用功能 | `2.0.9` | **🟢 正常** | `2` (`read, send, files, settings, hooks`) | 25KB 内嵌 DEX (`MediaScrollCore`) 与 `[VQ]` Hook 均正常，零外网权限，当前开启 4 项功能运行正常 |
| `non_member_marker` | 非群成员标识 | `1.4.0` | **🟢 正常** | `2` (`read, network, files, hooks`) | `setMessageObject` + `drawChild` 均已挂上（缓存 54 群 / 148 非成员）；日志中 `CHAT_ADMIN_REQUIRED` 属群隐私限制而非故障 |
| `add_to_folder` | Add to Folder in Folders | `1.0` | **🟢 正常（过度授权）** | `2` (全 6 项) | 仅 115 行 hook `DialogsActivity.updateCounters`（末尾 912B DEX 未加载）；实际仅需 `hooks`，其余 5 项权限可收回 |
| `channel_inspector` | Channel Inspector | `2.2.0` | **🟢 正常** | `2` (全 6 项) | 纯 Python，`.chinfo`/`.chstats`/`.avatars` 与头像保存正常；`urllib` 仅用于 `urlparse`，可关闭 `network` 权限 |
| `shortcuts` | Shortcuts | `1.3.2` | **🟡 空跑+过度授权** | `2` (全 6 项) | 单字母变量混淆压缩，hook `Browser.openUrl` 处理 `tg://shortcuts`（无外联）；当前仅含默认自指快捷方式且拿了全 6 项权限，不需要可关或只留 `hooks` |

---

## 3. 插件深度审计：`Chats Menu`（`side_panel.py` v3.2.0）

### 3.1 元数据与权限对照

- **文件**：`/data/data/com.exteraless.app/files/plugins/side_panel.py`（147,350 字节，3,596 行纯 Python，无内嵌 DEX、无混淆、无外部网络请求）
- **元数据**：`__id__ = "side_panel"`，`__name__ = "Chats Menu"`，`__version__ = "3.2.0"`，`__author__ = "@d1zzy412 • @d1zplug"`，`__app_version__ = ">=12.10.6"`
- **权限对照**：
  - 源码声明：`__permissions__ = ["hooks", "messages.read"]`
  - 静态扫描补全：`messages.send`（因包含 `SendMessagesHelper` 与 `ConnectionsManager`，用于「选中消息后点聊天一键转发」）
  - 本机实授：`plugin_level_side_panel = 2 (TRUSTED)`，`messages.read, messages.send, hooks`
- **当前本机已保存配置**（`plugin_settings_side_panel.xml`）：
  `chat_mode=0`（指定聊天模式）、`panel_style=1`（MD3）、`panel_anim=0`（滑出）、`close_on_tap=true`、`edge_swipe=false`（其余均未落盘/走默认值）。

### 3.2 注入锚点与工作流

`SidePanel.on_plugin_load()`（`side_panel.py:545-565`）只装了 **2 个 Java MethodHook**：

1. **`org.telegram.ui.ChatActivity.createView(Context)`（after hook）** → `sp_attach(fragment, root)`
   - 在每个聊天页的根布局 `root` 上附加右侧抽屉面板（`panel_layout == 0`）或单页悬浮球（`panel_layout == 1` 且 `float_global == False`）。
2. **`org.telegram.ui.LaunchActivity.onResume()`（after hook）** → `sp_attach_global(activity)`
   - 仅在 `panel_layout == 1`（悬浮按钮模式）且 `float_global == True` 时启用，直接把悬浮球与弹出菜单挂到 `activity.getWindow().getDecorView().findViewById(android.R.id.content)`（`16908290`）上，通过 `TopFragment` 代理 `get_last_fragment()` 实现跨页面全局悬浮。

### 3.3 功能模块拆解

- **聊天数据源（`collect()`，L1608-1715）**：
  - `chat_mode == 0`（Selected）：解析 `selected_usernames_text` / `selected_chats` 中的 `@username`、`t.me/...` 或数字 ID；本地缓存找不到时异步发 `TLRPC$TL_contacts_resolveUsername` 解析。
  - `chat_mode == 1`（By category）：从 `MessagesController.getAllDialogs()` 拉主列表对话（跳过加密聊天与归档 `folder_id != 0`），按 `user / bot / group / channel` 开关与 `exceptions` 白名单、`hidden` 黑名单过滤，按最新消息动态置顶排序。
- **双形态 UI**：
  - **侧边抽屉（L2271-3596）**：右侧停靠条 + 把手（带未读聚合角标）+ 可选全屏遮罩 + 独立搜索按钮（长按可上下拖拽停靠顶/底并弹本地过滤搜索窗）+ 可选 14dp 右缘侧滑条 + 1 秒轮询自动淡出/收起。
  - **悬浮按钮（L1720-2269）**：48dp 可自由拖拽 FAB + 260×360dp 弹出列表 + 顶部 `+` 按钮一键将当前聊天加入面板。
- **交互能力**：
  - 单击打开聊天；若当前处于消息多选模式且 `forward_here=True`，通过反射读取 `ChatActivity.selectedMessagesIds` 并调用 `SendMessagesHelper.sendMessage` 转发。
  - 长按弹操作表（预览 Peek `presentFragmentAsPreviewWithMenu` / 打开资料 / 标为已读 / 静音切换 / 从面板隐藏）。
  - 列表项支持左右滑手势（左滑默认标已读、右滑默认静音切换，可配置为隐藏或关闭）。
- **外观体系**：5 套视觉风格（Classic / MD3 / iOS / Glass / AMOLED）+ 8 种开关动画（Slide / Spring / None / Zoom / Drop / Wave / Flip / Stretch）。

### 3.4 代码审计：实锤 Bug 与隐患清单

按严重程度排序（前两项已在真机 `plugins.log` 中复现）：

| 编号 | 级别 | 位置 | 问题与根因 | 实证 / 影响 |
|---|---|---|---|---|
| **B1** | **高（实锤崩溃）** | `side_panel.py:650-701` (`_make_slider_setting`) | `create_view` 中对 Chaquopy 包装的纯 Java 对象（`TextView`、`LinearLayout`）执行动态属性赋值 `value._sidepanel_seek = seek`、`root._sidepanel_seek = seek`。ExteraLess 的 `plugin_loader.py:1852` 仅允许 `PyProxy` 子类动态 `setattr`，普通 `JavaObjectBase` 无 `__dict__`，直接抛出 `AttributeError`。 | `plugins.log` 记录 **50 次** `custom settings view failed: AttributeError: 'TextView' object has no attribute '_sidepanel_seek'`。**全部 5 个滑块设置**（`panel_width`、`vertical_offset`、`count_custom`、`idle_seconds`、`idle_alpha`）在设置页无法渲染、无法调节。ExteraLess 原生已提供 `ui.settings.Slider`（`PluginSliderCell`），或改用闭包字典/`View.setTag` 即可彻底规避。 |
| **B2** | **高（实锤崩溃）** | `side_panel.py:921-924` (`sp_open_author_profile`) | 点击设置页底部「Found a bug / have an idea?」触发 `TL_contacts_resolveUsername("d1zzy412")` 后，回调错误读取 `response.user`（`TLRPC$TL_contacts_resolvedPeer` 只有 `.users` 列表和 `.peer`，无 `.user` 字段；同文件 L1563 写对了 `response.users`）。 | `plugins.log`（18:29:31）实锤抛 `AttributeError: 'TLRPC$TL_contacts_resolvedPeer' object has no attribute 'user'`，作者主页打不开。 |
| **B3** | **中（功能失效）** | `side_panel.py:802, 814` & `L1010-1089` | ① `Switch(key="show_pinned")`（「置顶聊天」）在两种模式下都渲染了开关，但整个插件没有任何地方调用 `get_setting("show_pinned")`，开关完全无效。② `sp_open_palette()`（调色板）与 `panel_color` 成了孤儿死代码，`create_settings()` 无入口且 `read_style()` 硬读主题色。 | 用户开关「置顶聊天」无任何效果。 |
| **B4** | **中（内存泄漏 & 性能风险）** | `side_panel.py:536-576`, `L2582`, `L3568-3595` | ① 只 hook `ChatActivity.createView`，**未 hook `onFragmentDestroy`**。每次打开聊天都会往 `self._sp_cleanups` 追加闭包并启动 1s 周期 `tick()`，关闭聊天后闭包列表永不释放，持续强引用已销毁 `ChatActivity`/View。② `self.sp_restyle` / `self.sp_reload` 为插件级单槽变量，后打开的聊天页会覆盖前一个回调。③ `tick()` 每秒在主线程遍历 `entries` 调 Java `isDialogMuted` 且反复 `new GradientDrawable()`，聊天多时容易逼近 ExteraLess `PluginsWatchdog` 的 250ms/s 主线程红线。 | 长时间多聊天切换后内存持续增长；设置页实时刷新只影响最后一个聊天实例。 |
| **B5** | **中（多账号失步）** | `side_panel.py:1220-1222` | `acc = UserConfig.selectedAccount`、`mc`、`me` 仅在 `sp_build()` 构建视图时快照一次。 | 开启全局悬浮球（`float_global=True`）时，切换 Telegram 多账号后，悬浮球里仍显示并操作旧账号的聊天列表（本机 ExteraLess 存在 `account1`~`account9` 多账号）。 |
| **B6** | **低（交互/本地化缺陷）** | `L1358`, `L1100/1119/1142/1613`, `L1980-1996`, `L225-253` | ① **群/频道资料页类名错**：`open_profile` 对 `did < 0` 调了 `org.telegram.ui.ChatProfileActivity`，已核对 `base.apk` 全部 7 个 DEX 均不存在该类（群/频道也用 `ProfileActivity` + `chat_id`），导致异常回退成直接进群。② **俄语硬编码**：即使选了英文，`Saved Messages` 在面板里硬写 `"избранное"`（L1613），隐藏/例外弹窗标题与按钮硬写 `"скрытые чаты"`/`"всегда показывать"`/`"готово"`/`"ок"`。③ **MD3 悬浮球图标色被覆写**：L1983-1986 设为 `on_primary` 后紧接着被 L1991-1994 覆盖成 `style["text"]`。④ **Glass 模糊方式**：展开时在主线程同步 `root.draw(canvas)` + CPU `stackBlurBitmap`，含视频/复杂聊天页易掉帧或黑块。 | 影响英文界面观感、MD3 对比度及群资料跳转体验。 |

### 3.5 本仓本地维护版：`side_panel.py`（已归档至本目录）

在保留原版全部功能（双形态侧边栏/悬浮球、5 套视觉样式、8 种动画、搜索框、预览、多选转发、滑动手势）的基础上，本目录已直接归档本地维护版 [side_panel.py](side_panel.py)（基于上游 `@d1zzy412 • @d1zplug` `v3.2.0`，当前本地版 `v3.2.3`，保留 `__id__ = "side_panel"` 与原授权记录）：

- **纯 Quick Bar「最近打开」排序**：彻底移除原版按来信/未读顶置的逻辑，改为实时 Hook `ChatActivity.createView` / `onResume` 记录每账号最近打开序列（`panel_recent_<acc>`，保留最近 120 个），并自动衔接宿主原生最近打开库（ExteraLess `BackButtonMenuRecent.getRecentDialogs` / NagramXF `RecentDialogsStore.getRecentDialogs`），仅展示置顶 + 最近打开的聊天。
- **全高伸展布局（对标 NagramXF Quick Bar）**：取消原版底部 `150dp` 死留白（`BOTTOM_MARGIN: 150 → 14`、`TOP_OFFSET: 10 → 2`），从顶栏下方 `6dp` 一直向下铺满到输入框/输入法上方 `6dp`，弹键盘时实时自适应收缩。

- **完整简体中文汉化**：新增 114 条 `TRANSLATIONS_ZH` 词条，语言选择器扩展为 `简体中文 / English / Русский`（默认简体中文），补全所有硬编码俄语弹窗与收藏夹标题。
- **修复搜索按钮与收藏夹重叠**：修正 MD3 风格下 `dock_px()` 返回 `0` 的布局 bug，所有风格统一让出 `SFD + 8dp` 间距，居中对齐搜索圆钮，收紧 `TOP_OFFSET`（`64 → 10`）。
- **新增侧栏本地置顶与分组**：按账号隔离持久化在 `panel_pinned_<acc>`；支持长按菜单置顶/取消置顶、可选「长按头像直接置顶（跳过菜单）」开关、左右滑手势新增「置顶/取消置顶」动作、头像 `★` 置顶角标（自动避开未读/提及/在线圆点）、置顶项固定排在最前及设置页「侧栏已置顶的聊天」管理弹窗。**收藏夹与置顶聊天合并为顶部置顶组**，与下方未置顶聊天视觉分离（MD3 风格同处上方圆角卡片并留 `9dp` 间距隔开下方未置顶卡片；经典 / iOS / 毛玻璃 / AMOLED 及悬浮按钮模式在两组间插入 1dp 细分割线）。
- **整体尺寸放大（+10% 基准）**：默认面板宽度 `60 → 66dp`（滑块范围 `48–110dp`）、头像 `38 → 42dp`、行高 `71 → 78dp`、字号 `10.5 → 11.5sp`、未读与 `★` 徽章 `14–16 → 16–18dp`、把手 `22×48 → 25×53dp`、搜索钮 `44 → 48dp`（随面板宽度等比缩放），悬浮球/菜单/搜索弹窗同步按 +10% 放大，放宽字号与角标缩放上限至 `1.45×`。
- **修复 4 项 ExteraLess 兼容与泄漏问题**：5 个滑块改用原生 `ui.settings.Slider`（彻底修复 `TextView._sidepanel_seek` 崩溃）、`show_pinned` 开关接入实际排序、`sp_open_author_profile` 改读 `response.users`、群/频道资料页改用 `ProfileActivity`，并新增 `ChatActivity.onFragmentDestroy` hook 退出即停表清理。

---

## 4. 跨客户端兼容：NagramXF（`fork.risin42.nagramx`）与 `liquid_glass_global` 排障

### 4.1 NagramXF 插件引擎对照

- **实机基线**：`fork.risin42.nagramx` `13.2.1-37a3538`（vc `1252`），已启用完整 exteraGram 兼容 Python 3.11 + `libaliuhook.so` 引擎（`elyxcore` SDK **`1.4.8.0`**，存放于 `files/chaquopy/plugins-sdk/` 与 `files/python_shims/`）；普通版 `xyz.nextalone.nagram` 不带插件引擎。
- **核心差异**：
  1. NagramXF **无 5 层权限沙箱**（无 `exteraless_plugin_grants.xml` 拦截）；
  2. NagramXF 的 `ui.settings` **不含 `Slider` 类**（仅 `Header/Divider/Switch/Selector/Input/Text/EditText/Custom/SimpleSettingFactory`）。本目录的 [side_panel.py](side_panel.py) 已内置 `Slider → 修复版 SimpleSettingFactory` 回退，可直接跨端运行。
- **双向兼容矩阵**：
  - `side_panel.py (v3.2.1)`、`ToolCollection (v2.0.9)`、`shortcuts (v1.3.2)`、`add_to_folder (v1.0)`、`advanced_search (v3.55)`、`channel_inspector (v2.2.0)`、`non_member_marker (v1.4.0)` 均与 NagramXF SDK/DEX 完全兼容；`all_chats_loader (v2.0.5)` 存在与 ExteraLess 相同的 L302 上游小 bug。

### 4.2 `liquid_glass_global.py`（v2.4.2 失效根因 → 本地双端适配版 `v2.6.0`）

1. **原版 `v2.4.2` 在 ExteraLess 失效的 3 层根因**：
   - **加载即崩（`plugins.log` 实锤 3 次）**：原版 L144 `from base_plugin import BasePlugin, BaseHook, PyMethodHook, PluginsController, XposedBridge` 在 ExteraLess 直接抛 `ImportError`（ExteraLess `base_plugin` 只导出 `MethodHook`）。
   - **Telegram `13.1.1` vs `13.2.1` `blur3` 类差异**：ExteraLess (`13.1.1`) 的具体渲染类为 `BlurredBackgroundDrawableRenderNode` / `BlurredBackgroundDrawableSource`（父类 `BlurredBackgroundDrawable` 为抽象类、未声明 `draw`），且改 `backgroundColor` 后必须置位 `renderNodeInvalidated = True` 才能首帧重录 RenderNode。
   - **权限与看门狗**：原版未声明 `__permissions__`，且 `tint_on_update` 触发 `capability_scan` 误报 `messages.read`；热路径每帧调 `get_setting()` 易撞 ExteraLess 250ms/s 看门狗。
2. **`v2.5.0` / 原版 `v2.4.2` 滚动时气泡跳变与不平滑的 5 层根因**（ExteraLess 13.1.1 `ChatActivity` / `ChatMessageCell` / `MessageDrawable` / `SizeNotifierFrameLayout` 反编译实证，且与机内当前主题 `remote6028185744684615194.attheme` 的照片壁纸 + 己方渐变气泡完全吻合）：
   - **硬件 RenderNode 冻结再骤跳**：对方消息与纯色己方消息的 `ChatMessageCell.drawBackgroundInParent()` 返回 `false`，气泡背景录制在子 View `RenderNode` 中；平滑滚动时 `RecyclerListView` 只平移子 View 而不重调 `onDraw()`，导致糊图随气泡僵直滑动，一旦触发 `invalidate()` 就瞬移跳变数百像素。
   - **`drawBackgroundInternal` 2/3 参双挂 + 己方气泡父/子双层覆盖**：`_hook` 未按参数个数过滤，同时挂住了 2 参公开版与 3 参内部版；更严重的是，己方渐变气泡在父列表 `drawChatBackgroundElements` 调 `(canvas, true)` 实时画完后，子 View `onDraw` 调 `(canvas, false)` 时 `before` 钩子早于原生 `if (!fromParent && drawBackgroundInParent()) return false` 执行，在子 `RenderNode` 上又盖了一张死糊图，滚动时跟着气泡滑动并跳变。
   - **1/7 缩放整型 `Rect` 量化阶梯与拉伸抖动**：`src.set(int((fx-ox)/7), int((fy-oy)/7), ...)` 将浮点坐标截断到 1/7 整型网格，每滚 7px 才跳 1 格且上下边界不同步跳变，造成明显的 7px 阶梯感与垂直呼吸拉伸。
   - **`backgroundCacheParams.path` 滞后与 NinePatch 空路径**：`before` 钩子跑在 `MessageDrawable` 更新 `lastRect` 之前，复用 cell 首帧因 `lastRect` 不一致直接跳过磨砂（清玻璃→磨砂闪烁）；而 NinePatch 对方气泡（`gradientShader == null`）在开启尾巴时甚至根本不生成 `path`。
   - **全局单例引用陈旧**：`_list_x`/`_list_y`/`_content_view` 只读一次，忽略 `cell.getPaddingTop()`、`transitionYOffsetForDrawables`、键盘 `bottomClip`、折叠屏展开/分栏调宽及多聊天切换；相册分组气泡走 `drawBackground(...)` 也未覆盖。
3. **本目录双端适配版 [liquid_glass_global.py](liquid_glass_global.py)（`v2.6.0`）**：
   - 保留 `v2.5.0` 的双端 `blur3`（13.1.1 RenderNode + 13.2.1 Impl）与最小权限 `["hooks"]`，并将 `_BeforeHook`/`_AfterHook` 未使用侧显式置 `None` 消除双端空回调。
   - **父列表逐帧绘制**：before-hook `ChatMessageCell.drawBackgroundInParent()` 使所有非分组气泡由父列表 `drawChatBackgroundElements` 每帧实时绘制（动态沿继承链挂钩 2 参 `drawChatBackgroundElements(Canvas, RectF)`，不硬编码 `$22`），彻底消除 `RenderNode` 冻结跳变。
   - **精确重载 + GPU `BitmapShader` 子像素绘制**：按参数个数精确挂钩 2 参 `drawBackgroundInternal(Canvas, boolean)`、9 参分组 `drawBackground(...)` 与 2 参 `MessageDrawable.drawCached(Canvas, PathDrawParams)`；在 `drawCached` 前用 `BitmapShader(CLAMP)` + `Matrix(7.0, postTranslate)` 调 `drawable.draw(canvas, shaderPaint)`，绕开 NinePatch 分支、首帧实时生成精确轮廓、实现浮点子像素平滑采样与原生抗锯齿圆角。
   - **按 `SizeNotifierFrameLayout` 隔离缓存 & 零重入透明度**：每帧在列表层一次性读取壁纸/列表偏移，自动感知尺寸变化/换壁纸/主题渐隐与 `bottomClip`；`MessageDrawable.setAlpha` 改按比例缩放并直写字段零重入。

---

## 5. 原创插件：`folder_hide_archived.py`（归档即全隐，v1.0.0）

**问题**：Telegram 原生 `MessagesController$DialogFilter.includesDialog()` 判定顺序为 `neverShow → alwaysShow → (folder_id!=0 && EXCLUDE_ARCHIVED=128) → 类型条件`。归档只把 `folder_id` 置 1，于是只从「全部」消失；自定义文件夹未勾「排除已归档」时照样显示，手动加入（`alwaysShow`）的聊天即使勾了也照样显示。

**做法**（纯本地，不改服务器文件夹设置，仅需 `hooks` 权限）：

- after-hook `MessagesController.sortDialogs` / `updateFilterDialogs`：用 `HashSet(dialogsByFolder[1])` 对当前文件夹的 `dialogs` / `dialogsForward` 做一次 Java `removeAll`，无逐条 Python 循环，避开 250ms/s 看门狗。
- before/after-hook `MessagesStorage.calcUnreadCounters`：临时给每个文件夹补 `EXCLUDE_ARCHIVED`，结束即还原，修正文件夹标签未读角标。
- 已知限制：手动加入且已归档的聊天，角标仍可能被原生 `alwaysShow` 计数分支计入（列表里已隐藏）。

锚点来源：`cache/tg-clients/exteraless/jadx_filters/`（ExteraLess 13.1.1 单类反编译 `MessagesController` / `MessagesStorage`）。

---

## 6. `advanced_search.py`（高级搜索，本地维护 v3.56.2：折叠屏双栏）

基线是 `@night9977` 的 v3.55（设备原副本 SHA-256 `83645173…0758849`，内嵌 DEX `e183527d…dc0eb`）。本地版 [advanced_search.py](advanced_search.py) **不改 DEX**，只在 Python 层补了双栏路由：
- `3.56`：初版因 `find_class()` 返回 Chaquopy 类型代理而非 `java.lang.Class` 导致挂载回退；
- `3.56.1`（实机验收通过）：改用 `Class.forName` / `.getClass()` 与 `_jbool()` 拆箱；真机 `plugins.log` 实证 `22:50:10.380 已挂载 3 个 hook (isTablet=True)`、`22:50:16.441 presentFragment ok=True routed_right=True`，点击全局搜索结果已在右半边打开；
- `3.56.2`（本地已修，待按需同步设备）：从 `22:50:20.837` 实机日志抓出左栏切会话替换右栏时 `dlg.discardDialog()` 报 `AttributeError`（动态 DEX 类代理不暴露属性方法），已改为预缓存 `Method.invoke(dlg)` 彻底销毁隐藏弹窗，避免 `Dialog` 残留。

**问题**：vivo X Fold6 内屏（2312×2504 @462dpi，约 800dp）下 `AndroidUtilities.isTablet()` 为真，Telegram 是左右双栏。点全局搜索结果后，聊天却开在**左栏**。

**根因**（DEX 反编译：`cache/tg-clients/exteraless/advsearch/src/`）：`AdvancedSearchDialog.presentFragment(Object)` 调用的是
`ownerLayout.presentFragment(frag, false, true, /*check*/false, false)`。
`ActionBarLayout.presentFragment(NavigationParams)`（13.1.1 L2942-2954）只有在 `checkPresentFromDelegate=true` 时，才会去问 `LaunchActivity.needPresentFragment`，而双栏路由（ChatActivity → `rightActionBarLayout`）就是在那里做的。check=false 绕过了路由，聊天就直接压进了 owner（左栏 DialogsActivity）所在的 `actionBarLayout`。

**修法**（3 个 Python hook，只在 `isTablet()` 为真时生效，否则全部放行）：

1. `AdvancedSearchDialog.presentFragment(Object)`（before）：遇到 ChatActivity 时，先按原逻辑校验，再通过 `INavigationLayout` 接口显式调 5 参 `presentFragment(..., check=true)` 交给宿主路由（若 `ownerLayout` 本身就是右栏则用 `removeLast=true, check=false` 直接替换右栏）。宿主转交右栏后自己会返回 `false`，所以改看 `fragment.getParentLayout() != null` 来判定成功，让 DEX 照常记录 `pendingRestore` 并隐藏面板。右栏聊天按返回关掉后，搜索面板照旧恢复。
2. `ActionBarLayout.removeAllFragments()`（before/after）：标记「右栏正在被清空替换」。
3. `AdvancedSearchDialog.onChatClosed(Object)`（before）：如果搜索打开的那条聊天是被右栏新会话挤掉的（比如在左栏点了别的聊天），就清空 `pendingChatFragment`/`pendingRestore`、截断原恢复逻辑并反射调用 `discardDialog()` 彻底销毁隐藏的搜索弹窗，不再全屏弹回来。

已知限制：搜索面板本身还是全屏 `Dialog`，没有做成「左栏留着结果、右栏看聊天」的常驻主从布局。真要做，得改 DEX 里的 Dialog 窗口尺寸与 `hideForNavigation` 流程。

---

## 7. 原创通用插件：`tablet_split_resize.py`（分栏宽度拖拽，v1.0.0）

**背景**：实机 11 个 Telegram 客户端全量 DEX 核查确认，平板/折叠屏左右拖拽调宽把手是 exteraGram 上游独创的 `com.exteragram.messenger.utils.ui.TabletSplitUiHelper`（AyuGram 直接基于 exteraGram 全量源码构建，因而继承）；ExteraLess（底层为 Nekogram/Nagram 底座）、NagramXF、AlexGram 及 Telegram 官方均无此组件，左栏宽度写死为 `max(320dp, 35%)`。

**本目录原创通用插件 [tablet_split_resize.py](tablet_split_resize.py)**（纯 Python、零内嵌 DEX、仅需 `["hooks"]` 最小权限，已通过 ExteraLess 真实 `capability_scan.pyc` 扫描验证）：

- **跨客户端自动适配与防冲突**：检测到宿主内置 `TabletSplitUiHelper` 或 `LaunchActivity.tabletSplit`（exteraGram / AyuGram）时自动待机；对 ExteraLess (`13.1.x`) / NagramXF (`13.2.x`) / AlexGram 等则通过 `LaunchActivity.launchLayout` 运行时类动态挂载 `onMeasure` / `onLayout`，不硬编码匿名类序号（`$9` / `$10` 均通吃）。
- **零看门狗压力视觉复刻**：在 `launchLayout` 中插入原生 `FrameLayout`（位于 `rightActionBarLayout` 之后、`shadowTablet` 之前），由 `12dp` 灰槽 + 4 张 `16dp` 内凹圆角位图 + 原生 `ViewPropertyAnimator` 胶囊把手 + 独立居中触摸热区组成；平时正常聊天/滑动列表 0 次 Python 回调，避开 ExteraLess 250ms/s 看门狗。
- **完整交互与可调选项**：支持原版圆角沟槽 (12dp) / 紧凑细线 (2dp) / 隐形热区三种样式、左栏最小宽度 (`240–360dp`)、热区宽度、默认 35% 磁吸 + 触觉震动、双击把手复位、松手持久化比例并等价复现 `ChatActivity.onTabletSplitChanged()` 刷新右侧消息排版、主题切色同步及热启停即时生效/还原。

锚点来源：`cache/ayugram_study/`（AyuGram 13.1.1 / exteraGram 13.2.2 `TabletSplitUiHelper` 与 ExteraLess 13.1.1 / NagramXF 13.2.1 `LaunchActivity`、`AndroidUtilities`、`ChatActivity` 反编译对照）。

---

## 8. 经验总结：折叠屏双栏路由 & Chaquopy 插件排障铁律

从本轮 `side_panel` / `liquid_glass_global` / `folder_hide_archived` / `advanced_search` 连续排障中沉淀的可复用经验：

1. **Telegram 折叠屏 / 平板双栏路由契约（`ActionBarLayout` × `LaunchActivity`）**
   - 双栏分派全部收口在 `LaunchActivity.needPresentFragment(INavigationLayout, NavigationParams)`，但 `ActionBarLayout.presentFragment` 仅在 **`checkPresentFromDelegate=true`** 时才调用它；传 `check=false` 会绕开分栏、直接压进左栏 `actionBarLayout`。
   - 宿主把 `ChatActivity` 转交给 `rightActionBarLayout` 后，`needPresentFragment` 固定返回 **`false`**（阻断原左栏继续压栈）。调用方不能用 `presentFragment(...) == false` 判失败，必须补查 `fragment.getParentLayout() != null`。
   - 关闭右栏聊天的两条路径语义不同：按返回键/关页走 `ActionBarLayout.closeLastFragment()`（不调用 `removeAllFragments()`），左栏切新聊天或切号清栈则走 `rightActionBarLayout.removeAllFragments()`。依赖 `onFragmentDestroy` 做「返回恢复弹窗」的插件，必须用 `removeAllFragments()` 区分「被新会话替换」与「用户主动返回」。
   - 只要交给原生 `check=true`，`LaunchActivity.tabletFullSize`（分屏/小窗单栏与内屏双栏切换）由宿主自动处理，无需在插件侧重复造轮子。
2. **Chaquopy + 内嵌 DEX（`InMemoryDexClassLoader`）反射四连坑**
   - **`find_class(name)` 不是 `java.lang.Class`**：它返回 Chaquopy Python 类型代理，直接调 `.getDeclaredMethods()` / `.getField()` / `.isInstance()` 会抛 `AttributeError`；凡要拿 `java.lang.Class`，统一走 `java.lang.Class.forName(name)` 优先、回退 `proxy.getClass()`。
   - **动态 DEX 类实例别直接点属性调方法**：即使 `AdvancedSearchDialog.discardDialog()` 是 `public`，经 `Field.get()` 拿回来的动态类实例在 Chaquopy 代理上也可能缺失方法属性（实测抛 `AttributeError`）；统一在 `install()` 时预缓存 `java.lang.reflect.Method` 并用 `.invoke(obj, ...)` 调用。
   - **`Method.invoke()` 返回 `Boolean.FALSE` 必须显式拆箱**：反射返回声明为 `Object`，未拆箱的 Java `Boolean.FALSE` 在 Python 里是非空对象、`bool(val)` 为 `True`，务必走 `.booleanValue()`。
   - **Java 8 接口 `default` 方法从接口反射**：像 `INavigationLayout.presentFragment(BaseFragment, boolean, boolean, boolean, boolean)` 这类接口默认方法，直接从 `INavigationLayout` 取 `Method` 调用最稳，避开实现类重载解析分派差异。
3. **落盘日志验尸 & ADB 零临时文件推送**
   - **体感「生效」后仍要扫 `plugins.log`**：本次 `v3.56.1` 用户体验已正常，但回看 `plugins.log` 立刻抓出了左栏替换时 `discardDialog` 的隐藏泄漏报错；所有 `except` 分支都应落日志，绝不静默 `pass`。
   - **Windows `adb shell -T` 传 CRLF 脚本会吞 `\r`**：直灌 `cat > target` 会导致字节数与 SHA-256 偏移；改用 `base64 -d > target` 管道（不落设备临时文件）再立即做设备端 `sha256sum` + `ls -la` 对账，可保证字节、权限与属主 100% 精确一致。
