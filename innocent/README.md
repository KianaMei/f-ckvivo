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

当前设备上的本地版使用 `sharefix/.signing/innocent-sharefix.keystore`（证书 SHA-256 `5fe52e…cc19`，见 [签名登记](../signing/README.md)）。后续本地版沿用同一证书和包名，**不卸载、不迁移配置**。作者版签名不同，不能当作可覆盖的回滚包。

- `sharefix/src/`：原有 `LinkPurifier` 与 `ShareFixEntry`。
- `sharefix/tests/PurifierTest.java`：分享净化纯 JVM 单测；`source/` 下的 `FoldKeepSettingTest` 覆盖开关登记与导出值。
- `sharefix/dist/BilibiliInnocent-1.2.2-sharefix.apk`：旧的、只含分享净化的同签名留档。
- `sharefix/dist/BilibiliInnocent-1.2.2-foldkeep-sharefix.apk`：19:42 的旧包，保留作坏包对照，不用于验收。
- `sharefix/dist/BilibiliInnocent-1.2.2-foldkeep-sharefix-r2.apk`：20:26 覆盖、20:28 开合仍刷新的运行对照。[r2 构建与装机](logs/foldkeep-r2-validation-2026-11-18.md)
- `backup/`、`sharefix/restore-config.sh`：历史首次换签迁移留档，本次同签名更新不使用。

## 真机复测

上机另行授权，遵守[设备红线](../AGENTS.md#1-设备红线最高优先级覆盖一切默认行为)：用户在场、屏幕亮且解锁，只允许同签名覆盖，失败立即停止。不要改 Vector 配置库、杀系统进程或重启设备。

1. 用户手动打开 Innocent，在「增强」启用「折叠变化不刷新」，退出再进入确认状态仍开。
2. B 站主进程下次启动时核对 Vector 的模块加载日志。9.18.0 可在 Innocent 完整档日志中对照 `registered: window-pane-layout-only` 和 `layout-only-hit: window-pane`。
3. 记录推荐区首屏几条视频的标题或 BV，折叠、展开分别核对视频和列数，再验证下拉刷新与继续分页。
4. 复测分享净化与小程序转链接。
