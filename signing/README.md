# 构建签名材料（本仓库统一登记）

> 2026-11-17 按用户要求：**所有构建签名材料全部入库**，目的是任何一台机器 clone 后都能
> 复现**同一个签名身份**，模块/补丁才能继续 `adb install -r` 覆盖安装。
> 这些文件是自用签名私钥，不是泄漏；不要当垃圾清理。
> 仓库一旦转公开，等同公开私钥——转公开前须先改这批 key（会失去 install -r 连续性）。

## 1. 清单

| 用途 | keystore | 别名 (alias) | storepass | keypass | 证书 SHA-256 |
|---|---|---|---|---|---|
| fq-fold-pad | `modules/fq-fold-pad/.signing/fq-fold-pad.keystore` | `fqfoldpad` | `fqfoldpad` | 同 storepass | `21:92:...:33:14` |
| fuckdrag | `modules/fuckdrag/.signing/fuckdrag.keystore` | `fuckdrag` | `fuckdrag` | 同 storepass | `48:60:...:9E:E2` |
| origin-tweaks | `modules/origin-tweaks/.signing/origintweaks.keystore` | `origintweaks` | `origintweaks` | 同 storepass | `EA:8D:...:5B:99` |
| drag-all | `modules/drag-all/.signing/drag-all.keystore` | `dragall` | `dragall-debug` | `dragall-debug` | `9B:5D:...:43:A0` |
| coolapk 净化/类导出 | `coolapk/module-inspect/.signing/coolapk-inspect.keystore` | `coolapkinspect` | `coolapkinspect` | 同 storepass | `BC:97:...:D5:DA` |
| 静盒×小红书 | `xhs/.signing/quietbox-port.keystore` | `quietboxport` | `quietboxport` | 同 storepass | `DA:E3:...:7F:FF` |
| nuke / xhook（原 debug key） | `signing/debug.keystore` | `androiddebugkey` | `android` | `android` | `19:90:...:12:FB` |

完整指纹（keytool -list 原样）：

```
fq-fold-pad : 21:92:77:82:6E:79:4E:DE:55:73:50:9A:6D:7C:50:19:C2:DB:14:17:A5:9D:D9:25:30:D1:D1:26:9A:FB:33:14
fuckdrag    : 48:60:9F:0B:21:02:8C:E6:29:E2:69:8A:03:84:37:B7:FC:CF:54:E6:8B:C6:27:A8:B3:B4:29:50:BA:F6:9E:E2
origin-tweaks: EA:8D:BF:B4:B9:BA:EA:87:FF:DD:BA:60:34:58:BA:90:9E:1F:9F:E1:BC:30:02:67:DE:8A:CD:AA:0D:E4:5B:99
drag-all    : 9B:5D:C7:ED:7F:E8:D6:30:60:C5:89:6D:F9:1A:54:C0:81:12:1E:FA:03:8D:D5:46:35:7A:13:A5:47:08:43:A0
coolapk     : BC:97:CF:AA:35:93:22:EE:9C:9D:F8:19:5A:0E:07:7C:BD:B5:BF:3E:0A:7D:C2:9A:97:D3:42:9D:E6:9F:D5:DA
xhs/静盒    : DA:E3:0A:0A:98:B6:ED:9E:91:5B:71:2E:6C:D1:ED:4C:EC:0E:2F:0D:D0:6C:57:E0:FC:0E:6B:49:FF:E5:F9:7F
debug key   : 19:90:DF:77:2C:ED:9C:2F:67:A6:4F:7E:27:DE:85:5A:16:CE:1F:8D:30:C8:8F:99:E8:EC:53:4A:9C:72:12:FB
```

`modules/drag-all/.signing/drag-all.keystore` 本来就在库里（0.1.x 起）。
密码默认值已硬编码在各 `build.sh` / `build.gradle.kts`（`SIGNING_STORE_PASSWORD` /
`SIGNING_KEY_PASSWORD` / `SIGNING_KEY_ALIAS` 可覆盖），本文件只是集中登记。

## 2. nuke / xhook 的 key

这两个 Gradle 工程历史上用机器级 debug key（`%USERPROFILE%\.android\debug.keystore`），
该文件原本不在仓库里 → 换机器签名就变、`adb install -r` 失败。
现把**本机在用的那把 debug key** 原样入库为 `signing/debug.keystore`：

- `nuke/src/local.properties` 已提交，`signing.storeFile` 指向 `../../signing/debug.keystore`，
  nuke 的 debug/release 两个构建都用它（证书与原 debug key **完全相同**，已装 APK 仍可覆盖安装）。
- xhook 走 Gradle 默认 debug 签名；新机器把 `signing/debug.keystore` 复制到
  `%USERPROFILE%\.android\debug.keystore`（Linux/macOS：`~/.android/debug.keystore`）即可复现。
- `xhook/ours/local.properties`（仅 `sdk.dir`）也已入库，新机器按需改路径。

## 3. 不在本文件登记的材料

- `_tmp_dingtalk/patch/patch.keystore`：nuke Patcher 运行时**随机生成**的临时 key，
  密码是 32 字节随机数、只存在同目录 `signing.properties` 里（该文件不在，密码不可恢复）。
  换机器重新 patch 会生成新 key，属一次性产物，不属于“构建 secret”；已装补丁包如需
  覆盖安装，只能找原 `signing.properties` 或卸载重装（卸载属设备变更，须先问用户）。
- `root/.upstream/`、`originos/` 内上游克隆自带的示例 keystore：非本仓库产物。
