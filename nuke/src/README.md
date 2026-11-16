<p align="center"><img src=".github/icon.svg" width="128" alt="NukeApp-OSS"></p>

<p align="center">
  <a href="https://github.com/dartcv/NukeApp-OSS/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/dartcv/NukeApp-OSS?style=flat-square&logo=github" /></a>
  <a href="https://github.com/dartcv/NukeApp-OSS/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/dartcv/NukeApp-OSS/ci.yml?branch=dev&style=flat-square&label=CI" /></a>
  <a href="https://developer.android.com/"><img alt="Android" src="https://img.shields.io/badge/Android-arm64--v8a-3DDC84?style=flat-square&logo=android&logoColor=white" /></a>
</p>

# NukeApp-OSS

NukeApp-OSS 是给**抖音**（`com.ss.android.ugc.aweme`）用的 Android Hook 模块。装好以后，抖音的设置页里会多出一行「NukeApp-OSS」，点进去是一百多项功能开关，改动在抖音重启后生效。

## 能做什么

设置页按分类列出功能，只有「禁用双击点赞」默认开启，其余都默认关闭，用哪个开哪个。

| 分区 | 分类 |
| --- | --- |
| 视频 | 推荐流、播放控制、画质与缓存、视频页面、手势与操作、弹幕、广告 |
| 社交 | 评论、消息、聊天玩法、分享与转发、个人主页、隐私 |
| 应用 | 布局与显示、使用时长、应用与系统、其他 |

举几个例子：不上报已读状态（可只对黑名单或白名单里的会话生效）、自定义火花天数（只有你自己看得到）、自动续火、自动领取火星（每天定时领取一次，可只选部分好友）、自动抢红包、聊天消息复读（气泡旁边的「+1」）、去除转发数量限制。每一项在设置页里都有一句说明。

除了内置功能，还能在设置页的「脚本」页导入 `.nks` 脚本包，用 JavaScript 写自己的功能。脚本怎么写、怎么打包见[脚本 SDK](sdk/script-sdk/README.md)。

## 需要什么

- Android 8.1（API 27）及以上。
- 抖音（不含国际版 TikTok）。
- 下面三种方式里选一种让模块进入抖音。三种方式的功能一样，区别只是怎么把模块放进去：

| 方式 | 适合谁 | 要求 |
| --- | --- | --- |
| Xposed | 已经在用 LSPosed 之类的框架 | 支持经典 Xposed（API 82）或现代 libxposed（API 102）的框架 |
| Zygisk | 有 root，用 Magisk、KernelSU 等 | 开启 Zygisk，设备是 `arm64-v8a` |
| 修补 | 没有 root | 用 NukePatcher 给抖音打补丁，Android 9 及以上，4 KiB 页大小 |

同时启用了多种方式也没关系：同一个进程里只有最先到达的那个入口生效，不会重复 Hook。

## 安装

### 第一步：拿到模块 APK

用 Xposed 或 Zygisk 方式时，要把 NukeApp-OSS 的 APK 装到手机上，设置页本身就在这个 APK 里。用修补方式时不用安装它，只要把这个 APK 作为文件选进 NukePatcher。

### 方式一：Xposed

1. 在框架里启用 NukeApp-OSS，作用域选抖音。
2. 彻底重启抖音（强行停止后再打开）。

### 方式二：Zygisk

1. 把模块 APK 改名为 `.zip`（也可以在模块首页「导出模块 APK」，再改名）。**这个 zip 必须和已安装的 APK 是同一个构建。**
2. 在 root 管理器里「从本地安装」这个 zip。
3. 确认 Zygisk 已开启，并且抖音**不在**排除列表（DenyList）里，否则模块可能不会被加载进抖音。
4. 重启手机。

### 方式三：修补（没有 root）

1. 安装 NukePatcher，打开「修补」页。
2. 分别选一个 NukeApp-OSS 模块 APK 和一个完整的单 APK 抖音安装包。
3. 校验通过后开始修补，签名并核对嵌入内容后导出。
4. 自己安装导出的 APK。

## 使用

1. 打开抖音，进入设置，找到「NukeApp-OSS」。
2. 按分类找到想要的功能，打开开关。有额外选项的功能点进去设置。
3. **强行停止抖音再重新打开**，改动才会生效。

## 出问题时

**抖音里没有「NukeApp-OSS」这一行**

- Xposed：检查作用域里勾了抖音，并且彻底重启过抖音。
- Zygisk：依次检查 Zygisk 是否开启、模块是否启用、抖音是不是在 DenyList 里、有没有重启手机、设备是不是 `arm64-v8a`。然后在电脑上看日志（需要 adb）：

  ```bash
  adb logcat -c
  adb shell am force-stop com.ss.android.ugc.aweme
  adb shell monkey -p com.ss.android.ugc.aweme -c android.intent.category.LAUNCHER 1
  adb logcat -d -s 'NukeApp-OSS/Zygisk'
  ```

  看到 `zygisk_started` 说明运行时起来了。抖音进程里有时看不到这条日志，这时可以读抖音数据目录里的 `cache/nukeapp-zygisk.txt`（需要 root）：里面记着这次启动有没有走到模块、`bootstrap` 是 `returned` 还是 `failed`，失败时还写着停在哪一步。
- 修补：确认装的是修补后的那个包，而不是原版。

**某个功能没效果**：功能开关改完要重启抖音。抖音更新后，部分功能依赖的内部代码可能变了，这样的功能会失效，需要等模块跟进。

## 组成

三个应用互相独立：

| 名称 | 应用 ID | 作用 |
| --- | --- | --- |
| NukeApp-OSS（模块） | `io.github.nuke.oss` | 三种入口都带上的模块，加设置管理界面（中英文）。可以把模块 APK 按原字节导出。 |
| NukePatcher | `io.github.nuke.oss.patcher` | 把模块嵌进抖音的修补器，用固定版本的 [LSPatch](https://github.com/JingMatrix/LSPatch)。不依赖模块，也不要求设备上装了模块。 |
| Hook Fixture | `io.github.nuke.oss.fixture` | 测试应用，用来确认一个 Hook 是否生效。 |

Hook 只会装在两处：Hook Fixture，以及抖音的主进程和少数功能声明过的非隔离子进程（小程序、代码热更新）。

## 许可证

仓库按目录分两种许可证：

| 目录 | 许可证 | 原因 |
| --- | --- | --- |
| `apps/patcher`（NukePatcher） | [GPL-3.0](apps/patcher/LICENSE) | 它内置 LSPatch（GPL-3.0） |
| 其余：模块、`core`、`sdk`、`fixtures`、`tools` | [MIT](LICENSE) | |

版权归 dartcv，见 [NOTICE](NOTICE)。模块和修补器是各自独立的 APK，修补器只是把模块当文件嵌进目标应用，这属于并列分发，不改变各自的许可证。

模块的原生库静态链接了 LSPlant 和 DexBuilder（LGPL-3.0）。按 LGPL 的要求，你可以替换这两个库并重新链接：`python3 tools/build_native.py --export-relink <目录>` 会把重新链接所需的目标文件和脚本导出到那个目录，细节见 [native 说明](apps/module/src/main/cpp/README.md)。其余第三方组件的许可证文本在各应用的 `src/main/resources/third-party` 目录。

## 免责声明

本项目与抖音、字节跳动没有任何关联，也未获其认可。修改应用可能违反其服务条款，请只在自己的设备上、自担风险使用。

## 致谢

NukeApp-OSS 站在这些开源项目之上，感谢它们的作者：

- 界面与设计：[QAuxiliary](https://github.com/cinit/QAuxiliary)（cinit）。模块主界面的设计参考了它。
- 注入与 Hook：[LSPlant](https://github.com/LSPosed/LSPlant) 和 [DexBuilder](https://github.com/LSPosed/DexBuilder)（LSPosed）、[Dobby](https://github.com/jmpews/Dobby)（jmpews）、[xDL](https://github.com/hexhacking/xDL)（hexhacking）、[Zygisk 示例模块](https://github.com/topjohnwu/zygisk-module-sample)的 API 头文件（topjohnwu）、Xposed API 与 libxposed。
- 修补：[LSPatch](https://github.com/JingMatrix/LSPatch)（JingMatrix 的分支）。
- 查找与反射：[DexKit](https://github.com/LuckyPray/DexKit)（LuckyPray）、[KavaRef](https://github.com/HighCapable/KavaRef)（HighCapable）。
- 脚本：[QuickJS](https://bellard.org/quickjs/) 和 [quickjs-kt](https://github.com/dokar3/quickjs-kt)（dokar3）。
- 其他：[parallel-hashmap](https://github.com/greg7mdp/parallel-hashmap)（greg7mdp）、Silk-Codec-Android、AndroidHiddenApiBypass（LSPosed）、Jetpack Compose。

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=dartcv/NukeApp-OSS&type=Date)](https://star-history.com/#dartcv/NukeApp-OSS&Date)
