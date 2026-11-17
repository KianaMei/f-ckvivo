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

**验证方式**（2026-11-17 实测；本机已装 `platforms/android-37.0` + `build-tools/37.0.0`）：

```sh
cd nuke/src
export ANDROID_HOME=<SDK>          # 有 platforms/android-37* 与 build-tools 即可
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:compileDebugKotlin
./gradlew -Pandroid.experimental.disableCompileSdkChecks=true :apps:module:testDebugUnitTest -x checkProtocolRust --tests "<测试类>"
```

结果：编译通过（Gradle 9.8.2 / JDK 21）；本次涉及的 23 条单测全过；
整包单测失败集合与上游基线**完全一致**（47 条，全部缺 Rust 原生库），但
`PacketExecutorTest.cancellationPropagatesAndClosesWaiter` 在本机会挂住不返回，
所以全量跑要设超时、或直接用 `--tests` 定向。本机**没有** Rust/NDK/`python3`，
原生任务（`buildZygiskNative` / `checkProtocolRust`）不参与。

## 下一步清单

### 可无宿主锚点直接做

| # | 事项 | 落点 | 说明 |
|---|---|---|---|
| S-4 | 报告导出（PNG/PDF） | 同上 + `ReportImageShare` 思路 | 可后置 |
| D-5 | 视频原声 **MP3**（MediaCodec → LAME） | 新 feature + `de.sciss:jump3r` | **卡在锚点**：见下节“D-5 / D-8 的前置条件” |
| D-5 | 视频原声 **MP3** | 新 feature + `de.sciss:jump3r` | 同上；见下节 |

### D-5 与 D-8 的“宿主下载”部分：前置条件（2026-11-17 核实，仍缺）

D-5 与“把宿主下载的文件也传上 WebDAV”都需要一个“**宿主下载完成**”的接点，而现有查询里没有：
（D-8 已完成的部分改为挂在模块自己的保存路径 `AndroidMediaFiles.save` 上，不依赖宿主。）

* `DownloadQueries` 只提供 `IMAGE_EXECUTOR` / `LIVE_EXECUTOR`（图集/实况），**视频下载的 executor 没有查询**；
  `TASK_PATHS`（`AbsTask#taskPaths()`）只是取路径的 getter，不是完成回调。
* 模块自己的落盘路径（`MediaFiles.save`，如 `CommentMediaSave`）只服务评论媒体，不经过宿主下载器。
* 本机 `douyin/work/assets/douyin-host-rules-41.2.0.tsv` 不在（换机未重建），无法直接交叉核对。

所以要继续这两项，先做（只读取证，不改设备）：

1. `adb exec-out` 拉 41.2 宿主 APK → jadx 定位 `AbsTask` 子类与视频下载的 executor/完成回调（写进 `remote`/`adb pull` 的本地目录，不入库）；
2. 把它写成新的 `DexQuery`（按 `taskPaths` 同类结构 + `execute/run` 特征），加进 `UPLOAD`/`AUDIO` 两个 feature 的 `dexQueries`；
3. 有了接点再做 D-5（`MediaCodec` 解音轨 → `jump3r`，LGPL，需在 `NOTICE` 里记一笔）与 D-8（`HttpURLConnection` + Basic auth，`PROPFIND/MKCOL/PUT`）。

### 需要宿主锚点（要对着宿主 APK 做 DexKit 取证）

| # | 事项 | 缺什么 |
|---|---|---|
| D-2 / D-3 | 下载命名模板、自定义目录 | 需确认 `AbsTask#taskPaths`（已有查询）能否安全重写；目录需 SAF 交互 |
| D-4 | 下载任务管理（进度/取消/失败阶段） | 需自有下载器 + 任务 UI（最大一块） |
| D-6 | 实况照片封装（JPEG/XMP + MP4） | 需宿主实况照片任务结构 |
| D-7 | 私信媒体保存 | 需会话媒体锚点（可复用 `VoiceForward` 的会话解析） |
| A9 / A10 / A12 / A13 / A16 | 用户搜索、九宫格手势、侧栏、插件逐项管理、自动点赞/关注 | 各自的宿主接点 |

## 执行顺序（2026-11-16 确认）

按"先做不需要宿主取证的，再做需要取证的"推进；每项都要过编译门禁、写完单测、提交后推送。

**第一批（无需新锚点）**

1. ~~D-5 视频原声 MP3~~ —— 见上节，缺“下载完成”接点，暂缓。
2. ~~D-8 WebDAV 上传~~ —— 同上。
3. ~~A15 配置导入合并/预览~~ —— **已完成**（预览 + 合并/替换）。
4. ~~A14 宿主整包身份校验~~ —— **已完成**（整包 SHA-256 + 已知清单，默认空 = 不启用）。

**第二批（需要对着宿主 APK 做 DexKit 取证，只读采样即可）**

5. D-2 / D-3 命名模板 + 自定义目录（先验证 `AbsTask#taskPaths` 能否安全重写）
6. D-4 下载任务管理（自有下载器 + 进度/取消/失败阶段 UI，最大一块）
7. D-6 实况照片封装、D-7 私信媒体保存
8. A9 / A10 / A12 / A13 / A16 用户搜索、九宫格手势、侧栏、插件逐项管理、自动点赞/关注

**取证方式（不改设备）**：`adb exec-out` 拉宿主 APK / dex 到本地，用本机 jadx（仓库不存副本）
交叉定位，再写成 DexKit 结构化匹配；不安装、不重启、不改任何设备文件。
注意：`douyin/work/assets/douyin-host-rules-41.2.0.tsv`（41.2 已核对的类/方法描述符）**本机已不在**，
换机后需用 `douyin/tools/dy_rules_fingerprint.py` 重建，再拿来交叉核对。

## 仓库约定

* 改动直接改 `nuke/src`，按功能提交；`src/UPSTREAM.md` 与本文件同步更新。
* 构建与产物不在本会话范围（用户要求不产出安装包）。
* 上游更新（可选）：另开临时克隆与新版本 diff，手工并回；不要引入上游 `.git`。
