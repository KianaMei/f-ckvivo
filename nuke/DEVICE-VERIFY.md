# nuke 上机验证 runbook

> 目的：把 `nuke/src` 编出来的 APK 装上手机，逐项拿到"有效/无效"的证据。
> **每一步都要用户明确同意**（见根 `AGENTS.md` §1 与 §1.0 事故条款）；本文件只写步骤，不代表已获授权。

## 0. 前置（缺一不可）

- 屏幕已解锁、用户在场；手机不重启、不强停系统应用、不碰 `system_server`/`PackageInstaller` 之外的东西。
- `adb devices` 显示 `device`（transport 会变，别写死路径）。
- 本地已构建：`nuke/src/apps/module/build/outputs/apk/debug/module-debug.apk`
  （2026-11-18 实测 25.7 MB，含 `libnuke_zygisk.so`、`libnuke_protocol_core.so`、`libquickjs.so`、`libdexkit.so`）。
- 我们的签名是 `1990df…`（`signing/debug.keystore`），与官方 `72b63d…` 不同 → **无法 `install -r` 覆盖**。

## 1. 装（设备变更，需授权）

```sh
ADB=<platform-tools>/adb.exe
"$ADB" install -r -d nuke/src/apps/module/build/outputs/apk/debug/module-debug.apk
```

若报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不符），只有卸载官方包这一条路：

```sh
"$ADB" uninstall io.github.nuke.oss        # 会丢模块自己的设置与统计库
"$ADB" install nuke/src/apps/module/build/outputs/apk/debug/module-debug.apk
```

> 卸载会触发 `PACKAGE_*` 广播；本机 vivo 备份 `TransportManager.onPackageChanged` 有已知 NPE
> （`root/INCIDENT-2026-11-15-packageinstaller.md`）。屏幕必须解锁，出现任何系统弹窗先停下问用户。

## 2. 启用（设备变更，需授权）

Vector 里启用 `io.github.nuke.oss`，scope 勾 `com.ss.android.ugc.aweme`（只勾抖音主进程；不要加
`io.github.nuke.oss.fixture`）。然后：

```sh
"$ADB" shell am force-stop com.ss.android.ugc.aweme   # 只强停抖音（用户应用），不碰系统进程
```

打开抖音 → 我的 → 设置，应多出「NukeApp-OSS」一行。

## 3. 证据来源

- Vector daemon 日志（**首选**，模块日志都落这里）：
  ```sh
  "$ADB" shell su -c 'ls -t /data/adb/lspd/log/verbose_*.log | head -1'
  "$ADB" shell su -c 'grep -E "feature=|host_identity|features_held" /data/adb/lspd/log/<最新>.log | tail -50'
  ```
- 模块设置页 → 「调试」页：失败记录、Dex 未定位、功能状态。
- 文件证据：`/sdcard/Movies/douyin/<folder>`、`/sdcard/Pictures/douyin/image`、
  `/sdcard/Music/douyin/audio`、`/sdcard/Downloads/Nuke`；模块私有目录需 `su -c ls`。

## 4. 逐项检查（每项记"看到什么"）

| 项 | 操作 | 期望证据 |
|---|---|---|
| 加载 | 打开抖音 | `verbose_*.log` 有模块加载；设置页功能列表可见；无 `features_held dex_unlocated` 意外项 |
| D-1 下载来源 | 打开「下载原视频」，`source=play` | 下载成功；文件与播放源一致（无水印） |
| D-2/D-3 | 长按视频 → 保存 | 文件名按模板；落在 `Movies/douyin/<folder>`；日志 `feature=DownloadVideoWithName saved` |
| D-4a 队列 | 连点保存多个 | 设置页任务对话框出现排队/进度；取消生效 |
| D-4b 图片/SAF | 长按图片帖保存；对话框选目录 | 逐张 `_01/_02`；改目录后文件写进 SAF 目录 |
| D-4c 分享面板 | 分享面板点「按模板下载」 | 同 D-2 的结果 |
| D-5 MP3 | 保存一个视频（开着「把视频原声保存为 MP3」） | `Music/douyin/audio/audio-*.mp3` 能播；日志 `feature=SaveVideoAudio saved duration=` |
| D-6 实况 | 保存实况照片 | `Pictures/douyin/image` 一个 `.jpg`；PC 上 `ffprobe` 能读到内嵌 MP4；日志 `saved` |
| D-7 聊天媒体 | 收到的图片/视频消息 | `su -c ls /data/data/com.ss.android.ugc.aweme/no_backup/nuke/feature-files/CacheChatMedia/<会话>/`；日志 `feature=CacheChatMedia saved count=` |
| D-8 WebDAV | 填地址/账号后保存任意媒体 | 服务器上出现文件；日志 `feature=WebDavUpload uploaded status=2xx` |
| A14 哈希 | 填 `KnownHostApks.DIGESTS` 后重启抖音 | 日志 `host_identity verdict=known`（不填应是 `unconfigured`） |
| A15 配置 | 调试页导入配置 | 先出预览（新增/修改/未变/替换删除），选合并或替换 |
| S-4 报告 | 分析对话框「导出图片」 | `Downloads/Nuke/analytics-*.png` 可打开；成功回显路径 |
| A10a 双击分区 | 设为「上一条/下一条」并双击该区域 | 翻页；日志 `feature=FeedGestureZones next/previous moved=true` |

## 5. 失败时

- 单项无效：先看该功能在调试页的状态（FAILED / held / 未定位的 Dex 查询名），把日志和失败记录导出
  （调试页「导出全部异常」），再决定是修锚点还是改实现。
- 加载失败/崩溃：立刻在 Vector 里禁用模块（不要重启手机），保留 `verbose_*.log` 与 `logcat`。
- 回滚：Vector 禁用 → `adb uninstall io.github.nuke.oss` → 装回官方 0.1.2 APK（`nuke/apk/nuke-base.apk`，
  若本地没有就按 `nuke/README.md` 的复现步骤从设备重新拉）。

## 6. 已知边界

- 本仓库不产出发布包；debug 包与官方同 versionCode 家族，装过一次后要更新就 `install -r` 自己签名的包。
- 官方包被卸载后，NukeApp 自己的设置/统计会丢；重新安装需要重新在 Vector 里启用与勾 scope。
- 所有功能都**未上机验证**；`PLAN-nuke-upgrade.md` 的每一条都标了这一点。
