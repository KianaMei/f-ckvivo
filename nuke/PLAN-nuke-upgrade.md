# 改造 NukeApp：进度与清单

> 源码就是 **`nuke/src`**（由 ganvivo 直接跟踪，无上游 git）。基准与改动清单见 [`src/UPSTREAM.md`](src/UPSTREAM.md)，
> 相对基准的完整 diff 见 [`CHANGES.md`](CHANGES.md)。
> 优先级（用户指定）：**下载/媒体 + 本地分析 SQLite**，界面类暂不做。

## 已完成（已编译 + 单测通过，零回归）

| # | 事项 | 状态 |
|---|---|---|
| S-0 | 续火「今天我已发言 → 跳过不发」 | ✅ `SelfSpeech` + `StreakEngine.skip` + `AutoStreak` 观察；2 条单测 |
| D-1 | 下载**来源选择**（auto/play/h264/h265/download） | ✅ 只解析所选那一路；缺流回退播放地址；设置页中英文案；既有用例已适配 |
| S-1 | 本地分析**存储层**（SQLite） | ✅ 表：visits / daily / hours / ranks / actions / collection；后台线程写；失败静默 |
| S-3 | 本地分析**查看界面** | ✅ `AnalyticsDialog`：今天/近 7 天条数与可见时长、作者数、最常刷的 3 个时段（只读本地库） |
| S-2 | 采集接线 | ✅ `LocalAnalytics` 功能（默认关闭），接 `FeedEvents` 的视频切换与播放/暂停；可见时长只算播放中；标识先哈希 |

**验证方式**（本机没有 android-37 平台，用编译门禁代替）：

```sh
# 在副本里把 compileSdk 临时降到 36（源码不改），并绕过 AAR 校验；跳过 Rust 原生校验
gradle.properties += android.experimental.disableCompileSdkChecks=true
./gradlew :apps:module:compileDebugKotlin
./gradlew :apps:module:testDebugUnitTest -x checkProtocolRust
```

结果：编译通过；新增 6 条单测全过；整包单测失败集合与上游基线**完全一致**（47 条，全部缺 Rust 原生库）。

## 下一步清单

### 可无宿主锚点直接做

| # | 事项 | 落点 | 说明 |
|---|---|---|---|
| S-4 | 报告导出（PNG/PDF） | 同上 + `ReportImageShare` 思路 | 可后置 |
| D-5 | 视频原声 **MP3**（MediaCodec → LAME） | 新 feature + `de.sciss:jump3r` | NukeApp 现有转码只到 M4A；注意许可 |
| D-8 | **WebDAV** 上传 | 新 feature（`TextOption` 配置 + 上传任务） | 依赖"本地文件落盘"事件，先做手动上传 |
| A14 | 宿主**身份校验**（整包 SHA-256 + 内容摘要） | 复用 `rust/src/signer/` | 需要一份已知良好包哈希清单（可只在 41.2 上启用） |
| A15 | 配置导入**合并/预览** | `HostSettingsActivity` + `HostSettingsScreen` | 现有导入只整体覆盖 |

### 需要宿主锚点（要对着宿主 APK 做 DexKit 取证）

| # | 事项 | 缺什么 |
|---|---|---|
| D-2 / D-3 | 下载命名模板、自定义目录 | 需确认 `AbsTask#taskPaths`（已有查询）能否安全重写；目录需 SAF 交互 |
| D-4 | 下载任务管理（进度/取消/失败阶段） | 需自有下载器 + 任务 UI（最大一块） |
| D-6 | 实况照片封装（JPEG/XMP + MP4） | 需宿主实况照片任务结构 |
| D-7 | 私信媒体保存 | 需会话媒体锚点（可复用 `VoiceForward` 的会话解析） |
| A9 / A10 / A12 / A13 / A16 | 用户搜索、九宫格手势、侧栏、插件逐项管理、自动点赞/关注 | 各自的宿主接点 |

## 仓库约定

* 改动直接改 `nuke/src`，按功能提交；`src/UPSTREAM.md` 与本文件同步更新。
* 构建与产物不在本会话范围（用户要求不产出安装包）。
* 上游更新（可选）：另开临时克隆与新版本 diff，手工并回；不要引入上游 `.git`。
