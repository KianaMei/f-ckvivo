# 改造 NukeApp：进度与清单

> 源码就是 **`nuke/src`**（由 ganvivo 直接跟踪，无上游 git）。基准与改动清单见 [`src/UPSTREAM.md`](src/UPSTREAM.md)，
> 相对基准的完整 diff 见 [`CHANGES.md`](CHANGES.md)。
> 优先级（用户指定）：**下载/媒体 + 本地分析 SQLite**，界面类暂不做。

## 已完成（已编译 + 单测通过，零回归）

| # | 事项 | 状态 |
|---|---|---|
| S-0 | 续火「今天我已发言 → 跳过不发」 | ✅ `SelfSpeech` + `StreakEngine.skip` + `AutoStreak` 观察；2 条单测 |
| D-1 | 下载**来源选择**（auto/play/h264/h265/download） | ✅ 只解析所选那一路；缺流回退播放地址；设置页中英文案；既有用例已适配 |
| S-1 | 本地分析**存储层**（SQLite） | ✅ 表：visits / daily / hours / ranks / collection（未使用的 `actions` 已删）；后台线程写；失败静默 |
| S-3 | 本地分析**查看界面** | ✅ `AnalyticsDialog`：今天/近 7 天条数与可见时长、作者数、最常刷的 3 个时段（只读本地库） |
| S-2 | 采集接线 | ✅ `LocalAnalytics` 功能（默认关闭），接 `FeedEvents` 的视频切换与播放/暂停；可见时长只算播放中（暂停状态切页不计）；标识先哈希 |
| A15 | 配置导入**合并 / 预览** | ✅ `ModuleConfig.preview()` + `ConfigImportMode`（合并/替换）；先出预览再选，文件整个校验通过才写；新增 3 条单测 |
| A14 | 宿主**整包身份校验** | ✅ `HostApk` 流式 SHA-256 + `KnownHostApks.DIGESTS`（**默认空 = 不启用、不读文件**），后台线程日志 `host_identity …`；新增 6 条单测。填清单后才能对同版本改包报警（目前只写日志，不拦功能） |
| D-8 | **WebDAV 上传**（模块自己保存的文件） | ✅ `WebDavClient`（Basic 认证、MKCOL/PUT、307/308 跟随且换源不转交凭据）+ `MediaSaved` 钩子 + `WebDavUpload` 功能（`TextOption` 地址/用户/密码/目录，后台单线程上传，32 MiB 上限）。**只覆盖模块自己保存的文件**（评论图/语音等）；宿主下载仍缺接点。**尚未上机验证**（本仓库不产安装包） |
| D-2 / D-3 | **自有下载器 + 命名模板 + 目录** | ✅ `DownloadVideoWithName` 接管长按菜单“保存”项（`LppDownloadModule` 点击 + 面板持有的 Aweme），模块自己从播放地址下载，按模板命名写到 `Movies/douyin/<folder>`；`MediaNaming` 支持 10 个标记、UTF-8 字节截断、非法字符替换；没有视频/可播地址时**不接管**，仍走宿主；接管时**跳过宿主自己的隐私/下载校验**，因此默认关闭。11 + 5 条单测。覆盖长按入口（视频 + 图片帖）、进度/取消（D-4a）、SAF 目录（D-4b）与分享面板入口（D-4c）。**尚未上机验证** |
| D-4a | 下载**队列 / 进度 / 取消 / 失败阶段 / UI** | ✅ `DownloadQueue`（2 线程、FIFO、进度、取消、`TaskStage` 失败阶段、保留最近 20 条）+ `DownloadVideoWithName` 接管改为入队 + `DownloadTasksDialog`（设置页轮询快照、取消、清空已完成）+ 中英文案；8 条队列单测、5 条入口单测。**尚未上机验证** |
| D-4b | 图片入口 + SAF 自定义目录 | ✅ 同一个长按入口现也接管**图片帖**（逐张按模板 + 序号命名，写到 `Pictures/douyin/image`）；设置页任务对话框里可选 SAF 目录（`OpenDocumentTree` + 持久授权，写入走 `DocumentsContract`），也可改回相册目录。**分享面板入口仍缺**（需要分享面板锚点）。**尚未上机验证** |
| D-5 | 视频原声 **MP3** | ✅ `SaveVideoAudio`：本模块保存视频时，`MediaCodec` 解音轨 → `jump3r`（纯 Java LAME，192 kbit/s）→ `Music/douyin/audio`；后台单线程、失败静默记日志；`Mp3Encoder` 4 条单测（含“没有输入时不 flush”，LAME 对此会抛断言）。**尚未上机验证** |
| S-4 | 本地分析**报告导出（PNG）** | ✅ `AnalyticsImageExport`：把对话框里的同一组数字绘成 1080 宽的 PNG，写到 `Downloads/Nuke`（Android 10+ 走 MediaStore）；对话框里新增“导出图片”，成败都回显；`AnalyticsSummary.dayRows()` 按日期升序，2 条单测 |
| D-6 | 实况照片**封装** | ✅ `MotionPhoto.wrap()`：把短 MP4 追加到 JPEG 后写 XMP APP1（`MicroVideoOffset` / 版本 / 呈现时间），`videoOffset()` 可回读校验；5 条单测。自有下载器对带 `video` 的图片（实况）走 `saveMotionPhoto`，一张文件落 `Pictures/douyin/image`；`saveMotionPhoto` 下载两个地址、封装后按普通图片保存。**尚未上机验证** |
| D-4c | **分享面板入口** | ✅ 同一个功能往分享面板加一行“按模板下载”：`SharePanelEntry` 拿到当前 Aweme，复用视频/图片/实况的解析与队列；`SharePanelEntries.QUERIES` 并入 `dexQueries`；新增 1 条入口单测 |
| A10a | 信息流**双击分区** | ✅ `FeedGestureZones`：九宫格分区（屏幕三等分）+ 每区一个动作（下一条/上一条/不做）；`FeedPager.previous()` 新增；只挂 `DOUBLE_TAP`，滑动与长按不动；3 条分区映射单测。快捷菜单见 A10b；**长按分区仍缺**，且**未上机验证** |
| A10b（快捷菜单部分） | **双击分区 → 快捷菜单** | ✅ `FeedGestureZones` 新增 `menu` 动作：双击该区域弹出「下一条 / 上一条」，文案走模块资源；长按分区仍缺（需要长按手势锚点）。**未上机验证** |
| D-7 | 私信**媒体保留** | ✅ `CacheChatMedia`：挂 `ChatQueries.INCOMING_MESSAGE`，从原始消息的 `content` JSON 取媒体地址（`ChatMediaUrls`：去重、丢非 http、丢 `~tplv` 缩略图、取扩展名、会话目录名清洗），后台线程下载到 `featureFiles/CacheChatMedia/<会话>/<msgId>_<i>.<ext>`，单文件上限 64 MiB、单条最多 10 个；默认关闭。6 + 3 条单测。**未上机验证** |
| A16a | **自动点赞（规则触发）** | ✅ `AutoLike`：挂 `InteractionDiggPresenter` 构造拿到实例，视频连续播放 `after-seconds` 后点一次宿主自己的 like 按钮（`diggClickLayout.performClick`）；已赞/本次赞过/超出上限都跳过；默认关闭；规则 4 条单测。**未上机验证** |
| A16b | **自动关注（规则触发）** | ✅ `AutoFollow`：挂 `ConfirmQueries.FOLLOW`，从第一次手动关注学到 receiver（弱引用），视频播放 `after-seconds` 后复用该调用关注作者；已关注/本次关注过/超上限跳过；主线程执行；默认关闭；4 条规则单测。**未上机验证** |

**完整 APK 构建（2026-11-18 打通，WSL）**：Windows 上编不了 —— `tools/build_native.py` 与
`build_rust.py` 都 `import fcntl`，且 NDK 预编译目录写死 `linux-x86_64`。改用 WSL Ubuntu（root）装齐
JDK 21、`platforms;android-37.0`、`build-tools;37.0.0`、`ndk;29.0.13113456`、`cmake;4.1.2`、
Rust 1.90.0（`aarch64-linux-android` / `x86_64-linux-android`）、`build-essential`，并在
`/mnt/c` 仓库上设 `git config --global --add safe.directory '*'`，然后：

```sh
cd /mnt/c/mycode/somethingsmall/fuckmyphone/nuke/src
export ANDROID_HOME=/opt/android-sdk
java -Dorg.gradle.appname=gradlew -jar gradle/wrapper/gradle-wrapper.jar   -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:assembleDebug --no-daemon
```

产物 `apps/module/build/outputs/apk/debug/module-debug.apk`（25.7 MB，含 `libnuke_zygisk.so`、
`libnuke_protocol_core.so`、`libquickjs.so`、`libdexkit.so`），签名 `1990df…`（`signing/debug.keystore`）。
它和官方 0.1.2 同 versionCode/versionName 但签名不同，**装机必须先卸载官方包**。

**验证方式（Kotlin 编译/单测；2026-11-17 实测）**：

```sh
cd nuke/src
export ANDROID_HOME=<SDK>          # 有 platforms/android-37* 与 build-tools 即可
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:compileDebugKotlin
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:testDebugUnitTest -x checkProtocolRust --tests "<测试类>"
```

结果：编译通过（Gradle 9.8.2 / JDK 21）；本次涉及的 109 条单测全过（其中队列 8 条）；
整包单测失败集合与上游基线**完全一致**（47 条，全部缺 Rust 原生库），但
`PacketExecutorTest.cancellationPropagatesAndClosesWaiter` 在本机会挂住不返回，
所以全量跑要设超时、或直接用 `--tests` 定向。本机**没有** Rust/NDK/`python3`，
原生任务（`buildZygiskNative` / `checkProtocolRust`）不参与。

## 下一步清单

### 可无宿主锚点直接做

| # | 事项 | 落点 | 说明 |
|---|---|---|---|

### 下载锚点核实（2026-11-17，只读本地 41.2 APK + 规则表）

取证物：`douyin/apk/douyin-41-2-0.apk`（57 个 dex，未改动），
规则表 `douyin/work/assets/douyin-host-rules-41.2.0.tsv`（**本机在**，此前文档说“不在”已过时）。
用 `baksmali` 拆 `classes48.dex` 与 `classes25/28.dex` 得到以下结论：

* `DownloadQueries.TASK_PATHS` 目标类 **在基础 APK 里**：
  `com/ss/android/ugc/aweme/share/business/camera/publish/infer/AbsTask`（classes48.dex）。
  它的 `taskPaths` 在 41.2 里被混淆成 `LIZIZ()Ljava/util/List;`，实现只是
  `listOf(LIZJ)`——`LIZJ` 是构造时写入的**模块内部临时路径**（setter `LJI(String)`），
  不是相册文件名。子类只有 4 个（`X/1Ytl`、`X/1Ytm`、`X/1YtU`、`X/1YtV`），全是图片/实况下载任务。
* 图片临时名在 `X/1Ytr.LIZIZ(AbsTask)` 里拼成 `<external>/douyin/share_<md5>.png`；
  相册里显示的名字由之后的 MediaStore 插入决定。基础 APK 里**没有**
  `MediaStore$MediaColumns;->DISPLAY_NAME` / `RELATIVE_PATH` 的引用（保存走的是宿主自己的兼容层或插件），
  找不到能稳定改写名字的锚点。
* 结论：**“改写 taskPaths 来命名”不可行**，D-2/D-3 只能走自有下载器；
  入口锚点用长按面板（与 DouAi 41.2 规则同源）：
  `LppDownloadModule->LJJIJIIJIL(Landroid/view/View;)V`（`download_native_click`）与
  `LongPressPanelAbsModule->LJIIJ()Lcom/ss/android/ugc/aweme/feed/model/Aweme;`（`download_aweme_getter`）。
  两者所在类名未混淆，`DownloadQueries.PANEL_DOWNLOAD_CLICK` / `PANEL_AWEME` 就是按这个写的。

### 需要宿主锚点（要对着宿主 APK 做 DexKit 取证）

| # | 事项 | 缺什么 |
|---|---|---|
| A9 | 用户搜索 / 关系筛选 / 关注列表分页 | 搜索服务与关注列表 API 的锚点 |
| A10b（长按部分） | 长按分区 | 信息流长按手势入口（现只有 `DOUBLE_TAP`） |
| A12 | 首页侧栏 | `ISideBarCommonBridgeService` 等侧栏接口的方法级锚点 |
| D-6b | **日常（原「限时动态」）保存** | 41.2 已无「限时动态」字样（dex 0 命中），改叫「日常」；需要日常详情页的保存入口锚点 |

## 上机验证记录（2026-11-18）

设备 `10AG632QLS002CF`，抖音 41.2.0（code 410200）。为了不动官方包，debug 构建改成
`io.github.nuke.oss.fork`（`applicationIdSuffix = ".fork"`），与官方 `io.github.nuke.oss` 共存。

| 步骤 | 结果 | 证据 |
|---|---|---|
| 安装 | `Success`，`versionName=0.1.3 versionCode=7` | `nuke/logs/device-2026-11-18/package.txt` |
| Vector 启用 + scope | `modules enable io.github.nuke.oss.fork` → `Enabled`；`scope add ... com.ss.android.ugc.aweme` → 成功 | `scope.txt` |
| 模块加载 | `VectorModuleManager: Loading module io.github.nuke.oss.fork` / `Loaded module … successfully` | `module-load.txt` |
| 模块初始化 | `NukeApp-OSS: io.github.nuke.oss.fork: feature=Init init_choices language=CHINESE bug_reports=false template=recommended enabled=21` | `module-load.txt` |
| 设置入口 | 抖音设置页出现 `NukeApp-OSS` 行（uiautomator 命中 `[955,171][1357,237]`） | 本次会话记录 |
| 设置页 | 点入后 Compose 页面正常渲染，功能分组与开关可见 | `settings-page-texts.txt` |

结论：注入链、模块注册表、设置入口、设置页在 41.2 上都可用。逐项功能验证与 A9/A10b/A12 的锚点取证随后进行。

### hook 装载验证（2026-11-18）

用模块自己的调试页逐条确认（临时把 7 个新功能默认开启后重编、装机、重开抖音）：

| 功能 | 调试页状态 |
|---|---|
| SaveVideoAudio（视频原声 MP3） | 正常 |
| CacheChatMedia（保留聊天媒体） | 正常 |
| WebDavUpload（保存的文件上传 WebDAV） | 正常 |
| FeedGestureZones（信息流双击分区） | 正常 |
| AutoLike（看完自动点赞） | 正常 |
| AutoFollow（看完自动关注） | 正常 |
| DownloadVideoWithName（按自己的命名和文件夹保存视频） | 正常 |

状态概览：**正常 120 / 不可用 0 / 异常 2**。两个异常都是上游功能在 41.2 上找不到 Dex 锚点：

* `AllowAlbumQrScan`：`GateQueries.ALBUM_SCAN` not located（expected one method, found 0）
* `ColorfulDanmaku`：`FeedQueries.DANMAKU_STYLE` not located（expected one method, found 0）

证据：`nuke/logs/device-2026-11-18/debug-hooks.txt`、`debug-page.txt`。

**两个失效锚点已修（2026-11-18）**

* `AllowAlbumQrScan`：41.2 的 dex 里已没有 `scan_to_login_block_path_list`（0 命中），只保留
  `qr_code_scan_rejected`（classes43.dex）；查询改成单串匹配。实测该串所在方法为
  `LIZJ(String,int,ScanFrom)Z`，正是「拒绝相册二维码」的判断。
* `ColorfulDanmaku`：41.2 的 `DDanmakuConfig.toString()` 不再打印 `textColor`（只有 alpha/textSize/
  lineHeight/maxHeight/screenWidth/scrollDuration/supportSeamless/density），原先「靠打印文本找颜色字段」
  的办法失效。查询改成 `DDanmakuConfig(alpha=` + `, scrollDuration=` 两个仍在的串；取色字段改为
  **构造参数兜底**：`findTextColorField` 找不到时，用构造器第二个 int 参数去匹配唯一相等的 int 字段。

**失败记录跨版本自动解锁（2026-11-18）**

旧失败记录会在升级后继续锁着功能（`features_held locked=2`），以前只能到调试页手工清理。
现在 `TikTokHostAdapter.register` 在 `setup.build_version` 与本机 `BuildConfig.VERSION_CODE` 不一致时，
清空全部失败记录并写回新版本号 —— 升级即重试一次，同版本内仍按原规则锁定。
覆盖：`FeatureDiagnosticsTest`（`forgetAll` 一条）、`TikTokHostAdapterTest`（升级解锁 / 同版本仍锁 两条）。

两处都编译通过；装机后因旧失败记录仍把这两个功能锁着（`failure.AllowAlbumQrScan` /
`failure.ColorfulDanmaku` 还在 store 里），需要在调试页点「清理配置文件」并重开抖音，异常数才会归零。

临时默认开启已全部回滚（`git status` 干净），随后重编并装回默认关闭的构建。


## 阻塞记录（2026-11-18）

剩下 **A9 用户搜索/关注列表、A10b 长按分区、A12 首页侧栏** 三项，全部卡在同一条：

* 需要宿主 41.2 的**方法级锚点**，而 `IProfileSideBarService` / `ISideBarCommonBridgeService` /
  `InteractFollowFeedPresenter` 这些类用 jadx `--single-class` 取不出一致结果（类不在可解析 dex），
  离线无法确认方法名与参数。
* 要实机确认就必须装自建 APK；官方包签名 `72b63d…` 与我们的 `1990df…` 不同 → **必须先卸载官方包**
  （设备变更 + `PACKAGE_*` 广播风险，见 `AGENTS.md` §1.0 与 `root/INCIDENT-2026-11-15-packageinstaller.md`），
  这一步没有用户明确同意不能做。
* APK 已就绪：`apps/module/build/outputs/apk/debug/module-debug.apk`（0.1.3/7，25.7 MB，6 个 .so）；
  装机与逐项验证步骤见 [`DEVICE-VERIFY.md`](DEVICE-VERIFY.md)。

拿到装机许可后，按 A9 → A10b → A12 顺序做：装 → logcat 抓锚点 → 加 DexQuery → 实现 → 单测 → 提交。

## 执行顺序（2026-11-16 确认）

按"先做不需要宿主取证的，再做需要取证的"推进；每项都要过编译门禁、写完单测、提交后推送。

**第一批（无需新锚点）**

1. ~~D-5 视频原声 MP3~~ —— 见上节，缺“下载完成”接点，暂缓。
2. ~~D-8 WebDAV 上传~~ —— 同上。
3. ~~A15 配置导入合并/预览~~ —— **已完成**（预览 + 合并/替换）。
4. ~~A14 宿主整包身份校验~~ —— **已完成**（整包 SHA-256 + 已知清单，默认空 = 不启用）。

**第二批（需要对着宿主 APK 做 DexKit 取证，只读采样即可）**

5. ~~D-2 / D-3 命名模板 + 自定义目录~~ —— **已完成**（2026-11-17，改为自有下载器，见“下载锚点核实”）。
6. ~~D-4a~~、~~D-4b 图片入口 + SAF 目录~~、~~D-4c 分享面板入口~~、~~D-5 MP3~~、~~D-6~~ —— **已完成**；**下一项 D-7**
7. ~~D-6 实况照片封装~~、~~D-7 私信媒体保留~~ —— **已完成**；**下一项 A9/A10b/A12/A16**
8. A9 / A10 / A12 / A13 / A16 用户搜索、九宫格手势、侧栏、插件逐项管理、自动点赞/关注

**取证方式（不改设备）**：`adb exec-out` 拉宿主 APK / dex 到本地，用本机 jadx（仓库不存副本）
交叉定位，再写成 DexKit 结构化匹配；不安装、不重启、不改任何设备文件。
注意：`douyin/work/assets/douyin-host-rules-41.2.0.tsv`（41.2 已核对的类/方法描述符）**本机在**，
换机后若丢失，用 `douyin/tools/dy_rules_fingerprint.py` 重建。

## 仓库约定

* 改动直接改 `nuke/src`，按功能提交；`src/UPSTREAM.md` 与本文件同步更新。
* 构建与产物不在本会话范围（用户要求不产出安装包）。
* 上游更新（可选）：另开临时克隆与新版本 diff，手工并回；不要引入上游 `.git`。
