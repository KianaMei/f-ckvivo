# 构建签名材料（本仓库统一登记）

> 2026-11-17 按用户要求：**所有构建签名材料全部入库**，目的是任何一台机器 clone 后都能
> 复现**同一个签名身份**，模块/补丁才能继续 `adb install -r` 覆盖安装。
> 这些文件是自用签名私钥，不是泄漏；不要当垃圾清理。
> 仓库一旦转公开，等同公开私钥——转公开前须先改这批 key（会失去 install -r 连续性）。

>
> **身份以证书指纹（SHA-256）为准，不以目录路径为准。** 本仓库在两台电脑上用，绝对路径
> 必然不同；下面每把 key 都登记指纹、别名、密码，工程内一律用相对路径或 `SIGNING_*`
> 环境变量引用。判断一个 APK 该用哪把 key，只看它在设备上的签名指纹。

## 1. 清单（按证书指纹对账）

| 用途 | keystore（本机拍平后路径） | alias | storepass / keypass | 证书 SHA-256 |
|---|---|---|---|---|
| drag-all 拖拽模块 | `drag-all/.signing/drag-all.keystore` | `dragall` | `dragall-debug` | `9B:5D:…:43:A0` |
| fq-fold-pad 番茄双列 | `fq-fold-pad/.signing/fq-fold-pad.keystore` | `fqfoldpad` | `fqfoldpad` | `21:92:…:33:14` |
| origin-tweaks（也签 `dev.ganvivo.probe`） | `origin-tweaks/.signing/origintweaks.keystore` | `origintweaks` | `origintweaks` | `EA:8D:…:5B:99` |
| 酷安净化合并 / 类导出 | `coolapk/module-inspect/.signing/coolapk-inspect.keystore` | `coolapkinspect` | `coolapkinspect` | `BC:97:…:D5:DA` |
| 静盒×小红书重打包 | `xhs/.signing/quietbox-port.keystore` | `quietboxport` | `quietboxport` | `DA:E3:…:7F:FF` |
| 旧 fuckdrag（模块已卸载，留档） | `signing/fuckdrag.keystore` | `fuckdrag` | `fuckdrag` | `48:60:…:9E:E2` |
| debug 身份 A：xhook（旧机在用） | `signing/debug.keystore` | `androiddebugkey` | `android` | `19:90:…:12:FB` |
| debug 身份 B：DouAi / LiquidTab（当前机在用） | `signing/debug-115ed9.keystore` | `androiddebugkey` | `android` | `11:5E:…:8F:A3` |

完整指纹（`keytool -list` 原样，已逐一与设备在装 APK 的 `apksigner --print-certs` 对账）：

```
drag-all      9B:5D:C7:ED:7F:E8:D6:30:60:C5:89:6D:F9:1A:54:C0:81:12:1E:FA:03:8D:D5:46:35:7A:13:A5:47:08:43:A0
fq-fold-pad   21:92:77:82:6E:79:4E:DE:55:73:50:9A:6D:7C:50:19:C2:DB:14:17:A5:9D:D9:25:30:D1:D1:26:9A:FB:33:14
origin-tweaks EA:8D:BF:B4:B9:BA:EA:87:FF:DD:BA:60:34:58:BA:90:9E:1F:9F:E1:BC:30:02:67:DE:8A:CD:AA:0D:E4:5B:99
coolapk       BC:97:CF:AA:35:93:22:EE:9C:9D:F8:19:5A:0E:07:7C:BD:B5:BF:3E:0A:7D:C2:9A:97:D3:42:9D:E6:9F:D5:DA
quietbox      DA:E3:0A:0A:98:B6:ED:9E:91:5B:71:2E:6C:D1:ED:4C:EC:0E:2F:0D:D0:6C:57:E0:FC:0E:6B:49:FF:E5:F9:7F
fuckdrag      48:60:9F:0B:21:02:8C:E6:29:E2:69:8A:03:84:37:B7:FC:CF:54:E6:8B:C6:27:A8:B3:B4:29:50:BA:F6:9E:E2
debug A 1990  19:90:DF:77:2C:ED:9C:2F:67:A6:4F:7E:27:DE:85:5A:16:CE:1F:8D:30:C8:8F:99:E8:EC:53:4A:9C:72:12:FB
debug B 115e  11:5E:D9:8E:05:24:2E:FD:BF:2D:3F:A2:F0:E2:D6:C2:54:A3:79:06:39:64:D5:D1:F0:87:89:86:48:40:8F:A3
```

## 2. 设备在装模块 → 用哪把 key（实测指纹映射）

| 设备包名 | 设备证书（前缀） | 对应 keystore |
|---|---|---|
| `dev.ganvivo.dragall` | `9b5dc7…` | drag-all |
| `local.codex.fanqiefold` | `219277…` | fq-fold-pad |
| `dev.ganvivo.probe` | `ea8dbf…` | origin-tweaks |
| `io.github.yigenhuobah.quietbox` | `dae30a…` | quietbox-port |
| `io.github.yylsping.coolapkpurifier`（合并版） | `bc97cf…` | coolapk-inspect |
| `dev.xhook` | `1990df…` | debug A `signing/debug.keystore` |
| `io.github.yudigaga.douai.adapt412` | `115ed9…` | debug B `signing/debug-115ed9.keystore` |
| `io.github.offlineglass`（LiquidTab fork） | `115ed9…` | debug B `signing/debug-115ed9.keystore` |

## 3. 两把 debug key 的来历（不要合并、不要互相覆盖）

- **debug A `signing/debug.keystore`（`1990df…`）**：另一台/早期机器在用的 Android debug key，
  `dev.xhook` 已装包是这个证书。xhook 走 Gradle 默认 debug 签名；新机器要复现，就把它复制为
  `~/.android/debug.keystore`（或用 `ANDROID_DEBUG_KEYSTORE` 指向它）。
- **debug B `signing/debug-115ed9.keystore`（`115ed9…`）**：当前这台机器 2026-11-13 重新生成的
  `~/.android/debug.keystore` 原样副本，DouAi 与 LiquidTab 已装包是这个证书。
- 两把都是真实在用身份，删任一把都会让对应已装包失去 `install -r` 连续性。改名带指纹后缀就是
  为了防止"debug.keystore 只有一个"的误判。

## 4. nuke 是例外（不是我们的私钥，无需找回）

设备上的 `io.github.nuke.oss` 0.1.2 证书是 `72:B6:3D:…:B5:B3:2A`，那是**上游
`dartcv/NukeApp-OSS` 官方 release 签名证书，私钥在作者的 CI secrets 里，我们从来没有**。
该摘要被硬编码进 `libnuke_protocol_core.so` 做防重签校验（见 `nuke/ANALYSIS.md` §8）。
我们从 `nuke/src` 源码自建时用 debug A 自签；`nuke/src/local.properties` 已入库，构建会把
debug A 的证书摘要注入 native，所以**自建包的 signer 校验是 configured 且自洽的**（同一把
key 签名 → 通过）。若把 `local.properties` 里的 `signing.*` 四行去掉，则退回
`Unconfigured`（也不拦）。两种情形都可用；但自签包**无法 `install -r` 覆盖官方包**，
只能卸载重装（属设备变更，须先授权）。

## 5. 工程怎么引用（跨机器）

- `fq-fold-pad` / `origin-tweaks` / `xhs` / `coolapk/module-merged` 的 `build.sh` 默认读各自
  目录下 `.signing/<name>.keystore`（本拍平结构已对齐），可用 `SIGNING_DIR` /
  `SIGNING_KEYSTORE` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` 覆盖。
- `nuke/src/local.properties` 已入库，`signing.storeFile=../../signing/debug.keystore` 相对路径，
  clone 即可用，不含机器绝对路径。
- `liquidtab-mine` 的 release 用 `ANDROID_DEBUG_KEYSTORE` 环境变量或 `~/.android/debug.keystore`
  （即 debug B），见 `liquidtab-mine/app/build.gradle.kts` 的 `installOver`。
- `drag-all` 在 `build.gradle.kts` 内置 `.signing/drag-all.keystore` 与口令。
- `xhook/ours/app/build.gradle.kts` 新增 `pinned` 签名配置，debug/release 都钉死到
  `../../signing/debug.keystore`（debug A，`1990df…`，与手机 `dev.xhook` 同证书），
  不再吃机器默认 debug key。
- `douyin/reference/DouAi/app/build.gradle.kts` 新增 `pinned` 签名配置，debug（装机的
  `adapt412` 变体）钉死到 `../../../signing/debug-115ed9.keystore`（debug B，`115ed9…`，
  与手机 DouAi 同证书）；原 `release` 配置（走 `douaiRelease*` 属性）保持不变。
  已用 `:app:signingReport` 验证 debug 变体证书为 `11:5E:D9…:8F:A3`。

## 6. 不收录的材料

- `_tmp_dingtalk/patch/patch.keystore`：nuke Patcher 运行时随机生成的一次性 key，32 字节随机
  口令只存在同目录 `signing.properties`（已不在，口令不可恢复）。重新 patch 会生成新 key。
- `root/.upstream/`、`originos/` 内上游克隆自带的示例 keystore：非本仓库产物。
- 仓库一旦转公开，等同公开这些私钥——转公开前须先评估换 key（会失去 install -r 连续性）。
