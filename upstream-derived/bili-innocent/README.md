# Bilibili-Innocent 本地版：分享净化与折叠开关

当前源码是上游 `main`（`3418e18ed1c8`，2026-11-23）与本地能力的 **r8 综合合并版**；设备在装仍为 1.2.2 r7，r8 尚未装机。B 站 9.18.0 分享补丁覆盖**净化分享链接**、**分享小程序转链接分享**，面向全部分享渠道（微信 / QQ / 复制链接 / 系统分享等）。

## 上游 main 综合合并（r8，2026-11-26）

- 工作副本 `../../forks/innocent` 当前在 `local/r8-main`（合并结果 `f3d6311c`）；r7 原始工作树完整保存在 `local/r7-v1.2.2`，没有覆盖。上游 53 个新提交与本地折叠不刷新、直播间广告拦截、弹幕标记/跳转全部保留，`sharefix` 继续作为第二入口。
- 冲突只涉及目录与公告登记。上游 capability/settings 目录 `44/86` 向后顺延为 **`47/89`**；本地三项依次登记为 capability `45–47`、settings `87–89`。当前共 284 项设置；验收矩阵升级为 `settings=89; capabilities=47`，保留原有 57 条非 pending 实测状态。
- `:app:testDebugUnitTest :app:assembleRelease` 通过：**640 个测试，0 失败，2 个按条件跳过**；分享净化纯 JVM 测试 **13/13** 通过。构建日志留在 `cache/bili-innocent-r8-full-build.log`。
- release 底座 SHA-256 `3ee532c7…91b2ce52`；最终 `sharefix/dist/BilibiliInnocent-1.2.3-local-r8.apk` 为 15,846,903 B，SHA-256 **`40d89910…8404c42def`**。验包确认底座 DEX 逐字节不变，入口依次为新混淆入口 `s41`、`local.codex.innocent.sharefix.ShareFixEntry`；v2/v3 验签通过，证书仍为 `5fe52e…cc19`。
- 构建时使用 `-PinnocentLab.releaseVersionName=1.2.3-local-r8` 与既有 `innocentLab.signing.*` 配置跑 release，再以该 `app-release.apk` 为 `BASE_APK`、`VERSION_NAME=1.2.3-local-r8` 执行 `sharefix/build.sh`。
- **验收边界不变**：合并、单测和验签不证明真机生效。r7 的弹幕安装器静默问题仍未闭环，r8 需要另行授权装机后复测；本轮未操作设备。

## 折叠变化不刷新

**2026-11-18，B 站 9.18.0 的折叠刷新经用户复测报告解决。** vivo X Fold6 最早由 Innocent r3 完成该项复测。21:07:58 同签名覆盖并对账设备 APK，21:11 在 B 站新进程中确认适配注册和首次命中。[完整复盘](#折叠刷新-r2r3-运行复盘原-logsfoldkeep-r2-runtime-2026-11-18md)（r3 APK 已不在本地留档）

- 功能由 **Innocent** 提供，LiquidTab 265 已移除早期折叠实验。该能力最初基于上游 v1.2.2，当前已并入 `../../forks/innocent` 的 r8 工作副本（GPL-3.0，`forks/` 不入主仓）。[r3 时上游源码](https://github.com/jichuo1/Bilibili_Innocent_Lab/tree/v1.2.2)
- 「增强」页开关名为「折叠变化不刷新」，默认关。持久化键 `fold_keep_not_refresh`，同时登记在持久化目录（r3 时 catalog v77，r8 顺延为 v87）和 `SettingsUiCatalog`。复用既有 modeled toggle 命令与 `hook_config` 发布；通常要等 B 站主进程下一次启动才读取。
- r2 命中的 9.18.0 getter 只覆盖两个宽屏档位之间的切换。r3 在 9180300 主进程中调用 `WindowPaneStyleComponent.f(WindowSizeClass, boolean)` 的原生仅重排路径，保留布局适配并跳过该回调发起的推荐重拉。[静态对照](#同一-apk-的静态对照) · [本地回归](#r3-本地验证)
- 修正后的 `sharefix/build.sh` 保留所选底座的全部入口及 DEX，再追加 `classes2.dex` 和 `ShareFixEntry`。签名前用 `verify_apk.py` 对照入口次序、DEX 原样保留和入口类定义；签完先验签再输出，不覆盖旧包。
- 构建：`local.properties` 用 `sdk.dir=C:/...`；`-PinnocentLab.ncnnArchive=C:/.../innocent/ncnn/ncnn-20261020-android-vulkan.zip` 也用正斜杠。NDK `30.0.16248370`、CMake `4.1.2`。沿用本地签名配置跑 `:app:testDebugUnitTest :app:assembleRelease`，再用 `BASE_APK` 指向本次 release 底座并指定新的 `VERSION_NAME` 执行 `sharefix/build.sh`。

## 分享链路根因

- B 站 9.18.0 的分享框架迁到了 `kntr.common.share`（668 个类的完整新架构）。
  统一数据模型是 `Cn1.c`（序列化字段 `share_mode/program_id/program_path/link/content/...`），
  全渠道在 `kntr.common.share.domain.service.ShareContentPipeline.processContent` 汇聚，
  每个渠道 handler 的 `j(Cn1.c)` 从这一个对象取数据。
- 原模块的 `share_purify`（混淆类 `p53`）只 hook 旧类
  `com.bilibili.lib.sharewrapper.online.api.ShareClickResult`，而该旧类只剩 `supermenu`
  老面板的 7 处引用；新面板的数据根本不经过它，所以两个 hook 装上也空转。
- 原模块自己的「微信分享兼容」(`zj`) 已经迁到 `kntr.common.share.common.handler.wx.*`，
  反证作者知道新框架，只是 `p53` 没迁。
- 远程适配库 `innocent_lab_adapt.json`（sv62）无任何 share 锚点；远程最新模块版本号就是
  本机的 code 23，官方暂无修复。

## 分享补丁的双入口合包

复刻本仓库酷安合并模块（`coolapk/module-merged`，设备上已是双入口实证可行）的做法：

- 初版只含分享净化，使用原版 1.2.2 APK 作底座。r3 使用本地 Innocent 源码构建的 release 底座，合包保留所选底座的全部 DEX。
- 新增 `classes2.dex`，入口 `local.codex.innocent.sharefix.ShareFixEntry`，
  追加到 `META-INF/xposed/java_init.list`。r3 底座的主入口为 `az0`，旧版原始底座的主入口为 `xy0`。
- hook 点：`Cn1.c` 的 13 参反序列化构造器出口（网络响应唯一构造点，全渠道共享）。
  - `purify_share_content`：净化 `c`(link)，并净化 `b`(content) 文案里的 B 站链接，
    规则对齐原 `n53`——只保留 `p`/`t`（`start_progress` 毫秒 /1000 转 `t`），其余查询参数全剥，
    域名白名单 `bilibili.com / b23.tv / bili2233.cn / bilibili.tv`。
  - `share_mini_program_direct_link`：`g`(share_mode) 为 6(微信小程序)/7(QQ小程序) 时
    强制改 3(普通 Link)，标题为空时用文案/链接兜底并截到 120 字符（对齐原 `mh` 行为）。
- 开关复用原模块同一份 Vector 远程配置：`getRemotePreferences("hook_config")`
  读 `purify_share_content` / `share_mini_program_direct_link`，与原模块 `dy0` 用法一致，无需独立设置页。
- 反射改 final 字段，全部包在 protective try/catch，任何异常只记日志不影响 B 站。

## 直播间广告图拦截（r4/r5，2026-11-24）

用户拍板：bilidanmucheck 8.0 只保留三块独有能力（播放器下方 underplayer 广告、直播间广告图、弹幕发送者/点击跳转/进度条标记），其余丢弃，先做直播间。核对结果：underplayer 广告 Innocent 已有（`hide_video_detail_app_promotion` + `DetailAppPromotionFeatureInstaller`，审计覆盖 `...ad.biz.videodetail.underplayer.*`），无需移植；直播间广告图与弹幕三件套确实缺失。

- 开关 `live.room_web_ad.blocked`（默认开，catalog 78 / capability 39），挂在「进阶增强·直播」组。
- 锚点：`com.bilibili.app.comm.bh.NativeWebView` 的 WebViewClient 子类（9.18.0 = `NativeWebView$d`）。按 `$a..$z/$0..$9` 有界枚举 + 「自己声明 `shouldInterceptRequest(WebView, WebResourceRequest) → WebResourceResponse`」筛选，不写死混淆名；候选不唯一按缺失处理。
- r4 实测反证：进直播间后真实投放是**主文档整页** `live.bilibili.com/activity/nuwa/liveroom_activity_pendant/index.html?...&is_live_half_webview=1`，bilidanmucheck 的 `LiveRoomAdSniper` 同场打出 `Sniped by NativeWebView$d`。r4 只拦子资源（`!isForMainFrame`），对真实场景 0 命中，等于没拦。
- r5 策略：主文档命中且 URL 含 `/activity/` → 回空 HTML（整块挂件不渲染）；子资源命中 → 1×1 透明 PNG；非 http(s)、普通页面、`/activity/` 之外的主文档一律不碰。`LiveRoomAdUrlPolicyTest` 7 例覆盖，含 `nuwa` 正文页不误伤。
- 构建：`:app:compileDebugKotlin` + `--tests *LiveRoomAdUrlPolicyTest` 通过；`:app:assembleRelease` 成功（底座 `app-release.apk` SHA-256 `EC228F83BEA1FDC0193708455A2F901B842C9E564EA9E4F7883C35B7D39DC6ED`）；`sharefix/build.sh` 合包 r5，`verify_apk.py` 报 `Verified Xposed entries and unchanged base DEX: az0, local.codex.innocent.sharefix.ShareFixEntry`，v2/v3 验签通过、证书 `5fe52e…cc19`。
- 装机：r4（15:11，`adb install -r` Success，设备哈希 `c33a9e…d1d5` 与本地一致）。r5 在 2026-11-24 16:32 前后 `adb install -r` Success，设备 `pm path` 得到 `/data/app/~~nyKIn2MVOfMn_a-_6w266g==/com.Bilibili_Innocent_Lab.xposedmodule-aDrrTtPz6d7Y7foTZOm7eQ==/base.apk`，设备侧 SHA-256 `11a5f94e…f3688` 与本地一致。
- 真机验证（2026-11-24 16:41，房间 1950045894）：新主进程 25627 打印 `[BIL] Modern 模块入口已加载(api=102, process=tv.danmaku.bili)`、`live_room_web_ad_status=success`、`直播间广告图拦截已安装 client=com.bilibili.app.comm.bh.NativeWebView$d legacy=true`；进入 `LiveRoomActivityV3` 约 20 秒内出现命中 `[BIL] 已拦截直播间广告页面: live.bilibili.com/activity/nuwa/liveroom_activity_pendant/index.html?...`，进程未崩（`pidof` 仍 25627），截图里广告挂件内容已不渲染。
- 残留：主文档回空 HTML 后，宿主侧挂件容器仍保留一个空白小卡位（右上角「虚区/内容连接」徽标）。若要连容器一起隐藏，需要再 hook WebView 控件可见性；当前版本不处理。
- 判定口径：模块是否加载看 `[BIL] Modern 模块入口已加载(api=102, process=tv.danmaku.bili)` 与各 `*_status`，**不要**用 `/proc/<pid>/maps` 里搜 APK 名——9.18.0 主进程日志正常时 maps 里也搜不到。首次 `am start` 后数十秒内查日志容易误判「没注入」：入口要先走 provider 启动授权（`route=provider ... result=granted`）再安装特性。

## 弹幕刷屏标记 + 点击跳转（r6/r7，2026-11-24）

bilidanmucheck 8.0 的弹幕三件套里，唯一真正没法照搬的是**点击跳转**与**进度条标记**：两者都绑在它自带的 Chronos 3.12.0 补丁包上，而 Innocent 的字幕样式已经有自己的 patcher，两个功能会抢同一个包。核对设备上真实包 `cache/bilidanmucheck/host/service_danmaku.pkg` 后确认，bilidanmucheck 记录的主脚本锚点（`var Es=new J;` / `new Hy(t)` / `function dC`）在当前包里**全部不存在**（真实为 `var Es=…`、`new qy(t)`、`dC=class`），所以它的跳转在 9.18.0 上大概率本来就是坏的。

r6 因此完全不改 Chronos 脚本：

- 数据：宿主持有播放器时，按 `bvid` + `cid` 只读请求 `x/web-interface/view`（取 `aid` 与分 P 时长）和 `x/v2/dm/web/seg.so`（每 360s 一段，最多 40 段）；本地 protobuf 解析只取 `progress` / `midHash` / `content` 三个字段。
- 共识：同一句文本（去空白）在 5s 桶内被 **≥3 个不同 `midHash`** 重复才算刷屏点，相邻桶合并，只保留 `(60s, 时长-5s)` 区间，最多 120 点。普通密集但内容不同的弹幕不会出标记。
- 绘制：`DanmakuMarkerView` 是 18dp 窄条，贴在详情页 `android.R.id.content` 底部（margin 44dp），只画 1.2dp 红色刻线；**没有任何标记点时必须整条 `GONE`，不画任何底色**（r6 初版留了一条全宽半透明粉色底条，真机被误认成屏幕故障，r7 已删）。点击 14dp 内最近刻线且与当前位置相差 ≥15s 才跳。
- 跳转：直接复用 sponsor 播放器结构（`SponsorPlayerLocator` / `SponsorPlayerBindings`），通过 `IPlayerCoreService.seekTo(int, boolean)` 执行；跳转前重新校验 bvid/cid 未变，避免切视频后跳错。
- 开关：`player.danmaku_jump_markers`（默认开，catalog 79 / capability 40），与字幕样式完全解耦——字幕样式关掉也能单独用标记。
- 只读边界：扫描在独立 daemon 线程，只发 GET，不读宿主登录态，不在设备或宿主目录写任何文件。
- 验证：`DanmakuMarkerClientTest` 4 例（三人同句成点、两人或跨段不成立、窗口外丢弃、相邻桶合并）通过；`SettingsCatalogTest` / `DiagnosticCapabilityCatalogTest` / `SubtitlePackagePatcherTest` 全通过（`CATALOG_VERSION=79`、271 specs、265 AUTOMATIC、166 BOOLEAN）。
- 产物：r6 底座 release SHA-256 `30A584B8…bfb41e`、合包 `BilibiliInnocent-1.2.2-danmaku-marker-r6.apk`（14,472,197 B，`6376B3D3…aa1990`）；r7 底座 release SHA-256 `11695050…a90655`、合包 `BilibiliInnocent-1.2.2-danmaku-marker-r7.apk`（14,472,197 B，`DECD5D6F…FA5D`）。两版均 v2/v3 验签通过、证书 `5fe52e…cc19`。
- 装机状态（2026-11-24 17:12–17:16）：r6、r7 均 `adb install -r` Success，设备 `pm path` 指向 `/data/app/~~Tyr3T2m6VSjPOe2cWaaeJQ==/com.Bilibili_Innocent_Lab.xposedmodule-Bt_aKYicTX4b81eG_koOyg==/base.apk`（r6 设备哈希 `6376b3d3…aa1990` 与本地一致）。r7 装机后连开两个视频详情、播放中截图确认播放区下方无粉色长条。
- **未闭环**：真机冷启动日志里始终**没有** `[BIL] 弹幕刷屏标记已安装`，三条 Skipped 分支也没打出 `danmaku_marker_skip`，说明安装器在当前 9.18.0 上根本没被调用或被上游开关判断提前拦下；因此标记功能目前是「装了但静默不生效」，进度条上看不到任何刻线。排查下一步：确认 `HookEntry` 里该安装器是否在 `running`/`enabled` 分支内、以及 `player.danmaku_jump_markers` 是否被设置文件覆盖为 false。

## 签名与目录

当前设备上的本地版使用 `sharefix/.signing/innocent-sharefix.keystore`（证书 SHA-256 `5fe52e…cc19`，见 [签名登记](../../shared/signing/README.md)）。后续本地版沿用同一证书和包名，**不卸载、不迁移配置**。作者版签名不同，不能当作可覆盖的回滚包。

- `sharefix/dist/BilibiliInnocent-1.2.3-local-r8.apk`：上游 `main` + 本地全部能力的 r8 综合合并包（仅本地构建验收，尚未装机）。
- `sharefix/src/`：原有 `LinkPurifier` 与 `ShareFixEntry`。
- `sharefix/tests/PurifierTest.java`：分享净化纯 JVM 单测；`source/` 下的 `FoldKeepSettingTest` 覆盖开关登记与导出值。
- `sharefix/dist/BilibiliInnocent-1.2.2-sharefix.apk`：旧的、只含分享净化的同签名留档。
- `sharefix/dist/BilibiliInnocent-1.2.2-foldkeep-sharefix.apk`：19:42 的旧包，保留作坏包对照，不用于验收。
- `sharefix/dist/BilibiliInnocent-1.2.2-foldkeep-sharefix-r2.apk`：20:26 覆盖、20:28 开合仍刷新的运行对照（产物已清理，未留档）。[r2 构建与装机](#r2-构建与验证记录原-logsfoldkeep-r2-validation-2026-11-18md)
- `sharefix/dist/BilibiliInnocent-1.2.2-livewebad-r4.apk`：15:11 装机的子资源版，只拦子资源、真实主文档投放 0 命中，留作对照。
- `sharefix/dist/BilibiliInnocent-1.2.2-livewebad-r5.apk`：主文档投放页回空 HTML 的修正版。
- `sharefix/dist/BilibiliInnocent-1.2.2-danmaku-marker-r6.apk`：弹幕刷屏标记 + 点击跳转首版（带全宽粉底条，真机误判为屏幕故障）。
- `sharefix/dist/BilibiliInnocent-1.2.2-danmaku-marker-r7.apk`：修掉粉底条、无标记整条 `GONE` 的视觉修正版（当前设备在装）。
- `backup/`、`sharefix/restore-config.sh`：历史首次换签迁移留档，本次同签名更新不使用。

## 真机复测

上机另行授权，遵守[设备红线](../../AGENTS.md#1-设备红线最高优先级覆盖一切默认行为)：用户在场、屏幕亮且解锁，只允许同签名覆盖，失败立即停止。不要改 Vector 配置库、杀系统进程或重启设备。

1. 用户手动打开 Innocent，在「增强」启用「折叠变化不刷新」，退出再进入确认状态仍开。
2. B 站主进程下次启动时核对 Vector 的模块加载日志。9.18.0 可在 Innocent 完整档日志中对照 `registered: window-pane-layout-only` 和 `layout-only-hit: window-pane`。
3. 记录推荐区首屏几条视频的标题或 BV，折叠、展开分别核对视频和列数，再验证下拉刷新与继续分页。
4. 复测分享净化与小程序转链接。

> 本文件已合并 `logs/foldkeep-r2-*.md`；上游源码工作副本位于 `../../forks/innocent`（GPL-3.0，`forks/` 不入主仓），ncnn 与底座备份位于 `../../cache/bases/innocent/`，签名位于 `../../shared/signing/innocent-sharefix.keystore`。
>
> **换机还原（当前 r8）**：检出上游 `main` 的 `3418e18ed1c8` 后，应用 `source-main-3418e18-local-r8.patch`（16 个修改文件）并覆盖 `source-main-3418e18-r8-new-files/`（11 个新增文件），即可得到 `local/r8-main`；已在干净上游 worktree 中跑过 `git apply --check`，并核对还原结果与合并工作树逐文件一致。`source-v1.2.2-local-r7/r5/r3` 与对应 `-new-files/` 保留作历史对照。
>
> **链接口径**：早期 `../source/...` 是指向本机未入库工作副本的相对链接，该副本已迁到 `../../forks/innocent`；本文件已把源码链接改指 `source-v1.2.2-r7-new-files/`，随 `source/`、`sharefix/build/` 一起消失的构建日志改成文字说明（不再提供死链）。

---

## 折叠刷新 r2/r3 运行复盘（原 logs/foldkeep-r2-runtime-2026-11-18.md）

# B 站 9.18.0 折叠保留推荐流复盘

2026-11-18，UTC+8。vivo X Fold6 上的 B 站 9.18.0（9180300）安装 Innocent 1.2.2 r3 后，用户反馈开合刷新问题已解决。B 站新进程日志记录了 r3 的适配注册和首次执行。[真机证据](#r3-真机反馈)

## 时间线

| 时间 | 现象与处理 |
|---|---|
| 16:39 | LiquidTab 263 下用户观察到开合换推荐视频。Activity 和 RecyclerView 实例保留，布局宽度由 2312px 变为 1120px。[LiquidTab 记录](../liquidtab-mine/README-MINE.md) |
| 19:17 | Innocent 首包安装后打开模块 App 闪退。新增设置后，目录数量断言仍预期 268 项，实际为 269 项。[首包记录](logs/foldkeep-sharefix-install-2026-11-18.txt) |
| 19:42 | 修完目录后模块 App 可以打开，开合仍换推荐。合包把底座的 `zy0` 写成了旧入口 `xy0`，开关漏登 `SettingsUiCatalog`，点按也未进入保存命令。[r2 排查记录](#旧包与复现) |
| 20:24–20:26 | LiquidTab 升到移除折叠实验的 265。Innocent r2 补齐开关登记和导出验证，合包从当前底座保留实际 Xposed 入口及 DEX，随后同签名覆盖。[装机记录](#同签名覆盖记录) |
| 20:28 | r2 在 B 站新进程中命中保留 getter，合上和展开仍换视频。对账装机 APK 后检查 9.18.0 的 `classes14.dex`，发现该 getter 只覆盖两个宽屏档位之间的切换。[现场](#2028-现场) · [字节码对照](#同一-apk-的静态对照) |
| 21:07–21:12 | r3 对 9180300 使用宿主已有的仅重排路径。双向本地回归通过，21:07:58 同签名覆盖，21:11 新进程注册并命中，随后收到用户正向反馈。[本地验证](#r3-本地验证) · [真机反馈](#r3-真机反馈) |

## 20:28 现场

- B 站是 9.18.0（9180300）。设备 `base.apk` 与本地 `drag-all/apk/tv.danmaku.bili-9.18.0.apk` 的 SHA-256 一致（`df5c9b2eee64023c75ac403116a5765970f7cd351b834c98fc0a38dcc2398c04`）。当时是 Innocent r2 和已移除折叠实验的 LiquidTab 265，见[安装记录](#同签名覆盖记录)。
- `logcat -b events` 记录 `20:27:33.446 am_proc_start [0,17567,10291,tv.danmaku.bili,preStart_down,tv.danmaku.bili]`。20:28 窗口内 `.MainActivityV2` 保持同一实例，之后只读 `pidof` 仍为 17567。
- 模块自身的日志记录 `20:28:01.390 17567 17567 I BilibiliInnocentLab: [BIL] fold_keep_not_refresh getter-hit: getEnableKeepFeedsOnWideColumnSwitch`，确认 r2 的替换回调已在新宿主进程执行。最新 Vector verbose 文件内容止于 11-15。本次推荐请求是否实际发出需单独的请求证据。
- 用户实测折叠、展开都会更换推荐视频。推荐内容保留验收未通过。

## 同一 APK 的静态对照

- 9.19.0 有 `getEnableFoldKeepNotRefresh()`。本机 9.18.0 的 `classes14.dex` 只有 `getEnableKeepFeedsOnWideColumnSwitch()`。它为 `true` 时，`WindowPaneStyleComponent.f(WindowSizeClass, boolean)` 保留 `Medium ↔ Large`。`Normal ↔ Medium/Large` 且宿主空闲时仍会调用 `PegasusViewModel.d1(..., PegasusFlush.NORMAL, ...)`。
- 同一方法收到 `false` 时跳过尺寸刷新分支，继续执行 `setSpanCount`、卡片尺寸通知和重排。9.18.0 的视图布局和窗口焦点回调已有传 `false` 的调用点。
- 本地反编译证据位于 `drag-all/analysis/bili-9.18.0/jadx-windowpane/PegasusDDConfigKt.java:24` 和 `drag-all/analysis/bili-9.18.0/jadx-windowpane/WindowPaneStyleComponent-dex.java:692`。

## r3 本地验证

- B 站 9180300 主进程在开关开启时启用尺寸回调适配。确认 `WindowPaneStyleComponent.f(WindowSizeClass, boolean)` 是实例 `void` 方法后，把第二参从 `true` 改为 `false`，由原生方法完成布局。手动刷新与分页沿各自原生入口运行。[实现](source-v1.2.2-r7-new-files/Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/hook/feature/FoldKeepFeedFeatureInstaller.kt)
- 以该分支的宿主替身跑真实 Innocent Hook 回调。旧逻辑的窄→宽、宽→窄用例都观察到一次刷新，修正后均为零次且布局通过。完整 JVM 单测 5692 通过、1 跳过（修前/修后/完整构建的原始 Gradle 日志随本地 `source/` 清理丢失）。
- r3 APK SHA-256 为 `86c4d1b2e1c70527934a0d4a69bc85ae528fbe6fb13baa9e8a89c977f06cb604`。包名和证书与 r2 一致，分享补丁 DEX 的 SHA-256 也与 r2 一致。合包校验与 v2/v3 验签通过（r3 APK 与验包日志均未留档）。

## r3 装机

- 2026-11-18 21:07:58，用户当次授权且设备亮屏解锁时，`adb install -r` 返回 `Success`。设备 APK 的 SHA-256 与本地 r3 完全一致（原始安装日志随本地 `source/` 清理丢失）。
- 装机完成时 B 站主进程仍是 PID 17567。21:11 后实际运行 r3 的证据来自新进程 PID 27390。

## r3 真机反馈

安装后通过只读 ADB 取到以下三条记录，时间均为 11-18 UTC+8。

```text
21:11:28.775 am_proc_start [0,27390,10291,tv.danmaku.bili,preStart_down,tv.danmaku.bili]
21:11:31.670 27390 BilibiliInnocentLab [BIL] fold_keep_not_refresh registered: window-pane-layout-only
21:11:33.561 27390 BilibiliInnocentLab [BIL] fold_keep_not_refresh layout-only-hit: window-pane
```

`registered` 记录注册，`layout-only-hit` 记录首次改写尺寸刷新参数。用户复测后反馈「完美解决了估计」，记为折叠刷新正向实测。两向的视频身份、列数、主动下拉、分页和分享功能仍可按复测清单逐项记录。[复测清单](#真机复测)

## 排查要点

- 按设备 APK 的 SHA-256 对照实际宿主 DEX。先核对 getter 的调用条件，再判断它能覆盖哪种窗口变化。
- 分别验证 APK 入口与 DEX、开关持久化与发布、宿主回调执行，以及开合前后的推荐视频身份。20:28 的 r2 正是「回调命中，推荐仍变」的对照。
- 优先复用宿主的布局分支。本案的 `false` 参数让列数和卡片尺寸继续随窗口更新，同时跳过尺寸变化发起的推荐重拉。双向回归通过真实 Innocent Hook DSL 调用覆盖了这条分支。[回归用例](source-v1.2.2-r7-new-files/Bilibili_Innocent_Lab/app/src/test/java/com/Bilibili_Innocent_Lab/xposedmodule/hook/feature/FoldKeepFeedFeatureInstallerTest.kt)

## 顶栏消息按钮

用户另报消息按钮更新不及时。设备上的 `liquidtab-home-layout.txt` 在 20:27:37.807 记录 `binding=compose`，消息按钮由 LiquidTab 265 裁取原生 Compose 图层绘制。要定位延迟，需在同一次未读变化中对照原生图层和玻璃按钮的更新时间。

---

## r2 构建与验证记录（原 logs/foldkeep-r2-validation-2026-11-18.md）

# 折叠变化不刷新 r2：本地验证 / 2026-11-18

## 旧包与复现

- 设备 19:42 装入的 `com.Bilibili_Innocent_Lab.xposedmodule`（23 / 1.2.2）与本地旧 `BilibiliInnocent-1.2.2-foldkeep-sharefix.apk` SHA-256 一致：`a734d1033283d5bd8c02a210e411149cbde75bc87f5cdc154c0cd4d1a6bd8d4c`。它修了 [19:17 的目录启动异常](logs/foldkeep-sharefix-install-2026-11-18.txt)，但用户实测开合仍换了推荐视频。
- 旧源码底座声明 `zy0`（R8 映射为 `HookEntry`）；旧合包声明 `xy0` + `ShareFixEntry`。主 DEX 未换，入口列表与其不匹配。`verify_apk.py` 对该旧合包报 `Entry list mismatch`；对旧原版底座及只含分享净化的合包，校验通过。旧底座另走新 `build.sh` 也通过（对照日志已清理，未留档）。
- 开关原先可显示，但 `applyModeledToggle()` 从未登记的 `SettingsUiCatalog` 找不到对应项，不会执行保存。补登记前，`FoldKeepSettingTest` 运行失败（`NoSuchElementException`；原始日志已随本地 `source/` 清理丢失）。有界 logcat 没看到相关运行证据，未拿到对应 Vector verbose log，不据此断言模块是否加载。

## 新包的本地验证

- 文件：`BilibiliInnocent-1.2.2-foldkeep-sharefix-r2.apk`（产物已清理，未留档），包名和版本仍为 `com.Bilibili_Innocent_Lab.xposedmodule` / 23 / 1.2.2。证书与 19:42 版一致（SHA-256 `5fe52e…cc19`），沿用现有签名，不更换配置。
- 源码底座 SHA-256：`84111a0ed8f0e4b60c347454529135e8f516385318800f3dd765c757e5707bd6`。最终合包 SHA-256：`5a928439d211b7887ab316f262a3119fadbca885aa5e82bcabb263944905e636`。
- 本次 R8 映射 `HookEntry -> az0`；最终列表按序为 `az0`、`local.codex.innocent.sharefix.ShareFixEntry`。两类有实际 DEX 定义，所有底座 DEX 逐字节不变，新增 `classes2.dex` 与上次装机包的分享补丁 DEX 逐字节一致（SHA-256 `4a9c30693b70974cd42a0ddf82dd7752cc4e3c1b28882eef0aaf5568ac3a835f`）。
- `:app:testDebugUnitTest :app:assembleRelease` 成功：5689 通过、0 失败、1 跳过；新增设置描述项/命令与默认关和布尔值导出回归通过。分享净化 13/13 纯 JVM 用例通过（Gradle、合包、验包、净化测试的原始日志均已清理，未留档）。

## 验收边界

开关重进仍然打开、B 站主进程读取并执行 getter、两向折叠后仍是同一批视频，是三个不同的证据。完整档模块日志 `registered` 仅表示登记返回，`getter-hit` 仅表示第一次执行，未证明执行发生在开合分支。9.18.0 不能用 9.19.0 的静态路径代替真机结果。验收时同时观察布局、主动刷新和翻页；不要拿卡片 View 对象或模块 App 启动替代视频身份。

## 同签名覆盖记录

- 用户确认在场、亮屏解锁后，两次独立检查设备状态并依次覆盖：20:24:57 LiquidTab 265，20:26:19 Innocent r2。两个设备 APK 均与各自本地文件的 SHA-256 完全一致（两处安装日志与对账日志均已清理，未留档）。
- 未卸载、未改 Vector、未代开模块或重启宿主。功能实测须等用户打开模块并开启开关后，在 B 站新主进程中进行。

