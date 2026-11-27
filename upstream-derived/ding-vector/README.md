# Ding × Vector：上游功能全量保留 + 框架闸门补丁

## 当前合体版

| 项目 | 值 |
|---|---|
| 上游 | [Ding 1.18 (18)](https://github.com/Xposed-Modules-Repo/io.github.comewhy.ding/releases/tag/18-1.18) |
| 上游 APK SHA-256 | `a1996a4b9a2722fb6c2bb9e1a5ffc171254ab020ccb989f0f56a67c8e2efafe4` |
| 包名 / 版本 | `io.github.comewhy.ding` / `1.18 (18)`（与上游 tag `18-1.18` 完全一致，见下文「版本号策略」） |
| 本机目标钉钉 | `9.0.10 (1282)`；上游自 `1.15` 起声明适配，`1.17` / `1.18` 的发行说明未另列版本 |
| 合体 APK | `dist/Ding-1.18-vector.apk`（构建产物不入库） |
| 合体 APK SHA-256 | `841ec4113bf84faef398026f138d7ae874360e9d066c9053026c94d32bc0bb4f` |
| 合体签名 | `DingVector`，证书 SHA-256 `50:B9:4C:C7:92:36:F7:0C:6A:79:69:E7:A6:9A:9C:0E:FA:55:B7:48:62:CE:EA:AC:43:1F:82:06:C1:3C:70:D1` |

**上游 1.18 的功能、资源、Xposed 入口及其他 DEX 内容全部保留。** 本战线唯一自有补丁仍是旧 `1.10` 已使用的「框架判定放行 Vector」，没有改成另一套模块，也没有删上游任何功能。上一版合体版是 1.16（`b4213c24…d13529bd8`），2026-11-27 被本版同签名覆盖。

上游 1.16 → 1.18 的变化（按上游发行说明）：`1.17` 新增打卡/签到相册照片替换、自动通过审批，修复自动抢红包，**移除自动打卡**（`1.16` 才加入的功能，属上游自己的取舍）；`1.18` 新增自定义头像（个人资料页长按头像）、群签到与水印拍照。

## 为什么需要补丁

上游只接受名称含 `lsposed`，且不含 `npatch` / `lspatch` / `fpa` 的框架；本机 Vector 2.2 的名称不满足这个条件。未补丁时，虚拟定位、反检测、设备/传感器/BLE 伪装、拍照替换、环境隐匿等入口会被 `unsupported_framework` 门控。

旧手机版 `1.10` 的实际人工改动只有 `cz.u0(String)` 恒返 `true`。上游 `1.16` 中同一逻辑移到 `q90.v0(String)`，`1.18` 中移到 `nc0.A0(String)`（`insns_size=45`，入口仍是 `const/4 v0, #0` + `if-eqz`）。三版都是同一语义：

```java
public static boolean A0(String frameworkName) {
    return true;
}
```

补丁器不写死混淆名，而是要求同一静态 `(String)boolean` 方法引用 `lsposed`、`npatch`、`lspatch`、`fpa` 四个锚点，并要求全 APK 所有 DEX 合计只能命中一个方法。零命中或多命中都直接失败，不猜测补丁位置。

## 构建

```sh
# 默认底座：cache/bases/ding/Ding-1.18.apk
sh upstream-derived/ding-vector/build.sh

# 明确重建已有产物
FORCE=1 sh upstream-derived/ding-vector/build.sh
```

可用 `BASE_APK` / `OUTPUT_APK` 覆盖路径；底座仍须匹配 `upstream.json` 锁定的 SHA-256。后续升级时更新 `upstream.json` 的版本、下载地址与哈希并放入对应底座，构建脚本会读取元数据，补丁定位无需跟着混淆名修改。

构建步骤：校验上游哈希 → 定位并补丁 DEX → 修复 DEX SHA-1/Adler-32 → zipalign → 固定私钥签名 → v3 验签 → 独立验包 → 对齐及包名/版本检查。

当前补丁把入口改为 `const/4 v0, 1; return v0; nop`，其中 `nop` 用于占住原双码元 `if-eqz` 的第二个码元，保持后续不可达指令的边界不变。验包要求：

- 非签名 ZIP 条目名称集合不变，834 个非 DEX 文件逐字节不变；
- 只有 `classes.dex` 可变，且必须逐字节等于「上游 DEX 经唯一闸门补丁后的预期结果」；
- 包名、`versionCode`、`versionName` 与 `upstream.json` 一致，v3 签名及 zipalign 通过；
- 独立解码复检，目标方法为 `const/4 v0, #1; return v0` 且整份 DEX 无解码错误。1.16 用的是 JADX（`--single-class`）；1.18 换用 SDK 的 `dexdump -d classes.dex`（JADX 1.5.6 对这个 APK 跑 `--single-class` 十分钟没出结果，已按 PID 终止），124 万行反汇编里零 `?!`，`nc0.A0` 反汇编为 `const/4 v0, #1 / return v0 / nop`，调用点 `Lnc0;.A0` 仍在。

## 版本号策略与 LSPosed Store

LSPosed 管理器的 Store 按包名查 LSPosed 模块仓库的最新 tag（格式 `版本码-版本名`），满足任一条就提示更新：上游版本码大于本机，或版本码相等但版本名不同。所以合体版**原样保留上游的 `versionCode` / `versionName`**（当前 `18` / `1.18`）：对 `18-1.18` 不提示，上游发 `19-…` 或同码换名的 `18-…` 都会重新提示，不会漏掉。不要给版本名加 `-vector` 之类后缀，否则立刻命中「同码不同名」。Store 里的静音是按包名永久静音，以后新版本也不提醒，不要用。

## 装机记录

2026-11-27 09:22，用户在场、亮屏解锁，`adb install -r` 同签名覆盖 1.16 合体版。装机后 `versionCode=18 versionName=1.18`，设备侧 `base.apk` SHA-256 与本地一致，LSPosed 中保持启用、作用域 `com.alibaba.android.rimet` 不变，system_server / lspd / zygote64 的 PID 前后不变。

**adb 这次把一条良性警告报成了 `failed to install`**：输出是 `Warning: Error occurred during dexopt when processing external profiles: Failed to load profile '…/base.apk.prof': The profile does not match the APK … Completed with warning(s)`，实际已装成。成因是 `dist/` 里 apksigner 顺带生成的 `Ding-1.18-vector.apk.idsig` 让 adb 先走 `Performing Incremental Install`，被 vivo 的 `com.android.packageinstaller/.PackageInterceptActivity` 拦截后回退 `Performing Streamed Install`，回退路径会对旧 ART 外部 profile 做 dexopt，profile 与新 dex 校验不符就只给警告。按红线仍按「非 Success 即停」处理，向用户说明并逐项核对后才继续。以后装机前先把 `.idsig` 挪开，输出就是干净的 `Success`。

## 签名与首次换签

新固定私钥已入库：[`shared/signing/ding-vector.keystore`](../../shared/signing/ding-vector.keystore)。alias / storepass / keypass 均为 `dingvector`，完整指纹见[统一签名登记](../../shared/signing/README.md)。今后的合体版都用这把 key，可同签名 `adb install -r` 覆盖。

旧手机版证书为 `CN=DingPatch`（`31:4B:20:…:0A:B8`），其私钥未入旧仓；已再次检查常见目录、Temp 与回收站，未找到 `patch.keystore` / 对应 `signing.properties`。因此**从旧手机版首次升级到本包必须先卸载旧模块**，不能绕过签名检查直接覆盖。这会触发 `PACKAGE_*` 广播，并清除该模块 App 自身数据；Vector 中旧模块的启用状态/作用域也需重新核对。

设备操作不属于构建脚本。遵守[设备红线](../../AGENTS.md#1-设备红线最高优先级覆盖一切默认行为)：用户在场、屏幕解锁、当次明确授权，只走标准 `adb`，失败立即停止；禁止 root `pm install`、杀系统进程或重启。安装后先手动打开一次模块 App，再在 Vector 重新启用并勾选 `com.alibaba.android.rimet`，最后核对落盘 verbose 日志与实际功能。

## 文件

- `upstream.json`：上游版本、下载 URL 与 SHA-256 锁定。
- `tools/dex_framework_patch.py`：无第三方依赖的 DEX 定位、补丁、完整性修复与 APK 重打包。
- `tools/verify_apk.py`：独立验包；拒绝任何非预期 payload 改动。
- `build.sh`：统一调用共享工具链、签名及验收。
- `build/` / `dist/`：本地中间产物和合体 APK，均由主仓 `.gitignore` 屏蔽。
