# liquidtab-mine — LiquidTab 本机维护 fork

> 上游：[big-dictator/LiquidTab](https://github.com/big-dictator/LiquidTab) v2.1.0（GPL-3.0，作者 bigdictator）。
> 本目录 = v2.1.0 源码 + 我们的补丁；LICENSE / NOTICE.md / THIRD_PARTY_ASSETS.md / README.md 均为上游原件。
> 上游 README 说明见 [README.md](README.md)。

## 我们改了什么（相对上游 v2.1.0）

**xhsfix3**（2026-11-17，versionCode 230）= **xhsfix2 + 全部回滚动画补丁**。

当前有效补丁只有 xhsfix1（热词隐藏）+ xhsfix2（顶栏高度滑条），见下文。
**内屏（Pad/宽屏）详情→信息流返回动画失效是上游固有 bug，本版不处理。**

### 失败记录（勿重蹈）：返回动画修复 mine3–mine6 全部失败

四种架构全部失败且导致外屏退化，已逐文件回滚（commit 5a9820b）：

1. **mine3**（detach 不 restoreNative，冻结玻璃几何）——内屏仍失效。
2. **mine4**（viewport 只写一次 + 返回后 500ms 不做背景抓取）——内屏仍失效（偶发好一次）。
3. **mine5**（详情 onCreate/覆盖期间 restoreNative 恢复原生 + 返回后 400ms 再上玻璃）——内屏仍失效，**外屏也开始闪**。
4. **mine6**（纯覆盖：不 hide 原生顶栏、不透明 scrim 遮挡、不改信息流）——**内外屏都失效**，且原生顶栏不隐藏导致外观更差。

教训：用户实测“关重构顶栏 → 动画恢复”“外屏（窄屏）正常、内屏（宽屏/Pad）失效”。
根因未明，**任何在未真正理解 XHS Pad 模式返回动画实现之前的猜测式修改都是负优化**。
已排除的错误假设：detach 恢复、viewport 布局争夺、PixelCopy 顶掉窗口动画（PixelCopy 只读不影响 WindowManager）、
hide 原生顶栏导致动画演员缺失（不 hide 也一样坏）。
**要继续修，必须先真正逆向 XHS Pad 模式返回动画（NoteDetailSwipeBackLayout / NoteDetailView 等），
或者给上游作者报 bug（附本文诊断）。**

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
