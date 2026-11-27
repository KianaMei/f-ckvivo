# vivo X Fold3 Pro（PD2337 / 6.1.172）临时 Root + KernelSU 激活

> **这是一份从酷安要来的 X Fold3 Pro 方案，不是我们从零写的。**
> 原作者的工作环境是 Linux 的 `/workspace` + iQOO Neo10 中转机，脚本里的 `/workspace/.adbh`、
> `NEO10_ADB` / `NEO10_WORK`、`<DEVICE_IP>:33481`、`/usr/lib/android-sdk` 都是他那边的路径，不是本机。
> 2026-11-21 13:10 我们在自己的 XF3P 上按它跑通了一轮，补了几件本机专用材料；
> 2026-11-27 清掉「找方向」阶段的一次性产物，只留原始包 + 跑通那轮的产出。
> 哪些是原文、哪些是我们加的、删了什么，见 [§9 来源与保留口径](#9-来源与保留口径2026-11-27-整理)。

> 研究记录与复现脚本。**临时 root**：重启后全部失效，需要重跑。
> 仅限本人设备/授权研究使用，风险自负。

## 1. 适用范围

| 项 | 值 |
|---|---|
| 设备 | vivo X Fold3 Pro（V2337A / PD2337） |
| 系统 | OriginOS 7（Android 17，CP2A.260605.016） |
| 内核 | `6.1.172-android14-11-maybe-dirty` |
| 漏洞 | CVE-2026-98260（`libroot.so` + `profile.framed`，成功率约 1/4，失败会硬复位） |

## 2. vivo OS7 的防护栈（本方案要绕过的四层）

1. **`vr.ko` / `hooks_defend` 看门狗**
   - `hooks_defend` 注册 2 个 kprobe，handler 里做 `kh` 白名单校验
   - `kh` 是 818 条 `crypto_shash_digest` 哈希的排序数组（结构魔数 `0xCAFEBABE`）
   - 校验失败 → `kh_in_ret=0` → `phlm num--=%d,%d,%s` → SIGKILL
2. **kallsyms 屏蔽**：`kptr_restrict=2` 且 `sysctl_perf_event_paranoid>=2`，并由 kprobe 钩住
   `kallsyms_open` / `proc_dointvec_minmax_sysadmin`，改完立即被改回
3. **模块过滤**：`.modinfo` 缺 `vivo_module_filter=auto` → `init_module` 直接 EPERM，无日志
4. **模块加载门**：kprobe/notifier/tracepoint 三重门禁，未清则 insmod 被拒

## 3. 关键结论（踩坑记录）

- `ksud late-load` 走 `finit_module` → 被 `kh` 白名单判非法 → 进程被杀，**这条路走不通**
- `insmod`（`init_module`）不触发该击杀 → **改用 insmod + 自己打补丁**
- KO 的未导出符号必须用**目标设备自己的** `/proc/kallsyms` 解析
  （用别的同版本内核的符号表 → 地址错 → `module_finalize → apply_alternatives_module` CFI oops）
- 管理器版本必须与内核模块 uapi 匹配：本模块为 **32680（uapi 5）**，管理器需 32680；
  装 32707（uapi 6）会显示「未安装」

## 4. 复现步骤

前置：平板/PC 上通过无线 adb 连接手机；手机已解锁并开启「无线调试」。

```bash
# 0) 连接
adb connect <手机IP>:<端口>

# 1) 临时 root（脚本内含 sticky 目录残留 socket 修复）
adb shell 'cd /data/local/tmp; nohup sh attempt_fix.sh > att_bg.txt 2>&1 &'
# 循环确认：adb shell 'cd /data/local/tmp && ./libroot.so su -c "id -u"'  → 0

# 2) 清 dfd 门禁（kprobe pre_handler / notifier break / sys_exit 槽位）
python3 ksu_enable.py --probe          # 需 NEO10_WORK 指向脚本目录

# 3) 导出本机 kallsyms 并给 KO 打补丁
adb shell 'cd /data/local/tmp; ./libroot.so su -c "cat /proc/kallsyms > /data/local/tmp/ksym.txt; chmod 644 /data/local/tmp/ksym.txt"'
adb pull /data/local/tmp/ksym.txt ksym.txt
python3 patch_ko.py ksu-final.ko ksym.txt ksu_v.ko --filter

# 4) insmod（关键：不要用 ksud late-load）
adb push ksu_v.ko /data/local/tmp/ksu_v.ko
adb shell 'cd /data/local/tmp; ./libroot.so su -c "cd /data/local/tmp; insmod ksu_v.ko allow_shell=1; grep -i kernelsu /proc/modules"'
# 期望：kernelsu  <size>  0 - Live 0x...  (O)

# 5) 安装与收尾
adb shell 'cd /data/local/tmp; ./libroot.so su -c "/data/adb/ksud install"'
adb shell 'cd /data/local/tmp; ./libroot.so su -c "setenforce 1"'
adb shell 'su -c "id; getenforce"'      # 期望 uid=0(root) 且 context=u:r:ksu:s0 / Enforcing
```

成功判据（管理器界面）：
`工作中 · 越狱模式`、版本 `32680-5`、运行模式 `LKM`、SELinux `强制执行`。

## 5. 为什么手机会自己重启？

两个独立原因，别混为一谈：

| 现象 | 原因 |
|---|---|
| 跑 exploit 后 1~3 分钟内硬复位、`boot_id` 变化 | **exploit 本身失败**（UAF 未命中），内核 panic / 硬复位，属已知风险，成功率约 1/4 |
| 成功拿到 root 后过一段时间被重启 | **dfd 看门狗**：SELinux 被拉成 Permissive、`kptr_restrict` 被清零等异常状态会被检测并强制重启 |

所以 root 的时间窗口很短（约 2~3 分钟）。对策：
- 所有步骤在一轮内跑完，不要中途停顿
- KSU 起来后立刻 `setenforce 1` 恢复常态，减少异常特征
- 需要保留成果时用**软重启**（重启 zygote / system_server，不动内核），
  模块与 `su` 都还在；真正的整机重启会全部丢失

## 6. 文件清单

| 文件 | 说明 |
|---|---|
| `attempt_fix.sh` | `attempt.sh` 的加固版：root 身份先 `rm -f` 残留 socket，解决 sticky 目录导致的 rootd 起不来 |
| `ksu_enable.py` | 门禁清理（kprobe pre_handler / notifier / sys_exit），需按实际路径改 `NEO10_WORK` 与 `ADB` |
| `patch_ko.py` | **本项目核心工具**：用本机 kallsyms 解析 KO 未导出符号 + 写入 `vivo_module_filter=auto` |
| `kwalk.py` | 遍历 kprobe 哈希表，定位指定 handler（用于分析和清理） |
| `deploy_ksu.py` | 上述 1~5 步的串联脚本（参考实现） |
| `ksu-final.ko` | 待补丁的 KernelSU LKM（v3.3.1 / 32680） |

## 7. 已知限制

- 符号地址是**按 boot 变化**的，每次重启都要重新执行第 3 步
- 模块 uapi 固定为 5，管理器必须用 32680；若要 32707 需对应的 KO
- 没有内核任意写原语，因此无法伪造 `kh` 白名单条目；本方案是"绕开击杀"而非"通过校验"
- exploit 崩溃会中断整个流程，需要重新开机再来

## 8. 验证记录

- `insmod` 返回 0，`/proc/modules` 出现 `kernelsu ... Live` 
- `ksud debug info`：`lkm: true`、`late_load: true`、`runtime_mode: late-load`
- `su -c "id"` → `uid=0(root)`，`context=u:r:ksu:s0`
- `getenforce` → `Enforcing`
- 管理器界面：工作中 / 越狱模式 / LKM / SELinux 强制执行

## 9. 来源与保留口径（2026-11-27 整理）

结论先说：**原始包全部保留，我们跑通那轮的有效产出保留，找方向阶段的一次性试验全部删除**。目录体量从 88.2 MB 降到 30.7 MB。

### 9.1 原始包（酷安方案自带，未改动）

| 文件 | 作用 |
|---|---|
| `README_generic.md` / `PARAMETERS.md` / `params.env.example` | 脱敏后的通用流程，以及需要按目标机自行填写的常量表 |
| `patch_ko.py` | 核心工具：用本机 kallsyms 解析 KO 的未导出符号并填运行时地址，同时写入 `vivo_module_filter=auto` |
| `ksu_enable.py` / `ksu_attempt.py` | 清三道加载门禁 / 拔掉 `kallsyms_open` 与 sysctl 写入口上的钩子 |
| `kwalk.py` | 借 kprobe 读内核内存，遍历 `kprobe_table` 找指定地址上的 handler |
| `deploy_ksu.py` / `phaseA.sh` / `phaseB.sh` / `run_all.sh` / `cleanup.sh` | 全流程串联，主机侧与设备侧各一套 |
| `attempt_fix.sh` | 临时 root 启动器（含 sticky 目录残留 socket 修复） |
| `targets.py` | 按 slide 生成 phaseB 需要的地址表，含 kprobe 哈希桶位算法 |
| `ksu-final.ko` | 待补丁的 KernelSU LKM（32680 / uapi 5） |
| `patch_table.txt` | 226 个符号 → KO 内偏移的对照表 |
| `hooks_defend_disasm.txt` | `hooks_defend.ko` 反汇编，即 818 条 `kh` 哈希白名单看门狗本体 |

### 9.2 我们补的（2026-11-21 那轮实机产出）

| 文件 | 来源 |
|---|---|
| `ksym_20261121_1310.txt` | 本机 XF3P 导出的 `/proc/kallsyms` 快照，471,177 行；`_text=ffffffdc85000000`、slide `0x1c7d000000` |
| `ksu_v_WORKING_20261121_1310.ko` | 用上表补完 226 个符号、`insmod rc=0` 实装成功的那份 KO |
| `RUN_20261121_1310.md` | 那一轮的复现流水（含顺序约束、两种 EPERM 的区分） |
| `selfbuilt/poc_tmp_root.device` | 设备上原样拉回的提权载荷原件（提供内核读写/写零） |
| `selfbuilt/make_ko_current.py` | 按任意 slide 重打 KO：减 `0x1c7d000000` 还原链接期表，再加新 slide |
| `selfbuilt/{alloc_spam,timer_spam,perf_ip_probe}.c` | 搅 page allocator / 搅 posix cpu timer slab / 无特权 perf 采内核 IP 的小工具源码；二进制没留，按文件头注释那行 clang 命令即可重编 |

### 9.3 已删除（找方向阶段的一次性产物）

载荷 retarget 的六个试验版本与三个生成脚本：`poc_leak2`、`poc_leak3`、`poc_patched`、`poc_patched_current`、`poc_patched_new`、`make_poc_leak2.py`、`make_poc_leak3.py`、`make_poc_patched.py`（约 52 MB）；另加 `t98260_plant`、`t98260_plant_p1`、`patch_t98260.py`、`ksu-final_slide2386400000.ko`、7 个 `trace_*.bin` 原生 ftrace 抓样，以及 `alloc_spam`/`timer_spam`/`perf_ip_probe` 的编译产物。

判定标准：它们是「试错记录」而不是「可用资产」——同一个二进制的不同补丁版本、或只服务一次扫描的原始抓样，留着只会让人误以为还有几条路可走。已入库过的那批可从 git 历史取回（2026-11-23 入库时路径前缀还是 `system/`）：

```bash
git show 382f110:"system/root/x fold3 pro root文件/selfbuilt/poc_patched" > poc_patched
```

`trace_*.bin` 命中 `.gitignore` 的 `*.bin`，从未入库，删除后不可恢复——它们是当轮 ftrace 的原始字节，结论已写进 `RUN_20261121_1310.md`。
