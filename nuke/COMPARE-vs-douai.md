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
