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
