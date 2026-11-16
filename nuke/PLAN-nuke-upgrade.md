# 改造 NukeApp：进度与清单（2026-11-16）

> 决定：主力改用 **NukeApp-OSS**，我们的 DouAi 融合分支停止维护。
> > 工作方式：**源码就是 `nuke/src`**，由 ganvivo 直接跟踪，直接改文件；不产出 patch，不涉及任何上游 git 历史。
> 基准提交与改动清单记在 `nuke/src/UPSTREAM.md`。**本会话不构建、不产出 APK、不碰设备。**
>
> 优先级（用户指定）：**① 下载/媒体 ② 本地分析 SQLite**；界面类与其他先不做。

## 0. 附带的结论

"把我们包里的 AI 自动回复删掉"不用做了 —— NukeApp 本来就没有 AI 回复。

## 1. 已完成

| # | 事项 | 改动 |
|---|---|---|
| S-0 | 续火：**今天我已发过言 → 跳过不发** | 新增 `SelfSpeech.kt`（每账号一份 `speech.dat`，`本地日 → 会话ID`，留 7 天）；`StreakEngine.tick/nextWakeAt` 增加 `skip`；`AutoStreak` 用已有的 `ChatAdapterEvents` 观察 `isSelf + getCreatedAt`，用 `ContactQueries.SINGLE_CONVERSATION_ID` 把 uid 映射到会话；跳过时打 `skip_spoke_today`（不含 uid）。**两条单元测试**：跳过后不写 claim；全跳过时 `nextWakeAt` 落次日（防每秒空转） |
| D-1 | 下载**来源选择** | `DownloadOriginalVideo` 增加选项 `source`：`auto`（播放地址，原行为）/`play`/`h264`/`h265`/`download`（宿主自己的地址，可能带水印）。新增 3 个 DexKit 字段查询（`play_addr`/`play_addr_h264`/`play_addr_265`），某路流不存在时回退播放地址；`download` 直接不介入。设置页标签与中英文案已补 |

两个改动的共同原则：**只做本地读/改，不新增网络请求；不新增 hook 点之外的东西；所有宿主反射都包 try/catch，失败即退回原行为。**

## 2. D 线：下载 / 媒体（优先）

现状：NukeApp 只有"放行宿主下载 + 把地址换成播放地址"（`DownloadOriginalVideo`、`DownloadOriginalImages`），
没有自己的下载器、没有任务队列、没有转码。

| # | 事项 | 我们的参考实现 | 落点 | 成本 | 依赖 |
|---|---|---|---|---|---|
| D-2 | **保存命名模板** `#author#_#aid#_#index#.#ext#` | `MediaNaming` | 在宿主写文件前改文件名（`DownloadQueries.TASK_PATHS` 已存在，可复用） | 中 | D-1 无关 |
| D-3 | **自定义保存目录 / SAF 授权** | `DownloadDocumentTree` | 同上，或改宿主 task 的目标 URI | 中 | D-2 |
| D-4 | **下载任务管理**（进度/取消/失败阶段） | `DownloadTasks`/`DownloadTransfer` | 需要自有下载器（OkHttp 已在依赖里）+ 设置页任务列表 | **大** | D-2/D-3 |
| D-5 | **视频原声 MP3**（MediaCodec → LAME） | `PcmMp3Encoder`/`UnifiedMp3Export` | 新 feature：取视频文件 → 解音轨 → 编码；NukeApp 现有转码只到 M4A（`replace_comment_voice`） | 中 | 需要引入 LAME（注意许可） |
| D-6 | **实况照片封装**（JPEG/XMP + MP4） | `PortableMotionPhoto` | 在 `DownloadOriginalImages` 的实况分支补封装 | 中 | D-3 |
| D-7 | **私信媒体保存** | `ChatMediaLoader` | 与现有 `VoiceForward` 共用会话解析 | 中 | — |
| D-8 | **WebDAV 上传** | `WebDavClient` | 独立 feature，本地落盘后上传；TLS 标准、重定向不转交凭据、可取消 | 中 | D-4（要有本地文件任务） |

## 3. S 线：本地分析 SQLite（优先）

目标：把"你自己刷抖音的账本"做进 NukeApp —— 只在本机、默认关闭、不上传、不记聊天内容。

现有可复用的观察点（不用新 hook）：`sdk/feed/FeedEvents.kt`（视频切换 / 播放 / 暂停 / 播完）、
`ChatAdapterEvents`（消息行，用于"发言"类统计时可选）。

| # | 事项 | 说明 | 成本 |
|---|---|---|---|
| S-1 | 存储层 `AnalyticsStore` | SQLite（`featureFiles`/宿主私有目录），schema 参照我们那套精简版：`visits`（作品哈希/来源/可见时长/结束原因）、`daily`、`hours`、`ranks`（作者/话题）、`actions`（功能触发）、`collection`（开关边界）、`meta` | 中 |
| S-2 | 采集接线 | 用 `FeedEvents` 记 visit 开始/结束、可见时长（前后台/切页要停表）；`actions` 从各 feature 的装点上报；**默认关闭**，开关一开就写 `collection` 边界 | 中 |
| S-3 | 设置页入口 + 概览数字 | 今天/7 天：浏览数、可见时长、高峰时段、作者/话题 Top N、广告占比 | 中 |
| S-4 | 报告/年志导出（PDF/PNG） | 我们用的 `PortraitReportViews` 思路；可后置 | 大 |

**隐私红线**：只记作品哈希、标签、时长、功能名；不记私信正文、不记账号；不上传。

## 4. 待定（用户明确"先不做"）

九宫格手势、组件自定义定位、侧栏、配置合并/预览、宿主整包身份校验、自动点赞/关注、TTS、原生用户搜索。
其中**身份校验**成本很低（可复用现有 Rust `signer`），等你有空说一句就能加。

## 5. 验证方式（等你自己装机时）

```sh
# 续火跳过
adb logcat -s NukeApp-OSS | grep AutoStreak   # 期望 skip_spoke_today，不出 send_acknowledged

# 下载来源
# 设置 → 下载相关 → 视频无水印 → 来源选 H.264 / H.265 / 下载地址，各下一个视频比对文件大小与画面右上角
```

## 6. 仓库约定

* 源码就是 `nuke/src`，直接改、由 ganvivo 跟踪；基准版本与改动清单见 `nuke/src/UPSTREAM.md`。
* 构建与产物**不在本会话范围**：需要 Android SDK 37 + 依赖，且用户要求本会话不给安装包。
* 上游更新（可选）：另开临时克隆与 `nuke/src` 做 diff，手工并回来；不要引入上游 `.git`。
