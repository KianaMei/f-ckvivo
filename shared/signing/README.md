# 构建签名材料（统一存放于 `shared/signing/`）

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

> 所有构建签名私钥集中存放在本目录并入库，确保跨机器 clone 后复现**同一个签名身份**，
> 设备已装模块可直接 `adb install -r` 覆盖安装，避免卸载重装触发 `PACKAGE_*` 广播红线。
> 仓库若将来转公开，须先独立评估换签（会失去 `install -r` 连续性）。
>
> **身份以证书 SHA-256 指纹为准，不以机器绝对路径为准。** 各工程默认通过相对路径指向本目录，并支持 `SIGNING_DIR` / `SIGNING_KEYSTORE` / `ANDROID_DEBUG_KEYSTORE` 环境变量覆盖。

## 1. 签名清单（已与设备在装 APK 对账）

| 用途 / 设备包名 | Keystore（本目录） | alias | storepass / keypass | 证书 SHA-256（前缀…后缀） |
|---|---|---|---|---|
| `dev.ganvivo.dmsilencer`（dm-silencer） | `dmsilencer.keystore` | `dmsilencer` | `dmsilencer` | `82:DF:…:62:7D` |
| `dev.ganvivo.dragall`（drag-all） | `drag-all.keystore` | `dragall` | `dragall-debug` | `9B:5D:…:43:A0` |
| `local.codex.fanqiefold`（fq-fold-pad） | `fq-fold-pad.keystore` | `fqfoldpad` | `fqfoldpad` | `21:92:…:33:14` |
| `dev.ganvivo.probe`（origin-tweaks） | `origintweaks.keystore` | `origintweaks` | `origintweaks` | `EA:8D:…:5B:99` |
| `dev.ganvivo.freeformresize`（vivo-freeform-resize） | `vivofreeformresize.keystore` | `vivofreeformresize` | `vivofreeformresize` | `22:BF:…:64:15` |
| `local.codex.vivoportal`（vivoportal） | `vivoportal.keystore` | `vivoportal` | `vivoportal` | `B9:27:…:92:E0` |
| `local.codex.vivoime`（vivo-ime） | `vivoime.keystore` | `vivoime` | `vivoime` | `7B:AB:…:D7:E2` |
| `dev.ganvivo.twspopup`（vivo-tws-popup） | `vivotwspopup.keystore` | `vivotwspopup` | `vivotwspopup` | `17:3A:…:4F:57` |
| `dev.ganvivo.powerinsight`（vivo-power-insight） | `vivopowerinsight.keystore` | `vivopowerinsight` | `vivopowerinsight` | `3F:88:…:70:F4` |
| `dev.ganvivo.vivoworkbench`（vivo-workbench） | `vivoworkbench.keystore` | `vivoworkbench` | `vivoworkbench` | `11:C8:…:17:31` |
| `io.github.yylsping.coolapkpurifier`（酷安合并/导出） | `coolapk-inspect.keystore` | `coolapkinspect` | `coolapkinspect` | `BC:97:…:D5:DA` |
| `io.github.yigenhuobah.quietbox`（静盒×小红书） | `quietbox-port.keystore` | `quietboxport` | `quietboxport` | `DA:E3:…:F9:7F` |
| `io.github.comewhy.ding`（Ding × Vector 合体版） | `ding-vector.keystore` | `dingvector` | `dingvector` | `50:B9:…:70:D1` |
| `com.Bilibili_Innocent_Lab.xposedmodule`（Innocent 补丁版） | `innocent-sharefix.keystore` | `innocent` | `innocent` | `5F:E5:…:CC:19` |
| `com.shatyuka.zhiliao`（知了×知乎 11.14.0 补丁版） | `zhiliao-adapt.keystore` | `zhiliao` | `zhiliao` | `B7:DC:…:14:D0` |
| `dev.xhook` & 自建 Nuke（**debug A**） | `debug.keystore` | `androiddebugkey` | `android` | `19:90:…:12:FB` |
| `io.github.offlineglass` & `douai.adapt412`（**debug B**） | `debug-115ed9.keystore` | `androiddebugkey` | `android` | `11:5E:…:8F:A3` |
| 旧 fuckdrag（已卸载留档） | `fuckdrag.keystore` | `fuckdrag` | `fuckdrag` | `48:60:…:9E:E2` |

完整证书 SHA-256：

```text
dmsilencer         82:DF:CE:9E:A4:DC:4A:37:00:9D:F9:8A:7F:A5:A4:DB:D9:16:C6:60:44:5D:F8:BF:93:DD:4F:46:4C:66:62:7D
drag-all           9B:5D:C7:ED:7F:E8:D6:30:60:C5:89:6D:F9:1A:54:C0:81:12:1E:FA:03:8D:D5:46:35:7A:13:A5:47:08:43:A0
fq-fold-pad        21:92:77:82:6E:79:4E:DE:55:73:50:9A:6D:7C:50:19:C2:DB:14:17:A5:9D:D9:25:30:D1:D1:26:9A:FB:33:14
origin-tweaks      EA:8D:BF:B4:B9:BA:EA:87:FF:DD:BA:60:34:58:BA:90:9E:1F:9F:E1:BC:30:02:67:DE:8A:CD:AA:0D:E4:5B:99
vivofreeformresize 22:BF:81:3E:59:49:7B:48:57:E9:4C:44:34:8F:38:DD:93:13:BD:02:96:01:0E:0C:BB:D9:31:01:2E:01:64:15
vivoportal         B9:27:6B:1F:F4:2D:72:C2:1F:2E:46:78:20:D9:38:89:34:0F:75:F1:3B:7E:7E:A9:9D:E3:E8:39:67:8C:92:E0
vivoime            7B:AB:02:AA:59:7C:9D:89:5D:D5:1B:21:71:A5:06:BE:2C:40:EF:66:08:FD:10:74:3F:B5:1A:23:DB:34:D7:E2
vivotwspopup       17:3A:21:A3:4A:86:2F:22:13:72:AA:68:C6:8C:A8:36:B8:1F:B6:28:50:54:0D:C8:40:3E:AE:60:55:24:4F:57
vivopowerinsight   3F:88:13:F7:ED:3A:35:34:AE:CB:32:21:D2:F0:10:BB:EE:DA:66:A1:CD:02:8C:FB:54:61:8D:88:CE:E9:70:F4
vivoworkbench      11:C8:C6:9E:C5:0A:BB:4C:AF:DB:CF:80:DA:B6:55:61:8A:B8:3F:D8:40:F4:DF:3A:8F:45:66:4A:C3:5F:17:31
coolapk           BC:97:CF:AA:35:93:22:EE:9C:9D:F8:19:5A:0E:07:7C:BD:B5:BF:3E:0A:7D:C2:9A:97:D3:42:9D:E6:9F:D5:DA
quietbox          DA:E3:0A:0A:98:B6:ED:9E:91:5B:71:2E:6C:D1:ED:4C:EC:0E:2F:0D:D0:6C:57:E0:FC:0E:6B:49:FF:E5:F9:7F
ding-vector       50:B9:4C:C7:92:36:F7:0C:6A:79:69:E7:A6:9A:9C:0E:FA:55:B7:48:62:CE:EA:AC:43:1F:82:06:C1:3C:70:D1
innocent-sharefix 5F:E5:2E:1F:A1:3B:0C:61:9E:ED:57:AF:63:27:5D:41:12:A0:FE:B9:FC:0B:1E:F6:57:CE:72:2B:7D:C4:CC:19
zhiliao-adapt     B7:DC:D7:F9:30:12:D4:7D:D3:87:93:A6:03:2F:63:D8:00:6C:A0:17:E9:E8:99:50:0D:5C:EC:80:69:F3:14:D0
debug A 1990      19:90:DF:77:2C:ED:9C:2F:67:A6:4F:7E:27:DE:85:5A:16:CE:1F:8D:30:C8:8F:99:E8:EC:53:4A:9C:72:12:FB
debug B 115e      11:5E:D9:8E:05:24:2E:FD:BF:2D:3F:A2:F0:E2:D6:C2:54:A3:79:06:39:64:D5:D1:F0:87:89:86:48:40:8F:A3
fuckdrag          48:60:9F:0B:21:02:8C:E6:29:E2:69:8A:03:84:37:B7:FC:CF:54:E6:8B:C6:27:A8:B3:B4:29:50:BA:F6:9E:E2
```

## 2. 两把 debug key 与 Nuke 例外

- **debug A (`debug.keystore`, `1990df…`)**：`dev.xhook` 与本地 `upstream-derived/nuke` 自建包使用。
- **debug B (`debug-115ed9.keystore`, `115ed9…`)**：本地 `upstream-derived/liquidtab-mine` 与 `forks/DouAi` 使用。两把均为在用身份，**不可合并或互相覆盖**。
- **Nuke 官方包例外**：上游 `dartcv/NukeApp-OSS` 官方发布包证书为 `72:B6:3D:…:B5:B3:2A`（私钥在作者 CI），自建包走 debug A，需卸载官方包后重装（属设备变更，先授权）。

## 3. 各工程引用位置

- **全部 `build.sh` 模块**（`original-modules/{dm-silencer,fq-fold-pad,origin-tweaks,vivo-freeform-resize,vivoportal,vivo-ime,vivo-tws-popup,vivo-workbench}`、`upstream-derived/{xhs-quietbox,bili-innocent,coolapk-purifier,ding-vector,zhihu-zhiliao}`）：通过 `shared/build-lib.sh` 自动解析 `$SIGNING_DIR`（默认本目录）。
- `original-modules/drag-all/build.gradle.kts`：`../../shared/signing/drag-all.keystore`
- `original-modules/xhook/ours/app/build.gradle.kts`：`../../../shared/signing/debug.keystore`
- `upstream-derived/nuke/local.properties`：`../../shared/signing/debug.keystore`
- `upstream-derived/liquidtab-mine/app/build.gradle.kts`：`../../shared/signing/debug-115ed9.keystore`
- `forks/DouAi/app/build.gradle.kts`：`../../shared/signing/debug-115ed9.keystore`
