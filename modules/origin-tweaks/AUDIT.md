# OriginTweaks 0.1.0 静态崩溃审计

> **2026-11-16 修正**：本文是 0.1.0 的历史静态审计，不是 0.1.6 的验收报告。
> 文中“classic API 93 / `assets/xposed_init` / `xposedminversion=93`”的描述
> 已过时：0.1.6 是 libxposed API 102 + `META-INF/xposed` 打包。
> 模块加载规则、本次弯路复盘和真机验证链见：
> [`../origin-tweaks-REVIEW.md`](../origin-tweaks-REVIEW.md)、
> [`../../root/VECTOR-KSU.md`](../../root/VECTOR-KSU.md)、
> 本目录 [`README.md`](README.md)。

审计日期：2026-11-15  
审计对象：`dist/OriginTweaks-0.1.0.apk`  
SHA-256：`05028d425ef111fa63b96f0990b47d5e3d20c1dce3899490f5b2119eb86e2527`

> 本审计是**静态审计**：没有把 APK 安装到手机，也没有在真机上触发 hook。
> 下面把“已经静态确认的”与“只能在真机上验证的”分开写。

## 1. 结论摘要

- **只装 APK 不会自己崩溃**：模块没有 Service、Receiver、BOOT_COMPLETED、
  定时器、`Runtime.exec`、`System.exit`、`reboot`、分区/挂载操作；不安装前
  不会执行任何 hook 代码。
- **hook 安装失败不会拖垮目标进程**：每个 `Class.forName` /
  `findAndHookMethod` / `hookAllMethods` 都在 try/catch 内；目标类或方法不
  存在时只打 `OriginTweaks` 日志并跳过。
- **每个 hook 回调都有 try/catch**：回调内的反射、空值、类型转换异常不会
  逃逸到目标进程。
- **外屏工作台的 system_server 侧不是“只能内屏”**：`isSupportVivoMultiTask()`
  是唯一显式的显示闸门；折叠时外屏就是 `default display 0`
  （`VivoDisplayContentImpl.isDefaultDisplaySecondScreen()`），工作台核心代码
  全部用 `getDefaultTaskDisplayArea()` / `displayId 0`，没有发现内屏专用假设。
  真正的入口闸门还有两个：recents 任务菜单的 `showAtomicTask()` 和外屏手势区，
  本模块已分别做了窄范围放行。
- **残余风险主要在 WMShell / 布局层**：`mRootTaskController` 的实现不在本次
  拉取的 APK 集合里（系统 UI 的 wm-shell 共享库），因此“工作台卡片在外屏的
  实际布局/动画”和“外屏 4 分屏的实际几何”只能真机验证；代码请求侧没有看到
  2 槽位或内屏专用硬编码。
- 所有改动都是运行时 hook，**没有写 /system、/vendor，没有改分区**。

## 2. Hook 清单与失败模式

| # | 目标（APK/进程） | 目标符号 | 动作 | 失败/异常时的行为 |
|---|---|---|---|---|
| 1 | globaldragdrop | `vb.p.h(String,String):boolean` | 合法的非空 pkg/activity 直接返回 true | 参数为空/`NA` 时交回原逻辑；异常只记日志 |
| 2 | globaldragdrop | `bb.a.a(String,String):Object` | 仅 `yb.f` 调用、原值 null、白名单列表名匹配时返回 `Boolean.TRUE` | 调用方只做 null 判断，不会把它当配置对象强转；异常只记日志 |
| 3 | globaldragdrop | `bb.a.c():List` | 仅 `jh.z`（设置页加载器）调用时，合并启动器可见的全部已安装 App | 返回新 `ArrayList`，不修改原缓存；查询失败保留原厂列表 |
| 4 | globaldragdrop | `kb.i1.b(Context,String):boolean` | 仅 `jh.z` 调用时返回 true，取消设置页的 3 个包过滤 | 失败则仍按原厂过滤，不崩 |
| 5 | globaldragdrop | `TransIslandIntentionBean.getMServiceInfo():List` | 注入“系统分享” `IntentionService`，返回新列表而不是原地 add | 构造失败则不注入，原菜单不变 |
| 6 | globaldragdrop | `ne.f.a(...)` | 识别 marker 后拉起系统 chooser；**无论成功失败都吞掉原 skill** | chooser 拉不起来就静默跳过，不会执行假 target skill |
| 7 | globaldragdrop | `ge.a.b(...)` | marker 项的分享能力检查强制 true | 失败则菜单可能不显示该项，不崩 |
| 8 | globaldragdrop | `he.n.q(config)` | marker 项跳过“包是否安装”过滤 | 失败则菜单可能不显示该项，不崩 |
| 9 | globaldragdrop | `Instrumentation.callActivityOnResume(Activity)` | 仅 `SuperDraggingSettingActivity` 应用模糊/半透明 | 窗口 API 异常全部 catch，最多没有玻璃效果 |
| 10 | system_server | `VivoMultiTaskManagerService.isSupportVivoMultiTask():boolean` | 仅在“明确 false + IS_FOLD + 功能开关开 + Xspace/超级省电/儿童模式/简易桌面均未触发”时改成 true | 任一步读不到/抛异常就放弃，不猜测 |
| 11 | launcher | `LauncherEnvironmentManagerMain.G()` / `a3.b.G():boolean` | 仅在明确 false 且调用方是 `splitwindow.a` 时改成 true | 读不到/抛异常就放弃；只影响分屏选择 UI |
| 12 | recents / launcher | `RecentsPreferences.getLayoutMode():int` | 内屏 + 用户选择“堆叠”(`recents_view_mode==0`) 时返回 128 | 任一字段/方法缺失就放弃，保留 page/grid |
| 13 | recents / launcher | `RecentsLayoutModeViewHolder.<init>` | 把设置页描述文字换成手机版资源 | 字段/资源缺失就放弃 |
| 14 | recents / launcher | `FoldedStateMonitor.isFoldableOpenState():boolean` | 仅当调用栈在 `VivoTaskMenuView.showAtomicTask` 时，把外屏 false 改成 true | 栈不匹配就不改；其他 recents 布局仍用真实折叠状态 |
| 15 | launcher | `SysUINavigationMode.canTriggerMultiTaskGestureAction(MotionEvent):boolean` | 仅当折叠屏、外屏、触摸命中多任务手势区且手势开关打开时放行 | 字段/区域/折叠状态任一不满足就放弃 |

## 3. 逐项风险分析

### 3.1 globaldragdrop（应用进程，低风险）

- 拖放闸门：
  - 已给 `vb.p.h` 加空值/空串/`NA` 保护；不会把 null package/activity 放行到
    后续拖放代码。
  - `bb.a.a` 的占位返回值只被 `yb.f` 做 `== null` 判断；已核对
    `yb/f.java:239`，没有强转，安全。
- 系统分享：
  - 构造参数与目标类构造器逐一对过：
    `IntentionServiceBaseConfig` 13 参、`IntentionServiceInfo` 4 参、
    `AIEAppInfo` 无参 + setter 均存在。
  - 注入改为 `param.setResult(new ArrayList(list))`，不再原地修改 Bean 的
    列表，避免菜单在另一线程读取时 `ConcurrentModificationException`。
  - marker 被识别后**总是** `setResult(null)`：即使 chooser 因后台启动限制
    拉不起来，也不会回落到“package=com.android.intentresolver 的假 skill”。
  - `ge.a.b` 和 `he.n.q` 只对 marker 项生效；其他菜单项走原逻辑。
- 设置页全部 App：
  - 只在调用栈含 `jh.z` 时扩张 `bb.a.c()`；运行期拖放判断、AppFunctions、
    可用性探测（其他调用方）仍拿原厂数据。
  - 不修改 `ConcurrentHashMap` 缓存；返回新列表。
  - 启动器列表查询与包信息读取都在 try/catch 内；单个包失败只跳过该项。
  - 新加入项的开关默认开（`y0.b()` 不含该包）；之前手动关掉的包仍保持关。
- 玻璃效果：
  - 只作用于 `SuperDraggingSettingActivity`；`setBackgroundBlurRadius` 只在
    API 31+ 调用；所有窗口操作 catch。

### 3.2 launcher（中风险）

- 多分屏只改了 `splitwindow.a` 调用链里的 `isInnerScreen` 判断，其他 launcher
  布局仍看到真实折叠状态。
- 外屏允许选到 4 个 App 后，最终会调用
  `performVivoMultiWindowPolicy`；系统侧是否接受外屏 3/4 分屏、是否会抛
  binder 异常，静态无法证明。若系统拒绝，表现可能是分屏启动失败或桌面
  捕获异常，不涉及系统分区。
- 已把判断从“非 true 就改”收紧为“明确 false 才改”，避免 null/未知状态被
  误判为外屏。

### 3.3 recents（中风险）

- 128（堆叠）本身就是外屏在用的受支持布局；内屏强制返回 128 不会进入
  未实现的布局分支。
- 只有用户确实在设置里选了“堆叠”(Secure `recents_view_mode==0`) 时才改；
  选“平铺/网格”时保持原逻辑。
- `RecentsLayoutModeViewHolder` 的字段名 `mDescriptionTextView` 和字符串
  资源 `vivo_recent_view_mode_phone_description` 已确认存在；缺失时只跳过。

### 3.4 system_server / recents / launcher：工作台外屏可行性

结论：**system_server 侧没有发现“外屏不能用”的第二个硬编码闸门**；外屏
无法进入工作台主要是三处判断叠加，本模块分别处理：

1. `VivoMultiTaskManagerService.isSupportVivoMultiTask()`（system_server）
   - 原逻辑：折叠设备且默认显示唯一 ID 为 `local:secondary` 时返回 false。
   - 关键事实：`VivoDisplayContentImpl.isDefaultDisplaySecondScreen()` 的实现是
     `FOLDABLE && mDisplayContent.isDefaultDisplay && uniqueId=="local:secondary"`，
     而 `DisplayContent.isDefaultDisplay` 等价于 `mDisplayId == 0`。
     **折叠时外屏就是 default display 0**，所以 `displayId` 硬编码 0 的路径
     （分屏请求、`interceptNotificationStartingForMultiWindow`、`createVMWRootTasks`）
     指的就是外屏，不是内屏。
   - `enterVivoMultiTask()` 的 smali 中没有 `isSupportVivoMultiTask`、
     `local:secondary`、`getFoldState` 等二次判断；它使用
     `getDefaultTaskDisplayArea()` 取当前默认显示区，并在
     `getFreeformTaskIdsForVivoMultiTask()`、`getTopFullScreenTask()` 等辅助
     方法里继续使用默认显示区。外屏作为默认显示区时，这些路径成立。
   - `VivoFreeformTaskController` 有显式的 `mIsFoldSecondary` 处理
     （`local:secondary` 时记录 0 号显示、切回内屏时重置边界），
     `VivoFreeformWindowManager` 也有多处 `"local:secondary"` 分支；
     说明自由窗口/工作台相关代码本来就为外屏写了适配，不是只支持内屏。
   - 保留的闸门：`SUPPORT_VIVO_MULTI_TASK`、`IS_FOLD`、Xspace、超级省电、
     儿童模式、简易桌面；简易模式检测抛异常时选择不覆盖。

2. `VivoTaskMenuView.showAtomicTask()`（recents）
   - 原逻辑：`if (VersionUtils.isFold() && !FoldedStateMonitor.isFoldableOpenState()) return false;`
     即外屏任务菜单直接不显示“原子工作台”。
   - 本模块只对 `showAtomicTask` 调用栈把 `isFoldableOpenState()` 视为 true；
     其他 recents 布局/动画仍用真实折叠状态。
   - 菜单项后续还会调用 `SystemUiProxy.isPkgSupportEnterMultitask()`；
     该系统方法已被第 10 条 hook 放行。

3. `SysUINavigationMode.canTriggerMultiTaskGestureAction()`（launcher）
   - 原逻辑：`(isFoldableOpenState || isPad || (isPhone && isSupportVivoMultiTask))
     && gestureEnabled && isInMultiTaskGestureArea`。
     折叠屏设备 `isPhone()` 为 false，外屏 `isFoldableOpenState()` 为 false，
     因此外屏手势区被禁用。
   - 本模块只在“折叠设备 + 非打开态 + 触摸命中多任务手势区 + 手势开关开”
     时返回 true；内屏/直板机走原逻辑。

残余未知：`VivoMultiWindowManagerService.mRootTaskController` 是向 WMShell
控制器下发的 binder，控制器实现（工作台卡片布局、动画、任务编排）不在本次
拉取的 APK 集合里。system_server 这一侧没有发现内屏专用分支，但 WMShell
是否在外屏按 1120×2528 正常排版，只能真机验证。真机若出现 system_server
异常，停用 `system` 作用域即可先去掉第 10 条；recents/launcher 的入口 hook
是独立的，不会一起消失。

### 3.5 外屏 4 分屏可行性

代码链路上的结论：**请求侧和服务端没有固定 2 槽位上限；外屏限制只在
launcher 的 `isInnerScreen ? 4 : 2`。** 具体证据：

- `DesktopSplitSelectManager.c()`：`maxSlots = isInnerScreen ? 4 : 2`
  （`splitwindow/a.java:807`），本模块 hook 后在 `splitwindow.a` 调用链里
  返回 4。
- `SplitSelectControlView.O(boolean)`：`f9735g = z10 ? 4 : 2`
  （`SplitSelectControlView.java:611-614`），UI 槽位也变成 4。
- `DesktopSplitSelectManager.H()`：把 `mSelectedApps` 全部塞进
  `VivoMultiWindowRequest.setIntents(intentArr, iArr, null)`，没有按外屏截断；
  `VivoMultiWindowRequest.Builder.displayId` 默认 0
  （`VivoMultiWindowRequest.java:429`），折叠时外屏就是 display 0。
- `VivoMultiWindowManagerService.performVivoMultiWindowPolicy(0)` 只把请求
  交给 `mRootTaskController.execute(0, bundle)`，服务端没有 2/4 的显示判断。
- 服务端 `createVMWRootTasks(displayId, ...)` 使用传入显示的
  `getDefaultTaskDisplayArea()`；缓存创建逻辑甚至按 5 个 stage 准备
  （`5 - remainingCacheStage.size()`），`ADVANCED_MULTI_WINDOW_MAX_NUM`
  在设备特性里是 4（`VivoMwUtils`），不是 2。
- `calculateLayoutPositionOnNewStageCreated()` 的 `isFoldableExtended()`
  （仅内屏）分支只影响“新增 App 时的布局位置/上下分屏方向”，不是槽位上限；
  外屏走返回 0 的通用路径。

因此**外屏 4 分屏不会因为“数量上限”被服务端拒绝**；真正的风险是 1120×2528
窄屏上 4 个窗口的几何：每个窗口约 560×1264（2×2）或更窄，部分 App 的
`supportsSplitScreenMultiWindowInDisplayArea`/最小宽高可能不满足，系统可能
拒绝该 App 或让布局很挤。这是兼容性/显示效果问题，不是数组越界或
system_server 崩溃。WMShell 的最终排版同样不在本次拉取代码里。

## 4. 安装面检查

- 包名 `dev.ganvivo.origintweaks` 唯一；没有已安装的同包名冲突记录。
- `minSdk 30` / `targetSdk 35`；Android 17 可安装。
- `MainActivity` 已声明 `android:exported="true"`，带 MAIN/LAUNCHER，安装期
  不会因 Android 12+ 的 exported 规则失败。
- `assets/xposed_init` = `dev.ganvivo.origintweaks.HookEntry`。
- `xposedmodule=true`、`xposedminversion=93`、`xposedscope` 数组存在。
- `apksigner verify` 通过 v3；未启用 v1/v2，Android 17 的包管理器接受 v3。
- 没有申请任何权限；hook 运行在目标 App 的 UID 里，使用目标 App 的
  `QUERY_ALL_PACKAGES` 可见性（已确认 globaldragdrop 声明了该权限）。

## 5. 未验证项（必须真机才能确认）

1. Vector 是否按经典 Xposed API 93 正常加载本模块（可看 `OriginTweaks` 日志）。
2. WMShell 的 `mRootTaskController` 在外屏如何排版工作台卡片、动画是否正常
   （控制器实现不在已拉取 APK 里）。
3. 外屏 4 分屏在 1120×2528 上的实际几何；部分 App 可能因最小尺寸/不支持
   分屏被系统拒绝。
4. 系统 chooser 在拖拽浮层场景下是否允许后台启动 Activity。
5. 全部 App 列表在真机上的加载耗时与图标内存占用。

## 6. 真机验证建议（将来由你决定，不是现在执行）

- 只软重启，不硬重启。
- 先在 Vector 中只启用需要的 scope；若只验证拖放/设置页，可先不启用
  `system`/`com.bbk.launcher2`，把最高风险项隔离。
- 看日志：`adb logcat -s OriginTweaks`（或 Vector 日志里的 `OriginTweaks`）。
- 出现异常时优先在 Vector 停用对应 scope，再软重启；不要用硬重启“恢复”。

## 7. 关键代码证据

以下路径相对 `originos/device-apks/decompiled/`：

- `vs-classes/com_android_server_wm_VivoMultiTaskManagerService.java`
  - `:222-234` `isSupportVivoMultiTask()`：唯一显式的外屏 `local:secondary` 闸门
  - `:468-500` `getFreeformTaskIdsForVivoMultiTask()` 全程使用
    `getDefaultTaskDisplayArea()`
  - `:578-590` `getTopFullScreenTask()` 使用默认显示区
  - `:1661-1710` `performVivoMultiWindowPolicy(0)` 把请求原样交给
    `mRootTaskController.execute(0, bundle)`，没有按显示裁掉 stage
- `vs-classes/com_android_server_wm_VivoMultiWindowManagerService.java`
  - `:969` `stageCount == ADVANCED_MULTI_WINDOW_MAX_NUM`
  - `:3391-3490` `createVMWRootTasks(displayId, ...)` 使用传入显示的
    `getDefaultTaskDisplayArea()`；缓存逻辑允许 5 个 stage
  - `:3888` `getChildCount() == ADVANCED_MULTI_WINDOW_MAX_NUM`（4）
- `framework-classes/VivoMultiTaskConstants.java`
  - `:307` `VIVO_MULTI_TASK_MAX_NUM = 5`
  - `:315-323` `SUPPORT_VIVO_MULTI_TASK` / `IS_FOLD` / `IS_PHONE`
- `com.vivo.recents/sources/com/vivo/recents/views/VivoTaskMenuView.java`
  - `:1315-1350` `showAtomicTask()` 的
    `VersionUtils.isFold() && !FoldedStateMonitor.isFoldableOpenState()` 外屏拦截
- `com.bbk.launcher2/sources/com/android/quickstep/SysUINavigationMode.java`
  - `:774-776` `canTriggerMultiTaskGestureAction()` 的
    `isFoldableOpenState || isPad || (isPhone && support)` 组合
- `com.bbk.launcher2/sources/com/bbk/launcher2/launcherBasic/splitwindow/a.java`
  - `:393` `SplitSelectControlView.O(isInnerScreen)`
  - `:807` `isInnerScreen ? 4 : 2`
  - `:923-927` reason 34 的外屏直接返回（同样受 `G()` hook 影响）
- `com.bbk.launcher2/sources/com/bbk/launcher2/launcherBasic/splitwindow/SplitSelectControlView.java`
  - `:611-614` `f9735g = z10 ? 4 : 2`
- `mw-classes/com_vivo_smartmultiwindow_VivoMultiWindowRequest.java`
  - `:429` `Builder.displayId` 默认 0
  - `:532-544` `setIntents()` 按数组长度添加 StageOp，没有 2 个上限
- `vs-classes/com_android_server_wm_VivoDisplayContentImpl.java`
  - `:716-718` `isDefaultDisplaySecondScreen()` =
    `FOLDABLE && mDisplayContent.isDefaultDisplay && uniqueId=="local:secondary"`
- `vs-classes/com_android_server_wm_DisplayContent.java`
  - `:996-1003` `mDisplayId = display.getDisplayId();
    isDefaultDisplay = mDisplayId == 0`
- `vs-classes/com_android_server_wm_VivoFreeformTaskController.java`
  - `:97` / `:899-909` `mIsFoldSecondary` 与 `"local:secondary"` 的显示切换处理
  - `:2575-2584` 自由窗口最大数量按显示/桌面模式计算，不是固定内屏专用
- `vs-classes/com_android_server_wm_VivoFreeformWindowManager.java`
  - `:1614` / `:1983` 对 `"local:secondary"` 的显式分支
- `framework-classes/android_util_VivoMwUtils.java`
  - `:148-153` `ADVANCED_MULTI_WINDOW_MAX_NUM` 来自 feature attribute
    `maxWindowNum`，不是固定 2
