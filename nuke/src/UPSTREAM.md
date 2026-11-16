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
| `apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/SelfSpeech.kt` | 新增 | 每账号一份 `speech.dat`：`本地日 → 会话ID`，最多留 7 天 |
| `apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/StreakEngine.kt` | `tick` / `nextWakeAt` 增加 `skip` | 用户今天已在某会话发过言 → 不写 claim、不发送；同时不再把它算作 pending（否则 worker 每秒空转） |
| `apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/chat/streak/AutoStreak.kt` | 观察聊天行 + uid↔会话映射 + 跳过日志 | 用已有 `ChatAdapterEvents` 记录「我方今日发言」；`ContactQueries.SINGLE_CONVERSATION_ID` 映射 uid |
| `apps/module/src/test/kotlin/io/github/nuke/oss/app/tiktok/StreakEngineTest.kt` | 新增两条用例 | 跳过后不写 claim；全跳过时 `nextWakeAt` 落次日 |
| `apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/query/DownloadQueries.kt` | 新增 3 个字段查询 | `play_addr` / `play_addr_h264` / `play_addr_265` |
| `apps/module/src/main/kotlin/io/github/nuke/oss/app/tiktok/feature/feed/DownloadOriginals.kt` | `DownloadOriginalVideo` 增加 `source` 选项 | 下载来源：auto / play / h264 / h265 / download；缺流回退播放地址 |
| `apps/module/src/main/kotlin/io/github/nuke/oss/ui/hostsettings/HostFeatureLabels.kt` | 选项标签 | 设置页显示下载来源 |
| `apps/module/src/main/res/values/host_settings_strings.xml`、`values-zh/…` | 新增文案 | 同上（中/英） |

## 约定

* 改动直接改这里的文件，按功能说明写在提交信息里；这里没有 `patches/` 之类的东西。
* 本目录**未提交**到 ganvivo 仓库时，改动只存在于工作区（`git status` 可见），不要 `git clean -xdf`。
* 上游更新（可选）：把上游新版本单独克隆到临时目录，与本目录 `diff` 后手工并回来；**不要**把上游 `.git` 引入本目录。
