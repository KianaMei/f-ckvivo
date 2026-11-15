# OriginTweaks 复盘：哪些是弯路，哪些是证据

日期：2026-11-16  
对象：`originos/OriginTweaks`（工作副本，当前放在只读参考目录下）  
配套：`root/VECTOR-KSU.md`（Vector / KernelSU / 临时 root 的加载规则）

> 本文只做复盘。`originos/` 保持只读；工作副本已复制到
> `modules/origin-tweaks/`（0.1.6，修正了打包注释、manifest/版本文档），
> 后续修改只在工作副本里做。

---

## 0. 一句话结论

**这次最大的弯路不是某个 hook 写错，而是顺序错了：先写了 7 个功能、4 个
进程的 hook，之后才去读 Vector 的模块加载器。** 结果把一个「模块有没有被
注入」的问题，误诊成「APK 里两个入口文件冲突」，又用 `staticScope` 去补，
文档还停在 classic API 93。真正该先做的三件事是：

1. 读 `FileSystem.loadModule()` / `ModuleDetection.inspect()`，确认模块会不会被加载；
2. 读 `DragDropPlus` 等已跑通的参考模块，复用它们的锚点；
3. 先用一个 10 行的 Probe 确认 `onModuleLoaded` / `onPackageLoaded` 出现，
   再写业务 hook。

---

## 1. 时间线（dist 里的物证）

| 时间 | 版本 | `assets/xposed_init` | `module.prop` | 说明 |
|---|---|---|---|---|
| 15:40 | 0.1.0 | 有（`HookEntry`） | `minApi=102 targetApi=102 staticScope=false` | dex 里同时有 classic `HookEntry` 和 modern `LibXposedEntry` |
| 15:49 | 0.1.1 | 有 | 同上 | 未解决 |
| 16:01 | 0.1.2 | **删掉** | `staticScope=false` | 误诊为「两个入口 → NONE」 |
| 16:06 | 0.1.3 | 无 | **`staticScope=true`** | 列表收窄，但仍不会自动勾选 |
| 16:07 | 0.1.4 | 无 | `staticScope=true` | — |
| 16:17 | 0.1.5 | 无 | `staticScope=true` | `originos/OriginTweaks` 的最新原样副本 |
| 16:58 | 0.1.6 | 无 | `staticScope=true` | **工作副本** `modules/origin-tweaks`：修正注释/manifest/版本文档 + 加载日志，业务 hook 未改 |
| 16:19 | — | — | — | `_tmp_vector/vdb2.dump`：模块 `enabled=1`，4 条 scope 行 |
| 16:26 | — | — | — | `_tmp_vector/vdb3.dump`：同上 |

`vdb` 里已经能看到：

```sql
INSERT INTO modules VALUES(327,'dev.ganvivo.origintweaks', '.../base.apk',1,0);
INSERT INTO scope VALUES(327,'com.vivo.globaldragdrop',0);
INSERT INTO scope VALUES(327,'com.bbk.launcher2',0);
INSERT INTO scope VALUES(327,'com.vivo.recents',0);
INSERT INTO scope VALUES(327,'system',0);
```

也就是说：**数据库层面模块是启用且已勾选 scope 的**。后面如果仍没有日志，
问题就不在「装没装/勾没勾」，而在「进程有没有被注入、hook 有没有挂上」。

---

## 2. 已证实 / 未证实

### 已证实

- 0.1.0 / 0.1.1 的 APK 同时含 `assets/xposed_init` 与
  `META-INF/xposed/java_init.list`；0.1.2 起删除前者。
- 0.1.0 的 dex 同时含 classic `HookEntry`（`IXposedHookLoadPackage`）与
  modern `LibXposedEntry`（`XposedModule`）。
- `module.prop` 从 0.1.0 起就是 `targetApiVersion=102`；0.1.3 起
  `staticScope=true`。
- 设备 Vector DB 里模块 `enabled=1`，4 条 scope 行齐全。
- `vb.p.h(pkg, activity)` 在 4.1.3.0 的 `yb.f` 里确实是拖放白名单闸门；
  `bb.a.a` 在同一处只做 null 判断。
- `ge.a.b`、`he.n.q`、`ne.f.a` 的签名与 `ShareHooks` 的假设一致。
- `RecentsPreferences.getLayoutMode()`、`FoldedStateMonitor.isFoldableOpenState()`、
  `VivoTaskMenuView.showAtomicTask()` 的调用链与注释一致。

### 未证实（必须真机日志）

- 模块是否真的被 daemon 加载（`VectorModuleManager` 日志）；
- `onPackageLoaded` / `onSystemServerStarting` 是否真的进了目标进程；
- 各 hook 是否挂上、命中；
- WMShell 的 `mRootTaskController` 在外屏如何排版；
- 外屏 4 分屏的实际几何；
- 系统 chooser 在拖拽浮层场景能否后台启动。

**AUDIT.md 自己写了「静态审计，没有安装到手机」**，所以它不能证明
「不会崩」，也不能证明「功能生效」。

---

## 3. 九条弯路

### 3.1 把「两个入口」误诊成 NONE（最贵的一条）

`originos/OriginTweaks/build.sh:72-74` 写着：

> Modern libxposed modules must NOT ship the legacy `assets/xposed_init`
> marker: Vector 2.2's module.prop parser classifies a package carrying both
> entries as NONE and silently drops it from the module map.

源码不是这样。`root/.upstream/Vector-88f8e1fa/daemon/.../FileSystem.kt:272-295`
的优先级是：

```kotlin
val strategy = when {
  targetApi >= 101 -> "MODERN"          // 先看 targetApi
  hasLegacyFile -> "LEGACY"             // 再看 assets/xposed_init
  targetApi == 100 -> "UNSUPPORTED"
  else -> "NONE"
}
```

`targetApiVersion=102` 时，**两个入口同时存在也走 MODERN**，读的是
`META-INF/xposed/java_init.list`；`assets/xposed_init` 根本不会被读。
`ModuleDetection.inspect()` 也是先看 `java_init.list`，有就按 modern 返回。

所以 0.1.2 删 `assets/xposed_init` 不是修复，只是把无用文件清掉。
真正要查的是：`java_init.list` 里的类在不在 dex、模块有没有 enabled、
目标有没有在 scope 里、目标进程有没有重启。

### 3.2 把 `staticScope` 当成「自动生效」开关

`staticScope` 的语义是**限定哪些 App 可以被列出来**，不是自动勾选：

> `staticScope` means the module fixes which apps may be listed, not which of
> them are hooked. `packages` is the outer limit and the user cannot widen it,
> but which of those packages are in the scope remains theirs to choose.

`root/.upstream/Vector-88f8e1fa/manager/.../ModuleDetection.kt:242-255`

所以 0.1.3 把 `staticScope=false` 改成 `true`，只是让 scope 页面从「几百个
App」变成「4 个目标」，用户仍然要手动打勾。`vdb2/vdb3` 里 4 条 scope 行
说明后来确实勾了；但这不是「改一个字段就生效」的证据。

补充：`staticScope=true` 且 `scope.list` 为空时，Vector 会**忽略**
staticScope（当成打包错误），不会把模块锁死。

### 3.3 classic / modern 混装，自己给自己制造歧义

0.1.0 的 dex 里同时有：

- `HookEntry implements IXposedHookLoadPackage`（classic API 93）
- `LibXposedEntry extends XposedModule`（libxposed API 102）

因为 `targetApiVersion=102`，Vector 只走 MODERN，`HookEntry` 是死代码。
`Env.java` / `Hooks.java` 的注释也写了：**target API 101+ 的模块会被
Vector 屏蔽 classic `de.robv.android.xposed` 表面**。这不是「classic 不能用」，
而是「你声明了 102，就不能再指望 classic 入口」。

正确做法是二选一：

| 选择 | 需要的文件 | manifest |
|---|---|---|
| modern（推荐） | `META-INF/xposed/module.prop` + `META-INF/xposed/java_init.list`，入口继承 `XposedModule` | `xposedminversion` 可写 102，也可不写；不要放 `assets/xposed_init` |
| classic | `assets/xposed_init`，入口实现 `IXposedHookLoadPackage` | **必须**写 `xposedminversion`（manager 靠它识别 legacy） |

`modules/fq-fold-pad` 就是纯 classic 且能工作的例子；`DragDropPlus` 是纯
modern 且能工作的例子。混装只会让后来的人分不清哪条路径在跑。

### 3.4 重新发明轮子，而不是先读参考模块

`DragDropPlus` 的 `WhiteListHooker.kt:11-35` 记录了他们自己 1.0.0/1.0.1 的
弯路：

- 先 hook `WhiteListResultBean.getResult()`，日志显示挂上但一次都没命中；
- 重新静态分析后发现它只在中转岛链路，真正的拖放起始闸门是
  `za.o.g(pkg, activity)`，整个 dex 只有 `cb.h` 一个调用点。

我们这边 4.1.3.0 的闸门是 `vb.p.h`（不是 `za.o.g`，版本不同），这一点
`DragHooks` 是对的；但 `bb.a.a` 的「栈里出现 `yb.f` 才返回 Boolean.TRUE」
是一个为了绕开 null 检查的补丁，不是从参考模块学来的稳定做法。
如果先读 `DragDropPlus`，至少会先问：「真正的 call site 是哪个？
这个 bean 有没有别的消费者？」而不是先写 4 个拖放 hook。

### 3.5 工作副本放进了只读参考目录

`originos/` 下除 `OriginTweaks` 外都有 `.git`，是上游克隆；`OriginTweaks`
没有 `.git`，是我们的。把 `build/`、`dist/`、`.signing/` 混在参考目录里，
既容易误改上游，也让「哪些是原版、哪些是我们改的」变得不清楚。

建议：`originos/` 只读；工作副本放 `modules/origin-tweaks/`。

### 3.6 文档、版本、manifest 三处不一致

| 位置 | 写的 | 实际 |
|---|---|---|
| `README.md:1` | 0.1.0 | dist 最新 0.1.5 |
| `README.md:3` | classic Xposed API 93 | 代码是 libxposed API 102 |
| `README.md:66` | APK 含 `assets/xposed_init` | 0.1.2 起已删除 |
| `AUDIT.md:1-4` | 0.1.0 / SHA `05028d…` | 0.1.5 / SHA `95b652…` |
| `AUDIT.md:184-192` | `HookEntry`、`xposedminversion=93` | `LibXposedEntry`、`module.prop targetApi=102` |
| `AndroidManifest.xml:23-24` | `xposedminversion=93` | modern 模块实际按 `module.prop` 加载 |
| `MainActivity.java:15` | “OriginTweaks 0.1.0” | 0.1.5 |
| `build.sh:25-26` | 默认 `VERSION_NAME=0.1.2` | 0.1.5 |

`xposedminversion=93` 本身不会让 modern 模块加载失败（manager/daemon 都以
`java_init.list` 为准），但它会误导下一个维护者，也会让「到底走哪条路」
永远说不清。

### 3.7 用静态审计代替运行时验证

`AUDIT.md` 开头就写「没有把 APK 安装到手机，也没有在真机上触发 hook」。
后面却给出了「只装 APK 不会自己崩溃」「hook 安装失败不会拖垮目标进程」
这类结论。这些结论在代码层面大体成立，但不能回答：

- 模块有没有被 daemon 加载？
- 目标进程有没有收到模块？
- hook 有没有挂上、命中？
- `system_server` 的 hook 会不会让系统服务崩溃？

正确的最小验证链是：

```
VectorModuleManager: Loading module / Loaded module successfully
  → 目标进程 logcat: libxposed module loaded / onPackageLoaded
    → 具体 hook 日志（OriginTweaks）
      → 功能实际生效
```

缺任何一层，都不能说「模块生效」。

### 3.8 高风险功能默认全开

`system` scope 里的 `MultiTaskHooks` 直接改
`VivoMultiTaskManagerService.isSupportVivoMultiTask()`；`SplitHooks` 改
launcher 的槽位；`RecentsHooks` 改布局模式。AUDIT 自己也承认 WMShell 的
控制器不在已拉取的 APK 里，外屏排版只能真机验证。这些功能**没有用户开关**，
一旦某一项让 system_server 异常，用户只能停整个 `system` scope。

至少应该：

- 把 `system_server` 相关 hook 做成远程偏好开关，默认关；
- 先只开 `com.vivo.globaldragdrop`，验证拖放/分享/玻璃；
- 再开 recents/launcher，最后才开 system；
- 每步保留 logcat 和 scope 配置。

### 3.9 栈帧判断又贵又脆

`fromYbF()`、`fromSplitWindow()`、`fromSettingsLoader()`、
`fromAtomicTaskMenu()` 都是每次调用遍历 `Thread.getStackTrace()`：

- 性能：拖放/布局/设置列表都是高频路径；
- 正确性：ART 内联、R8 改名、不同调用链都可能让帧消失；
- 可读性：后来的人不知道「为什么只对这个调用方生效」。

更稳的模式是：

- 在真正的 caller 方法上挂一个 ThreadLocal 作用域（enter/exit），
  被调方法只读 ThreadLocal；
- 或者直接 hook call site 所在的方法，避免在共享方法里做栈判断。

`RecentsHooks` 已经用了 `ATOMIC_TASK_SCOPE` 的 ThreadLocal 模式，这是对的；
`DragHooks` / `SplitHooks` / `SettingsListHooks` 还停留在栈帧判断。

---

## 4. 修正后的模块契约

### 4.1 打包（modern，推荐）

```
AndroidManifest.xml
classes.dex
META-INF/xposed/module.prop
META-INF/xposed/java_init.list
META-INF/xposed/scope.list
```

`module.prop`：

```properties
minApiVersion=102
targetApiVersion=102
staticScope=true
```

`java_init.list`：

```
dev.ganvivo.origintweaks.LibXposedEntry
```

`scope.list`：

```
system
com.vivo.globaldragdrop
com.bbk.launcher2
com.vivo.recents
```

注意：

- **不要**再放 `assets/xposed_init`（不是因为它会导致 NONE，而是因为
  modern 根本不会读它，留着只会让文档自相矛盾）；
- `xposedminversion` 可以写 102 或删掉，但不要写 93；
- `versionCode` / `versionName` 以 `build.sh` 的 `--version-code` /
  `--version-name` 为准，manifest 和 `MainActivity` 不要各写一套。

### 4.2 安装后必须确认

1. Vector 模块列表里能看到 `OriginTweaks`，且开关为开；
2. scope 页面 4 个目标全部打勾（`staticScope` 只收窄列表，不自动勾）；
3. 软重启（或至少 force-stop 目标 App 后重开）；
4. `logcat -s VectorModuleManager` 出现 `Loaded module dev.ganvivo.origintweaks successfully`；
5. 目标进程 `logcat -s OriginTweaks` 出现 `libxposed module loaded` /
   `onPackageLoaded pkg=...`；
6. 再验证具体功能日志。

### 4.3 测试顺序

| 顺序 | scope | 验证 |
|---|---|---|
| 1 | `com.vivo.globaldragdrop` | 拖放放行、系统分享、玻璃 |
| 2 | `com.vivo.globaldragdrop` | 设置页全部 App |
| 3 | `com.vivo.recents` / `com.bbk.launcher2` | 内屏堆叠、任务菜单、外屏手势 |
| 4 | `system` | 原子工作台（默认关，单独开） |

每步都记录：模块版本、scope、目标 App 版本、logcat 关键行、实际现象。
没有这些，不要写「已适配」。

---

## 5. 建议的下一步（未执行）

> 2026-11-16：第 1 步已做——`modules/origin-tweaks/` 是 0.1.6 工作副本，
> `originos/OriginTweaks` 原样保留。以下 2–8 仍未执行。

1. 把工作副本从 `originos/OriginTweaks` 复制到 `modules/origin-tweaks/`，
   `originos/` 保持只读；
2. 修正 `build.sh:72-74` 的错误注释；
3. 统一版本：`README` / `AUDIT` / `MainActivity` / `AndroidManifest` /
   `build.sh` 全部指向 0.1.5（或下一个版本号）；
4. `AUDIT.md` 增加「2026-11-16 修正」一节：把「both entries → NONE」
   改成源码实际的优先级规则，并标注哪些结论是静态推断；
5. 增加一个最小 Probe 构建（或复用 `_tmp_probe`），先验证模块加载；
6. 把 `system` 相关 hook 加远程开关，默认关；
7. 逐步把栈帧判断换成 ThreadLocal / call-site hook；
8. 真机按 §4.3 的顺序逐项验证，保留日志。

---

## 6. 证据索引

| 结论 | 证据 |
|---|---|
| 两个入口不会 NONE | `root/.upstream/Vector-88f8e1fa/daemon/.../FileSystem.kt:272-295` |
| manager 先看 java_init.list | `root/.upstream/Vector-88f8e1fa/manager/.../ModuleDetection.kt:80-140` |
| staticScope 只收窄列表 | `ModuleDetection.kt:242-255`；`FileSystem.readStaticScope()` |
| 0.1.0/0.1.1 有两个入口 | `unzip -l originos/OriginTweaks/dist/OriginTweaks-0.1.0.apk` |
| 0.1.2 删除 legacy 入口 | 同上，0.1.2 |
| 0.1.3 staticScope=true | 0.1.3 的 `META-INF/xposed/module.prop` |
| DB 已启用且已勾 scope | `_tmp_vector/vdb2.dump:44,74-77`；`vdb3.dump` 同 |
| 拖放闸门 `vb.p.h` | `originos/device-apks/decompiled/com.vivo.globaldragdrop/sources/yb/f.java:239-275` |
| `bb.a.a` 只做 null 判断 | 同上 `:239` |
| DragDropPlus 的旧弯路 | `originos/DragDropPlus/.../WhiteListHooker.kt:11-35` |
| Vector 3080 崩溃 | `root/vector-softreboot/FINDINGS.md` |
| Vector 3118 修复 | `root/.upstream/vector` git log（`5690481`、`eaffc81`） |
