# OriginTweaks 0.1.6

OriginOS 7 运行时模块（**libxposed API 102**，Vector / LSPosed 兼容）。
只做内存 hook；不写 `/system`、`/vendor`，不挂载，不主动重启整机。

> 本目录是从 `originos/OriginTweaks`（0.1.5）复制出来的**工作副本**。
> `originos/` 是只读参考树，不再往里写。
> 本次弯路复盘见 [`../origin-tweaks-REVIEW.md`](../origin-tweaks-REVIEW.md)；
> Vector/KernelSU/临时 root 的加载规则见
> [`../../root/VECTOR-KSU.md`](../../root/VECTOR-KSU.md)。

## 功能

1. **拖放直达全放行**（`com.vivo.globaldragdrop` 4.1.3.0）
   - `vb.p.h(pkg, activity)` → true
   - `bb.a.a(pkg, list)` 在 `yb.f` 调用且原值为 null 时返回占位非空值
2. **超级拖拽“系统分享”选项**
   - 在 `TransIslandIntentionBean.getMServiceInfo()` 的列表头部注入一个
     `IntentionService`，operation 为 `skill=shareText`
   - hook `ne.f.a`，对该 serviceId 用拖拽的 `ClipData` 拉
     `Intent.createChooser(ACTION_SEND / SEND_MULTIPLE)`
3. **超级拖拽设置页液态玻璃**
   - `SuperDraggingSettingActivity` onResume 后：`FLAG_BLUR_BEHIND` +
     `setBackgroundBlurRadius(80)` + 半透明底色
4. **原子工作台外屏解锁**（system_server + launcher + recents）
   - `VivoMultiTaskManagerService.isSupportVivoMultiTask()`：仅当折叠屏、
     `IS_FOLD` 且其他限制（Xspace/超级省电/儿童模式/简易桌面）都未触发时，
     把外屏的 false 改 true
   - `VivoTaskMenuView.showAtomicTask()`：外屏任务菜单原本因
     `!FoldedStateMonitor.isFoldableOpenState()` 直接返回 false；仅对该调用点
     把折叠状态视为“打开”，让“原子工作台”菜单项出现
   - `SysUINavigationMode.canTriggerMultiTaskGestureAction()`：外屏原本因
     `isPhone()` 为 false 而禁用手势区；仅在触摸命中多任务手势区时放行
5. **多分屏外屏槽位 2 → 4**（launcher）
   - `LauncherEnvironmentManagerMain.G()` / `a3.b.G()` 仅在调用方是
     `com.bbk.launcher2.launcherBasic.splitwindow.a` 时返回 true
   - 对应 `DesktopSplitSelectManager` 的 `isInnerScreen ? 4 : 2`，以及
     `SplitSelectControlView.O(true)` 的 4 槽位 UI
   - 分屏请求 `VivoMultiWindowRequest` 的 displayId 默认 0；折叠时外屏就是
     default display 0，请求/服务端不需要额外改 displayId
6. **内屏后台也支持“堆叠”样式**（com.vivo.recents / QuickstepPlugin）
   - `RecentsPreferences.getLayoutMode()` 在内屏原本强制走 page/grid；
     当用户在“应用 → 后台近期应用 → 后台样式”选择“堆叠”
     （Secure `recents_view_mode == 0`）时，内屏也返回 128（堆叠）
   - 设置页说明文字改为手机版描述（不再显示“内屏仅支持平铺”）
7. **超级拖拽设置页列出全部已安装应用**
   - 设置页原本只读厂商 JSON 白名单（`EnhancedDragWhiteListV2` /
     `EnhancedDragFeatureListV2`）
   - 仅在 `jh.z`（`SuperDraggingSettingActivity` 的列表加载器）调用
     `bb.a.c()` 时，把启动器可见的全部已安装 App 合并进返回列表；
     不改缓存，不影响运行时拖放判断
   - 新加入的 App 开关默认开（`y0.b()` 里没有记录即 true）；之前被手动
     关掉的 App 仍保持关闭

## 打包（重要）

当前是 **modern libxposed**，APK 必须包含：

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

不要放 `assets/xposed_init`。不是因为它会导致 `NONE`（Vector 的加载器
先看 `targetApiVersion`，`>=101` 就是 MODERN，两个入口都在也仍然走
MODERN），而是因为 modern 根本不会读它，留着只会让维护者误判。

`staticScope=true` **只收窄 scope 列表**，不会自动勾选目标。安装后仍要：

1. 在 Vector 里启用模块；
2. 打开 scope，把 4 个目标打勾；
3. 软重启（或至少 force-stop 目标 App 后重开）。

## 安装/生效

- 构建：`VERSION_NAME=0.1.6 VERSION_CODE=6 ./build.sh`
- 产物：`dist/OriginTweaks-0.1.6.apk`
- 生效需要软重启（重启 zygote / system_server），**不要硬重启**。
- 拖放/桌面类改动也可通过 force-stop 对应应用观察，但建议统一软重启。

## 验证链（必须按顺序看日志）

```
VectorModuleManager: Loading module dev.ganvivo.origintweaks
VectorModuleManager: Loaded module dev.ganvivo.origintweaks successfully
  → 目标进程 logcat: OriginTweaks: libxposed module loaded process=...
  → 目标进程 logcat: OriginTweaks: libxposed onPackageLoaded pkg=...
    → 具体 hook 日志（drag gate pass / settings app list expanded / ...）
      → 功能实际生效
```

只看到第一条 = 模块被识别；只看到第三条 = 被注入；最后一条才是功能成功。

## 构建校验

- `apksigner verify`：v3 通过（v1/v2 未启用）
- 已确认 APK 内含 `classes.dex`、`META-INF/xposed/module.prop`、
  `java_init.list`、`scope.list`；不含 `assets/xposed_init`
- 详细静态审计见 [AUDIT.md](AUDIT.md)（0.1.0 的历史审计，已加修正说明）

## 已知限制 / 未验证

- 原子工作台外屏的 system_server 侧路径已经按默认显示区放行；实际卡片
  布局/动画由 WMShell 控制器完成，该控制器不在已拉取的 APK 里，**未真机验证**。
- 外屏 4 分屏在 1120×2528 上的实际几何；部分 App 可能因最小尺寸/不支持
  分屏被系统拒绝。
- 系统 chooser 在拖拽浮层场景下是否允许后台启动 Activity。
- 全部 App 列表在真机上的加载耗时与图标内存占用。
- 超级拖拽菜单项的图标克隆自当前列表第一项；列表为空时用系统 share 图标。
- 玻璃效果依赖系统允许 `BLUR_BEHIND`；无效时只会有半透明底色。
- 所有 hook 都做了类/方法缺失保护；目标 APK 升级后类名变化时会静默跳过并打
  `OriginTweaks` 日志。

## 待办（按优先级）

1. 先把 `system` 相关 hook 做成远程偏好开关，默认关；先只验证
   `com.vivo.globaldragdrop`。
2. 用 `_tmp_probe` 同款最小模块确认 Vector 能加载/注入，再查业务 hook。
3. 把 `fromYbF()` / `fromSplitWindow()` / `fromSettingsLoader()` 的栈帧判断
   换成 ThreadLocal 或 call-site hook（`RecentsHooks` 已有正确示范）。
4. 真机按 README 的验证链逐项记录，不要把静态审计当功能验收。
