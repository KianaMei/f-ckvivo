# PD2545D (V2545A) 分区只读导出 — 2026-11-15 11:45

采集方式：`adb exec-out "su -c 'dd if=/dev/block/by-name/<part> bs=1M'" > <part>.img`

- 纯读：设备侧不落盘、不写任何分区，全程只保留在 RAM + PC
- 每份都做了两次独立读并比对（设备端 `dd | sha256sum` vs 本地 sha256）
- 再把镜像尾部 AVB footer 里的 hash descriptor 拿出来做 `sha256(salt + image[:size])` 自校验

## 设备快照
```
model    V2545A / PD2545D (MT6993, vivo X Fold6)
rom      PD2545D_A_17.1.13.20.W10 (OS 17.0), ro.build.date = 2026-11-06 21:58 CST
spl      2026-10-01
kernel   6.12.58-android16-6-gdd6fd5c98918-abogki543627742-4k (#1 SMP PREEMPT 2026-08-05 14:45 UTC)
slot     _b
avb      verifiedbootstate=green  flash.locked=1  veritymode=enforcing
root     KernelSU v3.3.1 LKM (in-memory), context=u:r:ksu:s0
```

## 产物

| 文件 | 字节 | sha256 | 校验 |
|---|---|---|---|
| `boot_b.img` | 100663296 | `3322eaab9aa4ecc99392d2e6fca72bb3a6a9d5fc9c123bc426c3f6de6707791b` | AVB HASH(boot) size=41328640 → **MATCH** |
| `kernel_b.bin` | 41306624 | `96b72e5dd2ab16f5f7471971c65546604ac2493bac9a5927b532b3ceb7b3aae8` | — |
| `init_boot_b.img` | 8388608 | `7a112551b483d4acaa60efe5c70dda5c3cc59629db82bd3794dc15c04ec36865` | AVB HASH(init_boot) size=3342336 → **MATCH** |
| `vendor_boot_b.img` | 134217728 | `2394d9840a81f480b3045d440a82636d24d9e97bbb9ec9130085881f341a5d18` | AVB HASH(vendor_boot) size=124338176 → **MATCH** |
| `vbmeta_b.img` | 8388608 | `c4dac458c81b0cc30688cfa59c39b3f958baaaf3eb1764b4252d605e9802e424` | AVB0 链式 vbmeta（本身无 footer） |
| `dtbo_b.img` | 8388608 | `e5aee6914e93216b207bf08025eb97e1074c2acb92add78751fcdec793fa19a9` | DTBO，无 AVB footer |

`kernel_b.bin` = `boot_b.img[0x1000 : 0x1000+41306624]`，未压缩 arm64 Image（MZ / EFI stub 头），
其中含 `Linux version 6.12.58-android16-6-gdd6fd5c98918-abogki543627742-4k`。

## 结构摘要
```
boot_b.img       ANDROID! v4, hdr 1584/page 4096, kernel=41306624, ramdisk=0
init_boot_b.img  ANDROID! v4, kernel=0, ramdisk=3337945 (LZ4 legacy 02 21 4c 18)
vendor_boot_b.img VNDRBOOT v4, vendor_ramdisk=123644473 (LZ4 legacy), dtb=679661 @0x75ec000,
                 ramdisk_table 216B/2 entries, bootconfig 159B
vbmeta_b.img     AVB0 ver 1.0, alg=SHA256_RSA2048, auth=320 aux=5056,
                 chain: boot / vbmeta_oem / vbmeta_system / vbmeta_vendor / vbmeta_vgc /
                        vendor_boot / init_boot / pvmfw
dtbo_b.img       DTBO magic 0xd7b7ab1e, total=516801, 1 entry
```

## 设备侧影响
```
uptime 218492s (11:38) → 219394s (11:53)，连续递增，无重启
kernelsu 模块仍在线，su 仍为 context=u:r:ksu:s0
/data/local/tmp 无新增写入
```
