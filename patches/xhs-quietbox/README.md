# 静盒（QuietBox）× 小红书 适配记录

更新日期：2026-11-22

## EX-03 分享面板补回 QQ（实验，2026-11-22 失败，未交付）

Play 9.55.1 把 QQ/微博平台实现删了，面板构建时过滤；设备缓存的服务端配置（`config_product.xml`）里 `qq_friends`/`qq_zone`/`weibo` 都在，QQ/微信/微博 App 也都装着。定位到的注入点：

- 面板条目实例化：`l0c.g#e(List<ShareItemConfig>, Function0)`（`l0c.g#s(scene)` 读配置）；
- 最终列表：`uyb.n0#c(Context, List)List`；条目工厂 `m0c.f#c(String,String,String,String,Z,I,Object)jj7.l`；
- 点击回调：`uyb.y#Z1(String,int)` / `uyb.y#v1(String,View)`；宿主 `IShareProxy` 已无 QQ/微博方法。

实验包 `dist/quietbox-0.9.31-xhs9.55.1-exp.apk`（78 锚点，EX-03 注入条目 + 系统 `ACTION_SEND` 到 `com.tencent.mobileqq`）已装上设备但**从未生效**：

- `10:06:02` 唯一一次加载是 `loaded process=com.xingin.xhs:longlinkNew`（子进程）；主进程三次 force-stop 重启均无 `VectorModuleManager: Loading module` 记录。
- `vector-cli modules disable/enable` 后仍不注入；之后连知乎新启动也没有任何模块加载 → **Vector 3080 的注入对新建进程失效**（vectord 单进程存活、API 102、scope 正常）。
- 分享面板在主进程，hook 一次未触发。需要 zygote/daemon 级别恢复才能再试（属红线，未动）。

源码已全部回滚到 9.55.0 适配；设备模块停在 0.9.31(45)，EX-03 默认开 —— 若日后注入恢复，面板会多一个 QQ 图标（点击走系统分享，未验证）。要清掉需出 0.9.32/46（EX-03 默认关）覆盖安装。

## 9.55.0 国内包适配（0.9.30，待上机）

国内渠道目前最高就是 **9.55.0 / 9550807**（9.55.1 只有 Play 渠道发了）。可用包是应用汇的 arm64 版：
`work/host-9.55.0-appchina/xhs-9.55.0-appchina.apk`（195,892,914 B、25 dex、160 个 arm64 so、签名 `f375f0f6…c121`、含 getui/hms/xiaomi push 与 QQ/微博/微信分享 SDK）。
应用宝同版本是 `armeabi-v7a` 单 ABI，本机装不上，已弃用。

适配产物：**`dist/quietbox-0.9.30-xhs9.55.0.apk`（versionCode 44）**，签名仍是自有 quietbox-port 证书；锚点 74/74 精确命中（[verify](work/host-9.55.0-appchina/verify-0.9.30.json)）。

定位方法（可复现，脚本在 `work/host-9.55.0-appchina/match/`）：混淆类名在相邻 build 间大幅漂移，靠四路证据逐条落实：

1. `dump_inventory.py` 导出整包类/方法/字段索引（9.55.1 与 9.55.0 各一份）；
2. `align_by_strings.py` 用**方法体字符串常量**做跨包对齐（如 `insertMFNoteItemBean`、`silentRefresh`、`appendResponseNoteWithCache`），多数锚点直接命中唯一方法；
3. `refs_of.py` / `search_refs.py` 用**字段/方法引用**消歧（如 `MediaSaveConfig#d()` 读 `disableWaterMark` → 9.55.0 的 `c()`；`FunctionSwitch#e()` 读 `type` → `d()`；`ContactsSyncCoordinator#d(Application)` 是假匹配，真身是 `#e(Application)`）；
4. 仍不确定的类用 jadx 单类反编译比对方法体（`e79.l#d/k` → `pa9.l`，`vl2.h#a(I)Z` → `pm2.h`，`nm2.f#b` → `nx9.j#f`）。

主要映射：`n4d.m0`→`smd.m0`（i0/U/G0/I0/v0）、`qdd.i`→`yvd.i#b`、`r4d.f#a`→`wmd.f#b`、`h4d.t#Id`→`mmd.t#Zd`、`lxd.g0#b`→`bge.i0#b`、`wm0.g0#o`→`am0.g0#r`、`rr.h#h/j`→`qq.h#b/f`、`ll6.o#n`→`nn6.p#o`、`z0a.o#invoke`→`r4a.o#invoke`、`em9.j`→`sp9.i`、`wa2.q#a`→`cb2.r#a`、`ClipboardMonitor#setPrimaryClip`→`heb.e#b`、`UpdateDialogActivity#Yd`→`#se`。

换包方式（**2026-11-22 实测被拒，不可用**）：`adb install -r -d` 报 `INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 9550807 is older than current 9551802`。`adb -d` 只对 debuggable 包有效，同签名 release 包也不行（`pm install --force-downgrade` 在 Android 17 不存在，`-d` 同样受限）。

要装国内包，只能等 versionCode ≥ 9551802 的新版本（普通 `install -r` 即可），否则只剩卸载重装 / root `pm install -d` 两条路，后者按 §1.0 红线禁止，前者丢数据 + `PACKAGE_*` 广播风险。

装新宿主后 `adb install -r dist/quietbox-0.9.30-xhs9.55.0.apk`。两处都需亮屏 + 用户点确认。

## 宿主 9.55.1 是 Google Play 包（2026-11-22 核对）

装机 `9.55.1 / 9551802` 与留档 `9.54.1 / 9541801` 同包名、同签名（`f375f0f6…c121`），但 9.55.1 是 Play 渠道包：

- 包内判定串 `isGooglePlay=true isOverSeaUser=`、`GooglePlay build, skip request geo data`；manifest 有 Finsky `BIND_GET_INSTALL_REFERRER_SERVICE`、Play Billing、GMS Ads App ID、C2DM。
- 国内推送 SDK 清零（个推/极光、HMS 38→5、mi/vivo/oppo/meizu、`com.xingin.push` 全无），只剩 `com.xingin.push.fcm`。
- QQ、微博整套 SDK 删除：`com.tencent.connect.share.QQShare`/`QzoneShare`、`com.tencent.tauth`、`com.sina.weibo.sdk.*`、`QQShareSpiProxyImpl`/`WeiboShareSpiProxyImpl` 均不存在，分享面板因此没有 QQ/微博入口；微信 SPI 代理和 `wxapi.WXEntryActivity` 仍在。
- 新增 Facebook SDK、Instagram/Line/Messenger/Telegram/WhatsApp 海外渠道、支付宝 Google Pay 适配。
- base.apk 179 MB → 144 MB，`lib/` 条目 161 → 0（so 走 ABI split），dex 23 → 21。

QQ 分享缺失属宿主裁剪，模块无法拦截。计划换成国内渠道 9.55.1（versionCode 预计 9551801，需 `adb install -r -d` 降级覆盖，同签名保数据）；到手后先 `tools/probe_anchors.py` 审计、再 `tools/recover_anchors.py` 重定位，出新的静盒包。在此之前静盒保持 0.9.29。

## 9.55.1 全量适配（0.9.29）

[静盒 0.9.29 / 43](dist/quietbox-0.9.29-xhs9.55.1.apk) 的整包适配版本为小红书 `9.55.1 / 9551802`，覆盖 27 个可用开关。基础 APK 中 74 个方法锚点和 6 个辅助类全部精确命中。适配结果需要上机核对挂载和功能表现。[DEX 审计](work/device-xhs-9.55.1-9551802/quietbox-0.9.29-audit.json)

| 范围 | 新版入口和约束 |
| --- | --- |
| UI-06、UI-07、UI-08 刷新 | `qdd.i#b` 写回首页刷新信号。`r4d.f#a(r4d.k)` 发射空列表处理冷启动首屏。`n4d.m0#E0` 和 `#s0` 分别处理后台静默刷新与半小时后的整页刷新。 |
| UI-06 系统返回补漏 | 「阻止返回自动刷新」在可见刷新入口仅跳过 `SYSTEM_BACK_REFRESH`，保留手动下拉和分页。真机验收看 `ui78-diag.log` 的 `UI-06 visibleRefresh ... source=SYSTEM_BACK_REFRESH decision=SKIP`，并确认返回不换内容、手动下拉仍可用。 |
| AD 广告及卡片 | 重定位开屏、首页广告插入、列表追加和刷新、分页、搜索结果、图文和视频广告条、广告落地页外跳。直播、带货和投票卡共用新版列表入口。 |
| UI-02、UI-04 弹窗 | 通知引导换新版签名。更新提示挂 `UpdateDialogActivity#Yd`，该方法是 `onResume` 调用的更新结果订阅入口。 |
| PV 隐私 | 通讯录同步精确匹配两种 SPI 签名和协调器入口，三参 SPI 通过新版 `d3.b#b()` 完成回调。剪贴板使用带 `ClipData` 的宿主写入入口和复制链接收口。 |
| EN 保存 | 对齐静态图片合成、保存入口、实况图视频分支、水印开关、长按菜单和 `FunctionSwitch` 下载类型。原图保存共用新版宿主保存入口。 |
| EX 扩展 | 对齐详情仓库本地缓存映射、AB 常规和只读一次入口，以及 ConfigCenter 字符串入口。 |

验收在小红书主进程的新会话内读取 `status_report.xml` 的 `reg`、`skip`、`hit`。冷启动、手动下拉、视频回首页和后台返回按不同路径分别确认。`ui78-diag.log` 每行包含进程 PID，能区分不同轮次。9.54.1 的完整适配包留在下方历史记录。

## 9.55.1 冷启动适配（0.9.28）

- 真机在 2026-11-21 14:25 升到小红书 `9.55.1 / 9551802`。静盒 0.9.27 在 17:25 的日志里只记了旧缓存加载器 `loaderGate` 的一次 `SKIP`。21:24:56 冷启动的主进程 PID 22917 已有 LiquidTab 279 落盘，静盒的 `ui78-diag.log` 停在 17:25。Vector 中静盒启用且小红书在作用域内，系统包状态显示静盒 `stopped=true`。冷启动验收先打开一次静盒，再按新小红书 PID 取证。
- 9.55.1 的 `HomepageSpiImpl#loadExploreNotes` 经 `y4d.c#invoke`、`y4d.e#d` 调用 `r4d.f#a(r4d.k)`，由该执行器调用 `ExploreService`。`r4d.k` 仍有 `refreshSource`、`noteIndex`、`cursorScore`、`launchScenario`。0.9.28 按确切 JVM 签名挂载该执行器，冷启动首屏命中后由宿主 classloader 构造 `Observable.just(emptyList())`。手动下拉与分页走原请求。9.54.1 仍使用 `djd.f#b(djd.k)` 及原有兜底。
- [0.9.28 / 42 Release](dist/quietbox-0.9.28-xhs9.55.1-ui07.apk) 于 21:43 经用户授权同签名覆盖成功，装机哈希与产物一致。验收查看 `ui78-diag.log` 同一 PID 的 `requestGate installed`、首屏 `decision=SKIP` 和随后下拉 `decision=proceed`，并核对真实画面。9.55.1 的通用锚点审计 35/74 命中，`UI-08` 的旧锚点已缺失。
- 打开静盒后冷启动小红书，新 PID 19669 的落盘记录在 401 ms 对 `r4d.k` 的 `INITIAL_REFRESH` 生成替代结果并记 `SKIP`，随后在 1426 ms 放行 `LOAD_MORE`。
- [9.55.1 全功能兼容性核对](QUIETBOX-9.55.1-AUDIT.md) 记录 30 项的真机挂载、命中和静态失配情况。

## 9.54.1 交付记录（源码构建，2026-11-21 上午）

- 宿主：`com.xingin.xhs` **9.54.1 / 9541801**（2026-11-17 前后自行升级；此前为 9.54.0）。
- 模块上游：源码迁到 CodeFloe（`quiet-tools/quietbox` + 复合构建依赖 `quiet-tools/xposed-core`），
  main = **0.9.13 / versionCode 27**，锚点 70 项，自带 9.54.1 适配与
  `VersionGate`（fail-open + 横幅）。GitHub `Xposed-Modules-Repo` 只是发布镜像（ APK 发布停在 0.9.12）。
- **构建方式改为源码编译**：[`build-src.sh`](build-src.sh)（克隆/FF 拉取上游 → 应用本地 overlay →
  `assembleRelease`（R8）→ 验签/对齐/锚点一致性 → 拷入 dist，`--audit` 顺带对机内宿主 APK 审计）。
- 我们的 overlay 只有两件事：① 用 [`.signing/quietbox-port.keystore`](.signing/quietbox-port.keystore)
  签名（对已装适配版 `install -r` 原地升级，不触发卸载广播）；② `release-trust.properties` 的
  证书指纹换成我们的 `dae30a0a…5f97f`，自编译包不弹「非官方签名」警告（该校验本身只警示不阻断）。
- 产物：**`dist/quietbox-0.9.27-xhs9.54.1.apk`（versionCode 41，本地号 0.9.27，2.9 MB）**。
- **已安装**：2026-11-21 `adb install -r` Success（versionCode 41）。
- 锚点离线审计：上游 70/70 命中本机 9.54.1 `base.apk`（`work/anchor-audit-9.54.1-upstream.json`；
  机内宿主经 root 中转分块拉取，md5 两端一致）。
- **本地扩展（patch `patches/ui78-coldstart-restore.patch`，`build-src.sh` 自动应用）**：
  - **UI-07 冷启动恢复不刷新**（默认开，**2026-11-21 重写，已真机验证**）：
    原实现拦 `zid.m0#u0`，实测冷启动**从不调用 u0**（只在手动滚动/刷新路径出现）。
    真实链路（0.9.19–0.9.26 探针实测 + 静态核实）：
    `djd.f#b(djd.k)` 是所有 feed 请求的唯一执行器（`chd.i#h` 参数组装的唯一调用方之一），
    冷启动首屏请求特征：`refreshSource=INITIAL_REFRESH`、`noteIndex=0`、`cursorScore=''`、
    **`launchScenario=1`**（唯一能区分"冷启动自动"与"手动下拉"的字段）。
    现闸门在 `djd.f#b`：冷启动窗口（120s）内匹配上述特征时返回
    `Observable.just(emptyList())` —— **不能返回 `Observable.empty()`**：
    doOnNext 不执行会让宿主停在"未加载"，下拉刷新永久禁用（0.9.25 实测）。
    发射空列表让整链走完（`kjd.e#a([])` 状态机正常收尾）而没有新内容可替换旧 feed。
    **反射必须用宿主 classloader**（`Class.forName(..., hostCl)`）：模块 CL 解析不到
    宿主 RxJava 类会抛异常被 catch 吞掉、静默 proceed（0.9.21–0.9.23 "日志写 SKIP、
    请求照发"的真凶）。
    附带同类闸门（兜底，实测未触发但保留）：`HomepageSpiImpl#loadExploreNotes`、
    `fgd.s#d`（HFNewCacheRequest）、`zid.m0#u0`（COLD_START+INITIAL_REFRESH）。
  - **UI-08 后台回来不刷新**（默认开）：`zid.m0#G0(n2.g, e2)` 拦截，
    当 `g=SILENT_BACK_REFRESH || g=FEED_30MIN_HOT_LAUNCH_REFRESH` 时跳过。
    解决「长时间后台/子进程被杀后回来 → 自动刷新」。
  - 两个新功能与 UI-06 共用 `HomeFeedRefreshHooks`，注册在 `Hooks.java`，锚点审计 72/72 命中。
  - **冷启动内容变少的取舍**：跳过首屏请求后只显示本地恢复的上一屏（约 7–10 条），
    小红书本地只缓存"上次浏览的一小段"，全量历史在服务端、无本地归档。
    用户 2026-11-21 确认接受该取舍（想要新内容自己下拉）。
- 0.9.9→0.9.13 变更全部为增量（9.54.1 适配、UI-06 阻止返回刷新默认开、EN-04 条目门、
  诊断 socket 通道、EN-05 保存原图默认关）；旧开关 PV-01/02/03、EN-01/03/04、EX-01、EX-02 关
  的 remote prefs 键名未变，`install -r` 后保持。

### 本机构建前置（一次性）

- SDK 需要实体 `platforms/android-37` 且 `source.properties` 为整数
  `AndroidVersion.ApiLevel=37`：由 `android-37.0` 复制并把 `37.0` 改 `37` 得到。
  junction 或小数 ApiLevel 都会让 AGP 8.13 报 `Failed to find target with hash string 'android-37'`。
- 两份克隆都各自需要 `local.properties`（`build-src.sh` 自动写）。
- CodeFloe 的 git 走 schannel 会握手失败，脚本统一带 `git -c http.sslBackend=openssl`。
- 从手机拉大文件（>100MB）今晚链路不稳：用 [`tools/pull_chunked.sh`](tools/pull_chunked.sh)
  （4MiB dd 分块 + 尺寸校验重试 + 本地拼装），直连 `adb pull`/`exec-out cat` 会随机截断。

## 历史交付（二进制移植 0.9.9 × 9.54.0，已被上面取代）

- 当时宿主 9.54.0 / 9540805（APK SHA-256 `200d09cf…69e9`），上游 0.9.9 / 23 原版仅支持 9.53.0。
- 方式：以原版 APK 为底座，替换根目录 `anchors.json` 为 9.54.0 profile + DEX 版本串补丁
  （`9.53.0→9.54.0`，含 DEX 校验修复）+ 删 `baseline.prof(m)` + 重签名。
- 已装适配 APK SHA-256：`60a4e8a3…29a0f2`；验收链 = `verify_apk.py` 载荷白名单 + 67 锚点审计 + 真机 hit。
- 该链路对 9.54.1 宿主已失效；`build.sh` + profiles 工具链保留为**上游未适配新宿主时的备用**，
  probe/transition 工具仍被源码路线的 `--audit` 复用。

## 原版失效原因（9.53.0 → 9.54.0 档案）

原版 `anchors.json` 面向 **9.53.0 / 9530807**，但运行时只读取版本字段而不做兼容分派；每个 Hook 仍按精确类名、方法名和完整 JVM 签名安装。

对本机 9.54.0 的 67 项锚点审计结果：

| 状态 | 数量 |
|---|---:|
| 完全匹配 | 37 |
| 类存在、完整签名漂移 | 9 |
| 类名漂移 | 18 |
| 方法迁移/改名 | 3 |

因此原模块表现为“部分功能可用、部分静默失效”，只改版本号没有适配作用。

JADX 在 `s2.d.m` 显示的返回类型守卫疑似反转，但 DEX 指令确认原逻辑正确：普通 Hooker 放行，`m` 要求 `void`，`n` 要求 `boolean/Boolean`。**没有修改该共享 Hook 安装器。**

## 9.54.0 profile

[`profiles/anchors-9.54.0.json`](profiles/anchors-9.54.0.json) 由 [`tools/port_anchors.py`](tools/port_anchors.py) 显式生成，共 31 项经审查替换；生成后 **67/67 锚点、6/6 辅助类精确命中**。

主要映射：

| 功能 | 9.53.0 → 9.54.0 |
|---|---|
| 开屏懒加载 | 参数 `bee.d → uge.e` |
| 广告记录 | 参数 `me7.r → hf7.r` |
| 信息流容器 | `hgd.m0 → yid.m0` |
| 信息流追加/刷新 | `S/C0 → T/E0` |
| 第二位广告插入 | `g0 → h0`；`h0` 方法体为实际 `insertMFNoteItemBean`，排除点赞/取消点赞同形方法 |
| 分页列表 | `bgd.s#Sd → sid.t#Xd`，参数 `bgd.b → sid.b` |
| 广告外跳兜底 | `j9e.i0 → cce.i0` |
| 搜索广告 | `ll0.g0 → yl0.g0`；参数同步更新为 `zn0.p / m6c.h` |
| 详情广告 | 参数 `z10.b → j20.b`、`o50.a0 → y50.a0` |
| 评论带货 | 参数包 `g1.* → h1.*` |
| 复制链接 | `h8b.e → rab.e`、`m92.r → ca2.r` |
| 图片水印 | `jm8.j/i → on8.j/i` |
| 实况保存 | `bt9.j#b → ou9.j#c`；`kl9.i → um9.i`、`zk2.h → pl2.h`、`rl2.e → hm2.e`，首参数为 `j0a.m` |
| 强制保存类型门禁 | `FunctionSwitch#c → d`：9.54 中 `a=enable`、`b=reason`、`c=toast`、`d=type`；旧版把 `c` 当 type，导致 `image_download/video_download` 永远匹配失败 |
| 不可用笔记 | `g0a.o → t1a.o`，确认为 `NoteDetailRepository` 的 `.map(...)` lambda |
| AB 读取 | `oq.h → qq.h`；普通读取 `b`，只读一次 `e → f` |
| ConfigCenter | `cl6.p → xl6.p` |
| 更新弹窗补充入口 | `he → re`，当前为 onResume 更新信息订阅入口 |
| 评分/通知/通讯录参数 | `a2.b→b2.b`、`co.c→eo.c`、`p1.a→q1.a`、`o39.n→v49.l` |

完整审计：

- 原版：[`work/anchor-audit-9.54.0.json`](work/anchor-audit-9.54.0.json)
- 适配后：[`work/anchor-audit-9.54.0-ported.json`](work/anchor-audit-9.54.0-ported.json)
- 结构候选及方法体证据：[`work/anchor-candidates-9.54.0.json`](work/anchor-candidates-9.54.0.json)、[`work/jadx-9.54.0/`](work/jadx-9.54.0/)

当前 profile 由 transition 生成：

```sh
python -I xhs/tools/port_anchors.py \
  xhs/work/anchors.json \
  xhs/profiles/transitions/9.53.0-to-9.54.0.json \
  xhs/profiles/anchors-9.54.0.json --force
```

transition 是纯数据，包含 `from`、`to`、`notes` 和 `changes`。每个 change 只接受经审查的 `c/m/s`（可选 `reason` 仅供记录），不会自动应用 `recover_anchors.py` 的候选。

## 宿主再升版时的流程

1. **先看上游**：CodeFloe `quiet-tools/quietbox` 是否已适配新版本（CHANGELOG / anchors.json 的
   `appVersion`）。已适配 → 直接 `sh xhs/build-src.sh --audit`（克隆会 FF 拉到最新），无锚点工作量。
2. 上游未适配时才走二进制移植备用链：`probe_anchors.py` 差异 → `recover_anchors.py` 候选 →
   `run_xref.py`/JADX 审语义 → `profiles/transitions/` 映射 → `port_anchors.py` 生成 profile →
   审计到全命中 → `build.sh`。真机验证必须看到对应功能 `hit`，`registered` 单独不算通过。
3. 分块拉取新宿主 APK：`tools/pull_chunked.sh <remote> <local> <total_bytes>`（不稳链路必备）。

### UI-07/08 专项：宿主升版后怎么复验/再适配（2026-11-21 定稿）

**先跑审计**：`python -I xhs/tools/probe_anchors.py <新宿主base.apk> <work/upstream-quietbox/app/src/main/resources/anchors.json> --json ...`，
本战线新增的三个锚点必须仍命中：

| 锚点 key | 目标 | 作用 | 9.54.1 状态 |
|---|---|---|---|
| `cold_start_refresh_gate` | `zid.m0#u0` | 兜底闸门（实测未触发） | 命中 |
| `background_silent_refresh` | `zid.m0#G0` | UI-08 静默刷新 | 命中 |
| `cold_start_cache_request` | `fgd.s#d` | 兜底闸门（实测未触发） | 命中 |
| `home_feed_cold_channel` | `zid.m0#C0` | 捕获 m0 实例（给闸门读缓存列表用） | 命中 |

**关键：UI-07 真闸门 `djd.f#b` 不在 anchors.json 里**（本版本按类名+方法名+返回类型动态挂载，
见 `HomeFeedRefreshHooks.installColdStartRequestGate`）。升版后若 `djd.f` 类名/方法名漂移，
锚点审计**不会报错**，必须按下面「真链路重定位」重跑探针。升版离线自查一行命令：

```sh
python -I xhs/tools/run_xref.py <新宿主base.apk> method "djd.f#b"   # 应仍列出 chd.i#h 调用方
python -I xhs/tools/run_xref.py <新宿主base.apk> method "chd.i#h"   # 应仍列出 djd.f#b 调用方
```

**真链路重定位 SOP（下次宿主改版照做，一次冷启动出结果）**：

1. 临时把探针装回去：`installProbe()`（代码模板见本文末「探针模板」），候选入口至少包含
   `djd.f#b`、`chd.i#h`、`zid.m0#C0`、`tid.t#Jd`、`kjd.e#d`、`kjd.e#a`、`zid.m0#D`。
2. `adb install -r` 后开一次静盒 App，再冷启动小红书（划掉 → 等 2-3 秒 → 重开）。
3. 10 秒内拉落盘诊断（chatty 会吞 logcat，**只认落盘**）：
   `adb exec-out su -c 'cat /data/data/com.xingin.xhs/files/ui78-diag.log'`（外置目录兜底）。
4. 读第一笔 feed 请求的 `refreshSource / noteIndex / cursorScore / launchScenario`，确认闸门
   匹配串仍然有效；若请求源枚举改名（`n2.g` 常量变化），同步改 `reqText` 匹配串。
5. 改完拆掉探针（`installProbe` 整块删除）出正式版；探针只存在于临时版本，不随正式包发布。

**判别字段速查**（djd.k.toString()）：

| 场景 | refreshSource | noteIndex | cursorScore | launchScenario |
|---|---|---|---|---|
| 冷启动自动首屏 | `INITIAL_REFRESH`（偶见 `WK_CACHE_REFRESH`） | 0 | `''` | **`1`** |
| 手动下拉刷新 | `PULL_REFRESH` | 0 | `''` | `null` |
| 上滑翻页 | `LOAD_MORE` | >0 | 有时间戳 | `null` |
| 冷启动兜底路径（未触发） | `INITIAL_REFRESH` | 0 | `''` | `1` |

**红线**：跳过时返回 `Observable.just(emptyList())`，**绝不能返回 `Observable.empty()`**
（doOnNext 不执行 → 宿主停在"未加载" → 下拉刷新永久禁用，0.9.25 实测）。反射一律用
`hostCl`（宿主 classloader），否则 `ClassNotFoundException` 被 catch 吞掉、静默放行。

## UI-07 攻坚历程（2026-11-20 → 21，保留失败判据别重复踩）

| 版本 | 动作 | 结果 |
|---|---|---|
| 0.9.13 (v27) | 原闸门在 `zid.m0#u0`（COLD_START+INITIAL_REFRESH+缓存非空） | 不生效——**冷启动从不调用 u0** |
| 0.9.14 (v28) | 落盘诊断（绕过 logcat chatty） | 发现主进程模块其实已加载，零日志是 chatty 吞的 |
| 0.9.15 (v29) | 加 `fgd.s#d`（HFNewCacheRequest）闸门 | 未触发——该路径冷启动不走 |
| 0.9.16 (v30) | 六路探针（f0.q2/w2/r2、m0.C0、tid.t.Jd、chd.i.h） | 抓到 `chd.i.h` 第一笔请求参数 + `kjd.e.d` 调用栈 |
| 0.9.18–0.9.19 | 加 `m0#D`、`chd.i.h` 栈探针 | 确认真链路：`kjd.e.d → djd.f.b → chd.i.h → ExploreService` |
| 0.9.20 (v34) | 闸门挂 `djd.f#b`，返回 `Observable.empty()` | 日志 SKIP 但请求照发（classloader 假象） |
| 0.9.21–0.9.23 | 改用 toString 解析参数、挂 HomepageSpiImpl | 同上假象；`loadExploreNotes` 实测未被调用 |
| 0.9.24 (v38) | **`hostCl` 修 classloader** | **冷启动真不刷新了**；但下拉刷新被一起拦、内容变少 |
| 0.9.25 (v39) | 加 `launchScenario=1` 判别 | 下拉仍坏——**空 Observable 让状态机停在"未加载"** |
| 0.9.26 (v40) | 改发射 `emptyList()` | 冷启动不刷 + 下拉可用，用户确认「貌似修好了」 |
| 0.9.27 (v41) | 拆探针出正式版 | 功能同 v40，已验收 |

**三个最贵的教训**（跨战线通用，详见 [`../HOOK-DEBUG-PLAYBOOK.md`](../HOOK-DEBUG-PLAYBOOK.md)）：
① 日志"SKIP 却照发"= 钩子里的反射/返回值抛异常被 catch 吞 → **catch 必须落盘**，反射必须宿主 CL；
② 跳过 RxJava 链时返回值的**形态**决定宿主状态机（empty vs just(emptyList)）；
③ jadx 显示的混淆字段名运行时 `getDeclaredField` 可能取不到 → 参数判定解析 `toString()`。

## 探针模板（复用到其他 App/hook 时直接抄）

```java
// 1) 落盘诊断（绕过 logcat chatty；hook 安装时 Application 未创建则先缓冲后补写）
private static final Object DIAG_LOCK = new Object();
private static final StringBuilder DIAG_PENDING = new StringBuilder();
private static volatile Context diagContext;
static void diagFile(String line) { /* 见 HomeFeedRefreshHooks：缓冲 + filesDir/外置目录兜底 */ }

// 2) 通用探针：按类名+方法名挂所有重载，只记参数不干预
private static void probe(XposedModule module, ClassLoader cl, String cls, String name, int cap) {
    Class<?> c = cl.loadClass(cls);
    for (Method m : c.getDeclaredMethods()) {
        if (!m.getName().equals(name)) continue;
        final String label = cls + "#" + name + "/" + m.getParameterCount();
        m.setAccessible(true);
        module.hook(m).setId("PROBE#" + label)
              .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
              .intercept(new XposedInterface.Hooker() {
                  int hits;
                  public Object intercept(XposedInterface.Chain chain) throws Throwable {
                      if (++hits <= cap) {
                          StringBuilder sb = new StringBuilder("probe ").append(label);
                          for (int i = 0; i < chain.getArgs().size() && i < 3; i++)
                              sb.append(" a").append(i).append('=').append(shortArg(chain.getArgs().get(i)));
                          diagFile(sb.toString());   // 需要调用栈时在此追加 getStackTrace()
                      }
                      return chain.proceed();
                  }
              });
    }
}

// 3) 参考取栈：定位"谁发的请求"——在探针里追加栈，过滤框架帧
for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
    String cn = e.getClassName();
    if (cn.startsWith("com.quietbox") || cn.startsWith("io.github.libxposed")
            || cn.startsWith("java.lang.Thread")) continue;
    sb.append(cn).append('.').append(e.getMethodName()).append("<-");
}
```



## 备用：二进制移植构建（build.sh）

```sh
# 默认使用 profiles/anchors-9.54.0.json 和对应本地宿主 APK
sh xhs/build.sh

# 新版本示例
ANCHOR_PROFILE=xhs/profiles/anchors-9.55.0.json \
XHS_APK=xhs/work/device-xhs-9.55.0-9550805/base.apk \
PATCH_OLD=9.54.0 \
sh xhs/build.sh

# 覆盖安装同签名适配版
adb install -r xhs/dist/quietbox-0.9.9-xhs9.54.0.apk
```

构建只改：

1. 根目录 `anchors.json` 替换为目标 profile；
2. DEX 中共享的目标版本字符串按 `PATCH_OLD → profile.appVersion` 替换，并同步修复 DEX SHA-1、Adler-32 和 file_size 校验；
3. 移除 `assets/dexopt/baseline.prof` 和 `baseline.profm`：它们绑定旧 DEX 校验，会导致覆盖安装报 `The profile does not match the APK`；
4. 重新签名。

验收脚本确认：除上述内容外，其余非签名 APK 文件逐字节不变；APK v3 签名和 zipalign 均通过。

原签名私钥不在仓库中，因此第一次适配安装需要重装静盒；之后保持本密钥即可 `install -r`，不要再换密钥。

Vector 管理器的正确入口是：

```sh
am start -a android.intent.action.MAIN \
  -c org.matrix.vector.manager.LAUNCH_MANAGER \
  -n com.android.shell/.BugreportWarningActivity
```

重装后若静盒长期显示“正在连接框架”，先在 **Vector** 内将静盒关闭再开启，再覆盖安装同签名适配版并重新打开静盒。验收日志必须出现：

```text
QuietBox: service bound, api=102 replaced=false
```

不要把 LSPatch 的模块页面当成 Vector 状态。

## 真机验证（0.9.9 × 9.54.0 历史基线；0.9.13 × 9.54.1 装机后按同口径复验）

当时小红书主进程 PID 为 `26170`；强制保存复测时主进程为 `17612`。静盒 remote prefs 可用。原额外开关已恢复：PV-01/02/03、EN-01/03/04、EX-01；EX-02 保持关闭，`xsec_token` 仍保留。

| 功能 | 当前证据 |
|---|---|
| AD-01 开屏广告 | `registered · hit 2 · skip —`，含开屏占位拦截 |
| AD-02 信息流广告 | `registered · hit 3 · skip —`；日志有 `feedAdPagerFilter removed=1`、`removed=2` |
| AD-03/04/05/06/07 | 已注册、诊断 `skip —`；未主动触发对应广告场景 |
| AD-08/09/10 直播/带货/投票卡 | 三组追加/刷新/分页入口均注册并产生 `seen feedCard...`；当前样本 `modelType=note` |
| UI-01/02/03/04 | 已注册、诊断 `skip —`；未人为制造弹窗 |
| UI-05 自动播放 | `registered · hit 1 · skip —` |
| PV-01 截图检测 | 最终定位 `spc.j1` 并注册 `screenshotDetector`；对其他系统观察者的“未定位”日志是动态探测噪声 |
| PV-02 通讯录同步 | `registered · hit 2 · skip —`，命中 `coordinator.i/e` |
| PV-03 复制链接 | 剪贴板主入口和 String 兜底均注册；未改写剪贴板做副作用测试 |
| EN-01 水印策略 | 合成、保存和 getter 均注册；未写入相册验证 |
| EN-03 实况去水印 | 保存入口和 `pl2.h#a(1)` 开关均注册；未写入相册验证 |
| EN-04 强制保存 | 两类保存门禁均已命中并通过真机保存：`MediaSaveConfig#b` 与 `FunctionSwitch#a:image_download` |
| EX-01 不可用笔记 | `t1a.o#invoke` 已注册；未访问真实不可用笔记 |
| EX-02 AB 面板 | 67 锚点静态校验包含其 3 项；功能按原配置保持关闭，未做运行验证 |

诊断页中，已启用功能均为 `registered · skip —`，不再出现“锚点未找到”。

### 强制保存复测（11:56，主进程 `17612`）

9.54 修正了 `FunctionSwitch` getter 顺序：`a=enable`、`b=reason`、`c=toast`、`d=type`。profile 将 `force_save_function_type` 从旧版的 `c` 改为 `d` 后，真机日志出现：

```text
EN-04 hit MediaSaveConfig#b
EN-04 hit FunctionSwitch#a:image_download
```

用户对原先禁止保存的笔记重试，确认**保存成功**。同时 `MediaSaveConfig#b` 继续处理长按图片面板的 `disable_save` 门禁。

原模块的三项非版本问题保持原状，未冒充已适配：

- **AD-11**：原生/Predy 摇一摇检测，Java 层无精确挂载点；
- **PV-04**：直接跳过 Lancet 插桩方法体会断生命周期；
- **EN-02**：需求取消，小红书已自带自动静音。

## 工具

- [`build-src.sh`](build-src.sh)：源码构建主入口（拉上游 + overlay + 验证 + dist；`--audit` 带锚点审计）。
- [`tools/pull_chunked.sh`](tools/pull_chunked.sh)：不稳 adb 链路的分块大文件拉取（4MiB + 尺寸校验重试）。
- [`tools/probe_anchors.py`](tools/probe_anchors.py)：精确审计锚点及辅助类，按功能汇总。
- [`tools/recover_anchors.py`](tools/recover_anchors.py)：结构候选扫描，只供人工/xref 审查，不自动提升候选。
- [`tools/run_xref.py`](tools/run_xref.py)：在 `python -I` 下复用仓库 DEX xref 扫描器。
- [`tools/port_anchors.py`](tools/port_anchors.py)：把已审查的 transition JSON 应用到上一版 profile。
- [`tools/profile_info.py`](tools/profile_info.py)：供构建脚本读取目标/来源版本元数据。
- [`tools/patch_dex_version.py`](tools/patch_dex_version.py)：最小版本字符串补丁和 DEX 完整性修复。
- [`tools/verify_apk.py`](tools/verify_apk.py)：APK 变更白名单、profile、版本字符串和 DEX 完整性验收。
- [`profiles/transitions/`](profiles/transitions/)：每两个版本之间经审查的锚点映射。

> 本文件已合并原 `NOTES.md`（含 9.55.1 全量适配记录）与 `QUIETBOX-9.55.1-AUDIT.md`；上游源码位于 `../../forks/{quietbox,xposed-core}`，宿主 APK 位于 `../../cache/hosts/`，签名位于 `../../shared/signing/quietbox-port.keystore`。

---

## 静盒 0.9.28 × 小红书 9.55.1 兼容性核对（原 QUIETBOX-9.55.1-AUDIT.md）

# 静盒 0.9.28 与小红书 9.55.1 兼容性核对

2026-11-21 核对装机静盒 `0.9.28 / 42` 和小红书 `9.55.1 / 9551802`。安装包哈希与本地留档一致。静态审计覆盖小红书基础 APK 和唯一含 DEX 的 `split_sku_poi_df.apk`。静盒包内的 74 个旧锚点有 35 个精确匹配、22 个找不到类、9 个找不到方法、8 个签名变化。6 个辅助类仍在。[静态审计](work/device-xhs-9.55.1-9551802/quietbox-0.9.28-audit.json)

运行结果取自 21:44 冷启动后这一轮的静盒状态回传，快照于 21:48:49 更新。挂载表示静盒注册了对应入口，命中表示这一轮确实执行过对应处理。整项功能的实际表现仍取决于用到哪条入口。[会话快照](work/device-xhs-9.55.1-9551802/quietbox-0.9.28-status-snapshot.json)

30 个条目中，2 项已命中且未报缺入口，5 项已挂载待触发，7 项部分挂载，12 项现场报缺入口，AB 测试另有 3 个旧入口全部失配，3 项在当前模块源码中本来就是停用条目。

| 编号 | 功能 | 9.55.1 核对结果 |
| --- | --- | --- |
| AD-01 | 开屏广告跳过 | 部分挂载，命中 2 次。12/14 锚点精确匹配，懒加载和全屏开屏两个入口的签名变了。 |
| AD-02 | 信息流广告过滤 | 部分挂载，`handleFeedDataIfNeed` 命中 1 次。广告记录、插入、列表追加、刷新和分页共 5 个入口失配。 |
| AD-03 | 评论广告过滤 | 1/1 匹配且已挂载，等待评论广告路径触发。 |
| AD-04 | 广告外跳拦截 | `gotoExtApp` 已挂载，网页汇合入口失配。 |
| AD-05 | 搜索广告过滤 | 搜索结果过滤入口所在类缺失，现场报缺入口。 |
| AD-06 | 详情页广告条屏蔽 | 图文和视频的 `AdvertV2View` 入口都变了签名，现场报缺入口。 |
| AD-07 | 评论带货插入屏蔽 | 1/1 匹配且已挂载，等待带货内容触发。 |
| AD-08 | 直播卡过滤 | 追加、刷新、分页这三个共享入口均失配，现场报缺入口。 |
| AD-09 | 带货卡过滤 | 共用 AD-08 的三个入口，现场报缺入口。 |
| AD-10 | 投票卡过滤 | 共用 AD-08 的三个入口，现场报缺入口。 |
| AD-11 | 摇一摇跳转禁用 | 源码标记为停用。 |
| UI-01 | 评分引导弹窗屏蔽 | 2/2 匹配且已挂载，等待评分路径触发。 |
| UI-02 | 通知引导弹窗屏蔽 | 3/4 匹配且已挂载。弹窗主入口的签名已变化。 |
| UI-03 | 满意度调研条屏蔽 | 4/4 匹配且已挂载，等待调研路径触发。 |
| UI-04 | 更新弹窗屏蔽 | `onCreate` 已挂载，恢复时接收更新信息的旧入口缺失。 |
| UI-05 | 自动播放 | 4/4 匹配且已挂载，命中 1 次。 |
| UI-06 | 阻止返回自动刷新 | 视频回首页的刷新信号旧入口 `bsd.i#b` 所在类缺失，现场报缺入口。 |
| UI-07 | 冷启动恢复不刷新 | 0.9.28 的新入口已挂载并命中。该入口通过源码按签名选择，包内锚点表中的 3 个旧兜底在 9.55.1 上已失配。 |
| UI-08 | 后台回来不刷新 | 旧入口 `zid.m0#G0` 所在类缺失，现场报缺入口。 |
| PV-01 | 截图检测关闭 | 动态发现已注册检测器，等待对应路径触发。 |
| PV-02 | 通讯录同步关闭 | 部分挂载，生命周期和同步入口仍有注册。两个协调器方法变了签名。 |
| PV-03 | 复制链接净化 | 剪贴板写入和链接汇合入口都失配，现场报缺入口。 |
| PV-04 | 埋点抑制 | 源码标记为停用。 |
| EN-01 | 水印策略 | 合成、保存和配置读取的 3 个锚点均失配，现场报缺入口。 |
| EN-02 | 默认静音 | 源码标记为停用。 |
| EN-03 | 实况图去水印 | 4 个锚点均失配，现场报缺入口。 |
| EN-04 | 强制允许保存 | 部分挂载，仅回报 `MediaSaveConfig#b` 已注册。菜单项和功能类型判断有 3 处失配。 |
| EN-05 | 保存原图（宿主链路） | 依赖 EN-01 的保存入口 `watermark_save_entry`，现场报缺入口。 |
| EX-01 | 已删除笔记查看 | 详情仓库的数据映射入口失配，现场报缺入口。 |
| EX-02 | AB 测试面板 | 读取和覆盖宿主配置依赖的 3 个旧入口全部失配。这轮状态回传没有该项记录。 |

## 冷启动为何恢复

1. 14:25:51，手机记录显示 Google Play 更新了小红书。9.55.1 的首屏请求执行器搬到 `r4d.f#a(r4d.k)`。0.9.27 主闸门用的 `djd.f#b(djd.k)` 已经对不上，17:25 仍有旧缓存加载器的一次 `loaderGate … SKIP`，和下午偶尔能保住旧内容的表现一致。
2. 21:24 那轮小红书冷启动时，静盒 App 的包状态标为 `stopped=true`，这轮没有新静盒落盘记录。装上 0.9.28 后，21:44:30 打开静盒，21:44:33 小红书进入新进程 19669，新的闸门随之挂载。
3. 这个进程在挂载后 401 ms 拦截 `INITIAL_REFRESH`。首屏请求由宿主的 `Observable.just(emptyList())` 发射空列表，宿主可以完成加载状态，同时保留已恢复的内容。挂载后 1426 ms 的 `LOAD_MORE` 正常放行。0.9.28 的版本横幅仍按整套锚点表标注的 9.54.1 适配基线判断。[冷启动适配记录](NOTES.md#9551-冷启动适配0928)

