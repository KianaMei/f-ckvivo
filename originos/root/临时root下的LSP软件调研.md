# 临时 root 下的 LSP 框架调研：Vector 与官方 LSPosed

日期：2026-11-16 立（原名 `VECTOR-KSU.md`），2026-11-28 收敛为选型对比。  
适用：PD2545D（X Fold6 / MT6993 / Android 17 / OriginOS 7）、PD2337（X Fold3 Pro / `6.1.172`）。  
现场：PD2545D = KernelSU v3.3.1(LKM) + Zygisk Next 1.5.3 + **官方 LSPosed v2.2.1 (7912)**；
PD2337 = SukiSU Nova（内置 Zygisk）+ 同一个 LSPosed。**Vector 已全线退役，不在任何一台机器上。**

> 运维口径（`lspd`、落盘日志、模块 App 打开条件、恢复优先 `setprop ctl.restart zygote`）以
> [AGENTS.md](../../AGENTS.md#3-框架--kernelsu-运维铁律) §3 为准，本文不再重复，只谈选型。

## 1. 一句话结论

临时 root 上要跑 Xposed 模块，现实选项只有两条：**Vector**（LSPosed 分支，开源）和**官方 LSPosed**（闭源二进制）。

- Vector 的长处是「能改、能看、粒度细」：完整 libxposed API 102、`staticScope`、按 `targetApiVersion`
  选入口的多策略加载、带模块 DB 的 root daemon，源码可读可改。
- Vector 的短处是「自己就是 bug 源，而且修复先落 canary」：2.2 发行版在软重启路径上会双 daemon 互转崩栈，
  就地升级还会触发重启风暴（2026-11-15 事故）。
- 官方 LSPosed 的长处是稳、生态默认对接；短处是闭源、能力较保守、装包有个 XZ 压缩坑。

本机最终停在官方 LSPosed。理由不是功能更强，而是**临时 root 下最不该动的就是框架本身**：模块生态默认按官方测，
Vector 的增量能力（scope 策略粒度）在我们的模块上没有决定性价值。

## 2. 结构对照

| | Vector 2.2（JingMatrix，`88f8e1fa`） | 官方 LSPosed v2.2.1 (7912) |
|---|---|---|
| 模块 id / 目录 | `zygisk_vector` | `zygisk_lsposed` |
| 注入方式 | Zygisk（Zygisk Next 或内核内置） | Zygisk（同上） |
| root daemon | `vectord`（`app_process` 起的 root 进程） | `lspd` |
| 管理器 | 寄生或独立 `manager.apk` | 寄生：常驻通知 + 拨号暗码 `5776733` |
| 运行时目录 | `/data/adb/lspd`（沿用 LSPosed 路径） | `/data/adb/lspd` |
| 分发 | 源码 + canary | 仅 `lsposed.zip`（GitHub 的 v1.9.2 是 2023 年旧版，不代表最新） |

## 3. 优劣对比

| 维度 | Vector 2.2 | 官方 LSPosed 2.2.1 |
|---|---|---|
| hook / API | 完整 libxposed API 102；额外提供 `staticScope` 与四类模块加载策略 | API 102，scope 规则更简单 |
| 可观测性 | daemon 落盘日志 + 模块 DB + CLI 齐全，排查「模块装了没生效」有据可查 | `lspctl status`、logcat 关键字；`verbose_*.log` 只在守护进程重启时写，不能当实时判据 |
| 稳定性 | **2.2 发行版软重启崩栈（见 §4）**；3080→3118 就地升级进过重启风暴 | 换回后连续运行至今无异常（2026-11-27 实测） |
| 模块生态 | 兼容官方模块，但作者默认只测官方，冷门模块偶有差异 | 生态默认目标 |
| 更新节奏 | canary 修得快，发行版滞后（bug 修在 canary，装机版没带上） | 发布慢、少动手；闭源，出问题只能等 |
| 部署坑 | 与 Zygisk Next 版本耦合；`service.sh` 用 `unshare -m` 起 daemon，重启路径要自己保证不重入 | 包内 33 个文件用 XZ（zip method 95），手机 busybox `unzip` 只认 store/deflate，直接 `abort "Unable to extract verify.sh!"`；需本地 7z 解包、校验 45 个 `.sha256`、重打成 deflate 再装 |
| 常驻成本 | 多一个 root daemon + manager | 同样有 daemon，但更轻、更少动 |

一句话：**Vector 用「能力上限」换「稳定性下限」**。它对愿意读源码、能自己跟 canary 的人是更好的框架；
对「只在临时 root 上挂几个自研模块」的用法，官方 LSPosed 的确定性更值钱。

## 4. Vector 2.2 的两处硬伤（已结案）

1. **软重启即崩**：`ksud soft-reboot` 会把 `service.sh` 重放一遍，起出第二个 `vectord`；两个 daemon 抢同一个
   rendezvous 名（真实服务 `serial`），servicemanager 把各自的 binder 交给对方，每个 transaction 在两者之间
   来回转发，binder 线程栈爆 → SIGSEGV。上游 `JingMatrix/Vector@5690481`（"Keep the daemon's lock where R8
   cannot reach it"）已修，**发行版 v2.2 没带上**。
2. **就地升级风暴**：3080→3118 运行中就地升级，daemon 反复重启、zygote 反复被拉起。最终靠「手工起恰一个
   daemon + 唯一一次 zygote-only 重启」恢复，详见
   [2026-11-15 事故记录](INCIDENT-2026-11-15-packageinstaller.md)。

这两条加起来，就是我们不再用 Vector 的实际原因——不是功能不行，是**临时 root 丢不起重启**。

## 5. 临时 root 给框架加的三条硬约束

1. **硬重启即丢 root**（`kernelsu.ko` 在内存里，boot 未改）→ 所有「重启」只能是 `setprop ctl.restart zygote`
   或 `ksud soft-reboot`，且后者会重放 `service.sh`，对自带 service 脚本的框架不友好。
2. **框架模块绝不就地升级/回滚**：升级 = 替换 `/data/adb/modules/<id>` + 重启注入，中间态最容易出双实例。
3. **判据用落盘状态而不是 logcat 有无**：`logcat -d` 的 main 环形缓冲只有 4 MiB（约 5–8 分钟），
   刷屏宿主会把模块日志挤掉；「没有日志」≠「没注入」。

## 6. 官方 LSPosed 的现场判据（留档）

```sh
su -c 'pidof lspd'                                  # 必须恰一个
su -c '/data/adb/ksu/bin/lspctl status'             # LSPosed 2.2.1 (7912) / API: 102 / System server: connected
logcat -d | grep -iE 'LSPosed'                      # LSPosedBridge: ... / LSPosedService: sent service to bridge
su -c '/data/adb/ksu/bin/ksud zygisk modules'       # zygisk_lsposed: companion=true, zygiskd64-zygisk_lsposed 已起
dumpsys notification --noredact | grep lsposed_status   # 「LSPosed 已加载」常驻通知
```

- 模块生效三条件：**模块 enabled + 目标在 scope + 目标进程重启过**；三者缺一都表现为「装了没生效」。
- 入口是通知或拨号暗码 `5776733`，不会装出 `org.lsposed.manager` 包。
- `lspctl` 未开开发者模式只给 `status` / `stop`；开了才有 `module` / `scope` 子命令。

## 7. 历史材料去哪了

- Vector 时期的 device 侧采集（模块 prop、日志、`znctx`）与反编译件（`apk/` 649 个文件、`daemon.apk`、
  `kmsg_old.log`，共 14.3 MB）已于 2026-11-28 **删除**：Vector 线退役，反编译产物不入库。
  需要复现请从上游 `JingMatrix/Vector@88f8e1fa` 自行 rebuild。
- 崩栈取证与时间线在 git 历史里（删除前的 `originos/root/vector-softreboot/FINDINGS.md`），
  本文 §4 保留了结论。
- 相关：[kiroot-apk-analysis.md](kiroot-apk-analysis.md)（exploit 侧）、
  [ROUTES.md](ROUTES.md)（路线状态）、[AGENTS.md](../../AGENTS.md#3-框架--kernelsu-运维铁律)（运维铁律）。
