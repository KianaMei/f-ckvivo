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
│   └── rex-strings.txt     ReX DEX 里提取的 URL/敏感字符串
├── ours/                   XHook 源码（已编译通过）
│   └── app/build/outputs/apk/debug/app-debug.apk
└── out/                    编译产物副本（XHook-0.1.0-debug.apk）
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
- 去推广：DexKit 找“含 promoted/RTB 字符串且列表进出”的方法，按 entryId 前缀过滤；
- 机器人：同批列表 hook + 关键词归一化匹配（默认只在详情页）；
- 刷新守卫：Home 重选、回前台生命周期刷新（对应 Re:X 的 Prevent automatic refresh）；
- **fail-open**：找不到目标/类型不匹配/异常，一律原样放行，不崩 X；
- 模块 App 零权限，无 root，无上报。

详见 [ours/README.md](ours/README.md)。

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
```

## 注意

- 所有 APK/源码来自公开 GitHub Release；`apks/`、`unpack/`、`src-upstream/`、`out/` 均不入库。
- 本目录不含设备安装/启用步骤；装 APK、勾 Vector 作用域、重启 X 都需要用户明确同意。
- 上游许可：TwitterHideAds/Piko/X-region 为 GPL-3.0，X-ADBlock/birdify 为 MIT，
  x-video-save / x-video-catcher / TwiFucker 未声明许可。XHook 为独立实现，仅借鉴思路。
