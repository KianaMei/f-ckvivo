# 侧边栏前台原图 / 原 GIF 直捞计划（Sidebar Media Grabber）

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

> 调研日期：2026-11-26（**全程只读**：仅管道直拉系统 APK 本地反编译与只读 `dumpsys`/`settings get`，手机零写入）。
> 设备现场：vivo X Fold6（`PD2545D` / `V2545A`），OriginOS 7 / Android 17，KernelSU 临时 Root + Zygisk Next + LSPosed/Vector。
> 当前状态：**规划完成，待启动 Phase 0 缓存目录摸底与 Phase 1 MVP 开发**（后续正式源码按主仓归属规则落到 `original-modules/`，本目录保留系统侧边栏逆向结论与实施路线图）。

---

## 0. 背景与选型结论

### 为什么不直接魔改 vivo「即刻图文」或逐个 Hook 第三方 App？

1. **「即刻图文」先天能力边界**：本机 `com.vivo.vtouch`（`v11.5.1.0.9`）+ `com.vivo.accessibilityenhance`（`v3.0.5.0`，`uid=1000`）链路本质依赖「截屏 + AI 抠图 + 有限视图树/白名单适配」，拿不到大多数第三方 App 里的**原始分辨率静态图与原始 GIF 动图**，且遇到 `FLAG_SECURE` 窗口截屏会出黑图。
2. **逐个 Hook 目标 App 成本与副作用高**：每多支持一个 App 就要多一个 Xposed 作用域与版本锚点维护，不仅增加目标 App 注入开销与耗电，还可能引入目标 App 风控风险。
3. **不碰 `system_server` / `AccessibilityEnhance`**：临时 Root 硬重启即丢，应避免在关键系统服务上叠加复杂逻辑。

### 选定方案：**按需 Root 前台缓存直捞 + vivo 智能侧边栏常驻入口**

- **0 第三方 App Hook、0 `system_server` Hook、平时 0 后台常驻耗电**。
- 在任意 App 看到想保存的原图或原 GIF 时，滑出 vivo 智能侧边栏点击按钮（直接紧挨着「即刻图文」），按需触发一次 Root 扫描当前底层前台 App 的缓存目录，按文件头魔数（Magic Bytes）捞出最近写入的原始图片/GIF，预览并无损保存相册或分享。
- **天然绕过 `FLAG_SECURE`**：直接读取缓存原文件而非截屏，不需动系统窗口安全标志。

---

## 1. vivo 智能侧边栏实查机制（反编译锚点）

### 1.1 宿主进程与插件结构

| 组件 | 包名 / 路径 | 版本 | 权限 / 标志 | 作用 |
|---|---|---|---|---|
| 宿主进程 | `com.vivo.upslide` (`/system/priv-app/UpSlide`) | `17.0.1.20` | `sharedUserId=android.uid.system` (`uid=1000`), `pkgFlags=[SYSTEM, HAS_CODE, PERSISTENT]` | 侧边栏运行宿主与系统边缘手势进程 |
| 插件 APK | `com.vivo.card` (`/data/app/.../com.vivo.card-.../base.apk`) | `9.0.2.22` | 普通系统更新应用（非 `uid=1000`，非 `PERSISTENT`） | 包含 `com.vivo.sidedockplugin.*` 全部侧边栏逻辑（未混淆） |

`com.vivo.upslide` 内部的 `com.vivo.card.plugin_sdk.PluginManager` 通过 `context.createPackageContext("com.vivo.card", 3)` 动态加载 `com.vivo.card` 的 `ClassLoader` 并实例化 `com.vivo.sidedockplugin.SideDock`。因此侧边栏 UI、编辑、工具发现与点击分发均执行在 **`com.vivo.upslide`** 进程内。

### 1.2 常驻列表数据结构（`Settings.System`）

本机实测配置：
```text
upslide_quick_launch_apps =
  com.vivo.vtouch,2,instant_text;com.vivo.translator,2,global_translation;com.vivo.favorite,3,ai_memory;com.tencent.mm,0;...
upslide_open_quick_app_small_window = open
```

列表由 `;` 分隔，每项对应 `SideDockAppBean.AppKey(pkg, keyType, detail)`（见 `com.vivo.sidedockplugin.model.applist.ResidentAppHelper`）：

- `keyType = "0"`（/ `"1"` 分身）：普通应用图标。
- `keyType = "2"`：内置静态工具，定义于 `VivoCard.apk/assets/shortcutlist_os4_1.json`（如 `instant_text` 即刻图文、`global_translation` 全局翻译），由 `FunctionFactory.getFunctionType()` 控制。
- `keyType = "3"`：**vivo 原生动态「快捷工具插件（`CONVENIENT_TOOL`）」**（如 `ai_memory` 小V记忆）。

> **关键发现**：在 `SideDockHotseatAppsAdapter` 中，`type 0 / 2 / 3` 全部存放在同一个常驻 `mAppList` 中，**在编辑态可直接拖动排序，把普通 App 或 `type=3` 工具紧挨着「即刻图文」排在最上方**。

### 1.3 两类点击启动链路

1. **普通 App（`keyType = "0"`）**：
   - `SideDockHotseatAppsAdapter.launchApp()` → `HotseatAppList.checkToLaunchApps()` → 当 `upslide_open_quick_app_small_window=open` 时，调用 `AppInfoUtils.launchSmallWindow()` 向 `com.vivo.smartmultiwindow` 发送 `Intent("com.vivo.smartmultiwindow.manager_smartmultiwindow_state")` 以**原生悬浮小窗**拉起应用，底层全屏 App 不退后台。
   - **约束**：`ResidentAppHelper.onSmallWindowAppsChanged()` 与 `SideDockHotseatAppsAdapter.buildEditStateListFromSettingIdle()` 会过滤不支持小窗的 App；普通第三方 App 保持默认 `resizeableActivity="true"` 即可正常加入（例如本机侧边栏已有 `com.exteraless.app,0`）。
   - **重复点击行为**：若小窗已在前台（位于 `mFreeformApps`），再次点击侧边栏图标会走 `SmallWindowInteractionProxy.performTaskFeedbackAnimForMultiWindow()` 触发聚焦动效而非重开。
2. **原生快捷工具插件（`keyType = "3"`，`CONVENIENT_TOOL`）**：
   - **扫描发现**：`ConvenientToolsHelper.resolveServiceInfos()` 查询所有响应 `Intent("com.vivo.card.intent.action.CONVENIENT_TOOL")` 的 Service，读取：
     - `meta-data`: `com.vivo.card.metadata.CONVENIENT_TOOL`（唯一 `toolId`）
     - `meta-data`: `com.vivo.card.metadata.CONVENIENT_TOOL_AREA`（`TOOL` / `RECOMMEND` / `RECOMMEND_AND_TOOL`，默认 `TOOL`）
     - 图标与显示名直接取自 Service 自身的 `android:icon` 与 `android:label`。
   - **无包名白名单，唯一门槛是 1 行权限检查**（`ConvenientToolsHelper.java:396`）：
     ```java
     if (this.mPackageManager.checkPermission("com.vivo.sidedockplugin.permission.CONVENIENT_TOOL", pkg) != 0)
     ```
     该权限由 `com.vivo.card` 声明为 `signature` 级别。
   - **支持度握手**：`ConvenientToolServiceManager.checkConvenientToolSupport()` 用 `CONVENIENT_TOOL` Action 绑定 Service，调用单方法 AIDL `com.vivo.sidedockplugin.IConventientToolService`（`DESCRIPTOR = "com.vivo.sidedockplugin.IConventientToolService"`，`TRANSACTION_isSupport = 2`），返回 `true` 即自动加入侧边栏工具列表（且监听 `onAppInstalled` 实时生效）。
   - **点击触发**：`ConvenientToolsHelper.onLaunchToolEvent()` → `ConvenientToolServiceManager.launchConvenientTool()` 以 `action = com.vivo.card.intent.action.CONVENIENT_TOOL_CLICK` 执行 `bindServiceAsUser()` 并在连接成功后立即 `unbindService()`——**完全不弹小窗、不切换前台焦点**。

---

## 2. 分阶段实施计划（两步走，同一份 APK 无缝演进）

采用**渐进式方案**：先交付 **0 Hook、0 风险** 的侧边栏小窗可用版本把核心捞图能力打磨好；若日常使用中希望去掉小窗外框、对齐「即刻图文 / 小V记忆」的无感悬浮体验，再在同一份 APK 上叠加 Phase 2 的极薄插件适配。

### Phase 0：高频 App 图片 / GIF 缓存目录只读摸底

**目标**：验证「前沿高频 App 缓存直捞」的实际覆盖率，建立内置加速路径表，识别需要特殊解码的场景。

- **抽样对象（结合本机常用 App）**：
  - 微信（`com.tencent.mm`）
  - QQ（`com.tencent.mobileqq`）
  - 小红书（`com.xingin.xhs`）
  - 抖音（`com.ss.android.ugc.aweme`）
  - B 站（`tv.danmaku.bili`）
  - X / Twitter（`com.twitter.android`）
  - Telegram 客户端（`com.exteraless.app` / `fork.risin42.nagramx`）
  - Chrome（`com.android.chrome`）
- **摸底内容（全程只读管道，不在手机写文件）**：
  1. 浏览单张高清原图与单张 GIF 后，定位实际落盘路径（`/data/user/0/<pkg>/cache`、`/data/user/0/<pkg>/files`、`/sdcard/Android/data/<pkg>/cache` 等）。
  2. 记录文件名规律（是否有后缀、是否 Glide/Fresco/Coil `.0` / `.cnt` / hash 缓存）。
  3. 确认文件头是否为明文标准图片/动图格式，标记存在私有混淆/加密或纯内存不落盘的例外 App。
- **交付物**：一份轻量 JSON/常量配置表（`pkg -> 优先子目录 + 排除噪音目录`）。

---

### Phase 1：零 Hook 侧边栏小窗版（MVP，首选落地）

**定位**：原创独立工具 App（代码落户 `original-modules/sidebar-media-grabber/`），**零 Xposed Hook、零系统进程注入**，直接加入 vivo 侧边栏常驻区并拖到「即刻图文」紧旁边。

#### 1. 核心架构

```text
[用户在目标 App 看到原图/GIF]
        │  滑出侧边栏，点「捞原图」图标 (type=0)
        ▼
[vivo 原生悬浮小窗启动 MainActivity]  ← 底层全屏目标 App 不退后台
        │
        ├─► 1. 底层前台包名识别 (su 只读)
        │      解析 dumpsys activity activities / dumpsys window
        │      过滤自身小窗、SystemUI、输入法，取底层全屏/分屏 App 的 pkg + userId (兼容 999 分身)
        │
        ├─► 2. 按需高速缓存扫描 (su 只读流 / 本地进程直读)
        │      优先扫该 pkg 热点缓存目录，未命中则限时兜底扫 cache/files + /sdcard/Android/data/<pkg>/cache
        │      过滤条件：mtime 在最近 N 分钟（默认 5 分钟，可切换 1m / 5m / 30m）、体积 >= 8 KB
        │      Magic Bytes 识别（不看扩展名）：
        │        - GIF:  GIF87a / GIF89a
        │        - WebP: RIFF....WEBP（解析 VP8X/ANIM 标记是否动图）
        │        - PNG:  89 50 4E 47 0D 0A 1A 0A
        │        - JPEG: FF D8 FF
        │        - AVIF/HEIF: ftypavif / ftypheic / ftypmif1
        │      按 mtime 倒序 + (size, head/tail hash) 去重，过滤小图标
        │
        └─► 3. 小窗极简预览与导出 UI
               - 顶栏：当前识别目标 App 名 + 筛选（全部 / 动图 / 大图）+ 时间窗口 + 刷新按钮
               - 网格：原比例缩略图 + 动图直播 + 角标（GIF / WEBP动 / PNG / JPG + 宽×高 + 大小）
               - 动作：一键无损存相册（MediaStore 写入 Pictures/MediaGrabber/，保持原字节不重编码）、
                       系统分享（FileProvider）、复制图片、可选「保存后自动关闭小窗」
```

#### 2. 关键工程细节

- **单次 Root 批量扫描**：用单次 `su` 调用执行轻量 shell/内置小型二进制或 `find + head` 管道汇总候选清单，再按需通过流读取选中文件字节，避免每个文件 fork 一次 `su` 导致卡顿。
- **手机端零垃圾残留**：缓存预览仅读入应用私有内存/LRU 缓存（关闭时清理）或 `ContentResolver` 流式写入最终相册目标，**绝不往 `/data/local/tmp` 或 `/sdcard` 根目录倾倒临时扫描文件**。
- **小窗体验优化**：
  - `AndroidManifest.xml` 保持默认 `resizeableActivity="true"`，确保被 vivo 侧边栏小窗名单识别。
  - 提供手动「重新扫描底层 App」按钮（兼容小窗未关闭时切换底层内容后再次抓取，或再点侧边栏只触发聚焦动效的场景）。
  - 适配折叠屏内外屏 DPI 与小窗尺寸。

#### 3. Phase 1 验收标准

- [ ] 0 Hook 安装即用，可在 vivo 侧边栏编辑界面添加并拖动至「即刻图文」紧旁边。
- [ ] 点击侧边栏小窗启动到展示候选原图/原 GIF 耗时 `< 1.5s`（热点目录命中 `< 500ms`）。
- [ ] 在至少 4 个高频 App（如小红书、B 站、微信/QQ、X 或 Telegram）上实测可无损捞出原分辨率图片与可播放的原始 GIF 动图。
- [ ] 导出文件 SHA-256 与目标 App 缓存原文件完全一致（无二次压缩）。
- [ ] 手机无额外临时文件残留，退出后 0 后台进程/服务驻留。

---

### Phase 2（可选升级）：原生侧边栏 `CONVENIENT_TOOL` 工具槽位（无小窗）

**触发条件**：Phase 1 已稳定好用，但希望彻底去掉 vivo 小窗标题栏与边框，像「即刻图文」「小V记忆」一样点一下原地弹出无框悬浮选图浮层。

#### 1. 增量改造清单（复用 Phase 1 同一份 APK）

1. **声明原生工具 Service**：
   - `AndroidManifest.xml` 注册 `SideDockToolService`（`android:exported="true"`），配好图标与名称（如「捞原图」）：
     ```xml
     <service
         android:name=".sidedock.SideDockToolService"
         android:exported="true"
         android:icon="@mipmap/ic_launcher"
         android:label="@string/tool_label">
         <intent-filter>
             <action android:name="com.vivo.card.intent.action.CONVENIENT_TOOL" />
         </intent-filter>
         <meta-data
             android:name="com.vivo.card.metadata.CONVENIENT_TOOL"
             android:value="sidebar_media_grabber" />
         <meta-data
             android:name="com.vivo.card.metadata.CONVENIENT_TOOL_AREA"
             android:value="TOOL" />
     </service>
     ```
2. **实现 1 方法 AIDL 契约**：
   - 返回 `Binder` 实现 `com.vivo.sidedockplugin.IConventientToolService`（`TRANSACTION_isSupport = 2` 返回 `true`）。
   - `onBind(Intent intent)` 检测到 `action == "com.vivo.card.intent.action.CONVENIENT_TOOL_CLICK"` 时，立即拉起无框悬浮窗（`TYPE_APPLICATION_OVERLAY`，或透明半屏 BottomSheet）并复用 Phase 1 的扫描与展示模块。
3. **极薄 libxposed 放行 Hook（约 20 行）**：
   - 模块采用 modern libxposed API 102（沿用 `original-modules/origin-tweaks` 规范），`scope.list` 仅声明 `com.vivo.upslide`。
   - 严格守卫：`if (!"com.vivo.upslide".equals(param.getProcessName())) return;`
   - Hook `android.app.ApplicationPackageManager.checkPermission(String permName, String pkgName)`（位于 bootclasspath，**无需追踪 `createPackageContext` 动态 ClassLoader**）：
     - 仅当 `"com.vivo.sidedockplugin.permission.CONVENIENT_TOOL".equals(permName)` 且 `OUR_PACKAGE.equals(pkgName)` 时返回 `PackageManager.PERMISSION_GRANTED`（`0`）。
     - 其余一切调用原样放行，全程 `try/catch` 兜底。

#### 2. Phase 2 风险与安全边界

- **`com.vivo.upslide` 风险评估**：虽然 `com.vivo.upslide` 是 `uid=1000` + `PERSISTENT` 进程，但**不是 `system_server` 本身**，崩溃不会导致内核硬重启或丢失临时 Root（仓内 `forks/OriginCeiler`、`forks/OriGoogle` 也常规 Hook 该进程）；但因它负责边缘返回与侧边栏手势，Hook 必须极简、无 I/O、无异常透出。
- **降级兜底**：即便未来系统更新或关闭 LSPosed 模块，APK 本身仍保留 Phase 1 的 `MainActivity`，随时可无缝退回零 Hook 小窗模式使用。

---

## 3. 已知局限与设备操作红线

### 已知局限（提前明确预期）

- **私有加密缓存**：若个别 App 将缓存图片做了私有字节异或/加密落盘（例如部分旧版微信聊天 `.dat` 机制），标准 Magic Bytes 扫描无法直接识别；Phase 0 摸底后可按需在工具内增加只读解码器（仍无需 Hook 目标 App）。
- **纯内存流图片**：若某 App 图片流全程只解码到内存/GPU 纹理而不落任何磁盘缓存，纯缓存直捞无法获取，此类极少数场景仍交由原生「即刻图文」兜底。

### 操作红线（遵守根 `AGENTS.md` §1）

1. **设备不是草稿纸**：所有摸底输出、测试 APK、抓图与日志一律落回本机 `cache/`，禁止往手机写临时文件。
2. **安装三条件**：未来安装/更新 MVP APK 时，必须确认屏幕已解锁、用户在场、仅用 `adb install`，绝不用 root `pm install` 绕过 `PackageInstaller`。
3. **Phase 2 生效守则**：若进入 Phase 2 启用 `com.vivo.upslide` Hook，不擅自执行 `force-stop` / `kill` / 软重启，每次非只读动作必须先向用户说明「做什么 + 影响 + 风险」并获当次明确授权。
