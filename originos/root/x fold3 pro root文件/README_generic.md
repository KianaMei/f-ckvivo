# 在 vivo（OriginOS/TOS）机型上加载第三方内核模块（LKM）的通用流程

> 面向开发者的通用文档。**本文不含任何具体机型/内核参数**，所有与设备绑定的常量都在
> `PARAMETERS.md` 里列明，需要你按自己设备填写。
> 仅限本人设备 / 授权研究。临时 root，重启即失效。

## 0. 前置能力

| 能力 | 说明 |
|---|---|
| 临时 root（提权载荷） | 需按目标内核自行准备，本文不提供（各家 payload 与内核构建强绑定） |
| 内核读 | 本文用 kprobe fetch 实现（`rk.sh`） |
| 内核写零 | 由提权载荷提供 `CVE_*_ZERO_SYMS` 之类的能力即可，只需支持"把指定地址写 0" |
| 待加载 KO | 与你目标 KMI 匹配的 KernelSU LKM（或其它管理器 LKM） |

## 1. 这套流程在解决什么

vivo 系内核在加载外部模块时有三道独立的拦截，**表现容易混淆**：

| 拦截 | 现象 | 对策 |
|---|---|---|
| 模块过滤（`.modinfo` 缺字段） | `init_module` 返回 **EPERM，内核日志一个字不打** | 给 KO 的 `.modinfo` 补上厂商要求的过滤字段，值设为 `auto` |
| 加载门禁（kprobe / notifier / tracepoint） | 同样是 EPERM 或无解释的失败 | 清掉 `load_module` 相关 kprobe 的 `pre_handler`、notifier 里的断链、`sys_exit` 的 func 槽 |
| 进程白名单看门狗 | 加载进程被 SIGKILL（`dmesg` 里能看到 `... num--=<pid>,<comm>` 之类） | **改用 `insmod`**（`init_module`）而不是 `ksud late-load`（`finit_module`） |

另外，`/proc/kallsyms` 常被打码（`kptr_restrict` + 厂商自有的打开钩子）。而给 KO 打符号补丁
**必须用目标设备自己的符号表**——用"同版本但不同构建"的符号表会得到错误地址，最终在
`module_finalize → apply_alternatives_module` 处 CFI oops。

## 2. 推荐执行顺序

```
A. 提权拿到 uid=0（载荷由你自行选择）
B. 解除 kallsyms 屏蔽：摘掉 kallsyms 打开路径与 sysctl 写入口上的钩子，并把
   kptr_restrict / perf_event_paranoid 写零
   成功判据：head /proc/kallsyms 出现真实地址（如 ffff...T _text）
C. 导出 /proc/kallsyms 到文件
D. 用该文件给 KO 打补丁（patch_ko.py）：
   - 未定义符号 → SHN_ABS + 运行时地址
   - .modinfo 追加/替换为厂商过滤字段 = auto
E. 清模块加载门禁
F. insmod（不要用 ksud late-load）
G. 管理器侧收尾：ksud install、setenforce 1、验证 su 上下文
H. 清理：杀残留提权进程、停临时通道、删中间文件
```

**顺序不能换**：D 需要 B 的结果；E 必须在 F 之前；F 之后再做 G。

## 3. 脚本清单

| 文件 | 作用 | 是否含设备常量 |
|---|---|---|
| `patch_ko.py` | 用给定 kallsyms 文件解析 KO 未定义符号 + 写过滤字段 | 否（通用） |
| `cleanup.sh` | 收尾：杀残留、停临时通道、清临时文件 | 否（通用） |
| `params.env.example` | 需要你填的全部常量模板 | 是（空模板） |
| `PARAMETERS.md` | 每个常量的含义与获取方法 | — |
| `rk.sh` | kprobe 内存读原语（依赖目标内核的 tracefs 可写） | 否 |

其余与"某台具体机器"绑定的脚本（提权 payload、门禁清理、钩子定位）请按 `PARAMETERS.md`
中的变量说明，替换常量后使用。

## 4. 已知坑（省时间用）

1. **两种 EPERM 要分清**：补字段 vs 清门禁，症状一样，先试字段（成本低）。
2. **符号表必须来自本机**：不匹配会在 `apply_alternatives_module` 崩，且会堵死模块槽，
   只能重启恢复。
3. **管理器版本必须和 LKM 的 uapi 对齐**，否则管理器首页显示"未安装/需要更新内核"。
4. **温度影响提权载荷命中率**：多次实测高温下失败率明显升高，建议 ≤42°C 再跑。
5. **残留 socket / 锁文件**：若创建者已死而文件仍在（且目录有 sticky 位），普通身份删不掉，
   需要提权后 `rm -f` 或更换路径，否则第二、三次会卡住。
6. **提权进程可能残留空转**（`for(;;) pause()`），表现为单核近 100%，务必执行 `cleanup.sh`。

## 5. 免责

- 全部为临时提权，重启失效；不要尝试写入 `/system`、`/vendor` 等只读分区。
- 提权/内核写有硬复位、卡启动的风险，请在可恢复的设备上操作并自行承担后果。
