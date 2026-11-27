# xhook —— X/Twitter 模块审计与自研

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

> **最新进度（2026-11-27，XHook `0.4.1` / `versionCode 15`，已编译验签待授权装机）**：
> - **机上现状**：设备运行 `com.twitter.android` **`12.45.1-prod.01`** (`312451001`) + `dev.xhook` **`0.4.0` (`14`，SHA-256 `ad744ffc…252ab`)** + `io.github.offlineglass` (`LiquidTab 2.1.2-mine9` / `289`)，LSPosed 2.2.1 均启用。
> - **`0.4.1` 实测热修（修复 `0.4.0` 装机后下载按钮不见的问题）**：
>   1. `PlayerButtons.DragController` 在按钮 `View.GONE` 时读 `button.getWidth()==0` 算出 `leftMargin = screenWidth - 12dp` 把按钮推出右边界外，且 `GONE -> VISIBLE` 未重算位置；现改为 `lp.width` 兜底 + 布局变更/显隐切换自动钳制并置顶（默认 `sidePad=16dp`、`bottomSafe=156dp`、`x=1.0, y=0.62`）；
>   2. `PlayerTracker.findMediaItemLocalConfigUri` 适配 `X 12.45.1` 的 `MediaItem (x0)` 含 3 个带 `Uri` 子对象（`s0` LocalConfiguration、`a1` MediaMetadata、`t0` RequestMetadata），以「唯一 `Uri` + `List` + `long` + 无 `Bundle`」精确锁定 `LocalConfiguration`；并以可见 `PlayerView` 面积为主依据兼容 X 的 `AsyncSurfaceTextureView`；
>   3. 产物 `cache/scratch/XHook-0.4.1-debug.apk`（SHA-256 `814398c277ec0ce14fee688deb57d02e1abda57a51e23a38c75debb387c0ec5d`，`zipalign` + `apksigner` 同签 `1990df77…7212fb` 通过）。
> - **① 上游更新审计（相对 `2026-11-13` 基线，10 仓全检）**：
>   - **有新发版 / 新提交（4 仓）**：`Re:X` (`1.8.2 → 1.8.4`，`2026-11-22`，适配 X `12.45.1` 侧边栏/Spaces/首页 Tab)；`x-video-save` (`main` 新增 3 个 commit `2026-11-20~21`：适配 X 12.44+、按签名解析回放 Hook、从可见 `Media3 PlayerView` 精准识别当前视频)；`birdify` (MIT，`v1.3.0 → v1.3.1`，`2026-11-19`：适配 X `12.44.0`、`Unsafe+toString` 抗 R8 重排找 `showAskGrokButton`、数据层 `itemBuilder` 零空行去广告与 `FeatureSwitchesRepositoryImpl` 净化)；`piko` (GPL-3.0，`dev` 分支持续更新至 `2026-11-26` / `v3.12.0-dev.6`，适配 NewX `12.44/12.45.1`，确认 X `12.45` 已移除旧版 Home `(ZZ)Z` 返回刷新并改为顶栏双击开关)。
>   - **无代码更新（6 仓）**：`TwitterHideAds` (`v3.1.0`，仅 `11-13` 合入文档 PR)、`x-video-catcher` (`v1.58.0`)、`X-ADBlockModule` (`v0.1.5`)、`TwiFucker` (`v0.1.2`)、`X-region` (`v0.1`)、`XShortLinkCleaner` (`v1.0.0`)。
> - **旧版 `0.3.1` 在机上 `X 12.45.1-prod.01` 离线实测断链点（已修复）**：
>   1. `Discovery.findListLambdas` 硬编码 `com.x.urt.v1` 且限定 `com.x.android.main`，而 `12.45.1` 条目基接口已改名 `com.x.urt.r2` 且主列表 lambda 被 R8 跨包合并进 `androidx.compose.material.r0`，导致渲染去广告主路径断链（0 命中）；
>   2. `Discovery.findRenderBoundaries` 仅扫 `com.x.urt.items.post`，漏掉被 R8 跨包合并进 `mlkit_vision_*` 与 `com.x.video.tab.*` 的 `k6` 渲染边界且未对 callers 做 `deoptimize`；
>   3. `RefreshGuard.findHomeReselect` 返回 0（X `12.45` 已移除旧 `(ZZ)Z` 处理器，改为顶栏双击开关 `home_timeline_navigation_tap_to_refresh_at_top_enabled`）；
>   4. `spamFilter` 挂在默认禁用的旧 `TimelineFilter` 上，在 `12.42/12.45.1` 实际完全不工作；
>   5. `MediaCapture.latest()` 易受信息流预加载干扰下错视频，悬浮按钮离场滞留 20s 且位置固定偏右下。
> - **② `XHook 0.4.0` 合并成果与验证**：
>   - **DexKit 双指纹持久化缓存**（`TargetCache`）：按宿主 APK + DEX CRC + 模块双指纹缓存在宿主 `cacheDir/xhook-targets-v2.properties`（常驻缓存登记，随系统清缓存自动回收），命中后跳过 `libdexkit.so` 加载，冷启动降至数毫秒；
>   - **数据层 + 开关层 + 渲染层三重去广告**：新增 `TimelineItemFilter` 拦截唯一数据构建咽喉 `itemBuilder`（`12.45.1` 唯一命中 `org.chromium.support_lib_boundary.util.b#T`，零空行移除推广帖与 `UrtTimelineRtbImageAd`）；新增 `FeatureSwitchHook` 关闭 Google SSP 广告、Premium 推销及可选帖子 Grok 提示（**刻意保留底栏 `android_webview_grok_tab_enabled` 兼容 LiquidTab**）；修复 `RenderAdFilter` 全 APK 边界/动态基接口 `r2` 列表 lambda 查找与 `deoptimize(callers)`，并支持 `AvailablePost.showAskGrokButton` 隐藏；
>   - **机器人/垃圾帖规则引擎打通**：`Rules` 升级支持普通词（剥离 Unicode `FORMAT`/`CONTROL` 零宽字符）、`/regex/flags`、`@user` 屏蔽、`!@user`/`@@user` 白名单，直接挂载至 `TimelineItemFilter`（默认仅扫对话回复 `conversationthread-`，主页零开销）；
>   - **精准视频跟踪与下载升级**：新增 `PlayerTracker` 按结构动态绑定 `PlayerView` 与 `ExoPlayer`（不硬编码 `j0`），精准锁定当前可见/播放视频；`PlayerButtons` 改为仅可见视频时显示、支持自由拖拽记忆相对位置（默认靠右偏中 `x=1.0, y=0.65` 避开 LiquidTab `XFloatingFab` 与底栏）与实时百分比进度；`VideoDownloader` 支持多任务排队、3 次退避重试、直播流拒收与直链 MP4 轨道校验，临时分片位于 `cacheDir/xhook-dl-*` 并在 `finally` 彻底删除；
>   - **链接净化（零混淆依赖）**：新增 `LinkCleaner`，拦截 `ClipboardManager#setPrimaryClip` 与 `Instrumentation#execStartActivity`，自动剥离 `x.com`/`twitter.com` 分享追踪参数（可选替换 `fxtwitter.com`/`vxtwitter.com`/`fixupx.com`/`twitter.com`），后台直达外跳 `t.co` 短链；
>   - **构建与测试**：Gradle 9.6.1 `:app:assembleDebug` 通过，产物 `cache/scratch/XHook-0.4.0-debug.apk`（4.9 MB，SHA-256 `ad744ffc7f73eb3527c8cd329de9b7c4603948daa7f7ed98e81cbf0ef25252ab`），`zipalign -c -p 4` 与 `apksigner verify` 通过（同签证书 `1990df77…7212fb`），19 项 JVM 单元测试全部 PASS。
> - **③ 与 `LiquidTab` (`io.github.offlineglass`) 的 Hook 重叠对账**：
>   - **X 业务类/方法级：100% 零重叠**。LiquidTab 在 `com.twitter.android` 上仅挂接 27 个 UI/导航/回复框 Haze 毛玻璃方法（`com.x.main.l0`、`com.x.ui.common.e3/z2`、`com.x.postdetail.r`、`com.x.composer.minimal.*`、`dev.chrisbanes.haze.*` 等），无任何去广告、媒体下载、刷新守卫或链接 Hook。
>   - **框架公共入口级**：仅 `Application#attach` 均为无害只读 `after` 观察者；Activity 生命周期 XHook 走官方 `ActivityLifecycleCallbacks`（零 Hook）。
>   - **两处潜在 UI / 开关层交互已主动规避**：① 悬浮下载按钮改为可见视频触发 + 避开右下角 `XFloatingFab` 与底部玻璃栏并支持拖拽；② `FeatureSwitchHook` 显式排除 `android_webview_grok_tab_enabled`，完整保护 LiquidTab 5 槽玻璃底栏与 `XGrokViewport` 契约。
> - **常驻缓存登记（按仓库顶栏规则）**：宿主 `cacheDir/xhook-targets-v2.properties`（DexKit 解析缓存）；宿主 `shared_prefs/xhook_ui_pos.xml`（悬浮球拖拽位置记忆）；宿主 `cacheDir/xhook-dl-*`（下载瞬时缓冲，`finally` 必删）。不写 `/sdcard` 或宿主 `files/`，无探针文件。

本目录做三件事：

1. **审计 Re:X（闭源）**，确认能不能放心用；
2. **拆解其它去广告 / 视频保存模块**，提取可复用的 hook 点；
3. **自研 XHook**，把“视频保存 + 推广过滤 + 机器人过滤”合成一个 libxposed 模块。

## 目录

```
xhook/
├── apks/                   下载的 APK（ReX、TwitterHideAds、x-video-save…）
├── unpack/
│   ├── rex-src/            ReX JADX 反编译（3,286 个 Java 文件）
│   ├── rex-smali/          ReX baksmali 反汇编（用于定位字符串/安装链）
│   ├── rex-res/            ReX 资源（manifest/strings/FileProvider 路径）
│   └── rex-dex/            classes.dex
├── src-upstream/           9 个模块的源码 zip + 解压目录
├── tools/                  下载 / 字符串扫描 / Java 语法检查脚本
├── notes/
│   ├── AUDIT-REX.md        ReX 1.8.2 静态安全审计（结论、证据、用法约束）
│   ├── MODULE-MATRIX.md    模块对比 + 去广告/机器人/视频 hook 点拆解
│   ├── PORTING.md          Re:X → XHook 移植台账（按功能组，含已实现/未实现）
│   ├── REX-FEATURES.md     Re:X 1.8.2 功能面/设置项清单
│   ├── X-BACKGROUND-KILL.md 回首页=进程被 single-cleaner 杀的系统层证据
│   ├── X-12.42-ANCHORS.md  **12.42 结构实测**：旧 entryId 锚点全失效、新锚点定位
│   ├── logcat-2026-11-15-xhook.txt  真机加载/命中日志（含 discovery=0 证据）
│   ├── now-x2.png          2026-11-15 22:14 真机截图（广告仍在）
│   └── s1–s9.xml / x3–x9.xml / window.xml   X UI dump（定位设置项/菜单用）
├── ours/                   XHook 源码（已编译通过）
│   └── app/build/outputs/apk/debug/app-debug.apk
├── out/                    编译产物副本（XHook-0.2.9-debug.apk）
├── work/                   本机反编译工作区（12.42 的 dex/baksmali，gitignore）
└── apks/                   下载的 APK + 从设备拉取的 base.apk（gitignore）
```

## 关键结论

### ReX 1.8.2 能不能用？

**能。** 静态审计未发现恶意行为：

- 权限只有 `INTERNET` + `REQUEST_INSTALL_PACKAGES`；
- 无遥测、无陌生域名、无动态加载、无隐私 API、无自定义 native；
- 更新链完整校验：大小 / SHA-256 / 包名 / 版本 / minSdk / **签名证书**，最后走系统安装器；
- 只有两条 root 命令：`pkill systemui`、`am force-stop/start com.twitter.android`，都是设置页按钮；
- 导出组件都有 UID/包名校验；HistoryProvider 处于 `enabled=false`。

风险在“闭源 + 自动更新 + SystemUI 作用域”，不在当前版本行为。详见
[notes/AUDIT-REX.md](notes/AUDIT-REX.md)。

### 设备（X 12.42.0-prod.01）兼容性

| 模块 | 12.42 可用性 | 说明 |
|---|---|---|
| ReX 1.8.2 | ✅ | release notes 已适配 X 12.43 |
| x-video-save 0.4 | ✅ | 明确适配 12.41/12.42 |
| TwitterHideAds 3.1.0 | ⚠️ 大概率 | 12.37 实测，DexKit 自适应 |
| X-ADBlock 0.1.5 | ❌ 需移植 | 硬编码 12.22 类名 |
| TwiFucker 0.1.2 | ❌ 需移植 | 目标 12.23 |
| x-video-catcher 1.58 | ❌ 需移植 | 目标 12.24–12.27 |
| Piko 3.11.1 | n/a | Morphe 补丁，不是 hook 模块 |

## XHook（自研）

- 视频下载：抓 `media3.datasource` 的 Uri → HLS 选档 → init+分片 → MediaMuxer → 相册；
- 去推广：0.2.9 用 DexKit 找“含 promoted/RTB 字符串且列表进出”的方法，按 entryId 前缀过滤；
  **在 X 12.42 已失效**（这些字符串在 dex 里已不存在，见下方“实测状态”）；
- 机器人：同批列表 hook + 关键词归一化匹配（默认只在详情页）；
- 刷新守卫：Home 重选、回前台生命周期刷新（对应 Re:X 的 Prevent automatic refresh）；
- **fail-open**：找不到目标/类型不匹配/异常，一律原样放行，不崩 X；
- 模块 App 零权限，无 root，无上报。

详见 [ours/README.md](ours/README.md)。

## 实测状态（2026-11-15 22:14，真机 X 12.42.0-prod.01）

模块已装（`dev.xhook` 0.2.9 / versionCode 11）、Vector 已启用（`enabled=1`，mid 298）。
启动日志（完整版见 [notes/logcat-2026-11-15-xhook.txt](notes/logcat-2026-11-15-xhook.txt)）：

| 功能 | 日志/现场 | 结论 |
|---|---|---|
| 模块加载 | `module loaded, process=com.twitter.android, api=102, framework=Vector 2.2` | ✅ 生效 |
| 视频 URL 抓取 | `MediaCapture installed, hooked=1` + 连续 `media captured id=… master=true` | ✅ 生效 |
| 下载悬浮按钮 | `PlayerButtons installed` | ✅ 已装（下载流程待复测） |
| 去广告 | 0.3.1：`RenderAdFilter installed … hooks=5` + 4 次 `skipped promoted item` | ✅ **生效**（0.2.9 完全无效，0.3.0 未命中） |
| 机器过滤 | `spamFilter=false` / `keywords=0` | ⏸ 未开启 |
| Home 重选守卫 | `Home reselect hook: com.x.home.tabbed.v#h deopt=true` | ✅ 已装（点按行为待复测） |
| 回前台刷新守卫 | `RefreshGuard installed: home=1 refresh=1` | ✅ 已装（回前台行为待复测） |
| 模块总耗时 | `feature install finished in 650ms` | ✅ |

**去广告失效原因（已定位）**：`Discovery.MARKERS` 依赖的 `promoted-tweet-` /
`promotedTweet-` / `rtb-image-ad-` / `superhero-` 在 X 12.42 的 15 个 dex（132.6 MB）
里**出现 0 次**，所以 DexKit 一个候选也找不到，过滤器不装 hook。12.42 改成了模型字段标记：
时间线帖模型带 `promotedMetadata` 对象（类 `com.x.models.ff`，`toString` 以
`TimelinePromotedMetadata(` 开头），UI 模型 `com.x.urt.items.post.g6` 直接持有该字段。

真机截图 [notes/now-x2.png](notes/now-x2.png)：@SUBWAY 的 `Ad` 帖仍在时间线最上方。
新锚点与修复方案见 [notes/X-12.42-ANCHORS.md](notes/X-12.42-ANCHORS.md)。

## 已知问题 / 下一步

1. **去广告（P0）✅ 0.3.1 已上机验证**：`RenderAdFilter` 用 DexKit 找 promoted-metadata
   类（`com.x.models.ff`，`toString` 前缀 `TimelinePromotedMetadata(`）与持有该字段的条目
   模型（`com.x.urt.items.post.g6`），挂三条渲染路径：条目接口默认方法 `l6#a`、
   Composer lambda `com.x.android.main.hl1#invoke`、帖子内部边界 `h#a`/`a2#a`/`a2#c`。
   实测命中 `hl1#invoke`（4 次 skip，无崩溃、无空行）。截图
   `notes/after-0.3.1-1.png` / `-2.png`，日志 `notes/logcat-0.3.1-verified.txt`。
   数据层方案（mapper + 空 presenter，`com.x.urt.b#a` / `z#u` …）留给后续。
2. 视频下载：0.2.9 已加“单码率媒体播放列表”fallback，未复测。
3. Home 重选 / 回前台刷新守卫：已命中但未做交互复测。
4. 回首页的根因是 `single-cleaner` 杀进程（hook 无解），见
   [notes/X-BACKGROUND-KILL.md](notes/X-BACKGROUND-KILL.md)。

## 复现

`ours/` 是自研模块的 Gradle 工程，`tools/` 存放工具源码，`notes/` 保留审计摘录。从本目录构建时运行下方命令。

```bash
cd ours && gradle :app:assembleDebug
```

本机样本默认位于 `cache/xhook/`，可通过 `LAB_CACHE` 切换缓存根目录。

- `apks/` 保存候选模块和 X 12.42 宿主样本，`src-upstream/` 保存九份对照源码 ZIP。比对已登记样本时核对标签和 SHA-256。[样本清单](tools/fetch-manifest.json)
- `work/x-12.42/` 保留原本地分析脚本 `dexapi.py` 和 `dexidx.py`。需要复核时从存档宿主 APK 重新解出 DEX。ReX 的本地展开结果放在 `unpack/rex-src/`，对应脚本读取其 `sources/`。
- `out/` 和 `gradle-apks/` 存放保留的签名包，`.cache/` 存放本机私有诊断快照。
- `python -I tools/fetch_upstream.py` 查询当前 GitHub Release 和默认分支，使用环境变量 `GH_TOKEN`。新抓取清单写入本机 `cache/xhook/fetch-manifest.json`。

## 注意
- 本目录不含设备安装/启用步骤；装 APK、勾 Vector 作用域、重启 X 都需要用户明确同意。
- 上游许可：TwitterHideAds/Piko/X-region 为 GPL-3.0，X-ADBlock/birdify 为 MIT，
  x-video-save / x-video-catcher / TwiFucker 未声明许可。XHook 为独立实现，仅借鉴思路。

> 本文件已合并原 `ours/README.md` 与 `notes/*.md`（单文档维护）；签名位于 `../../shared/signing/debug.keystore`。

---

## XHook 源码构建与模块说明（原 ours/README.md）

# XHook（我们自己的 X/Twitter hook 模块）

基于 **libxposed API 102**（Vector / 新版 LSPosed）。目标设备：vivo X Fold6，Android 16，
X 12.42.0-prod.01。只 hook `com.twitter.android` 主进程。

## 构建状态（2026-11-15）

- `:app:assembleDebug` **已编译通过**（AGP 9.2.1 / Gradle 9.4.1 / JDK 21 / compileSdk 36）。
- 产物：`../out/XHook-0.3.1-debug.apk`（5.07 MB，minSdk 31 / targetSdk 36，版本 0.3.1 / code 13）
- SHA-256：`5ca25d0c9145acd9cc7b7dfbe6aa40bbc1ff0b11a02b7077186ab8928c0961f8`
- 0.3.1（2026-11-15）：渲染入口锚点补全（`l6#a` / `hl1#invoke` / `h#a` 等 5 个 hook），
  **已上机验证生效**：日志 4 次 `skipped promoted item`，首页/滚动无广告、无崩溃
- 签名：Android Debug（`CN=Android Debug`，SHA-256 `1990df77…7212fb`）
- SDK：Gradle 工程，platform 36 + build-tools 36 由 AGP 自动安装/解析
- **已安装到设备**：`dev.xhook` UID 10558；Vector 2.2 已启用，scope `com.twitter.android/0`。
- 实测进度：模块加载 / MediaCapture / PlayerButtons / DexKit / Home reselect / refresh repository 均已命中；Remote Preferences 已能读到 `block_refresh=true`。交互测试被设备上的 vivo 安装器弹窗打断，待继续。

## 2026-11-15 真机实测记录

- 安装方式：root `pm install -r --user 0`（跳过安装器确认）；Vector 2.2，包 `dev.xhook`，scope `com.twitter.android/0`。
- **Vector 2.2 的行为**：模块 APK 更新后，需要先打开一次模块 App（或 CLI disable/enable 后再启动 App），否则 X 启动时 Vector 会静默跳过该模块。日常开机不受影响；更新模块后照此操作。
- Remote Preferences：服务连接后，X 进程已读到 `block_refresh=true`、`block_home_reselect=true`（日志 `enabled=true`）。
- Hook 目标（X 12.42.0）：
  - `com.x.home.tabbed.v#h`：唯一 `boolean(boolean,boolean)`，命中 Re:X `fe2.l` 的类级谓词，`deopt=true`。
  - `com.x.repositories.urt.a0#p`：`void(Enum, ...)`，`deopt=true`；启动时 type=`PULL_TO_REFRESH`。
- MediaCapture：`hooked=1`，能抓到 `video.twimg.com` 的 master/直链；悬浮按钮可见，点击能触发下载流程。
- 第一次下载失败：X 返回的是**单码率媒体播放列表**（没有 `EXT-X-STREAM-INF`），0.2.9 已加媒体播放列表 fallback，待复测。
- 0.3.1 去广告实现：`RenderAdFilter` 先在 DexKit 里找 promoted-metadata 类（toString 前缀
  `TimelinePromotedMetadata(`）→ 持有该字段的条目模型（12.42 是 `g6`）→ 挂三条渲染路径：
  条目接口默认方法 `l6#a`、Composer lambda `com.x.android.main.hl1#invoke`、
  帖子内部边界 `h#a`/`a2#a`/`a2#c`（共 5 个 hook）。命中推广条目就不执行原方法
  （fail-open，普通帖子原样渲染）。实测只有 `hl1#invoke` 真正命中。
- 探针日志：每进程前 24 条调用打 `probe <方法> self=<类> arg0=<类> promoted=<bool>`，
  用于下次 X 版本变更时离线校准。
- 旧版 entryId 过滤：`legacy_entryid_filter=true` 时才安装（12.42 无用）。
- 2026-11-15 22:14 实测（0.2.9）：`discovery: 0 candidate method(s)` → `hooks=0`，真机截图
  （`../notes/now-x2.png`）里 @SUBWAY 的 `Ad` 帖仍在；根因是 12.42 删掉了 entryId
  前缀字符串，详见 `../notes/X-12.42-ANCHORS.md`。日志：`../notes/logcat-2026-11-15-xhook.txt`。
- 交互测试（点 Home/Tab、后台恢复、下载）被设备上的 vivo 安装器弹窗（OriginTweaks 0.1.0）打断，未继续。

当前是 **0.2 骨架**：视频下载 + 去推广/关键词过滤 + 后台刷新守卫（Home reselect /
lifecycle）。去广告与刷新守卫是“结构发现 + fail-open”的实验实现，需要在真机上用
X 12.42 校准一次（见“测试计划”）。

## 功能

| 功能 | 状态 | 实现 |
|---|---|---|
| 视频下载（HLS/MP4） | 可编译，逻辑完整 | 抓 `androidx.media3.datasource` 构造器 Uri → 解析主播放列表 → 选档 → 下载 init+分片 → MediaMuxer 合流 → MediaStore |
| 下载按钮 | 可编译 | 在 X Activity 的 DecorView 上叠 48dp 按钮；最近 20s 抓到过视频才显示；长按选画质 |
| 去推广/广告 | 实验 | DexKit 找“含 promoted-tweet-/rtb-image-ad- 且列表进出”的方法；按 entryId 前缀过滤 |
| 机器人/垃圾帖 | 实验 | 同一批列表 hook；对话页（可配主页）按关键词/零宽字符归一化匹配 |
| 阻止首页重选刷新 | 实验 | 找 `com.x.home.tabbed.*` 的 `boolean(boolean,boolean)`，第二个参数置 false（Re:X `ja2` 同款） |
| 阻止回前台自动刷新 | 实验（默认关） | 找 `com.x.repositories.urt.*` 的 `void(Enum, cursor)`，仅拦截 `VIEWPORT_AWARE_AUTO_REFRESH`，首次启动放行 |
| 设置 | 可编译 | 模块 App 写 SharedPreferences，X 进程经 libxposed Remote Preferences 读 |
| root / 联网上报 / 通知读取 | 无 | 模块 App 无任何权限；下载用 X 自己的 INTERNET |

## 代码结构

```
app/src/main/java/dev/xhook/
├── XHookModule.java        libxposed 入口（onPackageReady → Bootstrap）
├── Bootstrap.java          hook Application.attach，启动功能
├── Runtime.java            API 持有、日志、Remote Preferences
├── Config.java             配置键
├── media/
│   ├── MediaCapture.java   抓 media3 datasource Uri，按 mediaId 记录 master/直链
│   ├── PlayerButtons.java  Activity 生命周期 + 悬浮下载按钮
│   ├── Quality.java        HLS 主播放列表解析/选档
│   └── VideoDownloader.java HLS 下载、合流、写相册
├── filter/
│   ├── Discovery.java      DexKit 结构发现候选方法
│   ├── TimelineFilter.java 列表 hook + 过滤 + 保持 ImmutableList 类型
│   ├── EntryAccess.java    混淆模型 entryId/正文访问器缓存
│   └── Rules.java          promoted 前缀 + 关键词归一化
├── timeline/
│   └── RefreshGuard.java   Home reselect + 生命周期自动刷新拦截
└── ui/SettingsActivity.java 设置页
```

模块元数据：

```
app/src/main/resources/META-INF/xposed/module.prop   # minApi 101 / targetApi 102 / staticScope
app/src/main/resources/META-INF/xposed/java_init.list
app/src/main/resources/META-INF/xposed/scope.list
```

## 编译

需要：

- JDK 21
- Android SDK：`platforms;android-36`
- Gradle 9.4.1（AGP 9.2.1）

```bash
# local.properties 里写 sdk.dir，或用 ANDROID_HOME
./gradlew :app:assembleDebug
# Windows: gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

> `local.properties` 指向本机 SDK（或用 `ANDROID_HOME`）；该文件已加入
> `.gitignore`，换机时按本机 SDK 位置自行改路径即可。

## 测试计划（需要用户授权后才能装 APK）

1. 安装 APK → 在 Vector 里启用 XHook → 作用域只勾 `com.twitter.android`。
2. 强制停止 X（不是重启手机）→ 打开 X。
3. 看日志：
   ```bash
   adb logcat -s XHook
   ```
   期望看到：
   ```
   XHook: MediaCapture installed, hooked=1
   XHook: PlayerButtons installed
   XHook: discovery: N candidate method(s)
   XHook: discovery target: com.x...#... -> ...
   XHook: TimelineFilter installed hooks=...
   ```
4. 视频：打开一条视频，播放 2 秒，右下角应出现蓝色 ↓；点一下下载原画；长按选 720/480。
   相册路径 `Movies/XHook/`。
5. 去广告：主页/详情页滚动，观察 `TimelineFilter removed=... total=...`。若 `discovery: 0 candidate`，
   把日志发我，用 X 12.42 的 dex 重新校准 DexKit 查询。
6. 机器人：设置里打开开关、粘贴关键词；进详情页看命中；确认没有误杀普通回复。
7. 刷新守卫：设置里打开“阻止首页重选刷新 / 阻止回前台自动刷新”；回桌面或锁屏 30 秒再回 X，
   看 logcat 里的 `Home reselect guard` / `Refresh guard: blocked`。若首页空白或卡在加载，
   先关掉“阻止回前台自动刷新”再试。
8. 如果仍然跳回账号首页/信息流，先判断是不是进程被杀：
   ```bash
   adb shell dumpsys activity exit-info com.twitter.android | head -30
   ```
   出现 `single-cleaner` / `LOW_MEMORY` 就是系统杀进程，hook 无法恢复页面，
   详见 [../notes/X-BACKGROUND-KILL.md](../notes/X-BACKGROUND-KILL.md)。
9. 回滚：关闭模块或卸载 APK；X 不受影响（所有 hook 都在 try/catch 里，失败只丢功能）。

## 已知限制

- **去广告发现是实验性的**：X 12.42 的类名/结构可能和 12.28/12.37 不同；DexKit 查询参数
  （包名 `com.x`、`promoted-tweet-` 字符串）需要真机日志校准。校准失败时模块不会乱删帖子。
- **关键词过滤**：当前是纯 contains（已做零宽字符剥离）；没有实现 Aho-Corasick、正则、
  白名单、已认证跳过、MARK 模式——这些是下一阶段。
- **刷新守卫是实验性的**：Home reselect 照抄 Re:X `ja2`（第二个 boolean 置 false）；
  lifecycle 只在“刚回前台 + 已有内容”时拦 `VIEWPORT_AWARE_AUTO_REFRESH`，默认关。
  需要在真机上确认不会空白/误拦。
- **后台回首页的根因已经查到**：`dumpsys activity exit-info` 里多次出现
  `reason=10 (USER REQUESTED) / subreason=21 (FORCE STOP)`,
  `description=stop com.twitter.android due to single-cleaner`。这是 OriginOS 的后台
  清理器杀进程，冷启动必然回到首页；hook 不能跨进程恢复详情页。先按
  [../notes/X-BACKGROUND-KILL.md](../notes/X-BACKGROUND-KILL.md) 做系统保活，再谈导航恢复。
- **视频**：不支持直播、加密 HLS、带 `EXT-X-BYTERANGE` 的播放列表；只接受
  `video.twimg.com`。Android 13- 的 mp4parser 兜底还没接（当前统一 MediaMuxer）。
- **入口**：只有悬浮按钮，还没做分享面板注入（x-video-catcher 的 state 构造器方案）。
- **SystemUI**：不 hook，不需要 SystemUI 作用域。

## 与上游的关系

只借鉴**思路与公开接口形状**，代码为独立实现：

- 去广告 entryId 语法：TwitterHideAds（GPL-3.0）文档/README
- 列表 hook / 关键词规则模型：X-ADBlockModule（MIT）
- media3 Uri 抓取与 HLS 合流：x-video-save（未声明许可，仅参考行为）
- 分享面板注入（未实现）：x-video-catcher（未声明许可，仅参考设计）
- HLS 解析、下载、合流代码为本项目原创；当前统一用 `MediaMuxer`，Android 13- 的
  mp4parser 兜底尚未接入。

---

## X 12.42 结构锚点与去广告根因（原 notes/X-12.42-ANCHORS.md）

# X 12.42.0-prod.01 结构锚点实测（2026-11-15）

从设备只读拉取 `base.apk`（158,388,045 B，15 个 dex，132.6 MB）离线分析。
结论用于判断 XHook 0.2.9 的去广告为什么 **0 candidate**，以及 12.42 上可用的新锚点。

## 1. 旧锚点已全部失效

entryId 前缀字符串在 12.42 dex 里的出现次数（`strings` 级精确匹配）：

| 字符串 | 出现次数 |
|---|---|
| `promoted-tweet-` | **0** |
| `promotedTweet-` | **0** |
| `rtb-image-ad-` | **0** |
| `superhero-` | **0** |
| `messageprompt-ads-` | **0** |
| `conversationthread-` | 1（`com/x/composer/upload/d`，与时间线无关） |

`Discovery.MARKERS` 依赖的四个标记在 12.42 全部不存在，因此 `discovery: 0 candidate`
→ `TimelineFilter installed hooks=0` → 过滤逻辑一行都没跑。真机截图
（`notes/now-x2.png`，22:14）里 @SUBWAY 的 `Ad` 帖仍在，与日志一致。

logcat 证据：`notes/logcat-2026-11-15-xhook.txt`

```
XHook: dev.xhook: features: android=17 (37) videoButton=true adFilter=true spamFilter=false
XHook: dev.xhook: MediaCapture installed, hooked=1
XHook: dev.xhook: PlayerButtons installed
XHook: dev.xhook: DexKit ready in 209ms
XHook: dev.xhook: discovery: 0 candidate method(s)
XHook: dev.xhook: TimelineFilter installed hooks=0 ... rules=promoted=4, keywords=0
XHook: dev.xhook: Home reselect predicate: com.x.home.tabbed.v#h
XHook: dev.xhook: Refresh repository hook: com.x.repositories.urt.a0#p deopt=true
XHook: dev.xhook: feature install finished in 650ms
```

## 2. 12.42 的广告信号：`promotedMetadata` 模型字段

广告不再由 entryId 前缀标记，而是时间线模型上的一个对象字段：

- 模型类：`com/x/models/ff`（混淆名，每版会变）
  - `toString` 前缀：`TimelinePromotedMetadata(impressionId=`
  - 序列化类 `com/x/models/pe` 的 serialName：`com.x.models.TimelinePromotedMetadata`
  - 字段：impressionId / disclosureType / advertiserResults / adMetadataContainer / rtbAdMetadata…
- 时间线帖子模型 `com/x/models/timelines/items/k1` 持有 `e:Lcom/x/models/ff;`
- UI 帖子模型 `com/x/urt/items/post/g6` 直接持有 `q:Lcom/x/models/ff;`

所以「是否广告」的新判定不依赖字符串，而是：**条目的 promoted-metadata 字段非空**。

## 3. 渲染层锚点（可用于隐藏）

`com.x.presenter.a` 是每个 item presenter 的 Compose 入口（`b(Composer, int)`）。
帖子内容实际由 `com/x/urt/items/post/a2#a(...)` 渲染：

```
Lcom/x/urt/items/post/a2;->a(
    Lcom/x/urt/items/post/g6;          // 帖子 UI 模型，含 q:promotedMetadata
    ... 22 个参数 ...
    Landroidx/compose/runtime/Composer; I)V
```

- 唯一调用者：`com/x/urt/items/post/n1#invoke`（Composable lambda）。
- 同包内还有 `com/x/urt/items/post/h#a(g6, e3, Modifier, Composer, I)` 这类薄封装。
- 发现规则（结构）：**直接声明在 `com.x.urt.items.post` 包内的 `static void`，
  参数含 `Composer` 与 `int`，第一参数类型带 promoted-metadata 字段**。

在这些边界上 `if (promoted != null) return;`（不进原方法）即可让广告帖不渲染。

## 4. 数据层锚点（更干净，但工程量大）

每个屏幕都有一个 item→presenter 的 mapper，返回 `com/x/presenter/a`：

```
com/x/urt/b#a(int, com/x/models/timelines/items/p0, fp, z1, t0) -> presenter   // Home/通用
com/x/urt/z#u(int, p0) -> presenter
com/x/urt/ui/g0#f(int, p0, Function2, Composer) -> presenter
com/x/home/foryou/v#u(int, p0) -> presenter
com/x/postdetail/o#u(int, p0) -> presenter
...（共 50+ 个，每个时间线屏幕一个）
```

若要在数据层删条目，需要替换 mapper 返回值（空 presenter）或改 store 里的
entry 列表；比渲染层跳过复杂，收益是**没有空行/分隔线**。

## 5. 结论

| 方案 | 可行性 | 代价 |
|---|---|---|
| 修 `Discovery.MARKERS`（继续用 entryId） | ❌ 字符串不存在 | — |
| 渲染层跳过（promotedMetadata 非空 → 不渲染 `a2#a`） | ✅ 结构稳定，1 个 hook 点 | 可能留 0 高条目/分隔线 |
| 数据层过滤（mapper 返回空 presenter） | 🟡 需要空 presenter + 逐屏覆盖 | 工程量大，效果最干净 |

复现分析（离线，不需要设备）：

```bash
# 只读拉取
adb pull /data/app/~~Qk2qUUNLOek8OZXp6-qVcw==/com.twitter.android-*/base.apk apks/x-12.42/base.apk
# dex 结构枚举（自写脚本，见 work/x-12.42/dexapi.py）
python dexapi.py mfield promoted
```

`work/` 与 `apks/` 均为本地大件，已 gitignore。

## 6. 0.3.0 实现（RenderAdFilter）

| 步骤 | 行为 |
|---|---|
| 1 | DexKit `FindClass` + `usingStrings("TimelinePromotedMetadata(")` → promoted 类 `com.x.models.ff` |
| 2 | 枚举 `com.x.urt.items.post` 包内直接声明、持有该类型字段的类 → 条目模型 `g6` |
| 3 | `FindMethod` 该包内声明、第一参数是条目模型、参数含 `Composer`/`int`、static void → 边界 `h#a`、`a2#a`、`a2#c` |
| 4 | hook 边界：`arg0` 的 promoted 字段非空 → 不 `proceed()` |

失败一律 fail-open（找不到就只打日志，不影响普通帖子）。旧 entryId 路径移到
`legacy_entryid_filter=true` 后面，仅对老版本 X 有效。

## 7. 上机验证（2026-11-15 23:12，0.3.1）

0.3.0 只挂 static 边界（`h#a` / `a2#a` / `a2#c`）：hook 全部安装成功，但**一次都没被调用**，
广告仍在（`after-0.3.0-1.png`）。

0.3.1 补上两条入口后生效：

```
XHook: ad discovery: item renderer com.x.urt.items.post.l6#a(6 params)
XHook: ad discovery: list lambda com.x.android.main.hl1#invoke
XHook: RenderAdFilter installed: promoted=com.x.models.ff items=[com.x.urt.items.post.g6] hooks=5
XHook: probe com.x.android.main.hl1#invoke self=com.x.android.main.hl1 arg0=…o0 promoted=false
XHook: RenderAdFilter: skipped promoted item total=1 self=com.x.android.main.hl1
...total=4
```

- 真正命中的只有 `hl1#invoke`（Composer lambda，条目在它的 Object 字段里）。
- `l6#a`（接口默认方法）虽然装了 hook、deopt=true，但探针没有它的调用记录 —— 推测
  ART 对接口默认方法走的是另一条分派路径，hook 不生效；留着无害。
- 4 次 skip 后首页与连续滚动均无广告、无空行、无崩溃（`after-0.3.1-1.png` / `-2.png`，
  日志 `logcat-0.3.1-verified.txt`）。
- 探针日志保留：每进程前 24 条，用于 X 下次改结构时快速定位真实渲染路径。

---

## Re:X 移植台账（原 notes/PORTING.md）

# Re:X → XHook 移植台账

状态：✅ 已实现并编译（未上机验证）｜🟡 部分/实验｜❌ 未开始

Re:X 1.8.2 的设置项约 100 个，分布在 8 个板块。全量“一次搬完”不现实：
闭源不能抄代码，只能按 UI 标签 + 反编译结构重写；外观类还绑死 X 的 Compose 内部结构。
下面按“功能组”推进，每组都能单独编译、单独回滚。

## 第 1 批：时间线行为（当前）

| 功能 | Re:X 参考 | XHook | 备注 |
|---|---|---|---|
| 阻止首页重选刷新 | `kg0.o` + `ge2.I` + `ja2` case 18（第二个 boolean 置 false） | ✅ `RefreshGuard.hookHomeReselect` | 已命中 `com.x.home.tabbed.v#h`（deopt=true）；点按触发待复测 |
| 阻止回前台自动刷新 | `kg0.o` + `ge2.M/R` + `by0`（enum `VIEWPORT_AWARE_AUTO_REFRESH`） | 🟡 `RefreshGuard.hookRefresh` | 已命中 `com.x.repositories.urt.a0#p`；Remote Preferences `enabled=true`；resume 拦截待复测 |
| 启动记忆滚动位置 | `kg0.o` 的 timeline position persistence（store/holder/save/get） | ❌ | 结构复杂，下一批 |
| 记忆首页 Tab | `ge2` Home tab coordination / remembered restoration | ❌ | 下一批 |
| 自动刷新控制（切换 Tab 放行等） | Re:X 设置项 | ❌ | 与上面共用 hook 点 |

## 第 2 批：去广告 / 视频 / 机器人

| 功能 | Re:X / 参考 | XHook | 备注 |
|---|---|---|---|
| 推广帖过滤（时间线/回复） | Re:X `lv1`；TwitterHideAds 渲染边界 | ✅ 0.3.1 上机验证 | promotedMetadata 字段 + `hl1#invoke` 渲染跳过；首页/滚动无广告 |
| Video Tab + RTB 广告 | TwitterHideAds `RtbAdFilter`/`VideoDatasetFilter` | ❌ | paging state / item→presenter |
| 隐藏 Spaces 栏 | Re:X `lv1` | ❌ | 结构 hook |
| 隐藏新帖悬浮按钮 | Re:X `lv1` | ❌ | 结构 hook |
| 隐藏 Premium 升级/付费订阅按钮 | Re:X `lv1` | ❌ | 结构 hook |
| 隐藏订阅/文章 Tab | Re:X `bv0` | ❌ | Tab 配置 |
| 视频下载（HLS/MP4） | Re:X `dv0/iv0`；x-video-save | 🟡 悬浮按钮 + 选档 | 已能抓 URL/触发；0.2.9 增加单码率媒体列表 fallback，待复测 |
| GIF 下载转 .gif | Re:X `lv1/dv0` | ❌ | 需要 GIF encoder |
| 下载位置/文件名模板 | Re:X `dv0/eq` | ❌ | 模块设置 + MediaStore |
| 分享面板下载入口 | x-video-catcher state ctor | ❌ | 工程量大 |
| 关键词/机器人过滤 | X-ADBlock（MIT） | 🟡 contains + 零宽归一化 | 无 Aho/正则/白名单/MARK/历史 |
| 过滤历史/白名单/已认证跳过 | X-ADBlock | ❌ | 模块 App + 规则层 |

## 第 3 批：隐私 / 分享 / 文本 / 维护

| 功能 | Re:X 参考 | XHook | 备注 |
|---|---|---|---|
| 本地身份伪装（昵称/@/头像） | Re:X `iv0/ge2/hv1` | ❌ | 仅本机显示 |
| 清理分享链接追踪 | Re:X `lv1/xu1/yv1` | ❌ | share intent |
| 分享域名替换（x.com/twitter.com/自定义） | Re:X `xu1/yv1` | ❌ | share intent |
| 文本选择菜单自定义（分享/翻译/词典） | Re:X `bv0/wt1` | ❌ | system selection menu |
| 隐藏 Grok 分析/长按项 | Re:X `lv1` | ❌ | 结构 hook |
| 跳过年龄验证遮罩 | Re:X `lv1` | ❌ | 结构 hook |
| 投票结果预览 / 长按倍速 | Re:X `lv1` | ❌ | 结构 hook |
| 设置备份/恢复 | Re:X `u41/dy1` | ❌ | 模块 App 独立完成 |
| 日志页（Hook 错误/安装耗时） | Re:X `gq/lx1` | ❌ | 模块 App + 日志文件 |
| 更新检查/安装 | Re:X `eq/qn` | ❌ | 可后置 |
| Feature Switch 编辑器 | Re:X `eq/m1/i60` | ❌ | 建议默认只读 + 可回滚 |

## 第 4 批：外观 / 导航（工程量大，最后做）

| 功能 | Re:X 参考 | XHook | 备注 |
|---|---|---|---|
| Material 3 动态取色 | `bv0/hv1/iv0` | ❌ | 大量 Compose 颜色 hook |
| 系统字体 / Twemoji | `bv0/lv1` | ❌ | 字体/emoji hook |
| 𝕏→小鸟 / 鸟舍 / 羽毛笔 | `bv0` | ❌ | 资源替换 |
| 开屏缩放动画 | `lv1` | ❌ | 开屏 hook |
| 底部导航/侧边栏/FAB 定制 | `ci/gv1/uw1` | ❌ | 结构 hook |
| 通知样式 / SystemUI | `hv1` | ❌ | 需 SystemUI 作用域，不建议 |
| 悬浮导航栏 | `gv1` | ❌ | 结构 hook |

## 已知阻塞

1. **去广告（P0）✅ 已上机验证**：12.42 删除了 entryId 前缀，0.2.9 的 `discovery`
   返回 0 个候选。0.3.0 只挂 static 边界没命中；0.3.1 补上 `com.x.android.main.hl1#invoke`
   后生效（4 次 skip、无崩溃）。锚点与验证见 `X-12.42-ANCHORS.md` §6–§7。
2. **上机校准**：模块已装（dev.xhook 0.2.9），Vector 已启用并勾 `com.twitter.android/0`；
   2026-11-15 22:14 已确认模块加载、视频抓取、两个刷新守卫都命中；交互测试
   （点 Home/Tab、后台恢复、下载、点按）仍待复测。
3. **进程被杀**：`notes/X-BACKGROUND-KILL.md` 已证明 OriginOS `single-cleaner` 会
   force-stop X；详情页回首页主要是冷启动，hook 无解，先做系统保活。
4. **闭源边界**：Re:X 的代码不能直接复制，只能按行为重写；GPL 模块（TwitterHideAds/Piko）
   只能参考思路，直接抄会让 XHook 变成 GPL。

---

## 后台被杀根因（原 notes/X-BACKGROUND-KILL.md）

# X 后台回来“跳回首页”的根因证据

设备：vivo X Fold6 (V2545A)，Android 16，X 12.42.0-prod.01。
命令（只读）：

```bash
adb shell dumpsys activity exit-info com.twitter.android
```

## 关键记录

```
timestamp=2026-11-14 09:04:07.722 pid=17571 ... process=com.twitter.android
reason=10 (USER REQUESTED) subreason=21 (FORCE STOP)
description=stop com.twitter.android due to single-cleaner

timestamp=2026-11-12 12:59:28.750 pid=18995 ... process=com.twitter.android
reason=10 (USER REQUESTED) subreason=21 (FORCE STOP)
description=stop com.twitter.android due to single-cleaner

timestamp=2026-11-09 20:29:23.787 pid=20314 ... process=com.twitter.android
reason=10 (USER REQUESTED) subreason=21 (FORCE STOP)
description=stop com.twitter.android due to single-cleaner

timestamp=2026-11-09 14:12:47.629 pid=13387 ... process=com.twitter.android
reason=3 (LOW_MEMORY) subreason=0 (UNKNOWN) description=low memory
```

## 这说明什么

1. **不是 X 的“返回”逻辑把页面弹回首页，而是 X 进程被系统杀掉了。**
   `single-cleaner` 是 OriginOS 的后台清理器（不是 Android 的 LMK），它会
   `force-stop` 后台应用；X 的 `rss` 经常在 1.1–1.4 GB，更容易被盯上。
2. 进程被杀后重新打开只能**冷启动**：X 从首页或账号页重新加载，自然看不到
   之前的视频/帖子详情。任何 Xposed hook 都无法跨越进程死亡保留页面栈。
3. 另外还有 `LOW_MEMORY` 记录（2026-11-09），说明内存压力也在参与。

## 两个问题要分开

| 现象 | 根因 | 能不能用 hook 解决 |
|---|---|---|
| 一段时间后台回来，首页自动刷新 | X 生命周期/自动刷新逻辑（进程还活着） | ✅ ReX 的 Home reselect / lifecycle guard，已移植到 XHook |
| 从账号视频/帖子详情回来，变成账号首页/信息流 | **进程被 single-cleaner / LMK 杀掉，冷启动** | ❌ hook 不能跨进程；需要系统保活 |

## 处理顺序

### 1. 先让系统别杀 X（推荐，先做）

OriginOS 常见路径（不同版本名称略有差异）：

- 设置 → 电池 → 后台耗电管理 → X → **允许后台高耗电 / 允许后台运行**
- 设置 → 应用管理 → X → 电池 → **允许后台活动**
- 设置 → 电池 → 更多设置 → 关闭 **“后台应用清理 / 智能清理 / 单应用清理”**
- 最近任务卡片下拉 → **锁定 X**（防止一键清理）

如果系统有“single-cleaner / 单应用清理”开关，优先关掉它。

### 2. adb / root 白名单（可选，需要你手动执行）

```bash
adb shell dumpsys deviceidle whitelist +com.twitter.android
adb shell cmd appops set com.twitter.android RUN_IN_BACKGROUND allow
adb shell cmd appops set com.twitter.android RUN_ANY_IN_BACKGROUND allow
```

这些命令不一定覆盖 vivo 自己的 cleaner，但能排除 Doze/后台限制；执行后观察
`exit-info` 是否还出现 `single-cleaner`。

### 3. 模块层面

- 已加 `RefreshGuard`：
  - Home reselect：把 `(boolean, boolean) -> boolean` 的第二个参数置 false（照抄
    ReX `ja2` case 18）。
  - lifecycle：拦截 `VIEWPORT_AWARE_AUTO_REFRESH`（刚回前台且已有内容时）。
  - 这只解决“进程还活着但自动刷新”的情况。
- **不建议**做“root 定时拉起 X”之类的保活：耗电、违反系统意图，而且冷启动仍会丢页面。

### 4. 怎么验证是进程被杀还是导航重置

```bash
# 记下当前 PID
adb shell pidof com.twitter.android
# 锁屏/后台 10 分钟，再回来
adb shell pidof com.twitter.android
# 如果 PID 变了，或 exit-info 新增 single-cleaner/LOW_MEMORY，就是进程被杀
adb shell dumpsys activity exit-info com.twitter.android | head -30
```

如果 PID 没变、仍然跳回首页，那才是 X 自己的导航/刷新逻辑，我们再继续挖
X 12.42 的 Activity/Compose 导航状态。

---

## 上游模块对比矩阵（原 notes/MODULE-MATRIX.md）

# X/Twitter hook 模块拆解与取长补短

设备基线：vivo X Fold6 (V2545A)，Android 16，X **12.42.0-prod.01** (versionCode 312420001)，
Vector (libxposed API 102) + KernelSU。

## 1. 候选模块总表

| 模块 | 最新版 / 日期 | 许可 | 机制 | 适配 X | 能否用于 12.42 | 可借鉴点 |
|---|---|---|---|---|---|---|
| **Re:X** (one.dot.rex) | 1.8.2 / 2026-11-13 | 闭源 | libxposed 102；Compose/资源/Feature Switch 大量 hook；SystemUI 作用域做外观 | 12.43（1.8.2 明确适配） | **可以** | 工程完整度、设置项组织、更新链校验、结构适配思路 |
| **TwitterHideAds** | 3.1.0 / 2026-10-26 | GPL-3.0 | DexKit 找 Compose 渲染边界；Brainfuck 策略编译成 .so；URT 数据层过滤 | 12.37 实测 | 大概率可用（自适应） | **去广告最强**：渲染前拦截、RTB 广告、Video Tab 批量过滤、fail-open |
| **X-ADBlockModule** | 0.1.5 / 2026-09-07 | MIT | 直接 hook `com.x.urt.ui.o#c` 列表方法；Aho-Corasick 关键词；云端词库 | 12.22 | 需重新找类名 | **机器人/黄推过滤**：对话页才扫描、REMOVE/MARK、白名单、历史、词库订阅 |
| **TwiFucker-Revived** | 0.1.2 / 2026-09-07 | 未声明 | URT 序列化契约找模型；按 entryId 前缀过滤；隐藏 NativeAdView；Grok 双语 | 12.23 | 需适配 | entryId 前缀集合、premium upsell ID、NativeAdView 处理、翻译 hook |
| **x-video-save** | 0.4 / 2026-11-13 | 未声明 | hook media3 datasource 构造器抓 Uri；播放器栏悬浮按钮；HLS 解析/选档/续传/合流 | **12.41 / 12.42** | **可以** | **视频保存最实用**：不依赖 X 混淆类、悬浮按钮、画质选择、HLS 合流 |
| **x-video-catcher** | 1.58.0 / 2026-09-23 | 未声明 | hook 分享面板 state 构造器注入行；MediaSpy 抓播放器已解析 URL；HLS 合流 | 12.24–12.27 | 需适配 | **分享面板注入**、字符串锚点/形状查找、失败即隐藏入口 |
| **Piko** | 3.11.1 / 2026-11-10 | GPL-3.0 | Morphe/ReVanced 补丁（重打包 APK，不是 hook 模块） | 12.19.1 | 不适用 | 补丁清单、下载/去广告功能面、字段级实现参考 |
| **X-region** | 0.1 / 2026-11-09 | GPL-3.0 | 伪造国家码 | 未知 | 需验证 | root 桥、国家码伪装 |
| **birdify** | 1.3.0 / 2026-11-12 | MIT | 旧版蓝鸟图标 | 未知 | 需验证 | 图标/资源替换 |
| **XShortLinkCleaner** | 1.0.0 / 2026-09-22 | 未声明 | 清 t.co 短链 | 未知 | 小功能 | 分享/复制链接清洗 |

## 2. 去广告/机器人：三种可组合的 hook 层

### 2.1 数据层（最稳，优先）
**目标**：在列表进入 UI 前把条目删掉，不产生空白，不触发渲染。

**entryId 语法（TwitterHideAds 实测，12.28/12.37 仍有效）**：

```text
Home         promoted-tweet-<id>-<hash>
Post detail  conversationthread-<id>-promoted-tweet-<id>-<hash>
Search       search-conversation-<id>-promoted-tweet-<id>-<hash>
Video Tab    promoted-tweet-<id>
```

TwiFucker 补充的前缀/ID：

```text
promotedTweet-        promoted-tweet-
superhero-            rtb-image-ad-
messageprompt-ads-sharing-x-premium-upsell-candidate
messageprompt-generic-non-premium-inline-prompt
messageprompt-premium-announcement-inline-prompt
messageprompt-premium-grok2-upsell-prompt
messageprompt-premium-plus-upsell-prompt
suggest_who_to_follow (module)
```

RTB 广告（X 12.37+）是独立 timeline item（`UrtTimelineRtbImageAd`），不属于 post；
需要单独在 paging state 构造器或 item→presenter 映射处处理。

**hook 点选择**：
- X-ADBlock（12.22）：`com.x.urt.ui.o#c` 静态方法，arg0 是 `kotlinx.collections.immutable` 列表；
  后备 `com.x.urt.ui.h/j` 构造器。
- TwiFucker（12.23）：序列化契约找到 timeline flow 的 collect 方法 + module.getItems。
- TwitterHideAds（12.28/12.37）：DexKit 找 Compose 渲染边界（9 个），在渲染前判断 entryId；
  另加 Video Tab 数据层与 RTB 构造器。
- ReX（12.43）：结构化的 timeline post card hook + ad-filter hook，具体类名闭源。

**结论**：12.42 上不能硬编码 `com.x.urt.ui.o`。要么用 DexKit 按“返回 ImmutableList 的
静态方法 / 列表收集方法”做形状查找，要么用 Compose 渲染边界。前者改动小、风险低；
后者覆盖全但工程量大。

### 2.2 文本/规则层（机器人、黄推）
X-ADBlock 的做法可直接复用（MIT）：

- 只处理 `conversationthread-` 开头的 entryId，主页时间线零扫描。
- Aho-Corasick 自动机一次遍历匹配所有关键词；支持 `/regex/flags`、`#` 注释、零宽字符剥离、
  emoji-only、特殊字符、用户名匹配、跳过已认证账号。
- REMOVE（整行删除）或 MARK（保留头像/用户名，正文替换为“[已拦截]”）。
- 用户白名单、过滤历史、云端词库（GitHub raw + jsdelivr 兜底，ETag/304）。
- 跨进程：模块 App 写 Remote Preferences，X 进程读；事件回传用 setPackage 广播。

**结论**：这是“屏蔽广告机器人”最省力且效果直接的部分；我们保留同样的规则模型，
但把扫描范围做成可配置（默认只在详情/对话页，避免主页性能开销）。

### 2.3 渲染层（兜底）
- TwiFucker 的 `NativeAdView.onVisibilityChanged` → `GONE`，处理 Google 原生广告。
- Compose 渲染边界（TwitterHideAds）作为最后兜底：数据层漏掉的 promoted 条目在绘制前
  直接不调 `chain.proceed()`。

## 3. 视频保存：两条路线

### 3.1 抓 URL（推荐，版本无关）
**x-video-save（12.41/12.42 实测可用）**：

- 遍历 `androidx.media3.datasource.<a..zz>`，找构造器满足
  `(Uri, long, int, byte[], Map, long, long, String, int, ...)` 的类；
- hook 构造器 `after`，记录 `uri`；
- 只接受 `https://video.twimg.com/...`，用正则识别：
  - 直链 MP4：`/(ext_tw_video|amplify_video|tweet_video)/<id>...mp4`
  - 主播放列表：`/(ext_tw_video|amplify_video|tweet_video)/<num>/(pu/)?pl/<id>.m3u8`
- 按媒体 id 维护最近 30 条，记录 master + 分片活动时间。

**x-video-catcher** 的 MediaSpy 思路相同，但入口是分享面板。

### 3.2 下载与合流
- 主播放列表解析：`#EXT-X-STREAM-INF` 的 `RESOLUTION`/`BANDWIDTH`；
  `#EXT-X-MEDIA:TYPE=AUDIO` 取音轨 URI。
- 选档：原画=面积最大；高清=短边≤720；标清=短边≤480；同分辨率比码率；不重编码。
- 下载：`#EXT-X-MAP` init + 所有 `.m4s` 顺序拼接；音轨同样处理。
- 合流：Android 14+ `MediaMuxer`；Android 13- 用 mp4parser 避免系统解析器崩溃。
- 直链 MP4：直接下载，校验 `moov`/`mdat` 完整性。
- 保存：`MediaStore.Video` + `RELATIVE_PATH=Movies/XHook`，`IS_PENDING` 流程。
- 续传：按 `id+quality` 建缓存目录，记录已下载分片；HTTP Range 可用时续传。

### 3.3 UI 入口
| 方案 | 优点 | 缺点 |
|---|---|---|
| 悬浮按钮（x-video-save） | 与 X 类名无关，12.42 可用；实现简单 | 需处理可见性/位置；不是原生入口 |
| 分享面板行（x-video-catcher） | 原生体验 | 依赖 sheet state 构造器/action 分发链，X 更新易断；工程量大 |
| 播放器内按钮（ReX） | 最原生 | 依赖播放器内部结构，闭源实现 |

**MVP 选悬浮按钮**，后续再加分享面板。

## 4. 工程/合规要点

- 框架：**libxposed API 102**（Vector 2.2 已支持）；模块元数据：
  `META-INF/xposed/module.prop`（minApiVersion=101, targetApiVersion=102, staticScope=true）、
  `java_init.list`、`scope.list`（com.twitter.android）。
- 依赖：
  - `compileOnly("io.github.libxposed:api:102.0.0")`
  - `implementation("io.github.libxposed:service:101.0.0")`（设置/远程偏好）
  - `implementation("org.luckypray:dexkit:2.2.0")`（结构化查找）
  - 仓库需加 `maven("https://api.xposed.info")`。
- 进程：只 hook 主进程（`param.getProcessName()` 为空或等于包名）；SystemUI 不在 MVP 范围。
- 失败策略：所有 hook 体 try/catch，解析失败就“不装/不拦”，绝不把异常抛回 X。
- 许可：
  - 可借鉴代码：X-ADBlock（MIT）、birdify（MIT）。
  - 仅借鉴思路：TwitterHideAds（GPL-3.0）、Piko（GPL-3.0）、X-region（GPL-3.0）、
    x-video-save / x-video-catcher（未声明许可，默认保留所有权利）。
  - 我们的实现按原创新写；若直接复制 GPL 代码，整个 app 必须 GPL-3 开源。

---

## Re:X 1.8.2 安全审计与功能表（原 notes/AUDIT-REX.md & REX-FEATURES.md）

# Re:X (one.dot.rex) 1.8.2 静态安全审计

审计日期：2026-11-15
审计对象：`Re.X-1.8.2.apk`（GitHub Release `2611142-1.8.2.261114`，发布 2026-11-13）
样本路径：`xhook/apks/rex/Re.X-1.8.2.apk`

## 0. 结论先行

**未发现恶意行为。** 权限最小、无遥测、无动态加载、无隐私数据采集；唯一的联网行为是
GitHub Release 更新检查与下载，且更新包有完整的包名/版本/minSdk/**签名证书**校验。
模块确实会执行两条 root 命令，但均为设置页里的用户主动按钮（重启 X / 重启系统界面），
命令内容固定，不接受外部输入。

风险点不在“偷数据”，而在：**闭源 + 自动更新 + 需要 SystemUI 作用域**。
建议按第 7 节的用法约束使用。

## 1. 样本身份

| 项 | 值 |
|---|---|
| 包名 | `one.dot.rex` |
| versionName / code | `1.8.2.261114` / `2611142` |
| minSdk / targetSdk | 31 (Android 12) / 37 |
| APK SHA-256 | `788e9bd121665405ce0ae9c10f8242fcbfee4a71b5dabaa10e9fc8a5a3496b51` |
| 大小 | 9,139,082 bytes（解包 4,227 项） |
| 签名方案 | v2（无 v1 / 无 v3） |
| 签名证书 SHA-256 | `0ff9c9d08e92fbaa948643f73ed2b0a422e44f7a7f2c709d2323be57b5be3c09` |
| 证书主体 | CN=1Dot, OU=Dev, O=1Dot, L=Nanjing, ST=Jiangsu, C=CN（自签） |
| 代码规模 | 3,287 类 / 15,480 方法 / 1,056,234 条指令（JADX） |

签名证书用于更新自校验：新 APK 的 `SigningInfo.getApkContentsSigners()` 必须与已安装
模块完全一致，否则抛异常中止。这也意味着**任何第三方重打包/改签的 1.8.2 都无法被官方
更新链覆盖**，反之官方更新也不能被任意证书伪造。

## 2. 权限与组件面

权限只有 3 个：

```xml
android.permission.INTERNET
android.permission.REQUEST_INSTALL_PACKAGES
one.dot.rex.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION   (signature 级，自用)
```

没有存储、通讯录、短信、定位、相机、麦克风、`QUERY_ALL_PACKAGES`、无障碍、悬浮窗等敏感权限。

组件统计：1 个 Activity、4 个 Receiver、7 个 Provider。

### 2.1 导出的组件与访问控制

| 组件 | 导出 | 代码里的实际校验 | 评价 |
|---|---|---|---|
| `StartupLogReceiver` | 是（自定义 action） | 收到广播后只回传一个 Binder；Binder 的 `onTransact` 里 opcode 1 校验调用方为自身 UID 或 `com.twitter.android`，opcode 2 校验为 `com.android.systemui` | 外部应用能触发广播，但写不了日志；影响仅限于空转/刷日志 |
| `StartupLogProvider` | 否 | `Binder.getCallingUid() != Process.myUid()` 直接抛异常 | 仅模块自身可读写 |
| `LocalIdentityAvatarProvider` | 是（`content://one.dot.rex.identity`） | 只允许自身 UID 或持有 `com.twitter.android` 包的 UID 读取；只接受 `r` 模式；FileProvider 式解析，拒绝越界路径 | 头像“伪装”功能的必要暴露，读取面仅限 X |
| `LocalIdentityAvatarVisibilityReceiver` | 是（自定义 action） | 只做 `grantUriPermission("com.twitter.android", ...)`，然后回结果 | 任意应用可触发，但受益者只有 X，不能借此读文件 |
| `XposedProvider`（libxposed 标准服务） | 是 | 上游 libxposed 协议：接收框架侧 binder | 所有 libxposed 模块都这样；不属于 ReX 特有风险 |
| `HistoryProvider` / `HistoryReceiver` | 是 | 但 manifest 里 `android:enabled="false"` | 未启用，实际不生效 |
| `MainActivity` / `LazyLauncher` 别名 | 是 | 普通桌面入口 | 正常 |

FileProvider 路径也做了最小化：

- `one.dot.rex.update.files` → 仅 `cache/updates/`
- `one.dot.rex.logs.files` → 仅 `cache/startup-logs/`

不存在 `root-path`、`external-path` 之类的大范围共享。

## 3. 联网行为

全 APK 提取出的 URL/域名（已排除 AndroidX/许可证噪声）：

| 用途 | 地址 |
|---|---|
| 更新检查 | `https://api.github.com/repos/Xposed-Modules-Repo/one.dot.rex/releases/latest` |
| 更新下载 | `https://github.com/Xposed-Modules-Repo/one.dot.rex/releases/download/...` |
| 项目主页 | `https://github.com/Xposed-Modules-Repo/one.dot.rex`、`https://github.com/1-dot` |
| 赞助 / 社群 | `https://ko-fi.com/1dot`、`https://t.me/re_x_mod`、`https://t.me/re_x_mod/327` |
| 辅助链接 | `https://www.apkmirror.com/uploads/?appcategory=twitter`（版本说明里的外链） |
| 分享域替换 | `x.com`、`twitter.com`、`fxtwitter.com`、`fixupx.com` 等（本地改写分享链接） |

**没有**统计 SDK、崩溃上报、广告、IP 探测、陌生域名或硬编码 IP。`zd0` 里有一个
`BitmapFactory.decodeStream(new URL(str))`，调用方是本地身份头像预览，`str` 来自 X 的
头像地址，只用于显示，不携带模块数据。

## 4. 自动更新链（重点）

代码位置（JADX 反编译）：

- `defpackage/gi1.java:177` — 请求 GitHub latest release
- `defpackage/fw0.java:141-227` — 下载到 `cache/updates/update.part`
- `defpackage/ak2.java:67-118` — 安装前校验
- `defpackage/i7.smali:1519` — `FileProvider` + `ACTION_VIEW` 拉起系统安装器
- `defpackage/dd2.java:699-719` — 无“安装未知应用”权限时跳系统设置页

校验顺序（任何一步失败都会抛 `IllegalArgumentException` 中止，不会安装）：

1. 从 GitHub API 取 release 元数据（HTTPS）。
2. 下载大小 ≤ 100 MiB，且写入字节数必须等于 `Content-Length`（`"Incomplete APK"`）。
3. SHA-256 必须等于 release 资产声明的摘要（`"APK checksum mismatch"`）。
4. `getPackageArchiveInfo` 解析失败 → 拒绝。
5. 包名必须等于 `one.dot.rex`。
6. `versionCode` 必须**大于**当前安装版本。
7. `minSdkVersion` 不得高于本机 SDK。
8. 新 APK 的 `apkContentsSigners` 集合必须与已安装 APK 完全一致，否则
   `"APK signing certificate mismatch"`。
9. 以上全部通过才 `FileProvider.getUriForFile` + `Intent.ACTION_VIEW` +
   `application/vnd.android.package-archive`，由系统安装器弹窗，用户再确认一次。

这套校验比大多数开源模块都严格，**没有静默安装、没有 root 安装、没有忽略签名**。

## 5. root 使用

代码位置：`defpackage/r60.java:j(String)`。实现是 `new ProcessBuilder("su","-c",cmd)`，
60 秒超时，读取输出，非 0 退出视为失败。全 APK 只有两处调用：

| 触发 | 命令 | 位置 |
|---|---|---|
| 设置页「重启系统界面 / Restart System UI」 | `pkill com.android.systemui \|\| killall com.android.systemui` | `ji1.java:62` |
| 设置页「快速重启 X / Restart X」 | `am force-stop --user <uid> com.twitter.android && am start -W --user <uid> -n '<launcher component>'` | `rv.java:253` |

- 命令字符串固定，只有 user id 和 launcher component 是变量；component 来自
  `PackageManager.getLaunchIntentForPackage`，并做了单引号转义。
- 没有任何网络下发命令、没有读取配置文件拼接 shell、没有 `Runtime.exec` 的其他调用点。
- 也没有监听开机广播；不会在后台自动重启 X 或 SystemUI。

`重启系统界面` 是 ReX 主题/通知样式生效的必要操作，属于用户主动行为；如果你不想让它
碰 SystemUI，别点这个按钮，也别把 SystemUI 勾进作用域（代价是通知样式/开屏相关功能不可用）。

## 6. 其他敏感面

| 检查项 | 结果 |
|---|---|
| `DexClassLoader` / `InMemoryDexClassLoader` / `PathClassLoader` | 无 |
| `Runtime.exec` / `ProcessBuilder` | 仅上述 `su` 两处 |
| `System.load` / 自带 `.so` | 只有 `androidx.graphics.path`（AndroidX 官方库），无自定义 native |
| 反射 | 大量，但集中在 Compose 内部与 X 结构扫描（`bf2`/`be2`/`cd2` 等），没有 `Class.forName` 加载外部 dex |
| `DexFile` / `ZipFile` | 用于枚举 X 自己的 APK、扫描方法签名（结构适配），不加载代码 |
| 通讯录 / 短信 / 通话记录 | 无 |
| 定位 / 相机 / 麦克风 / 录音 | 无 |
| 账号 / `AccountManager` | 无 |
| `Settings.Global/System/Secure.put` | 无（只读 `boot_count` 写日志） |
| 通知读取 | 无 `StatusBarNotification` / `NotificationListenerService`；SystemUI 作用域只做开屏/Toast/通知外观 hook |
| 剪贴板 | 只写（复制日志、Compose 文本框），没有读取剪贴板内容外发 |
| 设备标识 | 无 IMEI/SN/AndroidID 读取 |
| 加密/编码 | SHA-256 用于更新校验与备份校验；Base64 仅 Compose 内部 |
| 后台服务 / 开机自启 | 无 Service、无 BOOT_COMPLETED |

## 7. 风险与使用建议

**残余风险**

1. **闭源**：本次审计只覆盖 1.8.2 这一个二进制；作者可以在后续版本改变行为。
2. **自动更新**：应用内检查更新，签名校验 + 系统安装器二次确认，但如果你一直点“安装更新”，
   等于持续信任作者的签名密钥。
3. **`REQUEST_INSTALL_PACKAGES`**：能力本身敏感，虽然当前实现安全。
4. **SystemUI 作用域**：会向 `com.android.systemui` 注入代码；当前未发现读取通知内容，
   但这是额外的攻击面。
5. **root**：两条命令固定且手动触发；但模块一旦被替换为恶意版本，root 能力会被滥用。

**建议**

- 只从官方 Release 下载，安装前核对
  `sha256 = 788e9bd121665405ce0ae9c10f8242fcbfee4a71b5dabaa10e9fc8a5a3496b51`；
- 记录证书指纹 `0ff9c9d0…be3c09`，以后每次更新用 `apksigner verify --print-certs` 或
  `keytool`/androguard 对一下；
- 若不需要通知样式/开屏动画，**不要**把 SystemUI 加进作用域，也不需要点“重启系统界面”；
- 关闭“自动检查更新”或只在需要时手动检查（模块设置里可关；更新仍走上面的校验链）；
- 不要在别的设备上覆盖安装第三方改签版本。

**结论：可以装，按上面约束用。** 它不是“偷数据的模块”，但闭源软件永远保留“下个版本变坏”
的可能性，审计只能证明这个版本干净。

---

## Re:X 功能清单（原 notes/REX-FEATURES.md）

# Re:X 1.8.2 功能全图（从设置页反编译提取）

来源：`unpack/rex-src` 中所有 `vi2.a("中文","English")` UI 标签（见
`notes/rex-ui-labels-all.txt`）+ 27 个 Release 的更新说明。只列设置页真实存在的选项，
不是猜的。

## A. 时间线 / 去广告 / 净化

- 隐藏信息流和帖子回复中的推广（Ad 标签帖）
- 隐藏直播空间栏（Spaces bar）
- 隐藏“新帖子”悬浮按钮
- 隐藏首页顶栏升级 Premium 按钮
- 隐藏付费订阅按钮（Paid Subscribe）
- 隐藏订阅 Tab、文章 Tab
- 禁用“跳转链接嵌入首页信息流”（link card 不自动展开）
- 禁用独立转发 Tab；在帖子 Tab 也显示转发（恢复旧版）；拆分帖子/亮点 Tab
- 阻止自动刷新；切换 Tab 时放行自动刷新；自动刷新控制
- 启动时记忆滚动位置；记忆当前首页 Tab（扩展到 For You/Following 之外的 Tab）
- Grok 净化：隐藏帖子 Grok 分析按钮、隐藏图片长按菜单 Grok 项
- 跳过年龄验证媒体遮罩
- 预览投票结果（不用投票就能看结果）
- 强制开启长按视频倍速播放
- 隐藏发帖 FAB / 禁用大操作按钮样式
- 隐藏底部/侧边栏 Grok

## B. 视频 / GIF / 媒体

- 视频和 GIF 下载（最高画质；媒体长按菜单或全屏查看器右上角菜单）
- 下载后 GIF 转 `.gif`
- 下载位置、文件名模板（占位符、非法字符校验）
- 多图帖子水平轮播组件
- 时间线大头像
- 媒体展示：单 Media Tab / 照片与视频分开 / 合并 Tab（默认照片或视频）
- 媒体选择器：系统 Android picker 或 X 内置 picker
- 强制图片打开使用连续缩放过渡（shared-element）
- 媒体可直接外露选择（旧版行为）

## C. 外观 / 主题

- Material 3 动态色板：取色种子、浅色/深色取色背景、第三主题色、柔和 Accent、Material 语义色
- 通知样式：黑底白字 / 主题色底白字 / 黑底主题色字 / 白底主题色字 / 不透明
- 主题色：默认蓝 / 跟随主题色
- 使用系统字体替代 Chirp
- Twemoji 17.0.3 表情
- 𝕏 → Twitter 小鸟（应用内图标 + 开屏画面）
- 导航栏主页图标 → 鸟舍；发帖 FAB 图标 → 羽毛笔
- 主页顶部品牌图标跟随主题色
- 开屏缩放动画
- 禁用背景模糊
- 预测性返回样式 / 返回动画
- 新版/旧版 Tab 样式
- 新版账号选择器、新版设置界面、新版下层侧边栏
- 悬浮导航栏（开启后 FAB/导航样式选项暂时禁用）

## D. 导航 / 布局

- 底部导航项显示/隐藏（主页、搜索、Grok、通知、私信、社区、通话等）
- 侧边栏项目自定义、侧边栏页脚、侧边栏宽度
- 主页 / 用户主页 / 探索页 / 搜索详情页 Tab 自定义
- Re:X 设置入口：显示为设置图标或模块图标；替换“反馈与问题”项
- 发帖 FAB：隐藏、形状、图标
- 底栏 Material 3 取色、显示文本标签

## E. 本地身份伪装（隐私）

- 启用 profile disguise：自定义昵称、@handle、头像
- **仅改变本机显示**，不影响服务器/别人看到的内容
- 自定义头像导入、预览、恢复原头像
- Premium 蓝勾样式（themed check）
- 应用内悬浮通知提示（in-app floating toasts）

## F. 分享 / 文本操作

- 清理分享链接追踪（去掉 `?s=20` 等追踪参数）
- 分享域名替换：
  - 默认 `x.com`
  - `twitter.com`
  - 自定义域名
  - 分别设置“帖子链接域名”和“用户主页等域名”
- 自定义加入分享、翻译、词典、设备可用的 process-text 应用
- 使用系统文本选择菜单；移除“问 Grok”和“隐藏”
- 扩展系统文本操作

## G. 维护 / 实验功能

- **Feature Switch 编辑器**：
  - 内置目录来自 X 12.43.0-beta.01 的布尔开关
  - 搜索键名、添加目录外键名、查看当前覆盖、保存/清除覆盖
  - 这是 ReX 里“解锁 X 隐藏功能”的入口，也是风险最高的功能之一
- 设置备份与恢复（导出/导入完整配置；失败有回滚提示）
- 日志页：Hook 错误、安装耗时、启动记录、错误详情、复制全部、一键反馈、清空日志
- 更新：自动/手动检查、更新内容、立即更新、安装更新（后台安装 / 主线程安装）、忽略此版本
- 语言（简体中文/跟随系统）
- 从桌面隐藏模块图标
- 快速重启 X（root）、重启系统界面（root）
- 在 APKMirror 查看 X 版本 / 已知适配最高版本
- 推荐历史、浏览历史（搜索正文或用户名、清空）
- 支持作者（微信/支付宝/Ko-fi）

## H. SystemUI（需额外的 SystemUI 作用域）

- 通知样式/浮动通知颜色/旧版 Toast 颜色
- 开屏错误记录（只有 SystemUI 能提交）
- 设置生效提示里明确要求“开启系统界面作用域并重启系统界面”

---

## 对照：XHook 现在有什么（0.2）

| 能力 | ReX | XHook 0.2 |
|---|---|---|
| 视频下载 | ✅ 播放器菜单 + 画质 + GIF + 位置/文件名模板 | ✅ 悬浮按钮 + HLS 选档 + 相册（无 GIF/模板/分享面板） |
| 去推广 | ✅ 时间线/回复/Video Tab/RTB + Spaces/新帖/Premium | 🟡 仅 entryId 前缀过滤（DexKit 发现，实验） |
| 机器人/垃圾帖 | ❌（ReX 不做关键词规则） | 🟡 关键词 + 零宽归一化（实验，无正则/白名单/MARK） |
| 时间线状态 | ✅ 滚动位置/Tab 记忆/自动刷新 | 🟡 首页重选 + 生命周期刷新守卫（实验）；滚动/Tab 记忆未做 |
| 本地身份伪装 | ✅ 昵称/@/头像 | ❌ |
| 分享链接清理/域名替换 | ✅ | ❌ |
| 文本选择菜单 | ✅ 分享/翻译/词典 | ❌ |
| 导航/外观 | ✅ 大量 | ❌ |
| Feature Switch 编辑器 | ✅ | ❌ |
| 备份/日志/更新 | ✅ | 🟡 只有设置页 |
| root | 2 条固定命令 | ❌ |

## 移植价值 / 成本（建议顺序）

| 优先级 | 功能 | 价值 | 难度 | 依赖/说明 |
|---|---|---|---|---|
| P0 | 去广告补全：Video Tab + RTB + Spaces/新帖/Premium 推销 | 高 | 中 | 先校准 DexKit 列表 hook；RTB 要 paging state/映射 |
| P0 | 视频下载增强：分享面板入口、GIF→gif、续传、文件名模板 | 高 | 中 | 分享面板参考 x-video-catcher；GIF 编码用 Glide/自写 |
| P0 | 机器人规则：Aho-Corasick、正则、白名单、已认证跳过、MARK 模式、历史 | 高 | 中 | X-ADBlock 的 MIT 规则模型可直接借鉴 |
| P1 | 时间线状态：记忆滚动/Tab、阻止自动刷新 | 高 | 低-中 | 多为 Feature Switch/状态 hook |
| P1 | 分享链接清理 + 域名替换 | 中高 | 低 | intent/share hook，风险小 |
| P1 | 本地身份伪装（昵称/@/头像，仅本机） | 中高 | 中 | 需要找到 profile 显示模型入口 |
| P1 | 文本选择菜单自定义（分享/翻译/词典） | 中 | 低-中 | 与 ReX 的 bv0/wt1 同类 |
| P1 | 备份/恢复 + 日志页 | 中 | 低 | 纯模块 App 工作 |
| P2 | Feature Switch 编辑器 | 高（但危险） | 低 | 需要内置键名目录；建议默认只读/可回滚 |
| P2 | 外观：动态取色、字体、Twemoji、图标替换、开屏动画 | 中 | 高 | 工程量大，纯装饰 |
| P2 | 导航栏/侧边栏/FAB 定制 | 中 | 高 | 依赖 X Compose 结构 |
| P3 | SystemUI 通知样式 | 低 | 高 | 增加 SystemUI 作用域，不建议 |

**结论**：ReX 是“X 客户端定制工具箱”，功能远不止去广告；但它闭源、外观类占大头。
我们的 XHook 适合走“实用功能包”路线：**视频 + 去广告 + 机器人 + 时间线状态 + 隐私/分享**，
外观类最后做或不做。

