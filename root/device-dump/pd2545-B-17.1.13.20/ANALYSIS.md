# PD2545D 真机内核离线分析（2026-11-15）

输入产物：`boot_b.img` → `kernel_b.bin`（未压缩 arm64 Image，41,306,624 B，
`sha256 96b72e5d…`）。全程离线，没有再碰手机。

结论一句话：**这份 Image 里 kallsyms / BTF 全在，已经完整抽出 128,943 个符号的链接期地址；
64560 端口原来用的 r59 地址与真机最大差 3.5 KB，必须换成本文件里的表。**

---

## 1. kallsyms：从裸 Image 里抽出来

### 1.1 定位

| 表 | 文件偏移 | 说明 |
|---|---|---|
| `kallsyms_token_table` | `0x16dac28` | 256 条 token，前几条是 `pr la core T_R vm_ ms lock t__ ou …` |
| `kallsyms_token_index` | `0x16dafc0` | 256 个 u16，`0,3,6,11,15,…`，最后 `0x38e` |
| `kallsyms_offsets` | `0x16db1c0` | 128,943 × s32，`0 … 0x2820000`（`_end` 相对 base 的偏移） |
| `kallsyms_relative_base` | `0x1759080` | `0xffffffc080000000` |
| `kallsyms_num_syms`（对齐前） | `0x14e3170` | `128943` |
| `kallsyms_names` | `0x14e3178` | 到 `0x16da448`，2,058,672 B |
| `kallsyms_markers` | `0x16da448` | 504 × u32，首项 0，末项 `0x1f69cb` |
| `kallsyms_seqs_of_names` | `0x16db1c0` 之后 | 本次未使用（地址靠 offsets 顺序即得） |

> 注意 6.12 的两处细节，踩过才知道：
> 1. 长符号用两字节长度，编码是 `len = (b0 & 0x7f) | (b1 << 7)`，**不是** `(b0<<8)|b1`；
> 2. 每个符号展开后**首字符是类型字母**（`T/t/D/d/B/b/r`），要剥掉。
>    这也是 `_text` 在表里显示成 `T_text` 的原因。

### 1.2 校验（三重，都过）

1. **内部一致性**：128,943 条地址单调不减，`_text = 0xffffffc080000000`，
   `_end = 0xffffffc082820000`（与项目笔记里 `LINK_END` 的 0x2820000 跨距一致）。
2. **markers 复核**：504 条 marker 位置与实际解码位置逐条一致，末位置正好收在 `0x16da448`。
3. **外部对照**：用手上的两份 `ksu_patch.txt`（设备 `/data/local/tmp` 一份 + `kiroot.apk` assets 一份）
   做 `runtime - link` 校验，**225/225 全部命中，slide 恒为 0**（因为那两份表本来就是链接期地址，
   slide 是 `ksu_patch_dyn` 在加载时加的）。

工具：`root/tools/kallsyms_dump.py`（新增，纯离线，`--json/--grep` 可复用）。

---

## 2. 64560 端口要的 LINK_*：真机值

base = `0xffffffc080000000`（链接期，运行时 + slide）。

| LINK | r59（旧） | **真机（新）** | Δ |
|---|---|---|---|
| `LINK_CYCLE_C` | `0xffffffc08253e278` | `0xffffffc08253c9f8` | −6272 |
| `LINK_CYCLE_D` | `0xffffffc08253ef10` | `0xffffffc08253f3e8` | +1240 |
| `LINK_MISC_LIST` | `0xffffffc08265e238` | `0xffffffc08265e838` | +1536 |
| `LINK_SYSCTL_BOOTID` | `0xffffffc0827e8d88` | `0xffffffc0827e8908` | −1152 |
| `LINK_UHID_MISC` | `0xffffffc082699a18` | `0xffffffc08269a018` | +1536 |
| `LINK_UHID_FOPS` | `0xffffffc08145f8d0` | `0xffffffc08145fa50` | +384 |
| `LINK_UHID_CHAR_OPEN` | `0xffffffc080e96d5c` | `0xffffffc080e97b44` | +3560 |
| `LINK_UHID_CHAR_WRITE` | `0xffffffc080e965d8` | `0xffffffc080e973c0` | +3560 |
| `LINK_UHID_CHAR_RELEASE` | `0xffffffc080e96e80` | `0xffffffc080e97c68` | +3560 |
| `LINK_CONFIGFS_READ_ITER` | `0xffffffc08073a0ac` | `0xffffffc08073aabc` | +2576 |
| `LINK_CONFIGFS_BIN_WRITE_ITER` | `0xffffffc08073a658` | `0xffffffc08073b068` | +2576 |
| `LINK_MODULE_DIRECT_BASE` | `0xffffffc081926090` | `0xffffffc081926db0` | +3360 |
| `LINK_MODULE_PLT_BASE` | `0xffffffc081926098` | `0xffffffc081926db8` | +3360 |
| `LINK_MEMSTART_ADDR` | `0xffffffc081926078` | `0xffffffc081926d98` | +3360 |
| `LINK_KIMAGE_VOFFSET` | `0xffffffc081927000` | `0xffffffc081927000` | **0** |
| `LINK_KMALLOC_CACHES` | `0xffffffc08192e4c0` | `0xffffffc08192e4c0` | **0** |
| `LINK_ANON_PIPE_BUF_OPS` | `0xffffffc0812eb608` | `0xffffffc0812eb788` | +384 |
| `LINK_INIT_TASK` | `0xffffffc08253dac0` | `0xffffffc08253c240` | −6272 |
| `LINK_INIT_CRED` | `0xffffffc082553840` | `0xffffffc082553e28` | +1512 |
| `LINK_SELINUX_BLOB_SIZES` | `0xffffffc081938868` | `0xffffffc081938868` | **0** |
| `LINK_SELINUX_STATE` | `0xffffffc0827c69a0` | `0xffffffc0827c6520` | −1152 |
| `LINK_SDATA` | `0xffffffc082520000` | `0xffffffc082520000` | **0** |
| `LINK_END` | `0xffffffc082820000` | `0xffffffc082820000` | **0** |
| `LINK_SYS_EXIT_TP` | `0xffffffc0826d8de0` | `0xffffffc0826d93e0` | +1536 |
| `LINK_IMAGE_BASE` | `0xffffffc080000000` | `0xffffffc080000000` | **0** |

机器可读版：`device-links.json`。全量符号：`kallsyms.txt`（`地址 类型 名字`）。

`LINK_CYCLE_C = init_task + 0x7b8`、`LINK_CYCLE_D = init_signals + 0x10`
（偏移来自本机 BTF，见下节）。

---

## 3. BTF：段布局与 r59 同源，结构偏移可直接沿用

`.BTF` 位于 `0x19ea3e4`（r59 也是这个偏移），164,865 个类型。

| 结构 / 成员 | 本机偏移 | 端口里的常量 | 结论 |
|---|---|---|---|
| `task_struct.thread_node` | `0x7b8` | — | 用于 `LINK_CYCLE_C` |
| `signal_struct.thread_head` | `0x10` | — | 用于 `LINK_CYCLE_D` |
| `file_operations.open` | `0x68` | `FOPS_OPEN_OFF 0x68` | 一致 |
| `file_operations.release` | `0x78` | — | 一致 |
| `tracepoint.funcs` | `0x48` | `TP_FUNCS_OFF 0x48` | 一致 |
| `vmap_node.busy` | `0x1808` | `VMAP_NODE_BUSY_OFF` | 一致 |
| `vmap_node.lazy` | `0x1828` | `VMAP_NODE_LAZY_OFF` | 一致 |
| `vmap_node` 大小 | `0x1880` | — | 一致 |

→ **结构几何没变，变的只是符号地址**。所以端口只需要重打 `LINK_*`，不用重算偏移。

---

## 4. 还差一块：PHYS_DELTA

`memstart_addr` / `kimage_voffset` / `module_direct_base` / `module_plt_base`
在 Image 里全是 **0**——它们是启动期才赋值的运行时变量，静态镜像看不出物理布局。
`PHYS_DELTA` 只能等下次拿到 root 时现读（`/proc/iomem` 或直接读这几个变量），
不能从这份 dump 推出来。这一点别自欺欺人。

---

## 5. 顺手记录

- 系统已是 `PD2545D_A_17.1.13.20.W10`（2026-11-06 构建，SPL 2026-10-01），
  但内核仍是 `gdd6fd5c98918` / 2026-08-05，64560 的"仍在漏洞区间"判断不变。
- `.BTF` 偏移、`_sdata`/`_end`、`kmalloc_caches` 都与 r59 完全重合，
  说明 vivo 这次 respin 只动了 text/data 的排布，没有换基础构建。
