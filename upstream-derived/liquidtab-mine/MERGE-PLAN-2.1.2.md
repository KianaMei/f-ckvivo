# LiquidTab 2.1.2 合并计划（2026-11-24 定稿）

> 前置文档：[UPSTREAM-2.1.2-AUDIT.md](UPSTREAM-2.1.2-AUDIT.md)（冲突全量清单与逐文件建议）。
> 本文是执行计划，审计是证据底稿。冲突块数、文件清单以审计为准，本文不重复罗列。

## 0. 现场事实（计划前提）

- 上游最新 = **v2.1.2**（2026-11-22，versionCode 225），之后无新 Release。
- 本机基线 = v2.1.0 + 224→279 补丁，整树入库于 `upstream-derived/liquidtab-mine/`。
- **X Fold6（10AG632QLS002CF）当前不在线**：机上装着 279 / `2.1.0-xhs-return-trace`，278 待复验、279 探针日志未回收。
- **V2337A（10AE6E28Y10011K）在线但不能当实验机**：无 su、无 Vector（仅 KernelSU 管理器 App），未装 LiquidTab；直板机（1172×2748），无法验证折叠/内屏场景。
  - 该机宿主版本信号：B 站 **9.20.0**、抖音 41.2.0 versionCode=**410201**、小红书 9.54.0。
  - 推论：277 抖音首条绑定写死 `versionCode=410200` 在新版宿主上不命中，合并时必须放宽（见批 3）。
- 全部合并验证只能等 X Fold6 在线；批 1–3 纯离线可做。

## 1. 三个策略决策（已与用户确认）

### 1.1 不做「纯上游包上主力机回归」

审计原第 0 步修正为**静态判断 + 合并后真机验证**：

- 纯上游包没有 coverfix（XHS 返回动画必回退）、没有四层配置兜底（划掉模块 App 玻璃失效）、没有 B 站皮肤图标，让日常主力机回退换信息，代价大收益小。
- 「上游新机制是否已替代我们的补丁」大部分可读码静态判定；存疑的一律保留我们的实现作 fallback，留到批 4 真机对比时再砍。

### 1.2 合并方向：上游新骨架为主，我们的补丁按主题重挂

上游重写了选中绘制内核（`drawSelectionContent` 改 Unit 返回、`detachedDirectSelection`、`selectionPath` 内外分绘），守旧骨架会被两套光路撕扯。取舍原则：

| 类别 | 处理 | 涉及 |
|---|---|---|
| 上游已重做、可能替代 | 先用上游实现，我们的留作 fallback 或标记弃用 | B 站宽频道条（上游 `topBarTabs`）、选中镜内空白（上游 `selectionPath`）、竖屏评论（上游 `scene`/`isWithin`） |
| 我们独占、用户已验收 | **必须重挂，不许丢** | coverfix3/273/278（XHS+B 站返回动画）、四层配置兜底（236–238）、B 站原生皮肤图标+对比度（267/268）、XHS 顶栏高度+热搜胶囊（224/225）、抖音首条绑定（277）+pad 分屏（270） |
| 上游删除 | 清理 | `KEY_YOUTUBE_TOP_BAR` 孤儿键；scope.list 用上游 33 款（保持不含 systemui） |

### 1.3 版本与签名口径

- versionCode **280 起**，versionName `2.1.2-mine1` 起；`xhsReturnTrace` 保留为可选构建开关。
- 保留：lint 关闭、`installOver` 签名块、debug B 证书（`115ed98e…`）。**签名红线不动**。
- `gradle.properties` 保留本机 JDK 路径。

## 2. 执行批次

### 批 1｜三方合并骨架 + 机制层（离线，门槛：编译通过）

1. `cache/` 下建临时三方仓库：base=2.1.0 source zip、ours=当前工作树（排除 build/.gradle/产物）、theirs=2.1.2 source zip；`git merge` 自动合 34 文件，结果落回 `upstream-derived/liquidtab-mine/`。
2. 手工解冲突，顺序：
   - `app/build.gradle.kts` / `gradle.properties`（机械，按 §1.3）
   - `TargetAdapter.kt`：`detachedDirectSelection` + `topBarTabs` + `channelCenter/Edge()` 与 `selectionArtworkDrawn` **三字段并存**，两套方法都保留
   - `GlassHostLayout.kt`（7 块，最重）：以上游新骨架为主，「本帧镜内是否真画了替代字」改挂成上游风格字段，不硬保留 Boolean 返回；scene 参数表挂进上游新 map
   - 配置链 5 文件（ConfigContract / GlassConfig / LocalSettings / HookConfigReader / SettingsProvider）：上游默认值与钳制为准，只插入我们的键（`xhsTopHeight` 等 4 键）；删 `KEY_YOUTUBE_TOP_BAR`
3. 每解完一层跑一次 `./gradlew :app:assembleRelease`，不攒到最后。

### 批 2｜B 站（4 冲突 + 2 复核）

- `BilibiliHomeLayoutHooks`：用上游 `topBarTabs.fraction()` 投影替代我们的比例换算，保留「能放就保留原宽条」判定；`pauseBackdropWork` 合并为 `!session.active || (role==3 && detachedSearch) || BilibiliDetailCover.pauseBackdropWork(host)`
- `BilibiliNavigationState` / `BilibiliRenderer`：上游签名为骨架，clipPath 包外层；重挂原生图稿槽位 + 1.22× 限幅（267/268 在新基线重做，本批主要工作量）
- `BilibiliStoryCommentHooks`：采用上游 `Owner(source, scene, …)` 结构，272 的「阴影挂播放器 overlay 坐标系」重挂进上游 `scene`
- 复核：`BilibiliAdapter`、`BilibiliStoryDanmakuGlass`（上游大删，272 局部阴影可能已被取代）

### 批 3｜小红书（3 冲突 + 2 复核）+ 抖音

- `XhsDetailGlass`（6 块）：上游骨架 + `XhsDetailCover.enter/scheduleExit` 插四个生命周期；**重点处理与上游 `XhsNoteTop` 的 start/pause/dispose 共存**，避免双重 hide/restore；48dp 统一按钮高度保留
- `XhsNavigationState`：优先上游 `selectionPath` 路径；我们的兜底保留在 `!selectionPath.isEmpty` 之后
- `XhsTopBar`：宽度用上游 `measured*`，`barDp`（32–56dp）替换写死 40dp，`hotSearchSource` hide/点击保留
- 复核：`XhsTopMaterial`、`XhsVideoViewport`、`XhsTopBinding`（与上游 `XhsNoteTopBinding` 职责划清，防双绑）
- 抖音：`DouyinNavigationState`（自动合并）+ 两个独立文件；**277 的 versionCode 判定从 `==410200` 放宽**（≥410200 或改语义判定，改前重核对宿主类 `X.0zm2` 与 `LIZ()` 语义）

### 批 4｜真机验证（等 X Fold6 在线；装机三条件：解锁、用户在场、仅 adb install）

| 宿主 | 验证项 |
|---|---|
| XHS | 图片/视频笔记返回动画 + 「首页」标签；快开快退；后台 5 分钟回来返回；回收 278 探针日志（`liquidtab-xhs-return.log`） |
| B 站 | 详情/竖屏返回动画；皮肤图标+对比度；宽频道条；竖屏评论悬浮栏+残影；顺手复测「BV 号区起滑无效」 |
| 抖音 | 冷启动首条文案/右栏（`tools/check_douyin_first_feed.py`）；内屏 pad 分屏底栏左移 |
| 配置链 | 划掉模块 App → 宿主冷启动玻璃照常（四层兜底） |

### 批 5｜收尾

- README-MINE 基线改写为 v2.1.2 + 差异清单；审计文档标记已合并项；本计划标记完成度。

## 3. 风险与回退

- 两个最大风险点：**GlassHostLayout 7 块**、**XhsDetailGlass 与 XhsNoteTop 共存**——解完先单独上机，不攒着。
- 全程分支操作；回退点 = 当前 279 源码树（git 已有）。
- 设备红线全程适用：默认只读；装机三条件；禁 `pm install` 绕行、禁卸载换签。
- 预估：批 1–3 净编码 2–3 天（离线），批 4 验证 1–2 天（取决于 X Fold6 何时在线）。

## 4. 进度追踪

- [x] 批 1：三方合并 + 机制层 + 编译通过（2026-11-23）
- [x] 批 2：B 站（2026-11-23）
- [x] 批 3：小红书 + 抖音（2026-11-23）
- [x] 批 4：X Fold6 真机验证矩阵（2026-11-23 主体通过，B 站剩余观察项转日常）
- [ ] 批 5：文档收尾（README-MINE 基线提示已加；224–279 台账保留作档案）

## 5. 批 1–3 完成记录（2026-11-23）

### 产物

- 合并工作区：`cache/liquidtab-merge/repo`（三方仓库 base=2.1.0 / ours=本地 279 / theirs=2.1.2，merge commit `6800d97`）。
- 已同步回 `upstream-derived/liquidtab-mine/`（826 个文件变更，含上游新增/删除）。
- **280 包**：`upstream-derived/liquidtab-mine/build/LiquidTab-280-upstream-2.1.2-mine1.apk`
  - versionCode=280 / versionName=`2.1.2-mine1`；`xhsReturnTrace=true` 时为 281 / `-xhs-return-trace`。
  - 签名 = debug B（SHA-256 `115ed98e…`），与机上 279 同证书，可 `adb install -r` 覆盖。
  - APK SHA-256 `5934b0c8c1ff0956d8b49f1d1c322ae1079a048bede3606de4c307055a679de8`。

### 验证（离线）

- `:app:compileReleaseKotlin` 通过；`:app:assembleRelease` 通过。
- JVM：`BilibiliComposeTabContractTest` PASS、`BilibiliBottomIconFitTest` PASS；python 抖音首条脚本 4/4 OK。
- 注意：构建签名块按 `rootProject/../../shared/signing` 解析；merge 工作区比原目录深一层，需把 keystore 补到 `cache/shared/signing/`（已做），否则静默回退到 `~/.android/debug.keystore`（第一次产物就是错签，已纠正）。

### 关键合并决策（双轨实现）

- **GlassHostLayout**：保留我们的 `selectionArtworkDrawn` Boolean 追踪链，叠加上游 `indicatorArtworkSubmitted`/`detachedDirectSelection`/`topBarTabs`；`drawHiddenTabsBackdrop` 保留我们的 `navigationRecorded` 返回值 + 上游新参数全收。修复了一个结构坑：上游 `traceOpticalWork("BackgroundRecord")` 的闭合 `}` 在冲突块 theirs 侧，漏补会导致类体不闭合、866 个级联 unresolved。
- **配置链**：上游默认值/钳制为准 + 我们的 `xhsTopHeight` 等 3 键 + `xhsEdgeToEdgeFeed`；`youtubeTopBar` 整链删除（ConfigContract/GlassConfig/LocalSettings/HookConfigReader，无其它引用）。
- **B 站**：`BilibiliRenderer.draw` 双参数（`artwork` + `searchPress`）；`glassOutline` clip 包在外层；HomeLayoutHooks 保留我们的 `channelStripSpan` 宽条 cluster 摆放 + 补回 `side/width` 定义与 `symmetricGlassSideMarginPx` import，叠加上游 `updateTopBarTabs(sizing)`；`pauseBackdropWork = !active || (role==3 && detachedSearch) || (role in 0..3 && DetailCover)`。
- **小红书**：`XhsNavigationState` 上游 `detachedDirectSelection` 优先、我们的 `selectionArtworkDrawn` mix 兜底在后；`XhsDetailGlass` 四个生命周期 = `COVERED_PAGES` cover + `XhsNoteTop` 共存（NoteTop 仅 NOTE_DETAIL）；按钮绘制采用上游 `Source`/`actionCapsule` 结构，但 `availableHeight` 去掉 min 恢复 48dp 统一（239 语义注入）。
- **XhsTopBar**：保留手动 `place(...)` + `hotSearchSource` hide/点击；`ChannelAction` 同时有上游 `preferredWidth`（测字缓存）与我们的 `onMeasure`（`barDp` 高度）。
- **抖音**：首条绑定门槛 `versionCode in 410200..410299 && versionName=="41.2.0"`（反射语义校验仍兜底），覆盖 410201。

### 批 4 真机重点风险（按优先级）

1. **GlassHostLayout 双轨光路**：XHS/B 站返回帧是否还缺字、折射是否正常——两套同帧机制叠加可能重复裁剪或重复绘制。
2. **XhsNoteTop 与 XhsDetailCover 共存**：详情顶栏玻璃与返回采样闸门是否互相 hide/restore 打架。
3. **B 站顶栏**：我们的 cluster 宽条摆放与上游 `topBarTabs` 投影并存，可能出现槽位/命中错位（261/262 的回归路径）。
4. **详情三按钮**：48dp 统一在上游 `actionCapsule` 堆叠结构下是否仍对齐（239 回归）。
5. **XHS 顶栏**：手动 place 与上游 `XhsTopBarRow` 是否重复布局；`barDp` 高度是否生效。

### 批 4 进行中记录

- **280 已装机**（2026-11-23 22:42，三条件满足，同签名覆盖 279）。用户反馈「基本都 OK」，仅顶栏返回后偶发退化到三槽、滑动恢复。
- **退化根因取证**（22:58 时间序列 400 帧）：正常帧 `candidates=6 widest=120 needed=1065`；异常帧 `candidates=7 widest=240 needed=2174.1 > maxSpan=1883.7 keptWide=true`——B 站动态插入**超宽运营热词候选**（文字宽 2 倍于常规标签），`needed` 超预算。宽条已建立时 262 的 `keptWide` 能接住；返回后宽条未建立时热词已在候选 → 直接 pins 三槽，滑动重算时热词撤下恢复。
- **282 修复**（2026-11-23，已装机）：`BilibiliHomeChannels.trimOverWide()` 剔除非 pins/非选中且宽度 > pins 最宽 1.5 倍的运营候选（原生 pager 与「更多」不受影响），不再让热词拖垮整行宽条；`channels.txt` 增加 `full/trim/mode` 字段 + `liquidtab-channels-history.txt` 最近 20 条环形历史，供偶发退化事后取证。JVM `BilibiliHomeChannelsTrimTest` PASS。APK `build/LiquidTab-282-bili-campaign-trim.apk`，SHA-256 `46cc2488…b6a0`，versionCode 282 / `2.1.2-mine2`，debug B 同签覆盖。
- **待观察**：用户日常使用中热词出现时顶栏是否仍退化；若再出现，读 `liquidtab-channels-history.txt` 定位下一层原因。B3 返回动画 / B4 竖屏评论 / B5 遗留项继续分批验证。
- **XHS 验收**（2026-11-23）：9.55.1 上玻璃渲染正常（顶栏 tab/推荐胶囊/搜索钮、底栏标签与发布键），图片/视频笔记返回动画与「首页」标签用户确认正常。宿主已从 9.54.0 升到 9.55.1。
- **抖音验收**（2026-11-23）：versionCode=410200，首条推荐与 pad 分屏用户确认基本 OK。
- **批 4 主体完成**：B 站（282 顶栏修复后）、XHS、抖音均经用户真机确认。B 站 B3 返回动画/B4 竖屏评论/B5 遗留（BV 号起滑、折叠换视频、信封黑边）转入日常观察项，不阻塞主线。
