# VivoTwsPopup（原生蓝牙全系弹窗）

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

面向 **vivo X Fold6（PD2545D / OriginOS 7 / `VivoTws 9.2.41.6`）** 的纯 **libxposed API 102** 原创模块。

- **单作用域 `com.android.vivo.tws.vivotws`，绝不 Hook `com.android.bluetooth`、绝不触碰 `system_server`，免重启生效**。
- 常驻配置登记：模块在自身应用私有目录存 `twspopup_ui` 偏好，并在 `com.android.vivo.tws.vivotws` 私有 `SharedPreferences` 存 `ganvivo_twspopup_cfg`（用于重拉进程后保留外观模型与过滤配置）；严禁写入 `/sdcard` 或 `/data/local/tmp`。

## 特性

1. **进程内蓝牙直连监听**：在 `com.android.vivo.tws.vivotws` 主进程内直接注册 `A2DP / HEADSET` 连接状态与 `BATTERY_LEVEL_CHANGED` 广播监听，自动为任意非 vivo 第三方蓝牙音频耳机弹出原生快连/电量卡片（并自动给原生 vivo/iQOO 耳机让位防重复）。
2. **AirPods BLE 三电量短扫**：耳机连上瞬间短暂开启 8 秒低延迟 BLE 扫描解析 Apple `0x004C` Continuity 广播，实时升级为左耳/右耳/充电盒三电量与充电状态，拿到一帧或超时立刻停扫，平时零耗电。
3. **14 款已验内置动画样式 + 一键预览 UI**：App 内直接切换 14 款已核验内置 `assets/flash_connect/` 素材的官方样式，并可一键发送广播预览「电量卡」或「配对 → 连接中 → 电量」完整三段动画。

## 构建

```sh
cd original-modules/vivo-tws-popup
sh build.sh
# 产物: original-modules/vivo-tws-popup/dist/VivoTwsPopup-0.1.0.apk
```

## 真机验证记录（2026-11-28）

- 设备：vivo X Fold6（`PD2545D` / OriginOS 7 / `VivoTws 9.2.41.6`，官方 LSPosed `2.2.1 (7912)` API 102）
- 签名证书 SHA-256：`17:3A:…:4F:57`，仅勾选 `com.android.vivo.tws.vivotws`（免 Hook `com.android.bluetooth`，免重启）
- 已通过 `lspctl` 启用并锁定单作用域，14 款内置 `assets/flash_connect/` 模型素材与 `TwsFastPairDeviceBean` 反射签名均与本机 `VivoTws 9.2.41.6` 逐项核对一致。
