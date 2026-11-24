# vivo 输入法 Pro（`com.vivo.ai.ime.nex`）

> **状态：活跃开发中（当前模块 `0.1.8` / `local.codex.vivoime`）。** 已完成可注入性调研与锚点台账，并落地英文长按 M 出 `/`、顶栏槽位扩展与文本编辑入口放开三项 hook（详见 §10）。
>
> **结论：可以 hook。** 宿主没有加固壳、没有 Xposed/Frida 检出、没有签名自校验；R8 只压短名和部分工具类，`com.vivo.ai.ime.*` 等业务包名、Kotlin 元数据和字符串都还在，JADX 1.5.6 能直接出源。真正要小心的不是"能不能注入"，而是它**是默认输入法**——崩了就是没键盘。

## 1. 适配基线

| 项目 | 值 |
|---|---|
| 设备 | vivo X Fold6（PD2545D / V2545A），Android 17 / OriginOS 7 |
| 包名 | `com.vivo.ai.ime.nex` |
| 显示名 | vivo 输入法 Pro（资源串 `vivo IME Pro`） |
| 版本 | `3.9.5.2611202` / `39522`（minSdk 29，targetSdk 37，compileSdk 36） |
| 安装形态 | 系统应用被覆盖升级：`/data/app/~~Z-WMfHP7j_t-tFC58q5Lmw==/com.vivo.ai.ime.nex-EtPU9zrcXUyZs2aEVyD7uw==/base.apk`（单 APK，无 split） |
| 出厂副本 | `/system/priv-app/JoviIme`，`3.9.4.2611041` |
| APK 大小 | 207,931,178 字节（198.3 MiB） |
| APK SHA-256 | `35a91b28549aee9c8b5ebebc131ab2cff29f28c5043d8844e6bf5bf61387cc5f` |
| 签名 | v3 scheme，当前 `abd0c91b`，历史 `cd97f879`（vivo 平台签名） |
| 包标志 | `SYSTEM`、`PRIVILEGED`、`UPDATED_SYSTEM_APP` |
| 默认输入法 | `com.vivo.ai.ime.nex/com.vivo.ai.ime.main.IMEService` |
| 进程 | 主进程 + `:vivoasr` + `:webView` + `:crash`；采样时主进程 17405、`:vivoasr` 17927 |
| 上次更新 | 2026-11-24 08:49:23（应用商店路径，非本地安装） |

组成：4 个 dex（6.0 / 9.4 / 10.3 / 2.1 MB）+ 42 个 `arm64-v8a` so + assets 解压后 223 MB（其中离线模型 96.3 MB、离线语音 83.1 MB）。业务类 1,983 个（`com/vivo/ai/ime/`），全包 23,142 个唯一类描述符，JADX 处理 9,070 个类、93 个反编译失败。

R8 映射标识：`r8-map-id-527c7203818c5e06744d88e5c4a3971d93ee58513b2fe1931e2b5b2a8cd3bb50`（映射文件未随包发布，重命名不可复原，只能靠符号锚点）。

## 2. 可注入性证据

| 检查项 | 结果 | 依据 |
|---|---|---|
| 加固 / 壳 | 无 | 无 `libjiagu`/`libDexHelper`/`StubApp` 一类 so 与类；`IMEApp` 直接是 Application |
| 反 Xposed / Frida / Substrate | 未发现 | 全 dex 扫描仅命中 `org.lsposed.hiddenapibypass`（HiddenApiBypass 库，非检测）与微信/崩溃 SDK 的普通字样 |
| 反调试 | 未发现 | so 里唯一的 ptrace 是 `libcrashcollector.so` 内置 Breakpad；`libvivosgmain.so` 读 `/proc/self/maps`，但 SecuritySDK 默认关闭（`mEnableSecuritySDK : false`，`com/bbk/account/base/h.java:51`） |
| 签名 / 安装来源自校验 | 无 | 全源码无 `GET_SIGNATURES`、`SigningInfo` 校验；`checkSignature=` 只出现在微信 SDK 对**对方**应用的判断 |
| 动态 dex 加载 | 无 | 无 `DexClassLoader`/`InMemoryDexClassLoader`；`vcodeless.PluginAgent` 只是应用内 AOP 事件分发，不下载代码 |
| 原生保护 | 无 | 42 个 so 全是输入引擎、语音、手写、模型、崩溃采集、hdiff 补丁（`libsfpatch.so`）、OpenSSL（`libvcipher.so`） |
| 可注入点 | 有 | `IMEApp`（Application）+ `IMEService.onCreate` 都已定位，见 §5 |

结论：Vector 已就绪（KernelSU + Zygisk Next + Vector 3080-88f8e1fa），模块勾 `com.vivo.ai.ime.nex` 后由 zygote 注入该包所有进程。注入已实测通过（`VectorLegacyBridge` 日志 + 键盘行为验证）。

## 3. 作用域与进程

- 作用域只勾 `com.vivo.ai.ime.nex`，`system_server`/`android` 一律不勾。
- 该包声明了 4 个进程：主进程、`:vivoasr`（语音识别 SDK）、`:webView`（网页皮肤/运营页）、`:crash`（崩溃 SDK）。Vector 按包注入，四个进程都会进；模块里用 `processName` 区分，非主进程默认不加载 UI 改法。
- `:vivoasr` 有 `directBootAware` 服务，开机锁定前就可能拉起；不要在 `attachBaseContext` 阶段做重活，也不要在 direct-boot 状态下读用户数据。
- 宿主会被商店静默升级（`UpdateReceiver` 收 `MY_PACKAGE_REPLACED`/`BOOT_COMPLETED`），锚点随版本漂移，必须做版本校验 + 失败放行。

## 4. 反编译现状

JADX 1.5.6，`-r` 跳资源，8 线程，约 6 分钟出 14,653 个 java 文件：

```sh
jadx -r -j 8 -d jadx base.apk     # 输出约 112 MB
```

命名分布（决定锚点写法）：

- **保留原包名**：`com.vivo.ai.ime.*`、`com.vivo.ai.gpt*`、`com.vivo.ic.*`、`com.vivo.aicreation.*`、`com.vivo.speechsdk.*`、`com.xiaojinzi.component.*`。
- **R8 短名**：`gg.c`（设置中心）、`hx.d`（KV 存储）、`oa.a`（自定义 Application 基类）、`ab.q`/`ab.m`（引擎命令总线）、`sl.*`（日志）、大量 `a0`/`b8`/`v7` 工具类。这些**不能靠名字硬编码**，要先按调用方定位，再用方法签名兜底。

写锚点的两条纪律：优先用「保留包名 + 方法名」；遇到短名类，先找到业务侧调用点（例如 `IIMESettingImpl` 调 `gg.c`），再按参数类型和返回值匹配，别直接写 `gg.c.b`。

## 5. 可 hook 面（已核对锚点）

行号均指 JADX 输出（`cache/bases/jovi-ime-3.9.5.2611202/jadx/sources/`）。

### 5.1 入口层

| 锚点 | 位置 | 用途 |
|---|---|---|
| `com.vivo.ai.ime.IMEApp`（`extends oa.a`，`AppFunctionConfiguration.Provider`） | `IMEApp.java` | 进程级最早入口，`attachBaseContext` 与 `onCreate` 都在这里；四个进程共用 |
| `IMEService.onCreate()` | `main/IMEService.java:1938` | 服务初始化；整段有 `try/catch`（"Unexpected exception during service creation"），宿主自身容错较好 |
| `IMEService.onCreateInputView()` | `main/IMEService.java:1981` | 从单例 `oj.c.q().f19786b` 取预热点键盘视图，UI 改法的主要入口 |
| `IMEService.onStartInputView(EditorInfo, boolean)` | `main/IMEService.java:2727` | 每次唤起键盘；适合做按应用/按输入类型的条件逻辑 |
| `IMEService.onFinishInput()` | `main/IMEService.java:2439` | 收键盘 |

### 5.2 设置层（一次 hook 覆盖大部分开关）

| 锚点 | 位置 | 说明 |
|---|---|---|
| `gg.c.b(String)` | `gg/c.java:108` | 布尔读取，内部是巨大 `switch`（`cloudInputSetting` 默认 true、`showSmsCode`、`autoUpdateHotWordsSwitch`…），未命中回落缓存/默认 false |
| `gg.c.c(String)` | `gg/c.java:268` | 整型读取，未命中走 KV 默认 0 |
| `gg.c.e(String, boolean)` / `gg.c.f(String, int)` | `gg/c.java:388` / `:408` | 写入并落 KV（带 `ModifyTime` 记录），设置界面走这里 |
| `gg.c.d()` | `gg/c.java:277` | 启动时把默认值灌进内存缓存，能看到完整默认值清单 |
| `com.vivo.ai.ime.setting.IIMESettingImpl` | `setting/IIMESettingImpl.java` | 业务包装：`getSecureMode():78` / `setSecureMode():165`（键 `inputmethodLocalMode`，**0 = 本地模式**，非 0 = 在线），`setTraditionalSwitch`、`setChineseAssociate` 等 |

### 5.3 引擎命令总线

`com.vivo.ai.ime.datamanager.service.DataManagerImpl.execute(ab.q, Object...)`（`datamanager/service/DataManagerImpl.java:279`）是 Java 侧对输入引擎的统一命令入口，`ab.q` 枚举 60+ 条，含 `SET_EMOJI_SWITCH`、`SET_RECOMMEND_SWITCH`、`SET_TRADITIONAL_SWITCH`、`SET_NEX_LOCAL_MODE`、`UPDATE_CANDIDATE_LIST`、`RECORD_COMMIT` 等。

这是一个**收口很漂亮但风险也集中**的点：候选/上屏是高频路径，在这里加逻辑要保证零分配、零异常。

### 5.4 云端与上报

| 锚点 | 位置 | 说明 |
|---|---|---|
| `OperationModule.getNetworkReleaseConfig()` | `operation/OperationModule.java:47` | 读 KV `NETWORK_RELEASE_CONFIG`，默认 **true**；`setNetworkReleaseConfig():60` 可关运营网络 |
| `OperationModule.getOperationEnv()` | `:55` | 环境取自 prop `com.vivo.ai.ime.operation.env` 或 KV |
| `CloudWordModuleImpl.requestCloudWord/requestChatSendRecommendWord` | `operation/CloudWordModuleImpl.java` | 云词/推荐句走 WebSocket + protobuf + AES-GCM，有连接态判断 |
| `UploadManager.requestUploadContact/requestUploadMemory` | `operation/business_network/upload/logicmanager/UploadManager.java:34/:39` | 联系人词库与"记忆"上传 |
| `IUpgradeManagerImpl.isNeedUpgradeCheck()` | `setting/IUpgradeManagerImpl.java:16` | 更新检查闸门；`versionUpgradeCheck(int):25` 是触发点 |
| `com.vivo.ic.dm.datareport`、`com.vivo.dcs.sdk`、`com.vivo.vcode.TrackerConfig` | — | 统计/系统数据中心/埋点三套上报通道，各自独立 |
| `VCODAICreationClientAssistant.USER_EXPERIENCE_IMPROVE_PLAN` | `aicreation/kit/VCODAICreationClientAssistant.java:35` | 键为 `user_experience_improve_plan`，读 `Settings.System`（本机写它不需要 hook，但要 `WRITE_SECURE_SETTINGS`/shell） |

### 5.5 UI / 运营位

- `TopBarView`（`ui/panel/view/toolbar/TopBarView.java`）：`t.AgentBanner` 在 `:225` 挂载，`:415` 另有 `FuncTips`、`ExclusiveWordList`、`Loading` 等状态视图。这是"AI 助手横幅/专属词"一类运营位的落点，**视觉对应关系还需真机确认**。
- 面板体系：`ui/panel/*`、`ui/richtext/*`（富文本候选）、`operation/ui/*`（运营页）。

### 5.6 其它

- `com.vivo.ai.ime.vcodeless.PluginAgent.aop(...)`：应用内 AOP 分发（`10123` 是 FFPM 事件），不是代码注入框架，和我们的 hook 互不干扰。
- 验证码相关：**没有** `RECEIVE_SMS`/`READ_SMS` 权限，也没有 `content://sms` 访问；`showSmsCode` 只是"在候选里显示验证码"的开关，数据来自 Messages/vcode SDK。
- AppFunctions：宿主向 Android 17 的 `device_state` 域暴露了输入法设置读写（`androidx.appfunctions.service.PlatformAppFunctionService`），是另一条"不改包也能改设置"的通道，权限受系统管控。

## 6. 候选功能（按 收益/风险 排序）

| 目标 | 主要锚点 | 难度 | 风险 | 备注 |
|---|---|---|---|---|
| **去更新提示** | `IUpgradeManagerImpl.isNeedUpgradeCheck` → false；`versionUpgradeCheck` 空实现 | 低 | 低 | 改动最小，适合当首个真功能 |
| **去运营/AI 横幅** | `TopBarView` 的 `t.AgentBanner` / `ExclusiveWordList`；必要时叠加 `OperationModule.setNetworkReleaseConfig(false)` | 中 | 低-中 | 需要真机对照截图确认哪块是广告位 |
| **断云 / 隐私强化** | `inputmethodLocalMode=0`；`SET_RECOMMEND_SWITCH`、`cloudInputSetting=false`；`UploadManager` 两处上传 | 中 | 中 | 会连带关掉云词、AI、语音在线识别，需按用户取舍 |
| **关埋点/体验计划** | `user_experience_improve_plan`（Settings.System）、`com.vivo.ic.dm.datareport`、`TrackerConfig` | 低-中 | 低 | 前者不 hook 也能改，后者要看是否影响崩溃排查 |
| **解锁/增强设置** | `gg.c.e/f` 强制值 + 设置界面项 | 低 | 低 | 收益不确定，需要先列"本机被隐藏了哪些开关" |
| **输入内容拦截/改写** | `DataManagerImpl.execute`（`RECORD_COMMIT`/`SET_COMPOSING`）、InputConnection 侧 | 高 | 高 | 高频热路径，不建议第一版碰 |

## 7. 风险与红线

1. **默认输入法 = 崩溃即没键盘。** 模块异常会让 `IMEService` 起不来，输入框直接无法输入。宿主的 `onCreate` 有 try/catch，但挡不住注入期和 UI 期的异常。
2. **必须有逃生路径。** 本机已启用 `com.baidu.input_vivo` 与微信输入法插件（`com.tencent.wetype/.plugin.hld.WxHldService`），出事可切换；模块自身所有 hook 一律 `try/catch` + 失败放行 + 只在主进程装 UI 改法。
3. **不做任何重启类操作**（仓库 §1 红线）；安装模块仍走"屏幕解锁 + 用户在场 + `adb install`"，失败立即停。
4. **宿主会自己升级**（2026-11-24 刚升过一次，出厂副本 3.9.4）。锚点按版本守卫，缺失就整体禁用，不要"猜着 hook"。
5. **别碰 native 引擎路径**：`libime_engine.so`、`libgeneralcore-jni-v1001.so`、`libpinyin_wrapper` 等负责候选与解码，改动收益小、崩溃代价大。

## 8. 首版探针（待授权）

目的：只验证"Vector 能否注入 + 四个进程各是什么"，不改任何行为。

- hook `IMEApp.attachBaseContext` 与 `IMEService.onCreate`，打印 `processName`、`versionName`、加载器与 `inputmethodLocalMode` 当前值。
- 日志只走 `XposedBridge.log` / logcat；**不在设备上写任何调试文件**（仓库「设备不是草稿纸」约定）。
- 验收：能连续唤起键盘 10 次不崩、`pidof com.vivo.ai.ime.nex` 不变、日志里四个进程都有记录。

通过后再按 §6 选一项落地。构建沿用 `original-modules/vivoportal/build.sh` 那套（`shared/build-lib.sh` + aapt2 + d8 + apksigner + `assets/xposed_init`），包名暂定 `local.codex.vivoime`，签名新建 `shared/signing/vivoime.keystore` 并按 `shared/signing/README.md` 登记。

## 10. 首版目标：两处键盘行为修正

2026-11-24 立项，用户提出两个具体问题。两条都已在反编译源码里定位到唯一根因，不涉及 UI 重构。

### 10.1 英文 26 键 M 的长按符号：`\` → `/`

**根因在键盘布局 XML，不在代码逻辑。** 键盘布局是 `res/xml` 资源，由 `ah.b` 解析成 `uc.f`（软键模型）。

| 布局 | M 的 `label_secondary` |
|---|---|
| `en_26_normal` / `_float` / `_fold` / `_fold_split` / `_land` / `_land_split` / `_new_fold` / `_new_fold_split`（8 个） | `\` |
| `en_26_num_*`（6 个） | `\` |
| `pinyin_26_*`（8 个，中文 26 键） | `/` |
| `email_normal` | `/` |
| `web_normal` | `,` |

也就是说中文 26 键早就是 `/`，只有英文 26 键写成 `\`。`label_secondary` 同时决定键面右上角的小字和长按气泡/上屏内容：

- 解析：`ah.b.f(XmlResourceParser, a)` 把属性读进 `strH2`，再 `fVar2.g = strH2`（`ah/b.java:171`、`:242`）。`long_press_code` 默认回落到 `code`（`:172`），所以 M 的长按码仍是 41（`KEYCODE_M`）。
- 展示：`jl.o.E()` 读 `f13124f0`（构造时取自 `fVar.g`），`jl/h.java:366` 处赋值；对 `"`、`'`、`;` 等还有单独的偏移修正。
- 长按：`mi.l.N(key)` 用 `key.g` 弹单字气泡（`mi/l.java:2026` 附近 → `x(key, label, key.g, …)`），英文 presenter `j9.e`（`extends j9.d`）继承该路径；松手上屏走 `SoftKey.getCommitText()` 与 `BY_LONG_PRESS_POPUP` 分支。

**改法：** hook `ah.b.f` 的返回值，条件 `keycode == 41 && "\\".equals(field g)` 时把 `g` 改成 `/`。这个条件天然只命中英文 26 键——中文 26 键、email 布局的 M 本来就不是反斜杠，不会被误改。改完后键面小字、长按气泡、上屏字符、无障碍播报（`sym_en_zxiegang` = 斜杠）一起变。

不需要改 XML 资源、不需要重打包宿主，也不碰引擎。

### 10.2 顶部快捷栏 5 个上限

**根因是显示列表被硬截断，不是设置页不给加。**

| 位置 | 代码 | 作用 |
|---|---|---|
| `bj.a.q(boolean)`（`:720`） | `int iMin = Math.min(arrayList3.size(), 5); for (i = 1; i < iMin; i++)` | 非分离键盘分支，顶部栏最多拼进 5 个用户工具 |
| `bj.a.q(boolean)` 分离键盘分支 | `size() > 2` / `size() > 4` + 两个 `SPLIT_EMPTY` 占位 | 折叠/分离键盘固定 1+2+2 布局 |
| `xh.e.d(int, Object)`（`:34`） | `int i10 = customToolBarView.L ? 7 : 5` | 加工具被拒时的"最多可添加 N 个常用工具"提示；`L` 是横屏标记 |
| `TopAdapter.onBindViewHolder` | `size = (config.L - 2*n - total*iC) / (total-1)` | 图标间距按剩余宽度均分；总数变多时间距会变小甚至为负 |
| `bj.a.v()`（`:991`） | 读 KV `custom_topToolBarData` | **完整**的顶部工具列表，没有截断，是扩充时的数据源 |

设置页和运行期用的是同一份列表：`CustomToolBarView.r()` 与 `ui/panel/view/toolbar/a.java:323` 都调 `c.y().q(false)`。所以只要让 `q()` 不再截断到 5，配置页的槽位和键盘上的图标会一起变多；`q()` 的默认列表是 `[TOOL_BOX, 工具…, KEYBOARD_HIDE]`，首尾两个由 `TopAdapter` 隐藏（`:194`），实际可见的就是中间那段。

**改法：** hook `bj.a.q` 返回值，在末尾 `KEYBOARD_HIDE` 之前补入 `v()` 里被砍掉的项（按类型去重），把用户槽位从 5 提到目标值；分离键盘分支不动。**宽度是硬约束**：每个图标约 42dp，`TopAdapter` 均分间距，9 个总项在 360dp 宽度上间距会算成负值，实际能稳定放下的上限大约 6 个可见（总项 8 个），7 个要真机看是否重叠。默认值先取 6，留一个开关再加。

### 10.3 文本编辑工具（光标 / 全选 / 复制 / 剪切 / 粘贴 / 删除）全场景启用

“移动光标、全选、删除”不是三个工具，是同一个 `TEXT_EDITOR`（显示名“文本编辑”）。点开是 `TouchBarPresent`（`ai/k.java`，present type 31），真正的按钮在 `touchBar/TouchBarParentView`：`touch_select`（全选，`setSelection(0, len)`）、`touch_copy`、`touch_cut`、`touch_paste`、`touch_delete`，加上滑动的 `TouchBarPanelView` 负责移光标。全部走标准 `InputConnection` / `CursorAnchorInfo`，没有私有依赖。

卡的是**入口可见性**，不是实现：

| 位置 | 逻辑 |
|---|---|
| `bj.f.p()`（`:940`） | `TEXT_EDITOR` 的支持性直接读 `ol.q.f15990b` |
| `mj/i.java:163` | `f15990b = getServedViewType() != -100`，通过 `sl.k0.a` 反射调目标输入框的 vivo 私有方法 |
| `ui/panel/view/toolbar/a.java:379` | 渲染前过滤 `f1071p && p()`，任一为 false 就不画 |
| 本机 `custom_topToolBarData` | 列表里根本没有 `TEXT_EDITOR`：5 个槽位是 表情/键盘选择/**文本扫描**/剪贴板/语音；文本扫描 `occupy=false`，被 `f1071p && p()` 过滤后留下一个空白格 |

对照其他输入法：它们不需要 `getServedViewType`，直接用 `InputConnection.getExtractedText()` + `setSelection()` 做全选/移光标，用 `ClipboardManager` + `commitText()` 做粘贴，用 `sendKeyEvent(KEYCODE_DEL)` 做删除。vivo 其实也写了这套——`ai/c.g()` 在 `getServedViewType != 1` 时会切到 `ai.m`（`WebViewTypeTouchBarProcess`，用 `y8.b` 这个 WebView 输入连接实现）——只是工具入口被前面的支持性判定藏了。

**改法（只放开入口，不伪造环境）：**

- hook `bj.f.p()`：类型为 `TEXT_EDITOR` 且原返回 false 时改 true。
- hook `bj.a.v()`：顶部列表加载后，没有 `TEXT_EDITOR` 就用 `new bj.f(bj.n.TEXT_EDITOR)` 插一个，置 `occupy=true`、`location=1`；顺手丢掉 `occupy=false` 或当前场景不支持的工具（本机就是未授权的“文本扫描”，也就是那个空白格）。
- hook `bj.a.q()`：非分离布局下按“真正可见”的数量补回被 `Math.min(size, 5)` 截断的项；分离/折叠布局走宿主原有分支，不动。
- 不伪造 `getServedViewType`。点击后仍由 `ai/c.g()` 自己选真触控栏（`ai.b`）还是 WebView 兜底（`ai.m`），兼容性交给宿主原有分支。

**验证（2026-11-24，模块 0.1.7 / 0.1.8）**：在系统短信 `com.android.mms` 里唤起键盘，顶部工具栏出现 `⇤I⇥` 图标，点开是“文本编辑”面板：全选 / 剪切 / 复制 / 粘贴 / 删除 + 滑动移光标区域，全部可用。说明走的是兜底实现，没有依赖 `getServedViewType`。

排查笔记：早期用 MMKV 里 `type:9` 推断“文本编辑 occupy=false”是错的。定位阶段曾往输入法私有目录写 `vivoime_probe.txt` 打点，`0.1.8` 已删除该逻辑并清理设备文件；后续调试只走 logcat。`bj.n` 里 `getIndex()` 9 确实是 `TEXT_EDITOR`，但运行时列表的第三个槽位实际是 `LIVE_TEXT`（文本扫描），说明保存的 `type` 与运行时枚举存在漂移；最终以 hook 打点打印的枚举名为准。

### 10.4 实现与验证

- 模块包名 `local.codex.vivoime`，沿用 `vivoportal` 的 `build.sh` + `assets/xposed_init` 老式入口，作用域只勾 `com.vivo.ai.ime.nex`。
- 两个 hook 都只在主进程装（`:vivoasr`/`:webView`/`:crash` 不需要），全部 `try/catch` 失败放行；`ah.b.f` 在主线程解析布局，只做字段替换，不分配对象。
- 版本守卫按 `3.9.5.2611202`：类/方法缺失就整体跳过，不猜着 hook。
- 真机验收：① 英文 26 键长按 M 出 `/`，键面小字也是 `/`，中文 26 键 M 仍是 `/`、email 布局不受影响；② 顶部栏能放第 6 个工具，6 个时图标不重叠，设置页"定制工具栏"槽位同步变多。

## 11. 复现

```sh
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
adb -s 10AG632QLS002CF shell pm path com.vivo.ai.ime.nex
adb -s 10AG632QLS002CF pull /data/app/~~.../base.apk cache/bases/jovi-ime-3.9.5.2611202/base.apk
sha256sum cache/bases/jovi-ime-3.9.5.2611202/base.apk      # 应为 35a91b28…
unzip -l cache/bases/jovi-ime-3.9.5.2611202/base.apk | grep -E '\.dex$'
jadx -r -j 8 -d cache/bases/jovi-ime-3.9.5.2611202/jadx cache/bases/jovi-ime-3.9.5.2611202/base.apk
```

APK、dex、so 与 JADX 输出全部留在 `cache/`，不入主仓。版本升级后旧的 `cache/bases/jovi-ime-<版本>/` 保留作对照。

## 12. 未决问题

- 顶部快捷栏目标值：已按 6 个实现（宽度安全），7 个需真机看重叠。
- `0.1.0` 装机后进程未换，`0.1.1` 已重新安装并对精确 PID 发送 SIGTERM，新进程 28483 已带 `zygisk_vector` 映射；三个 hook 的实际效果待真机确认。
- 宿主若升级到新版本，`ah.b.f` / `bj.a.q` / `bj.a.v` / `bj.f.p` 需要重新核对签名。
