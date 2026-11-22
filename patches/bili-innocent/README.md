# Bilibili-Innocent 本地版：分享净化与折叠开关

基于 `com.Bilibili_Innocent_Lab.xposedmodule` 1.2.2。B 站 9.18.0 分享补丁覆盖**净化分享链接**、
**分享小程序转链接分享**，面向全部分享渠道（微信 / QQ / 复制链接 / 系统分享等）。

## 折叠变化不刷新

**2026-11-18，B 站 9.18.0 的折叠刷新经用户复测报告解决。** vivo X Fold6 当前装机包为 Innocent r3。21:07:58 同签名覆盖并对账设备 APK，21:11 在 B 站新进程中确认适配注册和首次命中。[完整复盘](logs/foldkeep-r2-runtime-2026-11-18.md) · [r3 APK](sharefix/dist/BilibiliInnocent-1.2.2-foldkeep-sharefix-r3.apk)

- 功能由 **Innocent** 提供，LiquidTab 265 已移除早期折叠实验。`source/` 是上游 v1.2.2 源码工作副本（GPL-3.0）。[上游源码](https://github.com/jichuo1/Bilibili_Innocent_Lab/tree/v1.2.2)
- 「增强」页开关名为「折叠变化不刷新」，默认关。持久化键 `fold_keep_not_refresh`，同时登记在持久化目录（catalog v77）和 `SettingsUiCatalog`。复用既有 modeled toggle 命令与 `hook_config` 发布；通常要等 B 站主进程下一次启动才读取。
- r2 命中的 9.18.0 getter 只覆盖两个宽屏档位之间的切换。r3 在 9180300 主进程中调用 `WindowPaneStyleComponent.f(WindowSizeClass, boolean)` 的原生仅重排路径，保留布局适配并跳过该回调发起的推荐重拉。[静态对照](logs/foldkeep-r2-runtime-2026-11-18.md#同一-apk-的静态对照) · [本地回归](logs/foldkeep-r2-runtime-2026-11-18.md#r3-本地验证)
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

## 签名与目录

当前设备上的本地版使用 `sharefix/.signing/innocent-sharefix.keystore`（证书 SHA-256 `5fe52e…cc19`，见 [签名登记](../../shared/signing/README.md)）。后续本地版沿用同一证书和包名，**不卸载、不迁移配置**。作者版签名不同，不能当作可覆盖的回滚包。

- `sharefix/src/`：原有 `LinkPurifier` 与 `ShareFixEntry`。
- `sharefix/tests/PurifierTest.java`：分享净化纯 JVM 单测；`source/` 下的 `FoldKeepSettingTest` 覆盖开关登记与导出值。
- `sharefix/dist/BilibiliInnocent-1.2.2-sharefix.apk`：旧的、只含分享净化的同签名留档。
- `sharefix/dist/BilibiliInnocent-1.2.2-foldkeep-sharefix.apk`：19:42 的旧包，保留作坏包对照，不用于验收。
- `sharefix/dist/BilibiliInnocent-1.2.2-foldkeep-sharefix-r2.apk`：20:26 覆盖、20:28 开合仍刷新的运行对照。[r2 构建与装机](logs/foldkeep-r2-validation-2026-11-18.md)
- `backup/`、`sharefix/restore-config.sh`：历史首次换签迁移留档，本次同签名更新不使用。

## 真机复测

上机另行授权，遵守[设备红线](../../AGENTS.md#1-设备红线最高优先级覆盖一切默认行为)：用户在场、屏幕亮且解锁，只允许同签名覆盖，失败立即停止。不要改 Vector 配置库、杀系统进程或重启设备。

1. 用户手动打开 Innocent，在「增强」启用「折叠变化不刷新」，退出再进入确认状态仍开。
2. B 站主进程下次启动时核对 Vector 的模块加载日志。9.18.0 可在 Innocent 完整档日志中对照 `registered: window-pane-layout-only` 和 `layout-only-hit: window-pane`。
3. 记录推荐区首屏几条视频的标题或 BV，折叠、展开分别核对视频和列数，再验证下拉刷新与继续分页。
4. 复测分享净化与小程序转链接。

> 本文件已合并 `logs/foldkeep-r2-*.md`；上游源码副本位于 `../../forks/innocent`，ncnn 与底座备份位于 `../../cache/bases/innocent/`，签名位于 `../../shared/signing/innocent-sharefix.keystore`。

---

## 折叠刷新 r2/r3 运行复盘（原 logs/foldkeep-r2-runtime-2026-11-18.md）

# B 站 9.18.0 折叠保留推荐流复盘

2026-11-18，UTC+8。vivo X Fold6 上的 B 站 9.18.0（9180300）安装 Innocent 1.2.2 r3 后，用户反馈开合刷新问题已解决。B 站新进程日志记录了 r3 的适配注册和首次执行。[真机证据](#r3-真机反馈)

## 时间线

| 时间 | 现象与处理 |
|---|---|
| 16:39 | LiquidTab 263 下用户观察到开合换推荐视频。Activity 和 RecyclerView 实例保留，布局宽度由 2312px 变为 1120px。[LiquidTab 记录](../../liquidtab-mine/README-MINE.md) |
| 19:17 | Innocent 首包安装后打开模块 App 闪退。新增设置后，目录数量断言仍预期 268 项，实际为 269 项。[首包记录](foldkeep-sharefix-install-2026-11-18.txt) |
| 19:42 | 修完目录后模块 App 可以打开，开合仍换推荐。合包把底座的 `zy0` 写成了旧入口 `xy0`，开关漏登 `SettingsUiCatalog`，点按也未进入保存命令。[r2 排查记录](foldkeep-r2-validation-2026-11-18.md#旧包与复现) |
| 20:24–20:26 | LiquidTab 升到移除折叠实验的 265。Innocent r2 补齐开关登记和导出验证，合包从当前底座保留实际 Xposed 入口及 DEX，随后同签名覆盖。[装机记录](foldkeep-r2-validation-2026-11-18.md#同签名覆盖记录) |
| 20:28 | r2 在 B 站新进程中命中保留 getter，合上和展开仍换视频。对账装机 APK 后检查 9.18.0 的 `classes14.dex`，发现该 getter 只覆盖两个宽屏档位之间的切换。[现场](#2028-现场) · [字节码对照](#同一-apk-的静态对照) |
| 21:07–21:12 | r3 对 9180300 使用宿主已有的仅重排路径。双向本地回归通过，21:07:58 同签名覆盖，21:11 新进程注册并命中，随后收到用户正向反馈。[本地验证](#r3-本地验证) · [真机反馈](#r3-真机反馈) |

## 20:28 现场

- B 站是 9.18.0（9180300）。设备 `base.apk` 与本地 `drag-all/apk/tv.danmaku.bili-9.18.0.apk` 的 SHA-256 一致（`df5c9b2eee64023c75ac403116a5765970f7cd351b834c98fc0a38dcc2398c04`）。当时是 Innocent r2 和已移除折叠实验的 LiquidTab 265，见[安装记录](foldkeep-r2-validation-2026-11-18.md#同签名覆盖记录)。
- `logcat -b events` 记录 `20:27:33.446 am_proc_start [0,17567,10291,tv.danmaku.bili,preStart_down,tv.danmaku.bili]`。20:28 窗口内 `.MainActivityV2` 保持同一实例，之后只读 `pidof` 仍为 17567。
- 模块自身的日志记录 `20:28:01.390 17567 17567 I BilibiliInnocentLab: [BIL] fold_keep_not_refresh getter-hit: getEnableKeepFeedsOnWideColumnSwitch`，确认 r2 的替换回调已在新宿主进程执行。最新 Vector verbose 文件内容止于 11-15。本次推荐请求是否实际发出需单独的请求证据。
- 用户实测折叠、展开都会更换推荐视频。推荐内容保留验收未通过。

## 同一 APK 的静态对照

- 9.19.0 有 `getEnableFoldKeepNotRefresh()`。本机 9.18.0 的 `classes14.dex` 只有 `getEnableKeepFeedsOnWideColumnSwitch()`。它为 `true` 时，`WindowPaneStyleComponent.f(WindowSizeClass, boolean)` 保留 `Medium ↔ Large`。`Normal ↔ Medium/Large` 且宿主空闲时仍会调用 `PegasusViewModel.d1(..., PegasusFlush.NORMAL, ...)`。
- 同一方法收到 `false` 时跳过尺寸刷新分支，继续执行 `setSpanCount`、卡片尺寸通知和重排。9.18.0 的视图布局和窗口焦点回调已有传 `false` 的调用点。
- 本地反编译证据位于 `drag-all/analysis/bili-9.18.0/jadx-windowpane/PegasusDDConfigKt.java:24` 和 `drag-all/analysis/bili-9.18.0/jadx-windowpane/WindowPaneStyleComponent-dex.java:692`。

## r3 本地验证

- B 站 9180300 主进程在开关开启时启用尺寸回调适配。确认 `WindowPaneStyleComponent.f(WindowSizeClass, boolean)` 是实例 `void` 方法后，把第二参从 `true` 改为 `false`，由原生方法完成布局。手动刷新与分页沿各自原生入口运行。[实现](../source/Bilibili_Innocent_Lab/app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/hook/feature/FoldKeepFeedFeatureInstaller.kt)
- 以该分支的宿主替身跑真实 Innocent Hook 回调。旧逻辑的窄→宽、宽→窄用例都观察到一次刷新，修正后均为零次且布局通过。完整 JVM 单测 5692 通过、1 跳过。[修前](../source/Bilibili_Innocent_Lab/build/fold-918-red.log) · [修后](../source/Bilibili_Innocent_Lab/build/fold-918-green.log) · [完整构建](../source/Bilibili_Innocent_Lab/build/fold-918-release.log)
- r3 APK SHA-256 为 `86c4d1b2e1c70527934a0d4a69bc85ae528fbe6fb13baa9e8a89c977f06cb604`。包名和证书与 r2 一致，分享补丁 DEX 的 SHA-256 也与 r2 一致。合包校验与 v2/v3 验签通过。[r3 APK](../sharefix/dist/BilibiliInnocent-1.2.2-foldkeep-sharefix-r3.apk) · [验包日志](../source/Bilibili_Innocent_Lab/build/fold-918-merge.log)

## r3 装机

- 2026-11-18 21:07:58，用户当次授权且设备亮屏解锁时，`adb install -r` 返回 `Success`。设备 APK 的 SHA-256 与本地 r3 完全一致。[安装日志](../source/Bilibili_Innocent_Lab/build/fold-918-r3-install.log)
- 装机完成时 B 站主进程仍是 PID 17567。21:11 后实际运行 r3 的证据来自新进程 PID 27390。

## r3 真机反馈

安装后通过只读 ADB 取到以下三条记录，时间均为 11-18 UTC+8。

```text
21:11:28.775 am_proc_start [0,27390,10291,tv.danmaku.bili,preStart_down,tv.danmaku.bili]
21:11:31.670 27390 BilibiliInnocentLab [BIL] fold_keep_not_refresh registered: window-pane-layout-only
21:11:33.561 27390 BilibiliInnocentLab [BIL] fold_keep_not_refresh layout-only-hit: window-pane
```

`registered` 记录注册，`layout-only-hit` 记录首次改写尺寸刷新参数。用户复测后反馈「完美解决了估计」，记为折叠刷新正向实测。两向的视频身份、列数、主动下拉、分页和分享功能仍可按复测清单逐项记录。[复测清单](../README.md#真机复测)

## 排查要点

- 按设备 APK 的 SHA-256 对照实际宿主 DEX。先核对 getter 的调用条件，再判断它能覆盖哪种窗口变化。
- 分别验证 APK 入口与 DEX、开关持久化与发布、宿主回调执行，以及开合前后的推荐视频身份。20:28 的 r2 正是「回调命中，推荐仍变」的对照。
- 优先复用宿主的布局分支。本案的 `false` 参数让列数和卡片尺寸继续随窗口更新，同时跳过尺寸变化发起的推荐重拉。双向回归通过真实 Innocent Hook DSL 调用覆盖了这条分支。[回归用例](../source/Bilibili_Innocent_Lab/app/src/test/java/com/Bilibili_Innocent_Lab/xposedmodule/hook/feature/FoldKeepFeedFeatureInstallerTest.kt)

## 顶栏消息按钮

用户另报消息按钮更新不及时。设备上的 `liquidtab-home-layout.txt` 在 20:27:37.807 记录 `binding=compose`，消息按钮由 LiquidTab 265 裁取原生 Compose 图层绘制。要定位延迟，需在同一次未读变化中对照原生图层和玻璃按钮的更新时间。

---

## r2 构建与验证记录（原 logs/foldkeep-r2-validation-2026-11-18.md）

# 折叠变化不刷新 r2：本地验证 / 2026-11-18

## 旧包与复现

- 设备 19:42 装入的 `com.Bilibili_Innocent_Lab.xposedmodule`（23 / 1.2.2）与本地旧 `BilibiliInnocent-1.2.2-foldkeep-sharefix.apk` SHA-256 一致：`a734d1033283d5bd8c02a210e411149cbde75bc87f5cdc154c0cd4d1a6bd8d4c`。它修了 [19:17 的目录启动异常](foldkeep-sharefix-install-2026-11-18.txt)，但用户实测开合仍换了推荐视频。
- 旧源码底座声明 `zy0`（R8 映射为 `HookEntry`）；旧合包声明 `xy0` + `ShareFixEntry`。主 DEX 未换，入口列表与其不匹配。`verify_apk.py` 对该旧合包报 `Entry list mismatch`；对旧原版底座及只含分享净化的合包，校验通过。旧底座另走新 `build.sh` 通过，[对照日志](../sharefix/build/stock-control.bwZJFz/merge.log)。
- 开关原先可显示，但 `applyModeledToggle()` 从未登记的 `SettingsUiCatalog` 找不到对应项，不会执行保存。补登记前，`FoldKeepSettingTest` 运行失败（`NoSuchElementException`，日志 `source/Bilibili_Innocent_Lab/build/fold-setting-red.XwaPDv`）。有界 logcat 没看到相关运行证据，未拿到对应 Vector verbose log，不据此断言模块是否加载。

## 新包的本地验证

- 文件：[BilibiliInnocent-1.2.2-foldkeep-sharefix-r2.apk](../sharefix/dist/BilibiliInnocent-1.2.2-foldkeep-sharefix-r2.apk)，包名和版本仍为 `com.Bilibili_Innocent_Lab.xposedmodule` / 23 / 1.2.2。证书与 19:42 版一致（SHA-256 `5fe52e…cc19`），沿用现有签名，不更换配置。
- 源码底座 SHA-256：`84111a0ed8f0e4b60c347454529135e8f516385318800f3dd765c757e5707bd6`。最终合包 SHA-256：`5a928439d211b7887ab316f262a3119fadbca885aa5e82bcabb263944905e636`。
- 本次 R8 映射 `HookEntry -> az0`；最终列表按序为 `az0`、`local.codex.innocent.sharefix.ShareFixEntry`。两类有实际 DEX 定义，所有底座 DEX 逐字节不变，新增 `classes2.dex` 与上次装机包的分享补丁 DEX 逐字节一致（SHA-256 `4a9c30693b70974cd42a0ddf82dd7752cc4e3c1b28882eef0aaf5568ac3a835f`）。
- `:app:testDebugUnitTest :app:assembleRelease` 成功：5689 通过、0 失败、1 跳过；新增设置描述项/命令与默认关和布尔值导出回归通过。分享净化 13/13 纯 JVM 用例通过。[Gradle](../sharefix/build/foldkeep-r2.G2OMpC/gradle-signed.log) · [合包](../sharefix/build/foldkeep-r2.G2OMpC/merge.log) · [验包](../sharefix/build/foldkeep-r2.G2OMpC/verify.log) · [净化测试](../sharefix/build/foldkeep-r2.G2OMpC/purifier-test.log)。

## 验收边界

开关重进仍然打开、B 站主进程读取并执行 getter、两向折叠后仍是同一批视频，是三个不同的证据。完整档模块日志 `registered` 仅表示登记返回，`getter-hit` 仅表示第一次执行，未证明执行发生在开合分支。9.18.0 不能用 9.19.0 的静态路径代替真机结果。验收时同时观察布局、主动刷新和翻页；不要拿卡片 View 对象或模块 App 启动替代视频身份。

## 同签名覆盖记录

- 用户确认在场、亮屏解锁后，两次独立检查设备状态并依次覆盖：20:24:57 LiquidTab 265（[安装日志](../../liquidtab-mine/build/bili-release-265.N5PtHC/install.lAEM4y.log)），20:26:19 Innocent r2（[安装日志](../sharefix/build/foldkeep-r2.G2OMpC/install.ktvy5z.log)）。两个设备 APK 均与各自本地文件的 SHA-256 完全一致；[对账结果](../sharefix/build/foldkeep-r2.G2OMpC/installed.Bfd1jP.log)。
- 未卸载、未改 Vector、未代开模块或重启宿主。功能实测须等用户打开模块并开启开关后，在 B 站新主进程中进行。

