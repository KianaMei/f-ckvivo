# 需要自行填写的常量

这版文档与脚本里**不含任何具体机型数据**。下面每个常量都请从**你自己的目标内核**取得。

## 1. 内核符号（链接期地址，非运行时）

从目标机型的 `vmlinux` / `System.map` / 内核镜像里查（`nm` 或 `grep`）：

| 变量 | 含义 | 查法 |
|---|---|---|
| `KPROBE_TABLE` | 内核 kprobe 哈希表数组首地址 | `nm vmlinux | grep ' kprobe_table$'` |
| `LOAD_MODULE` | 模块加载主函数 | `nm vmlinux | grep ' load_module$'` |
| `KALLSYMS_OPEN` | `/proc/kallsyms` 的 open 实现 | `nm vmlinux | grep ' kallsyms_open$'` |
| `SYSCTL_WRITE` | `kptr_restrict` 等整型 sysctl 的写入口 | `nm vmlinux | grep ' proc_dointvec_minmax_sysadmin$'` |
| `KPTR_RESTRICT` | `kptr_restrict` 变量 | `nm vmlinux | grep ' kptr_restrict$'` |
| `PERF_PARANOID` | `sysctl_perf_event_paranoid` 变量 | `nm vmlinux | grep ' sysctl_perf_event_paranoid$'` |

> 运行期地址 = 链接期地址 + 本次开机的 KASLR slide。
> slide 可用 `calibrate` 类接口或 `kallsyms` 中任一已知符号反推。

## 2. 结构体字段偏移（随内核版本/配置变化）

| 变量 | 含义 | 取法 |
|---|---|---|
| `KP_OFF_ADDR` | `struct kprobe.addr` 偏移 | 一般为 `0x28`，请用 `pahole`/BTF 核对 |
| `KP_OFF_PRE` | `struct kprobe.pre_handler` 偏移 | 一般为 `0x40`，同上 |
| `KPRE_HASH_SHIFT` / `KPRE_HASH_BITS` | kprobe 表取桶用的 hash 参数 | 见 `kernel/kprobes.c` 的 `hash_ptr`/`kprobe_hash` |
| `MOD_MEM_DATA_IDX` | `struct module.mem[]` 中 data 段下标 | 见 `include/linux/module.h` |

## 3. 厂商过滤字段

| 变量 | 说明 |
|---|---|
| `FILTER_FIELD` | 该机型 `.modinfo` 需要的过滤字段名与值（本流程中为 `auto`）。可通过对**厂商自带、可正常加载的 KO** 执行 `modinfo` 对比得出 |
| `FILTER_REPLACE` | 若 `.modinfo` 无空位，选一个长度相同的既有字段做原位替换（`patch_ko.py --filter` 已内置"同长度替换"逻辑，替换目标字符串请按你的 KO 调整） |

## 4. 其它环境项

| 变量 | 说明 |
|---|---|
| `ADB` | adb 可执行文件路径 |
| `DEV` | `adb connect` 后的设备串（`ip:port`） |
| `HOME` | 存放 adbkey 的目录（需跨命令保持一致，否则每次都要重新授权） |
| `WORK` | 脚本工作目录（存放中间文件的设备侧路径） |

## 5. 与提权载荷相关的项

| 项 | 说明 |
|---|---|
| `ZERO_SYMS` | 载荷支持的"批量写零"接口的地址列表格式（本流程按"链接期地址,逗号分隔"传入） |
| `SLIDE_SOURCE` | slide 的获取方式（载荷的 calibrate 输出 / 缓存文件） |
| `PARK_MARKER` | 载荷完成写零后打印的标记字符串（用于等待同步） |
