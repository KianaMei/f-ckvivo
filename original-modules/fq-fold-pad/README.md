# 番茄折叠屏双列（fq-fold-pad）

LSPosed / Vector 模块，目标包 `com.dragon.read`。
**实测机：vivo X Fold6（V2545A / PD2545，内屏 2312×2504、外屏 1120×2528，Android 17），番茄 7.4.3.32。**

成品：`dist/fq-fold-pad-1.4.0.apk`（已签名）。

> **行为约定（1.4.0 起）**
> - **内屏（展开）**：平板布局 + 阅读页双列 + 横屏判定，全套生效。
> - **外屏（折叠）**：模块检测到后**完全不介入** —— 不改平板判定、不改分栏开关、不改方向、不改 Configuration，
>   并且会把内屏时下过的横屏请求撤销（`UNSPECIFIED`），恢复番茄自己的 manifest 行为。
>
> 判定阈值：`smallestScreenWidthDp >= 600`（内屏 801dp，外屏 345dp）。

---

## 一、双列到底是怎么判的（7.4.3.32 逆向结论）

链路（全部来自 dex 反汇编，非猜测）：

```
ReaderActivity
 └─ DefaultFrameController.R1()                // 配置变化时重算
     └─ IReaderConfig.r0(widthDp, heightDp)    // 实现类 uo6.s（继承 jt7.z0）
         = util.b5.b() && ( widthDp >= heightDp || ( isFoldDevice() && xm5.d2.a ) )
 └─ DefaultFrameController.p2()                // 是否真的按双列排版
     = q(由 r0 置位) && xt7.e.n(getPageTurnMode())   // n(): mode ∈ {1,2,3,5} 才算"左右翻页"
```

相关开关的存储位置：

| 位置 | key / 类 | 含义 |
|---|---|---|
| ssconfig 模板 | `AndroidPadDoublePage(enable, isDefault2col)`（`android_pad_double_page_v621`） | 平板双页总开关 + 默认分栏 |
| ssconfig 模板 | `xm5.d2.a`（`reader_split_config_v733`） | **折叠屏分栏开关**，服务端下发，默认 false |
| 私有 SP | `app_global_config / is_reader_split_enable` | 用户上次选的单/双列（`util.b5.d()` 写入） |

**为什么方形折叠屏永远不出双列**：展开态 2312×2504 → `widthDp 800.7 < heightDp 806.6`，
第一支不成立；只能落到 `isFoldDevice() && reader_split_config_v733`，而这个配置默认关。
（另外 `AndroidPadDoublePage.enable` 强开 true 会让 `util.b5` 走"依赖该配置"的分支，**反而关掉双列**，
所以模块给它的是 `(false, true)`。）

菜单里的单/双列切换是 `lk7.d1`（`ReaderActivity` 的菜单组件）：切到双列要求
`Configuration.orientation == LANDSCAPE` 且 `getPageTurnMode()` 是左右模式。

## 二、模块做了什么

全部 Hook **都带内屏判定**，外屏直接原样返回。

| 层 | Hook | 内屏行为 | 外屏行为 |
|---|---|---|---|
| L1 平板判定 | `IAndroidPadConfig` / `IIsPadConfig` / `IAndroidPadFitConfig` / `IAndroidFoldConfig` 的 `$$Impl.getConfig()` | 返回全 true | 返回原值 |
| L1b 平板双页 | `IAndroidPadDoublePage$$Impl.getConfig()` | `AndroidPadDoublePage(false, true)` | 返回原值 |
| L1c | `IPadFitPhaseTwoV691$$Impl` | 全 true | 原值 |
| **S1** | `xm5.d2.a`（静态实例 + 构造） | 强制 true | 不动 |
| **S2** | `util.b5#b()Z`；`b5#a(Configuration)` | true | 原值 / 不置位 |
| **S3（决定性）** | `uo6.s#r0(float,float)Z` | 恒 true | 原值 |
| L2 方向 | `Activity#setRequestedOrientation` / `onCreate` / `onResume` / `onConfigurationChanged` | 竖屏类请求改写成 SENSOR_LANDSCAPE | 原样放行；若发现模块下过的横屏请求则改回 `UNSPECIFIED` |
| L3 方向判定 | `Configuration#setTo/updateFrom`、`Resources#getConfiguration` | `orientation = LANDSCAPE` | 按该 Configuration 自己的宽高算回真实方向（通常 PORTRAIT），顺带修掉内屏留下的脏值 |
| L4 开关服务 | `CSSReaderApi.readerSwitchService()` 的 `boolean(String)` | pad/land/fold/double_page 等 key 强制 true | 不动 |
| 诊断 | `DefaultFrameController#R1()`、`Activity#onConfigurationChanged` | 打印 `q / p2 / pageTurnMode` 与 `GATE` 状态 | 同 |

实测日志（折叠/展开来回切过）：

```
GATE INNER 801x807dp sw801dp -> apply module hooks  @onCreate ...SplashActivity
L2 ...ReaderActivity -> SENSOR_LANDSCAPE (inner)
S3 r0 widthDp=800.6926 heightDp=806.5801 inner=true original=false -> true
S4 frameController q=true p2=true pageTurnMode=5

GATE OUTER 388x815dp sw388dp -> leave untouched  @onConfigurationChanged ...ReaderActivity
L2 ...ReaderActivity -> UNSPECIFIED (outer, restore manifest value)
S3 r0 widthDp=387.87878 heightDp=835.67096 inner=false original=false (outer, untouched)
S4 frameController q=false p2=false pageTurnMode=5

GATE INNER 801x807dp sw801dp -> apply module hooks  @onConfigurationChanged ...ReaderActivity
```

截图对照：`shots/inner-back.png`（内屏双列）、`shots/outer-sim.png`（外屏单列竖排、页码连续）。

## 三、安装与验证

1. `adb install -r dist/fq-fold-pad-1.4.0.apk`
   （本机普通 adb 安装会弹权限确认，可改用 root：`su -c 'pm install -r -d /data/local/tmp/fq140.apk'`）
2. LSPosed/Vector 里启用「番茄折叠屏双列」，作用域 `com.dragon.read`（含子进程）
3. **强制停止番茄**后重开
4. 看日志：`adb logcat -s FqFoldPad`，关注 `GATE` 行是 `INNER` 还是 `OUTER`

外屏自测（不用真折）：`adb shell wm size 1120x2528` 会把逻辑尺寸压到外屏级别，
配置变化 → `GATE OUTER` → 阅读页立刻退回单列；`adb shell wm size reset` 复原。

## 四、注意事项

- **翻页方式必须是「左右」**（仿真/覆盖/滑动/无动画都行）。"上下滚动"时 `xt7.e.n()` 返回 false，引擎不分栏，这是番茄自身限制。
- 双列只在阅读页生效；回单列：阅读菜单 → 设置 → 单列/双列。
- 物理旋屏模块不强行拉：Android 12L+ 大屏默认忽略 App 的方向请求，模块保证的是"番茄认为横屏 + 引擎强制分栏"。
- 模块若在**外屏启动**，S1/S2 的静态值保持关闭；等展开进内屏时 `r0`/`b5.b()` 会实时返回 true，无需重启。

## 五、构建（Windows / Git Bash）

```sh
VERSION_NAME=1.4.0 VERSION_CODE=5 ./build.sh
```

依赖：JDK 17+、Android SDK。`build.sh` 会自动探测 JDK / SDK 并选用本机最高版本的
platform 与 build-tools，必要时用 `JAVA_HOME`、`ANDROID_SDK_ROOT`、`ANDROID_BUILD_TOOLS`
覆盖；没有系统 `zip` 时自动回退到 build-tools 的 `aapt`。首次构建自动生成
`.signing/fq-fold-pad.keystore`。

## 六、逆向工具（tools/）

- `tools/dexscan.py` — dex 解析：`classes` / `detail` / `strings`
- `tools/xref_string.py`、`tools/xref.py` — 按**字符串常量 / 方法 / 字段**反查调用点
  （这次从"双列"字符串一路摸到 `uo6.s#r0` 就靠它）
- `tools/patch_gate1.py`、`tools/patch_gate2.py` — 1.4.0 加入内外屏门控时的补丁脚本（留档）

```sh
python tools/xref.py apk/dragon-7.4.3.32.apk method "util.b5#b"
python tools/xref.py apk/dragon-7.4.3.32.apk field  "DefaultFrameController#q"
```

## 七、未生效 / 未验证

- `SPOOF_MODEL`、`FORCE_DISPLAY_ROTATION_90` 默认关闭，本次用不到。
- 平板三 Provider 类名沿用 [ciaozz/fanqie-paragraph-comment-enabler](https://github.com/ciaozz/fanqie-paragraph-comment-enabler) 的已验证路径。
- 后续版本若 `uo6.s` / `xm5.d2` 改名，用 tools/ 的方法重新反查（`reader_split_config_v733`、`is_reader_split_enable` 这类 key 稳定）。
- 内屏判定阈值 600dp 是按本机实测定的；换其它折叠屏若内屏 `smallestWidthDp` 偏小，改 `INNER_MIN_SMALLEST_WIDTH_DP` 即可。
