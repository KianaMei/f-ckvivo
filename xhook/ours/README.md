# XHook（我们自己的 X/Twitter hook 模块）

基于 **libxposed API 102**（Vector / 新版 LSPosed）。目标设备：vivo X Fold6，Android 16，
X 12.42.0-prod.01。只 hook `com.twitter.android` 主进程。

## 构建状态（2026-11-15）

- `:app:assembleDebug` **已编译通过**（AGP 9.2.1 / Gradle 9.4.1 / JDK 21 / compileSdk 36）。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（5.01 MB，minSdk 31 / targetSdk 36，版本 0.2.9）
- SHA-256：`04d9918a83dbefca251bbd8135745152b268186fab06fc22f4a7dca8164d0fff`
- 签名：Android Debug（`CN=Android Debug`，SHA-256 `1990df77…7212fb`）
- 本机 SDK：`D:\D-code\tools\android\sdk`（platform 36 + build-tools 36 由 AGP 自动安装）
- **已安装到设备**：`dev.xhook` UID 10558；Vector 2.2 已启用，scope `com.twitter.android/0`。
- 实测进度：模块加载 / MediaCapture / PlayerButtons / DexKit / Home reselect / refresh repository 均已命中；Remote Preferences 已能读到 `block_refresh=true`。交互测试被设备上的 vivo 安装器弹窗打断，待继续。

## 2026-11-15 真机实测记录

- 安装方式：root `pm install -r --user 0`（跳过安装器确认）；Vector 2.2，包 `dev.xhook`，scope `com.twitter.android/0`。
- **Vector 2.2 的行为**：模块 APK 更新后，需要先打开一次模块 App（或 CLI disable/enable 后再启动 App），否则 X 启动时 Vector 会静默跳过该模块。日常开机不受影响；更新模块后照此操作。
- Remote Preferences：服务连接后，X 进程已读到 `block_refresh=true`、`block_home_reselect=true`（日志 `enabled=true`）。
- Hook 目标（X 12.42.0）：
  - `com.x.home.tabbed.v#h`：唯一 `boolean(boolean,boolean)`，命中 Re:X `fe2.l` 的类级谓词，`deopt=true`。
  - `com.x.repositories.urt.a0#p`：`void(Enum, ...)`，`deopt=true`；启动时 type=`PULL_TO_REFRESH`。
- MediaCapture：`hooked=1`，能抓到 `video.twimg.com` 的 master/直链；悬浮按钮可见，点击能触发下载流程。
- 第一次下载失败：X 返回的是**单码率媒体播放列表**（没有 `EXT-X-STREAM-INF`），0.2.9 已加媒体播放列表 fallback，待复测。
- 去广告 discovery 仍为 **0 candidate**，DexKit 查询需要单独校准。
  ！2026-11-15 22:14 复查：`discovery: 0 candidate method(s)` → `hooks=0`，真机截图
  （`../notes/now-x2.png`）里 @SUBWAY 的 `Ad` 帖仍在；根因是 12.42 删掉了 entryId
  前缀字符串，详见 `../notes/X-12.42-ANCHORS.md`。日志：`../notes/logcat-2026-11-15-xhook.txt`。
- 交互测试（点 Home/Tab、后台恢复、下载）被设备上的 vivo 安装器弹窗（OriginTweaks 0.1.0）打断，未继续。

当前是 **0.2 骨架**：视频下载 + 去推广/关键词过滤 + 后台刷新守卫（Home reselect /
lifecycle）。去广告与刷新守卫是“结构发现 + fail-open”的实验实现，需要在真机上用
X 12.42 校准一次（见“测试计划”）。

## 功能

| 功能 | 状态 | 实现 |
|---|---|---|
| 视频下载（HLS/MP4） | 可编译，逻辑完整 | 抓 `androidx.media3.datasource` 构造器 Uri → 解析主播放列表 → 选档 → 下载 init+分片 → MediaMuxer 合流 → MediaStore |
| 下载按钮 | 可编译 | 在 X Activity 的 DecorView 上叠 48dp 按钮；最近 20s 抓到过视频才显示；长按选画质 |
| 去推广/广告 | 实验 | DexKit 找“含 promoted-tweet-/rtb-image-ad- 且列表进出”的方法；按 entryId 前缀过滤 |
| 机器人/垃圾帖 | 实验 | 同一批列表 hook；对话页（可配主页）按关键词/零宽字符归一化匹配 |
| 阻止首页重选刷新 | 实验 | 找 `com.x.home.tabbed.*` 的 `boolean(boolean,boolean)`，第二个参数置 false（Re:X `ja2` 同款） |
| 阻止回前台自动刷新 | 实验（默认关） | 找 `com.x.repositories.urt.*` 的 `void(Enum, cursor)`，仅拦截 `VIEWPORT_AWARE_AUTO_REFRESH`，首次启动放行 |
| 设置 | 可编译 | 模块 App 写 SharedPreferences，X 进程经 libxposed Remote Preferences 读 |
| root / 联网上报 / 通知读取 | 无 | 模块 App 无任何权限；下载用 X 自己的 INTERNET |

## 代码结构

```
app/src/main/java/dev/xhook/
├── XHookModule.java        libxposed 入口（onPackageReady → Bootstrap）
├── Bootstrap.java          hook Application.attach，启动功能
├── Runtime.java            API 持有、日志、Remote Preferences
├── Config.java             配置键
├── media/
│   ├── MediaCapture.java   抓 media3 datasource Uri，按 mediaId 记录 master/直链
│   ├── PlayerButtons.java  Activity 生命周期 + 悬浮下载按钮
│   ├── Quality.java        HLS 主播放列表解析/选档
│   └── VideoDownloader.java HLS 下载、合流、写相册
├── filter/
│   ├── Discovery.java      DexKit 结构发现候选方法
│   ├── TimelineFilter.java 列表 hook + 过滤 + 保持 ImmutableList 类型
│   ├── EntryAccess.java    混淆模型 entryId/正文访问器缓存
│   └── Rules.java          promoted 前缀 + 关键词归一化
├── timeline/
│   └── RefreshGuard.java   Home reselect + 生命周期自动刷新拦截
└── ui/SettingsActivity.java 设置页
```

模块元数据：

```
app/src/main/resources/META-INF/xposed/module.prop   # minApi 101 / targetApi 102 / staticScope
app/src/main/resources/META-INF/xposed/java_init.list
app/src/main/resources/META-INF/xposed/scope.list
```

## 编译

需要：

- JDK 21
- Android SDK：`platforms;android-36`
- Gradle 9.4.1（AGP 9.2.1）

```bash
# local.properties 里写 sdk.dir，或用 ANDROID_HOME
./gradlew :app:assembleDebug
# Windows: gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

> 本机已装好 `D:\D-code\tools\android\sdk`（platform 36 / build-tools 36），
> `local.properties` 指向它；该文件已加入 `.gitignore`，换机时自行改路径即可。

## 测试计划（需要用户授权后才能装 APK）

1. 安装 APK → 在 Vector 里启用 XHook → 作用域只勾 `com.twitter.android`。
2. 强制停止 X（不是重启手机）→ 打开 X。
3. 看日志：
   ```bash
   adb logcat -s XHook
   ```
   期望看到：
   ```
   XHook: MediaCapture installed, hooked=1
   XHook: PlayerButtons installed
   XHook: discovery: N candidate method(s)
   XHook: discovery target: com.x...#... -> ...
   XHook: TimelineFilter installed hooks=...
   ```
4. 视频：打开一条视频，播放 2 秒，右下角应出现蓝色 ↓；点一下下载原画；长按选 720/480。
   相册路径 `Movies/XHook/`。
5. 去广告：主页/详情页滚动，观察 `TimelineFilter removed=... total=...`。若 `discovery: 0 candidate`，
   把日志发我，用 X 12.42 的 dex 重新校准 DexKit 查询。
6. 机器人：设置里打开开关、粘贴关键词；进详情页看命中；确认没有误杀普通回复。
7. 刷新守卫：设置里打开“阻止首页重选刷新 / 阻止回前台自动刷新”；回桌面或锁屏 30 秒再回 X，
   看 logcat 里的 `Home reselect guard` / `Refresh guard: blocked`。若首页空白或卡在加载，
   先关掉“阻止回前台自动刷新”再试。
8. 如果仍然跳回账号首页/信息流，先判断是不是进程被杀：
   ```bash
   adb shell dumpsys activity exit-info com.twitter.android | head -30
   ```
   出现 `single-cleaner` / `LOW_MEMORY` 就是系统杀进程，hook 无法恢复页面，
   详见 [../notes/X-BACKGROUND-KILL.md](../notes/X-BACKGROUND-KILL.md)。
9. 回滚：关闭模块或卸载 APK；X 不受影响（所有 hook 都在 try/catch 里，失败只丢功能）。

## 已知限制

- **去广告发现是实验性的**：X 12.42 的类名/结构可能和 12.28/12.37 不同；DexKit 查询参数
  （包名 `com.x`、`promoted-tweet-` 字符串）需要真机日志校准。校准失败时模块不会乱删帖子。
- **关键词过滤**：当前是纯 contains（已做零宽字符剥离）；没有实现 Aho-Corasick、正则、
  白名单、已认证跳过、MARK 模式——这些是下一阶段。
- **刷新守卫是实验性的**：Home reselect 照抄 Re:X `ja2`（第二个 boolean 置 false）；
  lifecycle 只在“刚回前台 + 已有内容”时拦 `VIEWPORT_AWARE_AUTO_REFRESH`，默认关。
  需要在真机上确认不会空白/误拦。
- **后台回首页的根因已经查到**：`dumpsys activity exit-info` 里多次出现
  `reason=10 (USER REQUESTED) / subreason=21 (FORCE STOP)`,
  `description=stop com.twitter.android due to single-cleaner`。这是 OriginOS 的后台
  清理器杀进程，冷启动必然回到首页；hook 不能跨进程恢复详情页。先按
  [../notes/X-BACKGROUND-KILL.md](../notes/X-BACKGROUND-KILL.md) 做系统保活，再谈导航恢复。
- **视频**：不支持直播、加密 HLS、带 `EXT-X-BYTERANGE` 的播放列表；只接受
  `video.twimg.com`。Android 13- 的 mp4parser 兜底还没接（当前统一 MediaMuxer）。
- **入口**：只有悬浮按钮，还没做分享面板注入（x-video-catcher 的 state 构造器方案）。
- **SystemUI**：不 hook，不需要 SystemUI 作用域。

## 与上游的关系

只借鉴**思路与公开接口形状**，代码为独立实现：

- 去广告 entryId 语法：TwitterHideAds（GPL-3.0）文档/README
- 列表 hook / 关键词规则模型：X-ADBlockModule（MIT）
- media3 Uri 抓取与 HLS 合流：x-video-save（未声明许可，仅参考行为）
- 分享面板注入（未实现）：x-video-catcher（未声明许可，仅参考设计）
- HLS 解析、下载、合流代码为本项目原创；当前统一用 `MediaMuxer`，Android 13- 的
  mp4parser 兜底尚未接入。
