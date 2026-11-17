# liquidtab-mine — LiquidTab 本机维护 fork

> 上游：[big-dictator/LiquidTab](https://github.com/big-dictator/LiquidTab) v2.1.0（GPL-3.0，作者 bigdictator）。
> 本目录 = v2.1.0 源码 + 我们的补丁；LICENSE / NOTICE.md / THIRD_PARTY_ASSETS.md / README.md 均为上游原件。
> 上游 README 说明见 [README.md](README.md)。

## 我们改了什么（相对上游 v2.1.0）

**mine3**（2026-11-17，versionCode 226）：

**修复详情→主页信息流返回动画**（上游 bug，XHS 与 B 站同根因）。

- 症状：XHS 详情返回直接消失（无收缩动画）；B 站返回动画像被加速无数倍。
- 根因（已实证）：详情打开时主页 header/page 被宿主 detach，上游在 detach 时
  `stop()`→`restoreNative()`/`restore()`，**主页布局在详情期间被恢复成原生、
  信息流卡片位置整体移动**，宿主返回动画记录的收缩目标失效。
  证据：顶栏关闭后动画恢复（录屏 `xhs/work/back_topbar_off.mp4`）；
  详情期间 `dumpsys activity top` 中 `HomeTabLayout` 从主页树消失（1→0）。
- 修复：**detach 时不再恢复原生，玻璃几何状态在被覆盖期间冻结**。
  - `XhsTopBar`：删掉 `onViewDetachedFromWindow` 里的 `sessions.remove(row)?.stop()`，
    保留 Session 与 saved 状态，重新 attach 时经 `attach()` 的 sessions 检查直接复用。
  - `BilibiliHomeLayoutHooks`：onPreDraw 的 `!page.isAttachedToWindow` 分支删掉 `stop()`，
    保留 `attach(activity)` 复活路径（同 page 复用、换 page 才 stop 重建），加 300ms 节流。
- 配置里主动关顶栏时仍走原 `restoreNative()` 路径，行为不变。

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

## 装机状态（2026-11-17）

- 手机当前装：`io.github.offlineglass` 224 / `2.1.0-xhsfix1`（debug 版；release 待用户亮屏确认后 `install -r` 覆盖）。
- Vector：模块启用，作用域含小红书等 21 个 App（**不含 SystemUI**——刻意不勾，高风险层）。
- 装后必须打开一次模块 App（Vector 2.2 更新怪癖），重启宿主；验证只认
  `/data/adb/lspd/log/verbose_*.log` 与 `logcat -s LiquidTab*`。

## 同源参考（不在本目录，gitignored）

- 上游源码 zip / 原 v2.1.0 APK：`xhs/work/liquidtab/`
- 本机小红书 APK（9.54.0）与顶栏取证：`xhs/work/device-xhs-current/`、`xhs/work/dumpsys_fg.txt`
