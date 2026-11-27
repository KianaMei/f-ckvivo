# VivoWorkbench（原子工作台增强）

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

面向 **vivo X Fold6（PD2545D / OriginOS 7 / Android 17）** 的纯 **libxposed API 102** 模块（Vector / LSPosed 兼容）。

> 仅做内存 runtime hook；不写 `/system`、`/vendor`，不向设备落盘任何日志或临时文件，**不请求、不 hook `system`（`system_server`）作用域**。

## 定位与阶段规划

- **阶段一（当前 `0.1.3`，2026-11-26 外屏实测通过）**：零 `system_server` 风险，解锁折叠屏**外屏**原子工作台，直接复用 OriginOS 7 WMShell 原生内置的直板机竖屏工作台布局（`VivoMultiTaskHelper.isPhoneState()`）。外屏最近任务卡片下拉菜单已能出现「原子工作台」并正常进入。
- **阶段二（计划）**：通过 libxposed RemotePreferences 增加配置开关，并吸收 `WorkbenchTuner` 的合理布局调参思路（主窗缩放/偏移、侧栏缩放/隐藏、图标独立偏移等，同时适配左右/上下/外屏模式）。

## 作用域与打包契约

| 字段 | 值 |
|---|---|
| 包名 | `dev.ganvivo.vivoworkbench` |
| 模块格式 | 纯 modern libxposed API 102（`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`，**无 `assets/xposed_init`**） |
| 入口 | `dev.ganvivo.vivoworkbench.LibXposedEntry`（`META-INF/xposed/java_init.list`） |
| 作用域 | `com.android.systemui`、`com.vivo.recents`、`com.bbk.launcher2`（**不含 `system`**） |
| 签名 | `shared/signing/vivoworkbench.keystore`（alias `vivoworkbench`） |

> 说明：勾选 `com.bbk.launcher2` 仅因为系统将 `/system/app/QuickstepPlugin/QuickstepPlugin.apk`（`com.vivo.recents`）同时动态加载进了桌面进程；本模块不会 hook 桌面自身的任何类。

## 已解锁的外屏入口（均不触碰 `system_server`）

1. **后台最近任务卡片菜单 →「原子工作台」**（`com.vivo.recents` / `QuickstepPlugin`）
2. **小窗顶部小白条菜单 →「原子工作台」**（`com.android.systemui` / WMShell）
3. **分屏顶部小白条菜单 →「原子工作台」**（`com.android.systemui` / WMShell）
4. **桌面原子工作台快捷组合图标**（`com.android.systemui` / WMShell）

> 不启用外屏右下角上滑手势：该手势需经 `system_server` 的 `VivoMultiTaskGestureAnimServerController.initialize()`；保持不 hook `system` 时，外屏右下角上滑只会按原厂逻辑静默不触发，无崩溃或异常风险。

## Hook 锚点清单（基于本机 `wm-shell-device.jar` 与 `QuickstepPlugin-device.apk` 实证）

| # | 进程 / 目标包 | 类与精确签名 | 作用与安全边界 |
|---|---|---|---|
| 1 | `com.android.systemui` | `VivoMultiTaskProxy#isSupportVivoMultiTask()` | 仅当原值 `false`、折叠屏、当前处于外屏且非 Xspace/超级省电/儿童模式/简易桌面时返回 `true` |
| 2 | `com.android.systemui` | `MinimizeThumbnailContainerView#isSupportVivoMultiTask()` | 同上，放行小窗缩略图入口 |
| 3 | `com.android.systemui` | `VivoMultiTaskController#moveTasksToVivoMultiTask(Bundle, ArrayList)` + `VivoMultiTaskCoordinator#getFoldState()` | 仅在同步 `ThreadLocal` 深度作用域内放行桌面快捷组合的外屏拦截，不全局篡改折叠态 |
| 4 | `com.android.systemui` | `VivoMultiTaskLocationManager#initResource()` | 保留 `sIsFold=true` 让 `isPhoneState()` 生效加载 `Phone*` 资源；修正原厂只取高宽比 `<2.0` 内屏写入 `mDisplayWidth/Height` 的逻辑，外屏态纠偏为外屏物理尺寸 |
| 5 | `com.android.systemui` | `VivoMultiTaskCoordinator$DeviceStateListener#onDeviceStateChanged(DeviceState)` | 折叠/展开切换后补刷 `LocationManager.initResource()`、外屏 bounds、`mIsSupportTopBottomUI` 与 `updateMaxAvailableIfNeeded(true)`（保留工作台活跃时动态合盖的原厂退出保护） |
| 6 | `com.android.systemui` | `VivoMultiTaskCoordinator#onDisplayConfigurationChanged(int, Configuration)` | 外屏态不再跳过 `UiModel.onDisplayChange(config, prevRotation)`，并同步 bounds |
| 7 | `com.android.systemui` | `VivoMultiTaskCoordinator#moveTasksToVivoMultiTask`（7 参主入口） | 进入前幂等兜底校验 `mVerticalPhoneItemWidthBasisPort > 0` 与外屏 bounds 已就绪 |
| 8 | `com.android.systemui` | `VivoMultiTaskUiUtil#getCornerRadiusMainTask()` | 外屏态动态返回 `dp2px(18f)`（避免内屏启动后静态字段锁定 74px） |
| 9 | `com.vivo.recents`（插件 ClassLoader 内） | `VivoTaskMenuView#showAtomicTask(Task)` + `FoldedStateMonitor#isFoldableOpenState()` | 标记同步 `ThreadLocal` 作用域、放行折叠态判断；若原厂仍返回 `false`，则在本地复核折叠态/受限模式/ROM 能力/原厂黑名单后**强制保留菜单项** |
| 10 | `com.vivo.recents`（插件 ClassLoader 内） | `SystemUiProxy#isPkgSupportEnterMultitask(Object[])` | 仅在 `showAtomicTask` 作用域 + 真实折叠态下，本地复核 `VivoMultiTaskConstants` 原厂黑名单与系统受限模式后放行 |
| 11 | `com.bbk.launcher2` | `PluginBridge`：`PluginActionManager#onPluginConnected(PluginInstance)` + `PluginInstance#loadPlugin()` + 后台 ClassLoader 扫描 | **见下方「ClassLoader 陷阱」**：Vector 在该进程给出的 `com.vivo.recents` 回调是空壳加载器，必须绕到真正的插件 ClassLoader 才能装上前两行 Hook |

## ClassLoader 陷阱（0.1.1 之前的「装了等于没装」根因）

`com.bbk.launcher2` 进程里，Vector 回调 `onPackageReady(pkg=com.vivo.recents)` 时给到的 ClassLoader 是 **`PathClassLoader[DexPathList[[], ...]]` 这种空壳**（一个 dex 都没有），`Class.forName("com.vivo.recents.views.VivoTaskMenuView", false, cl)` 必然失败，于是三个 Recents Hook 全部静默装不上——表现就是「模块明明加载了、外屏还是没有原子工作台菜单项」。

`VivoTaskMenuView` 只存在于 `QuickstepPlugin.apk`（包名 `com.vivo.recents`），不在 launcher2 的 `base.apk` 里；launcher 通过 `createPackageContext` 拿到它的真正 ClassLoader。`PluginBridge` 的做法：

1. 先按宿主 ClassLoader 试一次；
2. 挂 `PluginActionManager#onPluginConnected(PluginInstance)` 与 `PluginInstance#loadPlugin()`，插件连上后从 `getPlugin()` / `getPluginContext()` 取 ClassLoader；
3. 再用后台线程（1s 间隔、最多 120 次）扫描 `ActivityThread#mPackages` / `mResourcePackages` 里所有 `LoadedApk#getClassLoader()`，谁能 `Class.forName` 到 `VivoTaskMenuView` 就用谁装 Hook。

> 排查口径：`VivoWorkbench` 日志里出现 `Recents: VivoTaskMenuView not visible from this ClassLoader` 就说明踩了这个坑；正常时应该是 `PluginBridge: plugin ClassLoader ready ...` 紧跟 `Recents: hooked VivoTaskMenuView.showAtomicTask`。

## 构建

```sh
cd original-modules/vivo-workbench
sh build.sh
# 产物: original-modules/vivo-workbench/dist/VivoWorkbench-0.1.3.apk
```

## 真机验证链（需用户解锁亮屏在场操作）

> 遵循根 `AGENTS.md` §1 红线：安装 APK 必须亮屏解锁、用户在场、仅用 `adb install`；不主动触发软重启或 kill 系统进程。

1. 安装 `VivoWorkbench-0.1.3.apk`；
2. 在 Vector 中启用模块，并勾选 `com.android.systemui`、`com.vivo.recents`、`com.bbk.launcher2`（**不勾 `system`**）；
> **0.1.0 装机实测（2026-11-26 14:38~15:05）的「完全没有入口」已定位为 ClassLoader 问题（见下方「ClassLoader 陷阱」），不是 Hook 锚点缺陷。**

3. 待目标进程重启生效后，通过只读 logcat 按顺序核验：
   ```sh
   adb logcat -d -s VivoWorkbench
   ```
   期望看到：
   - `VivoWorkbench 0.1.3 loaded process=com.android.systemui ...`
   - `SystemUI workbench hooks installed count=9`
   - `Recents: hooked VivoTaskMenuView.showAtomicTask`（在 launcher 进程，需靠 `PluginBridge` 拿到插件 ClassLoader）
   - `Recents workbench hooks installed count=3`
4. 合盖在外屏验证：
   - 打开任意支持 App → 上划进后台最近任务 → 点卡片菜单确认出现「原子工作台」并可正常进入；
   - 将 App 切为小窗或分屏 → 点顶部小白条菜单确认出现「原子工作台」并可正常进入；
   - 核验外屏原子工作台直板机竖屏排版、切换主窗、关闭退出是否正常，以及展开回内屏后原厂内屏工作台无回归。

## 排障判据：先证明「模块被加载过」，再怀疑 Hook

`dev.ganvivo.vivoworkbench` 的 `onModuleLoaded` 必然输出一行 `VivoWorkbench <ver> loaded process=...`（不依赖任何 hook、不依赖 OriginOS 判定）。**这一行不存在 = 模块根本没被加载**，此时一切 Hook 结论都不成立，不要再对着源码猜。

> **2026-11-26 更正（重要）**：曾一度把「外屏无入口」归因为「Vector 守护进程半僵死、所有模块都没加载」，该结论**已被证伪**。同一天 15:10～15:40 的实测里，本模块在 launcher 进程正常输出 `onPackageReady`、LiquidTab 正常输出 `HOOK-ENTRY pkg=com.tencent.mm`，说明守护进程一直在正常下发模块。
>
> 但是 `/data/adb/lspd/log/modules_*.log`（本代）会停在 `----part 1 start----` 不再增长，与真实加载状态**不一致**——该日志文件在本机这份 Vector 上不可作为判据。请改用模块自己的 logcat：
>
> ```sh
> adb exec-out su -c 'logcat -d -v time | grep -a VivoWorkbench'
> ```
> 注意：普通 `adb logcat` 对本机 launcher 进程的日志**不可见**（`com.bbk.launcher2` 的 pid 在普通 logcat 里 0 命中），必须用 `su -c logcat` 才能读到。

Vector 侧同一份证据在两个位置（都只读）：

```sh
# 1) 本代守护进程的模块日志：正常时应出现每个进程的 Loading module / Loaded module ... successfully
adb exec-out su -c 'ls -lt /data/adb/lspd/log/'            # 注意 log.old/ 是上一代，内容不同
adb exec-out su -c 'cat /data/adb/lspd/log/modules_*.log'
# 2) 守护进程 verbose 日志（仅供参考；本机这份 Vector 的日志写入不可靠，不要单独当作“没加载”的证据）
adb exec-out su -c 'tail -c 800 /data/adb/lspd/log/verbose_*.log'
```

> 2026-11-26 实测：本代 `modules_2026-11-26T14:41:27.*.log` 只有一行 `----part 1 start----`，而同一时段模块 logcat 里的 `onPackageReady` 却在正常输出——**说明该日志文件只是写不进去，不代表模块没加载**。不要用它做判据。

附：0.1.0 装机时已核实 `dist/VivoWorkbench-0.1.0.apk` 与设备上 `/data/app/.../dev.ganvivo.vivoworkbench-*/base.apk` sha256 完全一致（`4fccb0bd…dfed73b`），排除装错包。

## 2026-11-26 外屏实测结果

0.1.3 在真机外屏（`DeviceState=CLOSED`，外屏 `1120×2528`）验证通过：

```text
15:39:44.602 I/VivoWorkbench(11461): Recents: showAtomicTask fold gate -> true (outer) (#1)
15:39:44.605 I/VivoWorkbench(11461): Recents: isPkgSupportEnterMultitask -> true (outer) pkg=com.tencent.mobileqq (#1)
15:39:44.606 I/VivoWorkbench(11461): Recents: showAtomicTask -> true (host allowed) (#1)
15:39:46.226 I/VivoWorkbench(14318): SystemUI: preEnter outer ready needReinit=false bounds=Rect(0, 0 - 1120, 2528) (#2)
```

即：外屏最近任务卡片下拉菜单出现「原子工作台」，点击后 SystemUI 按外屏 bounds 正常进入，工作台内排版可用。

同时记下两条 vivo 自身的坑（都不要踩）：

- `adb shell wm shell vivoMultiTask`（不带子命令）会直接让 **SystemUI 崩**：`VivoMultiTaskShellCommandHandler.onShellCommand:34 → ArrayIndexOutOfBoundsException: length=0`。要调这条调试通道必须带子命令（`moveTasksToVivoMultiTask <id[,id]>` / `enterVivoMultiTask` / `exitFromVivoMultiTask`）。
- 官方 UI 在外屏**不存在**原子工作台入口：`VivoMultiTaskManagerService.isSupportVivoMultiTask()` 写死 `IS_FOLD && uniqueId=="local:secondary" → false`，无任何用户开关；桌面「组合图标」路径还会弹 `string_vivo_multitask_not_support_for_outside_screen`。所以只能靠本模块放行。

