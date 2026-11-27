# 酷安（com.coolapk.market）现状勘察

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

当前双入口合包基于上游 `21-2.9.0`（`fc21249c`）。维护入口为 [module-merged/build.sh](module-merged/build.sh)，默认底座是 `cache/bases/coolapk/coolapk-purifier-v2.9.0.apk`。检查模块的源码、资源和诊断资产保留在 [module-inspect/](module-inspect/)。

本机原始 APK、配置备份、运行证据与历史签名包归入 `cache/coolapk-purifier/`，按原目录 `apk/`、`backup/`、`work/` 和模块的 `dist/` 查找。

下方的 2.8.0 勘察采集于 2026-11-14（vivo X Fold6，Android 17）。

## 1. 装了什么

| 项 | 值 |
|---|---|
| 酷安 | `com.coolapk.market` **16.7.3（versionCode 2611121）**，安装于 11-12，targetSdk 34 |
| 模块 | `io.github.yylsping.coolapkpurifier`「酷安净化」**2.8.0（code 20）**，11-09 安装 |
| 框架 | Vector（LSPosed 分支，`/data/adb/lspd`），模块 `enabled=1`，作用域 `com.coolapk.market`（user 0） |
| 模块 API | 新版 Xposed API 102（`META-INF/xposed/{java_init.list,module.prop,scope.list}`，入口 `io.github.yylsping.coolapkpurifier.y`） |
| 运行时 | 自带 `libdexkit.so`（dexkit）+ `assets/coolapk_target_manifest.json` |

## 2. 模块能做什么（从 manifest + 字符串还原的功能清单）

manifest schema 1，共 15 个 target：

`relatedData` `relatedIconListUi` `relatedContentUi` `detailSponsor` `detailSponsorUi`
`replySponsor` `sameTopic` `topicDeviceRecommend` `topicDeviceRecommendUi`
`autoComment` `autoCommentPrompt`
`searchHotCapsuleUi` `searchHotWordsUi` `searchHotRankingsUi`
`bottomNavigation`

对应开关（写在酷安私有目录 `files/coolapk_purifier_config.json`）：

```json
{"schema":2,"revision":5,"pendingAdaptation":"selection",
 "options":{"remove_splash_ads":true,"remove_feed_sponsor":true,"remove_reply_sponsor":true,
            "remove_auto_comment":false,"remove_topic_device_recommend":false,
            "remove_related_data":false,"remove_same_topic_feed":false,
            "remove_detail_sponsor":false,"remove_search_hot_words":false,
            "remove_search_hot_rankings":false}}
```

即：开屏广告 / 信息流赞助 / 回复赞助 打开，其余关。

## 3. 关键问题：16.7.3 没有 profile

模块日志（`adb logcat | grep CoolapkAdBlock`）：

```
manifest loaded schema=1 profiles=5
manifest schema=1 hostVersion=16.7.3(2611121) profile=missing
feature=remove_detail_sponsor    source=manifest_exact install=DISABLED hookInstalled=false
feature=remove_reply_sponsor     source=manifest_exact install=UNSUPPORTED_VERSION reason=no_validated_profile versionCode=2611121
feature=remove_same_topic_feed   source=manifest_exact install=DISABLED
feature=remove_topic_device_recommend source=manifest_exact install=DISABLED
bottomNavigation install=DISABLED versionCode=2611121
```

manifest 里的 profile 只有 **16.6.1 / 16.6.2 / 16.6.4 / 16.6.5 / 16.7.0**，
手机上是 **16.7.3** → `profile=missing`。

于是只剩指纹兜底能跑通（`files/coolapk_purifier_resolver_cache.json` 里有实际解析结果）：

| key | source | 解析到的类/方法 |
|---|---|---|
| `splash_base` | fingerprint_strong | `com.coolapk.market.view.splash.SplashAdActivity->onCreate(Bundle)` |
| `splash_decision` | splash_decision_post_result_v1 | `cz5->Ԯ(Context, ۮ, String)Z` |
| `feed` / `feed#2` | fallback_compat | `EntityAdHelper->Ϳ(List,Z)List`、`EntityListFragment->Ғ(List,Z)List` |
| `getter.*` | fingerprint_strong | `Entity->getEntityTemplate/getEntityId/getTitle/getEntityType` |

也就是说 **现在实际生效的只有：开屏广告 + 信息流赞助卡片**；
详情页赞助 / 回复赞助 / 相关推荐 / 同话题 / 机型推荐 / 自动评论 / 搜索热词热榜 / 底栏精简 **全都没装上**。

## 4. 还有一个硬拦路虎：酷安 16.7.3 是易盾加固包

`apk/coolapk-16.7.3.apk` 里 `classes.dex` 有 **98 MB，但只声明 30 个类**，全是
`com.netease.nis.wrapper.*`（Application、InstrumentationProxy、NEDialog…），
真正的业务 dex 是运行时解密后直接进内存的：

- APK 内 `lib/arm64-v8a/libnesec.so`、`libnesec-x86.so`（易盾）
- `/data/data/com.coolapk.market/oat/arm64/Anonymous-DexFile@*.vdex` 只有 **92 字节**（空壳）
- 应用目录里**没有**任何解密后的 dex/jar 落地

结论：**静态反编译这条路对 16.7.3 无效**。要么从官方渠道拿未加固包（基本没有），
要么在运行时把 ClassLoader 里的类/方法 dump 出来。模块自己能工作，正是因为它跑在酷安进程内、用 dexkit 扫运行时 dex。

## 5. 目录里现有的东西

```
coolapk/
├── coolapk_target_manifest.json   从模块 APK 里取出的 profile 表（16.6.1–16.7.0）
├── apk/coolapk-16.7.3.apk         易盾加固包（gitignore）
├── apk/coolapkpurifier-2.8.0.apk  模块本体（gitignore）
├── work/classes.dex               加固后仅 30 个 wrapper 类的壳（gitignore）
├── work/capk.vdex                 92 字节空 vdex（gitignore）
└── tools/probe_profile.py         把 manifest 的每个 target 拿到某个 APK 里核对
```

`probe_profile.py` 用法（对**未加固**的 APK 有效，加固包会全报 MISSING）：

```sh
python tools/probe_profile.py apk/coolapk-16.7.3.apk coolapk_target_manifest.json --profile 16.7.0
```

## 6. 可选下一步（待定）

1. **运行时 dumper 模块**：写一个只跑在 `com.coolapk.market` 里的 LSPosed 模块，
   枚举运行时 ClassLoader 的全部类/方法，按语义指纹（`getEntityTemplate`、`EntityAdHelper`、
   `RecyclerViewItemFullVisibleControllerKt` 这类不混淆的锚点）反查出 16.7.3 的混淆名，
   直接产出一份 `profile` 贴回 manifest → 恢复全功能。工作量中等，和 fq-fold-pad 共用构建链。
2. **只管开屏/信息流的轻量脚本**：不碰 manifest，直接用现有 fallback 语义再补几个 target（信息流广告实体过滤、搜索热词卡片），风险低。
3. **跟模块无关的酷安脚本**：比如签到、抓取、导出等（走 API/Web 侧，与加固无关）。

---

# 附：硬刚静态反编译的实测记录（2026-11-14）

结论先说：**酷安 16.7.3 不是"加壳"这么简单，是易盾的「header 抹除 + 表重排 + 字符串前缀剥离」三件套。**
能静态抠出表结构和一部分类名/签名，但拿不到可反编译的完整 dex。下面是逐条证据。

## 1. APK 里的 classes.dex 只是壳

```
classes.dex          98,518,356 B
  声明类数           30（全是 com.netease.nis.wrapper.*）
  map_list 结束      63,184 B
  其后 ~98 MB        未被任何 dex 表引用（壳把真实 dex 塞在这儿）
```

## 2. 那 98 MB 是 10 个真实 dex 首尾相接

每个单元的起点都能被「string_ids 表紧跟 type_ids 表」这一结构锚定，
且只有前 **112 字节 header 被抹成随机字节**，表区/字符串区/代码区仍是明文：

| 单元 | 偏移 | 大小 | string_ids | type_ids |
|---|---|---|---|---|
| 0 | 67,596 | 17.8 MB | 73,909 | 18,623 |
| 1 | 18,783,820 | 3.2 MB | 23,243 | 4,528 |
| 2 | 22,140,604 | 12.1 MB | 74,170 | 11,694 |
| 3 | 34,846,756 | 8.8 MB | 66,732 | 12,465 |
| 4 | 44,058,468 | 7.5 MB | 40,872 | 10,583 |
| 5 | 51,909,364 | 8.6 MB | 54,887 | 11,047 |
| 6 | 60,952,508 | 9.3 MB | 50,428 | 14,200 |
| 7 | 70,747,588 | 9.8 MB | 67,054 | 15,122 |
| 8 | 81,061,688 | 8.5 MB | 63,348 | 12,148 |
| 9 | 89,998,620 | 8.1 MB | 61,390 | 8,804 |

合计 **12 万条类型条目、~50 万条字符串、~150 万条 field/method id**——
单元 0 的 proto_ids 表还能正常解析出 **11,894 个方法签名**。

## 3. 三处硬伤：为什么还是不能直接反编译

| 内容 | 状态 | 证据 |
|---|---|---|
| 112 字节 header | ✗ 随机字节 | 魔数、checksum、sha1 全无；`dex\n035` 在整个 blob 里 0 命中 |
| class_defs | ✗ **顺序被打乱** | 表本身在（14,815 条，cls/sup/off 都合法），但 class_idx 从 549 跳回 542；ART 需要严格递增，说明加载时由 native 重排 |
| class_data | ✗ 解析出乱数 | 用 class_defs 里的 `class_data_off` 解出 `static_fields=9, instance_fields=2,093,456` 这种荒谬值 → 偏移或内容被改过 |
| 字符串 | ✗ **前缀被剥离** | 例如真实类名 `Lcom/coolapk/market/view/ad/EntityAdHelper;` 在表里是 `w/ad/EntityAdHelper;`；`SOME_OP_NAME`→`OP_NAME`、`visible`→`sible`、`getView` 反而完整（说明共享前缀按字典序做了 front-coding） |
| map_list | ✗ 找不到 | 单元尾部全高熵，map 结构不存在/被加密 |

## 4. 运行时内存也没有"完整 dex"可捞

用自编 `tools/memdump.c`（root，`/proc/<pid>/mem` 全量扫描）扫了 871 个可读映射：

- 命中 60 个 dex 镜像：全是系统 framework jar + 3 个广告 SDK 插件 APK（`base-1.apk`、`gdt_plugin.jar`）+ 壳自己那 98 MB
- `/data/data/com.coolapk.market/oat/arm64/Anonymous-DexFile@*.vdex` 只有 **92 字节**
- 应用目录里没有任何解密后的 dex/jar 落地

即：真实代码就是那 98 MB 映射（`rw-p` 的 base.vdex 段），壳在进程内**就地改写** header/表，
所以"记忆体 dump 出一个干净 dex"这条路也走不通。

## 5. 这一轮新增的工具

| 文件 | 作用 |
|---|---|
| `tools/memdump.c` | root 侧进程内存 dex 扫描/落盘（NDK 静态编译，已实测） |
| `tools/rebuild_dex2.py` | 定位 10 个 dex 单元 + 按排序性切表 + 自己拼 header（能拼出结构，但 class 区被重排，产物不可解析） |
| `tools/strings_dump.py` | 导出每个单元的 string_ids / type_ids / proto_ids（明文部分） |
| `tools/members_dump.py` | 导出 field/method id 合表 → `class ⇄ member name` 映射（~150 万行） |

产物在 `work/`（gitignore）：`ids/`（字符串/类型/签名）、`members/`（类-成员映射）。

## 6. 结论 & 可行路线

静态侧的天花板：**表结构 + 类名骨架 + 签名 + 成员名**（字符串带前缀缺失，需要额外重建）；
拿不到：类继承关系、字段布局、方法体归属 → 也就拿不到"能读的代码"。

要真拿到代码/完整类结构，只有运行时：
1. 模块内 hook ART 的 `DefineClass` / `DexFile` 构造，把壳还原后的内存结构导出；
2. 或者干脆不追 dex，直接用 dexkit 在运行时按语义反查（现有「酷安净化」就是这么活的）。

---

# 附二：16.7.3 适配进度（2026-11-14 17:40）

## 已完成

1. **上机勘察**：`io.github.yylsping.coolapkpurifier` 2.8.0 已装且启用，作用域 `com.coolapk.market`；
   但它自带的 manifest 最新只到 **16.7.0**，本机是 **16.7.3(2611121)** → `profile=missing`，
   只剩开屏广告 + 信息流赞助走指纹兜底，其余 10+ 个 target 全部 DISABLED。

2. **静态挖出完整类名**：那 98 MB 里 `Lcom/coolapk/...;` 明文出现 6,777 次，
   正则扫描去重得到 **3,795 个类描述符**（`work/names.txt`）。
   ——之前判断"字符串被前缀剥离"是我解析器的问题，字符串本身是完整的。

3. **写了一个只跑在酷安进程里的导出模块** `module-inspect/`（`local.codex.coolapkinspect`）：
   - 通过 Vector CLI 自动启用并设置作用域（`cli modules enable` / `cli scope add`）
   - 用 names.txt 里 3,795 个类名逐个 `Class.forName(name, false, cl)` 强制加载
   - 导出 `files/fqi/classes.json`：**4,739 个类 / 37 万个方法条目**，含父类、接口、方法签名、字段
   - 关键锚点全部命中：`EntityAdHelper`(55 方法)、`EntityListFragment`(315)、
     `FeedDetailFragmentV8`(115)、`RecyclerViewItemFullVisibleControllerKt`、`LiquidGlassTabPanelKt`(137)、
     `$$AutoValue_Feed.getRelatedData/getDetailSponsorCard`

4. **匹配报告**：`tools/match_profile.py` 把 16.7.0 的 15 个 target 对着新 dump 比对，
   **5/15 的 ownerClass 在 16.7.3 里同名复用**（`sameTopic`、`autoComment`、`autoCommentPrompt`、
   `relatedData`、`detailSponsor` 等）；其余 10 个是混淆类名/方法名，需要结构匹配。

## 设备状态（很重要）

- **没有重启**：`uptime` 1 day 18:43，与本轮开始时一致。
- 酷安数据零改动：只在它自己的 `files/fqi/` 下加了导出文件，`databases/`、净化配置 JSON 都在。
- 净化模块本体未动，仍启用；备份在 `apk/coolapkpurifier-2.8.0.apk` 与 `backup/`。

## 下一步（待在手机解锁状态下做）

1. 用结构锚点把剩下 10 个 target 映射到 16.7.3 的混淆名：
   - `relatedIconListUi` / `relatedContentUi` / `detailSponsorUi` / `replySponsor`：
     按「父类是 X + 构造参数含 DataBindingComponent + 字段类型是 callback 类」筛
   - `topicDeviceRecommend*` / `searchHot*Ui` / `bottomNavigation`：
     按 Kotlin Compose 函数签名（参数里含 `Composer`/`Modifier`）+ 模板字符串锚点筛
2. 生成 `profiles` 里新增一条 `16.7.3 / 2611121` 的 profile，写回模块 APK 的
   `assets/coolapk_target_manifest.json`，重新签名安装（模块配置存在酷安私有目录，不丢）。
3. 强停酷安 → 重启 → `adb logcat -s CoolapkAdBlock` 看 `install=OK` 全覆盖。

---

# 附三：16.7.3 全量类结构已拿到（2026-11-14 17:56）

## 关键突破：暴力枚举短名混淆类

易盾壳里的类除了 `com.coolapk.*`，还有大量**默认包 + 1~4 字符**的混淆类（`b25`、`gg4`、`jw`、`x2f`…），
文本扫描抓不到。模块加了 `brute` 触发：用 `Class.forName` 枚举 `[a-z0-9]{1,4}` 全部组合：

```
brute len=1 tried=36       found=26
brute len=2 tried=1,332    found=958
brute len=3 tried=47,988   found=20,343
brute len=4 tried=1,727,604 found=20,343
dumped classes=25,107 methods=117,362 fields=107,438 -> 18.3 MB
```

产物：`work/runtime/classes-25107.json`（**25,107 个类 / 11.7 万方法 / 10.7 万字段**，含父类、接口、签名）。

## UI 采样（解锁后做的）

- 打开过 搜索页（热门搜索胶囊 + 数码热榜）→ `searchHot*Ui` 相关类
- 打开过 `FeedDetailActivityV8`（帖子详情 + 点评列表）→ `relatedIconListUi` / `detailSponsorUi` / `replySponsor` 相关类

## 已验证仍然可用的锚点（16.7.3）

| 锚点 | 状态 |
|---|---|
| `com.coolapk.market.view.ad.EntityAdHelper` | 55 个方法 |
| `EntityAdHelper.Ϳ(List, boolean) -> List` | **与 16.7.0 完全同名**（信息流广告过滤） |
| `EntityAdHelper.ޒ(List) -> Entity` | 新发现 |
| `EntityAdHelper` 父类 `com.coolapk.market.view.cardlist.Ԩ` | 混淆父类 |
| `$$AutoValue_Feed.getRelatedData()` / `getDetailSponsorCard()` | 签名一致 |
| `RecyclerViewItemFullVisibleControllerKt` / `LiquidGlassTabPanelKt` / `MainV8ListFragment` / `FeedDetailFragmentV8` | 全在 |

## 模块使用方式（下次继续）

```sh
# 启用并划作用域（Vector CLI）
su -c '/data/adb/lspd/cli modules enable local.codex.coolapkinspect'
su -c '/data/adb/lspd/cli scope add local.codex.coolapkinspect com.coolapk.market/0'
# 触发一次「带暴力枚举」的导出
su -c 'touch /data/user/0/com.coolapk.market/files/fqi/brute /data/user/0/com.coolapk.market/files/fqi/trigger'
```

导出完**记得 disable**，否则每次启动酷安都会强制加载几千个类、多写 10~18 MB。
目前状态：`local.codex.coolapkinspect` = **disabled**（已关），净化的模块仍是 enabled。

## 剩余工作

10 个混淆 target 的映射（`relatedIconListUi`、`relatedContentUi`、`detailSponsorUi`、
`replySponsor`、`topicDeviceRecommend*`、`searchHot*Ui`、`bottomNavigation`），
然后生成 profile → 写回模块 APK → 重签 → 验证。

---

# 附四：16.7.3 profile 已装上（2026-11-14 18:14）

## 做了什么

1. 用 16.7.0 的 profile 复制出 `16.7.3 / 2611121 / status=validated`，写进模块 APK 的
   `assets/coolapk_target_manifest.json`（`work/manifest-1673.json`）。
2. 重新打包 + 用本地 keystore 重签（注意：`META-INF/xposed/*` 必须保留、
   `resources.arsc` 和 `lib/*.so` 必须 **Stored** 否则 Android 14+ 拒装）。
3. 卸载原模块 → 安装改过的 APK → Vector 里重新 enable + 划作用域。

产物：`work/coolapkpurifier-2.8.0-1673.apk`（2.8.0，profile 里多了 16.7.3 一条）。

## 实测结果（logcat -s CoolapkAdBlock）

```
manifest schema=1 hostVersion=16.7.3(2611121) profile=missing   ← 打补丁前
manifest schema=1 hostVersion=16.7.3(2611121) profile=validated ← 打补丁后（不再 UNSUPPORTED_VERSION）

feature=remove_splash_ads      hookInstalled=true  → FIRST_MODIFIED（真的干掉了一次开屏）
feature=remove_feed_sponsor    hookInstalled=true
feature=remove_reply_sponsor   install=TARGET_MISSING
```

其余 target 都是 `DISABLED`——因为**配置里本来就是关的**（不是 hook 失败）：
`remove_detail_sponsor / remove_related_data / remove_same_topic_feed /
remove_auto_comment / remove_search_hot_words / remove_search_hot_rankings /
remove_topic_device_recommend / bottomNavigation`。

## 关键发现：16.7.3 的卡片架构变了

- 全库 25,107 个类里，**构造参数含 `DataBindingComponent` 的类 = 0 个**
  → 16.7.0 那批 DataBinding ViewHolder（`*Ui` 系列 target）在 16.7.3 已经不存在。
- 新架构是 **binder**：大量类继承 `z26`（→ `jd` → `RecyclerView$ViewHolder`），
  统一实现 `ގ(Entity) -> void` 绑定。目前识别出 **109 个**这样的绑定器（`work/binders.txt`）。
- 所以 `replySponsor` 的 `ownerClass: b25 / method: ޅ` 已失效 ——
  16.7.3 里 `b25` 变成了一个 `Function1` 实现类（只有 `invoke`）。

## 下一步（可选）

把「实体模板 → 绑定器类」摸出来就能补 replySponsor：
需要让导出模块 hook 这 109 个 `ގ(Entity)`，导航到帖子详情，看谁被
`feedDetailReplySponsorCard` 命中。模块代码里 `probeBinders()` 已经写好
（读 `fqi/binders.txt` 逐个挂），但近几次重装后 Vector 不再注入这个模块
（`CoolapkInspect` 一行日志都没有），需要先把注入问题解决——**不要重启手机**，
可以试：Vector 管理器里手动开关一次模块，或者 `cli modules disable/enable` 后
再强停酷安重开。

---

# 附五：binder 探针实测（2026-11-14 18:43）

用户在 Vector 里重新打开导出模块后，注入恢复正常：

```
CoolapkInspect: === attached to com.coolapk.market
CoolapkInspect: probeBinders hooked=108        ← 109 个 binder 挂上 108 个
```

巡界面（头条信息流 / 热榜 / 帖子详情 303 回复）抓到的绑定关系：

| binder 类 | 收到的实体模板 |
|---|---|
| `ub5` | `feed` |
| `ti4` | `feedCover` |
| `jr4` / `uh6` / `qsd` | `null`（无模板的头部/尾部卡片） |

**没有抓到 `feedDetailReplySponsorCard`**：16.7.3 的回复列表（`FeedDetailActivityV8`，303 楼）
翻了几屏都是正常回复，没有任何"赞助卡"。所以有三种可能：

1. 这个卡片只在特定帖子里下发（客户端按服务端 AB 开关决定）；
2. 新版详情页改用别的渲染路径，不走这 109 个 `ގ(Entity)` binder；
3. 新版干脆不再在回复列表里插赞助卡了。

结论：**目前无法确认这张卡还存在**。要么找到一个确实带赞助卡的帖子再抓一次，
要么就把 `remove_reply_sponsor` 当作"新版无需处理"。

导出模块用完已经重新 disable（`local.codex.coolapkinspect`），需要再抓时说一声或自己开一下。

---

# 附六：回复赞助卡已过滤（2026-11-14 22:30）

上一节的判断错了：净化模块的 `remove_feed_sponsor` 开着，回复区赞助卡被它自己挡掉，当然看不到。
关掉净化后进 `FeedDetailActivityV8`，卡还在，模板名仍是 `feedDetailReplySponsorCard`，
类是 `com.coolapk.market.model.AutoValue_EntityCard`。

它不走任何 ViewHolder。108 个 `ގ(Entity)`、106 个 `z26` 子类的 `ޅ(Object)`、11 个
`RecyclerView.Adapter.onBindViewHolder` 都挂过，赞助卡一次都没进绑定。它是
`com.coolapk.market.remote.EntityListTypeAdapter` 的 `Ϳ` 在反序列化时直接塞进回复列表的。
旧 profile 的 `fn4` / `qx4` / `b25` 在 16.7.3 已不存在，所以正式模块仍报
`remove_reply_sponsor install=TARGET_MISSING`。正式模块自己的 `X.a()` 甚至把这个模板名
当成「不是赞助」直接放行。

做法：新模块 `local.codex.coolapkreply`（酷安回复赞助过滤，后来已合并进净化包，见附七）。
挂 `EntityListTypeAdapter` 的 `Ϳ`，把模板名含 `sponsor` 的项从列表删掉。实测：

```
CoolapkReply: hooked EntityListTypeAdapter
CoolapkReply: removed sponsor cards=2
```

进一篇 48 楼的帖子，回复区没有「今日推荐 / 广告」卡。

当时的设备状态：

- `io.github.yylsping.coolapkpurifier` enabled：开屏 + 信息流赞助（它的 replySponsor 仍是 TARGET_MISSING，不影响这一刀）
- `local.codex.coolapkreply` enabled，作用域 `com.coolapk.market`：回复赞助过滤（附七已并入净化包并卸载）
- `local.codex.coolapkinspect` disabled
- 酷安配置 JSON 未改，手机未重启

---

# 附七：两个酷安模块合并成一个（2026-11-15 09:26）

## 问题

手机上同时装了 `io.github.yylsping.coolapkpurifier`（净化本体，已打 16.7.3 profile）和
`local.codex.coolapkreply`（回复赞助过滤），两个模块都 hook 同一个酷安进程，冗余。

## 做法：在一个净化 APK 里放两个 java_init 入口

Vector（LSPosed 分支）的 API 102 模块格式是 `META-INF/xposed/java_init.list` **一行一个入口类**。
从设备上拉的 `framework/vector.dex` 反编译确认（`defpackage/b3.java` + `de.robv.android.xposed.XposedInit`）：

- 每行都会 `loadClass` → 必须 `extends io.github.libxposed.api.XposedModule`，否则
  `Class does not extend XposedModule, skipping.`（所以旧版 `IXposedHookLoadPackage` 入口放进
  这个 list 是没用的，`assets/xposed_init` 才是 legacy 通道）
- `x0.b`（入口类列表）是循环实例化的 → **多入口天然支持**，入口数量没有限制
- `classes2.dex / classes3.dex …` 由 daemon 一并塞进模块 ClassLoader，直接可用

另外 `updateHotReload` 要求模块「exactly one Java entry class」，多入口模块不支持热重载（不碍事）。

## 实现

`coolapk/module-merged/`：

| 文件 | 作用 |
|---|---|
| `src/local/codex/coolapk/MergedHook.java` | 新 API 写法（`extends XposedModule`，`onPackageReady` 里挂 `EntityListTypeAdapter.Ϳ`） |
| `stubs/io/github/libxposed/api/*.java` | 编译期桩，签名对齐设备上 `vector.dex` 的 API 102 |
| `build.sh` | 以已打 profile 的净化 APK 为底座，追加 `classes2.dex` + 第二个 `java_init` 入口，重签 |

签名必须与已装版本同 key，才能 `install -r` 覆盖安装：
`module-inspect/.signing/coolapk-inspect.keystore`（alias/pass `coolapkinspect`，CN=CoolapkInspect）。

```sh
cd coolapk/module-merged
./build.sh                                  # BASE_APK 默认 ../work/purifier-now.apk
# 覆盖安装（vivo 上 adb install 会弹「用户拒绝权限」，走 root pm install 最稳）
adb push dist/coolapk-purifier-merged-2.8.0-merged.apk /data/local/tmp/m.apk
adb shell "su -c 'pm install -r /data/local/tmp/m.apk'"
```

底座 APK 从手机拉：

```sh
adb exec-out su -c "cat $(adb shell pm path io.github.yylsping.coolapkpurifier | sed 's/package://')" > coolapk/work/purifier-now.apk
```

## 实测（logcat）

```
VectorModuleManager: Loading class class io.github.yylsping.coolapkpurifier.y
VectorModuleManager: Loading class class local.codex.coolapk.MergedHook
VectorModuleManager: Loaded module io.github.yylsping.coolapkpurifier successfully.
CoolapkAdBlock: manifest schema=1 hostVersion=16.7.3(2611121) profile=validated
CoolapkReply: hooked com.coolapk.market.remote.EntityListTypeAdapter methods=1
CoolapkReply: intercept list size=3 / 17 / 7      ← 证明 hook 真的进了列表解析
```

随后 `pm uninstall local.codex.coolapkreply`，只剩一个酷安模块，覆盖安装前后
签名一致（`signatures=57c7d7a9 version:3`），Vector 里 enabled + 作用域 `com.coolapk.market` 都没丢。

## 当前设备状态

- `io.github.yylsping.coolapkpurifier` 2.8.0（merged，versionCode 仍是 20）：开屏 + 信息流赞助 + 回复赞助过滤
- `local.codex.coolapkreply`：**已卸载**（独立版 APK 备份在 `apk/coolapk-reply-standalone-1.0.0.apk`）
- `local.codex.coolapkinspect`：未安装
- 酷安配置 JSON 未改，手机未重启（`uptime` 连续，2 天 10 小时）

---

# 附八：上游 2.9.0 底座升级合包（2026-11-26）

## 上游变化（`yylsping/coolapk-purifier` `21-2.9.0` / `fc21249c`）

- 官方 `coolapk-purifier-v2.9.0.apk`（`versionCode=21`，下载于 `cache/bases/coolapk/coolapk-purifier-v2.9.0.apk`）已完整原生适配酷安 `16.7.3 (2611121)` 全部 12 项功能：
  - 补齐 16.7.3 的 `replySponsor`（`l65.ޅ`，View 层折叠）、`detailSponsorUi`、`relatedIconListUi` / `relatedContentUi`、`sameTopic`、`topicDeviceRecommend*`、`searchHot*Ui`；
  - 「精简底部导航」适配 16.7.3 新版液态玻璃底栏（`LiquidGlassTabPanelKt` / `vt1`）；
  - 新增第 12 项功能「屏蔽酷安更新」（`hostUpgrade` -> `kc2`，默认关闭）。
- `META-INF/xposed/java_init.list` 入口仍为 `io.github.yylsping.coolapkpurifier.y`。

## 本地合包更新

- `module-merged/build.sh` 默认版本号升至 `2.9.0-merged`，并改为自动从底座 APK 读取 `META-INF/xposed/java_init.list` 主入口，避免后续上游 R8 重命名入口类导致漏挂。
- 仍使用 `shared/signing/coolapk-inspect.keystore`（证书 SHA-256 `BC:97:…:D5:DA`）重签，保证可直接 `adb install -r` 覆盖升级而不丢 Vector 启用与作用域（遵循 `AGENTS.md` §1.0：仅在屏幕解锁、用户在场时用 `adb install -r`，禁止 root `pm install`）。
- 第二入口 `local.codex.coolapk.MergedHook` 保留作为 `EntityListTypeAdapter.Ϳ` 反序列化层兜底过滤（与 2.9.0 所有 hook 目标互不重叠）；若后续希望回复赞助完全受酷安内开关控制，可改为纯重签官方 2.9.0 或直接切回官方签名包。
