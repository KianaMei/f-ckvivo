# LiquidTab 2.1.2 上游全量盘点（2026-11-23）

> 对象：`big-dictator/LiquidTab` v2.1.2（`03ee88b`，2026-11-22 12:38 发布）
> 对照：本机 fork `forks/liquidtab-mine`（基线 = 上游 2.1.0 Release 源码 zip，versionCode 223）
> 方法：下载 2.1.0 / 2.1.2 两个官方 source zip → 建临时三方仓库（base=2.1.0，ours=本机补丁，theirs=2.1.2）→ 真实 `git merge` 取冲突。
> 审计用的临时三方仓库已清理（不占空间）。复现：下载官方 `LiquidTab-v2.1.0-source.zip` 与 `LiquidTab-v2.1.2-source.zip`，以 2.1.0 为 base、`mine` 为本地补丁、2.1.2 为 theirs，`git merge` 即可复现本文数字。

---

## 0. 结论先行

- **上游确实有更新**：v2.1.2，versionCode **225**，Release 带正式 APK（11.6 MB）+ 完整源码 zip（4.99 MB）。
- **上游不是单纯加功能**：2.1.0 之后它自己继续演进，恰好把我们动过的区域（XHS 顶栏/详情、B 站底栏/频道条/竖屏评论、选中绘制内核、YouTube 顶栏）也重做了一遍，所以冲突是"两套实现抢同一块地"，不是文本打架。
- **真实合并结果：15 个文件、37 个冲突块**；其余 34 个文件自动合并，但其中 **10 个属于语义重叠**，必须人工复核。
- 冲突分三级：机制层（`TargetAdapter` / `GlassHostLayout` / 配置链）→ 必须先定；B 站 4 个；小红书 3 个。
- **上游删了 `bilibili_island`（超级岛桥接）和 YouTube 旧顶栏 13 个文件**，我们的 `youtube_top_bar` 配置键在上游已不存在（变孤儿）。
- 设备侧：机上仍是 **279 / `2.1.0-xhs-return-trace`**（11-21 21:21 更新）；刚才复查时 ADB 已掉线（设备不在线，未做任何操作）。
- 签名红线不变：本机包 = debug B（`115ed98e…`），上游 APK 是作者自有签名，**不能 `install -r` 覆盖**，只能自己源码重签。

---

## 1. 上游 2.1.2 全量变更

总量：**325 文件变更，+24990 / −9365**（新增 85、删除 49、修改 190、重命名 1）。

### 1.1 应用适配层（改动量前 20）

| 应用 | 文件数 | 关键动作 |
|---|---|---|
| 京东 jd | 25 | 新增 6：CheckoutBinding / GpuBackdropCapture / HiddenNavigationBlur / NativeContentBinding / NavigationBinding / NavigationContract |
| 小红书 xhs | 23 | 新增 9：`XhsNoteTop*`（详情顶栏四件套）、`XhsTopBarRow`、`XhsHomeFeedEdges`、`XhsMarketBackdrop`、`XhsNoteCapsule`、`XhsImageSaveContract`、`XhsOriginalImageExport` |
| YouTube | 20 | **删 13**（HomeLayout/TopBar 等旧实现）、新增 `YouTubePivotBinding.kt` / `YouTubePivotRoles.java` |
| Apple Music | 17 | 全新适配（Artist 玻璃/光学/语义、MiniPlayer、Overflow、Search、Settings、TargetSpec） |
| 微信 | 15 | 新增 `WeChatFoldDexContract.java` / `WeChatNativeFoldAction.kt` |
| B 站 | 14 | 新增 `BilibiliBottomWidth` / `BilibiliHomeFeedEdges` / `BilibiliHomeGlassScene` / `BilibiliSearchFeedback`；**删 `bilibili_island` 两件** |
| ChatGPT | 13 | 新增 `ChatGptBottomBar` / `ChatGptDockMasks` |
| X | 12 | 回复页玻璃/光环/材质继续演进 |
| 淘宝 | 9 | 新增 `TaobaoCartRenderSurface`；删 2 |
| 贴吧 | 9 | 新增 6：BottomArtwork / ChannelContract / EdgeGradient / FeedBottomInsets / NativeTabAnimation / TopBar |
| 美团主端 | 8 | — |
| 抖音 | 8 | 新增 `TikTokAssistantVisibility`（同目录）等 |
| 小米应用商店 | 7 | 新增 `MiMarketBackdrop` / `ProfileRecommendations` / `UpgradeRecommendations`；删 20+ 图标资源 |
| PDD | 7 | 新增 3；删 2 |
| Quora | 7 | 新增 2；删 2 |
| Reddit | 7 | 全新适配 |
| TikTok | 6 | — |
| 微博 | 6 | 新增 `WeiboEmbeddedIcons` |
| 小米健康 | 5 | 删 3 |
| Spotify | 5 | 删 1 |

### 1.2 通用层 / 渲染层

- **新增 `hook/topbar/` 通用顶栏**：`TopBarChannelHost.kt`、`TopBarTabLayout.kt`、`HomeChannelProjection.java`——这是"频道条按原生投影槽位"的通用方案，直接与我们 bilitop1/2 的自研实现撞车。
- `AdapterNavigationFrame` 新增字段：`detachedDirectSelection`、`topBarTabs`，并配套 `channelCenter()` / `channelEdge()`。
- **rendering 新增 10 件**：`BoundedContourGpuProbe`、`BoundedContourShader`、`ColdTapMotionGate`、`ContinuousEdgeFalloff`、`ContourSearchBounds`、`ExactRenderInputs`、`GlassEdgeColorProbe`、`GlassEdgeHighlight`、`GpuHaloProbe`、`InsetIndicatorOutline`（另有 `SliderTrackGeometry.java` 在 ui/ios）。
- Manager UI 新增 3：`ManagerGlassEdge.kt`、`ManagerInsetIndicatorShape.kt`、`ManagerSliderGesture` 侧改动。

### 1.3 作用域 / 默认值 / 构建

- `scope.list`：33 款。**移除 `com.android.systemui`**，新增 `com.apple.android.music`、`com.reddit.frontpage`。（我们本地仍是 32 行含 systemui 的旧清单）
- 新装默认值：底栏 56 dp、底色 50%、模糊 0、高光描边 60%/高光透明度 45%、折射 120%、粘滞 25%；滑块放大不额外加模糊；浅色滑块细描边；默认开底栏无字模式。
- `app/build.gradle.kts`：`versionCode 225 / versionName "2.1.2"`，`jniLibs.useLegacyPackaging = false`，无签名配置（作者不提供密钥）。
- `RELEASE.md`：明确不含超级小爱 / 小米桌面 / 小米相册 / 小米系统界面；顶栏布局创意来自酷安 @小郑大王。

---

## 2. 我们自己的补丁全集（相对 2.1.0：49 文件，+2623 / −241）

13 个新增文件：

- B 站：`BilibiliBottomArtwork.kt`、`BilibiliComposeBottomArtwork.kt`、`BilibiliComposeTabContract.java`、`BilibiliBottomIconFit.java`、`BilibiliDetailCover.kt`
- 抖音：`DouyinFirstFeedBinding.kt`、`DouyinPadSplitPages.kt`
- 小红书：`XhsLifecycleTrace.kt`
- 工具：`tools/BilibiliBottomIconFitTest.java`、`tools/BilibiliComposeTabContractTest.java`、`tools/check_douyin_first_feed.py`、`tools/test_douyin_first_feed.py`
- 文档：`README-MINE.md`

36 个修改文件覆盖：配置链（ConfigContract / GlassConfig / LocalSettings / SettingsProvider / ModernSettingsBridge / HookConfigReader / StartupPreferencesPolicy / HookEntry / MainActivity）、`GlassHostLayout`、`TargetAdapter`、B 站 14 件、小红书 8 件、抖音 3 件、home_glass 2 件、MiuixUi、构建两件。

按版本线的主题（详见 README-MINE）：

| 版本段 | 主题 | 现状（相对 2.1.2） |
|---|---|---|
| 224–225 | xhs 热搜胶囊接管、顶栏高度可调 | 上游**没有**，仍是我们独占 |
| 235–238 | coverfix3、mirrorfix、hostfallback、prefsfallback | 上游**没有**宿主存档/重试链（`liquidtab_last_good_config` 全无） |
| 239 | 详情三按钮对齐（48dp 统一、可见字形） | 上游同名 `measureVisibleGlyph` 存在但实现不同，仍需复核 |
| 241–243/260–262 | B 站 cover、槽位投影、宽频道条 | 上游用 `hook/topbar` + `topBarTabs` 重做，**架构级撞车** |
| 265–268 | 信封黑边、原生皮肤图标、对比度 | 上游 `BilibiliBottomWidth`/`HomeGlassScene` 重新实现，撞车 |
| 270–272 | 抖音 pad 分屏、B 站竖屏评论 | 上游没有 pad 分屏；竖屏评论上游另有解法（`scene`/`isWithin`） |
| 273–279 | XHS 视频详情 cover、返回门禁、落盘探针 | 上游**完全没有**（`XhsDetailCover`/`COVERED_PAGES`/`xhsReturnTrace` 均 ABSENT） |

---

## 3. 冲突全量清单（15 文件 / 37 块）

| # | 文件 | 块数 | 冲突性质 | 建议 |
|---|---|---|---|---|
| 1 | `app/build.gradle.kts` | 1 | `versionCode/Name`：上游 225/2.1.2 ↔ 我们 278/279 | 取上游新基线，本地另起号段（如 280 起）并保留 `xhsReturnTrace` 开关、lint 关闭、`installOver` 签名块（这三块无冲突已自动合入） |
| 2 | `gradle.properties` | 1 | 仅 `org.gradle.java.installations.paths=C:/jdk-21.0.9+10` | 保留我们的（上游是清理机器路径）；按本机 JDK 实际路径 |
| 3 | `ConfigContract.kt` | 1 | 上游加 `KEY_XHS_EDGE_TO_EDGE_FEED`，我们加 4 键 | 两边全留；`KEY_YOUTUBE_TOP_BAR` 在上游已不存在 → 确认后可删 |
| 4 | `GlassConfig.kt` | 4 | 默认值 + 钳制区间：上游改了 alpha/描边默认并新增 `highlightTransparency` 钳制，我们的 clamp 基于老默认 | 以**上游**默认/钳制为准，只插入 `xhsTopHeight` 字段、put/get、钳制一行（注意我们旧行 `lightAlpha`/0.40 是过时写法） |
| 5 | `LocalSettings.kt` | 2 | 同上：键读写同点插入 | 上游键 + 我们的键都写 |
| 6 | `HookConfigReader.kt` | 1 | 同上 | 同上 |
| 7 | `TargetAdapter.kt` | 1 | `AdapterNavigationFrame` 字段对撞：上游 `detachedDirectSelection` + `topBarTabs` + `channelCenter/Edge()` ↔ 我们 `selectionArtworkDrawn` | 三个字段**并存**，两套方法都保留 |
| 8 | `GlassHostLayout.kt` | 7 | **最重**。上游重写了 `drawSelectionContent`：返回值改 Unit、新增 `combinedIndicatorNavigationClipped/ContinuousSample`、`artworkOnlyLens`、`continuousGlassSource`、`traceOpticalWork{}`、`topBarTabs` 分数映射；我们的实现依赖 `drawSelectionContent(...): Boolean` + `selectionArtworkDrawn` 同帧兜底 | 以上游新骨架为主，把"本帧镜内是否真的画了替代字"改成上游风格的字段（不要硬保留 Boolean 返回），hunk2 的 scene 参数表把该字段挂进上游新 map；改完必须真机看 XHS/B 站返回帧 |
| 9 | `BilibiliHomeLayoutHooks.kt` | 4 | 上游新增 `topBarTabs.fraction()` 比例、`bilibiliDetachedSearch` 顶栏、`updateTopBarTabs(sizing)`；我们的 `channelStripSpan` 全宽频道条 + `selectedIndex()!=null` + `BilibiliDetailCover` 闸门 | 二选一：优先上游投影 API（用 `fraction()` 替我们的比例换算），保留我们的"能放就保留原宽条"判定；`pauseBackdropWork` 合并为 `!session.active || (role==3 && detachedSearch) || BilibiliDetailCover.pauseBackdropWork(host)` |
| 10 | `BilibiliNavigationState.kt` | 1 | `draw()` 重写：上游走 `BilibiliRenderer.draw(..., searchFeedback.progress)`；我们加了 `glassOutline` clipPath + `artwork` 参数 | 以上游签名为准，把 clipPath 包在外层，artwork 由新渲染器内部取 |
| 11 | `BilibiliRenderer.kt` | 2 | 签名 `searchPress: Float` ↔ `artwork: BilibiliBottomArtwork?`；图标/文字基线被上游 `iconOnly` 逻辑重写 | 以上游为骨架，重新挂回原生图稿槽位（267/268 的 native icon + 1.22× 限幅需要在新基线重做） |
| 12 | `BilibiliStoryCommentHooks.kt` | 3 | 上游自带 `scene`（`cmt3_swipe_refresh` 归属判定）+ `isWithin` + `shadowHost` 结构，是同一问题的另一种解法；我们 271/272 的 `shadowHost=bar.rootView` 直接撞 | 采用上游 `Owner(source, scene, ...)` 结构，把我们的"阴影挂播放器 overlay 坐标系"重挂进上游 `scene` |
| 13 | `XhsDetailGlass.kt` | 6 | 上游新增 `XhsNoteTop.start/pause/dispose`（详情顶栏玻璃）并重做按钮字形测量（`measureVisibleGlyph`、`actionCapsule`）；我们 `COVERED_PAGES`（含 `DetailFeedActivity`）、`EXIT_SETTLE_MS=500`、48dp 统一 | 以上游为骨架，把我们的 `XhsDetailCover.enter/scheduleExit` 插进四个生命周期；**注意与 `XhsNoteTop` 的 start/pause/dispose 共存**，避免双重 hide/恢复 |
| 14 | `XhsNavigationState.kt` | 1 | 上游新增 `detachedDirectSelection` + `selectionPath` 内外分绘（从根上解决"镜内空白"）；我们的 `selectionArtworkDrawn` 同帧兜底 | 优先用上游新路径；我们的兜底可保留为 fallback（`!selectionPath.isEmpty` 分支之后再判断） |
| 15 | `XhsTopBar.kt` | 3 | 上游用 `measuredTitle/measuredTextSize` 驱动宽度 + 新增 `XhsTopBarRow`；我们加 `hotSearchSource`、`barDp` 可调高度、手动 place 顶栏 | 融合：宽度用上游 `measured*`，把 `barDp`（32–56dp）替换上游写死 40dp 的高度，`hotSearchSource` 的 hide/点击保留 |

---

## 4. 自动合并但语义重叠（10 个，必须人工复核）

| 文件 | 我们 | 上游 | 风险 |
|---|---|---|---|
| `MainActivity.kt` | +4 | −1 | 极低（入口顺序） |
| `SettingsProvider.kt` | +4 | +32/−4 | 上游新增键的投影，别覆盖 |
| `HookEntry.kt` | +3 | −5 | 上游删了入口注册行，可能与我们的注册重名 |
| `BilibiliAdapter.kt` | +9/−10 | +19 | 适配器注册/信号表，需核对 target 版本号 |
| `BilibiliStoryDanmakuGlass.kt` | +28/−16 | +10/−76 | 上游大删，我们 272 的局部阴影可能已被取代 |
| `DouyinNavigationState.kt` | +115 | +4/−1 | 我们 270 pad 分屏主逻辑，被上游改动影响面小但需真机 |
| `XhsAdapter.kt` | +4 | +8 | target 版本门槛，可能双份 |
| `XhsTopMaterial.kt` | +3/−1 | +6/−2 | 顶栏材质参数，需对齐上游新光路 |
| `XhsVideoViewport.kt` | +19 | +12/−6 | 视频笔记详情类名/边界，273 与其相关 |
| `MiuixUi.kt` | +32 | +124/−36 | 管理页 Miuix 大改，我们的 32 行要重挂 |

---

## 5. 完全不与上游相交、可干净落地的补丁（24 文件）

`.gitignore`、`README-MINE.md`、`ModernSettingsBridge.kt`、`StartupPreferencesPolicy.java`、`BilibiliBottomArtwork.kt`、`BilibiliBottomIconFit.java`、`BilibiliComposeBottomArtwork.kt`、`BilibiliComposeTabContract.java`、`BilibiliDetailCover.kt`、`BilibiliHomeChannels.java`、`BilibiliHomeTopVeil.kt`、`BilibiliOpticalForeground.kt`、`DouyinAdapter.kt`、`DouyinFirstFeedBinding.kt`、`DouyinPadSplitPages.kt`、`DouyinSplashHooks.kt`、`ComposeHomeBinding.kt`、`HomeTopGradientVeil.kt`、`XhsLifecycleTrace.kt`、`XhsTopBinding.kt`、以及 4 个 tools 脚本。

> 注意：`XhsTopBinding.kt` 虽可干净落地，但上游新增的 `XhsNoteTopBinding` 已接手详情顶栏，两个 Binding 职责要划清，否则可能双绑。

---

## 6. 上游删除项对我们的连带影响

- `bilibili_island/*` 两件删除 → B 站"超级岛联动"整条链路消失；`scope.list` 也移除了 `com.android.systemui`。我们 README 里的"B 站超级岛联动"提法要改。
- YouTube 旧 13 件删除 + Pivot 新方案 → 我们的 `ios.youtube_top_bar` / `KEY_YOUTUBE_TOP_BAR` 在 2.1.2 中**已无任何读取方**，合并时应删除或迁移到 Pivot。
- `mi_health` 删 3（`MiHealthDeviceTopVeil.kt.txt` 等归档件）→ 不影响我们（我们不碰 mi_health）。
- 小米市场 / QQ 图标资源大量删除 → 资源引用编译期就能暴露问题，构建即可发现。

---

## 7. 建议的合并路径（分级 + 验证）

### 第 0 步：先量化"哪些补丁已经不需要"
用**纯上游 2.1.2 + 只加签名配置**构建一个 `versionCode=280` 的包，同签名覆盖上机，跑三条回归：
1) XHS 图片/视频笔记 → 返回动画 +「首页」标签；
2) B 站首页 → 详情/竖屏 → 返回；底栏图标与顶栏频道条；
3) 抖音冷启动首条推荐文案/右栏；内屏开评论底栏左移。

结果决定：`detachedDirectSelection`、`XhsNoteTop`、`BilibiliBottomWidth/HomeGlassScene` 是否已经把我们对应补丁"替代掉"。凡是上游已解决的，直接弃用我们的实现，工作量立减一半。

### 第 1 步（机制层，必须先定）
`TargetAdapter` → `GlassHostLayout`（7 块）→ 配置链 5 文件。这一层不确定，后面 adapter 全都编译不过或行为随机。

### 第 2 步（B 站，4 冲突 + 2 复核）
`BilibiliHomeLayoutHooks` → `BilibiliNavigationState` → `BilibiliRenderer` → `BilibiliStoryCommentHooks`；复核 `BilibiliAdapter`、`BilibiliStoryDanmakuGlass`。

### 第 3 步（小红书，3 冲突 + 2 复核）
`XhsDetailGlass` → `XhsNavigationState` → `XhsTopBar`；复核 `XhsTopMaterial`、`XhsVideoViewport`。

### 第 4 步（抖音 + 其余）
`DouyinNavigationState`（自动合并）+`FirstFeedBinding`/`PadSplitPages`（独立文件）。

### 第 5 步（构建与发布口径）
- `versionCode`：别再沿用 279 这种"补丁号"，改成 `2.1.2` 基线 + 本地递增（280 起）。
- `versionName`：`2.1.2-mine1` 之类，保留 `-xhs-return-trace` 后缀作为可选开关。
- `scope.list`：以 33 款为基线，**保持不含 systemui**（与设备现状一致）。
- 签名：`shared/signing/debug-115ed9.keystore`（debug B）不变；`installOver` 块保留。
- `README-MINE.md` 需要重写"相对上游"的基线（现在是 v2.1.0，要改成 v2.1.2 + 我们的差异清单）。

### 工作量预估
15 个冲突里：3 个机械（build/gradle/properties、配置链 3 个），1 个重（GlassHostLayout），B 站 4 个中 2 个偏重（Renderer / HomeLayoutHooks），小红书 3 个中 `XhsDetailGlass` 偏重。**净编码约 2–3 天，验证 2–4 天**，前提是第 0 步能砍掉一部分。

---

## 8. 现场事实与限制

- 设备 `10AG632QLS002CF`：**当前 ADB 已掉线**（本次检查时 `adb devices` 为空；此前在线时读到的是 279 / `2.1.0-xhs-return-trace`，`lastUpdateTime=2026-11-21 21:21:30`）。
- 上游 APK 与我们的 debug B 证书不同，**不能 `adb install -r`**；按根 AGENTS §1.0，禁止 `uninstall` 绕行 → 只能源码自签覆盖。
- 上游 `main` 分支仍停在 v2.1.0 提交（`e54cdea`），2.1.2 只挂在 tag/Release 上，`git pull` 拿不到。
- 除本报告文件本身外，本次审计未改动 `forks/liquidtab-mine`；仅执行过 `git fetch upstream --tags`（新增 3 个 tag 引用），工作树其余部分未动。
