# `kiroot.apk` 离线分析

来源：`C:\Users\jaqenze\Downloads\apks\kiroot.apk`
大小 6,931,225 B，`sha256 ca47e660d0011ed6344d7c65b6101e80ce18170807d93d74d2768ae58b11a303`

这是 2026-11-12 那次临时 root 实际使用的工具；设备上装的包名就是 `com.kiroot.manager`。

---

## 1. 应用身份

| 项 | 值 |
|---|---|
| package | `com.kiroot.manager` |
| versionName / Code | `1.0` / `15` |
| 入口 | `com.kiroot.manager.MainActivity` |
| Shizuku provider | `com.kiroot.manager.shizuku`（`rikka.shizuku.ShizukuProvider`） |
| 权限 | `moe.shizuku.manager.permission.API_V23` |
| 签名 | **`CN=Android Debug, O=Android, C=US`**，自签调试证书，有效期 2026-11-01 → 2054 |
| 证书 SHA-256 | `BA:66:85:A2:8A:25:86:A5:B6:81:3F:FC:CD:D4:24:50:C5:20:6E:1C:72:D1:EC:7F:2D:1D:A2:14:34:C5:3C:1B` |

调试证书 + 1.0/15 + 无任何发行渠道特征 → 个人自建的一次性工具，不是商店应用。

## 2. 它不自己提权，它借 Shizuku 的 shell

`ShizukuShell` 通过 Shizuku 服务起进程，默认环境只有
`PATH=/sbin:/system/sbin:/system/bin:/system/xbin`，身份 `uid=2000(shell)`。
代码里自己会检查并警告：

> `Shizuku 不是 shell 身份（uid 2000），漏洞大概率失败`

也就是说：**root 链的起点是 adb shell 级别，不是 root**。这正是 `root.log` 里
一路 `uid=2000 … INIT_MODULE ret=-1 … CHOWN ret=-1` 的原因——所有提权都靠 poc 打内核。

## 3. 资产与设备副本比对

`assets/` 共 5 个文件，和 `/data/local/tmp` 上的东西一一对应：

| asset | 大小 | sha256 | 设备侧比对 |
|---|---|---|---|
| `poc_tmp_root` | 9,130,848 | `369d2f78…ea602` | 与 `cleanroot_bakasu.sh` 里写死的 POC_SHA 完全一致 |
| `kernelsu.ko` | 404,928 | `7e25d684…54d9f` | **与 `/data/adb/kernelsu.ko` 字节相同** |
| `ksu_patch.txt` | 7,824 | `37ec22bf…d857d` | 225 行「符号 链接期地址」，与设备那份仅行序/行尾不同 |
| `ksud` | 6,402,736 | `049491e2…fbfb1b` | 即 `/data/local/tmp/ksud`（4.2.0-rc3 系） |
| `kiro-hello.ko` | 3,080 | `b7cfd9ff…38502` | 就是现在挂在设备上的 `kiro` 模块（`srcversion=KIROHELLO0002`） |

## 4. 实际执行的命令（`MainActivity.runRoot`）

```java
Stager.push(...)                      // cat > /data/local/tmp/<name> + chmod，逐块推 5 个资产
ShizukuShell.exec(
  {"/data/local/tmp/poc_tmp_root", "--seed"},
  {PATH=…,
   POC_LOG=/data/local/tmp/root.log,
   POC_PRESSURE_MB=700, POC_GATE_MS=8000,
   POC_TP=1, POC_KILLHOOK=1,
   POC_INSTALL_KSU=1, POC_SU_TEST=1},
  "/data/local/tmp", null, 3_600_000L, sink)
```

要点：

- **没有 `POC_RMCLEAN`、没有 `POC_DEEP_MB`** —— 这就是 `cleanroot_bakasu.sh` 注释里
  "Rift 的 orange7 段传了全套，唯独缺 RMCLEAN" 的来源；清理收尾是另一个按钮。
- UI 自己写明：`开始利用 CVE-2026-98260（1-4 分钟，期间平板会明显卡顿）`。
  作者把它报成 **CVE-2026-98260**，和项目原先主线（43074 / 43499 / 64560）不是同一条。
- 完成后用 `su -c id` 自检，命中 `uid=0(root)` 才算成功。

## 5. "清理并复原"按钮 —— 对我们是硬红线

```java
ShizukuShell.exec({poc_tmp_root, --seed},
  {POC_LOG=/sdcard/Download/kiroot-clean.log, POC_PRESSURE_MB=700, POC_GATE_MS=8000,
   POC_DEEP_MB=1300, POC_TP=1, POC_KILLHOOK=1, POC_RMCLEAN=1}, …)
ShizukuShell.shell("rm -f <payload> ; ls -a /data/local/tmp")
ShizukuShell.shell("reboot", 15_000L)
```

一次点下去：**清 `/data/adb/ksu` + 删 payload + 直接 `reboot`**。
在"临时 root 不能掉"的前提下，这个按钮等于自杀，别按。

## 6. 与 11-12 现场的一致性

设备 `/data/local/tmp` 里剩的 `ksu_stage.sh` / `worker.sh` / `dispatch.sh` / `x300root.sh`
是 Rift（`pyb110`）那一套的脚本，`poc_tmp_root` 二进制是**同一份**（sha256 相同）。
也就是说 kiroot 的 poc 与 Rift 同源，差别只在包装：

| | kiroot.apk | Rift / pyb110 |
|---|---|---|
| 提权后怎么装 KSU | poc 内部 `POC_INSTALL_KSU=1` 自己 patch+insmod | 外部 `x300root.sh` 用 `ksu_patch_dyn` + `insmod` |
| 模块来源 | 内置 `assets/kernelsu.ko`（官方 3.3.1） | 从已装管理器的 `libksud.so` 里 `extract-binary` |
| 通道 | Shizuku 直连 shell | 自建 `chan/` 假 root 命令通道 |
| 清理 | 单独按钮（含 reboot） | 手动/`cleanroot_bakasu.sh` |

## 7. 结论

- 这是一次性的自建工具，靠 Shizuku 拿 shell，再用 poc 打内核拿真 root；
  它自己不改任何分区，root 完全活在内存里 —— 与设备现状吻合。
- 对后续工作的实际价值：`poc_tmp_root` + `ksu_patch.txt` 是**可复用的重挂链**，
  但前提仍是"干净开机"（`/proc/modules` 无 `kernelsu`、无 park 的 `t28k`）。
- 唯一要记住的坑：`清理并复原` 会 `reboot`。
