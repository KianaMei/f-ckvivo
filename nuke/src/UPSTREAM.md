# 这份源码是怎么来的

## 基准

* 上游：<https://github.com/dartcv/NukeApp-OSS>（MIT）
* 基准提交：`22d272d5a5f25e23b4afa4a6e30832ebbac54ba2`
  （`fix(playback): hook the check that hides the progress bar`，2026-11-16 的 `dev`）
* 基线版本：`0.1.2 / 6`

本目录就是**我们维护的源码**，直接由 ganvivo 仓库跟踪，**不依赖任何上游 git 历史，也不向上游提交任何东西**。
「基准提交」只用于将来对照上游更新：`git diff 22d272d..` 之类可以在临时克隆里做，不会影响这里。

## 我们相对基准改了什么

| 文件 | 改动 | 目的 |
|---|---|---|
| `feature/chat/streak/SelfSpeech.kt`（新） | 每账号 `speech.dat`：`本地日 → 会话ID`，留 7 天 | 记住"今天我在这个会话说过话" |
| `feature/chat/streak/StreakEngine.kt` | `tick` / `nextWakeAt` 增加 `skip` | 已发言的好友不写 claim、不发送；不算 pending（否则每秒空转） |
| `feature/chat/streak/AutoStreak.kt` | 观察聊天行、uid↔会话映射、跳过日志 | 用已有 `ChatAdapterEvents` 记录我方发言 |
| `test/.../StreakEngineTest.kt` | 新增 2 条用例 | 跳过后不写 claim；全跳过时 `nextWakeAt` 落次日 |
| `feature/feed/DownloadOriginals.kt` | `DownloadOriginalVideo` 增加 `source` 选项 | auto / play / h264 / h265 / download；只解析所选那一路 |
| `query/DownloadQueries.kt` | 新增 3 个字段查询 | `play_addr` / `play_addr_h264` / `play_addr_265` |
| `ui/hostsettings/HostFeatureLabels.kt` | 选项标签 + 新功能标签 | 设置页显示 |
| `res/values*/host_settings_strings.xml` | 新增文案（中/英） | 同上 |
| `feature/analytics/AnalyticsSink.kt`（新） | 接收端接口与 `AnalyticsVisit` | 与存储解耦，可单测 |
| `feature/analytics/AnalyticsRecorder.kt`（新） | 观看会话的开启/关闭/可见时长/哈希 | 纯逻辑，可单测 |
| `feature/analytics/SqliteAnalyticsSink.kt`（新） | SQLite 表：visits / daily / hours / ranks / actions / collection | 本地账本，失败静默 |
| `feature/analytics/LocalAnalytics.kt`（新） | `@HookEntry` 功能，默认关闭；接 `FeedEvents` 的视频切换与播放/暂停 | 采集入口 |
| `test/.../AnalyticsRecorderTest.kt`（新） | 4 条用例 | 时长、暂停不计、无 id 不记、哈希稳定 |
| `test/.../MediaFeaturesTest.kt` | 适配 `DownloadOriginalVideo` 新构造参数 | 保持既有用例可编译 |

## 约定

* 改动直接改这里的文件，按功能说明写在提交信息里；这里没有 `patches/` 之类的东西。
* 本目录**未提交**到 ganvivo 仓库时，改动只存在于工作区（`git status` 可见），不要 `git clean -xdf`。
* 上游更新（可选）：把上游新版本单独克隆到临时目录，与本目录 `diff` 后手工并回来；**不要**把上游 `.git` 引入本目录。
