# zhihu-zhiliao — 知了（Zhiliao）× 知乎 11.14.0 源码适配补丁

> 本目录是知了 Xposed 模块（`com.shatyuka.zhiliao`）针对知乎 `11.14.0`（`versionCode=41410`）的全量源码补丁线，全部修改保持向后兼容旧版知乎。

## 1. 上游基线与目录约定

- **上游仓库**：<https://github.com/shatyuka/Zhiliao>（GPL-3.0）
- **上游基线**：`26.02.03`（`versionCode=202602030`，commit `988eac3efb5d23f9a861c44739bfb9d1b9d030cc`）
- **本地版本**：`26.02.03-adapt1`（`versionCode=202602031`）
- **源码工作副本**：`forks/zhiliao/`（被主仓 `.gitignore` 屏蔽，各自独立 `.git`）
- **补丁文件**：[`patches/adapt-zhihu-11.14.0.patch`](patches/adapt-zhihu-11.14.0.patch)
- **目标宿主**：`com.zhihu.android` `11.14.0`（`41410`），官方签名 SHA-1 `b6f997e3827be11af2fa4a153fea3fe627686602`
- **构建产物**：`dist/Zhiliao_26.02.03-adapt1.apk`（不入库）
- **协同模块说明**：`upstream-derived/liquidtab-mine` 已在 `289 / 2.1.2-mine9` 移除重叠的 `ZhihuSplashAds`，开屏广告统一交由知了 `LaunchAd` 处理。

## 2. 全部 15 项修复明细（基于知乎 11.14.0 反编译实证）

| # | 模块文件 | 问题与现象 | 11.14.0 根因与修法 |
|---|---|---|---|
| 1 | `CustomFilter.java` | **实时高频异常刷屏**（`IllegalArgumentException: Expected receiver of type TextElement, but got HtmlTextElement`）且中断 SDUI 卡片过滤 | `HtmlTextElement` 与 `TextElement` 均为 `Element` 子类（各自含私有 `text` 字段）。新增 `HtmlTextElement` 反射与 `getElementText()` 分发，补齐 `extra/elements/type` 空安全，`"zvideo".equals(type)` 防 NPE |
| 2 | `Tag.java` | SDUI 卡片下非 `TextView` 子节点或空 `type` 潜在 `ClassCastException` / NPE | `card_onBindData` 增加 `extra/elements` 判空、`instanceof TextView` 与越界保护，`getType`/`getBackground` 增加 `type == null` 兜底 |
| 3 | `RedDot.java` | **严重误杀底栏刷新**（`BottomNavMenuItemView.a(int)` 与 `BottomNavMenuItemViewForIconOnly.a(int)` 已变成刷新文字/颜色/图标/padding 的方法）；`NotiUnreadCountKt.hasUnread` 已删 | 当 `BaseBottomNavMenuItemView.a(NavBadge)` 存在时禁止 Hook 子类 `a(int)`；将 `setNavBadge` 改为 `beforeHookedMethod` 置 `args[0]=null`，放行原方法主动隐藏数字/文字/红点三类角标；新增 `IUnreadCount$DefaultImpls.hasUnreadCount` 返回 `false` |
| 4 | `CommentAd.java` | **100% 静默失效**（`MorphAdHelper.resolveCommentAdParam` 已删除） | 保留旧版 Hook，新增 11.14.0 四重拦截：`ADPluginData.getPluginAsset()` 返回 `null`、`comment_for_v7.b.k.getPluginInfo()` 返回 `null`、`BaseCommentDynamicAdViewHolder` / `CommentDynamicAdViewHolderV70` 的 `a(CommentListAd)` 与 `AdPluginHolder.onBindData` 拦截并隐藏 `itemView`（GONE + 0×0） |
| 5 | `FeedAd.java` | `MorphAdHelper.resolve(Context, FeedAdvert, boolean, Boolean)` 已拆分为两个新方法，导致部分信息流/热榜广告漏网 | 保留原有全部 Hook 并加元素判空，补挂 `MorphAdHelper.resolveCommonAdParam(Context, FeedAdvert, boolean, boolean)`、`resolveHotAdParam(Context, FeedAdvert, int)` 返回 `false`；补充拦截首页顶部横幅广告位 `HeaderAdViewHolder`（`optimal.b`），同时保留搜索框 |
| 6 | `FeedTopHotBanner.java` | **100% 静默失效**且存在**误杀搜索框风险**（`FeedTopHotAutoJacksonDeserializer` 不再声明 `deserialize`，且 `FeedTopHot` 被 `TemplateHeaderHolder` 复用为顶部搜索框载体） | 仅当 `FeedTopHotAutoJacksonDeserializer` 自身声明 `deserialize`（旧版）时拦截反序列化；11.14.0+ 改为精准拦截 `HeaderAdViewHolder`（`optimal.b` 的 `e()` 与 `b$a$1.a(View, int)`），隐藏顶部推广横幅且完好保留顶部搜索栏 |
| 7 | `FullScreen.java` | **100% 静默失效**且漏判总开关（`ClearScreenHelper` 从 `clearscreen.d$c` 混淆漂移到 `clearscreen.c$c`） | 依次匹配 `d$c`、`c$c`，并支持扫描 `clearscreen.*` 带 `(Activity)` 构造函数的 `ClearScreenHelper` 动态定位内部 `$c`；补齐 `switch_mainswitch` 总开关检查 |
| 8 | `Article.java` | **100% 静默失效**（`ContentMixPagerFragment.c` 偏移为 `scenes`，真正 `type` 偏移至 `d`；且原版使用 `== "article"` 引用判等） | 检测到同时存在 `String c`、`String d` 及无参 `String o()`、`String p()` 时锁定字段 `d`，否则回退 `c`/`t`；修复为 `"article".equals(...)` 并增加 `fragment` 判空 |
| 9 | `SearchAd.java` | 漏掉搜索商业推广（`SearchTopTabsItemList` 已删，`commercialData` 迁入 `SearchRecommendQuery`） | 新增 `SearchRecommendQuery.commercialData` 反射，在响应反序列化后一并置 `null` |
| 10 | `HeadZoneBanner.java` | 漏掉新版热榜复数置顶（`FeedsHotListFragment2` 优先读 `RankFeedList.headZones`） | 同时反射 `RankFeedList.headZones` 与 `head_zone`，判空后一并置 `null` |
| 11 | `VIPBanner.java` | 漏掉盐选/知识会员卡（`MineTabFragment` 同时判断 `MoreVipData.isLegal()` 与 `MoreKVipData.isLegal()`） | 新增 Hook `com.zhihu.android.app.ui.fragment.more.mine.model.MoreKVipData.isLegal()` 返回 `false`，并为 `MoreVipData` 补判空 |
| 12 | `Horizontal.java` | `ActionSheetLayout` 回调列表字段已从 `z` 偏移到 `A`（当前 `z` 为 `boolean`），旧容器触发将抛类型转换异常 | 改为 `Helper.findFieldByType(ActionSheetLayout, List.class)` 动态定位（失败回退 `z`），遍历前加 `instanceof List` 防护 |
| 13 | `LiveButton.java` | **100% 静默失效**（`FeedsTabsFragment.mEntranceManger` 已删除，首页直播迁入顶部频道 `RecommendTabInfo.tabType == "live"`） | 保留旧版 `mEntranceManger` 兼容；新增 Hook `FeedsTabsFragment.updateTabs(List)`（兜底 `mTabLayoutDesignHelper.a(List)`），过滤 `tabType == "live"`，保持 Tab 与 ViewPager 索引同步 |
| 14 | `AutoRefresh.java` | `ui2.feed.a.*` 冷启预加载刷新拦截未命中（11.14.0 包名漂移至 `ui2.feed.b.*`） | 双包名扫描 `ui2.feed.a.` 与 `ui2.feed.b.`，精准命中 `b.c` (`FeedPreloadDataSource`) → `b.e` (`FeedRemoteDataSource`) → `b.g.f` (`nextUrl`) |
| 15 | `NextAnswer.java` | `View.setVisibility` 回调中误对 `param.args[0].getClass()`（`Integer`）做 `NextContentAnimationView_short.isAssignableFrom` 判断 | 修正为 `param.thisObject.getClass()` |

此外，`app/build.gradle` 增加无 Dobby submodule 时的预编译 `jniLibs` 支持（自动从上游原包提取 `arm64-v8a` / `armeabi-v7a` 的 `libzhiliao.so`，保留 100% native 能力且零 NDK 编译依赖）；`gradle.properties` 升版至 `26.02.03-adapt1` (`202602031`)。

## 3. 构建与校验

```sh
sh upstream-derived/zhihu-zhiliao/build-src.sh
```

## 4. 签名与首次换签说明

- **自有签名**：`shared/signing/zhiliao-adapt.keystore`（alias/storepass/keypass 均为 `zhiliao`，SHA-256 `B7:DC:D7:F9:30:12:D4:7D:D3:87:93:A6:03:2F:63:D8:00:6C:A0:17:E9:E8:99:50:0D:5C:EC:80:69:F3:14:D0`）。
- **首次从官方原版切换**：机上官方原版签名为原作者私钥 `33:1B:D3:5B:...`，签名不同无法直接 `adb install -r`，首次需先卸载原版 `com.shatyuka.zhiliao` 再安装补丁版，并在 Vector 重新勾选 `com.zhihu.android` 作用域。
- **配置不会丢失**：知了所有偏好开关均保存在知乎宿主私有目录 `/data/user/0/com.zhihu.android/shared_prefs/zhiliao_preferences.xml`，卸载重装知了模块本身不会丢失任何设置；后续补丁更新同签即可直接 `adb install -r`。
- **设备安全红线**：严禁未经用户明确授权执行卸载/安装；安装须满足屏幕已解锁、用户在场、仅用 `adb install`。
