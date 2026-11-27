# HOOK-DEBUG-PLAYBOOK — libxposed / Vector 模块排障方法论

> 2026-11-21 从静盒×小红书 UI-07 冷启动刷新攻坚（0.9.13→0.9.27，11 个版本、4 次"假修复"）
> 提炼。适用于本仓库所有 libxposed API 102 模块（静盒、LiquidTab、drag-all、XHook、nuke…）。
> 各战线自己的细节留在各自 README；这里是**跨战线通用**的判据与纪律。

## 0. 一句话总则

**凡是"看起来生效了"的结论，都必须有一条不依赖 logcat 的落盘证据。**
本项目已经踩过至少四次"日志写成功、行为没生效"和"日志没出现、其实已生效"的坑。

## 1. 证据从哪里来（按可信度排序）

| 证据 | 可信度 | 说明 |
|---|---|---|
| 状态回传文件（如静盒 `shared_prefs/status_report.xml` 的 `meta.session`/`updated_at`） | ★★★★★ | 每进程会话一条，能和进程 `STIME` 对齐，证明"模块已注入且 hook 已装" |
| 模块自己写的落盘日志（宿主 files 目录 / `/data/local/tmp`） | ★★★★★ | 完全绕开 logd；`chatty` 吞不掉 |
| 行为判据（功能是否 hit、UI 是否变化） | ★★★★★ | 最终裁决，但需要用户操作 |
| `logcat` 按 tag 抓 | ★★☆☆☆ | **会被 logd chatty 静默丢弃**；只在进程不刷屏时可信 |
| `/proc/<pid>/maps` 找模块 APK / liblspd | ★☆☆☆☆ | 模块类多由内存/dex 装载，**看不到不等于没注入**；只配合作辅证 |
| "日志里没有 X" 推"X 没发生" | ☆☆☆☆☆ | 错误率最高的一条。零日志 ≠ 没加载/没调用 |
| Vector 自己的 `modules_*.log` | ★☆☆☆☆ | 2026-11-26 实测：本机这份 Vector（3080）的日志文件会停在 `----part 1 start----` 再不增长，而同秒模块的 `onPackageReady` 却在正常输出。**不能用它证明"没加载"**，只能当"加载过"的正向线索 |

**两条 logcat 使用铁律（2026-11-26 vivo-workbench 实测）**：

- **系统进程（launcher / systemui）的日志用普通 `adb logcat` 看不见**，必须 `adb exec-out su -c 'logcat -d -v time'`。实测同一时间段 `com.bbk.launcher2` 的 pid 在普通 logcat 里 0 命中、在 root logcat 里 2205 条。
- 想把"等用户复现"的日志留全，别用 `nohup ... &`（工具调用收尾会连带杀进程），用 `powershell Start-Process -RedirectStandardOutput`，或干脆用 `timeout` 在同一命令里守着。

**落盘日志的规范写法**（模板见 `xhs/NOTES.md`「探针模板」）：
- hook 安装时 Application 往往还没创建 → 拿不到 Context，**先写内存缓冲，15s/45s `postDelayed` 补写**；
- 首选 `context.getFilesDir()`，无 Context 时退回外置绝对路径
  `/storage/emulated/0/Android/data/<pkg>/files/`（属主 App 可写，root 可读）；
- 文件 200KB 截断重置；每行带 `SystemClock.elapsedRealtime()`。

## 2. 定位"真链路"：探针 + 栈

逆向静态分析（jadx/xref）能给出候选，但**调用是否发生只有运行时有发言权**。

1. **先列候选**：xref 找目标方法的调用方（`python -I <tools>/run_xref.py <apk> method "cls#name"`），
   把"入口类/执行器类/回调类"都列上。
2. **全挂只读探针**：按类名+方法名挂**所有重载**（注意 Kotlin 默认参数桥接会生成多个同名方法），
   记录参数前 3 个（`toString()` 截断 500 字符）+ 命中次数上限。
3. **关键节点附调用栈**：在"结果落地/请求组装"这类方法里追加栈（过滤框架帧），
   栈会直接告诉你真正的发起方——比猜一百遍强。
4. **一次冷启动出结果**：复现路径越短越好（冷启动 = 最稳定的一次性触发）。
5. **探针只存在于临时版本**：出正式版前整块删除，不随包发布。

**实锤案例**：UI-07 原锚点 `zid.m0#u0` 静态审计 72/72 命中，但运行时**整场冷启动零调用**——
真正发请求的是 `djd.f#b(djd.k)`。锚点命中只证明方法存在，不证明它被调用。

## 3. 钩子里的三个致命细节

### 3.1 反射必须用**宿主** classloader

```java
// install(module, cl) 时缓存：hostCl = cl;
Class<?> c = Class.forName("io.reactivex.Observable", false, hostCl);
```

模块自己的 classloader 解析不到宿主依赖（RxJava/OkHttp/Retrofit…）会抛 `ClassNotFoundException`。
**如果 catch 里 `return chain.proceed()`，就变成静默放行**——日志写着 SKIP、行为照旧，
排查成本极高（静盒 0.9.20–0.9.23 四轮全栽在这）。

### 3.2 所有 catch 分支**必须落盘**

```java
} catch (Throwable t) {
    diagFile("GATE FAILED " + t);          // ← 缺这一行就会重演上面的悲剧
    HookKit.error(module, ...);
    return chain.proceed();
}
```

### 3.3 返回值**形态**决定宿主状态机

替换 RxJava/协程链的返回值时，"逻辑上等价"不等于"状态机等价"：

| 返回 | 效果 |
|---|---|
| `Observable.empty()` | 只有 onComplete，**无 onNext** → 依赖 onNext 记录"已加载"的宿主会停在未加载态（静盒实测：下拉刷新永久禁用） |
| `Observable.just(emptyList())` | onNext + onComplete 都发生 → 状态机收尾正常，同时"没有新内容"可应用 ✅ |

**排除法**：如果跳过请求后出现"功能 A 好了但功能 B 坏了"，先怀疑状态机，而不是功能 B 另有 hook 点。

### 3.4 jadx 字段名 ≠ 运行时字段名

jadx 显示的混淆字段（`f188605g` 之类）运行时 `getDeclaredField` 可能取不到
（不同构建/内联/合成差异）。**参数判定优先解析 `obj.toString()`**（Kotlin data class 的
toString 包含全部字段名=值），比反射稳。字段名仅在实测可用时使用。

### 3.5 局部放开私有白名单：用 `invokeOriginalMethod` 保留宿主安全门禁

当宿主把「环境/布局安全门禁」（全屏抽取、悬浮键盘、多窗分屏、TalkBack、画中画）与「私有控件类型白名单」（如 `vivo-ime` 反射 `getServedViewType() == 1`）写在同一个布尔方法里时，**禁止粗暴 `param.setResult(true)` 跳过整段方法**：

1. 在 `afterHookedMethod` 中仅当原返回 `false` 且白名单布尔字段不全为 `true` 时介入；
2. 临时将白名单布尔字段置 `true` → 调 `XposedBridge.invokeOriginalMethod(original, self, param.args)` 让宿主自己的环境/多窗判定重跑一遍 → `finally` 立即还原原字段值；
3. 若两次调用仍为 `false`，说明是布局安全门禁拒绝，尊重宿主原结果。
（`shared/stubs/xposed-legacy` 的 `XposedBridge` 已补齐 `hookMethod` / `invokeOriginalMethod` 编译期桩。）

### 3.6 Vector 的 `onPackageReady` 可能给**空壳 ClassLoader**

插件化宿主（vivo launcher 的 `QuickstepPlugin`、`com.vivo.recents`）里，Vector 回调的 ClassLoader 可能是
`PathClassLoader[DexPathList[[], ...]]`——**一个 dex 都没有**，`Class.forName` 必然失败，于是 Hook 全部静默装不上，
表现与"模块没加载"一模一样（vivo-workbench 0.1.0～0.1.1 就栽在这里，卡了两天）。

判据与解法：

1. 安装前先 `findClassOrNull(目标类, cl)`，失败就把 `cl` 的 `toString()` 打进日志——出现 `DexPathList[[],` 就是这个坑；
2. 目标类若在插件 APK 里，真正的 ClassLoader 要靠 **AOSP 插件框架**（`com.android.systemui.shared.plugins.PluginActionManager` / `PluginInstance`）拿到：
   `getPlugin()` / `getPluginContext()` 取 ClassLoader，或挂 `onPluginConnected` / `loadPlugin`；
3. 再加一道**有限次后台重试**扫 `ActivityThread#mPackages` / `mResourcePackages` 里所有
   `LoadedApk#getClassLoader()`，谁能 `Class.forName` 到目标类就用谁（插件包是懒注册的，第一次调用时还不在表里）。
   重试要有上限并自行退出，避免常驻线程。

## 4. 判别字段与折叠屏异构布局契约

### 4.1 判别字段：先找"只有这条路径才有"的特征

同一功能的多条触发路径，参数常常高度相似。找判别字段而不是加大概率阈值：

- 静盒冷启动自动首屏 vs 手动下拉：两者都是 `INITIAL_REFRESH + noteIndex=0 + cursorScore=''`，
  **唯一区别是 `launchScenario`（冷启动=1，手动=null）**。少了它就把下拉刷新一起拦了（0.9.25 实测）。
- 找判别字段的方法：把两条路径的完整参数 toString 各抓一份，diff。

### 4.2 折叠屏展开（双栏 / Pad）不是“单栏拉宽”，而是独立布局树

在 vivo X Fold6 内屏（`2312×2504`）上，主流 App（小红书、B 站、抖音、Telegram/ExteraLess）普遍走独立的平板/分栏 XML 或 Compose 分支，常见 4 类断层：

1. **父子层级重组**：内容流与顶栏从根容器直系子节点拆进水平 `LinearLayout` / `DrawerLayout`（如小红书 `NoteDetailContentView` 切换为 `splitBody(leftPane, feed)` + `topBarRow(backBox, titleContainer)`，返回键与标题栏分处左右两棵子树）；
2. **`LayoutParams` 类型切换**：单栏用 `RelativeLayout.LayoutParams`（`BELOW statusBar`），双栏改用 `LinearLayout.LayoutParams`（运行时注入 `topMargin = statusBarH + 56dp`）；
3. **资源名混淆叠加异构树**：国服大版本（如小红书 9.56.0 `0_resource_name_obfuscated`）同时抹掉多数 ID 名，`getIdentifier` 返回 `0` 时须准备公开 getter / 控件语义类名或容器结构兜底；
4. **左右窗格独立视觉场景**：左图（淡灰底 `#F9F9F9`）与右文（白底 `#FFFFFF`）的 `RenderNode` 和背景色彼此独立，跨栏玻璃控件必须按各自所在窗格分别绑定 `RenderNode` 与底色，否则会出现跨栏色差或空采样。

## 5. 装机与验收纪律（与 AGENTS.md §1 红线配合）

- 装 APK 永远走 `adb install -r`（同签名覆盖），**用户在场 + 亮屏解锁**，每次单独授权。
- 装完**不必**一律打开模块 App：配置镜像跨 APK 更新保留，只有首次安装、改过设置、或宿主状态文件里 `keys` 明显偏少时才需要（判定见 AGENTS.md §3），然后重启目标 App 进程。
- 复现后 **10 秒内**拉日志/落盘文件——晚了缓冲区就轮转/被 chatty 吃掉。
- 用户体感是最终裁决；一轮改动只验一个行为点，别把多个变量混在一次验收里。
- **合包/侧载产物必须带 v1（JAR）签名**（2026-11-26 实测）：`adb install` 不校验 v1，但 **vivo 系统安装器会校验**，
  只有 v2/v3 的 APK 从文件管理器手动装会报「安装失败：系统原因」，而 adb 装同一份文件完全正常。
  apksigner 0.9 有两个坑：① 不显式传 `--max-sdk-version` 时即便 `--v1-signing-enabled true` 也不生成 v1；
  ② `--min-sdk-version` 必须 <24，v1 才进签名方案（取 21，不影响清单里的 minSdkVersion）。
  验收要分区间验：`apksigner verify`（默认区间）看 v2/v3、`--min-sdk-version 21 --max-sdk-version 23` 看 v1。
- **不要对 vivo 的 `wm shell vivoMultiTask` 发空子命令**：`adb shell wm shell vivoMultiTask` 会让 SystemUI 直接
  `ArrayIndexOutOfBoundsException: length=0`（`VivoMultiTaskShellCommandHandler.onShellCommand` 没做空参数保护）。
  这条通道能绕过所有 UI 闸门验证工作台，但必须带子命令（`moveTasksToVivoMultiTask <id[,id]>` 等）。

## 6. 通用 debug 循环（照抄即可）

```
1. 静态：xref 列候选入口 → 读方法体确认语义
2. 动态：探针（全重载）+ 关键节点栈 → 落盘
3. 复现：一次最短路径（冷启动/单次点击）
4. 读证：优先状态回传/落盘文件；logcat 只作辅证
5. 定位：确认真链路 + 判别字段 + 返回形态
6. 收窄：只改一个决定，探针保留
7. 复验：同一路径重跑，对比落盘
8. 固化：拆探针 → 正式版 → patch 入库 → 文档记录（含失败判据）
```

## 7. 战绩索引

| 战线 | 案例 | 结论 |
|---|---|---|
| 静盒 × 小红书 | UI-07 冷启动不刷新（2026-11-21）& 国服 9.56.0 全量重定位（0.9.34） | `djd.f#b` / `hrd.f#b` 闸门 + `just(emptyList())` + `hostCl` + 80 锚点跨包字节码对齐；详见 `upstream-derived/xhs-quietbox/README.md` |
| LiquidTab × 小红书/B站 | 返回动画丢失（coverfix2/3）& 折叠内屏双栏笔记顶栏失效（287） | 采样闸门 + `isFinishing` 冻结；双栏识别 `splitBody`/`topBarRow` 并分窗格采样 `RenderNode`；详见 `upstream-derived/liquidtab-mine/README-MINE.md` |
| vivo-ime × 输入法 Pro | 第三方 App 文本编辑置灰与触控板失效（0.1.9–0.2.1） | `invokeOriginalMethod` 局部放开 `ol.q` 类型位 + 非原生输入框触控板改发标准 DPAD/Shift 选字；详见 `original-modules/vivo-ime/README.md` |
| tg-clients × ExteraLess | 折叠屏双栏高级搜索面板错位（3.56.2） | 识别右栏 Fragment 容器边界替代整窗宽度假设；详见 `original-modules/tg-clients/README.md` |
| nuke × 抖音 | 冷启动 UI 丢失（已破案） | 双模块各掐一刀开屏状态机；详见 `upstream-derived/nuke/UPSTREAM.md` |
| XHook × X | 去广告锚点失效（0.2.9→0.3.1） | 锚点随版本漂移，必须运行时验证 hit；详见 `original-modules/xhook/README.md` |
| vivo-workbench × OriginOS 7 | 外屏原子工作台「装了等于没装」（0.1.0→0.2.0，2026-11-26） | 双层根因：① Vector 给 launcher 的 `com.vivo.recents` 回调是空壳 ClassLoader；② 官方在外屏硬编码拦死（`IS_FOLD && local:secondary → false`），没有任何用户开关。解法是 `PluginBridge` 走插件框架/`mPackages` 拿真 ClassLoader；详见 `original-modules/vivo-workbench/README.md` |
| bili-innocent × B站 | r9 手装报「系统原因」（2026-11-26） | 合包只有 v2/v3、缺 v1；`adb install` 无感、vivo 安装器拦截；已把 v1 固化进 `sharefix/build.sh`；详见 `upstream-derived/bili-innocent/README.md` |

## 8. 作业纪律：把墙钟花在"事件"上，不要花在"等待自己的失误"上

> 2026-11-27 一轮"LSPosed 模块批量更新 + Nuke 合并"复盘（08:32→11:16，2 小时 44 分）。
> 实测：Gradle/cargo/CMake 编译占 38 分钟（必要），jadx 卡死占近 50 分钟（全废），
> 等一个没启动的后台构建占 6 分钟，等设备重连占 7 分钟以上。
> **token 生成速度快，不等于任务完成快**；真正的墙钟在"等外部世界"。

### 8.1 开工前先探环境，把失败前置（10 秒省掉半小时）

新战线或新机器第一次构建前，先跑这几条再动手：

```sh
command -v cargo python3 cmake ninja zip    # 缺哪个工具
python -c "import fcntl"                    # Windows Python 没有；有 import 说明只能在 WSL/Linux 编
ls -d "$ANDROID_SDK_ROOT"/ndk/* 2>/dev/null # 版本对不对
ls "$ANDROID_SDK_ROOT"/ndk/*/toolchains/llvm/prebuilt/   # windows-x86_64 还是 linux-x86_64
```

教训：`tools/*.py` 只要 `import fcntl`，Windows 上就永远编不了；NDK 只有 `windows-x86_64` 时，
WSL 里也没法用（需要 Linux 版 NDK）。这两条一旦没探，就会在"合并完整棵树"之后才发现，
前面所有准备都变成沉没成本。

### 8.2 长任务用 `setsid` + pid 文件，判活绝不用 `pgrep -f`

```sh
# WSL/Linux：日志写自己的 $HOME，不写 /tmp（重启就没了），也不写 /mnt/c（9p 慢）
setsid bash -c 'cd <repo> && exec <long-command>' > "$HOME/build.log" 2>&1 < /dev/null &
echo $! > "$HOME/build.pid"
kill -0 "$(cat $HOME/build.pid)" 2>/dev/null && echo RUNNING || echo DONE
```

- `nohup ... &` 放在 `wsl.exe -lc '...'` 里会被回收，进程和日志一起消失（实测）。
- `pgrep -f <关键字>` 会匹配到**你自己那条包含关键字的命令行**，必然误报 RUNNING。
- 判断"到底有没有在跑"，看 pid 文件 + `kill -0`，或看日志有没有增长（`stat -c %s`）。

### 8.3 反编译工具按问题选，jadx 必须带超时

| 问题 | 工具 | 耗时 |
|---|---|---|
| "这个方法现在被改成什么了" / 校验补丁结果 / 数指令 | `dexdump -d classes.dex` 或 baksmali | 秒级 |
| 看某处控制流、字段赋值 | `jadx --single-class <cls>`，**必须 `timeout 120`** | 分钟级，且可能不返回 |
| 读大段业务逻辑、写人看的报告 | jadx 整包 | 十分钟级，放后台 |

教训：为确认一个闸门方法是否恒返 true，jadx 单类跑了近 50 分钟没出结果；`dexdump` 3 秒给出
`const/4 v0, #1 / return v0 / nop`，而且看的是字节码本身，比反编译还原的 Java 更可信。

### 8.4 互不依赖的战线要同时开

同一轮里"下载上游包 / 装工具链 / 编译模块 A / 分析模块 B / 查 Store 版本"彼此不依赖，
必须并行起跑再逐个收。串行执行是本项目最常见的墙钟浪费来源：
把 4 条互不依赖的战线做成串行，会把 40 分钟拖成 2 小时 40 分。

### 8.5 装机的对账不能省，但要批量做

设备红线要求每个包装完核对**版本 / 设备侧 SHA-256 / system_server·lspd·zygote64 的 PID 不变**。
这三件事逐条手打会吃掉大量时间，写成一个循环脚本一次跑完（`cache/scratch/install_batch.sh` 是范例），
并且脚本里把"任何非纯 `Success` 的输出"当失败立即停。
