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
