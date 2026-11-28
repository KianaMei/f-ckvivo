---
name: vivo-font-swap
description: >-
  Zero-reboot live font switching, stock font restore, and minikin crash repair on rooted
  vivo OriginOS 7 devices (PD2545D / Android 17). Use whenever changing a custom TTF/OTF
  system font without rebooting, restoring stock vivo Sans, or diagnosing/fixing Settings
  or app SIGSEGV crashes in libminikin.so (minikin::Font::prepareFont) caused by stale
  /data/vfonts state.
---

# vivo OriginOS 7 零重启换字体与故障修复

> 遵守仓库 [AGENTS.md](../../AGENTS.md) 顶栏「设备不是草稿纸」与 §1 设备红线：
> **默认只读；写入设备前必须向用户说明「做什么 + 影响 + 风险」并获当次明确授权；严禁重启、软重启、`setprop ctl.restart` 或 kill 系统进程。**

本技能覆盖三个分支：
- **分支 A（换自定义字体）**：部署新 `.ttf` 到 `/data/vfonts/` 并通过 `IActivityManager.updatePersistentConfiguration` 触发 `system_server` 热重载与刷新共享字体表。
- **分支 B（还原系统默认字体）**：切回 `vivo Sans`（`/system/fonts/DroidSansFallbackBBK.ttf` + `fonts_default.xml`）并热重载。
- **分支 C（修复 `minikin::Font::prepareFont` 闪退）**：手工改坏 `/data/vfonts/` 导致设置或新 App 启动即 `SIGSEGV (fault addr 0x0)` 时的零重启救急。

底层反编译机制、`Configuration` Parcel 布局及 **2026-11-28 改字体惊魂复盘** 见 [references/mechanism-and-incident.md](references/mechanism-and-incident.md)。

---

## 步骤一：只读体检与字体预检（先不写设备）

1. **记录核心进程基线与当前 `/data/vfonts` 现场**：
   ```bash
   adb shell "su -c '
     echo PIDS: $(pidof zygote64 lspd system_server)
     ls -laZ /data/vfonts/
     grep -iE \"vfonts|\\.ttf\" /proc/$(pidof system_server)/maps | head -n 20
   '"
   ```
   - 记下 `zygote64`、`lspd`、`system_server` 三个 PID，用于收尾核对零重启。
   - 检查 `system_server` 映射里是否出现 `(deleted)` 的 `/data/vfonts/*.ttf`（命中即说明已踩到 `mSerializedFontMap` 路径失同步，对应分支 C）。
   - 记下当前活跃文件名（即 `Typeface.mLastFontPath`）。

2. **离线校验待换字体**（分支 A 必做；若源文件在手机上，用 `adb exec-out su -c 'cat ...' > cache/font-fix/target.ttf` 管道直拉到本机 `cache/`）：
   ```bash
   python originos/vivo-font-swap/scripts/live_reload_font.py --validate-ttf <本地ttf路径>
   ```
   - 校验所有 OpenType 表可解析、包含常用中英文/数字/标点字形。
   - 同时确认 `service call activity 54` 解析正常（只读 dry-run）：
     ```bash
     python originos/vivo-font-swap/scripts/live_reload_font.py --dry-run
     ```

**完成标准**：核心 PID 基线已记录、`--dry-run` 正常解析出 `typefaceChanged` 与 `eaMode`；分支 A 的目标字体已通过 `--validate-ttf` 校验。

---

## 步骤二：向用户说明并获取当次写入授权

向用户明确汇报并等同意后才执行步骤三：
1. **目标字体**（文件路径、体积、字体名、校验结果）及是否需要顺手清理历史遗留临时文件；
2. **做什么**：部署文件到 `/data/vfonts/<新文件名>.ttf`、更新软链、经 `service call activity 131` 仅递增 `typefaceChanged += 0.01f`；
3. **影响**：界面短暂刷新配置一次；已运行进程通过 `ConfigurationController.handleReloadVivoFont()` 实时换字，新进程从刷新的 `mSerializedFontMap` 加载新字体；不重启、不动临时 root；
4. **风险**：极低（离线已校验字体，且 `ActivityManagerService$20` 有 `CastLog.checkIfRestoreToDefaultFont()` 校验回退兜底，`IActivityManager$Stub` case 131 有 `enforceNoDataAvail()` 严格校验）。

**完成标准**：用户明确回复同意执行。

---

## 步骤三：部署字体并触发 `system_server` 热重载

### 关键铁律（踩坑硬约束）

- **永远不要删除正在被 `system_server` 映射的旧 `/data/vfonts/*.ttf` 而不触发重载**；先部署新文件并完成热重载，确保 `mSerializedFontMap` 已切换。
- **新字体文件名必须不同于当前的 `mLastFontPath`**：`Typeface.updateOnlineFont(path)` 内部有 `if (!path.equals(mLastFontPath))` 短路，**原地覆盖同名文件不会重载**。若要替换同名文件，先换一个新文件名（如 `MyFont_v2.ttf`）。
- **必须用 `cp` 而非 `mv` 从 `/sdcard` 写入 `/data/vfonts/`**，并显式设置 `0644`、`system:system` 与纯净的 `u:object_r:vfont_data_file:s0`（不得残留 `media_rw_data_file` 或 `:c512,c768` MCS 类别，否则沙箱 App 读文件被 SELinux 拒绝）。
- **设备侧零临时文件**：热重载全程走 `service call activity 54/131`，不在手机上落任何脚本或 DEX。

### 分支 A / C（切换到自定义字体，例如 `/sdcard/Download/MyFont.ttf`）

```bash
# 1. 复制字体并设齐权限、属主与 SELinux 上下文（文件名务必与当前活跃文件不同）
adb shell "su -c '
  set -e
  cp /sdcard/Download/<源文件>.ttf /data/vfonts/<新文件名>.ttf
  chown system:system /data/vfonts/<新文件名>.ttf
  chmod 0644 /data/vfonts/<新文件名>.ttf
  chcon u:object_r:vfont_data_file:s0 /data/vfonts/<新文件名>.ttf
  ln -sfn /data/vfonts/<新文件名>.ttf /data/vfonts/VivoFont.ttf
  ln -sfn /system/etc/fonts_online.xml /data/vfonts/fonts.xml
  chcon -h u:object_r:vfont_data_file:s0 /data/vfonts/VivoFont.ttf /data/vfonts/fonts.xml
  chown -h system:system /data/vfonts/VivoFont.ttf /data/vfonts/fonts.xml
  sha256sum /sdcard/Download/<源文件>.ttf /data/vfonts/<新文件名>.ttf
'"

# 2. 触发 system_server 热重载与共享字体表刷新
python originos/vivo-font-swap/scripts/live_reload_font.py --apply
```

### 分支 B（还原国行默认 `vivo Sans`）

```bash
adb shell "su -c '
  set -e
  ln -sfn /system/fonts/DroidSansFallbackBBK.ttf /data/vfonts/VivoFont.ttf
  ln -sfn /system/etc/fonts_default.xml /data/vfonts/fonts.xml
  chcon -h u:object_r:vfont_data_file:s0 /data/vfonts/VivoFont.ttf /data/vfonts/fonts.xml
  chown -h system:system /data/vfonts/VivoFont.ttf /data/vfonts/fonts.xml
'"
python originos/vivo-font-swap/scripts/live_reload_font.py --apply
```

> 海外版（`ro.vivo.product.overseas=yes`）默认字体路径为 `/system/fonts/vivoSansENVF.ttf`，见 [references/mechanism-and-incident.md](references/mechanism-and-incident.md)。

**完成标准**：SHA-256 一致、`live_reload_font.py --apply` 返回 `Result: Parcel(00000000 '....')`（零异常）。

---

## 步骤四：验收与收尾自查

等待 1–2 秒让 `system_server` 的 `mUiHandler` 跑完 `ActivityManagerService$20` 后，执行一次性只读验收：

```bash
adb shell "su -c '
  echo PIDS: $(pidof zygote64 lspd system_server)
  for p in system_server com.android.systemui com.bbk.launcher2; do
    echo \"[\$p \$(pidof \$p)]\"
    grep -E \"vfonts|CMKSYKHB|DroidSansFallback\" /proc/\$(pidof \$p)/maps 2>/dev/null | head -n 4
  done
  am start -n com.android.settings/.Settings
  sleep 2
  SP=\$(pidof com.android.settings)
  echo settings_pid=\$SP
  grep -E \"vfonts|DroidSansFallback\" /proc/\$SP/maps 2>/dev/null | head -n 4
  logcat -d -b crash | tail -n 15
  dmesg | grep -iE \"avc.*vfont\" | tail -n 10
  ls -laZ /data/vfonts/
'"
```

按需管道直拉截屏验证视觉效果（`adb exec-out screencap -p > cache/font-fix/verify.png`，注意折叠屏多屏 `screencap` 前部可能带 347 字节多屏提示文本，剥离到 `\x89PNG` 后查看）。

**完成标准（全部满足才算完成）**：
1. `zygote64`、`lspd`、`system_server` PID 与步骤一基线完全一致；
2. `system_server`、`com.android.systemui`、`com.bbk.launcher2` 与新拉起的 `com.android.settings` 均已映射目标字体；
3. `com.android.settings` 稳定停留在前台且 `logcat -b crash` 无新增崩溃；
4. `dmesg` 无 `vfont_data_file` 相关 `avc: denied`；
5. `/data/vfonts/` 与 `/data/local/tmp/` 无本轮新增临时文件。

### 收尾提醒用户

- 少数热切换前已常驻后台、自带独立字体缓存的 App（如微信/QQ），如局部仍是旧字体，划掉该 App 重开即可。
- 后续**勿在 iTheme 中再次点击应用或试用字体**，否则 iTheme 会覆写 `/data/vfonts/VivoFont.ttf`。
