# 抖音 41.2.0 统一增强模块：本地实现与验证

日期：2026-11-13。代码基线为官方 `yudigaga/DouAi` 的 `6e51a2a436d6a5aad6f831f6ca1cc1c46a95e5b6`，版本 0.2.66 / 167，比最初分析的 DouAi 0.2.48 新。本项目把六个样本的功能需求落实到可编辑的统一模块，未把其他 APK 的二进制代码直接拼接，也未沿用 Freedom+ 的宽松 TLS 校验。

这是本地开发构建，不是作者发布版，不使用作者的正式签名。最终 APK、哈希和 Gradle 验证结果以本文末尾的最终构建记录及交付清单为准。

## 目标 APK 与身份边界

| 项目 | 实证值 |
|---|---|
| 宿主包名 | `com.ss.android.ugc.aweme` |
| versionName | `41.2.0` |
| versionCode | **410200**，并非 410201 |
| 原 APK SHA-256 | `4d161a8eb4da14ee9931b87476e9f0de3847797006e3485e51c4cf497a9d5a99` |
| DEX | 57 个 `classes*.dex` |
| 内部更新构建号 | Manifest 未声明之前 40.8/41.0 使用的内部构建元数据 |

因此 `HostApkProfile` 除检查包名、版本和 code，还优先校验上述 APK 哈希。启动修复版在完整哈希不同时，继续校验固定的二进制 Manifest 和全部业务内容摘要，只允许签名与 ZIP 封装差异；同名版本的其他实际内容不能自动获得此配置。Manifest SHA-256 为 `86d442f7f45098a2d7656bfdc4801481c6770ffb5ca535007c876700b4aa6d8d`，内容聚合为 `b376fa7c53b08ea668b3138ca97503d7c9b34c3ecd6e41c3c2c57f71a26ccc76`。业务内容覆盖 45,752 项、842,410,520 展开字节，仅排除标准 v1 签名文件，并拒绝重复与异常路径。未知内容仍不授权业务 Hook，但始终允许显示启动诊断。

本轮把 APK 当 ZIP、DEX、Manifest 和资源数据读取，没有安装或执行宿主/模块，没有登录账号，没有提交评论或私信，也没有向样本内服务发请求。

## 规则迁移与替代实现

把旧 41.0 的 242 条规则直接用于本包，实际结果只有 **152 UNIQUE、90 NOT_FOUND、0 AMBIGUOUS**。据此没有采用“只加版本白名单”的做法。

下载修复版的独立 `douyin-host-rules-41.2.0.tsv` 有 **243 条规则**，在原 242 条基础上新增分享面板共用下载执行边界。canonical fingerprint 为：

```
492851f724e88171b03ecbe7d28ae148d61754a1c4672fd01d470cabd56f96c3
```

首次融合迁移保留并重新定位了 229 个旧规则 ID，13 个旧触摸/容器/旧删除路径被新实现取代，新增了 13 个明确的媒体 getter、分类器、安全任务和播放事件锚点。下载修复版另增 `X.0xqk.execute(Context, SharePackage)` 精确规则；该动作由分享工厂、分享扩展和社交面板共同创建，当前作品字段为 `b:Aweme`。旧 40.6/40.8/41.0 规则资产内容保持不变。

迁移按调用角色展开：从稳定类的真实参数类型推导模型、上下文、附件、音频和回调；用字段、字符串、方法体调用、注解、switch 分支和消费者要求交叉验证。唯一匹配只证明定位，不能单独证明反射字段、业务语义或手机运行成功。

### 已接入的能力分组

| 分组 | 实现与关键证据 |
|---|---|
| 推荐过滤 | `SsResponse.success`、URL、`FeedItemList`、Aweme 分类器及统计字段合同；广告、直播、图文、卡片、关键词与时长/点赞阈值。`AwemeStatistics.d` 的序列化名确认为 `digg_count`。 |
| 时长兼容 | `Aweme.duration` 缺失/为零时，优先按 `SerializedName("duration")` 读 41.2 的 `Video.x:I`，保留旧字段回退。 |
| 下载 | 原生长按菜单扩展、视频/图集/音频、无水印来源、选择目录、命名、下载任务与第三方解析；视频子菜单可选择 Auto/H264/H265/Down，保存菜单可保存已下发的作者大头像。`LppDownloadModule.LJJI` 创建菜单，`LJJIJIIJIL` 处理点击；父类 `LJIIJ():Aweme` 读取当前 `LongPressPanelParams.a`。 |
| 播放与手势 | 新 `UnifiedPlayback412Hook` 使用真实播放事件、work ID、当前 panel 和播放器，接入自动连播、播完暂停、九宫格双击/长按、速度切换及复制/保存/评论/组件/设置快捷操作。自动接管时抑制宿主重复连播；迟到旧 work 事件不能翻动新作品。 |
| 倍速与画质 | 新播放调用和 SDK 参数转发目标独立定位；实际速度读回使用具名 speed command。新画质模块按原 VideoModel 保留用户选择或最高可用档位，并保留音频/未知流。 |
| 界面与沉浸 | 新 `UnifiedUi412Hook` 使用视频/图集 holder、语义资源名、控件类型和可访问性描述，支持组件隐藏/透明度、导航/沉浸和暂停/换作品状态；未知资源和任意文案不会被当成指定控件。 |
| 安全召回/插播控制 | 新独立 hook 精确区分 `ALambdaS34S1500000_3` 的 0/1 安全分支、`ALambdaS157S1300000_3` 的 1 分支以及 `06BY` 背景删除；其他分支放行。四个插入入口/降级方法按真实 adapter 插入调用定位。 |
| 标签、基础提示、资料 | 重新定位顶部 tab 数据、刷新、启动/青少年提示、进度组件、资料统计及本人主页判断。资料显示使用 41.2 的 x/v/w/E 控件角色，不复用旧 F 字段。 |
| 点赞与关注 | 点赞定位到真实 DiggViewModel 操作；关注保留登录、当前 work、作者 followStatus=0 和重复防护，以唯一原生关注控件/明确可访问性标签定位，拒绝多候选、已关注标签及普通文案。 |
| 自动评论/评论回复 | 52 条完整评论/语音/回复规则重扫均唯一。模型 `12uf`、附件 `12uh`、音频 `12wU`、上下文 `12p7`、工厂 `11cF` 与 resolver `12vp` 对应；原生 checked publisher、回执、收件通知、评论线程回源、目标和账本策略复用。 |
| 评论工具/语音 | 复制正文/数据、原生评论图片保存、评论视频/语音保存、AI 解析入口、后缀/颜色、语音替换、文本转语音和 # 长按。视频按 `CommentVideoStruct.b=alias_aweme` 读取；语音只从 `CommentAudioStruct.b=content` 内已有的播放地址读取。41.2 的评论标识读 `Comment.cid`，不再假设旧 `getCid()` 存在。 |
| 消息与私信语音 | 迁移全局接收、编辑器输入模式、当前消息、原生撤回、录音完成、上传队列及音频消息构造。`AudioLogic.onCreate` 注册的 41.2 回调分支为 **4**。 |
| 消息扩展 | 新消息工具独立接入已读/输入状态控制、私信媒体保存和语音转发；只针对定位到的原生入口，保留非音频转发及其他父策略。 |
| AI/侧栏 | 41.2 使用自绘聊天界面和系统附件选择，继续共用原有模型、上下文、媒体预算、取消和 provider 管线；侧栏与作品/评论 AI 使用新 portable 入口，避免复用旧 18 个混淆 UI 类。 |
| 本地分析 | 已重新开放独立目录中的本地采集、统计、关联、报告、主动 AI 分析与导出；作品时长/可见停留不冒充实际观看或完播。 |
| 统一增强 | WebDAV、定时/空闲退出、可选骰子/猜拳、彩虹弹幕、官方热加载插件控制、完整作品信息、章节广告标记跳过等由独立能力接入；默认开关关闭或名单为空。 |

实况封装和 MP3 导出作为下载能力扩展接入同一保存/任务路径。原生实况服务与标准 Motion Photo 容器的具体实现、可解码样本和最终测试结果由相应专项与最终构建记录列明；不能将生成了文件解释为所有设备相册都已验证呈现。

`UnifiedMediaActions` 的编码选项读取当前 Video/BitRate 的实际播放源和编解码标识：H264 只接受明确的 H264 源或标记为 AVC 的源，H265/ByteVC1 只接受已有对应源，Down 使用官方下载地址并明确提示可能带水印；缺少来源时显示不可用，不通过改 URL 或转码伪造资源。作者头像使用 `avatar_larger` 原地址，不去掉尺寸参数或用小头像假充原图。评论语音保留已知音频容器；未知格式保存为 `.audio`，不会直接改名为 MP3。仅有 VID、HLS/DASH 播放清单或封面图片时不创建语音下载。所有新增媒体保存继续使用现有任务队列及选定 SAF 目录。

## 安装机制与失败诊断

`DouAiModule.installUnified412Hooks` 将每项能力独立安装、独立捕获错误并保存 `unified.<capability>.failure`。不能通过注册其他功能成功就报告某项就绪。扫描不唯一、反射类型不符、字段/分支或回调合同不符时，该项保持不可用并记录原因；已注册的部分句柄需回滚。

诊断的 41.2 capability 规则列表对应新实现：自动连播检查新播放事件/panel 规则，安全召回检查三个新任务，而不是继续要求已经替代的旧删除/旧手势容器。界面中的“已安装 Hook”仍不等同于已观察到服务端成功。

设置首页已开放完整分类、搜索及统一增强入口，不再限制为最初的三个 checkpoint 分类。桌面模块主页的“功能设置”可离线配置全部 14 类参数。宿主内有独立的“融合设置”备用入口，未通过内容校验时为“融合诊断”，可以复制具体失败阶段。原生抖音设置行按唯一布局插入，失败时不影响备用入口。已确认宿主的单项安装失败可在诊断中单独识别。

启动修复版在 Application 到达时就建立诊断并调用 `beginScan()`，将大文件身份核对移到后台；融合分支不请求不需要的框架远程偏好。配置通过模块 Provider 与宿主本地镜像同步，使用实际 Binder UID 校验、逐 key 乐观冲突检测和共同事务锁；待提交的宿主值不会被配置监听刷新覆盖。桌面回执读取不依赖现代框架服务，缓存回执明确标为历史状态。另有独立的传统 API82 桥接变体，只启用传统入口，不将两个框架入口装入同一个 APK。

## 数据、网络和默认行为

本地 debug 构建使用 `.adapt412` 包名后缀、不同应用名称和 debug 签名；release 仍保留上游包名。配置偏好独立，旧配置迁移在 preview 中跳过。本地分析目录使用 `douai-analytics-adapt412`，避免直接打开或升级原上游的分析库。HostStorageNamespace 还对私信/评论状态、发送账本、频率限制、自动化历史、评论线程回源游标、聊天记忆、日志、私有语音源/临时音频及扫描缓存使用独立名称；融合统计的 LocalPortraitContext 与新分析目录一致。release 保留原名。公开媒体下载、用户选择的 SAF 文件夹和 WebDAV 目标仍按保存配置使用，不因私有状态隔离而偷偷改到另一个位置。

自动回复、自动评论、本地采集与新增增强默认关闭；AI 模型列表初始为空。启用自动 AI 功能后，符合范围会触发所选模型的请求和发送，不是每条必有确认弹窗。图片/视频、作品/评论资料、主动统计分析及第三方语音的数据流仍遵循相应设置和帮助内容。

WebDAV 使用标准证书与主机名校验，HTTP 需要显式允许；不复制 Freedom+ 的全局绕过 TLS 实现。插件管理控制明确的加载入口，不删除安装文件；游戏选项不 Hook 全局随机数。

## 可重复静态验证

`tools/host-scan/run.py` 直接编译并运行生产 HostScanner，把 APK 当数据读入，不加载宿主类。工具依赖由调用者显式传入，不会自动下载；JDK 21、dexlib2 3.0.9、Guava 和 Android SDK android.jar 足够运行离线扫描。

```
python tools/host-scan/run.py host.apk app/src/main/assets/douyin-host-rules-41.2.0.tsv work/412-scan.tsv --java-home C:/jdk-21.0.9+10 --dexlib C:/path/dexlib.jar --guava C:/path/guava.jar --android-jar C:/path/Android/Sdk/platforms/android-37.0/android.jar --require-unique
```

本地证据包括 `work/adapt-41.2.0/old410-scan.*`、`unified-merged-scan.*`、`comments/type-mapping.json`、定点提取 DEX、方法引用和局部 JADX。`migration_candidates.py` 仅生成结构候选，每项上限 250 并标记截断，不能自动授权 Hook。

以下三类新原包合同已经通过独立 JDK/JUnit 执行，共 **17 项通过**：

- `Host412ContractTest`：原包身份/哈希、伪同 Manifest 拒绝、全规则唯一、下载 getter/native click、真实点赞字段、序列化时长、capability 规则一致性。
- `Host412CommentsContractTest`：模型继承/附件/语音字段、work 上下文往返、异步上传 callback 角色、notice GET/query/provider、一般通知/回复 detail、root/direct resolver 及线程 builder 参数角色。
- `Host412FeedSafetyContractTest`：三个新安全任务的真实 switch 分支、四个原生插入角色和 MediaPlayer 参数转发。

新增 `Host412MediaActionsContractTest` 的三项原包合同也已独立执行通过，直接验证 Video/BitRate 编码与下载地址、User 大头像，以及 Comment 视频/语音的序列化字段。`UnifiedMediaActionsTest`、`CommentMediaActionsIntegrationTest` 和扩展后的 `OfficialDownloadDialogTest` 覆盖编码来源、缺源拒绝、混淆字段、评论菜单真实下载回调、容器保留和头像/Down 来源到所选目录；其完整 Gradle 结果以最终构建记录为准。

历史 `CommentReplyHostContractTest` 使用 HostContractNameFacts 对 40.6/40.8/41.0 做 canonical 名称重写，明确只覆盖这三个宿主。不能将它的旧 Y callback 名盲换到 41.2，也不能把此历史套件的跳过统计为 41.2 通过；41.2 有上面独立的原包合同与运行实现行为测试。

## 最终构建/验证记录

项目使用 JDK 21 和 Android SDK Platform `android-37.0`，构建中显式 `compileSdkMinor = 0`。本地测试包采用普通 debug/unit-test variant。

此前启动修复版回归为 93 个套件、1021 项，0 失败、2 跳过。下载修复版在此基础上补充实际长按和分享执行 Hook、选项更新、new_download_addr/download_url_list、界面会话有效性，以及选择器经真实本机 HTTP 到指定文件夹内容提交等回归。当前最终计数以交付 `manifest.json` 为准。验证仍包含原有消息/播放/界面/AI portable、过滤、隔离、WebDAV、实况与 MP3 等专项；两项历史宿主检查不计为 41.2 成功。两个变体的实际 Manifest、入口与 DEX 类型隔离在打包时独立检查。

Android Lint 的 PrivateApiDetector 会尝试把合法 DEX 混淆名 `X.1bIz` 解析成 Java 类型并崩溃。原始失败日志已保留；使用显式 `-PhostDexLintWorkaround=true` 仅排除 `BlockedPrivateApi`、`DiscouragedPrivateApi`、`PrivateApi`、`SoonBlockedPrivateApi` 四个同属该检测器的项目，其余检查分别对现代和传统变体执行。错误/警告计数见最终清单，不将排除的检测器写成通过。此前检查发现的两个仅 API33 可用的 URL 编解码重载，已经改用 Android9 可用的编码名称重载。

传统引导负责创建兼容框架并调用官方 `attachFramework` 绑定。该接口标为 `InternalApi`，故仅在 `LegacyModuleEntry.bindCompatibilityFramework` 私有函数使用有理由的 `@SuppressLint("XposedInternalApi")`；不全局关闭该检测器，现代 APK 不包含这段兼容引导或 API 实现。实际包检查还拦下了 AGP 将全局 `merges` 置于 `excludes` 之前的入口合并问题，现已将合并规则仅用于现代变体，传统变体仅保留 `assets/xposed_init`。

复现入口为 `tools/verify-unified.ps1 -HostApk <原始41.2.apk> -RunLint`，会顺序完成回归、`:app:assembleDebug :app:assembleLegacy`、两个变体的 Lint。下载修复版的现代 APK 为 `Douyin-Unified-41.2-download3-modern.apk`，包名 `io.github.yudigaga.douai.adapt412`，版本 `0.2.68-41.2-unified-download3 / 169`。传统版为 `Douyin-Unified-41.2-download3-legacy.apk`，包名 `io.github.yudigaga.douai.legacy.adapt412`，版本 `0.2.68-41.2-legacy-download3 / 169`。签名相同，桌面配置分属两个应用，抖音内融合数据命名空间共用；首次空桌面配置会读取已有融合设置，每次只启用一个。最终 APK、补丁、源码快照的 SHA-256 和原始日志均在 `dist/download-fixed-41.2`，不使用旧包或中间失败结果代替最终记录。

静态原包合同与本地行为测试证明定位、数据角色和本地实现；手机上的 LSPosed 回调、真实视频/消息操作、服务端响应、功耗以及 vivo 相册的实际显示仍需设备验收。没有将这些未进行的验证写成已经成功。
