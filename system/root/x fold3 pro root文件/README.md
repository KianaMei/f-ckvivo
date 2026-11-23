# vivo X Fold3 Pro（PD2337 / 6.1.172）临时 Root + KernelSU 激活

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
