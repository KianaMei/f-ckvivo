# Re:X → XHook 移植台账

状态：✅ 已实现并编译（未上机验证）｜🟡 部分/实验｜❌ 未开始

Re:X 1.8.2 的设置项约 100 个，分布在 8 个板块。全量“一次搬完”不现实：
闭源不能抄代码，只能按 UI 标签 + 反编译结构重写；外观类还绑死 X 的 Compose 内部结构。
下面按“功能组”推进，每组都能单独编译、单独回滚。

## 第 1 批：时间线行为（当前）

| 功能 | Re:X 参考 | XHook | 备注 |
|---|---|---|---|
| 阻止首页重选刷新 | `kg0.o` + `ge2.I` + `ja2` case 18（第二个 boolean 置 false） | ✅ `RefreshGuard.hookHomeReselect` | 已命中 `com.x.home.tabbed.v#h`（deopt=true）；点按触发待复测 |
| 阻止回前台自动刷新 | `kg0.o` + `ge2.M/R` + `by0`（enum `VIEWPORT_AWARE_AUTO_REFRESH`） | 🟡 `RefreshGuard.hookRefresh` | 已命中 `com.x.repositories.urt.a0#p`；Remote Preferences `enabled=true`；resume 拦截待复测 |
| 启动记忆滚动位置 | `kg0.o` 的 timeline position persistence（store/holder/save/get） | ❌ | 结构复杂，下一批 |
| 记忆首页 Tab | `ge2` Home tab coordination / remembered restoration | ❌ | 下一批 |
| 自动刷新控制（切换 Tab 放行等） | Re:X 设置项 | ❌ | 与上面共用 hook 点 |

## 第 2 批：去广告 / 视频 / 机器人

| 功能 | Re:X / 参考 | XHook | 备注 |
|---|---|---|---|
| 推广帖过滤（时间线/回复） | Re:X `lv1`；TwitterHideAds 渲染边界 | 🟡 0.3.0 已实现 | 12.42 改用 promotedMetadata 字段 + 渲染边界跳过；待上机验证 |
| Video Tab + RTB 广告 | TwitterHideAds `RtbAdFilter`/`VideoDatasetFilter` | ❌ | paging state / item→presenter |
| 隐藏 Spaces 栏 | Re:X `lv1` | ❌ | 结构 hook |
| 隐藏新帖悬浮按钮 | Re:X `lv1` | ❌ | 结构 hook |
| 隐藏 Premium 升级/付费订阅按钮 | Re:X `lv1` | ❌ | 结构 hook |
| 隐藏订阅/文章 Tab | Re:X `bv0` | ❌ | Tab 配置 |
| 视频下载（HLS/MP4） | Re:X `dv0/iv0`；x-video-save | 🟡 悬浮按钮 + 选档 | 已能抓 URL/触发；0.2.9 增加单码率媒体列表 fallback，待复测 |
| GIF 下载转 .gif | Re:X `lv1/dv0` | ❌ | 需要 GIF encoder |
| 下载位置/文件名模板 | Re:X `dv0/eq` | ❌ | 模块设置 + MediaStore |
| 分享面板下载入口 | x-video-catcher state ctor | ❌ | 工程量大 |
| 关键词/机器人过滤 | X-ADBlock（MIT） | 🟡 contains + 零宽归一化 | 无 Aho/正则/白名单/MARK/历史 |
| 过滤历史/白名单/已认证跳过 | X-ADBlock | ❌ | 模块 App + 规则层 |

## 第 3 批：隐私 / 分享 / 文本 / 维护

| 功能 | Re:X 参考 | XHook | 备注 |
|---|---|---|---|
| 本地身份伪装（昵称/@/头像） | Re:X `iv0/ge2/hv1` | ❌ | 仅本机显示 |
| 清理分享链接追踪 | Re:X `lv1/xu1/yv1` | ❌ | share intent |
| 分享域名替换（x.com/twitter.com/自定义） | Re:X `xu1/yv1` | ❌ | share intent |
| 文本选择菜单自定义（分享/翻译/词典） | Re:X `bv0/wt1` | ❌ | system selection menu |
| 隐藏 Grok 分析/长按项 | Re:X `lv1` | ❌ | 结构 hook |
| 跳过年龄验证遮罩 | Re:X `lv1` | ❌ | 结构 hook |
| 投票结果预览 / 长按倍速 | Re:X `lv1` | ❌ | 结构 hook |
| 设置备份/恢复 | Re:X `u41/dy1` | ❌ | 模块 App 独立完成 |
| 日志页（Hook 错误/安装耗时） | Re:X `gq/lx1` | ❌ | 模块 App + 日志文件 |
| 更新检查/安装 | Re:X `eq/qn` | ❌ | 可后置 |
| Feature Switch 编辑器 | Re:X `eq/m1/i60` | ❌ | 建议默认只读 + 可回滚 |

## 第 4 批：外观 / 导航（工程量大，最后做）

| 功能 | Re:X 参考 | XHook | 备注 |
|---|---|---|---|
| Material 3 动态取色 | `bv0/hv1/iv0` | ❌ | 大量 Compose 颜色 hook |
| 系统字体 / Twemoji | `bv0/lv1` | ❌ | 字体/emoji hook |
| 𝕏→小鸟 / 鸟舍 / 羽毛笔 | `bv0` | ❌ | 资源替换 |
| 开屏缩放动画 | `lv1` | ❌ | 开屏 hook |
| 底部导航/侧边栏/FAB 定制 | `ci/gv1/uw1` | ❌ | 结构 hook |
| 通知样式 / SystemUI | `hv1` | ❌ | 需 SystemUI 作用域，不建议 |
| 悬浮导航栏 | `gv1` | ❌ | 结构 hook |

## 已知阻塞

1. **去广告（P0）已改代码、待上机**：12.42 删除了 entryId 前缀，0.2.9 的 `discovery`
   返回 0 个候选。0.3.0 改为 `RenderAdFilter`：promotedMetadata 非空 → 在
   `com.x.urt.items.post` 的渲染边界提前返回。锚点证据见 `X-12.42-ANCHORS.md`。
2. **上机校准**：模块已装（dev.xhook 0.2.9），Vector 已启用并勾 `com.twitter.android/0`；
   2026-11-15 22:14 已确认模块加载、视频抓取、两个刷新守卫都命中；交互测试
   （点 Home/Tab、后台恢复、下载、点按）仍待复测。
3. **进程被杀**：`notes/X-BACKGROUND-KILL.md` 已证明 OriginOS `single-cleaner` 会
   force-stop X；详情页回首页主要是冷启动，hook 无解，先做系统保活。
4. **闭源边界**：Re:X 的代码不能直接复制，只能按行为重写；GPL 模块（TwitterHideAds/Piko）
   只能参考思路，直接抄会让 XHook 变成 GPL。
