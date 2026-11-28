# Vivo Power Insight（耗电洞察）

> 原创 Android Root 应用（Jetpack Compose + Material You Dynamic Color），专为 OriginOS 7 / vivo 设备打造，通过 Root 只读直连底层 PIE 功耗数据库与内核 fuelsummary 节点，完整采集、可视化展示并长期存档电池与功耗全维数据。

## 1. 基本信息

| 项目 | 值 |
|---|---|
| 应用名称 | 耗电洞察（Vivo Power Insight） |
| 包名 | `dev.ganvivo.powerinsight` |
| 签名 Keystore | `shared/signing/vivopowerinsight.keystore`（alias `vivopowerinsight`，SHA-256 `3F:88:13:…:70:F4`） |
| 权限需求 | Root（KernelSU / Magisk，执行只读 `su` 查询）+ `QUERY_ALL_PACKAGES`（解析应用中文名）；**无需联网权限、无需外部存储权限** |
| 适配形态 | Material You（Android 12+ 壁纸动态取色、图标中心羽化晕染日夜间切换动效），折叠屏自适应布局（外屏底栏 `NavigationBar`，内屏侧栏 `NavigationRail` + 多列 KPI 网格） |

## 2. 数据源与只读采集机制（零设备临时文件）

所有系统数据均通过单次 `su -c "sh -s"` 只读管道流式读取，**不在手机任何路径写临时文件或锁定系统库**：

| 维度 | 数据源 | 说明 |
|---|---|---|
| **① 应用耗电明细（全量不截断）** | `/data/system/pie/YYYYMMDD-PIE-*.db` (`raw_battery_gas_gauge` + `raw_foreground` + `raw_screen_brightness`) | 通过 `/system/bin/sqlite3 -readonly -json` 只读查询，按分钟桶对齐前台时长、掉电量、掉电速率、库仑计平均电流（mA）、加权平均亮度（nit） |
| **② 今日按小时时间分布** | `raw_battery_gas_gauge` + `raw_battery_state` | 不排除充电时段，逐小时统计充入%、放出%、亮屏时长、灭屏时长与状态，配交互式柱状图 |
| **③ 近 7 天 / 近 30 天耗电趋势** | `/data/user/0/com.vivo.pie/cache_databases/YYYY-MM-DD/overviewBatteryLife.bin` + 本应用持久化 `daily_archive` 表 | 开箱自动内存解码系统最近 8~9 天 `.bin` 缓存，同时每日沉淀进本应用私有数据库，突破系统仅保留 9 天的限制 |
| **④ 网络流量消耗** | `detailed_applications_wifi_data` + `detailed_applications_modem` | 今日总流量、WiFi/移动汇总及各应用前台/后台明细与占比 |
| **⑤ 电池健康度 & 循环次数** | `/sys/class/fuelsummary/{soh,soh_from_battery_health,cycle,capacity_mah_from_battery_health}` + `daily_battery_info` | 实时内核节点与 PIE 每日健康表双保险 |
| **⑥ 详细充电数据 & 协议电压** | `/sys/class/power_supply/battery/*` + `/sys/class/fuelsummary/{item,data,item_ex,data_ex}` + `/sys/class/cms_class/{chg_voltage,ibus,usable_soh}` + `raw_battery_state` + `thermal_charge_info` | 完整解析具体充电协议名（vivo FlashCharge VFCP / UFCS 融合快充 / USB PD 3.0 PPS / PD 9V / QC / USB BC1.2 SDP·CDP·DCP·Float / Qi·vivo 无线闪充）、VBUS/IBUS 总线功率、支持协议上限、电池保护限流 voter 提示与今日逐段充电明细 |
| **⑦ 全天 30 秒级温度采样** | `thermal_temperature_info` + `raw_battery_state` + `raw_foreground` | 直接输出电池温度 / 机身壳温 / 主板温度具体数值（℃），自动区分充电阶段与放电阶段温度区间，精确关联每一时刻的前台应用 |
| **附加：待机与网络唤醒** | `raw_screen_off_section` + `raw_wake_up_source` + `raw_net_wakeup` | 长/短待机统计、总唤醒次数、Top 网络唤醒应用 |

## 3. 常驻本地数据登记

按主仓《设备不是草稿纸》规范登记本应用私有持久化数据：

- `/data/user/0/dev.ganvivo.powerinsight/databases/power_insight.db`：
  - `snapshots` 表：用户每次手动点击「存为历史快照」保存的完整静态历史存档（与主界面「● 实时模式」严格分离；回看历史时顶部全局横幅提醒并可一键切回实时，支持同天多次存档、备注、回看、双快照 Δ 对比、删除）
  - `daily_archive` 表：每日耗电趋势长期归档（用于近 7 天 / 近 30 天对比）
- 导出 HTML / JSON 报告通过 Android Storage Access Framework（`CreateDocument`）由用户主动选择保存位置。

## 4. 单位规范

- 时长：`< 60 分钟` 用分钟，`≥ 60 分钟` 用小时，`≥ 24 小时` 用天，均保留 2 位小数
- 流量：`< 1 MB` 用 KB，`≥ 1 MB` 用 MB，`≥ 1 GB` 用 GB，均保留 2 位小数
- 温度：直接展示具体摄氏度数值（`XX.X ℃`）
- 缺失数据：统一标注 `该项暂无记录`，不编造估算值

## 5. 构建方式

```powershell
$env:JAVA_HOME = "C:\Program Files\jdk-21.0.8+9"
$env:ANDROID_HOME = "C:\Users\jaqenze\AppData\Local\AndroidSdk"
cd original-modules\vivo-power-insight
.\gradlew.bat :app:exportReleaseApk --no-daemon
```

产物输出至 `original-modules/vivo-power-insight/dist/vivo-power-insight-1.1.0.apk`（`.gitignore` 屏蔽，不入库）。
