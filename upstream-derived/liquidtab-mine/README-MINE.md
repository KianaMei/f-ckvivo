# liquidtab-mine — LiquidTab 本机维护 fork

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

> **当前基线**为 [LiquidTab v2.1.2](https://github.com/big-dictator/LiquidTab/tree/03ee88b)（`03ee88b`，上游 versionCode 225）。2026-11-23 合入本地补丁，从 versionCode 280 起。构建版本见 [app/build.gradle.kts](app/build.gradle.kts)。
> 当前机上版本：**292 / `2.1.2-mine12`**（2026-11-27 21:02 同签名覆盖安装成功，机上 SHA-256 `d14e88f1…` 与产物一致，`system_server`/`lspd`/`zygote64` PID 不变，首页已确认 `607,2285-1704,2440 live=true`；彻底修复淘宝切 Tab 液态玻璃丢白与进入购物车时底栏宽度暴涨占满整屏两个根因，并同步修正内屏购物车结账双玻璃胶囊宽度比例，待全 Tab 切换真机验收，详见下节）。
> 上一机上版本：**291 / `2.1.2-mine11`**（2026-11-27 20:31 同签名覆盖安装成功，机上 SHA-256 `5f1b8339…` 与产物一致，`system_server`/`lspd`/`zygote64` PID 不变；修复折叠屏内屏下淘宝底栏图标与文字被按全宽原生槽宽 `2312/5=462.4px` 错误缩小至 `57.9%` 及未读消息 `99+` 角标向槽中心偏移遮挡图标的比例失调问题，含 290 X 底栏修复，真机内屏截图已验，详见下节）。
> 历史待装版本：**290 / `2.1.2-mine10`**（2026-11-27 构建并验签通过，SHA-256 `7C12DA81…3636E25E`，已合入 291 一并装机；修复 X `12.45.1` 进入帖子详情及一切非 Tab 页底栏不消失、折叠屏内屏大屏侧边栏模式下多余底部 Tab 且点击无用的问题，详见下节）。
> 上一机上版本：**289 / `2.1.2-mine9`**（2026-11-26 11:13 同签名覆盖安装成功，机上 SHA-256 `5412a315…` 与产物一致，`vectord` 单进程正常；参照 286 移除小红书开屏拦截的做法，彻底移除知乎无独立开关的 `ZhihuSplashAds` 与 `ZhihuApkContract`，消除与知了 `switch_launchad` 对 `LaunchAdInterface` / `com.zhihu.android.app.util.dk.isShowLaunchAd()` 的双 Hook 重叠与冷启 18-DEX 扫描开销；含 288 全部改动；待真机验收）。
> 上一机上版本：**288 / `2.1.2-mine8`**（2026-11-26 09:44 同签名覆盖安装成功，机上 SHA-256 `6537aa47…` 与产物一致，`vectord` 单进程正常；修复 YouTube `21.46.522` 底栏 `image_with_text_tab` 将 `id/image` 包入 `id/thumbnail_layout` 导致 `YouTubePivotBinding.resolve` 必返 `null`、悬浮玻璃底栏不显示的问题；含 287 全部改动）。
> 上一机上版本：**287 / `2.1.2-mine7`**（2026-11-25 22:56 同签名覆盖安装并经用户真机验收确认 ✅，机上 SHA-256 `b11634c3…` 与产物一致，`vectord` 单进程正常；修复折叠屏内屏小红书图文笔记详情 `NoteDetailActivity` 双栏布局顶栏未玻璃化的问题，详见下节）。
> 上一机上版本：**286 / `2.1.2-mine6`**（2026-11-25 17:18 同签名覆盖安装并已验收，机上 SHA-256 `b6ef0e04…` 与产物一致；彻底移除小红书无开关的 `XhsOriginalImageExport` / `XhsImageSaveContract` 原图导出与 `XhsSplashAdsHooks` 开屏广告拦截，消除与静盒的全部已激活与潜在撞车；同步复核国服小红书 `9.56.0 / 9560805` 兼容性，在资源名混淆下补充消息底栏 `FrameLayout` 角标容器兜底以同时兼容数字与纯红点角标；含 285 全部修复）。
> 历史版本：**285 / `2.1.2-mine5`**（2026-11-25，修复小红书详情返回首页底栏“首页”先黑后跳红 + 停窗误弹入动画 + 消息底栏未读数字/红点角标；详见下节）。
> 已装机并验收：**282 / `2.1.2-mine2`**（B 站/XHS/抖音已真机验收；B 站顶栏热词退化修复见 MERGE-PLAN §5）。
> 已装机并验收：**284 / `2.1.2-mine4`**（2026-11-24，贴吧 22.14.1.0 实测：切“进吧”时首页玻璃顶栏收起、
> 原生搜索胶囊与“一键签到”完整可点；小红书不再产生 trace 文件）。 —— 在 283（贴吧首页玻璃顶栏越界到“进吧”，见下节）之上
> **移除 279 引入的小红书返回 trace**（`XhsLifecycleTrace.kt`、5 处调用、`xhsReturnTrace` 构建开关与
> `XHS_RETURN_TRACE` 字段），以及 274 遗留的 `dskin`/`covertrace` 探针。APK 的 DEX 里已不含
> `liquidtab-xhs-return` / `XhsLifecycleTrace` / `covertrace` 任何字符串，正常构建不再往宿主目录写 trace 文件。
> 下文「我们改了什么（相对上游 v2.1.0）」是 224–279 补丁线的历史台账，保留作各版本根因档案。

> 上游：[big-dictator/LiquidTab](https://github.com/big-dictator/LiquidTab) v2.1.0（GPL-3.0，作者 bigdictator）。
> 本目录 = v2.1.0 源码 + 我们的补丁；LICENSE / NOTICE.md / THIRD_PARTY_ASSETS.md / README.md 均为上游原件。
> 上游 README 说明见 [README.md](README.md)。

## 设备实测来源（不保留原始日志）

所有设备结论均可只读复取，仓库不保存 dump 文件：

- Activity / 窗口 / View 层级：`adb shell dumpsys activity`、`adb shell dumpsys window windows`
- 控件树：`adb shell uiautomator dump /sdcard/window_dump.xml`
- 开合、返回、安装等事件：`adb logcat -b events`
- B 站频道条内部状态：`/sdcard/Android/data/tv.danmaku.bili/files/liquidtab-home-layout.txt`、`liquidtab-channels.txt`
- 屏幕截图：`adb exec-out screencap -p > shot.png`
- B 站 9.19.0 静态对照：`drag-all/analysis/bili-9.19.0`（本地 jadx 反编译产物，不入库）

## 我们改了什么（相对上游 v2.1.0）

### 修复淘宝切 Tab 液态玻璃丢白与进购物车底栏宽度暴涨（292 / `2.1.2-mine12`）◐ 已装机待验收

- **症状（用户真机内屏 `2312×2504` 实测并复现抓证）**：
  1. 切换淘宝不同 Tab 时底栏液态玻璃不稳定，频繁丢失折射变成纯白条；
  2. 进入购物车页时，底栏原本正常的居中宽度（`607..1704`，宽 `1097px`）突然跳变为占据整个内屏底部（`64..2248`，宽 `2184px`），且切回首页后无法恢复。
- **根因（真机 `dumpsys activity top` + v2.1.0/v2.1.2 源码对账实锤）**：
  1. **宽度跳变双因联动**：冷启安装时 `GlassInstaller.install()` 算 `visibleSlots = 5`（未扣除 `TaobaoAdapter.isNavigationEntryDisabled(1, 5)`），走 `glassHostWidthPx(5)` 得到限宽 `1097px`；进入购物车后 `TaobaoNavigationState.updateBackdropRendererMode()` 强行篡改 `host.config = base.copy(blurRadius = maxOf(2f, base.blurRadius))`，触发下次 `pollConfig()` 检测到 `blurRadius` (`2f` vs 用户配置 `0f`) 差异并调用 `applyLiveConfig()` → `updateHostLayoutForConfig()`；该函数以 `visualSlotCount() == 4 <= 4` 改走无上限的 `symmetricGlassHostWidthPx(4)`，在内屏 `2312px` 下直接算出 `2312 - 2×64 = 2184px`；
  2. **液态玻璃丢白三因叠加**：
     - 上游 v2.1.2 删除了 `GlassHostLayout` 内置 root-window `PixelCopy` 与淘宝 `TaobaoHomeSurfaceSampler`，却保留了 `TaobaoAdapter.usesOpticalSurfacePipeline = true` / `isOpticalSurfaceTab(0/2/4) = true` 及 `TaobaoNavigationState.skipLiveBackdrop` 禁用非购物车 Tab 的 `liveBackdrop`，导致独立光学 `SurfaceView` 从未创建（现场 `pipeline=false surfaceVisibility=null live=false`），非购物车 Tab 全被压到 CPU 软件截屏 `root.draw(softwareCanvas)`（遇到硬件位图或 `SurfaceView` 即变透明/纯白，切 Tab 动画期间停拍）；
     - 进出购物车时 `prepareLiveBackdropSceneContent()` 回收旧 `backdrop = null`，切出购物车时 `updateBackdropRendererMode()` 又 `discardDisplayList()` 清空 `liveBackdropNode`，双向清零瞬间 `hasUsableBackdrop() == false` 变纯白；
     - `TaobaoNavigationState` 残留的 `outerBackdropNeedsRecord` / `opticalBlurNodeNeedsRecord` / `combinedIndicatorSceneNeedsRecord` 缓存键只看 `backdrop` Bitmap 与 `route`，未感知 `liveBackdropActive` 切换，导致 `liveBackdrop` 就绪后仍复用空 DisplayList 卡死纯白；同时 `TaobaoCartRenderSurface.attach()` 首次遇到未附着引擎直接放弃不重试，使购物车可能停留在 `UnicornSurfaceView` 透洞。
- **修法**：
  1. **固定宽度与消除配置打架**：[TaobaoAdapter.kt](app/src/main/java/io/github/offlineglass/hook/adapters/taobao/TaobaoAdapter.kt) 覆写 `hostWidthSlotCount(...) = 5` 使冷启与后续配置更新均走 `glassHostWidthPx(5)` 锁定内屏 `1097px` 居中宽；[TaobaoNavigationState.kt](app/src/main/java/io/github/offlineglass/hook/adapters/taobao/TaobaoNavigationState.kt) 移除对 `host.config` 的偷偷改写；[TaobaoCartNativeButtons.kt](app/src/main/java/io/github/offlineglass/hook/adapters/taobao/TaobaoCartNativeButtons.kt) 改按 `min(glass.width, widthPixels)` 计算结算双玻璃胶囊宽度与点击区；
  2. **重建稳定硬件 GPU 液态玻璃管线**：关闭 `TaobaoAdapter` 已失效的 `usesOpticalSurfacePipeline` / `isOpticalSurfaceTab`，开启 `foregroundNavigationOverSelection`、`retainsExistingBackdropRenderNode`、`skipBackdropStableDelay`；[TaobaoNavigationState.kt](app/src/main/java/io/github/offlineglass/hook/adapters/taobao/TaobaoNavigationState.kt) 移除毒缓存覆写、禁掉 `allowsSoftwareBackdropCapture` 与 `retainHomeBackdrop` / `retainBackdropDuringTransition`，普通与 `TextureView` 页面全走 120Hz 硬件 GPU `liveBackdropNode`（通过 `backdropSceneViews` 排除 `AdapterOwnedOverlay`、`GlassHostLayout` 与原生 `tabhost`），并加固 `drawForeground` 防丢选中图标；
  3. **兼容 `SurfaceView` 子页与购物车转换重试**：新增 [TaobaoSurfaceBackdropSampler.kt](app/src/main/java/io/github/offlineglass/hook/adapters/taobao/TaobaoSurfaceBackdropSampler.kt) 通过独立后台线程 `PixelCopy` 实时采样覆盖底栏的 `SurfaceView`（如闪购外卖 `UnicornSurfaceView` 或购物车转 `TextureView` 前的过渡帧）并在有采样帧时自动置 `skipLiveBackdrop = true` 防白底遮盖；加固 [TaobaoCartRenderSurface.kt](app/src/main/java/io/github/offlineglass/hook/adapters/taobao/TaobaoCartRenderSurface.kt) 节流重试直至确认 `TextureView` 就绪。
- **验证**：Release R8 构建通过（`cache/scratch/LiquidTab-292-taobao-glass-width-fix.apk`，11,656,156 B，SHA-256 `D14E88F13D54145158969BCA5D0AFA4BCE184C158DF52325DEF53C72CFC8D3D2`，debug B 同签 `115ed98e…`，`zipalign -c -p 4` 与 `apksigner verify` 通过）。

### 修复折叠屏内屏淘宝底栏图标、文字与未读角标比例失调（291 / `2.1.2-mine11`）✅ 已装机真机验证

- **症状（用户真机内屏 `2312×2504` 实测）**：折叠屏展开状态下淘宝底栏（`GlassHostLayout` `1097×155px`，4 可见槽，单槽宽 `267.75px`）中的「首页 / 消息 / 购物车 / 我的淘宝」四个图标与文字严重偏小（图标实测仅 `42×42px` ≈ `12.9dp`，文字高仅 `15px` ≈ `4.6dp`，图标+文字总高仅 `65px` 占 `155px` 底栏不足一半），而「消息」右上角 `99+` 橙色角标既保持 `55px` 保底直径又往槽中心收缩，直接压住缩小后的消息气泡图标右半侧。
- **根因（真机 `dumpsys activity top` 层级 + `TaobaoNavigationState` / `TaobaoBadge` / `GlassHostLayout` 实证）**：
  1. 淘宝原生底栏 `TabWidget`（`0,0-2312,162`）在内屏铺满全屏宽度 `2312px`，5 个 `NavigationTabIndicatorView` 单槽宽 `nativeSlotWidth = 2312 / 5 = 462.4px`；但其内部图标容器 `TUrlImageView`（`156,12-306,162`）与文字/角标始终居中在 `150×150px`（`<= sourceHeight 162px`）的正方形内容盒内，左右两侧 `312px` 全为水平留白；
  2. `TaobaoNavigationState.draw()` 与 `GlassHostLayout.sourceTransformForSlot()` 原先用包含留白的 `nativeSlotWidth (462.4px)` 作为分母计算 `scale = min(slotWidth / nativeSlotWidth, height / sourceHeight) = min(267.75 / 462.4, 155 / 162) = 0.5790`（正常高度比应为 `155 / 162 = 0.9568`），再将 `min(nativeSlotWidth, sourceHeight) = 162px` 乘以该 `0.5790`，导致内屏图标与文字被额外缩小至 `60.5%`（`boxSize` 从 `135.6px` 跌至 `82.1px`）；
  3. `TaobaoBadge.update()` 通过 `host.adapterTabTransform(2)` 复用同一 `scale = 0.5790` 映射角标中心偏移，使角标向槽中心收缩，而角标半径由 `max(..., 8.5f * density)` 保底不缩小，且 `inset` 误除以全屏 `sourceScale (0.463)` 与后续 `- 3f * density / scale` 不对称。
- **修法**：
  1. 在 [TaobaoNavigationState.kt](app/src/main/java/io/github/offlineglass/hook/adapters/taobao/TaobaoNavigationState.kt) 与 [GlassHostLayout.kt](app/src/main/java/io/github/offlineglass/hook/GlassHostLayout.kt) 的 `sourceTransformForSlot()` 中，以原生槽的有效内容宽度 `nativeContentWidth = min(nativeSlotWidth, sourceHeight)` 替代含空白边距的 `nativeSlotWidth` 计算槽缩放比 `scale = min(slotWidth / nativeContentWidth, height / sourceHeight)`，并在 `TaobaoNavigationState.draw()` 中同步接入 `frame.iconScale * frame.extraScale`；
  2. 在 [TaobaoBadge.kt](app/src/main/java/io/github/offlineglass/hook/adapters/taobao/TaobaoBadge.kt) 将 `inset` 统一改为 `1.5f * density / scale`，与 `nativeWidth`/`nativeHeight` 的 `- 3f * density / scale` 对称抵消。
- **验证**：Release R8 构建通过（`cache/scratch/LiquidTab-291-taobao-inner-icon-scale.apk`，11,656,156 B，SHA-256 `5F1B833982134A25C9A35E847334AC4222892098C75AAE375F4EFA14C3FEB413`，debug B 同签 `115ed98e…`，`zipalign -c -p 4` 与 `apksigner verify` 通过）；内屏 `boxSize` 由 `82.1px (25.3dp)` 恢复至 `135.6px (41.7dp)`（图标 `71.2px / 21.9dp`、文字 `27.4px / 8.4dp`、未读角标定位在消息图标右上角）。

### 修复 X 12.45.1 非 Tab 页底栏残留与折叠屏内屏多余底栏（290 / `2.1.2-mine10`）⏳ 待授权装机

- **症状（用户真机实测）**：在 X `12.45.1-prod.01`（X Fold6 内屏 `2312×2504` 等场景）进入帖子详情以及任何非 Tab 控制的二级页面（个人主页、设置、书签、私信对话等），底部玻璃 Tab 栏未消失，莫名多出一行底部 Tab 且点击无用。
- **根因（`12.45.1` 字节码 + `XAdapter`/`XBottomBarHooks` 实证）**：
  1. X 为单 Activity Compose + Decompose 根栈架构，所有页面共享同一 `ComposeView`；原 `XAdapter` 仅用 5 项黑名单（`postdetail` / `MediaGallery` / `reply` / `ComposerRoot` / `drawer`）判断隐藏，未接管 `MainLanding` 组件（`com.x.main.k`）生命周期，导致其他所有二级页均被误判仍在主 Tab 页；
  2. `XBottomBarHooks` 覆写 `bottom` (`com.x.ui.common.e3#a`) 的 `hiddenIndex` 前未先读取原生传入的 `hideBottomBar` 值（来自 `com.x.main.l0#x` 等子页隐藏信号，如 DM 聊天室）；
  3. X Fold6 内屏 `2312×2504` 高大于宽（系统报竖屏但宽约 `770dp >= 600dp` 触发 `com.x.main.l0#j` 大屏侧边栏分支 `com.x.main.p`，原生不渲染 `e3#a` 底栏），而原 `replacesCurrentPage` 仅检查 `landing` (`l0#i`) 拿到 `tabClick` 即强行绘制底部玻璃栏。
- **修法**：
  1. 在 [XBottomBarHooks.kt](app/src/main/java/io/github/offlineglass/hook/adapters/x/XBottomBarHooks.kt) 的 `landing` Hook 中动态识别持有 `getLifecycle()` 的 `MainLanding` 组件实例，对其 `Lifecycle` 实现类（`com.arkivanov.essenty.lifecycle.i`）挂载未混淆的 `onCreate`/`onStart`/`onResume`/`onPause`/`onStop`/`onDestroy` 及状态切换 Hook，并在 `onHostFrame` 实时核对 `state == RESUMED`（零硬编码混淆名，离开主 Tab 进任何非 Tab 页立刻隐藏）；
  2. 在 `bottom` (`e3#a`) 覆写 `hiddenIndex` 前与 `landing` (`l0#i`) 中先读取原生 `hideBottomBar` 标志并同步至 `XAdapter`；
  3. `replacesCurrentPage` 新增 `bottomObserved` 闸门（仅在观测到原生 `e3#a` 实际渲染时激活），并在 `XNavigationShim.onConfigurationChanged` 检测到 `screenWidthDp` 变化（折叠开合）时自动重置重判；
  4. 在 [XAdapter.kt](app/src/main/java/io/github/offlineglass/hook/adapters/x/XAdapter.kt) 将 `!landingResumedActive || nativeBottomHiddenActive || !bottomBarModeActive` 统一接入 `GlassInstaller.setAdapterBlockingOverlayVisible` 链路，`allowsHostTouch()` 同步受 `!detailActive` 门控。
- **验证**：Release R8 构建通过（`cache/scratch/LiquidTab-290-x-landing-gate.apk`，11,656,156 B，SHA-256 `7C12DA8160B7B57DF33900072692A2D56B1ADA4F30E49E09249646543636E25E`，debug B 同签 `115ed98e…`，`zipalign -c -p 4` 与 `apksigner verify` 通过，DEX 符号核验完整）。

### 移除知乎与知了重叠的开屏广告 Hook（289 / `2.1.2-mine9`）◐ 已装机待验收

- **背景**：与机上知了（`com.shatyuka.zhiliao` `26.02.03`）逐方法对账知乎 `11.14.0 (41410)` 后检出：`ZhihuSplashAds.kt` 在 [TargetSpec.kt](app/src/main/java/io/github/offlineglass/hook/adapters/zhihu/TargetSpec.kt) 配置了 `processHooksAlwaysEnabled = true`，无开关拦截 `LaunchAdInterface` 实现类 `com.zhihu.android.app.util.dk.isShowLaunchAd()` 与 `LaunchApi.requestAd()`，与知了 `switch_launchad` 精确重叠，并在冷启阶段额外遍历全部 18 个 DEX 查找接口实现类。参照 286 移除小红书 `XhsSplashAdsHooks` 的原则，LiquidTab 仅保留玻璃导航栏与内容区适配职责。
- **改动**：
  1. 删除 `ZhihuSplashAds.kt` 与其唯一依赖 `ZhihuApkContract.kt`；
  2. 清理 [TargetSpec.kt](app/src/main/java/io/github/offlineglass/hook/adapters/zhihu/TargetSpec.kt)，移除 `processHookInstaller` 与 `processHooksAlwaysEnabled`。
- **验证**：Release 构建通过（`cache/scratch/LiquidTab-289-zhihu-clean.apk`，11,639,764 B，SHA-256 `5412A315D7B876C53A1D4B022C103EB51F263C9A95CCEA5E00F092FD662A68F0`，debug B 同签 `115ed98e…`，`zipalign -c -p 4` 通过）；DEX 核验 `ZhihuSplashAds` / `ZhihuApkContract` / `LaunchAdInterface` / `LaunchApi` 符号数均为 `0`，`ZhihuAdapter` 与 `ZhihuNavigationState` 完好。

### 修复 YouTube 21.46.522 底栏悬浮玻璃不显示（288 / `2.1.2-mine8`）◐ 已装机待验收

- **症状（真机 logcat 实证）**：打开 YouTube（`21.46.522 / 1561314006`）后 Vector 注入与配置读取均正常（`keys=310`、`accepted=true`、`YouTubeSpeedHaptics` 已绑定），但底部悬浮玻璃栏完全不出现，logcat 持续刷屏 `W YouTubeGlassDiag: Pivot row roles or native click targets could not be verified`。
- **根因（YouTube `21.46.522` 原包 `aapt2` + `jadx` 反编译 `PivotBar` / `avwh` / `avzo` 实证）**：
  1. `layout/image_with_text_tab`（`res/yTF.xml`，首页 / Shorts / 订阅内容等普通带字 tab）已将 `ImageView (id/image)` 包裹进 `FrameLayout (id/thumbnail_layout)` 内，不再是 `ConstraintLayout (cell)` 的直接子 View；而 `YouTubePivotBinding.child()` 原先只扫描第一层直接子 View，导致 `child(cell, "image")` 必返 `null`、`readRow()` 在第 0 槽即淘汰整行；
  2. 同型暗坑：`layout/avatar_image_with_text_tab_animation`（`res/JWb.xml`）将 `id/pivot_bar_thumbnail` 嵌在 `thumbnail_layout → 无 ID FrameLayout → CircularImageView` 两层之下，单层查找同样取空。
- **修法**：
  1. 升级 [YouTubePivotBinding.kt](app/src/main/java/io/github/offlineglass/hook/adapters/youtube/YouTubePivotBinding.kt) 的 `child(group, resource, maxDepth = 4)` 为“直接子节点优先 + 有限深度唯一子树查找”（同作用域重名仍拒收），并在 `readRow()` 中同时兼容直挂与 `thumbnail_layout` 嵌套的 `id/image` / `id/pivot_bar_thumbnail`；新增失败专用 `diagnose()`（仅纯 logcat，不落设备文件），并在 [YouTubeAdapter.kt](app/src/main/java/io/github/offlineglass/hook/adapters/youtube/YouTubeAdapter.kt) 做 3 秒节流输出；
  2. 补充 [YouTubePivotRoles.java](app/src/main/java/io/github/offlineglass/hook/adapters/youtube/YouTubePivotRoles.java) 空安全及 `上传/上載/上傳/upload`、`我的/媒体库/媒體庫/library` 标签映射；
  3. 新增 JVM 单测 [YouTubePivotRolesTest.java](tools/YouTubePivotRolesTest.java)。
- **验证**：`YouTubePivotRolesTest` PASS；Release 构建通过（`cache/scratch/LiquidTab-288-youtube-pivot.apk`，SHA-256 `6537AA47C5E18B767BC32E6C9A1F2D21C915489EEEC7E76415A437026FC2F680`，debug B 同签 `115ed98e…`，`zipalign -c -p 4` 通过）。

### 小红书折叠屏内屏（双栏）笔记详情顶栏玻璃适配（287 / `2.1.2-mine7`）✅ 用户真机确认

- **症状（内屏 `2312×2504` 真机截图与层级实证）**：折叠屏展开状态下打开小红书图文笔记详情（`NoteDetailActivity`），底栏「说点什么…」与点赞/收藏/评论胶囊已正常玻璃化（`XhsDetailGlass`），但顶栏完全停留在原生样式——左栏左上角是原生淡灰圆底 `<` 返回钮、右栏顶部是纯白底色作者栏与红框关注钮，右栏正文/评论从顶栏下方被截断。logcat 每次进入均打印 `W LiquidTabXhsNoteTop: note title/feed contract unavailable`。
- **根因（9.56.0 实机 View 树 + `r/m0b.xml` / `r/m13.xml` + `ss.l` 反编译确认）**：小红书在大屏双栏态为 `NoteDetailContentView` 加载双栏布局 `r/m0b.xml` + 无返回键标题栏 `r/m13.xml`，与 `XhsNoteTopBinding` 写死的手机单栏契约（`r/m09.xml` + `r/m12.xml`）有 5 处结构差异：
  1. `NestedScrollLayout`（`feed`）位于水平 `LinearLayout`（`splitBody`，左 `leftPane` `0..1272` + 右 `feed` `1272..2312`）下，非 `page` 直接子 View；
  2. `titleBarContainer` 位于水平 `LinearLayout`（`topBarRow`，`0,110-2312,272`）右侧，非 `page` 直接子 View；
  3. `page` 与 `TitlebarView` 内均无 `StatusBarView`，`ss.l.a()` 改用 `topBarRow.topMargin = statusBarH` 和 `feed.topMargin = statusBarH + 56dp`（`LinearLayout.LayoutParams`）；
  4. `avatarFrame` 直接挂在 `row` 下（无 `left` 容器），返回按钮 `back` 被独立移到 `topBarRow` 左侧 `backBox`（`0..1272`）内并自带淡灰圆背景；
  5. `XhsNoteTopPlates` 写死 `feed.parent === binding.page`，即使绑定成功也会跳过 backdrop RenderNode 采集。
- **修法**：
  1. 新增纯 JVM 几何求解器 [XhsNoteTopLayout.java](app/src/main/java/io/github/offlineglass/hook/adapters/xhs/XhsNoteTopLayout.java)，统一支持单栏、双栏展开（返回圆钮置左栏对称边距 `side`，作者/关注/分享三胶囊置右栏并左右与底栏 `EngageBarView` 对齐）与双栏窄屏压缩态（`leftPane` 隐藏时返回钮与作者胶囊顺排不重叠）；
  2. 扩展 [XhsNoteTopBinding.kt](app/src/main/java/io/github/offlineglass/hook/adapters/xhs/XhsNoteTopBinding.kt) 识别双栏 `splitBody(leftPane, feed)` + `topBarRow(backBox(back), container(title(row)))` 契约，保留单栏校验不变，并增加 `RedViewUserNameViewV2` 类名兜底防御资源混淆；
  3. 改造 [XhsNoteTop.kt](app/src/main/java/io/github/offlineglass/hook/adapters/xhs/XhsNoteTop.kt)：双栏时将 `plates` 插在 `page` 中 `topBarRow` 之下、`hits` 插在 `topBarRow` 紧上方（且高度钳制在顶栏区域，不侵入底栏与下半屏触摸）；清除 `back` 原生灰圆底与 `TitlebarView` 纯白底；对 `feed` 的 `MarginLayoutParams.topMargin` 归零并叠加 `header.paddingTop`，让右栏正文与评论滚动穿过玻璃顶栏；增加 `isFinishing` 门禁防止返回转场写入破坏收缩动画；
  4. 改造 [XhsNoteTopPlates.kt](app/src/main/java/io/github/offlineglass/hook/adapters/xhs/XhsNoteTopPlates.kt)：左栏返回圆钮采样 `leftPane` 的 live `RenderNode` 及左栏底色，右栏三胶囊采样 `feed` 的 live `RenderNode` 及右栏底色；状态栏渐变按左右分界分别匹配左右栏底色。
- **验证**：JVM 单测 `tools/XhsNoteTopLayoutTest.java` 与 `tools/XhsNavigationLogicTest.java` 全绿；Release APK `cache/scratch/LiquidTab-287-xhs-fold-notetop.apk`（11,656,148 B，SHA-256 `B11634C3D54A14B47CD58D7A0AB66A5E2D324A26EB9D599EB5D53F9269B39F55`）经 `zipalign -c -p 4` 与 `apksigner verify` 通过（debug B 同签 `115ed98e…`）。
- **真机验收路径**：① 折叠屏内屏点开图文笔记：左上 `<` 返回钮为液态玻璃圆钮（无灰底套圈）、右栏顶部「头像+昵称 / 关注 / 转发」三胶囊为液态玻璃且左右与底栏评论/三按钮对齐；② 向上滑动右栏正文与评论：文字平滑滚到玻璃顶栏背后触发折射；③ 滑动左栏长图、点返回/头像/关注/转发、按压缩放反馈均正常；④ 笔记返回首页收缩动画不回归；⑤ 外屏单栏图文笔记无回归。

### 移除小红书与静盒重叠的原图导出与开屏广告 Hook + 兼容国服 9.56.0 资源混淆（286 / `2.1.2-mine6`）◐ 已装机待验收

- **背景**：与机上静盒（小红书 GP `9.55.1` 及随后升级的国服 `9.56.0 / 9560805`）逐方法对账后，检出 LiquidTab 内两处无独立开关的非玻璃功能撞车：
  1. `XhsSplashAdsHooks.kt`（已激活）：拦截 `com.xingin.advert.intersitial.ui.a` 与静盒 `AD-01`（`splash_fullscreen_entry`）精确撞在同一类/方法链上；
  2. `XhsOriginalImageExport.kt` + `XhsImageSaveContract.java`（2.1.2 新增，潜伏高危）：设计上拦截 `MediaSaveConfig#b()` 与图片保存方法（与静盒 `EN-01/03/04` 完全重叠并会短路截胡原生保存链）；在 9.55.1 上仅因写死未混淆 `Intrinsics.areEqual` 碰巧回滚未生效。
- **国服 9.56.0 (`9560805`) 专项兼容发现**：
  - `XhsNativeContract.resolve` 对国服 9.56.0 `base.apk` 实跑 4/4 UI 合同唯一命中，`TabBarView#getIndexHome/Store/Post/Message/Me` 均在；
  - 国服 9.56.0 开启了资源名混淆（`0_resource_name_obfuscated`，仅保留 48 个未混淆 ID）：`id/nickNameTV` 与 `id/commentLayout` 仍在保留清单中（详情顶栏/视频按钮不受影响），但 `index_message`/`tab_badge`/`numberBadge`/`redDotBadge`/`tab_title` 全被混淆，`getIdentifier` 返回 `0`。原 `XhsNavigationState` 子树兜底（`skipTitle=true`）能读数字角标，但会漏纯红点角标；经反编译国服 `TabView` 实证，其懒膨胀角标容器为直接子视图 `FrameLayout`。
- **改动**：
  1. 删除 `XhsOriginalImageExport.kt`、`XhsImageSaveContract.java`、`XhsSplashAdsHooks.kt`；
  2. 清理 [XhsNativeBindings.kt](app/src/main/java/io/github/offlineglass/hook/adapters/xhs/XhsNativeBindings.kt)，仅保留玻璃 UI 所需绑定（`maxHeight` / `lottieFilter` / `channelTitle` / `sidebar`）；
  3. 精简 [XhsNativeContract.java](app/src/main/java/io/github/offlineglass/hook/adapters/xhs/XhsNativeContract.java)，跳过上述 4 个 UI 类以外的全部类扫描并将缓存前缀升至 `xhs-contract-7`，冷启 DEX 解析耗时由 ~2067ms 降至 ~1053ms；
  4. 增强 [XhsNavigationState.kt](app/src/main/java/io/github/offlineglass/hook/adapters/xhs/XhsNavigationState.kt)：当 `tabBadgeId == 0`（国服资源名混淆）时，优先定位 `TabView` 直接子 `FrameLayout` 角标容器并扫描其子树（`skipTitle = false`），同时兼容未读数字与纯红点角标。
- **验证**：JVM 单测 `tools/XhsNavigationLogicTest.java` PASS；Release 构建通过（`cache/scratch/LiquidTab-286-xhs-clean.apk`，SHA-256 `B6EF0E04E0C061F0B8385376BAADE8B6232CB989188B1A59678D4424E4BA8950`，debug B 同签 `115ed98e…`，`zipalign -c -p 4` 通过）；DEX 核验上述全部原图导出/开屏广告/历史 trace 符号均为 `0`，`xhs-contract-7` 与底栏配色/角标逻辑完好；对国服 9.56.0 `base.apk` 实跑 `XhsNativeContract.resolve` 精准唯一命中全部 4 个 UI 合同。

### 小红书底栏返回跳色（黑→红）与消息未读数字角标（285 / `2.1.2-mine5`）◐ 待装机验收

**问题①（详情返回首页时「首页」先黑色后跳红，且底栏播多余弹入动画）**：

- **根因 1（直接导致黑→红）**：进入笔记详情后 `IndexActivityV2` 走 `onStop()`，HWUI 回收 `liveBackdropNode` 的 DisplayList。返回首页后的 `500ms` 窗口内，`XhsDetailCover` 按设计暂停 backdrop 采样以保住原生收缩动画 → `hasUsableBackdrop()==false` → `drawSelectionContent()` 跳过光学镜片并返回 `selectionArtworkDrawn=false`。旧版 273 的兜底把 `mix` 归 `0` 后直接调用 `drawSource()`（`hideSelectedForOptics=false`），而 `XhsNavigationRenderer` 原先只按 `hideSelectedForOptics` 着色 → 选中态「首页」被画成普通黑色 `rgb(40,40,40)`，直到 `500ms` 后 `XhsDetailCover.exit()` 重建场景才啪一下跳成红色 `0xFFFF2442`。
- **根因 2（真机 `dumpsys` 实证的多余退出/弹入动画）**：详情页打开时，`TabBarView` 到 `android:id/content` 全部保持 `VISIBLE`，仅 `DecorView` 因 `onStop()` 变 `INVISIBLE`。`GlassHostLayout` 的 `ancestorsAreVisible()` 查到了 `DecorView`，误判原生底栏消失而将其藏为 `INVISIBLE / hostAlpha=0.0`，返回时又额外触发 `600ms` 弹出动画。
- **修法**：
  1. 新增纯逻辑工具 [XhsNavigationLogic.java](app/src/main/java/io/github/offlineglass/hook/adapters/xhs/XhsNavigationLogic.java)，[XhsNavigationState.kt](app/src/main/java/io/github/offlineglass/hook/adapters/xhs/XhsNavigationState.kt) 的 `drawForeground` 在 `!selectionArtworkDrawn` / `detachedDirectSelection` / `mix < 1f` 时，对 `selectionPath` 外绘普通色、对 `selectionPath` 内直接以 `hideSelectedForOptics=true` 绘正红 `accentColor`（与静止态镜片零坐标/色差）；`selectionPath` 为空时按 `index == selectedIndex` 着红。
  2. `XhsNavigationState.retainNavigationWhenNativeRowHidden` 在停窗或 `XhsDetailCover.paused` 且 `TabBarView` 到 `DecorView` 之前全为 `VISIBLE` 时保留底栏可见，避免后台误藏与返回重播弹入动画；同 Activity 内部真正隐藏原生 `TabBarView` 时照常隐藏。

**问题②（消息底栏不显示未读条数）**：

- **根因**：`XhsNavigationRenderer` 自绘静态五槽文字，未读取原生 `TabBarView.getIndexMessage()`（`TabView`）中的角标。经 9.55.1 反编译（`TabView`、`sdd.c0#D0`）及真机 `dumpsys` 实证，小红书全部未读消息条数与无数字红点统一挂载在 `index_message` → `app:id/tab_badge` → `app:id/numberBadge` / `app:id/redDotBadge`。
- **修法**：在 `XhsNavigationState` 的 `onHostPreDraw` / `onHostFrame` 中只读采集 `index_message` 的 `tab_badge`（按资源名动态解析 ID + 弱引用缓存，仅检查自身 `visibility`/`alpha` 避开父 `TabBarView.alpha=0`，并带子树兜底），变化时驱动重绘；在 `XhsNavigationRenderer` 中于「消息」文字右上角绘制小红书品牌红数字胶囊（`1`–`99` / `99+`）或纯红点，按槽宽安全区钳制适配内外屏与镜片折射。

- **验证**：JVM 单测 `tools/XhsNavigationLogicTest.java` PASS；Release APK `cache/scratch/LiquidTab-285-xhs-return-color-badge.apk`（SHA-256 `9e5557e3c9b37a378de16b88a4c9e790145d2e289c6f45d7fe18fd45f83afee3`，debug B 同签）。
- **真机验收路径**：① 首页点开图片/视频笔记 → 返回：收缩动画正常、底栏稳定在原位不重弹、「首页」首帧即正红且 500ms 后无跳变；② 有未读消息时看底栏「消息」右上角数字角标（内外屏 + 切到「消息」页/拖动滑块跨过「消息」时均完整折射不裁切）；③ 已读清空或新消息到达后角标实时更新/消失。

### 贴吧首页玻璃顶栏越界到“进吧”（283 / `2.1.2-mine3`）◐ 待装机验收

**症状**（内屏 2312×2504 取证）：切到底部“进吧”后，首页的三块玻璃（左圆钮 / 频道条 / 搜索圆钮）仍画在状态栏下方，正好压在“进吧”自己的顶栏上——整宽搜索胶囊被从中间截断，右侧“一键签到”还在旁边，观感是两套搜索栏叠在一起；同一段区域还会吃掉触摸，点胶囊中段点不动。

**层级证据**：decor 根下有三个 40dp panel，`[750,117][870,237]`、`[906,117][1406,237]`、`[1442,117][1562,237]`（top=117 恰为状态栏高度），而“进吧”整页是一个 `android.webkit.WebView [0,0][2312,2374]`，它的搜索胶囊在 `y≈129–213`。中间频道条上的“有料/推荐/直播”在控件树里不存在，是模块用 canvas 画的首页频道图稿——玻璃条正在把首页的排字画到进吧的搜索栏上。现场截图与层级：[`cache/scratch/tieba-22.13.1.1/enter-forum-inner/`](../../cache/scratch/tieba-22.13.1.1/enter-forum-inner/)（不入库）。

**根因**（`TiebaTopBar`）：

1. 顶栏绑定的是首页 `homepage.framework.indicator.ScrollTabBarLayout`，`scan()` 只认 `MainTabActivity`。
2. 可见性只判 `owner.isShown`（`canUseControls()`），没有底部 tab 判断；而首页 `ScrollFragmentTabHost` 的 `offscreenPageLimit = size-1`，切走后首页 PageView 仍然 `isShown`。
3. 唯一的搜索栏避让 `FeedHeaderSpacing` 要求 `PersonalizePageView → CoordinatorLayout → BigdaySwipeRefreshLayout → AppBarLayout → LinearLayout → TbSearchBoxView`，只覆盖首页原生推荐流；进吧是 H5，任何原生 View 规则都碰不到。

**不是折叠专用缺陷**：`TiebaTopBar` 全程只用 `root.width` 与 WindowInsets，没有 fold/pad 分支；外屏切“进吧”同样会盖住，只是内屏顶栏更宽、胶囊更长，重叠更完整。消息 / 我的 / 小卖部属同一类。

**修法**：新增 [TiebaMainTabGate.kt](app/src/main/java/io/github/offlineglass/hook/adapters/tieba/TiebaMainTabGate.kt)，用宿主自己的底部 tab 类型做闸门——`com.baidu.tbadk.core.tabHost.FragmentTabHost#getCurrentTabType()`，首页 `type=2`（`RecommendFrsDelegateStatic`），进吧 `type=1`（`EnterForumDelegateStatic`）。只有首页放行；`canUseControls()` 追加该判定，`onPageSelected` / `onTabSelectionChanged` 增加通知以便切 tab 当帧收起。宿主契约读不到时**一律放行**，保持改动前行为，避免版本漂移把首页顶栏一起关掉。日志 `LiquidTabTieba: Bottom tab type=…, home chrome enabled|parked`。

**验收路径**：装 283 后冷开贴吧 → 首页顶栏照旧 → 切“进吧”：玻璃条应在切页动画内收起、原生搜索胶囊与“一键签到”“有料/推荐/直播”完整可见可点 → 切回首页玻璃条恢复 → 依次过 小卖部 / 消息 / 我的 → 外屏重复一遍。

**B 方案备忘（把“进吧”顶栏玻璃化，未实施）**：可行但脆。进吧顶栏是 H5，语义类名未做哈希（`.top-bar`、`.top-bar-tabs .tab-both`、`.forum-search`、`.search-box-new`、`.search-box-wrapper`、`.sign`），注入一条 `!important` 样式即可隐藏，点击与状态可用 `WebView.evaluateJavascript` + `ValueCallback` 完成，不需要 `addJavascriptInterface`。抓取 WebView 可从 decor → `FragmentTabHost`（`type==1`）→ 当前 ViewPager 页里第一个 `android.webkit.WebView`；宿主侧 `EnterForumHybridController`（`com.baidu.tieba.enterforum.home`）负责建页。**风险点**：`assets/hybrid-main-forumtab_<hash>` 里的 `mainPage.html` 是 CDN 外壳，真正的前端从 `tb3.bdstatic.com` 加载，H5 可以在不发版的情况下改类名；因此 B 必须带开关 + 选择器 miss 时回滚原生可见，不能作为默认路径。

### 抖音 41.2.0 首条推荐的文案和右栏（277，真机验证）

旧版有过视频已播完、页面高 2504 px、文案和评论栏却都是 0 px 的前台样本。41.2.0 有一条首页专用的延迟绑定路径，滑动开始时会执行待处理的控件任务。277 在抖音主进程、`versionCode=410200`、主线程和确切的宿主类 `X.0zm2` 均匹配，且原判定 `LIZ()` 返回 `true` 时，选择宿主自己的立即绑定分支。普通视频在原位置装配文案和右栏，专用子类和宿主异常仍按原流程处理。实现见 [DouyinFirstFeedBinding.kt](app/src/main/java/io/github/offlineglass/hook/adapters/douyin/DouyinFirstFeedBinding.kt)。

11 月 21 日的同一进程内，277 命中后，首批 1 条视频的第一次页面采样已测到文案 279 px、右栏 1195 px。加载更多的 17 条晚 879 ms 返回，翻页发生得更晚。复验时看当前页的文案、右栏和评论图标，`h` 单独不能判断成功。版本升级后应重新核对宿主类、方法和语义。[失败快照、成功时序和 APK 指纹](logs/douyin-coldstart/feed-binding-41.2.md)

带 277 探针及 Nuke 列表日志时，在 `liquidtab-mine` 下按本轮抖音主 PID 回放。`adb` 选定同一台设备，`--pid` 填入该次冷启动的 PID。

```powershell
$douyinPid = (adb shell pidof com.ss.android.ugc.aweme).Trim()
adb logcat -d --pid=$douyinPid -v threadtime -s LiquidTab:I NukeApp-OSS:I '*:S' | python tools/check_douyin_first_feed.py --pid $douyinPid
```

脚本也可读取保存的 UTF-8 日志。退出码 0 表示首屏和回调次序均满足，1 表示唯一首屏的文案或右栏为空，2 表示缺少可判定的时序。四组回归用例可用 `python -m unittest discover -s tools -p test_douyin_first_feed.py` 复跑。

### 小红书详情切后台后返回（278，待真机复验）

274 的时序记录里，同一个图片详情实例多次切回前台后仍存在，随后返回首页时底栏采样闸门仍关闭。首页顶栏的取景原先只检查可见性，焦点恢复时还会立即重建玻璃画面。用生产代码在 JVM 回放这段顺序，可复现顶栏在详情退出结算前取景和重建。

278 将顶栏取景和前台恢复接入已有的详情闸门。退出后由原来的唤醒逻辑恢复，JVM 回放通过。真机遇到偶发问题时，记录大致时间以及返回后是否仍在原卡片和滚动位置。

279 为本次排障启用落盘记录，用 `./gradlew -PxhsReturnTrace=true :app:assembleRelease` 构建。小红书主进程将页面生命周期、原生转场请求、顶栏和底栏的取景闸门状态异步写入宿主 `files/liquidtab-xhs-return.log`，另保留上一份 `.log.1`，每份最多 128 KiB。每行的 `elapsedRealtime` 可与静盒 0.9.27 的 `files/ui78-diag.log` 对齐。原生转场请求记录的是宿主调用，实际画面仍以使用时看到的结果为准。

**xhs-videocover**（versionCode 273）—— **小红书视频笔记返回动画 + 返回后「首页」标签消失** ⏳ 待装机验收

- **症状①**（用户 2026-11-19 报）：打开笔记详情返回后，底栏「首页」标签消失、要切 tab 才恢复——
  与 B 站 260 时代的"当前标签缺字"同型。
- **①根因**：`XhsNavigationState.drawForeground` 在 optical mix=1 时无条件把选中标签从前景裁掉，
  指望镜头场景里的折射字补位；但 `drawSelectionContent` 某些帧没能把导航录进 combined 场景
  （`selectionArtworkDrawn=false`，如详情返回后暂停场景重建的首帧），镜头内部就是空的
  → 标签整段消失，直到某次状态变化重新录制（切 tab）。
  B 站 261 已用同帧兜底修过同型问题（`BilibiliOpticalForeground`），273 把同一 recipe 移植给 XHS：
  `selectionArtworkDrawn=false` 时 mix 直接归 0、正常画原前景。
- **症状②**（用户报）：小红书视频笔记返回动画丢失。
- **②根因（代码即证）**：**视频笔记不进 `NoteDetailActivity`，走
  `com.xingin.matrix.detail.activity.DetailFeedActivity`**（`XhsVideoViewport`/`XhsVideoRenderHooks`
  早已按这个类名适配），而 `XhsDetailCover` 只盖 NoteDetailActivity → 首页采样循环在视频详情
  整个存续期照常活跃 → 返回瞬间吃掉收缩动画（与 coverfix2 根因完全一致，只是漏了这个页面）。
- **②修复**：`XhsDetailGlass` 的四个生命周期 hook 改为 `COVERED_PAGES` 集合
  （NoteDetailActivity + DetailFeedActivity）统一 enter/exit；详情工具栏 skin 仍只对 NoteDetailActivity 生效。
- **症状③**（用户报）：外屏看帖子几分钟后返回必失效；从后台切回也偶发。
  判定：**"看几分钟"大概率就是②的视频笔记变体**（看视频笔记必然超过几分钟）；
  图片笔记的 cover 无任何时间相关逻辑，coverfix3 的后台重武装（onPostResume enter）仍在。
  装机后请分别用**图片笔记长停留**与**视频笔记**复测，若图片笔记长停留仍丢动画，
  复现时抓 `adb logcat -s LiquidTabCover` 看 enter/exit 时序再查。
- Release 构建/验签通过，与装机包同证书（`115ed98e…`）。
  APK SHA-256 `f370895985d966ee436f3d7d3540ef220683adc6b7e0b7ceff51013e2eeb6095`。
  [273 APK](build/LiquidTab-273-xhs-videocover.apk) · [验签日志](build/xhs-videocover-273-verification.log)
- 2026-11-19 19:44:26 经用户授权同签名覆盖至 273，设备包信息确认 273/`2.1.0-xhs-videocover`。
  未代开模块 App、未强停小红书。[安装日志](build/xhs-videocover-273-install.log)
- **验证路径**：① 图片笔记打开→返回：动画完整、「首页」标签不消失；② 视频笔记打开→返回：
  动画完整（外屏+内屏）；③ 视频笔记快开快退；④ 图片笔记停留 5 分钟→返回；
  ⑤ 图片笔记→后台→回来→返回。全程底栏标签不冻结。

**bili-story-comment-shadow**（versionCode 272）—— **修正竖屏评论面板上的弹幕栏残影**

- B 站 9.18.0 于 22:43 在 271 复现两条胶囊。层级只有一套评论输入控件，输入框的窗口范围为 `(84, 2255)–(2078, 2382)`。下方空胶囊与播放器 `story_ctrl_layer_danmaku` 的 `(56, 2339)–(1909, 2431)` 重合，22:27 的旧截图也有同一条残影。[271 截图](build/bili-story-overlap-20261118-224332/screen.png) · [层级](build/bili-story-overlap-20261118-224332/bili-hierarchy.txt) · [旧截图](build/bili-story-reply-20261118-222717/bili-screen.png)
- 弹幕阴影原先绘制在 `DecorView.overlay`。播放器被评论面板盖住后，这个顶层阴影仍描出弹幕胶囊的边缘。272 将阴影挂回 `story_view_pager` 的 overlay，按播放器层的局部坐标绘制，随原生层级被评论面板盖住。
- Release 构建和签名验证通过，272 与 271 使用同一签名。[272 APK](build/LiquidTab-272-bili-story-comment-shadow.apk) · [构建日志](build/bili-story-comment-272-release.log) · [验包日志](build/bili-story-comment-272-verification.log)
- 2026-11-18 22:55:00 经用户授权从 271 同签名覆盖至 272。手机包信息和 APK SHA-256 与本地产物一致。核对时 B 站仍是装前进程 PID 4481，待冷启动测试。[安装日志](build/bili-story-comment-272-install.log)
- 真机待复测。使用同一竖屏视频打开评论，确认只有一条悬浮输入栏。再打开「共 N 条回复」，核对二级回复栏、列表滚动、键盘和返回。关闭评论后核对弹幕栏阴影。

**bili-story-comment**（versionCode 271）—— **B 站竖屏评论悬浮输入栏**

- B 站 9.18.0 在 2026-11-18 22:27 的真机现场，将评论弹层挂在 `StoryVideoActivity` 的 `fl_root_container` 内。旧适配只从独立 Dialog 进入，现场的 `comment_main.bottom` 与输入栏顶部同为 875px，列表停在输入栏上沿。[层级证据](build/bili-story-reply-20261118-222717/bili-hierarchy.txt)
- 271 从内嵌的 `StoryTabCommentFragment` 绑定主评论与二级回复容器，分别应用悬浮输入栏和局部阴影，随页面移除解绑。Release 构建与签名验证通过。待真机复测滚动、二级回复及键盘弹起。[271 APK](build/LiquidTab-271-bili-story-comment.apk) · [构建日志](build/bili-story-comment-271-release.log)
- 2026-11-18 22:39:33 经授权同签名覆盖至 271，设备 APK 与本地 SHA-256 一致。覆盖后 B 站仍在原进程，下一次冷启动可验证悬浮效果。[安装日志](build/bili-story-comment-271-install.log)

**bili-icon-contrast**（versionCode 268）—— **B 站底栏文字对比度和皮肤图标尺寸**

- 267 真机反馈已显示个性皮肤，浅色玻璃上的白字偏淡，图标偏小。268 将独立标签放在玻璃前景绘制，浅色玻璃用黑字，深色玻璃用白字。
- Compose 按原生图稿子节点范围取图，View 直接量原生图标和角标。默认放大目标为 1.22 倍，由槽宽、文字上方的可用高度和玻璃轮廓限幅。
- 黑白标签、窄槽、宽图稿和独立发布键的 JVM 用例通过。Release 与同签名校验通过，真机待复测。APK SHA-256 为 `863cae5aa9ecf6dbc46a21084547deb835ba70a0d0e73561de601e3715d5e147`。[268 APK](build/LiquidTab-268-bili-icon-contrast.apk) · [构建日志](build/bili-icon-contrast-268-final.log)
- 2026-11-18 21:43:35 经用户授权从 267 同签名覆盖至 268，设备 APK 的 SHA-256 与本地一致。核对时 B 站主进程已退出，底栏显示待下次启动后复测。[安装日志](build/bili-icon-contrast-268-install.log)

**bili-native-icons**（versionCode 267）—— **B 站内外屏玻璃底栏接回原生皮肤图标**

- 267 基于 266 构建。Release 构建、`BilibiliComposeTabContractTest` 和与 265 的证书对照均通过。APK SHA-256 为 `5afb7c4cd6c0cb0fdc99b56dd69ad3d8f7379fc7e5884eb7585d246ea2e49b1b`。[267 APK](build/LiquidTab-267-bili-native-icons.apk) · [构建日志](build/bili-native-icons-267-release.log)
- 2026-11-18 21:32:29 经用户授权从 266 同签名覆盖至 267，设备 APK 的 SHA-256 与本地一致。装机后 B 站仍在原主进程 PID 27390，皮肤效果待冷启动后复测。[安装日志](build/bili-native-icons-267-install.log)
- 国服 B 站 `TabHost` 逐槽使用原生图片、SVGA、角标和发布控件。`WindowAwareComposeView` 通过五个原生点击槽定位独立的 Compose 绘制节点。两条路径都将原生图稿摆进玻璃槽位。
- B 站 9.18.0 的 Compose 证据在 `drag-all/analysis/bili-9.18.0/jadx-bottomtabs/Compose-j.java`，皮肤整条背景与 `o.a`、`q.a` 绘制的导航项是同级节点。View 路径的图标与动画证据见同目录的 `TabHost.java`。`LiquidTabBiliNav` 的 `bottomArtwork` 记录首次成功绘制的来源。
- 图稿尚未就绪时使用现有图标兜底。原生文字独立于图片时可应用仅图标、字号和英文标签设置。
- 真机复测用一套差异明显的 B 站个性皮肤，分别在内屏和外屏看完整底栏、发布图标、选中态与换皮肤后的更新。另核对隐藏槽位、分离发布键和详情返回动画。

**dy-padsplit**（versionCode 266→270 迭代）—— **抖音折叠内屏侧边面板让位** ✅ 270 用户真机确认（开评论/别人主页底栏收进左列，关闭回中）

- **症状（2026-11-18 内屏真机截图取证，`logs/douyin-padsplit/`）**：内屏（2312×2504 竖屏 pad 布局）下打开评论区，抖音把 feed 列压到左列 0–1388，右侧窗格（`NewPadMultiScreenDrawerLayout` → `app:id/t-6`，x 1388–2312）承载 `PadSplitCommentListPageFragment`；原生 `root_view` 底栏行被整个移除、左列底部槽位（`app:id/z+o`）塌缩 0 高。模块玻璃底栏（挂 decor、按整窗居中，bounds 607–1704）横跨分界，盖住评论面板底部输入栏。打开别人主页同理（`profile.PadProfilePageFragmentV2`，tag `profile_split_container`）。
- **266/269 教训**：载荷 fragment 是空壳类（`PadSplitCommentListPageFragment extends CommentListPageFragment`，47 行，不声明 `onCreateView`），按 `DouyinSidebarPages` 模式 hook 各自 `onCreateView` 必然 NoSuchMethod 静默失败；269 改 hook `NewPadMultiScreenDrawerLayout` 构造函数（`hookAllConstructors` + 绑定落盘 `dy_pad_split_binding_status.txt` 自证）后隐藏生效，但用户拍板不要"滑走"。
- **270 语义（用户拍板）**：面板打开时底栏**不消失，像视频列一样整体左移**——锚定列宽 = 被占窗格左沿（`occupiedPaneLeft` 逐帧取值，随面板滑入联动），底栏 LEFT gravity + leftMargin 动画居中进左列（607→145px，240ms），关闭回整窗中心。`retainNavigationWhenNativeRowHidden` 在分屏期间保留导航（原生行被移除时 shouldShow 仍真）；`pageAllowsNavigation` **不**接 pad 分屏（底栏保持可见）。translationX 被共享滑块跟手占用，故走 leftMargin。pane→container→payload 三层占用检查覆盖所有载荷类型（评论/主页/音乐/web），空窗格与 GONE 缓存判"关"。
- 验证路径：装 270 → 冷开模块 App → 重启抖音 → 内屏开评论/别人主页：底栏左移缩进左列、不遮面板；关闭回中；左侧抽屉隐藏行为不回退；外屏零变化。

**bili-mail-outline-cleanup**（versionCode 265）—— **移除 264 的折叠实验，保留已验收的玻璃和返回动画**

- 264 是历史实验包。265 删除 `BilibiliFoldKeepFeeds` 及注册，不改 263 已验收的信封黑边、宽频道条、底栏导航和返回期间的采样门禁。与 263 相比，265 DEX 的全部 40221 个 code item 区域逐字节相同，DEX 字符串集合只差版本名。
- 2026-11-18 20:24:57 经用户确认后，同签名覆盖至 LiquidTab 265。「折叠变化不刷新」由 Innocent 提供，B 站 9.18.0 已收到 r3 的用户复测反馈和宿主日志。[Innocent 折叠修复复盘](../innocent/logs/foldkeep-r2-runtime-2026-11-18.md)
- [265 release APK](build/bili-release-265.N5PtHC/LiquidTab-265.apk) 已完成 R8 构建，和 264 同证书（`115ed9…`）。源码及 DEX 都不含折叠实验，264 DEX 作阳性对照。`BilibiliComposeTabContractTest` 通过。[验包日志](build/bili-release-265.N5PtHC/verification.log) · [安装日志](build/bili-release-265.N5PtHC/install.lAEM4y.log) · [装机 SHA-256 对账](../innocent/sharefix/build/foldkeep-r2.G2OMpC/installed.Bfd1jP.log)。

- **B 站信封黑边（263 / `2.1.0-bili-mail-outline`，用户已确认基本正常）**：只在国服首页消息图标下画黑色轮廓，原生图稿、未读角标与消息点击保留，不改频道/底栏和返回采样。首轮同签名覆盖返回 `INSTALL_FAILED_ABORTED: User rejected permissions` 后停止；用户重新授权后于 2026-11-18 16:11 单次覆盖成功，设备包信息确认 263。未代开模块 App、未强停 B 站。APK 和日志见 `build/bili-release-263.eq5MVi/`。
- **内屏普通视频详情：BV 号到标签之间起滑无效（待定位）**：263 用户复测，展开箭头可点、简介文字可原生长按复制、该区未触发 DragAll，只是横/竖滑不随手势移动。不是整片收不到触摸，也不能把 262 阶段的 `dumpsys activity` 后台快照（播放器边界重叠）当作拦截证据。原生链可用 `adb shell dumpsys activity` 在 `UnitedViewPager` → `UnitedRecyclerView` → `ExpandableTextView` 层级复核。待确认越过触摸阈值后由谁持有 MOVE，修复需保留复制、展开和标签点击。

- **折叠开合会替换推荐视频（用户实测）**：263 的 `adb logcat -b events` + `adb shell dumpsys activity` 只证明一次 16:39:20 开合保留了 Activity/PID 和 pager/RecyclerView 实例，布局从 2312px 改为 1120px。复采另一次开合也见到可见卡片 View 变化。这些对象的身份都不是视频身份，既不能反驳用户看到的视频被换，也不能单独证明重新发了网络请求。9.19.0 静态路径与 9.18.0 真机验收分开，详见 [Innocent](../innocent/README.md)。

**bili-stable-width**（versionCode 262）—— **稳住 B 站内屏宽频道条** ✅ 用户确认宽度问题已修好

- 261 设备记录：从设备 `/sdcard/Android/data/tv.danmaku.bili/files/liquidtab-home-layout.txt` 与 `liquidtab-channels.txt` 只读读取（可 `adb exec-out su -c 'cat ...'`）。内屏绑定到七项后，后续一次 `needed=2442.975` 超出 `maxSpan=1883.7`，`span=0` 回退成三项。按相同预算和测字宽度，本地用单个额外候选复现了宽条退化；该次设备记录没保留额外候选的身份，不能断言是热词。
- `BilibiliHomeLayoutHooks.Session.updateChannels()`：对于 Compose，当最新候选仅是原有顺序的扩展且放不下，原频道仍放得下、仍包含当前选中，则保留原宽条但用最新原生绑定。文字测量固定用粗体，不受当前高亮影响。不冻结真实缩窗，不动外屏的 View 回退、点击分派、底栏或采样门禁。
- JVM 回放覆盖 7→8→7、真正缩窗、能容纳的新增项、换序/移除及新项选中。2026-11-18 15:44 同签名覆盖一次成功，设备包信息确认 262。262 APK、261 留档和构建/安装日志见 `build/bili-release-262.zI2nBw/`；未代开模块 App、未强停 B 站。

**bili-slots-foreground**（versionCode 261）—— **修正顶栏命中与选中，底栏返回帧保字** ◐ 用户反馈基本修好，顶栏仍偶发变宽/变窄

- 2026-11-18 15:11 一次同签名覆盖成功，设备 `dumpsys package io.github.offlineglass` 确认为 261。未代开模块 App 或强停 B 站。

- 顶栏的文字、点击和滑块按同一组投影频道计槽（含「更多」）；宽条允许大于 7 槽，其他宿主上限不变。无可信原生选中项时不把「更多」当频道，滑块移动时不同时强调另一项文字。保留外屏压缩布局和原生频道点击。
- 国服 B 站底栏只在同一绘制帧确实画进镜片文字时才裁掉镜片内的普通前景；无替代画面时直接画原前景。没有新增采样、强制重绘或改 `BilibiliDetailCover` 的门禁与 500 ms 退出时序。秒退问题不混入本版。
- 本地复跑：4/7 槽更新到 8 槽、8 槽回 4 槽，所有标签中心的命中和滑块坐标一致；镜片无替代字时保留前景、有替代字时不重画。260 的 Compose 底栏契约测试仍通过；构建日志 `build/bili-261-assemble.log`。这些不代替真机上的动画和底栏尾帧验收。

**bili-nav-cover**（versionCode 260）—— **内屏底栏切页 + 补齐返回转场采样门禁** ◐ 已上机，返回动画有改善，仍有尾帧问题

- 旧版诊断（248/251）：点击到达槽 `1`，内屏 `WindowAwareComposeView` 里没有可用 View cell；合成触摸的成功只代表收下 DOWN。原生 Compose 树有「首页／关注／发布／会员购／我的」五个点击动作。
- `BilibiliNavigationState`：仅宽窗国服 Compose `tab_host`、且无有效 View cell 时走原生语义点击；`BilibiliComposeTabContract` 按标签匹配，歧义不派发，发布不算可选页面。原生 `Selected` 与页面内容才是切页证据。外屏沿旧路径。
- 258 时间线只证明首页恢复焦点时 cover 仍开着；`BilibiliHomeLayoutHooks.PanelState` 和 `BilibiliHomeTopVeil` 的取景原先不受它管。260 让它们共用门禁及退出唤醒，不改 500ms 或原生布局。移除同步写文件诊断，现场只看 `LiquidTabBiliNav`、`LiquidTabCover` 事件及用户实测。
- 260 用户反馈：详情停留后返回，原生动画已明显改善；秒退仍闪，停留后返回则在动画播完时底栏可能闪一下，当前标签（如「首页」）会短暂缺字。顶栏仍有双选中观感和点击错位。不以焦点日志、滑块移动或构建成功代替真机验收。
- 顶栏定位：装 260 后的设备诊断 `Android/data/tv.danmaku.bili/files/liquidtab-channels.txt` 记录了七个频道（直播、推荐、热门、动画、影视、S16、新征程），含「更多」共八槽。`BilibiliHomeLayoutHooks.Session.updateChannels()` 按八槽画字，但 `GlassHostLayout.updateAdapterSlotCount()` 拒绝超过七槽。JVM 夹具复用现有槽位代码：冷建八槽正常，从七槽更新到八槽后点末项仍落第六索引，选中末项时滑块退到「更多」。这是可重现的槽位错配，未截到该次真机画面。修复应让绘制、命中和原生选中共用槽位，不减少内屏频道。
- 底栏定位：`BilibiliOpticalForeground.draw()` 在光学混合为 1 时切掉镜片内的底栏字，但 `GlassHostLayout.drawSelectionContent()` 不保证该帧已画出镜片中的替代字。本地夹具给定缺替代图层的一帧，当前标签绘制 0 次、其他标签 1 次；尚未证实真机缺字的那一帧走了这个分支。优先研究前景兜底（只有该帧镜片确实画出替代字时才挖空），不提前恢复取景或改 500ms 门禁。秒退另查。

**bilitop2**（2026-11-18，versionCode 243）—— **B 站内屏频道条：全量原生顺序 + 宽屏加宽** ⏳ 待装机验收

- **bilitop1 实测反馈**：内屏玻璃顶栏已出，但频道条只显示 pins(直播/推荐/影视)+当前选中
  ——右划落在"看不见的热门"上（原生 pager 真实序为 直播/推荐/热门/动画/影视…），
  且热门被临时插进条里 3→4 个跳动，不符合使用预期。
- **修复**：宽屏姿态下频道条按**原生顺序显示全部频道**（条序=pager 序，划到哪指示器
  跟到哪、集合稳定不跳动），顶栏簇宽度按实测文本宽加宽（`channelStripSpan`）；
  放不下（余量 <40dp）才回退老的 pins 压缩逻辑。外屏条宽贴屏幕边自动维持原压缩样式，
  无回归。`BilibiliHomeChannels.all()` 新增；`Session.updateChannels` 宽度判定 +
  `update()` 置位 `clusterWidth`。
- **后续**：底栏原生图标在 267 接入。

**bilitop1**（2026-11-18，versionCode 242）—— **B 站内屏玻璃顶栏适配** ✅ 用户真机确认（反馈频道条逻辑，见 bilitop2）

- **症状**：内屏（折叠展开）B 站折叠适配把整个 chrome 合并进 `top_bar_fixed`，
  `top_bar_collapsible` 空置（height 0）→ `ComposeHomeBinding.refresh()` 只在
  collapsible 里找 avatar/搜索/消息 → `face == null` 绑定失败 → 每帧 GONE+restore，
  原生深蓝星空折叠顶栏裸奔（"所有透明失效"）。外屏 07:23 诊断成功 vs 内屏 09:24
  `top=1 row=46 tabs=[serin赛琳女装,…,46] actions=[]`（搜索占位文字与角标被当 tab）。
- **修复**（`ComposeHomeBinding.refresh()` 重构）：
  - `topNode` 查找池回退：top(collapsible) 找不到 → row(合并后的 fixed) 里找；
  - tab 候选过滤：先定位角色节点（头像/搜索/消息/游戏），按 lineage 排除角色子树
    + 纯计数角标（`isCountBadge`：纯数字/+/./万十百千亿）；
  - 搜索词回退：top 无文本 → 搜索子树里的占位文本（内屏显示原生热搜建议词）。
- **验证**：内屏顶栏出玻璃件（头像圆钮+频道胶囊+消息圆钮，搜索胶囊在底部导航上方）；
  外屏无回归；频道切换/点头像消息搜索可用。

**bilicover2**（2026-11-18，versionCode 241）—— **B 站返回动画修复（coverfix2/3 recipe 移植 + 竖屏视频）** ✅ 用户真机确认（横屏详情+竖屏 story 返回均正常）

- **症状**：B 站首页进视频详情再返回，收缩动画被跳过（一闪而过、像被加速）；
  **竖屏视频（story）返回同样闪**——用户 240 实测反馈。
- **现状确诊**：`BilibiliNavigationState` 没有重写 `pauseBackdropWork`（默认 false）→
  首页底栏采样循环在返回转场期间照常活跃。B 站组件链不止一条：
  首页 = `tv.danmaku.bili.MainActivityV2`（TabHost 玻璃底栏），横屏视频详情 =
  `com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity`，**竖屏视频 =
  `com.bilibili.video.story.StoryVideoActivity`**（现场取证：dumpsys topResumed），
  另有透明容器 `com.bilibili.video.story.StoryTransparentActivity`（模块
  `BilibiliStoryTransitionReveal` 已知的类）。
- **修复**（照抄 XhsDetailCover，新文件 `BilibiliDetailCover.kt`）：
  - `COVERED_PAGES` 集合盖住上述三个页面；`onCreate`/`onPostResume` enter，
    `onStop`(仅 isFinishing)/`onDestroy` 延迟 500ms exit；
  - `BilibiliNavigationState.pauseBackdropWork` 返回 cover 状态 + `markHost`；
  - exit 时唤醒 host（`postInvalidateOnAnimation` + 80ms 补一次 invalidate，防标签冻结）；
  - `GlassHostLayout` 的 onDraw 采样旁路门禁（coverfix3 加的）对所有 adapter 通用，自动生效。
- **范围**：播单 `UnitedPlaylistActivity`、搜索页等如也闪再扩名单。日志 tag
  `LiquidTabCover`（`adb logcat -s LiquidTabCover` 看 enter/exit，simpleName 区分页面）。
- **验证路径**：① 首页 → 横屏视频详情 → 返回；② 首页 → 竖屏视频 → 返回；
  ③ 详情刚开立刻返回；④ 详情/竖屏切后台再回来返回。动画应完整播放、底栏标签不冻结。

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

**detailalign**（2026-11-17，versionCode 239，✅ 用户真机确认）—— 详情悬浮栏三按钮对齐/同大小

- **症状**：笔记详情底部悬浮工具栏，点赞（心形）比收藏（星形）/评论（气泡）大一圈，
  且图标与下方数字（"17" vs "收藏"/"18"）不在同一水平线。
- **确诊**：XHS 详情动作按钮是**自定义 ViewGroup 容器（ImageView+TextView）**，
  覆写 `getAccessibilityClassName()` 返回 `"android.widget.Button"`（无障碍惯例），
  uiautomator dump 因此误报为 Button——勿被 dump 类名误导而以为
  `VerticalButtonSkin.create`（需 ViewGroup）不生效。实测 `VerticalButtonSkin` 生效。
- **Bug1（上下不齐）**：`VerticalButtonSkin.draw()` 的 `glassHeight = minOf(48dp, button.height)`。
  三个按钮原生高度不同（点赞 162px、收藏/评论 115px，但**中心都对齐 ~2371**），
  min 使点赞 top=15、其他 top=0 → 图标/文字目标 Y 差 ~9px。修法：
  **统一 `glassHeight = 48dp`**（不 min），`top = 按钮中心 − 24dp`，
  三按钮图标/文字全部落到同一水平线（绘制位置仍在各 button 内，无溢出）。
- **Bug2（心形独大）**：点赞 Lottie 走"测量可见像素→规范 24dp"，
  收藏/评论走 `min(icon.w, icon.h)`（含透明 padding 的视图尺寸）→ 可见部分被缩小。
  修法：**所有图标（lottie 与静态）统一走 `measureVisibleGlyph()` 的可见像素中心/大小**，
  统一回退值 = `icon.width/2, icon.height/2, min(w,h)`（去掉心形专用 15dp/30dp 经验回退），
  24dp 对任何图标都表示"可见部分 24dp"。
- **证据**：detail_dump.xml + detail_shot.png（首版症状），修复后用户实测对齐。
  诊断 tag `LiquidTabDetail`（row 初始化打印 action 类名/高度/skins 数）。
- **注意**：本次只改详情按钮绘制，不碰 cover/配置链/compactButtons。

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
- 装后是否需要打开模块 App 取决于镜像是否完整（首次安装要；只更新 APK、设置没动则不需要，
  判定见 AGENTS.md §3）；随后重启宿主。验证见“宿主状态文件 / 模块诊断落盘”，
  `/data/adb/lspd/log/verbose_*.log` 与 `logcat -s LiquidTab*`。
- **vivo 现场认知（22:xx 实测）**：模块 App 退后台数秒即被冻结/强停 →
  provider `Unknown authority`；libxposed service 绑定常来不及完成。配置读取
  依赖四层兜底（RAM committed → provider → Vector 镜像 → 宿主本地存档），
  详见 mirrorfix/hostfallback 两节。遗留：小红书主进程偶发未被注入
  （子进程正常），与配置链无关，待查。

## 同源参考（不在本目录，gitignored）

- 上游源码 zip / 原 v2.1.0 APK：`xhs/work/liquidtab/`
- 本机小红书 APK（9.54.0）与顶栏取证：`xhs/work/device-xhs-current/`、`xhs/work/dumpsys_fg.txt`
