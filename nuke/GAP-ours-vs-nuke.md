# 我们（DouAi 41.2 融合分支）有、NukeApp 没有的能力清单

> 基准：`douyin/reference/DouAi`（0.2.75 / 176）对照 NukeApp-OSS `dev@9353957`（0.1.2 / 6）。
> NukeApp 的功能全集取自 APK 的 `host_feature_*`（120 条，`logs/feature_catalog.tsv`）。
> **状态**：A1（下载来源选择）已改在 `nuke/src`；续火"今天已发言则跳过"也已改在 `nuke/src`；
> 本地分析 SQLite（采集 + 报告）已做；**A15 配置导入合并/预览**与**A14 宿主整包身份校验**
> 已完成（2026-11-17，见 `PLAN-nuke-upgrade.md` 与 `CHANGES.md`）。
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
| A13 | **官方热加载插件逐项管理** | `UnifiedPluginControl` | 列出宿主插件、逐个禁用（NukeApp 只有"全部阻断"） | 小 |
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
