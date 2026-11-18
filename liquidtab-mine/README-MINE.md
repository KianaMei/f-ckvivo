# liquidtab-mine — LiquidTab 本机维护 fork

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
