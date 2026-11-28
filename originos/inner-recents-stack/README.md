# OriginOS 7 折叠屏内屏最近任务零 Hook 堆叠形态

> 遵守仓库 [AGENTS.md](../../AGENTS.md) 顶栏「设备不是草稿纸」与 §1 设备红线：
> **默认只读；写入设备前须向用户说明「做什么 + 影响 + 风险」并获当次授权；不新增设备临时文件、不杀系统进程、不重启。**

- **验证基线（2026-11-28 真机实测通过）**：
  - 设备：vivo X Fold6（`PD2545D` / `V2545A`，OriginOS 7 / Android 17，内屏 `2312×2504`，外屏 `1120×2528`）
  - 宿主 APK：`/system/app/QuickstepPlugin/QuickstepPlugin.apk`（`com.vivo.recents` `17.0.0.214`，`versionCode=1700000214`，SHA-256 `6e846028de9ebe51e1955fe696a7cb7ab67fa52ed40e86267b5ba83cfb43bb3d`）
- **一句话结论**：无需任何 Xposed / LSPosed Hook（不碰 `system_server`，也不碰桌面/插件代码），利用原厂 `RecentsPreferences.getPageOrGridLayoutMode()` 与 `BlurRecentsAppProvider` 对 `recents_layout_mode_inner` 缺少范围校验的漏口，直接写入堆叠枚举值 `128`，**重启后即使丢失临时 root 仍持久化生效**。

---

## 一、操作与回滚手册

### 1. 只读核对当前状态

```sh
# 确认处于展开态（1 = 展开，2312x2504）
adb shell "getprop sys.foldphone.status; wm size"

# 查看两侧 SharedPreferences 与 Provider 当前值（4=宫格，0=横向平铺，128=堆叠）
adb exec-out su -c "cat /data/user_de/0/com.vivo.recents/shared_prefs/pref_recents.xml"
adb exec-out su -c "cat /data/user_de/0/com.bbk.launcher2/shared_prefs/pref_recents.xml"
adb exec-out su -c "content query --uri content://com.bbk.launcher2.recents.blurapp/layout_mode_inner"
```

### 2. 开启内屏堆叠（必须在展开状态下执行）

```sh
adb exec-out su -c "content insert --uri content://com.bbk.launcher2.recents.blurapp/layout_mode_inner --bind recent_layout_setting_inner:i:128"
```

- **为何必须展开态执行**：`BlurRecentsAppProvider.insert()` 写完 `com.vivo.recents` 的 `pref_recents.xml` 后会发出 `notifyChange`；桌面进程 `com.bbk.launcher2` 里的监听器 `RecentsPreferences$2` 收到通知后调用 `queryLayoutMode()`，只有 `isFoldableOpenState() == true` 时才会查询 `/layout_mode_inner` 并把 `128` 同步进桌面进程的 `pref_recents.xml`。
- **如何无重启生效**：确认两个 `pref_recents.xml` 均变为 `recents_layout_mode_inner="128"` 后，**手动把手机合盖一次（亮起外屏）再展开回内屏**，触发 `RecentsView.onFoldedStateChanged()` → `TaskStackView.onLayoutModeChanged()` 重建 `StackingTaskStackLayoutAlgorithm` 与 `StackedTaskView` 视图池。无需 kill 桌面或重启。
- **权限说明**：`BlurRecentsAppProvider` 受 `com.bbk.launcher2.permission.READ_RECENTS_BLUR_APP`（`signatureOrSystem`）保护，普通 `adb shell`（uid 2000）无权限，写入一步需 `su`。

### 3. 一键回滚（同样在展开状态下执行）

```sh
# 还原为原厂内屏默认宫格（4）；如需内屏横向平铺则改为 0
adb exec-out su -c "content insert --uri content://com.bbk.launcher2.recents.blurapp/layout_mode_inner --bind recent_layout_setting_inner:i:4"
```

写完后再**合盖一次、展开一次**即可恢复原厂平铺/宫格算法。

---

## 二、零 Hook 原理（基于 `QuickstepPlugin 17.0.0.214` 反编译实证）

1. **原厂内屏拦截点（无官方隐藏开关）**
   - `com.vivo.recents.recents.utils.RecentsPreferences#getLayoutMode()`：
     - `isFoldableOpenState() || VersionUtils.isPad()` 直接走 `getPageOrGridLayoutMode()`，跳过 `Settings.Secure` 的 `recents_view_mode`（`0`=堆叠、`1`=平铺）。
     - 全包审计 `VersionUtils`、`QuickstepFeatureManager`、`FtFeature`、`SystemProperties`、`Settings.*`，均无开放内屏堆叠的官方开关。
2. **未校验漏口**
   - `RecentsPreferences#getPageOrGridLayoutMode()` 直接返回 `pref_recents.xml` 中的 `recents_layout_mode_inner`（默认 `4`），**不做 `0/4` 合法性校验**。
   - `com.vivo.recents.blur.BlurRecentsAppProvider#insert()` 匹配 `/layout_mode_inner` 后，直接将 `recent_layout_setting_inner` 写入 `pref_recents.xml` 并 `notifyChange`；桌面进程中的 `mLayoutInnerChangeObserver`（`RecentsPreferences$2`）自动把该整数同步到 `/data/user_de/0/com.bbk.launcher2/shared_prefs/pref_recents.xml`。
3. **整条堆叠渲染链路兼容内屏**
   - `TaskLayoutState`：`LAYOUT_MODE_STACK = 128`，`setLayoutMode(128)` / `isLayoutStack()` 正常生效。
   - `TaskStackView#onLayoutModeChanged()`：在 `RecentsView#onFoldedStateChanged()` 折叠切换时检查 `RecentsPreferences#isStackMode()`（即 `getLayoutMode() == 128`），重建 `StackingTaskStackLayoutAlgorithm` 并改用 `R.layout.task_stack`（`StackedTaskView`）。
   - `StackingTaskLayoutCalculatorFactory` / `StackingTaskStackLayoutAlgorithm` / `RecentsAnimation`：均不依赖折叠态判断，竖屏（`0.683f`）与横屏（`0.60f`）在内屏上可完整计算合法布局与手势动画。

---

## 三、什么情况不会复位 / 什么情况会复位（全源码穷举）

### 不会复位
- **重启 / 关机开机 / 临时 root 或 LSPosed 失效**：配置落在标准设备保护存储 `/data/user_de/0/{com.vivo.recents,com.bbk.launcher2}/shared_prefs/pref_recents.xml`，重启后 `RecentsView` 构造函数直接读取 `128` 初始化为 `AlgorithmType.STACK`。
- **日常反复开合屏幕、横竖屏切换、桌面重启**。
- **常规应用覆盖更新 / 系统小版本 OTA**（前提是新版本未补上合法值过滤）。
- **在展开态（内屏）去「后台近期应用」设置页切换外屏样式**：`RecentsPreferences$3` 查 `queryLayoutMode()` 时展开态读到的仍是 `128`，不会冲掉内屏堆叠。

### 会复位（全源码共 5 处路径）

| 优先级 | 触发动作 | 源码位置与机制 |
|---|---|---|
| 1（最易误触） | **在内屏最近任务界面点左下角“横排 / 宫格”切换按钮** | `RecentsView#doChangeLayoutMode()` → `getNextLayoutMode(128)` 返回 `4` → `saveLayoutMode(4)` 将两侧 `recents_layout_mode_inner` 覆写为 `4`（且当下未重建 `TaskStackView` 算法实例，需再合盖展开复原） |
| 2（较隐蔽） | **在合盖（外屏）状态下，到「后台近期应用」设置页切换“堆叠 / 平铺”** | `RecentsPreferences$3#onChange()` 监听 `recents_view_mode` 变化时，调用一次 `queryLayoutMode()` 并**同时覆写外屏和内屏两个键**；合盖态查到的是外屏值（`0`），会把桌面侧 `recents_layout_mode_inner` 冲成 `0` |
| 3 | **用蓝心小 V / AppFunctions 语音指令改后台样式为“横向 / 宫格”** | `RecentsPrivacyFunctions$setRecentsLayoutMode$2` 调用 `saveLayoutMode(0 / 4)`（展开态下覆写 `recents_layout_mode_inner`） |
| 4 | **清除桌面或最近任务数据 / 互传或旧云备份恢复** | 清数据会删 `pref_recents.xml` 回到默认 `4`；互传 `BlurBackupRestoreDataChunk#restoreData()` 按布尔只还原 `0/4`；云备份 `QuickstepCloudDataManager#restoreData()` 恢复旧备份会覆盖回 `4`（新备份会原样按 int 保留 `128`） |
| 5 | **未来系统版本补上范围校验** | 若后续 `QuickstepPlugin` 在 `getPageOrGridLayoutMode()` 限制只允许返回 `0` 或 `4`，零 Hook 通道将失效 |

---

## 四、已知小瑕疵与备选方案 B（不 Hook `system`）

- **已知小瑕疵**：`RecentsBottomActionsController#resolveBottomAction()` 按折叠展开态写死显示 `mSeparateRecentsBottomActions`，因此内屏堆叠时底部左下角仍会多出一个“横排 / 宫格”切换图标（平时不要点它即可）。
- **备选方案 B（仅 Hook 桌面插件，不碰 `system_server`）**：
  - 若未来版本封堵了 `128` 漏口，或希望进一步隐藏内屏底栏多余的布局切换钮、修改设置页说明文案、调整内屏堆叠卡片缩放比例，可在 `com.bbk.launcher2` + `com.vivo.recents` 作用域下（配合 [VivoWorkbench](../../original-modules/vivo-workbench/README.md) 的 `PluginBridge` 获取插件真实 ClassLoader）Hook：
    - `RecentsPreferences#getLayoutMode()`（原型见 [OriginTweaks RecentsHooks.java](../../original-modules/origin-tweaks/src/dev/ganvivo/origintweaks/RecentsHooks.java)）
    - `RecentsBottomActionsController#resolveBottomAction(RecentsView)`（堆叠态改走 `mStackBottomActions`）
    - `StackingTaskLayoutCalculatorFactory$PortraitHorizontal` / `LandscapeHorizontal`（按需微调内屏缩放系数）
