# Nuke Fork 研究与改造总档

> 本文件由原 uckmyphone/nuke/*.md 与 douyin/docs/archive/*.md 合并收敛而来；代码改动台账见同目录 UPSTREAM.md。
> 设备操作与红线请遵守 ../../fuckmyphone/AGENTS.md §1。

---

## Nuke 战线总览 (原 nuke/README.md)

# nuke/ — NukeApp-OSS 分析工作区

对 `io.github.nuke.oss`（NukeApp-OSS）的一次只读 dump + 逆向。
**结论先看 [`ANALYSIS.md`](ANALYSIS.md)**；与自家 DouAi-41.2 的逐项对比见 [`COMPARE-vs-douai.md`](COMPARE-vs-douai.md)。
这里只是目录索引与复现命令。

## 目录

| 路径 | 内容 | 入库 |
|---|---|---|
| `ANALYSIS.md` | 分析正文（注入链 / 架构 / 功能面 / MCP / 风控 / 签名固定 / **开源核查**） | ✅ |
| `COMPARE-vs-douai.md` | 与 `douyin/reference/DouAi`（我们的 41.2 融合分支）的功能与实现对照 | ✅ |
| `GAP-ours-vs-nuke.md` | **我们有、NukeApp 没有**的能力清单（含移植成本） | ✅ |
| `PLAN-nuke-upgrade.md` | **迁移到 NukeApp 的改造清单**（P0~P3 + 已完成项 + 验证方法） | ✅ |
| `CHANGES.md` | `src/` 相对上游基准的**完整 diff**（26 改 + 41 新增，含验证记录） | ✅ |
| `src/` | **我们维护的 NukeApp 源码**（直接改，ganvivo 跟踪；基准版本与改动清单见 `src/UPSTREAM.md`） | ✅ |
| `apk/nuke-base.apk` | 从设备拉取的原始 APK（6.8 MB） | ❌ gitignore（换机需按上面命令重新拉取） |
| `extract/` | APK 解包：dex、lib、assets、`module.prop`、`customize.sh` | ❌ |
| `jadx/` `jadx2/` | jadx 反编译（`jadx2` 带 `--show-bad-code`，关键类可读） | ❌ |
| `DEVICE-VERIFY.md` | **上机验证 runbook**：安装/启用/证据来源/逐项检查/失败回滚（每步都要用户同意） | ✅ |
| `logs/` | 证据：feature 映射、设置页文案、JNI 表、MCP 工具串、风控 flag、Vector DB 快照；`upstream-*` 为上游源码核对件（tree / signer / flags / tools / ci） | ✅ |

## 复现（只读，Git Bash）

```sh
# adb 不在 PATH 就 export ADB=/全路径/adb；SDK 用 ANDROID_SDK_ROOT 或 ANDROID_HOME 指定
ADB=${ADB:-adb}
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n1)
export MSYS2_ARG_CONV_EXCL='/data'          # 否则 /data 被改写成 C:/Program Files/Git/data

# 1) 定位并拉 APK
P=$($ADB shell su -c 'pm path io.github.nuke.oss' | sed 's/^package://' | tr -d '\r')
$ADB shell su -c "cp '$P' /data/local/tmp/nuke_base.apk"
$ADB pull /data/local/tmp/nuke_base.apk apk/nuke-base.apk

# 2) 解包
unzip -o -q apk/nuke-base.apk -d extract

# 3) 反编译（关键类 defpackage.id3 需要 --show-bad-code）
# jadx 按机器自备（仓库里没有副本；把 jadx.bat 换成你本机的路径）
jadx.bat --no-res -d jadx  apk/nuke-base.apk
jadx.bat --show-bad-code --no-res -d jadx2 apk/nuke-base.apk

# 4) 设置页文案（1454 条 string 资源）
"$BT/aapt2.exe" dump resources apk/nuke-base.apk > logs/resources.txt

# 5) 签名；证书 SHA-256 应与 .so 内嵌摘要 72b63dc6…b5b32a 一致
"$BT/apksigner.bat" verify --print-certs apk/nuke-base.apk
```

## 构建与验证 `src/`（2026-11-17 实测）

```sh
cd nuke/src
export ANDROID_HOME=<SDK>        # 需要 platforms/android-37* 与 build-tools（本机 37.0.0）

# 编译（不需要 Rust/NDK；compileSdk 37 与目录名 android-37.0 的差异用下面的开关绕过）
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:compileDebugKotlin

# 单测：一定用 --tests 定向，整包会卡在 PacketExecutorTest（缺原生库时它不返回）
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:testDebugUnitTest -x checkProtocolRust \
  --tests "io.github.nuke.oss.app.tiktok.AnalyticsRecorderTest" \
  --tests "io.github.nuke.oss.app.shared.settings.ModuleConfigTest" \
  --tests "io.github.nuke.oss.app.shared.integrity.HostApkTest"
```

* 完整 APK（`assembleDebug`）会连带跑 `buildZygiskNative` / `buildProtocolNative`，
  需要 `python3`（任务里写死）+ Rust 工具链 + NDK 29；本机三者都没有。
* 整包单测的失败集合与上游基线一致（47 条，均缺 Rust 原生库），可作为“没引入新失败”的对照。
* 签名：`nuke/src/local.properties` → `signing/debug.keystore`（相对路径），自建包与官方包**不能** `install -r`。

## 完整 APK 构建（2026-11-18 打通）

Windows 上编不了（`tools/*.py` 用 `fcntl`、NDK 路径写死 `linux-x86_64`）。在 WSL Ubuntu 里装齐
JDK 21 / `platforms;android-37.0` / `build-tools;37.0.0` / `ndk;29.0.13113456` / `cmake;4.1.2` /
Rust 1.90.0 + 两个 android target / `build-essential`，`git config --global --add safe.directory '*'`，
然后：

```sh
cd /mnt/c/mycode/somethingsmall/fuckmyphone/nuke/src
export ANDROID_HOME=/opt/android-sdk
java -Dorg.gradle.appname=gradlew -jar gradle/wrapper/gradle-wrapper.jar   -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:assembleDebug --no-daemon
```

产物 `apps/module/build/outputs/apk/debug/module-debug.apk`（约 25.7 MB，5 个 .so 齐全，签名 `1990df…`）。
**与官方包同 versionCode 不同签名，装机要先卸载官方包。**

## 设备现状（采集时，2026-11-17 只读复核仍一致）

* 已安装 APK（v0.1.2 / code 6），**未**在 Vector 里注册/启用；
* KernelSU `/data/adb/modules` 里**没有** `nukeapp-oss` → Zygisk 形态未安装；
* 抖音 `no_backup/nuke` 不存在 → 从未在抖音进程内运行。

启用方式（属设备变更，需用户明确授权；**禁止**为它重启手机——本机 root 是临时 LKM）：
Vector 启用 + scope 勾 `com.ss.android.ugc.aweme` → 强停抖音再打开。


---

## 改造与升级清单 (原 nuke/PLAN-nuke-upgrade.md)

# 改造 NukeApp：进度与清单

> 源码就是 **`nuke/src`**（由 ganvivo 直接跟踪，无上游 git）。基准与改动清单见 [`src/UPSTREAM.md`](src/UPSTREAM.md)，
> 相对基准的完整 diff 见 [`CHANGES.md`](CHANGES.md)。
> 优先级（用户指定）：**下载/媒体 + 本地分析 SQLite**，界面类暂不做。

## 当前状态（2026-11-18 收尾）

计划已清空：能离线做完的全部落地，剩下四项按“宿主无对外扩展点”关闭。

| 项 | 结论 |
|---|---|
| 已完成功能 | 分析/配置/下载/媒体/手势/互动/A16 等 20+ 项，均编译 + 定向单测通过 |
| 41.2 失效锚点 | `AllowAlbumQrScan` 上机 **ACTIVE**；`ColorfulDanmaku` 分析命中 `LX/1OG1;->toString()`，默认关闭 |
| 升级解锁 | 版本变化自动清空失败记录（`features_unlocked`），`locked` 已归零 |
| A9 用户搜索 | 关闭：用户搜索不走 `SearchUserProfileFragment`；`ISearchService` 无自包含入口 |
| A12 首页侧栏 | 关闭：`LJI()` 是 42 项内部组件注册表，无「添加条目」API，硬塞有崩「我」页风险 |
| D-6b 日常保存 | 关闭：41.2 无 `Story*Save/Download/Export` 类 |
| A10b 长按分区 | 放弃（用户 2026-11-18 明确不修） |
| 装机版本 | 0.1.9 / versionCode 13（`io.github.nuke.oss.fork`，与官方包共存） |
| 取证工具 | `HostProbe`（默认关），签名写 `memory.HostProbe.*`，不依赖 logcat |

---

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

## 上机验证记录（2026-11-18，当前构建 0.1.9 / 13）

设备 `10AG632QLS002CF`，抖音 41.2.0（code 410200）。debug 构建用 `io.github.nuke.oss.fork`
（`applicationIdSuffix = ".fork"`），与官方 `io.github.nuke.oss` 共存。

| 项 | 结果 | 证据 |
|---|---|---|
| 安装 | `versionName=0.1.9 versionCode=13` | `nuke/logs/device-2026-11-18/package.txt` |
| Vector 模块与 scope | `enabled`；scope `com.ss.android.ugc.aweme` | `scope.txt` |
| 模块加载与状态 | `runtime_ready host=tiktok backend=libxposed102`；`locked=[]`、`safe=false` | `mirror.json` |
| 41.2 锚点 | `ALBUM_SCAN v2 -> LX/1R1K;->LIZJ(...)Z`；`DANMAKU_STYLE v3 -> LX/1OG1;->toString()` | `anchors.txt` |

早期构建（0.1.3/7）曾在调试页逐条确认 7 个新功能装载正常（正常 120 / 不可用 0 / 异常 2）；
两个异常就是上表这两个锚点，现已修复；当时的 dump 与截图已按 `AGENTS.md` §6 新规则清理。

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


## 阻塞记录（2026-11-18，2026-11-18 晚更新）

fork 包已上机（`io.github.nuke.oss.fork`，现 **0.1.6 / versionCode 10**），官方包未动，装机许可问题已解除。
仍缺方法级锚点的三项，按离线取证结论处理：

| 项 | 离线结论 | 状态 |
|---|---|---|
| A9 用户搜索 / 关注列表 | `ISearchService` 数百方法、全是混淆名；`SearchUserProfileFragment` 要宿主 Activity + 会话对象才能拉起，没有「给关键词/uid 就能调」的自包含入口 | 缺锚点 |
| A12 首页侧栏 | `ProfileSideBarService` 只是转发壳，实现在 `sidebar/profilesidebar/core/service/ProfileSideBarServiceImpl`；接口 12 个方法全是 `LIZIZ()V`/`LJII(LX/0fjI;)LX/0ffV;`，看不出注册入口 | 缺锚点 |
| D-6b 日常（原「限时动态」）保存 | 41.2 中 `限时动态` 0 命中；按 `Story*Save/Download/Export` 扫类名 0 命中，`story` 只剩发布任务 | 大概率宿主无此功能 |
| A10b 长按分区 | 用户明确「长按分区没啥用不用修了」（2026-11-18） | 已放弃 |

**探针实测（2026-11-18 晚，`HostProbe` 0.1.9/13）**

在抖音进程里用宿主 ClassLoader 读到的真实签名（存 `memory.HostProbe.dump`）：

* `ISearchService` 287 个方法，含 `buildSearchIntent(Context,Uri)->Intent`、`buildSearchResultParam(String)`
* `SearchUserProfileFragment` 32 个方法（`onCreate(Bundle)`、`kL(SearchResultParam)`）
* `ProfileSideBarServiceImpl` 14 个方法（`LJI()->Map`、`LJII(0fjI)->0ffV`、`LJIILIIL(0fjI)->0ffV`）

两个只读 hook 的收获：

* `LJI()` 返回 42 项，**key 全是 `self_help_ad_component`、value 全是 `java.lang.Class`** —— 这是宿主自己的组件注册表，
  不是「往侧栏加一项」的列表；硬塞一项需要实现宿主的组件契约，塞错会让「我」页崩
* `SearchUserProfileFragment.onCreate` **没有被触发**（`memory.HostProbe.search_args` 缺失）：用户搜索走的不是这个 Fragment

结论：A9/A12 不是「找不到锚点」，是**宿主没有对外承诺的扩展点**；继续做的唯一方式是实现宿主内部组件契约，
风险是崩宿主页面。D-6b 宿主无保存日常的类。

需要的取证手段只剩运行时探针（把宿主真实调用栈打出来），或等宿主版本出现自包含 API。

**本轮已验证的锚点修复**

* `AllowAlbumQrScan`：41.2 少了 `scan_to_login_block_path_list`，改单串 `qr_code_scan_rejected` + `version = 2`；
  上机日志 `feature=AllowAlbumQrScan state=ACTIVE`。
* `ColorfulDanmaku`：41.2 的 `toString()` 不再打印 `textColor`，改用单串 `DDanmakuConfig(alpha=`（全 dex 仅 1 处）
  + `version = 3`；颜色字段在打印法失效时用构造器第二个 int 参数兜底。该功能**默认关闭**，需在设置里打开才会装载。
* 顺带修：失败记录跨版本自动解锁（`features_unlocked`）、`FailureStore.clearAll` 连无法解码的旧记录一起删。

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


---

## 架构与逆向分析 (原 nuke/ANALYSIS.md)

# NukeApp-OSS 0.1.2 分析（dump 自本机 vivo X Fold6）

> 采集时间：2026-11-16 19:07（本机时间）
> 目标包：`io.github.nuke.oss`（应用名 `NukeApp-OSS`）
> APK sha256：`873a32807cf808deb4125c357e783c37c4607b7a7aceac8eb16bf89f7b31188c`（6,799,320 B）

## 0. 一句话结论

**给抖音（`com.ss.android.ugc.aweme`）用的 Xposed/Zygisk 模块**：同一个 APK 同时是
① 管理界面 App（Compose）、② 传统 Xposed（API 82）模块、③ 现代 libxposed（API 102）模块、
④ Zygisk + LSPlant 模块（可直接改名 `.zip` 刷进 KernelSU/Magisk）。
进去以后在抖音设置页多出「NukeApp-OSS」一行，里面是 100 多项开关；此外还有
**JS 脚本插件（QuickJS）**、**MCP 服务器（把抖音账号能力暴露给 LLM Agent）**、
**注入环境自检（Frida/Xposed/root/SELinux…）** 三块非常规能力。

上游开源：<https://github.com/dartcv/NukeApp-OSS>（MIT，`dev` 分支，272 star）。
本 APK 的 git 版本为 `93539571b51af11c4ed042e1e10750fb1c6061c5`，正是上游 `v0.1.2`
release 的提交（AGP 9.1.1，GitHub Actions 构建：native 里留有
`/home/runner/work/NukeApp-OSS/NukeApp-OSS/...` 路径）。

## 1. 现场状态（只读采集，未做任何设备变更）

| 项 | 值 |
|---|---|
| 设备 | vivo X Fold6，Android 17 / OriginOS 7（本项目主线设备） |
| 模块 APK | 已安装，firstInstall `2026-11-16 17:26:33`，lastUpdate `19:07:48`（v0.1.2 / code 6） |
| 抖音 | 已安装 `com.ss.android.ugc.aweme` 41.2.0（code 410200） |
| KernelSU 模块目录 | `/data/adb/modules` 只有 `zygisk_shamiko` / `zygisk_vector` / `zygisksu` → **Zygisk 形态未安装** |
| Vector（LSPosed） | `modules_config.db` 的 `modules` 表**没有** `io.github.nuke.oss` → **未在框架里启用/注册** |
| 模块数据目录 | `/data/user/0/io.github.nuke.oss/` 只有 `cache`/`code_cache` → 设置界面基本没被使用时写入过 |
| 抖音侧运行痕迹 | `/data/user/0/com.ss.android.ugc.aweme/no_backup/nuke` 不存在 → **从未在抖音进程里跑起来过** |
| 测试宿主 | `io.github.nuke.oss.fixture` 未安装 |

结论：**这是一个干净的待分析样本，设备上没有任何执行痕迹**。

## 2. APK 内部结构（三合一 + 原生）

```
AndroidManifest.xml          xposedmodule/xposedminversion=82/xposedscope
assets/xposed_init           io.github.nuke.oss.loader.xposed82.Xposed82Entry
module.prop                  id=nukeapp-oss / version 0.1.2 / zygisk+LSPlant 说明
customize.sh                 KernelSU/Magisk 安装脚本（SKIPUNZIP=1，仅 arm64）
classes.dex                  4.2 MB（2566 个类，自己的代码只有 ~21 个类，其余是 Compose/KotlinX）
lib/arm64-v8a/libnuke_zygisk.so        582 KB  Zygisk 入口 + LSPlant + Dobby + DexBuilder
lib/arm64-v8a/libnuke_protocol_core.so 1.1 MB  Rust：IM 协议、MCP HTTP 服务、签名/风控
lib/arm64-v8a/libquickjs.so            790 KB  QuickJS（脚本引擎）
lib/arm64-v8a/libsilk_codec.so         347 KB  Silk 语音编解码（语音消息转发用）
lib/arm64-v8a/libdexkit.so             395 KB  DexKit（运行时 dex 定位）
third-party/*.txt            LSPlant/Dobby/DexBuilder/parallel-hashmap/quickjs-kt/Silk/xDL/Zygisk 许可
```

`customize.sh` 的行为值得注意：它把 APK 里的 `classes*.dex` 解到
`$MODPATH/payload/`、`libnuke_zygisk.so` 解到 `$MODPATH/zygisk/arm64.so`，
其余 native（protocol core / quickjs / silk / dexkit）解到 `$MODPATH/natives/`，
**宿主进程的类由 payload dex 在内存里加载，不需要另装模块 APK**。

签名：自签证书 `CN=C`，证书 SHA-256 = `72b63dc6…b5b32a`。**这个摘要被硬编码在
`libnuke_protocol_core.so` 里**（见 §8），即"未改包/未重签"才被承认。

## 3. 注入链：三个入口 + 一个仲裁

```
Xposed(82)  ──> Xposed82Entry.handleLoadPackage ─┐
libxposed102──> Libxposed102Entry.onPackageReady ─┼─> RuntimeBootstrap(do2).a(...)
Zygisk      ──> libnuke_zygisk.so ─ payload dex ──┤     ├─ host adapter 选择（按包名）
                (InMemoryDexClassLoader)          │     ├─ scope 校验
                ZygiskEntry.bootstrap() ──────────┘     ├─ 模块签名校验
                                                        └─ 单例：已有运行时就拒绝
```

* **进程内 token 仲裁**：`do2.a()` 里 `co2` 单例 + `claim`（`qg1(eg2)`）保证同一进程里
  "先到者生效"，后到的入口直接 `xposed82_skipped entry already claimed` / `detach()`。
* **作用域**（`defpackage.v11`）：只认两个包名——
  `io.github.nuke.oss.fixture`（测试宿主）与 `com.ss.android.ugc.aweme`（抖音）；
  抖音侧只接**主进程**和**非 `sandboxed_process` 的子进程**，且子进程名要匹配
  `mini(?:app|game)(?:[0-9]+|X)`（小程序/小游戏/热更新），即 `u11.i`(主) / `u11.j` / `u11.k`。
  其余进程 `run` 直接被忽略（`The process is outside the host scope`）。
* **子进程不重复建配置**：主进程把开关快照写到
  `…/no_backup/nuke/process-mirror.json`（含 `safe_mode`、被 hold 的 feature 列表），
  子进程读它决定装哪些 hook（`child_ready/child_skipped … no_mirror`）。

### Zygisk 侧（`libnuke_zygisk.so`，C++）

* `zygisk_module_entry` → 专用化列表里含 `com.ss.android.ugc.aweme`、`io.github.nuke.oss.fixture`；
* 读模块目录下 `payload/*.dex` → `InMemoryDexClassLoader` → 找
  `io.github.nuke.oss.loader.zygisk.ZygiskEntry.bootstrap(...)` → 反射调用；
* 同时 `RegisterNatives` 注册 `io.github.nuke.oss.backend.lsplant.JniLsplantDriver`
  的 `nativeInitialize/nativeHook/nativeUnhook/nativeInvokeConstructor`（LSPlant 真正的落地）；
* 进程内状态写到 **`/cache/nukeapp-zygisk.txt`**（README 里也提示读这个文件判断
  `bootstrap=returned|failed`），失败分支写得很细（DEX 未分配、构造失败、RegisterNatives 失败…）；
* 还有一个 Dobby 的 inline hook（字符串 `Failed to hook EncodeGenericId, attaching
  debugger may crash the process`），属于反调试/环境相关的旁路处理。

## 4. Java 层运行时架构

```
loader/{xposed82,libxposed102,zygisk}   ← 三个入口（薄）
backend/{xposed82,libxposed102,lsplant} ← hook 后端抽象 f01，统一 attach/support/unhook
do2 (RuntimeBootstrap, fo2)             ← 唯一运行时创建者，含全部守卫
host adapter b11:  id3 = tiktok 宿主 / tp0 = fixture 宿主
feature   go2/kn0/vj3                   ← 107 个注册项（hv0.a 列表）
settings  feature  cy2 = "SettingsEntry" ← 往抖音设置页插一行入口
```

* 后端能力面 `g01` 有差异（LSPlant 只开放"便携方法回调"，xposed/libxposed 更多）；
  运行时会 `support()` 检查，不支持就不装。
* `id3`（抖音适配器）是核心：拿 `dataDir`/`sourceDir` → 读/建设置存储 →
  `DexKit` 定位关键类 → 构造每个 feature 的 hook → 装上；**定位不到就"hold"该功能**
  （日志 `features_held dex_unlocated=N`），避免抖音更新后乱炸。
* 设置存储：`/data/data/com.ss.android.ugc.aweme/no_backup/nuke/`
  ├─ `<version>/…`（按 APK 长度+修改时间分桶，避免串版本）
  ├─ `process-mirror.json`（主进程 → 子进程开关镜像）
  ├─ `streak/`（自动续火的状态，发送前落盘，避免重复发）
  └─ `feature-files/`（功能自己落盘的数据）
* UI 主题项也在这套 KV 里（`ui.theme` / `ui.language` / `ui.accent` / 模糊/透明度…）。

## 5. 功能面（107 个注册项 / 114 条带说明的开关）

从 `hv0.a` 逐个解出的 feature ID（`logs/features_map.txt`、`logs/feature_ids_unique.txt`）
和设置页文案（`logs/feature_catalog.tsv`）看，覆盖面大致是：

| 分类 | 代表功能 |
|---|---|
| 视频/播放 | `SetVideoQuality`、`ForceHighVideoQuality`、`CustomPlaybackSpeed`、`KeepAutoplayOn`、`LongPressSpeed`、`PauseOnPlaybackCompleted`、`BlockForegroundPlaybackResume`、`PortraitVideoSize`、`OfflineCacheCount`、`AlwaysShowProgress`、`ProgressTime` |
| 广告/推荐 | `SkipSplashAd`、`SkipChapterAds`、`HideRelatedRecommendations`、`HideTeenModeDialog`、`DisableUpdateChecks`、`DisableHotUpdates`、`SkipMiniApp{InterstitialAd,RewardAd,RealName}`、`SkipMinorCheck` |
| 界面精简 | `CleanMode`、`HideFeedComponents`、`HideVideoControls`、`HideStatusBar`、`Trim{Share,Comment,LongPress}Panel`、`TrimProfileTabs`、`HideCommentTabs`、`HideAvatarRings`、`TransparentNavigationBar`、`CustomDpi`、`ControlOpacity` |
| 评论 | `CommentDetails`、`CommentPreciseTime`、`CustomCommentStyle`、`FilterComments`、`BlockEmptyCommentInput`、`ShowCommentGender`、`ShowPreciseCounts`、`CleanCommentCopy`、`CheckSentComments`、`CommentPanelLook` |
| 聊天/消息 | `BlockReadStatusUpload`（不上报已读，支持黑/白名单）、`PreventMessageRecall`/`…2`、`RepeatMessage`（+1 复读）、`CustomChatBubble`、`MessageSendTime`、`MessageTabMenu`、`AllowAnyForward`、`VoiceForward`、`ShareFriendGroups`、`DisableChatEffects`、`KeepViewOnceMessages`、`HideMessageTopFriends` |
| 自动化 | `AutoStreak`（自动续火）、`AutoSparkClaim`（定时领火星，可只选好友）、`AutoPetFeed`（自动喂宠物）、`OpenRedPackets`（抢红包）、`CustomStreakDays`、`TimedClose` |
| 抓取/导出 | `AllowFeedDownloads`、`DownloadOriginalImages`、`AutoOriginalMedia`、`CoverInfo`、`VideoBottomInfo`、`AddAuthorInfoRow`、`LongPressCopy` |
| 隐私/环境 | `RandomLocationGradient`（IP 属地随机渐变）、`OpenExternalLinks`、`BypassListenFilter`、`AllowAlbumQrScan`、`BlockNotificationPrompts` |
| 特殊 | `McpServer`（§6）、`CustomProtocolSender`（裸 IM 协议）、`SettingsEntry`、`Init` |

设计上比较克制的地方（也是可信度的来源）：
* 大部分功能是"改本地判断/改 UI"，**发消息类功能一律先落盘再发，`unknown` 状态绝不重发**；
* 默认只开一项（`DisableDoubleTapDigg`），其余默认关；
* 开关改动要求"强停抖音再开"，不做热重载（`onHotReloading` 返回 false）。

## 6. MCP 服务器（最"重"的一块）

Feature `McpServer`（`lp1`）→ JNI `NativeMcp` → **`libnuke_protocol_core.so` 里一个
手写的 HTTP/1.1 + JSON-RPC 2.0 服务器**（MCP 协议版本 `2025-06-18`，serverInfo `nuke-douyin`）。

配置（`vo1` → `open(port, lan, json)`）：
```
{ "port": 1024..65535, "lan": bool, "tokens": [ {
    id, name, secretHash, enabled, account,
    permissions: [READ_CONTACTS|SEND_TEXT|SEND_IMAGE|CLAIM_REWARDS|MARK_READ|SEND_PROTOCOL],
    allowAll: bool, conversations: [conversationId…] (≤2000), limit: 1..600/min, createdAt } ] }
```
`issue(entropy, …)` 生成 token（返回 `{id, secretHash, token}`，明文 token 只给一次），
HTTP 侧 `Authorization: Bearer`。

工具（tool 名与语义都写在 `.so` 的提示词里，`logs/native_mcp_tools.txt` 是原始字符串）：

| tool | 作用 | 关键约束 |
|---|---|---|
| `douyin_list_contacts` | 列出可触达的好友/群 | limit 1..200，offset ≤100000 |
| `douyin_find_contact` | 按昵称/备注/群名模糊找 | 1..20 条，重名要跟用户确认 |
| `douyin_send_text` | 发文本 | 正文 1..5000 字；`sent` 只在服务器确认后返回 |
| `douyin_send_image` | 发一张 JPEG/PNG | base64，≤5 MB，可选不压缩 |
| `douyin_claim_sparks` | 领"火花"奖励 | 只领已挣到的；分页 `next/skip`；`unknown` 绝不重复提交 |
| `douyin_claim_closeness` | 领"亲密值" | 同上；遇到 unknown 直接停并告知 |
| `douyin_mark_all_read` | 一键全读（含陌生人信箱） | 需"全聊天"权限；无单聊版本、无撤销 |
| `douyin_send_protocol` | 发**裸 IM protobuf** | 需全聊天权限；4 MiB 上限；不校验内容；超时不许重试 |

协议侧防护（从错误码字符串提取）：`UNAUTHORIZED`、`ORIGIN_NOT_ALLOWED`、
`RATE_LIMITED`（`Retry-After: 60`）、`TOKEN_REVOKED`、`PERMISSION_DENIED`、
`ALL_CHATS_REQUIRED`、`CONTACT_NOT_FOUND`、`CONVERSATION_NOT_ALLOWED`、
`RESPONSE_TOO_LARGE`；HTTP 解析器是自己写的（`INVALID_CONTENT_LENGTH`、
`TRANSFER_ENCODING_NOT_SUPPORTED`、`CRLF_REQUIRED` 等），也自带 ZIP 读取（脚本包）。

线程模型：`NativeMcp.serve(handle, host)` 在阻塞线程里跑服务器，
每个请求回调 Java 的 `McpHost.request(...)`（`ng0` 实现），由它驱动抖音内的 IM 逻辑；
`configure(handle, json)` 可热更新 token 表，`snapshot(handle)` 取状态。

> 风险提示：`SEND_PROTOCOL` + `allowAll` 的 token ≈ **账号全权**；
> `lan: true` 会把端口开到局域网。这两点属于本项目里最该看清的开关。

## 7. 原生组件与 JNI 面

`NativeMcp`/`NativeProtocol`/`NativeRisk`/`NativeSignature`/`NativeScriptPackage`
全部只做 JNI 转发，实现在 `libnuke_protocol_core.so`（Rust：`jni` crate、`zip` crate、
自带 protobuf 编解码与 HTTP）。完整符号表见 `logs/native_jni.txt`，共 17 个：

* `NativeMcp`: `open/close/configure/issue/snapshot/serve/entropyBytes`
* `NativeProtocol`: `abiVersion`(=1)、`encode/decode`、`wrapPayload`、`correlate`、`validateRequest`
  （把 IM `RequestBody` protobuf 打包/拆包，并按 `cmd`/`seq` 关联回包；
  字符串里有 `douyin_send_text`、`no tracer field in the status` 等调试提示）
* `NativeSignature`: `signerDigest`、`verify`
* `NativeRisk`: `scan`
* `NativeScriptPackage`: `install`

## 8. 完整性 / 反检测（`NativeRisk`）

`NativeRisk.scan()` 在宿主进程里跑，产出一份"设备信任"报告，管理界面有对应面板
（`device_trust_*` 文案）。**29 个 flag，分 4 类**（源码 `rust/src/riskctl/flags.rs`，
见 `logs/native_risk_flags.txt`）：

| 类别 | flag |
|---|---|
| `Hard`（单独即可判定） | `DEBUGGER`、`GDB_PTRACE`、`FRIDA`、`LINKER_ENV`（LD_PRELOAD/LD_AUDIT）、`FD_SUSPICIOUS`、`EXEC_INTEGRITY`（模块库/libc 与磁盘文件不一致） |
| `Weak`（需伴随 Hard 才算） | `SIGNATURE_HOOK`、`PROC_MAPS`、`HOOK_SURFACE`、`MEMFD_DELETED`、`WX_MAPPING`、`NATIVE_PATH`、`MULTI_MAPPING`、`DUMP_SURFACE`、`OTHER_HOOK`、`NETWORK_SURFACE` |
| `Env`（只在报告里说明） | `XPOSED`、`ZYGISK`、`MAGISK`、`ROOT_TOOL`、`SU_BINARY`、`BOOT_UNLOCKED`、`SELINUX_PERMISSIVE`、`KERNEL_TAMPER`、`DEBUGGABLE_BUILD` |
| `Note`（仅供参考） | `NATIVE_BRIDGE`、`ADBD_RUNNING`、`TEST_KEYS`、`PROXY` |

**tripwire 逻辑**（源码注释写得很明白，和"旧版 native 引擎"一致）：
`Hard` 单独触发；`Weak` 与 `KERNEL_TAMPER` **只有在一个 `Hard` 同时存在时才计入**——
"一台 root 过的手机或 JIT 的可写可执行内存本身不算数，只有这些东西挨着调试器/Frida 时才算"。
所以本机常态（Zygisk + KernelSU + 解锁/verity、SELinux 打补丁）只是 `Env` 项，
面板会逐条列出状态，但**不会**因为 root 本身报"红"。等级字符串：`ok/info/warn/alert/unknown`。

签名固定（自毁逻辑）：
* `MainActivity.onCreate` 先 `NativeSignature.verify(sourceDir)`，不通过就
  `Log.e("NukeApp-OSS/Signature", …)` → `finishAndRemoveTask()` → `killProcess()`；
* 宿主内再校验**模块 APK** 的 v2/v3 签名（日志 `module_signature_refused …`，
  错误文案 `The module APK is not signed with the release certificate (signer …)`）；
* 内嵌摘要 `72b63dc62e5da6697bd82454537e997b2741be47788a0a944dcad6a58db5b32a`
  与 APK 实际签名证书 SHA-256 完全一致 → **这份样本是官方签名构建，能跑；被重签会直接被杀**；
* 但 pin **不是**硬编码在源码里的：`rust/src/signer/verify.rs` 用
  `option_env!("NUKE_MODULE_SIGNER_SHA256")`，值由 `apps/module/build.gradle.kts`
  从 `local.properties` 里那把发布 key 的证书摘要算出来注入（CI 用 secrets 里的 release
  key）。没有这把 key 时 `Verdict::Unconfigured`，`ModuleSignature.refusal()` 返回
  `null`（只记 `module_signature unconfigured`）→ **自己从源码构建的 APK 不会被拦**。
  pin 的用途是"防别人重签官方包冒充官方"。

  > 2026-11-17 备注：`nuke/src/local.properties` 已入库并指向 `signing/debug.keystore`
  > （见仓库根 `signing/README.md`），所以现在从 `nuke/src` 构建时 pin **是 configured**：
  > native 里嵌的是 debug A 的证书摘要，用同一把 key 自签的包自然通过。删掉
  > `local.properties` 里的 `signing.*` 四行才会退回 `Unconfigured`。

## 9. 脚本插件系统（QuickJS）

* 包格式 `.nks`（zip）：`manifest.json` + `main.js` + 可选 `README.md`/`icon.png`/`configSchema.json`；
* 校验很严：包 ≤16 MiB、单文件 ≤8 MiB、条目数上限、路径穿越/符号链接/加密条目全拒、
  大小写冲突拒、`manifestVersion/apiVersion/id(两段小写域名式)/version/versionCode/hosts` 必填；
* 权限模型：`permissions.network`（`origin` 白名单，或 `true`）、`permissions.filesystem`；
  `configSchema.fields[]`（string/text/number/integer/boolean/select，1..64 选项）；
* 运行在主进程（`pr2` 管理生命周期），SDK 见上游 `sdk/script-sdk`。

## 10. 与设备现状的对照 / 下一步

想在本机跑起来，有三条路（都属设备变更，按仓库红线需明确授权）：

1. **Xposed**：Vector 里启用模块 + scope 勾 `com.ss.android.ugc.aweme` → 强停抖音再开。
   （当前 `modules_config.db` 里没有它，等于完全没启用。）
2. **Zygisk**：把已安装的**同一个** APK 改 `.zip` 刷进 KernelSU → `arm64-v8a` + Zygisk 已开 +
   抖音不在 DenyList → **需要重启手机**（红线：硬重启会丢临时 root，不建议）。
3. **免 root 修补**：上游的 NukePatcher（另一个 APK `io.github.nuke.oss.patcher`）把模块嵌进抖音。

注意点：
* 本模块**不声明 INTERNET 权限**——它自己的进程不联网；MCP 服务器跑在抖音进程里（用抖音的权限）。
* 抖音 41.2.0 是否与 0.1.2 的功能锚点匹配未知；跑起来后看
  `features_held dex_unlocated=N` 就知道有多少功能没定位到。
* 分析顺序建议：先在飞书上**只启用 `SettingsEntry`**（能进设置页即可），再逐项开；
  开 `McpServer` 时先只用 `READ_CONTACTS` 权限的 token，确认无误再加 `SEND_TEXT`。

## 11. 证据索引

| 文件 | 内容 |
|---|---|
| `apk/nuke-base.apk` | 原始 APK（sha256 见文件头） |
| `extract/` | APK 解包（dex / lib / assets / module.prop / customize.sh / third-party） |
| `jadx/` | jadx 反编译（默认模式，`defpackage.id3` 未展开） |
| `jadx2/` | jadx `--show-bad-code`（关键类 `id3`/`ng0`/`pr2`/`v90`/`lp1` 可读） |
| `logs/features_map.txt` | `hv0.a` 107 个注册项 → feature ID 映射 |
| `logs/feature_catalog.tsv` | 设置页 120 条文案（标题 + 说明，英文默认值） |
| `logs/strings_en.txt` | 全部 1454 条 string 资源的默认语言值 |
| `logs/resources.txt` | `aapt2 dump resources` 原始输出 |
| `logs/native_jni.txt` | `libnuke_protocol_core.so` 的 JNI 符号表 |
| `logs/native_mcp_tools.txt` | MCP 工具/提示词/错误码原始字符串 |
| `logs/native_risk_flags.txt` | 风控 flag 与检查项清单 |
| `logs/modules_config.db` | 采集时的 Vector 配置快照（证明模块未注册） |
| `logs/upstream-README.md` | 上游 README（安装/三种入口/许可证说明） |
| `COMPARE-vs-douai.md` | 与本仓库 `douyin/reference/DouAi`（我们的 41.2 融合分支）的对照 |
| `logs/upstream-tree.json` | 上游 `dev`@`9353957` 全仓文件树（720 blob，用于核对"源码是否完整"） |
| `logs/upstream-signer-verify.rs`、`logs/upstream-ModuleSignature.kt`、`logs/upstream-module-build.gradle.kts` | 签名 pin 的实现与注入方式 |
| `logs/upstream-risk-flags.rs`、`logs/upstream-mcp-tools.rs` | 风控 flag 与 MCP 工具的上游源码（与 .so 字符串互证） |
| `logs/upstream-ci.yml`、`logs/upstream-release.py`、`logs/upstream-prepare_lspatch.py` | 构建/签名/LSPatch 获取流程 |
| `logs/jadx*.log` | 反编译日志（32 个方法降级，属正常 R8 产物） |

## 12. 开源真实性核查（对上游 `dev`@`9353957`）

结论：**是真的完全开源**——APK 里的 revision 就是上游 `dev` HEAD（也是 `v0.1.2` 发版提交），
仓库里能拿到同一份源码，且**没有任何预编译二进制入库**。

| 核查项 | 结果 |
|---|---|
| 仓库 / 许可 | `dartcv/NukeApp-OSS`，根 `LICENSE` = **MIT**；`apps/patcher/LICENSE` = GPL-3.0（因为内嵌 LSPatch） |
| 代码版本对得上 | APK 的 `META-INF/version-control-info.textproto` = `93539571b51af…`；GitHub `dev` HEAD 同 sha；`v0.1.2` 是 annotated tag `be1ef2e` |
| 源码规模 | 720 个 blob：`apps/module/src` 512 个（Kotlin，含 `feature/*.kt` **115 个**）+ Rust 37 个（`mcp/`、`protocol/`、`riskctl/`、`script/`、`signer/`）+ C++ `module.cpp` + `core/{runtime,loader,script,designsystem,hook-ksp}` + `sdk/script-sdk`（JS/TS）+ `fixtures/` + `tools/*.py` + 完整的 Kotlin/Rust 测试 |
| 提交的二进制 | 只有 `gradle/wrapper/gradle-wrapper.jar` 和测试用 `sdk/script-sdk/test/fixtures/packages/hello.nks`；**没有** `.so`/`.jar`/`.aar` 预编译产物 |
| 第三方 native | `cpp/dependencies.lock.json` 按 commit 锁定，`tools/build_native.py` 现场构建：LSPlant `d8b5d1d`、Dobby `cfb3469`、xDL `1e0b625`、zygisk API4 `7bb941a`、DexBuilder `ac7fb22`、parallel-hashmap `0cd57d2` |
| 二进制↔源码互证 | `.so` 里的 8 个 `douyin_*` 工具名、29 个风控 flag 名/分类、MCP 工具提示词，与 `rust/src/mcp/tools.rs`、`rust/src/riskctl/flags.rs` 逐字一致（`logs/upstream-mcp-tools.rs`、`logs/upstream-risk-flags.rs`） |
| 不在仓库里的 3 类东西 | ① **release keystore 与密码**（CI `secrets.SIGN_*`，`NUKE_MODULE_SIGNER_SHA256` 由 keystore 推出）；② **LSPatch jar**（patcher 用，`tools/prepare_lspatch.py` 按 sha256 锁定 URL 下载，`artifacts/` 不入库）；③ Maven 依赖（quickjs-kt、Silk-Codec-Android、AndroidHiddenApiBypass、Compose…） |
| 能否自己编译 | 能。`tools/build_rust.py` / `build_native.py` + Gradle；本地无发布 key 时签名校验为 `Unconfigured`（配了 `nuke/src/local.properties` 则嵌我们自己那把 debug key 的摘要），模块照常加载。CI 里 PR 构建的是 **unsigned** Release，只有 push/tag 才用 secrets 签名 |

唯一"非公开"的东西是发版私钥，这不影响"源码完整"。另外仓库自带 `AGENTS.md` / `CLAUDE.md`
（作者自己用它跑 AI 协作），从侧面也能看出是活的开源项目（272★/26 fork，2025-11 建，最近 push 2026-11-16）。

## 13. 一句话风险评级

* 能力面：**很高**（Hook + 自动化 + 账号级 MCP + 脚本引擎 + 反检测），本质是"抖音增强 + RPA"。
* 代码质量：**高**（上游开源、功能门控、状态落盘、失败不重发、签名固定、三入口仲裁做得比同类细）。
* 使用时最该盯的：`McpServer` 的 token 权限/`lan` 开关、自动化发送的 `unknown` 语义；
  "设备信任"面板只是如实报告（root/Zygisk 属 `Env` 项，不单独触发告警）。


---

## 设备验收清单 (原 nuke/DEVICE-VERIFY.md)

# nuke 上机验证 runbook

> 目的：把 `nuke/src` 编出来的 APK 装上手机，逐项拿到"有效/无效"的证据。
> **每一步都要用户明确同意**（见根 `AGENTS.md` §1 与 §1.0 事故条款）；本文件只写步骤，不代表已获授权。

## 0. 前置（缺一不可）

- 屏幕已解锁、用户在场；手机不重启、不强停系统应用、不碰 `system_server`/`PackageInstaller` 之外的东西。
- `adb devices` 显示 `device`（transport 会变，别写死路径）。
- 本地已构建：`nuke/src/apps/module/build/outputs/apk/debug/module-debug.apk`
  （2026-11-18 实测 25.7 MB，含 `libnuke_zygisk.so`、`libnuke_protocol_core.so`、`libquickjs.so`、`libdexkit.so`）。
- 我们的签名是 `1990df…`（`signing/debug.keystore`），与官方 `72b63d…` 不同 → **无法 `install -r` 覆盖**。

## 1. 装（设备变更，需授权）

```sh
ADB=<platform-tools>/adb.exe
"$ADB" install -r -d nuke/src/apps/module/build/outputs/apk/debug/module-debug.apk
```

若报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不符），只有卸载官方包这一条路：

```sh
"$ADB" uninstall io.github.nuke.oss        # 会丢模块自己的设置与统计库
"$ADB" install nuke/src/apps/module/build/outputs/apk/debug/module-debug.apk
```

> 卸载会触发 `PACKAGE_*` 广播；本机 vivo 备份 `TransportManager.onPackageChanged` 有已知 NPE
> （`root/INCIDENT-2026-11-15-packageinstaller.md`）。屏幕必须解锁，出现任何系统弹窗先停下问用户。

## 2. 启用（设备变更，需授权）

Vector 里启用 `io.github.nuke.oss`，scope 勾 `com.ss.android.ugc.aweme`（只勾抖音主进程；不要加
`io.github.nuke.oss.fixture`）。然后：

```sh
"$ADB" shell am force-stop com.ss.android.ugc.aweme   # 只强停抖音（用户应用），不碰系统进程
```

打开抖音 → 我的 → 设置，应多出「NukeApp-OSS」一行。

## 3. 证据来源

- Vector daemon 日志（**首选**，模块日志都落这里）：
  ```sh
  "$ADB" shell su -c 'ls -t /data/adb/lspd/log/verbose_*.log | head -1'
  "$ADB" shell su -c 'grep -E "feature=|host_identity|features_held" /data/adb/lspd/log/<最新>.log | tail -50'
  ```
- 模块设置页 → 「调试」页：失败记录、Dex 未定位、功能状态。
- 文件证据：`/sdcard/Movies/douyin/<folder>`、`/sdcard/Pictures/douyin/image`、
  `/sdcard/Music/douyin/audio`、`/sdcard/Downloads/Nuke`；模块私有目录需 `su -c ls`。

## 4. 逐项检查（每项记"看到什么"）

| 项 | 操作 | 期望证据 |
|---|---|---|
| 加载 | 打开抖音 | `verbose_*.log` 有模块加载；设置页功能列表可见；无 `features_held dex_unlocated` 意外项 |
| D-1 下载来源 | 打开「下载原视频」，`source=play` | 下载成功；文件与播放源一致（无水印） |
| D-2/D-3 | 长按视频 → 保存 | 文件名按模板；落在 `Movies/douyin/<folder>`；日志 `feature=DownloadVideoWithName saved` |
| D-4a 队列 | 连点保存多个 | 设置页任务对话框出现排队/进度；取消生效 |
| D-4b 图片/SAF | 长按图片帖保存；对话框选目录 | 逐张 `_01/_02`；改目录后文件写进 SAF 目录 |
| D-4c 分享面板 | 分享面板点「按模板下载」 | 同 D-2 的结果 |
| D-5 MP3 | 保存一个视频（开着「把视频原声保存为 MP3」） | `Music/douyin/audio/audio-*.mp3` 能播；日志 `feature=SaveVideoAudio saved duration=` |
| D-6 实况 | 保存实况照片 | `Pictures/douyin/image` 一个 `.jpg`；PC 上 `ffprobe` 能读到内嵌 MP4；日志 `saved` |
| D-7 聊天媒体 | 收到的图片/视频消息 | `su -c ls /data/data/com.ss.android.ugc.aweme/no_backup/nuke/feature-files/CacheChatMedia/<会话>/`；日志 `feature=CacheChatMedia saved count=` |
| D-8 WebDAV | 填地址/账号后保存任意媒体 | 服务器上出现文件；日志 `feature=WebDavUpload uploaded status=2xx` |
| A14 哈希 | 填 `KnownHostApks.DIGESTS` 后重启抖音 | 日志 `host_identity verdict=known`（不填应是 `unconfigured`） |
| A15 配置 | 调试页导入配置 | 先出预览（新增/修改/未变/替换删除），选合并或替换 |
| S-4 报告 | 分析对话框「导出图片」 | `Downloads/Nuke/analytics-*.png` 可打开；成功回显路径 |
| A10a 双击分区 | 设为「上一条/下一条」并双击该区域 | 翻页；日志 `feature=FeedGestureZones next/previous moved=true` |

## 5. 失败时

- 单项无效：先看该功能在调试页的状态（FAILED / held / 未定位的 Dex 查询名），把日志和失败记录导出
  （调试页「导出全部异常」），再决定是修锚点还是改实现。
- 加载失败/崩溃：立刻在 Vector 里禁用模块（不要重启手机），保留 `verbose_*.log` 与 `logcat`。
- 回滚：Vector 禁用 → `adb uninstall io.github.nuke.oss` → 装回官方 0.1.2 APK（`nuke/apk/nuke-base.apk`，
  若本地没有就按 `nuke/README.md` 的复现步骤从设备重新拉）。

## 6. 已知边界

- 本仓库不产出发布包；debug 包与官方同 versionCode 家族，装过一次后要更新就 `install -r` 自己签名的包。
- 官方包被卸载后，NukeApp 自己的设置/统计会丢；重新安装需要重新在 Vector 里启用与勾 scope。
- 所有功能都**未上机验证**；`PLAN-nuke-upgrade.md` 的每一条都标了这一点。


---

## 与 DouAi 对比 (原 nuke/COMPARE-vs-douai.md)

# NukeApp-OSS vs 我们的 DouAi 41.2 融合分支

> 对照对象
> * **我们**：`io.github.yudigaga.douai.adapt412`（DouAi 公开源码 + 我们的 41.2 融合分支；**仓库当前 build.gradle = 0.2.75 / 176**，
  文档里写的 0.2.68/169 是上一轮交付版本）
>   另有 legacy 变体 `io.github.yudigaga.douai.legacy.adapt412`）。源码在 `douyin/reference/DouAi`，
>   适配说明 `douyin/reference/DouAi/docs/unified-41.2.zh-CN.md`、`adapt-41.2.0-experimental.md`。
> * **它**：`io.github.nuke.oss`（NukeApp-OSS 0.1.2 / code 6），分析见 [`ANALYSIS.md`](ANALYSIS.md)。
>
> 本机事实（Vector `modules_config.db`）：抖音作用域里注册了 5 个模块 —— 我们（`enabled=1`）、
> Doula、抖仙人（`com.zz.douyin`）、逗音大手（`com.rong862.bear`）、Freedom+（`io.github.fplus`），
> 后四个都是 `enabled=0`；NukeApp **未注册**。

## 0. 硬指标对照

| 维度 | 我们（DouAi 41.2） | NukeApp-OSS |
|---|---|---|
| 定位一句话 | 抖音内的 **AI 助手 + 媒体工坊 + 本地分析**，给人点（注：AI 自动回复按用户要求待下线，见 §8） | 抖音内的 **净化/自动化 + 账号能力外泄（MCP）+ 脚本沙箱 + 反检测**，给机器调 |
| 框架接口 | libxposed **API 102**（我们额外做了 API 82 legacy 桥接包） | **API 82 + API 102 + Zygisk(LPPlant)** 三入口同一 APK，进程内仲裁 |
| 免 root | 靠 LSPatch 修补（不是模块自己实现） | 自带 **NukePatcher**（另一个 APK，LSPatch 嵌入） |
| 代码规模 | `app/src/main` 314 个 java ≈ **89.8k 行** + 4.2k 行 Kotlin；测试 375 个 java | 上游模块 512 个文件（Kotlin 115 个 feature + Rust 37 个）、C++ 1 个；APK 里自有类仅 21 个 |
| 原生 | **无自有 .so**（纯 Java + dexlib2） | 5 个 .so：zygisk / protocol(Rust) / quickjs / silk / dexkit |
| 关键依赖 | `smali-dexlib2 3.0.9`、okhttp、`jump3r`(LAME)、miuix | DexKit、QuickJS、Silk-Codec、LSPlant、Dobby、xDL、parallel-hashmap |
| 许可 | 上游 DouAi 源码（仓库里没有 LICENSE 文件；上游明确"未使用发布签名"） | **MIT**（patcher 目录 GPL-3.0） |
| 反篡改 | 宿主身份校验：整包 SHA-256 + Manifest/DEX/资源/so 摘要 | 自身 APK 签名 pin（可自编译放行）+ 宿主内校验模块签名 |

## 1. 最本质的差别：宿主适配的"声明式" vs "命令式"

| | 我们 DouAi | NukeApp |
|---|---|---|
| 载体 | **TSV 规则表**，一行 = 一个 hook 目标：`feature / kind / class_descriptor / class_strings / method_name / method_descriptor / method_strings / invoke_owners / field_owners / opcodes / exact_method_count / exact_field_count / field_types / field_names` | 每个 feature 自己写 **DexKit 查询**（源码 `app/shared/dex/DexKitSearch.kt`） |
| 规则规模 | 每个宿主版本一份，**≈242 条规则**/文件（41.2.0 / 41.0.0 / 40.8.0 / 40.6.0 四份，38–39 KB） | 107 个 feature 注册项，各自查询 |
| 匹配纪律 | **必须唯一命中**：0 条或多条一律放弃，绝不在悬空候选里乱挑 | 尽力而为；定位不到就"hold"该功能（`features_held dex_unlocated=N`） |
| 指纹 | 每份规则表有 sha256 指纹，与 `Profile(versionName, versionCode, updateVersionCode)` 绑定；41.2 实验分支额外强绑**整包 SHA-256**（`4d161a8e…`），同版本号的不同构建不放行 | 无版本表、无宿主指纹；跟着宿主走 |
| 失效表现 | 换宿主版本 → 大面积"未安装"，但**每一项都有明确的成功/失败记录** | 换宿主版本 → 少量功能静默禁用，只有日志里能看到 |
| 适配成本 | 换版本要人工扫一遍宿主（我们用 `douyin/work/src/HostRuleSet` + `tools/dy_rules_fingerprint.py` 生成/校验 TSV） | 换版本基本零动作，但**定位正确性不可证** |
| 误 hook 风险 | 低（唯一性 + 字节码/注解复核，41.2 的 `Video.x:I @SerializedName("duration")` 这种都核过） | 中（结构匹配通过即用，宿主语义变了也可能照样匹配） |

一句话：**我们牺牲适配速度换确定性，它牺牲确定性换适配速度。**

## 2. 功能面对照

### 2.1 重叠区（谁都有，实现思路类似）

| 能力 | 我们 | NukeApp |
|---|---|---|
| 推荐流过滤 | 广告/直播/图文/空文案/卡片/商家/商品/热点/小游戏 + 关键词/正则/最低赞/时长 | `FilterFeedVideos`、`HideRelatedRecommendations`、分类统计字段过滤 |
| 广告与提示 | 闪屏、更新、青少年、章节广告、小游戏免实名 | `SkipSplashAd`、`DisableUpdateChecks`、`HideTeenModeDialog`、`SkipChapterAds`、`SkipMiniApp{InterstitialAd,RewardAd,RealName}`、`SkipMinorCheck` |
| 播放 | 结束暂停、自动下一条、默认倍速、临时倍速、手势倍速 | `PauseOnPlaybackCompleted`、`KeepAutoplayOn`、`CustomPlaybackSpeed`、`LongPressSpeed`、`SetVideoQuality`、`ForceHighVideoQuality` |
| 界面精简 | 组件隐藏 + 独立透明度 + 自定义定位、顶标签、底栏、系统栏、侧栏 | `CleanMode`、`HideFeedComponents`、`Trim{Share,Comment,LongPress}Panel`、`TrimProfileTabs`、`HideStatusBar`、`TransparentNavigationBar`、`ControlOpacity`、`CustomDpi` |
| 消息 | 防撤回 + 提示/背景、已读与输入状态、完整消息时间、私信媒体保存/分享、语音转发 | `PreventMessageRecall`/`…2`、`BlockReadStatusUpload`（黑/白名单更细）、`MessageSendTime`、`VoiceForward`、`RepeatMessage`、`CustomChatBubble`、`AllowAnyForward` |
| 评论 | 复制、完整时间、来源数据、媒体工具、后缀/颜色、语音替换/TTS | `CommentDetails`、`CommentPreciseTime`、`CleanCommentCopy`、`CustomCommentStyle`、`TrimCommentPanel`、`ShowCommentGender` |
| 下载 | 视频/图集/动态/头像/配乐/评论/私信媒体 + 来源选择 + 命名/目录/任务管理 | `AllowFeedDownloads`、`DownloadOriginalImages`、`AutoOriginalMedia`、`CoverInfo` |
| 杂项 | 定时/无操作退出、彩虹弹幕、骰子资源、官方热加载插件管理、完整数字/发布时间、地区名 | `TimedClose`、`ColorfulDanmaku`、`CustomDiceResult`、`BlockPluginUpdates`/`DisableHotUpdates`、`ShowPreciseCounts`、`VideoBottomInfo`、`RandomLocationGradient` |

### 2.2 我们独有（NukeApp 完全没有）

* **AI 互动**：私聊/群聊自动回复、自动评论、评论回复与建议、自动点赞/关注、多模态（图片/可选视频）、分层提示词、
  模型预设（OpenAI / OpenAI Responses / Anthropic / Gemini / DeepSeek / 通义 / 硅基流动 / OpenRouter / Moonshot / 自定义）、
  额度 + 延迟 + 发送账本 + 历史。
* **语音**：系统 TTS + 三家第三方语音（`breeze` / `fish` / `minimax`）、音色目录/随机音色/试听、
  语音替换、评论语音、本地合成。
* **真 MP3 导出**：`MediaCodec` 解码音轨 → `jump3r`(LAME) 编码，落 `Music/` 或授权目录。
* **实况照片**：迁移宿主原生接口；OEM 直写相册分支用 JPEG/XMP + MP4 封装。
* **WebDAV**：本地落盘后独立上传任务、标准 TLS、重定向不转交凭据、可取消。
* **本地分析**：SQLite v4（浏览/作者/话题/时段/停留关联）、年志 PNG/PDF、用户主动触发的 AI 解读。
* **下载任务管理**：进度、取消、目录授权、失败阶段区分（入口接管 / 任务提交 / 文件已保存）。
* **配置体系**：备份/预览/合并或覆盖、可选密钥导出、扫描缓存、重新扫描、桌面离线配置。
* **宿主身份校验**：整包 SHA-256 + 内容摘要，拒绝"改了 Manifest 的同版本包"。

### 2.3 它独有（我们完全没有）

* **MCP 服务器**：8 个工具（`list_contacts` / `find_contact` / `send_text` / `send_image` / `claim_sparks` /
  `claim_closeness` / `mark_all_read` / `send_protocol`），HTTP + JSON-RPC 2.0，token 权限 6 种 + 会话白名单 +
  限流 + `lan` 开关。**这是"把账号借给外部 LLM agent"的能力，我们这边是模块自己调模型。**
* **QuickJS 脚本插件**：`.nks` 包（manifest + main.js + configSchema），12 个 `nuke:*` 命名空间
  （`config / crypto / douyin / encoding / fs / hook / http / java / log / runtime / timers / ui`），
  网络 origin 白名单、包体/单文件大小上限、路径穿越防护。
* **注入环境自检**：29 个 flag（`Hard/Weak/Env/Note`）+ tripwire，面板展示"设备信任"。
* **签名 pin + 三入口仲裁**：同一进程只允许最先到的入口生效。
* **免 root 修补器**（NukePatcher）。
* **测试宿主**：`io.github.nuke.oss.fixture` 是真 App，能在没有抖音的机器上验证 hook 是否生效。
* 具体小功能：自动领火星（定时）、自动喂宠物、抢红包、复读 +1、自定义气泡、分享好友群、
  语音转发（Silk 编解码）、评论区性别标记、作者信息复制按钮、"不感兴趣"按钮、免空评键盘、任意转发数量。

## 3. 实现方式逐项对照

| 维度 | 我们 DouAi | NukeApp |
|---|---|---|
| Hook 抽象 | libxposed before/after + 自家 `HookModule`/`HostTargets`/`HostReflectionCache` | 统一 `f01` 接口（`attach/support/unhook`），三后端实现（xposed82 / libxposed102 / LSPlant） |
| 定位 | 规则表 + dexlib2 扫描宿主 DEX，结果缓存 `no_backup/<ns>/host-scan-v2.cache` | DexKit 查询（native），结果进 `feature-files/` |
| 配置存储 | SharedPreferences `douai_module_settings_adapt412`（宿主内）+ 桌面 App 独立配置 + 配置桥（校验真实调用 UID） | 宿主 `no_backup/nuke/<version>/`，含主进程→子进程 `process-mirror.json`；无导出 |
| 设置 UI | 桌面 App（Compose + miuix，可离线改）+ 宿主内悬浮"融合设置"+ 尝试注入抖音设置行 + 扫描诊断页 | 管理 App（Compose）+ 注入抖音设置页一行（`SettingsEntry`）+ MCP/协议控制台 |
| 发送安全 | `StreakSendLedger` / `CommentReplyLedger` / `ReplyScheduler`：额度、延迟、去重、历史、失败不重试 | 先落盘再发；`unknown` 绝不重发；token 限流 1–600/min |
| 诊断 | 注入回执、每项 hook 成功/失败、扫描进度、可复制的诊断文本 | `runtime_ready` / `features_held` / `module_signature_*` / `/cache/nukeapp-zygisk.txt` / MCP console |
| 多进程 | 基本只在主进程工作 | 明确的主进程 + 非 sandboxed 子进程（小程序/热更新）适配，主进程写 mirror 给子进程读 |
| 反检测 | 无（只做宿主完整性） | 29 flag 环境扫描（Frida/Xposed/root/SELinux/verity/模拟器…） |
| 自更新 | 无 | `DisableHotUpdates` 倒是"禁掉宿主的"，自身也没远端更新 |

## 4. 同时启用会怎样（冲突面）

两边改的是同一批宿主接点，**不建议同时开**：

| 冲突面 | 具体表现 |
|---|---|
| 推荐流过滤 | 同一 `SsResponse` 被两家各删一次条目 → 连续跳、空推荐、重复请求 |
| 长按/分享菜单 | 两家都往里加项目（下载/AI/复读/不感兴趣）→ 菜单重复、顺序错乱 |
| 设置入口 | 两家都想插"设置行"→ 可能只出现一个或两个都错位 |
| 已读/防撤回 | 我们控"已读与输入状态"，它控"不上报已读"→ 状态语义互相抵消 |
| 播放器/手势 | 倍速、双击、长按都改同一批回调 → 双击开评论 vs 点赞互相覆盖 |
| 下载 | 两家都改视频地址来源 → 同一作品出现两套来源/两次保存 |

实践顺序：**先停另一家的 scope**，再验证本家；同一时间只留一个抖音模块（本机目前就是"我们开着、其余四个关着"）。

## 5. 选型建议

| 你想要的 | 选 |
|---|---|
| 让 AI 替我回私信/评评论、要 TTS 语音、要 MP3/实况照片/WebDAV、要本地分析与年志 | **我们（DouAi 41.2）** |
| 纯净看视频、定时自动化（续火/领火星/喂宠/抢红包）、给外部 agent 开接口、自己写 JS 插件 | **NukeApp-OSS** |
| 既要净化又要 AI | 两家功能域重叠太多，**只能二选一**；真要都要，等价做法是在我们这边补"净化项"，而不是叠两个模块 |
| 只想验证 NukeApp 有没有用 | 只勾 scope + 强停抖音，先看 `features_held` 数量和设置页是否出现；不要为它重启手机（Zygisk 形态要刷模块+重启，红线） |

## 6. 对我们的三点可借鉴

1. **多入口仲裁 + 测试宿主**：它用 `io.github.nuke.oss.fixture` 在没有抖音的机器上验证 hook，
   我们目前只能在真机抖音上验；如果做 41.2 的回归，这个思路值得抄（代价是要写个假宿主）。
2. **对外接口层**：它把"发送/领取"抽象成带权限、限流、`unknown` 语义的 token 工具；
   我们的 `AutomationRateLimiter` + `Ledger` 已经有类似语义，但没有统一成"可被外部调用的契约"。
3. **状态可观测性**：它把"哪个 feature 没定位到"写进设置页和日志（`features_held`），
   我们现在是"扫描诊断页 + 每项结果"，概念一致；差别是它连**子进程**都做了 mirror，我们没这层。

## 7. 结论

* 两者都是"抖音进程内的增强模块"，但**产品轴完全相反**：
  我们沿"人机交互 + 内容生产"纵深（AI/语音/媒体/分析），它沿"无人化 + 接口化 + 反暴露"纵深（自动化/MCP/脚本）。
* 工程上我们的优势是**适配确定性**（规则表 + 唯一命中 + 整包指纹），劣势是**换版本的人工成本**；
  它正好相反，并且多了三入口/免 root/签名 pin 这套"分发工程"。
* 真正重叠且会打架的是"过滤 + 菜单 + 已读/撤回 + 播放器"这几块，**不要同时启用**。

## 8. 待办：AI 自动回复下线 —— **已取消**（2026-11-16 决定迁移到 NukeApp）

> 后续方案见 [`PLAN-nuke-upgrade.md`](PLAN-nuke-upgrade.md)、缺口见 [`GAP-ours-vs-nuke.md`](GAP-ours-vs-nuke.md)。
> 因为主力改用 NukeApp（它本来就没有 AI 回复），本节原计划作废，仅留作记录。

### 原记录

用户决定不再保留"AI 自动回复"。工程阻塞点在 `NativeMessagingHooks`（1655 行）：
**自动回复、防撤回、已读控制、定时续火花共用这一个 hook 类**，
`ConfigurationCatalog.Feature.REPLY` 的依赖表同时覆盖 `reply_*` 与 `streak_*`
（`ConfigurationCatalog.java:141`），`FeatureLogCatalog` 也用 `Feature.REPLY` 标注"定时续火花"。

两档可行方案：

| 方案 | 动作 | 风险/成本 |
|---|---|---|
| A. 入口下线 | 去掉设置页 `Category.AUTO_REPLY` 与其搜索项、两处 `AutoMessageDraftHook.install(...)`、`FeatureLogCatalog` 的 `auto_message` 项 | 小；不动共用类，包内无入口、不装 hook；类保留为死代码 |
| B. 彻底删类 | 另需拆 `NativeMessagingHooks` 的 reply 分支（queue/generateReply/ReplyTask）、`ReplyPolicy`·`ReplyScopePolicy`·`GroupReplyPolicy`·`AutomationReplyMedia` 的下游（`CommentReplyHook`、`NativeFollowLists`、`NativeCommentReplyInbox`、`UnifiedPortableUi412Hook`）、`AutomationMessageSettingsPage`（1057 行，reply/comment 共用） | 大；必须能编译校验（本机只有 SDK 35/36，`compileSdk=37` 需先装），否则容易断引用 |


---

## 功能差异评估 (原 nuke/GAP-ours-vs-nuke.md)

# 我们（DouAi 41.2 融合分支）有、NukeApp 没有的能力清单

> 基准：`douyin/reference/DouAi`（0.2.75 / 176）对照 NukeApp-OSS `dev@9353957`（0.1.2 / 6）。
> NukeApp 的功能全集取自 APK 的 `host_feature_*`（120 条，`logs/feature_catalog.tsv`）。
> **状态**：A1（下载来源选择）已改在 `nuke/src`；续火"今天已发言则跳过"也已改在 `nuke/src`；
> 本地分析 SQLite（采集 + 报告）已做；**A15 配置导入合并/预览**与**A14 宿主整包身份校验**
> 已完成（2026-11-17，见 `PLAN-nuke-upgrade.md` 与 `CHANGES.md`）。**A2 的命名模板 + 自定义目录**
> 已用自有下载器落地（接管长按保存项、只覆盖视频，见 `DownloadVideoWithName`）；A3 的队列/进度/取消仍缺。
> 其余见 [`PLAN-nuke-upgrade.md`](PLAN-nuke-upgrade.md)，
> 当前优先级：**下载/媒体 → 本地分析 SQLite**，界面类暂不做。
> **AI 自动回复按决定不保留**，因此不在本表；TTS 只单列（是否要另说）。

## A. 真正缺失（NukeApp 完全没有）

| # | 能力 | 我们的实现 | 说明 | 移植成本 |
|---|---|---|---|---|
| A1 | **下载来源选择** | `MediaSourceSelector` / `OfficialMediaModel` / `PlaybackSourceCaptureHook` | Auto / Play / H264 / H265 / Down 五选一；`new_download_addr`、`download_url_list` 显式处理；不把 Down（可能带水印）偷偷当无水印源 | 中：要重做"取源"层（NukeApp 现在只有"用播放源"这一条） |
| A2 | **保存命名模板 + 自定义目录 + SAF 授权** | `MediaNaming` / `DownloadDocumentTree` / `DownloadTransfer` | `#author#_#aid#_#index#.#ext#` 模板、用户选目录、系统文件夹授权 | 中 |
| A3 | **下载任务管理** | `DownloadTasks` / `MediaStoreDownloader` | 进度、取消、失败阶段区分（入口接管/任务提交/文件已保存） | 大：要队列 + UI |
| A4 | **视频原声 MP3** | `PcmMp3Encoder` + `UnifiedMp3Export`（MediaCodec 解码 → LAME/jump3r 编码） | 真 MP3，不是改名 | 中：NukeApp 的音频转码只服务"评论语音替换"，输出 M4A，没有 LAME |
| A5 | **实况照片封装** | `OfficialLivePhotoDownload` / `PortableMotionPhoto` | 原生接口迁移 + 只能直写相册的 OEM 分支用 JPEG/XMP+MP4 封装 | 中 |
| A6 | **WebDAV 上传** | `WebDavClient` / `WebDavUploads` | 本地落盘后独立上传任务、标准 TLS、重定向不转交凭据、可取消 | 中 |
| A7 | **私信媒体保存/分享** | `ChatMediaLoader` / `DownloadActionHook`（私信） | 聊天里的图片/视频/语音落盘 | 中 |
| A8 | **本地分析（SQLite + 报告/年志）** | `LocalAnalytics*` / `Portrait*`（14 张表，schema v4） | 浏览/停留/作者/话题/时段/功能统计、7-30-90 天报告、PDF/PNG 导出 | **大**：新库 + 新 UI + PDF 导出 |
| A9 | **原生用户搜索 / 关系筛选 / 关注列表分页** | `NativeUserSearch` / `NativeFollowLists` / `FollowListPager` | 抖音原生搜索、按关系筛、关注/粉丝列表遍历 | 中 |
| A10 | **九宫格手势 + 快捷菜单** | `FeedGestureToolsHook` / `GestureQuick*Dialog` | 双击/长按分区域绑定动作、快捷菜单 | 小-中 |
| A11 | **组件自定义定位** | `HostResourceCatalog` + 组件隐藏的"自定义位置" | 隐藏之外的位移/重排 | 小 |
| A12 | **首页侧栏** | `HomeSidebar*`（`HomeSidebarModuleHook` 等） | 侧边栏入口与按压手势 | 小 |
| A13 | **官方热加载插件逐项管理** | `UnifiedPluginControl` | ~~NukeApp 只有"全部阻断"~~ **已由 `BlockPluginUpdates` 覆盖**：`blocked` 行列表按包名逐个阻断，`plugin_seen` 日志给出实际加载过的包名（没有列表 UI，但能逐项禁用）。**无需再移植** | — |
| A14 | **宿主身份校验（整包）** | `HostApkProfile` / `HostRuleSet` 指纹 | 整包 SHA-256 + Manifest/DEX/资源/so/META-INF 摘要比对，拒绝"同版本号的改包" | **小**：NukeApp 已有 Rust signer 代码，可复用 |
| A15 | **配置合并/覆盖/预览/可选密钥导出** | `ConfigBackup` / `ConfigImportTransaction` / `ConfigTransfer` | NukeApp 只有基础导出/导入 | 小-中 |
| A16 | **自动点赞 / 自动关注**（规则触发，不依赖 AI） | `AutoLikeHook` / `AutoFollowHook` | NukeApp 没有对应项 | 小（但我们自己的实现是 AI 驱动，移植要改成规则版） |

## B. 部分覆盖（NukeApp 有，但深度不如我们）

| 能力 | 我们 | NukeApp 现状 |
|---|---|---|
| 已读/输入状态 | 双向可控（已读 + 输入中） | `read_status` + `typing_status`，粒度接近 ✔ |
| 防撤回 | 保护 + 提示 + 背景色 | `recall` / `prevent_message_recall_2`，接近 ✔ |
| 评论媒体保存 | 图片/视频/语音全量 | 原图 + 语音 + 表情，缺视频 |
| 续火 | 时间窗 + 文本/HTTP 一言 + 未知重试 | 定时刻 + 文本/一言/**原生互动表情** + 补当天；**今天已发言不跳过**（本次 patch 补上） |
| 界面精简 | 隐藏 + 透明度 + 自定义定位 | 隐藏 + 透明度，缺定位 |
| 内容过滤 | 关键词/正则/最低赞/时长/分类全维度 | 接近，但正则与时长维度较弱 |

## C. 反向清单（NukeApp 有、我们没必要重造）

MCP 服务器、QuickJS 脚本、29 项风控自检、签名 pin、三入口仲裁、免 root 修补器、测试宿主、
`RepeatMessage`（+1 复读）、`CustomChatBubble`、`AllowAnyForward`、`ShareFriendGroups`、
`CustomStreakDays`、`AutoSparkClaim`/`AutoPetFeed`/`OpenRedPackets`、`RandomLocationGradient`、
`SkipMinorCheck`、`SaveCommentSticker`、`DanmakuMoreInfo`、`AddAuthorInfoRow`。

## D. 结论

* **真正值得搬的**：A14（身份校验，最便宜）、A10/A11/A12（手势与界面，小）、A1/A2（取源与命名，中）、A15（配置合并）。
  → A14 / A15 **已完成**（2026-11-17）。
* **重工程**：A3（下载任务）、A4（MP3）、A8（分析）、A7（私信媒体），可分期。
* **可不搬**：A16（我们自己那套是 AI 触发，改成规则版才有意义）、TTS（除非明确要）。


---

## 抖音 41.2 适配说明 (原 douyin/docs/archive)

**针对所提供抖音 41.2.0 APK 的适配记录 · 2026-11-13**

**本文件记录早期受限原型。用户随后要求完整融合，当前实现与交付说明已转到 [抖音融合 41.2 说明](C:/mycode/somethingsmall/fuckmyphone/repos/DouAi/docs/unified-41.2.zh-CN.md)，不应把下文的 24 条规则和三个分类当成当前功能范围。**

可以做适配。本轮已经在 DouAi 公开源码上实现受限的 41.2 分支，先开放推荐过滤、下载扩展和抖音内设置入口。没有把其余功能简单套用旧版混淆类和资源表。

当前宿主原件：`C:\Users\jaqenze\Downloads\vivo临时root\douyin-41-2-0.apk`，包名 `com.ss.android.ugc.aweme`，versionName `41.2.0`，**versionCode 410200**。大小 357,113,218 字节，共 57 个 DEX；静态声明索引含 564,875 个类、2,454,416 个方法定义。SHA-256 为：

```text
4d161a8eb4da14ee9931b87476e9f0de3847797006e3485e51c4cf497a9d5a99
```

不要把这个样本误当成 410201。它的 Manifest 未提供此前 DouAi 40.8/41.0 使用的内部更新构建号，本次实验分支因此额外绑定整个宿主 APK 的 SHA-256；另一个同版本号的包也不会自动放行。

**已经落实的源码工作**

源码目录为 [repos/DouAi](C:/mycode/somethingsmall/fuckmyphone/repos/DouAi)。基线为上游 `yudigaga/DouAi` 的提交 `6e51a2a436d6a5aad6f831f6ca1cc1c46a95e5b6`，版本 0.2.66 / 167；这比前一轮提供的 0.2.48 APK 新。本次是本地修改，使用本地调试签名，没有冒用上游发布签名。

对旧 41.0 的 242 条规则使用生产扫描器实际扫描新宿主，结果是 **152 条唯一匹配、90 条找不到、0 条歧义**。这些数字是定位规则结果，不是“功能成功率”。它们说明只改版本白名单无法得到完整适配。

已增加独立的 41.2 规则文件和宿主分支，共 **24 条必要规则**。它们在本 APK 全部唯一匹配，且对关键方法做了进一步的字节码/字段注解核验：

- 推荐过滤沿 `SsResponse.success`、推荐 URL、`FeedItemList.items` 和 `Aweme` 分类/统计字段处理。最低点赞字段 `AwemeStatistics.d:J` 已核对 Gson 注解为 `digg_count`。
- 时长过滤补齐 41.2 的混淆 `Video.x:I @SerializedName("duration")`，再保留旧字段回退，避免顶层 `Aweme.duration` 缺失时忽略时长条件。
- 下载扩展核对 `LppDownloadModule` 的创建/点击方法，以及父类 `LJIIJ():Aweme`。实际字节码会从 `LongPressPanelParams.a` 读取当前作品，原生点击也调用同一 getter。
- 视频地址使用序列化字段名读取，能够对应新版本的 `play_addr`、`play_addr_265`、`play_addr_h264`、`download_addr` 等，不继续死读旧 Java 字段名。
- 设置入口只接已核对的 `DouYinSettingNewVersionActivity` 和 `SettingNestedScrollView`，页面只展示筛选、下载、关于三个分类，并显示实验适配范围。

41.2 没有安装未核对的播放/倍速、手势、清屏、系统栏、聊天、防撤回、AI 自动回复、自动评论、自动关注等 Hook。未知资源 ID 返回无效值，未核对的反射别名会拒绝使用，避免退回旧版本猜目标。

测试构建的包名为 `io.github.yudigaga.douai.adapt412`，桌面名为“DouAi 41.2 测试”，使用独立偏好文件 `douai_module_settings_adapt412`。实验启动跳过旧偏好迁移、分析数据库初始化和下载统计捕获，避免试用时打开/升级原版的分析库。未知或哈希不匹配的宿主不会创建可操作的宿主设置控制器。

**其余五个模块的结论**

| 模块 | 对这个 41.2 APK 的判断 | 关键依据 |
| --- | --- | --- |
| 抖仙人 1.7.5 | 最值得先原样实机验证的另一个候选，暂不必为它重签 | 所列 28 项必要声明检查全部通过；旧 `y0(I)V` 页切换方法不在，但原模块把它作为可选 Hook 并有回退。25 个播放器方法符合既有动态筛选条件。结构检查不能代替运行测试。 |
| Doula 1.1.3 | 需要新增明确 41.2 分支，不能直接放行 | 旧版本识别失败会退回 40.6；旧播放器 getter 在 41.2 返回类型已不同。新 getter 为 `LLJL()`、feed listener 为 `X.10fQ` 等，已有定点语义证据。公开仓库只提供文档，缺少可构建源码。 |
| Dyoo | 需要迁移多个核心接点 | `MonitorDataResponseBeforeHandler` 等旧类缺失；`Video.playAddr/h264PlayAddr/playAddrH265` 这些旧字段不存在。 |
| Freedom+ | 需要迁移播放地址与若干 UI/方法接点，并先修网络辅助函数 | 稳定模型和部分菜单类仍在，但旧视频地址字段不在；此前确认的 TLS 默认校验问题也仍需修复。 |
| 逗音大手 | 需要改字段、菜单方法与版本适配，另有签名/环境限制 | `VideoViewHolder.E/F` 在新版本已不是旧代码期待的布局对象；`SharePanelDialog.initView` 不在；原包还有签名/native 检查。 |

抖仙人的 28/28 是所列必要宿主声明检查；既不表示所有功能都测试通过，也不是对整个模块的完整证明。抖仙人的可选页切换迁移还不能简单替换成另一个同签名方法：41.2 由 pager listener 调用 `panel.H2.onPageSelected(i)`，需要正确恢复 panel 上下文。

**验证与交付材料**

完整源码和测试在上述仓库；具体分支行为见 [源码适配文档](C:/mycode/somethingsmall/fuckmyphone/repos/DouAi/docs/adapt-41.2.0-experimental.md)。构建、测试结果和测试 APK 的指纹记录在本说明旁的交付清单中。

- [41.2 宿主 DEX 索引摘要](C:/mycode/somethingsmall/fuckmyphone/analysis/host412/dex-index.summary.json)
- [抖仙人检查结果与最小变更路线](C:/mycode/somethingsmall/fuckmyphone/analysis/host412/xianren-41.2.zh-CN.md)
- [Doula 断点和已定位的新符号](C:/mycode/somethingsmall/fuckmyphone/analysis/host412/doula-41.2.zh-CN.md)
- [三个旧模块兼容报告](C:/mycode/somethingsmall/fuckmyphone/analysis/host412/legacy-compatibility.md)
- [下载/过滤调用链复核](C:/mycode/somethingsmall/fuckmyphone/analysis/host412/legacy-douai-callchain-review.md)

本轮没有安装或运行宿主/模块，没有实机验证 LSPosed 回调、抖音设置入口呈现、下载成功、推荐过滤结果或多个模块共存。桌面测试通过和静态定位唯一只能证明目前的适配依据与实现检查通过；实际功能仍需要手机运行反馈。


---

## 抖音模块研究报告 (原 douyin/docs/archive)

**六个抖音模块的静态研究记录 · 2026-11-13**

六个安装包都已确认是抖音进程增强模块，包含四个现代 libxposed 模块和两个传统 Xposed 模块。它们的定位、宿主适配年代、框架接口和实现质量有明显差别，不能按“都是抖音模块”直接互换。

本轮读取安装包、校验签名、反编译 Java/资源并还原主要混淆字符串，没有安装或运行样本。以下“有实现”表示在样本代码中找到了实现路径；实际生效、稳定性和服务端结果仍需在匹配版本的设备上验证。

**文件与真实身份**

| 用户提供的文件 | 真实名称、模块版本 | 包名 | Android 最低版本 | 实际模块接口 |
| --- | --- | --- | --- | --- |
| `Dyoo.apk` | Dyoo 1.1.9，versionCode 19 | `o.dyoo` | 9 / API 28 | 现代 libxposed，声明 API 100 |
| `DouAi.apk` | DouAi 0.2.48，versionCode 149 | `io.github.yudigaga.douai` | 9 / API 28 | 现代 libxposed，声明 API 102 |
| `base(1).apk.1` | 抖仙人 1.7.5，versionCode 27 | `com.zz.douyin` | 9 / API 28 | 现代 libxposed，声明 API 102 |
| `base(2).apk.1` | Doula 1.1.3，versionCode 2026103140 | `io.github.jhihhe.doula` | 13 / API 33 | 现代 libxposed，声明 API 102 |
| `base(3).apk.1` | 逗音大手 3.0.0，versionCode 3000 | `com.rong862.bear` | 9 / API 28 | 传统 Xposed，声明最低版本 82 |
| `base(4).apk.1` | Freedom+ 1.3.6，versionCode 142 | `io.github.fplus` | 7 / API 24 | 传统 Xposed，声明最低版本 82 |

四个现代模块的 `META-INF/xposed/scope.list` 都只列正式版抖音 `com.ss.android.ugc.aweme`。逗音大手的入口代码还列出极速版 `com.ss.android.ugc.aweme.lite`、火山版 `com.ss.android.ugc.livelite`；Freedom+ 也具有多宿主适配。作用域声明与实际版本兼容是两回事。

Dyoo、Doula、逗音大手的原生库只提供 arm64-v8a。Freedom+ 提供 arm64-v8a 与 armeabi-v7a；DouAi 的图形依赖库提供四种常见 ABI；抖仙人没有原生库。最低 Android 版本只说明安装条件，框架 API、宿主位数与宿主版本还须分别匹配。

完整原件路径、文件大小、SHA-256、权限、DEX、原生库和 Xposed 元数据见 [inventory.json](/C:/mycode/somethingsmall/fuckmyphone/analysis/inventory.json)。六份分析副本的 SHA-256 均与原件一致，六份 `apksigner verify` 均通过。签名通过只证明安装包签名结构自洽，不能证明下载来源或作者身份。

**各模块的用途与适配线索**

| 模块 | 已找到的主要实现 | 宿主适配证据与限制 |
| --- | --- | --- |
| Dyoo | 按广告、直播、图文和文案过滤，弹窗过滤；沉浸与布局隐藏；手势；无水印媒体/表情下载；下载后上传自设 WebDAV；定时退出；管理抖音热加载插件 | 设置页明确写基于 `33.5.0(330501)`，代码明确拒绝 32 位抖音。不能据此保证新版本适配。 |
| DouAi | 推荐/界面增强与下载；AI 私聊/群聊自动回复、自动评论、图片/可选视频识别；系统或第三方语音；本地使用统计及主动 AI 分析 | 内置 40.6.0、40.8.0、41.0.0 规则。40.8 和 41.0 分别限定内部构建 `40809900`、`41009900`，同一个外显版本号也可能不兼容。 |
| 抖仙人 | 较集中的沉浸播放、暂停恢复控件、保留弹幕、双击行为、按内容跳过、固定清晰度、视频/图集下载和音频提取 | 此轮未找到精确抖音版本白名单；通过内部类名、反射和视图定位工作，仍会受宿主升级影响。 |
| Doula | 综合过滤与布局修改、沉浸、倍速/清晰度、下载与评论/私信媒体保存、聊天防撤回、抑制已读/输入状态、弹幕与主题增强 | 存在 40.6～40.9 适配路径。界面仍写 40.6/40.7/40.8，而代码另有 `Host409`，应以分支代码而非单条 UI 文案判断。不能保证该范围内每个构建均可用。 |
| 逗音大手 | 推荐/广告过滤、界面净化、沉浸、长按与分享菜单增强、无水印媒体/评论素材下载、消息防撤回等 | 首页写 37.6.0，内部另有 `370801` 版本线索，且留有“逗音小手”“抖+”等名称。应视作 37.x 适配线索，不能当严格兼容承诺。 |
| Freedom+ | 综合过滤、沉浸、界面精简、无水印下载、评论素材保存、聊天防撤回、WebDAV、空闲退出等 | 本地历史明确涉及 24.x～25.x；通过宿主版本缓存、DexKit 和远端 `versions.json` 提供适配信息。本次没有请求远端清单，不能保证当前新抖音适配。 |

Doula 留有把旧日志前缀 `[android-admin]` 和“抖+”替换为 Doula 的代码，并使用旧入口与新 API 桥接；逗音大手也留有移植文字。这些是代码来源线索，不能据此断言两个 APK 代码完全相同，或判断它们与其他项目的授权关系。

**实现方式及冲突点**

这些模块运行时进入抖音进程，拦截内部方法、修改响应对象或控制视图。DexKit 的用途是从宿主 DEX 中定位被混淆的方法；它不是 root 提权工具。DouAi 没有捆绑 `libdexkit.so`，主要使用自身扫描逻辑和内置规则定位宿主接口。抖仙人则以较直接的类名、反射和视图扫描为主。

| 容易重叠的区域 | 代码层面的结果 | 组合使用时要检查的现象 |
| --- | --- | --- |
| 推荐流过滤、跳过直播/广告 | 删除同一响应中的条目，或对同一作品再次触发切换 | 连续跳视频、空推荐、重复加载 |
| 沉浸、顶部/底部栏隐藏 | 多个回调修改同一个 View 的可见性、透明度和布局 | 控件闪回、点不到按钮、评论页异常 |
| 双击、长按和倍速 | 同时拦截手势消费或替换同一菜单 | 双击开评论与点赞相互覆盖、重复菜单、长按失灵 |
| 下载、分享、评论素材 | 向同一菜单加项目，或改写同一素材 URL | 重复入口、重复下载、保存路径不同 |
| 私信防撤回、已读、自动回复 | 多模块修改消息字段或处理同一回调 | 状态显示异常、重复任务；自动发送结果需查看实际会话 |

这是根据重叠 Hook 与数据修改路径得出的冲突可能性，本轮没有做六模块组合运行测试。合适的验证顺序是先单独验证一个模块，再逐项增加另一个模块的独有功能。

抖仙人的总开关只控制运行时行为，不会卸掉已安装的 Hook；关总开关后扫描调度也仍存在。其他模块的“功能关闭”也不应自动理解成框架已解除注入。彻底移除某个模块在当前宿主进程中的影响，需要框架停用后重启宿主进程。

**网络、数据与权限**

模块本身的 Manifest 权限不等于注入后的能力。Dyoo、DouAi、抖仙人、逗音大手虽然没有显式申请 `INTERNET`，进入宿主后仍可使用宿主进程的联网、文件访问和会话能力。

下表是从代码确认的地址及用途，未向这些服务实际发送样本数据：

| 模块 | 地址或目标 | 数据流和触发条件 |
| --- | --- | --- |
| Dyoo | `quhua.ipchaxun.com`；用户配置的 WebDAV | 前者把作品的 `Aweme.city` 地区码换成地区文字；后者用于可选的下载后媒体上传。地区码不是实时精确 GPS。 |
| DouAi | 用户配置的模型服务；`api.minimax.cn`、`api.fish.audio`、`api.breeze.blue` 等语音服务 | 启用相应功能后，可能发送聊天文本、提示词、图片、可选视频抽样帧，或作品作者/标题/文案及选用素材。自动回复与自动评论一旦开启，符合范围就可能自动请求并发送，不要求逐条确认。 |
| DouAi | `gateway.diadi.cn` 的解析模板；`www.yx520.ltd` 的第三方语音模板 | 均是可配置功能，不能把模板出现等同于默认调用。第三方语音默认模板中有 HTTP GET，文字和 voice 参数经 URL 编码拼入查询串。 |
| 抖仙人 | 当前作品提供的媒体 URL，下载请求使用抖音 Referer | 可读实现里主要是媒体下载，未看到专属账号信息采集服务；这不是完整安全证明。 |
| Doula | 当前素材 URL、项目主页等 | 保留的上游字符串含 `api.ip33.com`，但本轮追到的视频属地显示路径已改为 `CityLookup.resolve()` 本地表，不能仅凭残留字符串认定仍向 ip33 发请求。 |
| 逗音大手 | 当前素材 URL；关于页 `yun.y1yun.shop/details/7E9B9EE4` | 关于页用外部浏览器打开该地址，不能据此当成账号数据上传接口。已读业务路径主要是宿主素材处理。 |
| Freedom+ | GitHub release / `versions.json`；`http://api.ip33.com/Area_Code/GetArea/?code=`；用户 WebDAV | GitHub 用于更新及兼容信息；ip33 接收作品地区码；WebDAV 是用户选用的存储。 |

DouAi 的默认值经过单独核对：自动回复、自动评论、本地分析采集默认关闭；模型列表初始为空；下载来源默认 official；语音默认 local 系统 TTS。部分子选项默认开启，例如图片识别或私聊范围，但不代表对应总功能已开启。

DouAi 的统计默认留在抖音私有的不备份目录。主动点击 AI 分析会发送作者/话题/功能排行、对照统计、关联摘要和自填目的等，界面有告知。不能把“统计平时本地保存”理解成“所有 AI 功能都离线”。

**有证据的实现问题与待实测项**

1. **Freedom+ 会放宽进程默认 HTTPS 校验，值得优先修。** 通用连接辅助函数在 HTTPS 分支调用 `HttpsURLConnection.setDefaultHostnameVerifier()`，验证器始终返回 true；同时设置使用空 `checkServerTrusted()` 的默认 SSLSocketFactory。它被下载与文本请求路径调用，可能影响同一进程之后采用默认配置的 HttpsURLConnection。当前连接对象在修改全局默认值之前已创建，不能说该次请求必然失去校验，也不能把影响扩展成所有 OkHttp 请求或整个手机系统。证据：[连接辅助函数](/C:/mycode/somethingsmall/fuckmyphone/analysis/base4/jadx/sources/OooO00o/OooO00o.java:200)、[域名验证器](/C:/mycode/somethingsmall/fuckmyphone/analysis/base4/jadx/sources/p085o000oo0/OooOOOO.java:9)、[证书验证器](/C:/mycode/somethingsmall/fuckmyphone/analysis/base4/jadx/sources/p085o000oo0/OooOo00.java:18)。

2. **抖仙人有较频繁的主线程扫描。** 正常调度间隔 100 ms，过渡窗口可为 16 ms；无活动视图时 250 ms，关闭总开关仍按 500 ms 调度。它会查找并调整控件，值得实测流畅度与续航；频率本身不能证明一定卡顿或耗电严重。证据：[扫描与调度](/C:/mycode/somethingsmall/fuckmyphone/analysis/base1/jadx/sources/defpackage/f1.java:1428)、[16 ms 过渡调度](/C:/mycode/somethingsmall/fuckmyphone/analysis/base1/jadx/sources/defpackage/f1.java:1536)。

3. **Dyoo 的商家过滤疑似有选项名称不一致。** 配置选项是“商家卡片”，过滤 `awemeType == 140` 的条件却查“推荐商家”。按原字符串保存选项时，该项可能不生效，需实机核对。证据：[配置选项](/C:/mycode/somethingsmall/fuckmyphone/analysis/Dyoo/decoded-sources/p055/C0815.java:47)、[过滤判断](/C:/mycode/somethingsmall/fuckmyphone/analysis/Dyoo/decoded-sources/p172/C2265.java:267)。

4. **Doula 的 Android 13 主题同步接收器缺少同等调用者校验。** 接收器对外导出，但身份校验只在 `SDK >= 34` 分支执行；在它支持的 Android 13 上，其他应用可能发送有效 mode 改变主题。影响局限于主题偏好。它的 `SettingsProvider` 则检查调用 UID，只允许自身或抖音，缓存清理还进一步限自身，不能误报为任意应用读取全部配置。证据：[接收器](/C:/mycode/somethingsmall/fuckmyphone/analysis/base2/jadx/sources/io/github/jhihhe/doula/ThemeSyncReceiver.java:76)、[Provider 校验](/C:/mycode/somethingsmall/fuckmyphone/analysis/base2/jadx/sources/io/github/jhihhe/doula/SettingsProvider.java:18)。

5. **逗音大手有较强的签名和环境校验，改包兼容需另测。** Activity 明确调用 Java 与 native 校验，注入入口也包含证书比较和 native 检查；`libjb.so` 有 LSPatch、重签名工具等标记。由此能判断重签名、二次打包存在失效风险，不能静态断言所有 LSPatch 环境都会被拒绝。MainHook 的反编译有重复/冲突分支，不能逐句当原始 Java。证据：[Activity 签名校验](/C:/mycode/somethingsmall/fuckmyphone/analysis/base3/decoded-sources/com/rong862/bear/MainActivity.java:82)。

**后续研究的选择**

从代码可读性与功能集中度看，抖仙人适合先研究沉浸、手势、清晰度和下载；其轮询实现应一起评估。Doula 适合研究较新抖音的综合适配和旧入口桥接。DouAi 适合研究 AI、语音、自动化及本地统计，同时需要明确数据发送范围和防重复任务机制。

Freedom+ 的模块边界与功能较完整，可参考其结构，但网络辅助函数应先修。Dyoo 与逗音大手适合对照旧版本 Hook、字符串混淆和移植结构，作为当前新抖音的直接使用方案则需要更多验证。以上是研究取舍，不是经过实机稳定性测试的安装排名。

**可复查的材料**

- [Dyoo 详细报告](/C:/mycode/somethingsmall/fuckmyphone/analysis/Dyoo/REPORT.zh-CN.md)
- [DouAi 详细报告](/C:/mycode/somethingsmall/fuckmyphone/analysis/DouAi/REPORT.zh-CN.md)
- [抖仙人详细报告](/C:/mycode/somethingsmall/fuckmyphone/analysis/base1/REPORT.zh-CN.md)
- [Doula 详细报告](/C:/mycode/somethingsmall/fuckmyphone/analysis/base2/REPORT.zh-CN.md)
- [逗音大手详细报告](/C:/mycode/somethingsmall/fuckmyphone/analysis/base3/REPORT.zh-CN.md)
- [Freedom+ 详细报告](/C:/mycode/somethingsmall/fuckmyphone/analysis/base4/REPORT.zh-CN.md)
- [完整样本清单与指纹](/C:/mycode/somethingsmall/fuckmyphone/analysis/inventory.json)
- [可复现的清单/反编译脚本](/C:/mycode/somethingsmall/fuckmyphone/scripts/analyze_apks.py)

每个样本的 `analysis/<key>/` 下保留 `sample.apk`、`signature-verification.txt`、`jadx.log` 和 `jadx/sources`、`jadx/resources`。Dyoo 与逗音大手另有 `decoded-sources`；解码文件只用于阅读，没有改写 APK。Doula 另有还原的补码字符串材料。

工具为 JADX 1.5.6、Java 21 和 Android build-tools 36.0.0。抖仙人反编译未报告错误；Dyoo、DouAi、Doula、逗音大手、Freedom+ 分别报告 95、104、12、13、86 个反编译错误。这些数字是反编译器错误计数，不等于模块自身运行错误数。已确认的结论主要取自可读实现、模块元数据、资源及相互印证的调用链；复杂控制流和原生代码仍有盲区。

本轮没有实机运行、网络抓包、官方发布件签名比对或完整原生库审计，因而没有给任何一个样本作“绝对安全”“一定兼容”或“一定无封号风险”的保证。

