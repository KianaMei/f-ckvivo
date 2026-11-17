# liquidtab-mine — LiquidTab 本机维护 fork

> 上游：[big-dictator/LiquidTab](https://github.com/big-dictator/LiquidTab) v2.1.0（GPL-3.0，作者 bigdictator）。
> 本目录 = v2.1.0 源码 + 我们的补丁；LICENSE / NOTICE.md / THIRD_PARTY_ASSETS.md / README.md 均为上游原件。
> 上游 README 说明见 [README.md](README.md)。

## 我们改了什么（相对上游 v2.1.0）

**coverfix2**（2026-11-17，versionCode 234，最终版）—— **修复详情→信息流返回动画（真正根因）** ✅ 用户真机确认

- **症状**：XHS 进笔记详情再返回，原生收缩动画被跳过（直接闪现回信息流）；
  内屏（折叠展开/Pad 宽屏）必现，外屏合上后的第一次可能正常、之后全坏且永不自愈。
  B 站返回动画同样异常（像被加速）。
- **根因（实证）**：底栏玻璃的 **每帧 backdrop 取景采样循环**
  （`GlassHostLayout.installCaptureLoop` 的 `OnPreDrawListener` →
  `prepareLiveBackdropScene()` / PixelCopy / RenderNode 记录）在**返回转场期间仍然活跃**。
  返回瞬间主页 window 重获焦点，采样 pass 立即让窗口产出终帧，
  WindowManager 便跳过了宿主 `overridePendingTransition` 的收缩动画。
  机制上与“PixelCopy 只是异步只读”无关——是**采样活动本身**打断转场帧调度。
- **判定证据链**：
  1. 模块里小红书总开关关（E 测试）→ 动画恢复（玻璃也没了）；
  2. `backdrop_capture=false` → 玻璃全没（该开关是渲染引擎本身）→ 等价 E；
  3. 顶栏/详情玻璃/导航栏侵入写入三个开关**任意组合**都与动画无关（bisect1 全排除）；
  4. 还原版 v2.1.0 同机同坏，别的手机（小米）正常 → 与我们的 fork 无关；
  5. **coverfix 只暂停采样 → 动画恢复**（决定性）。
- **修复**：
  - 新增 `XhsDetailCover`（`XhsDetailGlass.kt`）：用 `WeakHashMap<Activity, Boolean>`
    记录存活的 `NoteDetailActivity`（onCreate enter / onStop+onDestroy exit）。
    paused 窗口 = 详情打开 → 返回动画结束（onStop 在动画之后触发，正好覆盖全程）。
  - `XhsNavigationState.pauseBackdropWork(host)` → 详情存在时返回 true，
    底栏采样循环（`GlassHostLayout:12838`）每帧自查后暂停采样，玻璃显示最后一帧。
  - **coverfix2 追加**：exit 时唤醒被暂停的 host（`postInvalidateOnAnimation` + 80ms 后
    补一次 invalidate），否则返回后底栏“首页”标签文字会消失、必须切 tab 才恢复
    （冻结帧上标签未重绘）。
- **为什么以前 mine3–mine6 全失败**：那些都在改布局/hide/恢复原生——动错了对象。
  真凶是采样时机，与原生 View 的隐藏与否无关。KISS。

**bisect1 排查开关（保留，默认开）**：设置页小红书页新增
「详情页玻璃（动画排查）」「导航栏侵入写入（动画排查）」——本次已排除两者，
保留作以后别的 App 排查用。

### 失败记录（勿重蹈）：mine3–mine6 全部失败

四种架构全部失败且导致外屏退化，已逐文件回滚（commit 5a9820b），教训：

1. **mine3**（detach 不 restoreNative，冻结玻璃几何）——无效。
2. **mine4**（viewport 只写一次 + 返回后 500ms 不重建）——无效（偶发好一次，误导）。
3. **mine5**（详情期间 restoreNative + 返回后 400ms 再上玻璃）——无效，外屏也开始闪。
4. **mine6**（纯覆盖：不 hide 原生顶栏、scrim 遮挡、不改信息流）——内外屏都无效，外观更差。

反面教训：**“某次录屏里看到动画恢复了”不可信**（帧采样差、偶发）。
唯一可信的是用户真机反复操作的主观确认——本次坚持到最后才找到
“暂停采样”这一个正确变量。另外：**capture=false 会让玻璃整体不渲染**，
所以“关 capture”是假阳性路径，不能当修复。

### 通用化：其他 App 的适配套路（以后挨个适配时用）

这个根因不是小红书独有的。任何采用“原生详情页 + 原生收缩返回动画”的宿主，
在底栏采样活跃时都可能被跳过（B 站已观察到同症状）。给新 App 适配时：

1. 找宿主的 **详情/次级 Activity 类名**（`dumpsys activity top` 或 jadx）；
2. 在对应 adapter 的 detail hooks 里同样 enter/exit 一个 cover；
3. `AppNavigationState.pauseBackdropWork(host)` 返回该 cover 状态；
4. exit 时唤醒 host 重绘（照抄 `XhsDetailCover.exit` 的唤醒逻辑，避免标签冻结）；
5. 如果宿主是 `Fragment` 式详情（无独立 Activity），改挂
   `onFragmentResumed/onFragmentPaused` 或宿主自己的转场状态。

**mirrorfix**（2026-11-17，versionCode 238，✅ 用户真机确认）—— 断掉"鸡生蛋"：镜像策略放宽 + 发布重试

- **237 实测暴露的问题**：宿主存档只在**第一次成功读到配置**时生成；而 vivo 在模块 App
  退后台几秒内就冻结/强停它 → provider 死 + 镜像发布（异步绑定 libxposed service）
  三次静默失败（20:50、22:09，无 "remote settings published" 日志）→ 第一次成功
  永远不发生 → 存档永远不生成（`liquidtab_last_good_config.json` 缺失实锤）。
- **修复三件套**：
  1. `StartupPreferencesPolicy` 不再要求 `liquid_glass_enabled` 显式存在（revision
     有效即算导出过；缺省按 true）——**当下停在 20:16 的旧镜像立刻可用**；
  2. `ModernSettingsBridge.tryPublish`：模块 App 前台期间每秒重试×15 + provider 每次
     METHOD_GET 顺手发布（内部按 revision dedup，重复调用零成本）——只要它活过几秒
     镜像永久追平；
  3. （237 已有）宿主存档随后从任一成功读取落地，四层兜底闭环。
- **验证**：装后**只划掉模块 App、别动小红书**重开小红书 → 玻璃应出现（走旧镜像，
  日志 `SOURCE provider`→无，`READY` 前无 WAITING/incomplete）；再开一次模块 App
  几秒 → 日志应见 `remote settings published`，小红书目录出现 last-good 存档。

**hostfallback**（2026-11-17，versionCode 237，✅ 随 238 一并确认）—— 宿主本地配置存档（第四层兜底）

- **背景**：236 把 `liquid_glass_enabled` 落盘了，但 Vector 远程偏好**镜像**只在模块 App
  绑定 libxposed service 成功时才发布——实测 20:50 那次绑定静默失败（无 "remote settings
  published" 日志），镜像停在 20:16 的 revision（宿主诊断 `modern_framework_status.txt`
  = `revision=1794917782157 keys=65`，而磁盘已 `1794919794992`+新键）。于是
  "划掉模块 App + 重启宿主" 必然 WAITING。
- **修复**：宿主每次接受新 revision 配置时，把完整 GlassConfig（toBundle 去
  `system_dark`）JSON 存进**宿主自己**的
  `Android/data/<pkg>/files/liquidtab_last_good_config.json`（有先例：诊断文件就写这）。
  读取链变为：RAM committed → provider → Vector 镜像 → **宿主本地存档** → 才禁用。
  日志标记：`LiquidTabStartup: last-good archived …` / `SOURCE last-good pkg=…`。
- **验证**：装后打开模块 App 一次（生成存档）→ 划掉模块 App → 强停并重开小红书 →
  玻璃应照常（日志见 `SOURCE last-good`）。改设置时存档随 revision 更新。
- **遗留（独立问题）**：20:51 起观察到部分小红书**主进程**冷启动 Vector 完全未注入
  （无模块 dex、零 LiquidTab 日志，而 :pushservice/:widgetProvider 子进程正常注入；
  24971 与一次前台 adj=0 的进程均未注入）。这不是模块问题——命中时连 hook 都不在，
  需单独排查（怀疑 USAP 预启动路径或 Vector 模块缓存），复测方法：连续划掉重开数次
  看玻璃是否"有时有有时没有"。

**prefsfallback**（2026-11-17，versionCode 236，被 238 的策略放宽取代）—— 修"划掉模块 App 玻璃全失效"

- **症状**：把模块 App 从最近任务划掉（vivo 冻结其进程）后，所有宿主的玻璃整套不渲染；
  打开一次模块 App 即恢复。用户问"模块不该一直生效吗"——hook 确实常驻，但配置是活的。
- **机制**：宿主每秒经 ContentProvider 回读设置（滑条实时生效的原因）；provider 死后
  走 Vector `getRemotePreferences` 磁盘镜像兜底。但 `StartupPreferencesPolicy` 要求
  `liquid_glass_enabled` 键**显式存在**，而该键只在用户拨"液态玻璃"总开关时才写——
  从没拨过 → 键缺失 → 兜底被判 `incomplete-global-settings` → 模块按"配置不可信
  =不渲染"处理。证据：宿主诊断
  `files/startup_preferences_status.txt` = `incomplete-global-settings revision=…`，
  CE `offline_glass.xml` 65 键无 `liquid_glass_enabled`（grep -c = 0）。
- **修复**：`applyFormalDefaultsOnce` 首次发现键缺失时把实际默认值（true）落盘并
  bump revision → 镜像完整，兜底路径永久修通。xhsfix1/xhsfix2/coverfix 的键都走
  `KEY_*` 常量读、磁盘已有，兜底不丢配置。
- **验证**：装后打开一次模块 App（触发 defaults 落盘+镜像发布）→ 划掉它 →
  重启小红书 → 玻璃应照常渲染（`logcat -s LiquidTabStartup` 应见
  `accepted=true revision=…` 而非 `incomplete-global-settings`）。

**coverfix3**（2026-11-17，versionCode 235，✅ 用户真机确认）—— 修 coverfix2 漏掉的两个转场窗口

- **症状 1**：详情完全打开后**立刻返回**，收缩动画仍丢失（原版 App 正常）。
- **症状 2**：打开详情后切后台（如 5 分钟）再回来，详情还在，但返回动画再次失效（原版正常）。
- **根因（coverfix2 生命周期太粗）**：
  1. `onStop` 不分原因一律 exit：XHS 退后台时详情的 `onStop` 触发，cover 被清空；
     回来时 `onPostResume` 只重启 detail 玻璃 Session、**没有重新 enter**，于是
     “详情还在前台、底栏采样已恢复”，再按返回必丢。
  2. 快速返回时 `onStop`/`onDestroy` 可能早于收缩动画结束，立刻 exit + 唤醒 host
     会在转场中恢复采样，同样吃动画。
  3. `GlassHostLayout.onDraw` 还有一条**不看 pause 门禁**的采样旁路
     （`liveBackdropActive == false` 时），首个未准备 live scene 的帧会在转场中补采样。
- **修复**：
  - `onStop` 仅在 `isFinishing`（真返回）时安排 exit，且**延迟 500ms**
    （`EXIT_SETTLE_MS`）结算，盖住整个转场；退后台保持 cover 武装。
  - `onPostResume` 重新 `enter`（后台回来重新武装）。
  - `onDestroy` 也改为延迟 exit，防止早于动画结束。
  - `onDraw` 采样旁路加同一 `pauseBackdropWork` 门禁（对所有 adapter 生效）。
  - 新增日志 tag `LiquidTabCover`：`adb logcat -s LiquidTabCover` 可看
    enter/exit 时序，用于核对 onStop 与转场的先后。
- **验证路径**（装机后逐条走）：① 详情全开立刻返回（内屏+外屏）；
  ② 打开详情 → 切后台 5 分钟 → 回来 → 返回；③ 正常停留后返回（回归）。

**xhsfix2**（2026-11-17，versionCode 225）：

**小红书顶栏高度自定义**（上游写死 40dp，底栏却有 barHeight 50–64dp 滑条）。
新增 `xhs_top_height` 配置（32–56dp，默认 40=原视觉），全链路打通：
设置页小红书页新增"顶栏高度"滑条 → LocalSettings/Provider → GlassConfig（钳制+Bundle）
→ `XhsTopBar.barHeightPx()` 驱动 controls 高度、左右按钮宽度（保持正方形）、
tab 玻璃 pill 高度、headerBottom、频道按钮 onMeasure。配置每秒轮询，拖滑条实时生效，
无需重启小红书。仅改 XHS 顶栏；抖音/B站顶栏仍为上游写死，要动再说。

**xhsfix1**（2026-11-17，versionCode 224）：

宽屏/折叠形态下，小红书首页顶栏右侧的原生搜索**图标**（`#7f092e7c`，`XhsTopBinding.headerAction` 的目标）
会被一个可点击的**热搜词胶囊 TextView**（如 "阿里qwen4"，`AppCompatTextView #7f09280d`）替换，
且图标被 XHS 置 GONE。上游顶栏玻璃只 hide ImageView，热搜胶囊成为残留。
证据：`xhs/work/dumpsys_fg.txt`（前台 View Hierarchy）+ `xhs/work/xhs_now.png`（截图，gitignored）。

补丁（全部结构性查找，不依赖混淆 id）：

- `XhsTopBinding.headerText(tabs)`：仿 `headerAction`，找 tabs 右侧、垂直对齐 32dp 内、
  可点击的 TextView → `hotSearchSource`。
- `XhsTopBar`：`hotSearchSource` 加入 hide 名单；`ready` 条件放宽为
  `searchSource != null || hotSearchSource != null`；玻璃搜索按钮点击兜底
  `click(searchSource ?: hotSearchSource)`。
- 窄屏（无热搜胶囊）布局下 `headerText` 返回 null，全部改动为 no-op。

另有两处构建侧改动（非 hook 逻辑）：

- `lint { checkReleaseBuilds = false }`：Xposed hook 非 SDK 接口触发 lintVital 全是误报。
- release `signingConfig "installOver"`：与已装机 debug 包同用 `~/.android/debug.keystore`
  （`ANDROID_DEBUG_KEYSTORE` 可覆盖），保证 `adb install -r` 直接覆盖升级、不触发卸载。

## 构建与签名（重要）

```sh
cd liquidtab-mine
# 首次构建需 local.properties: sdk.dir=C:/.../Android/Sdk（gitignored）
# JDK 21；gradle.properties 的 java.installations.paths 按机器调整
./gradlew :app:assembleRelease   # → app/build/outputs/apk/release/app-release.apk (~10.7MB)
./gradlew :app:assembleDebug     # debug 包 ~84MB（无 R8），仅救急用
```

**签名连续性红线**：装机签名 = debug keystore。**换签名 = 必须卸载重装 =
PACKAGE_* 广播红线（根 AGENTS.md §1.0），别动。**

## 装机状态（2026-11-17 晚）

- 手机当前装：`io.github.offlineglass` 238 / `2.1.0-mirrorfix`（用户真机确认：
  返回动画三路径正常 + 划掉模块 App 后小红书冷启动玻璃照常渲染）。
- Vector：模块启用，作用域含小红书等 21 个 App（**不含 SystemUI**——刻意不勾，高风险层）。
- 装后必须打开一次模块 App（Vector 2.2 更新怪癖），重启宿主；验证只认
  `/data/adb/lspd/log/verbose_*.log` 与 `logcat -s LiquidTab*`。
- **vivo 现场认知（22:xx 实测）**：模块 App 退后台数秒即被冻结/强停 →
  provider `Unknown authority`；libxposed service 绑定常来不及完成。配置读取
  依赖四层兜底（RAM committed → provider → Vector 镜像 → 宿主本地存档），
  详见 mirrorfix/hostfallback 两节。遗留：小红书主进程偶发未被注入
  （子进程正常），与配置链无关，待查。

## 同源参考（不在本目录，gitignored）

- 上游源码 zip / 原 v2.1.0 APK：`xhs/work/liquidtab/`
- 本机小红书 APK（9.54.0）与顶栏取证：`xhs/work/device-xhs-current/`、`xhs/work/dumpsys_fg.txt`
