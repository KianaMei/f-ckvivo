# XF3P 6.1.172 自写替代工具链

这些工具只使用仓库里已有的材料，重建 `x fold3 pro root文件` 缺失的外部 kit 中可确定的部分。

## 能确定的事实

- `ksym_20261121_1310.txt - 0x1c7d000000` 可还原 PD2337 / 6.1.172 的链接期符号表。
- 用该 linkmap 与旧 slide `0x1c7d000000` 重打 `ksu-final.ko`，可逐字节复现 `ksu_v_WORKING_20261121_1310.ko`（SHA-256 `012c2fc6…`）。
- 设备上的 `poc_tmp_root` 与仓库 `.upstream/rift-apk/.../poc_tmp_root` 是同一份（SHA-256 `369d2f78…`）。
- `poc_tmp_root` 的 `ksu_try()` 会读取 `/data/local/tmp/ksu_patch.txt`，按 `name link_address` 格式解析链接期地址，运行时地址 = `link_address + slide`，把 `.symtab` 的 `shndx` 写成 `0xfff1` 并生成 patched KO 后 `insmod`。
- 当前管理器的 `android14-6.1_kernelsu.ko` 与 `ksu-final.ko` 大小、符号表布局一致；后者额外带 `vivo_module_filter=auto` 可替换位与 `vivo` vermagic。

## 文件

- `make_linkmap.py`：从旧 kallsyms 快照生成 6.1.172 linkmap。
- `make_poc_patch_table.py`：生成 `poc_tmp_root` 可读的 `/data/local/tmp/ksu_patch.txt` 格式。
- `patch_ko_by_linkmap.py`：离线按任意 slide 重打 `ksu-final.ko` 并写 `vivo_module_filter=auto`。
- `verify_patched_ko.py`：验证 patched KO 的 226 个 `SHN_ABS` 地址、无未定义符号、filter 字段。
- `rk.sh`：kprobe-fetch 内核读原语，替代原 kit 的 `rk.sh`。
- `stage_reclaimed_assets.sh`：只复制/校验设备侧资产，不跑 exploit。
- `reclaimed_runner.sh`：Stage 1 只跑 `poc_tmp_root --leak` 尝试拿当前 slide；不写内核、不 insmod、不 install、不 kill。

## 静态验证

```sh
python -I make_linkmap.py "../x fold3 pro root文件/ksym_20261121_1310.txt" linkmap_6.1.172.txt
python -I patch_ko_by_linkmap.py "../x fold3 pro root文件/ksu-final.ko" linkmap_6.1.172.txt 0x1c7d000000 ksu_oldboot_repro.ko
sha256sum ksu_oldboot_repro.ko "../x fold3 pro root文件/ksu_v_WORKING_20261121_1310.ko"
python -I verify_patched_ko.py ksu_oldboot_repro.ko linkmap_6.1.172.txt 0x1c7d000000
```

应得到：`patched=226 undef=0 mismatches=0 filter=True`，且复现 KO 与旧工作 KO 同 SHA-256。

## 当前边界

- `poc_tmp_root --leak` 在本机当前状态下实测返回 `SLIDE site slide=0x0`，说明该二进制原本用于 XF6 / 6.12.58 的 slide leak 路径不适配 PD2337 / 6.1.172。
- 因此 Stage 1 只能作为检测；它能拿 slide 才继续，拿不到必须停。
- `--abs` 模式在本机可返回 `ABS ok=1`，但 `ksu_try()` 内部 patch/insmod 是否能在 6.1.172 + hooks_defend 下成功尚未实跑验证。
- 真要执行完整 `POC_INSTALL_KSU=1` 链仍属内核级高危动作，需单独确认；本目录当前不含自动开跑脚本。
