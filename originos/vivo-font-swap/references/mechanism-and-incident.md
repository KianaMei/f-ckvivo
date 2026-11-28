# vivo OriginOS 7 字体热切换机制与 2026-11-28 改字体惊魂复盘

> 验证设备：vivo X Fold6（`PD2545D` / `V2545A`，OriginOS 7 / Android 17，软件版本 `PD2545D_A_17.1.13.20.W10.V000L1`，内核 `6.12.58-android16-6-gdd6fd5c98918`）。
> 源码锚点均来自本机实拉 `framework.jar`、`vivo-framework.jar`、`services.jar` 反编译核验。

---

## 一、2026-11-28 改字体惊魂事故复盘

### 1. 现象

用户要求另一 AI（DeepSeek）将手机字体更换为从小米提取的**草莓可颂（`CMKSYKHB_import.ttf`）**，操作后导致**系统设置（`com.android.settings`）及部分新启动 App（如 `com.facebook.katana`）一打开即原生闪退**，且本机为 kiroot 临时 root，严禁重启。

### 2. 精确时间线（法证还原）

| 时间（2026-11-28） | 事件 | 设备侧痕迹 |
|---|---|---|
| `10:44` | 用户放入目标自定义字体 | `/sdcard/Download/CMKSYKHB_import.ttf`（9,359,692 B，北京花兮梦兮 `CMKSYKHB`，22,050 字形） |
| `10:50:41` | 在 iTheme 下载并应用免费字体 `司墨奶酪体` 作为跳板 | `/data/bbkcore/theme/.dwd/.../F/司墨奶酪体.itz` 解密释放 `/data/vfonts/司墨奶酪体.ttf`（3,609,448 B）；`system_server` 将该路径序列化入 `FontManagerService.mSerializedFontMap`，全局 `Configuration.typefaceChanged` 递增至 `0.02` |
| `11:09:59` | DeepSeek 手动改 `/data/vfonts/` 软链试图回退或替换 | `VivoFont.ttf -> /system/fonts/DroidSansFallbackBBK.ttf`、`fonts.xml -> /system/etc/fonts_default.xml`（属主 `root:root`），且**未通知 `system_server` 刷新字体配置** |
| `11:10:08` | **首次设置闪退**（距 `11:09:59` 仅 9 秒） | `com.android.settings` 发生 `SIGSEGV (SEGV_MAPERR, fault addr 0x0)` @ `/system/lib64/libminikin.so` (`minikin::Font::prepareFont+64`) |
| `11:10`–`11:41` | 连续 10+ 次闪退（含 `com.android.settings`、`com.facebook.katana`） | `11:31:24` DeepSeek 又将字体移到 `/data/local/tmp/store_font_司墨奶酪体.ttf` 留下垃圾；全机老进程 `/proc/<pid>/maps` 仍挂着 `/data/vfonts/司墨奶酪体.ttf (deleted)` |
| `12:01`–`12:03` | 零重启修复并成功换上草莓可颂 | 部署 `/data/vfonts/CMKSYKHB.ttf`（`0644`、`system:system`、`vfont_data_file:s0`）+ 清理 `/data/local/tmp/store_font_司墨奶酪体.ttf` + `service call activity 131` 递增 `typefaceChanged` 至 `0.03`；设置恢复稳定、全界面切换新字体，核心 PID `22726`/`22849`/`22909` 全程未变 |

### 3. 闪退根因（100% 实锤）

Android 12+ / OriginOS 7 的系统字体由 `system_server` 内的 `FontManagerService` 维护一份共享内存 `mSerializedFontMap`（`SharedMemory`），在每个新 App 进程启动时通过 Binder 分发给 `ActivityThread` → `Typeface.setSystemFontMap(SharedMemory)` → `nativeReadTypefaces()`。

关键在于：**`mSerializedFontMap` 中存储的是字体文件的绝对路径（`/data/vfonts/司墨奶酪体.ttf`），而非字库二进制本体**。

1. `10:50` iTheme 应用 `司墨奶酪体` 时，触发 `ActivityManagerService$20` 调用 `FontManagerInternal.setSerializedSystemFontMap()`，把 `/data/vfonts/司墨奶酪体.ttf` 写进了 `mSerializedFontMap`。
2. DeepSeek 直接删除/移走了 `/data/vfonts/司墨奶酪体.ttf`，只改了 `/data/vfonts/VivoFont.ttf` 软链，**没有触发 `updatePersistentConfiguration` 来让 `system_server` 重建 `mSerializedFontMap`**。
3. 所有已运行的老进程因为还持有旧 `mmap` 映射，暂时没有立刻崩溃；但**任何新启动的 App**（如 `com.android.settings`、`com.facebook.katana`）反序列化 `mSerializedFontMap` 时按旧路径 `open("/data/vfonts/司墨奶酪体.ttf")` 遇到 `ENOENT`，向 `FontCollection` 塞入空 `MinikinFont`；只要触发文本测量（设置首页 `DashboardSummary` → `SubscriptionInfo.createIconBitmap` → `Paint.getTextBounds`），`libminikin.so` 立即在 `minikin::Font::prepareFont` 解引用空指针 `SIGSEGV`。

---

## 二、OriginOS 7 字体热切换底层机制（反编译锚点）

### 1. 文件与软链拓扑

```text
/system/fonts/VivoFont.ttf  ──(ROM 固定软链)──▶ /data/vfonts/VivoFont.ttf ──(动态软链)──▶ 目标 .ttf
/system/etc/fonts.xml       ──(ROM 固定软链)──▶ /data/vfonts/fonts.xml    ──(动态软链)──▶ /system/etc/fonts_default.xml 或 fonts_online.xml
/system/etc/font_fallback.xml  (Android 15+ 实际主配置，sans-serif 指向 VivoFont.ttf)
```

- 默认字体路径常量（`android.graphics.Typeface` / `vivo.util.CastLog`）：
  - 国行（`ro.vivo.product.overseas != yes`）：`INITIAL_DEFAULT_FONT = /system/fonts/DroidSansFallbackBBK.ttf`（对应 `vivo Sans` 可变字重，底层映射 `vivoSansENVF.ttf` + `vivoSansSCVF.ttf` + `vivoSansTCVF.ttf`）。
  - 海外（`ro.vivo.product.overseas == yes`）：`DEFAULT_VIVO_FONT = /system/fonts/vivoSansENVF.ttf`，经典回退 `ORIGIN_FONT = /system/fonts/Roboto-Regular.ttf`。
- `fonts_default.xml` vs `fonts_online.xml` 差异：
  - `fonts_default.xml` 为 `sans-serif` 声明了 `weight 100..800` 的可变轴条目；
  - `fonts_online.xml` 仅为 `sans-serif` 声明 `weight 400` 单条目，匹配静态第三方 `.ttf`。

### 2. 完整热重载调用链

```text
调用方 (iTheme 或 root `service call activity 131`)
  │  Configuration.typefaceChanged += 0.01f
  ▼
ActivityManagerService.updatePersistentConfigurationWithAttribution()  [services.jar]
  ├─ enforceCallingPermission(CHANGE_CONFIGURATION)  ← UID 0 自动放行
  ├─ enforceWriteSettingsPermission()                ← UID 0 自动放行
  ├─ diff = tmpConfig.diff(values);  检查 (diff & 0x00800000) != 0 (CONFIG_TYPEFACE)
  ├─ mUiHandler.post(ActivityManagerService$20):
  │    1. CastLog.checkIfRestoreToDefaultFont()
  │         └─ 若 /data/vfonts/VivoFont.ttf 指向 /data/vfonts/* 且存在，
  │            用 new Font.Builder(file).build() 校验；抛异常才自动回退默认字体
  │    2. Typeface.reloadVivoFont()
  │         └─ Os.readlink("/data/vfonts/VivoFont.ttf") → updateFont(path)
  │              ├─ Roboto-Regular.ttf       → updateAndroidFont(path)
  │              ├─ DroidSansFallbackBBK.ttf → 国行转 updateVivoSansFont(vivoSansENVF.ttf)
  │              └─ 其他路径 (/data/vfonts/*) → updateOnlineFont(path)
  │                   ⚠️ 短路条件: if (!path.equals(mLastFontPath))
  │                   更新 mCurrentFontFamily(sans-serif)，CJK fallback 切回 NotoSansCJK-Regular.ttc
  │                   刷新 sSansSerifTypefaceMap 与 Styled/Weight 缓存
  │    3. Canvas.freeCaches(); Canvas.freeTextLayoutCaches();
  │    4. FontManagerInternal.setSerializedSystemFontMap()
  │         └─ 将 system_server 新 sSystemFontMap 序列化进 FontManagerService.mSerializedFontMap
  │    5. CastLog.symlinkForFontXML()
  │         └─ 根据 VivoFont.ttf 目标自动切换 /data/vfonts/fonts.xml
  │            (Roboto / DroidSansFallbackBBK → fonts_default.xml；其余 → fonts_online.xml)
  │    6. CastLog.updateFontAdjustment(mContext)
  └─ mActivityTaskManager.updatePersistentConfiguration(values, userId)
       └─ 广播新 Configuration 给全部已运行进程
            └─ ConfigurationController.handleReloadVivoFont()  [framework.jar]
                 └─ 非 system 进程检查 (diff & 0x00800000) != 0
                      → Typeface.reloadVivoFont() + Canvas.freeCaches() + freeTextLayoutCaches()
```

### 3. `IActivityManager` 事务号与 `Configuration` Parcel 布局（PD2545D 实测）

- `IActivityManager$Stub.TRANSACTION_getConfiguration = 54`
- `IActivityManager$Stub.TRANSACTION_updatePersistentConfiguration = 131`
  - 反编译代码：`Configuration _arg = data.readTypedObject(Configuration.CREATOR); data.enforceNoDataAvail(); updatePersistentConfiguration(_arg); reply.writeNoException();`
  - `writeToParcel` 与 `readFromParcel` 完全对称；且有 `enforceNoDataAvail()` 严格校验字节数。
- 实时 `service call activity 54` 返回 66 个 32-bit word（264 字节）：
  - `word[0]`：`0x00000000`（`writeNoException`，发往 tx `131` 时跳过）
  - `word[1]`：`0x00000001`（`writeTypedObject` 非空标记）
  - `word[2]`：`fontScale`（float32）
  - `word[3..4]`：`mcc`、`mnc`
  - `word[5..]`：`LocaleList`（长度视语言标签变长，因此**不要硬编码总长度**，始终以尾部相对位置定位）
  - `word[-4]`：`nightModeLevel`（如 `-1` = `0xffffffff`）
  - `word[-3]`：`ssMode`（如 `2`）
  - **`word[-2]`：`typefaceChanged`（IEEE-754 float32，如 `0.02f` = `0x3ca3d70a`，`0.03f` = `0x3cf5c28f`）**
  - `word[-1]`：`eaMode`（int32，如 `1`）

### 4. 五大易踩坑清单

1. **只改软链不触发 `updatePersistentConfiguration`**：新 App 全在 `minikin::Font::prepareFont` 空指针崩（即本次事故）。
2. **原地覆盖同名 `/data/vfonts/xxx.ttf`**：命中 `Typeface.updateOnlineFont` 的 `if (!path.equals(mLastFontPath))` 短路，系统以为路径没变而跳过重载。必须换新文件名。
3. **用 `mv` 从 `/sdcard` 移进 `/data/vfonts/`**：保留源 SELinux 标签 `media_rw_data_file`（或带 `:c512,c768`），导致普通 App 无权打开字库。必须 `cp` + `chmod 0644` + `chown system:system` + `chcon u:object_r:vfont_data_file:s0`。
4. **不要用 iTheme 试用来“永久挂靠”**：iTheme 存在 `com.bbk.theme/.apply.tryuse.VipPollingCheckService` 定时任务检查试用/VIP 状态；直接放自有 `.ttf` 到 `/data/vfonts/` 并通过 `IActivityManager` 热重载，与 iTheme 试用逻辑完全解耦（且只要后续不再在 iTheme 里点击应用/试用字体，就不会被覆盖）。
5. **字体文件本身缺陷**：若 `.ttf` 缺失常用字符（尤其是数字/英文字母/基础汉字）或表损坏，虽有 `CastLog.checkIfRestoreToDefaultFont()` 兜底回退，但务必先用 `scripts/live_reload_font.py --validate-ttf` 离线验表。
