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
