# Ding × Vector：上游功能全量保留 + 框架闸门补丁

## 当前合体版

| 项目 | 值 |
|---|---|
| 上游 | [Ding 1.16 (16)](https://github.com/Xposed-Modules-Repo/io.github.comewhy.ding/releases/tag/16-1.16) |
| 上游 APK SHA-256 | `d9781e6575832d29527dcc7f06fa5ef19fe4b485621d4ff9c4c63752b55d006c` |
| 包名 / 版本 | `io.github.comewhy.ding` / `1.16 (16)` |
| 本机目标钉钉 | 上游已适配 `9.0.5 (1280)`、`9.0.10 (1282)`；本机为后者 |
| 合体 APK | `dist/Ding-1.16-vector.apk`（构建产物不入库） |
| 合体 APK SHA-256 | `b4213c24e9944a7d4381d5892ada081cf1c2d73db90b7ad81102f07d13529bd8` |
| 合体签名 | `DingVector`，证书 SHA-256 `50:B9:4C:C7:92:36:F7:0C:6A:79:69:E7:A6:9A:9C:0E:FA:55:B7:48:62:CE:EA:AC:43:1F:82:06:C1:3C:70:D1` |

**上游 1.16 的功能、资源、Xposed 入口及其他 DEX 内容全部保留。** 本战线唯一自有补丁仍是旧 `1.10` 已使用的「框架判定放行 Vector」，没有改成另一套模块，也没有删上游任何新功能。

## 为什么需要补丁

上游只接受名称含 `lsposed`，且不含 `npatch` / `lspatch` / `fpa` 的框架；本机 Vector 2.2 的名称不满足这个条件。未补丁时，虚拟定位、反检测、设备/传感器/BLE 伪装、拍照替换、环境隐匿等入口会被 `unsupported_framework` 门控。

旧手机版 `1.10` 的实际人工改动只有 `cz.u0(String)` 恒返 `true`。上游 `1.16` 中同一逻辑移到 `q90.v0(String)`，本包保留相同语义：

```java
public static boolean v0(String frameworkName) {
    return true;
}
```

补丁器不写死混淆名，而是要求同一静态 `(String)boolean` 方法引用 `lsposed`、`npatch`、`lspatch`、`fpa` 四个锚点，并要求全 APK 所有 DEX 合计只能命中一个方法。零命中或多命中都直接失败，不猜测补丁位置。

## 构建

```sh
# 默认底座：cache/bases/ding/Ding-1.16.apk
sh patches/ding-vector/build.sh

# 明确重建已有产物
FORCE=1 sh patches/ding-vector/build.sh
```

可用 `BASE_APK` / `OUTPUT_APK` 覆盖路径；底座仍须匹配 `upstream.json` 锁定的 SHA-256。后续升级时更新 `upstream.json` 的版本、下载地址与哈希并放入对应底座，构建脚本会读取元数据，补丁定位无需跟着混淆名修改。

构建步骤：校验上游哈希 → 定位并补丁 DEX → 修复 DEX SHA-1/Adler-32 → zipalign → 固定私钥签名 → v3 验签 → 独立验包 → 对齐及包名/版本检查。

当前补丁把入口改为 `const/4 v0, 1; return v0; nop`，其中 `nop` 用于占住原双码元 `if-eqz` 的第二个码元，保持后续不可达指令的边界不变。验包要求：

- 非签名 ZIP 条目名称集合不变，833 个非 DEX 文件逐字节不变；
- 只有 `classes.dex` 可变，且必须逐字节等于「上游 DEX 经唯一闸门补丁后的预期结果」；
- 包名、`versionCode`、`versionName` 与 `upstream.json` 一致，v3 签名及 zipalign 通过；
- 另行以 JADX 复检，目标方法为 `return true;` 且无 DEX 解码错误。

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
