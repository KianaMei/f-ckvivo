# xhook —— X/Twitter 模块审计与自研

本目录做三件事：

1. **审计 Re:X（闭源）**，确认能不能放心用；
2. **拆解其它去广告 / 视频保存模块**，提取可复用的 hook 点；
3. **自研 XHook**，把“视频保存 + 推广过滤 + 机器人过滤”合成一个 libxposed 模块。

## 目录

```
xhook/
├── apks/                   下载的 APK（ReX、TwitterHideAds、x-video-save…）
├── unpack/
│   ├── rex-src/            ReX JADX 反编译（3,286 个 Java 文件）
│   ├── rex-smali/          ReX baksmali 反汇编（用于定位字符串/安装链）
│   ├── rex-res/            ReX 资源（manifest/strings/FileProvider 路径）
│   └── rex-dex/            classes.dex
├── src-upstream/           9 个模块的源码 zip + 解压目录
├── tools/                  下载 / 字符串扫描 / Java 语法检查脚本
├── notes/
│   ├── AUDIT-REX.md        ReX 1.8.2 静态安全审计（结论、证据、用法约束）
│   ├── MODULE-MATRIX.md    模块对比 + 去广告/机器人/视频 hook 点拆解
│   ├── PORTING.md          Re:X → XHook 移植台账（按功能组，含已实现/未实现）
│   ├── REX-FEATURES.md     Re:X 1.8.2 功能面/设置项清单
│   ├── X-BACKGROUND-KILL.md 回首页=进程被 single-cleaner 杀的系统层证据
│   ├── X-12.42-ANCHORS.md  **12.42 结构实测**：旧 entryId 锚点全失效、新锚点定位
│   ├── logcat-2026-11-15-xhook.txt  真机加载/命中日志（含 discovery=0 证据）
│   ├── now-x2.png          2026-11-15 22:14 真机截图（广告仍在）
│   └── s1–s9.xml / x3–x9.xml / window.xml   X UI dump（定位设置项/菜单用）
├── ours/                   XHook 源码（已编译通过）
│   └── app/build/outputs/apk/debug/app-debug.apk
├── out/                    编译产物副本（XHook-0.2.9-debug.apk）
├── work/                   本机反编译工作区（12.42 的 dex/baksmali，gitignore）
└── apks/                   下载的 APK + 从设备拉取的 base.apk（gitignore）
```

## 关键结论

### ReX 1.8.2 能不能用？

**能。** 静态审计未发现恶意行为：

- 权限只有 `INTERNET` + `REQUEST_INSTALL_PACKAGES`；
- 无遥测、无陌生域名、无动态加载、无隐私 API、无自定义 native；
- 更新链完整校验：大小 / SHA-256 / 包名 / 版本 / minSdk / **签名证书**，最后走系统安装器；
- 只有两条 root 命令：`pkill systemui`、`am force-stop/start com.twitter.android`，都是设置页按钮；
- 导出组件都有 UID/包名校验；HistoryProvider 处于 `enabled=false`。

风险在“闭源 + 自动更新 + SystemUI 作用域”，不在当前版本行为。详见
[notes/AUDIT-REX.md](notes/AUDIT-REX.md)。

### 设备（X 12.42.0-prod.01）兼容性

| 模块 | 12.42 可用性 | 说明 |
|---|---|---|
| ReX 1.8.2 | ✅ | release notes 已适配 X 12.43 |
| x-video-save 0.4 | ✅ | 明确适配 12.41/12.42 |
| TwitterHideAds 3.1.0 | ⚠️ 大概率 | 12.37 实测，DexKit 自适应 |
| X-ADBlock 0.1.5 | ❌ 需移植 | 硬编码 12.22 类名 |
| TwiFucker 0.1.2 | ❌ 需移植 | 目标 12.23 |
| x-video-catcher 1.58 | ❌ 需移植 | 目标 12.24–12.27 |
| Piko 3.11.1 | n/a | Morphe 补丁，不是 hook 模块 |

## XHook（自研）

- 视频下载：抓 `media3.datasource` 的 Uri → HLS 选档 → init+分片 → MediaMuxer → 相册；
- 去推广：0.2.9 用 DexKit 找“含 promoted/RTB 字符串且列表进出”的方法，按 entryId 前缀过滤；
  **在 X 12.42 已失效**（这些字符串在 dex 里已不存在，见下方“实测状态”）；
- 机器人：同批列表 hook + 关键词归一化匹配（默认只在详情页）；
- 刷新守卫：Home 重选、回前台生命周期刷新（对应 Re:X 的 Prevent automatic refresh）；
- **fail-open**：找不到目标/类型不匹配/异常，一律原样放行，不崩 X；
- 模块 App 零权限，无 root，无上报。

详见 [ours/README.md](ours/README.md)。

## 实测状态（2026-11-15 22:14，真机 X 12.42.0-prod.01）

模块已装（`dev.xhook` 0.2.9 / versionCode 11）、Vector 已启用（`enabled=1`，mid 298）。
启动日志（完整版见 [notes/logcat-2026-11-15-xhook.txt](notes/logcat-2026-11-15-xhook.txt)）：

| 功能 | 日志/现场 | 结论 |
|---|---|---|
| 模块加载 | `module loaded, process=com.twitter.android, api=102, framework=Vector 2.2` | ✅ 生效 |
| 视频 URL 抓取 | `MediaCapture installed, hooked=1` + 连续 `media captured id=… master=true` | ✅ 生效 |
| 下载悬浮按钮 | `PlayerButtons installed` | ✅ 已装（下载流程待复测） |
| 去广告 | 0.3.1：`RenderAdFilter installed … hooks=5` + 4 次 `skipped promoted item` | ✅ **生效**（0.2.9 完全无效，0.3.0 未命中） |
| 机器过滤 | `spamFilter=false` / `keywords=0` | ⏸ 未开启 |
| Home 重选守卫 | `Home reselect hook: com.x.home.tabbed.v#h deopt=true` | ✅ 已装（点按行为待复测） |
| 回前台刷新守卫 | `RefreshGuard installed: home=1 refresh=1` | ✅ 已装（回前台行为待复测） |
| 模块总耗时 | `feature install finished in 650ms` | ✅ |

**去广告失效原因（已定位）**：`Discovery.MARKERS` 依赖的 `promoted-tweet-` /
`promotedTweet-` / `rtb-image-ad-` / `superhero-` 在 X 12.42 的 15 个 dex（132.6 MB）
里**出现 0 次**，所以 DexKit 一个候选也找不到，过滤器不装 hook。12.42 改成了模型字段标记：
时间线帖模型带 `promotedMetadata` 对象（类 `com.x.models.ff`，`toString` 以
`TimelinePromotedMetadata(` 开头），UI 模型 `com.x.urt.items.post.g6` 直接持有该字段。

真机截图 [notes/now-x2.png](notes/now-x2.png)：@SUBWAY 的 `Ad` 帖仍在时间线最上方。
新锚点与修复方案见 [notes/X-12.42-ANCHORS.md](notes/X-12.42-ANCHORS.md)。

## 已知问题 / 下一步

1. **去广告（P0）✅ 0.3.1 已上机验证**：`RenderAdFilter` 用 DexKit 找 promoted-metadata
   类（`com.x.models.ff`，`toString` 前缀 `TimelinePromotedMetadata(`）与持有该字段的条目
   模型（`com.x.urt.items.post.g6`），挂三条渲染路径：条目接口默认方法 `l6#a`、
   Composer lambda `com.x.android.main.hl1#invoke`、帖子内部边界 `h#a`/`a2#a`/`a2#c`。
   实测命中 `hl1#invoke`（4 次 skip，无崩溃、无空行）。截图
   `notes/after-0.3.1-1.png` / `-2.png`，日志 `notes/logcat-0.3.1-verified.txt`。
   数据层方案（mapper + 空 presenter，`com.x.urt.b#a` / `z#u` …）留给后续。
2. 视频下载：0.2.9 已加“单码率媒体播放列表”fallback，未复测。
3. Home 重选 / 回前台刷新守卫：已命中但未做交互复测。
4. 回首页的根因是 `single-cleaner` 杀进程（hook 无解），见
   [notes/X-BACKGROUND-KILL.md](notes/X-BACKGROUND-KILL.md)。

## 复现

```bash
# 1. 重新拉取候选模块 + 源码
python -I tools/fetch_upstream.py     # 需要 GH_TOKEN

# 2. ReX 反编译（注意必须直接跑 jadx.cli.JadxCLI，绕开 GUI 更新检查）
JADX_CACHE_DIR=... java -cp jadx-1.5.1-all.jar jadx.cli.JadxCLI \
  -d unpack/rex-src --no-res -j 8 apks/rex/Re.X-1.8.2.apk

# 3. baksmali（定位 const-string 所在的类）
java -jar baksmali-3.0.10-fat.jar d unpack/rex-dex/classes.dex -o unpack/rex-smali

# 4. 编译 XHook
cd ours && gradle :app:assembleDebug

# 5. 目标 app 结构分析（只读）
#    adb pull /data/app/…/com.twitter.android-…/base.apk apks/x-12.42/base.apk
#    unzip base.apk 'classes*.dex' -d work/x-12.42；dexapi.py 枚举类/字段/方法
python work/x-12.42/dexapi.py mfield promoted
```

## 注意

- 所有 APK/源码来自公开 GitHub Release；`apks/`、`unpack/`、`src-upstream/`、`out/`、`work/` 均不入库。
- 本目录不含设备安装/启用步骤；装 APK、勾 Vector 作用域、重启 X 都需要用户明确同意。
- 上游许可：TwitterHideAds/Piko/X-region 为 GPL-3.0，X-ADBlock/birdify 为 MIT，
  x-video-save / x-video-catcher / TwiFucker 未声明许可。XHook 为独立实现，仅借鉴思路。
