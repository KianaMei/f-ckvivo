# DmSilencer（运营商遥测静音）

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

面向 **vivo X Fold6（PD2545D / OriginOS 7）** 的纯 **libxposed API 102** 原创隐私防护模块。

- **零 `system_server` 作用域、免重启生效**（仅作用域 `com.vivo.devicereg` 与 `com.mobiletools.systemhelper`）。
- **按需内存快照看板（`0.2.0`）**：目标进程内仅维护 160 条纯内存环形缓冲，不写任何目标目录或公共存储文件；打开本模块 App 或点「立即刷新」时，通过 `FLAG_RECEIVER_REGISTERED_ONLY` 定向有序广播按需拉取活跃进程统计与拦截明细（休眠中的进程绝不会被唤醒）。
- **常驻配置登记**：仅在用户进入本模块 App 刷新时，将已拉回的拦截历史去重缓存在 `dev.ganvivo.dmsilencer` 自身私有 `SharedPreferences`（`dm_silencer_history`，上限 200 条，App 内可一键清空）；目标进程与设备公共目录零落盘。

## 拦截范围

1. **`com.vivo.devicereg`**（`DeviceReg.apk 5.5.0.0`，实测库内收集 660 App 使用时长/启动次数、34 个周边 Wi-Fi BSSID、明文双卡 IMSI/ICCID/5G 基站、充放电温度，发往 `a.fxltsbl.com` / `zzhc.vnet.cn`）：
   - 关闭总开关（`DeviceRegActivity.e`、`ctrl.a.p/t/k`）
   - 拦截全部 18/18 `cn.richinfo.dm.DMController` 采集/上报方法与 `DbManager` 全部写入方法
   - 拦截 `CmRegReceiver`、`DmAlarmReceiver`、`DmOnlineConfigReceiver`、`DmOnlineConfigJobService`、`DMBroadCastReceiver`、`DynamicBroadcast`、`RegReceiver`（保留暗码查询页面）
2. **`com.mobiletools.systemhelper`**（`SystemHelper.apk 3.0.1-20251230`，实测每 15 分钟写一条 6 位小数精确 GPS + 5G SA 基站记录到 `signalhelper.db`，已累计 `#13440`）：
   - 拦截全部 7 个采集广播接收器、`RegisterIntentService`、`RegisterJobService`、百度定位启动 `GetSignalInfoReceiver.d` 与核心上报入口 `e1.b.a`
3. **进程级系统 API 围栏（`RuntimeFence`，仅作用于上述两个进程，绝不影响其他应用）**：
   - 拦截 `URL.openConnection`、`Socket.connect`（放行回环）、`SQLiteDatabase.insert*/replace*`、`LocationManager.*`、`WifiManager.startScan/getScanResults`、`AlarmManager.set*`、`SmsManager.send*`、`JobSchedulerImpl.schedule`。

## 构建

```sh
cd original-modules/dm-silencer
sh build.sh
# 产物: original-modules/dm-silencer/dist/DmSilencer-0.2.0.apk
```

## 真机验证记录（2026-11-28）

- 设备：vivo X Fold6（`PD2545D` / OriginOS 7 / Android 17，官方 LSPosed `2.2.1 (7912)` API 102）
- 同签覆盖升级 `0.1.0` → `0.2.0`（`adb install -r`，证书 SHA-256 `82:DF:…:62:7D`），按精确 PID 重载 `com.vivo.devicereg`（`com.mobiletools.systemhelper` 保持休眠未唤醒）：
  - `DeviceRegBlocker installed=38` + `RuntimeFence installed hooks=63` = `101 Hooks`
  - `StatsResponder registered in com.vivo.devicereg`
  - 启动瞬间拦截 `BLOCK[devicereg.master #1] DeviceRegActivity.e -> false`，随后进程进入休眠
  - 打开 App 按需刷新可正常拉取实时内存统计与拦截时间线，休眠目标进程不被唤醒，设备零新增临时文件。
