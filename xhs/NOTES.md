# 静盒（QuietBox）× 小红书 适配记录

更新日期：2026-11-21

## 当前交付状态（源码构建，2026-11-21）

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
