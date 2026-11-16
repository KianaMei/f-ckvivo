# Vector 2.2 / API 102：一手源码技术附录

研究日期：2026-11-16。范围：现存仓库资料，以及 Vector / libxposed 官方固定提交的一手源码。本文是静态研究；未连接设备、未安装、未触发广播、未改变任何进程或框架状态。设备操作边界以 [根 AGENTS.md §1](../../AGENTS.md#1-设备红线最高优先级覆盖一切默认行为) 为准。

面向本机的功能选择、当前代码缺口与开发顺序见 [OriginOS hook 方案主报告](ORIGINOS-HOOK-STRATEGY.md)。本文核对技术机制，不改变仓库既有的设备操作约定。

## 1. 版本基线与证据等级

| 对象 | 本次核实的基线 | 证据与边界 |
|---|---|---|
| Vector v2.2 | `88f8e1faa8b4e7ce20aefabe9c295cd746ea038e` | 官方 annotated tag `v2.2` 指向该提交；[提交](https://github.com/JingMatrix/Vector/commit/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e)、[发行说明](https://github.com/JingMatrix/Vector/releases/tag/v2.2)。 |
| Vector 内置 libxposed API | `39cac0845771547c9c67a3e3ce255af110a54a0e` | [固定子模块指针](https://github.com/JingMatrix/Vector/tree/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/libxposed)；本文 API 语义以此为准。 |
| Vector 内置 libxposed service | `3318940876192e29cf6ab07637e899e22a87ebf0` | [固定子模块指针](https://github.com/JingMatrix/Vector/tree/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/services/libxposed)。模块 App 的管理/配置通信接口与被注入进程的 hook API 是两层。 |
| 本机曾运行的框架 | Vector `2.2 (3080) 88f8e1fa`、API 102、Android 17/API 37 | 现存 [XHook 23:12 日志](../../xhook/notes/logcat-0.3.1-verified.txt#L1)：L1 服务连接，L4 目标进程模块加载，L6 系统版本，L29/L32/L44 实际广告 hook 命中。它晚于 11-15 傍晚事故，证明当晚曾恢复运行；不能证明研究时设备仍处于同一状态，也不能证明 OriginTweaks 生效。 |

本 checkout 中 `originos/`、`root/.upstream/`、`_tmp_vector/` 不存在。旧 [VECTOR-KSU.md](../../root/VECTOR-KSU.md#L238) 与 [复盘](../origin-tweaks-REVIEW.md#L343) 中的这些路径是历史指针，不能写成这次已查看的源码或物证。下文“源码确认”指固定提交的控制流；“静态推断”指其与本地代码组合后的结果；“未确认”指还缺目标版本/运行日志。

## 2. 打包、分类与 scope

| 问题 | Vector 3080 / API 102 的实际契约 | 一手出处 |
|---|---|---|
| daemon 怎样选模块类型 | `targetApiVersion >= 101` → MODERN；否则有 `assets/xposed_init` → LEGACY；否则 `targetApiVersion == 100` → UNSUPPORTED；其余不可用。先 modern、再 legacy，所以双入口不会使 target 102 变成 NONE。modern 读取 `META-INF/xposed/java_init.list` / `native_init.list`；Java 入口为空或没有 dex 仍不可用。 | [FileSystem L246–330][v-filesystem] |
| manager 怎样认出模块 | 优先查 APK 的 `META-INF/xposed/java_init.list`；不存在才按 manifest 是否含 `xposedminversion` 认 legacy。列表里能看见模块，只能证明 manager 识别，不能证明 daemon 已成功解析或目标进程已加载。 | [ModuleDetection L66–162][v-detection] |
| 入口与依赖 | 入口继承 `XposedModule`，框架反射无参构造器后注入 framework；API 应 `compileOnly`，不能把 API 类装入自己的 dex。Vector 会验证 `XposedModule` 是否由框架 loader 提供。`minApiVersion` / `targetApiVersion` 按 Java Properties 声明。 | [API package-info L10–44][a-package]；[API README L14–30][a-readme]；[VectorModuleManager L113–175][v-modules] |
| classic 是否还能用 | Vector 仍保留独立 legacy 路径。**屏蔽 modern 模块访问 classic API 的阈值是 target 102，不是 101。** `>=101` 只是模块分类阈值；`blockLegacyApi = targetApiVersion >= 102` 才控制 `de.robv.android.xposed` 等名称的拒绝。 | [VectorModuleManager L121][v-modules]；[VectorModuleClassLoader L64–94、L150–168][v-loader]；[API XposedInterface L25–52][a-interface] |
| `staticScope=true` | 规定可选集合的上限，用户仍选择其中的子集。daemon 也拒绝越界 scope，并在缓存重建时裁去越界旧行；不是纯 UI 过滤。空 `scope.list` 时忽略 staticScope。 | [ModuleDetection L112–128、L239–246][v-detection]；[FileSystem L198–229][v-filesystem]；[ModuleDatabase L253–303][v-database]；[ConfigCache L318–325][v-cache] |
| `system` 与 `android` | modern 的 `system` 是 `system_server` 虚拟 scope，`android` 仍可代表真实 `android:ui` 等进程。不能用 `android` 替代 system_server。legacy 推荐 scope 在 manager 中交换 `android` / `system`，不能把旧模块的写法原样搬到 modern。 | [API package-info L47–68][a-package]；[ModuleDetection L222–232][v-detection]；[ConfigCache L346–363][v-cache] |
| 包与进程边界 | scope 展开到所选包声明的常规进程；被注入后，同进程后续加载的其他包也会产生回调。因此模块仍需过滤 process/package。模块只进入安装了该模块的用户的目标进程；system_server 是全设备例外。 | [API package-info L49–68][a-package]；[ConfigCache L156–220、L338–387][v-cache] |

本地 [module.prop](module-meta/module.prop#L1) 的 `minApiVersion=102 / targetApiVersion=102 / staticScope=true`、[java_init.list](module-meta/java_init.list#L1)、[scope.list](module-meta/scope.list#L1) 与 modern 方向一致。旧 [Hooks.java L17–19](src/dev/ganvivo/origintweaks/Hooks.java#L17) 的“101+ 屏蔽 classic”注释应按上述阈值理解；本研究没有修改生产代码。

## 3. 生命周期与正确的类加载器

| 回调 | API 102 语义 | Vector 3080 实现 |
|---|---|---|
| `onModuleLoaded` | 模块 generation 已建立；参数是进程名、是否 system_server。这里还没有某个目标包的最终 loader。 | [VectorModuleManager L57–81][v-modules]：实例加入 lifecycle 集合后调用。回调抛错会被记录，后面仍可能打印 `Loaded module ... successfully`，所以此行不是业务初始化成功证明。 |
| `onPackageLoaded` | 默认 loader 已就绪，`AppComponentFactory` 尚未实例化；用 `getDefaultClassLoader()` 可检查 factory/默认 APK 层。 | [LoadedApkHookers L118–146][v-apk]：在 factory 原调用前 dispatch。 |
| `onPackageReady` | factory 已创建最终应用 loader，可以准备 `Application`。**`getClassLoader()` 可能不同于 `getDefaultClassLoader()`。** | [LoadedApkHookers L150–181][v-apk]：`createOrUpdateClassLoaderLocked` 返回后分别取 `mClassLoader` 与 `mDefaultClassLoader`；[VectorLifecycleManager L138–156][v-lifecycle] 原样保留两者。 |
| `onSystemServerStarting` | 使用 `param.getClassLoader()`；替代 system_server 首个包的 loaded/ready 阶段。 | [SystemServerHookers L35–75][v-system]。**late injection 例外**：[VectorStartup L80–109][v-startup] 明确补发此回调时服务已运行，不能再假定处于启动临界服务之前。 |

API 原始契约见 [XposedModuleInterface L71–110、L173–234][a-lifecycle]。它约定每包一次；Vector 3080 的 ready hook 还明确覆盖 split APK 路径，lifecycle dispatcher 本身未按包去重。因此业务安装应幂等，但幂等状态需要表达“哪一个功能已成功安装”。[LoadedApkHookers L156–181][v-apk]、[VectorLifecycleManager L63–115][v-lifecycle]。

**对本地的静态推断：**[LibXposedEntry L39–84](src/dev/ganvivo/origintweaks/LibXposedEntry.java#L39) 在 loaded 与 ready 两处都传 `getDefaultClassLoader()`，且在具体 hook 安装之前 `INSTALLED.add(pkg)`。若早期 loader 看不到目标类，或某项安装失败，ready 阶段仍会被整包标记拦住；同包的晚到插件 loader 也无法借 ready 获得补装。正确方向是区分早期探测与最终 loader 安装，并按实际安装成功的功能记录状态。**尚未证明本机这几个 vivo 包一定定制 factory/loader，也未证明这一点就是现有失效的唯一原因。**

## 4. 首次打开、stopped 与“模块已加载”需要分开

1. **模块 App 的服务连接：**`ModuleAppService.uidStarts(uid)` 通过模块 appId 找配置，再把 `IXposedService` 交给模块 App 的 provider。它服务于状态显示、scope 请求、远程配置及热重载控制。[ModuleAppService L68–75、L95–141][v-appservice]。首次打开模块 App 可以让这条链出现，但它与 hook 入口的回调不是同一个事件。
2. **目标进程中的模块加载：**`ActivityThread.attach` 后走 `AppAttachHooker → XposedInit.loadModules → VectorModuleManager.loadModule`，读取目标进程 scope 所对应的模块；`XposedInit` 的原子标记只允许正常初始装载一次。[AppAttachHooker L10–19][v-attach]、[XposedInit L219–235][v-init]。因此旧 [VECTOR-KSU.md L392–394](../../root/VECTOR-KSU.md#L392) 的“模块 App 拿不到 IXposedService，所以不会有 libxposed module loaded”缺少因果依据。
3. **缓存是另一件事：**同 APK 路径的 `updateModuleApkPath` 可以不触发重建；`ConfigCache` 的同路径快路复用已解析模块，只在 appId 为 `-1` 时补身份。[ModuleDatabase L354–378][v-database]、[ConfigCache L237–257][v-cache]。这是源码可证实的条件，不能泛化成每次覆盖更新都会改变 UID，或每次首次打开一定修复缓存。
4. **stopped 尚不能作为本版本的统一加载规则：**本次检索固定提交 daemon/xposed/zygisk/native 的 87 个 Kotlin/C++ 源文件中的 `FLAG_STOPPED|isStopped|ACTION_PACKAGE_FIRST_LAUNCH|notLaunched|stopped`，仅命中两个服务停止日志；另核对了 legacy 的实际 `XposedInit.loadModules` / `LegacyDelegateImpl`。上述装载、scope、缓存路径未找到“模块/目标带 FLAG_STOPPED 就跳过”的明确条件。因此 [根 AGENTS 的首次打开经验](../../AGENTS.md#L170) 应保留为本机场景经验，不能升格为已由 3080 源码证明的普遍前提。Android/vivo 在安装与启动时怎样改变 stopped 状态、本次历史故障是否由此造成，**未确认**。

历史“先打开后恢复”若要归因，还需同一轮的包状态、APK 路径/版本、缓存变化、目标 PID 与 framework 加载日志；本次没有为追补这些信息操作设备。

## 5. Hook API、异常保护与 deopt

- **源码确认：**modern 使用 `hook(Executable).setExceptionMode(...).intercept(Hooker)`，`chain.proceed()` 调后续拦截器/原方法；参数列表不可原地改写，应将新参数交给 `proceed(args)`。`Chain` 不可跨线程或在 intercept 返回后复用。[API XposedInterface L187–282、L362–408][a-interface]。
- **PROTECTIVE 的精确边界：**hooker 在 proceed 前抛错，跳过该 hook 并继续；proceed 后 hooker 自身抛错，恢复下游结果/异常；下游原方法的异常仍传播。Vector 的 [VectorChain L111–140][v-chain] 实现这一点。它不回滚 hook 已经改过的对象，也不保证避免死锁、错误业务状态、native 崩溃；例如 primitive 返回 null 的检查在 chain 外仍抛 NPE：[VectorNativeHooker L189–199][v-nativehook]。不能把本地 [Hooks.java L26–30](src/dev/ganvivo/origintweaks/Hooks.java#L26) 的保护模式写成“系统服务不会崩”。
- **安装阶段限制：**Vector 3080 拒绝 abstract 方法、框架自身方法、`Method.invoke`、`Constructor.newInstance`、`Object.getClass` 等递归敏感点；注册失败与运行时 hooker 保护是两层。[VectorNativeHooker L41–79][v-nativehook]。
- **deopt 的对象是已内联调用者：**若短方法 B 被 A 内联，仅 hook B 可能没有回调；`deoptimize(A)` 让 A 恢复实际调用。不能据“挂钩成功但零命中”直接认定锚点错误，也不能据 deopt API 存在认定所有调用者都已覆盖。API 明确说明要识别相关 callers；Vector 直接转到 `HookBridge.deoptimizeMethod`。[API XposedInterface L471–487][a-interface]、[VectorContext L151–153][v-context]。

## 6. 远程配置与无需结束进程的代码更新

**已存在的配置通路比热重载更适合功能开关。**模块 App 使用 libxposed **service** 的可写远程 preferences；被注入端通过 hook API 的 `getRemotePreferences(group)` 只读，并可注册变化 listener。Vector 已实现 Binder diff 推送与本地缓存更新；被注入端 `edit()` 直接拒绝。对应 [XposedInterface L535–543][a-interface]、[VectorRemotePreferences L24–85、L103–120][v-prefs]、[ModuleAppService L386–427][v-appservice]。远程 preferences 实际按模块包、Android user、group 保存，不应把“模块二进制/启用配置全设备一份”误读为所有用户共享功能设置：[PreferenceStore L11–35][v-prefstore]、[InjectedModuleService L43–58、L85–91][v-injectedservice]。

静态设计建议：高风险功能默认关闭，在 hook 的决策点读取稳定配置快照；关闭时尽量回到 `chain.proceed()` 的原有路径。若开关需要安装/撤销 hook，应持有明确的 HookHandle 并处理并发。此建议尚未实装，也不是批准设备端试验。

**Vector 2.2 确实实现 API 102 热重载**，不是只有接口名称：daemon 为 `getModules()` 返回的模块登记目标，模块 App 的 service 提交请求，经目标进程 `VectorProcessChannel` 到 `VectorModuleManager.hotReload`。[FrameworkService L85–102、L279–280][v-framework]、[ModuleAppService L232–265][v-appservice]、[VectorProcessChannel L14–49][v-channel]。实际边界如下：

| 条件/限制 | 固定版本证据与对本机的意义 |
|---|---|
| 必须已有该模块的 generation | `generations[packageName]` 不存在直接 UNSUPPORTED；只有一条 Java 入口才可重载。不是向任意存活 App 首次注入模块，也不是建立临时 root/恢复整个框架的方法。[VectorModuleManager L224–251][v-modules]。daemon 有 target 记录本身仍不足以证明实例建立成功。 |
| 旧代码必须同意 | 旧实例 `onHotReloading()` 必须返回 true；缺省返回 false。`autoHotReload=true` 仅使更新触发请求，不能绕过旧实例拒绝。[API lifecycle L236–267][a-lifecycle]、[ModuleAppService L82–91][v-appservice]、[VectorModuleManager L272–285][v-modules]。 |
| system_server 可作为既有目标 | 目标 channel 的实现明确覆盖 system_server；普通 App 的跨用户限制有 system UID 例外。源码没有排除 system_server，但不等于本机已验证系统 hook 的热更新安全性。[VectorProcessChannel L14–19][v-channel]、[FrameworkService L105–123][v-framework]。 |
| 不自动重放 loaded/ready/system 回调 | 新代码必须在 `onHotReloaded` 显式迁移/重装。缺省 `onHotReloaded` 会 unhook 全部旧 handle；覆盖它后需自行正确处理旧 hook，或使用 `replaceHook` / 相同 hook id 原子替换。[API lifecycle L173–178、L269–297][a-lifecycle]、[API XposedInterface L307–328、L395–408][a-interface]。 |
| 不是事务回滚 | 新 generation 在 `onHotReloaded` 前已提交；该回调失败可能留下部分迁移状态，框架不回滚。旧线程、外部 callback、JNI 引用和 native hook 的收尾由模块承担。[VectorModuleManager L287–327][v-modules]、[API lifecycle L248–252、L279–290][a-lifecycle]。 |
| capabilities 需分层核对 | API 的 `PROP_CAP_SYSTEM` / `PROP_CAP_REMOTE` 不是热重载许可位；service 方法另要求 service API 102、可达的既有 target 和异步结果。用于改配置时官方明确推荐 preferences，非 hot reload。[XposedService L39–52、L256–344][s-service]。 |

**当前 OriginTweaks 0.1.6 尚未采用此方案。**[LibXposedEntry](src/dev/ganvivo/origintweaks/LibXposedEntry.java#L20) 没有覆盖 `onHotReloading`，所以默认拒绝；[module.prop](module-meta/module.prop#L1) 未开启自动热重载。仅给下一个 APK 增加同意逻辑，不能让已加载的旧 0.1.6 实例同意。缺少 `onHotReloaded` override 本身不是“泄漏旧 hook”缺陷，因为 API 默认实现会清理；真正缺少的是完整的旧代同意、新代重新安装与失败管理设计。本文不提供安装、触发热重载或改变 system_server 的操作步骤。

## 7. 避免把最新文档套给 3080

- 本次网络读取时 Vector `master` 为 `6f6b976e3315c158d501f743c3c609e80cae7ec8`，比 v2.2 前进 39 个提交；manager 的 `ModuleDetection` 已移动目录，daemon lock/服务握手也有变化。[固定比较](https://github.com/JingMatrix/Vector/compare/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e...6f6b976e3315c158d501f743c3c609e80cae7ec8)。这用于解释源码路径和行为漂移，不是框架升级建议。
- libxposed `102.0.0` tag 为 `45e7c5cfe54725b6d828d8b7be65e22ce60c67e4`；它与 Vector pin 在此次核对的 `XposedInterface` / `XposedModuleInterface` / README 内容相同。读取时 API `master` 为 `79b75b49255a257d38f67bce2e928649dc2fc6c9`；[与 pin 的固定比较](https://github.com/libxposed/api/compare/39cac0845771547c9c67a3e3ce255af110a54a0e...79b75b49255a257d38f67bce2e928649dc2fc6c9) 包含 `proceedWith` 文档澄清，以及删除“至少一个 Java 入口”的描述。**3080 的 FileSystem 仍明确拒绝空 Java 入口**，不能据最新说明推导此版支持 native-only 模块。
- 当前设备 root/vectord 状态、OriginTweaks 实际注入/命中、vivo 最终 ClassLoader、系统 hook 的远程开关和热重载行为都没有本次运行验证。上述能力是实现可见性，不是现机验收结果。

## 来源固定方式

正文源码链接统一固定 Vector v2.2 提交，或其内置 API/service 提交，并标明对应行号，避免随着 master 移动失去上下文。

[v-filesystem]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/daemon/src/main/kotlin/org/matrix/vector/daemon/data/FileSystem.kt#L198-L330
[v-detection]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/manager/src/main/kotlin/org/matrix/vector/manager/data/model/ModuleDetection.kt#L66-L246
[v-database]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/daemon/src/main/kotlin/org/matrix/vector/daemon/data/ModuleDatabase.kt#L253-L378
[v-cache]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/daemon/src/main/kotlin/org/matrix/vector/daemon/data/ConfigCache.kt#L156-L387
[v-modules]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/core/VectorModuleManager.kt#L57-L331
[v-loader]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/utils/VectorModuleClassLoader.kt#L64-L168
[v-apk]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/hookers/LoadedApkHookers.kt#L118-L206
[v-lifecycle]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/VectorLifecycleManager.kt#L33-L158
[v-system]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/hookers/SystemServerHookers.kt#L35-L75
[v-startup]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/core/VectorStartup.kt#L80-L109
[v-appservice]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/daemon/src/main/kotlin/org/matrix/vector/daemon/ipc/ModuleAppService.kt#L68-L427
[v-attach]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/hookers/AppAttachHooker.kt#L10-L19
[v-init]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/legacy/src/main/java/de/robv/android/xposed/XposedInit.java#L219-L235
[v-chain]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/hooks/VectorChain.kt#L111-L140
[v-nativehook]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/hooks/VectorNativeHooker.kt#L41-L199
[v-context]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/VectorContext.kt#L151-L166
[v-prefs]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/VectorRemotePreferences.kt#L24-L120
[v-prefstore]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/daemon/src/main/kotlin/org/matrix/vector/daemon/data/PreferenceStore.kt#L11-L35
[v-injectedservice]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/daemon/src/main/kotlin/org/matrix/vector/daemon/ipc/InjectedModuleService.kt#L43-L91
[v-framework]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/daemon/src/main/kotlin/org/matrix/vector/daemon/ipc/FrameworkService.kt#L85-L280
[v-channel]: https://github.com/JingMatrix/Vector/blob/88f8e1faa8b4e7ce20aefabe9c295cd746ea038e/xposed/src/main/kotlin/org/matrix/vector/impl/core/VectorProcessChannel.kt#L14-L49
[a-readme]: https://github.com/libxposed/api/blob/39cac0845771547c9c67a3e3ce255af110a54a0e/README.md#L14-L30
[a-package]: https://github.com/libxposed/api/blob/39cac0845771547c9c67a3e3ce255af110a54a0e/api/src/main/java/io/github/libxposed/api/package-info.java#L10-L131
[a-interface]: https://github.com/libxposed/api/blob/39cac0845771547c9c67a3e3ce255af110a54a0e/api/src/main/java/io/github/libxposed/api/XposedInterface.java#L25-L543
[a-lifecycle]: https://github.com/libxposed/api/blob/39cac0845771547c9c67a3e3ce255af110a54a0e/api/src/main/java/io/github/libxposed/api/XposedModuleInterface.java#L71-L297
[s-service]: https://github.com/libxposed/service/blob/3318940876192e29cf6ab07637e899e22a87ebf0/service/src/main/java/io/github/libxposed/service/XposedService.java#L39-L387
