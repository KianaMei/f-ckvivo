# LiquidTab

LiquidTab 是适用于 LSPosed 的 Android 液态底栏模块。2.1.0 包含模块管理界面、共享玻璃渲染和按应用组织的适配代码。应用 ID 保持为 `io.github.offlineglass`，以兼容既有安装和本地设置。

## 特别致谢：kyant0 / AndroidLiquidGlass

特别感谢 [kyant0 的 AndroidLiquidGlass（Backdrop）](https://github.com/Kyant0/AndroidLiquidGlass)。LiquidTab 的液态玻璃折射方案参考、借鉴并改编了该项目的开源实现；这些上游贡献不应被视为 LiquidTab 独创。该项目采用 Apache License 2.0。

LiquidTab 同时使用 Miuix、AndroidX 和 Android 原生渲染能力，并包含自身的原生 View 桥接、动态高光、生命周期管理和应用适配代码。具体许可见 [NOTICE.md](NOTICE.md)。

## 运行条件

- Android 15（API 35）或更高版本
- 已安装并启用 LSPosed；将 LiquidTab 的作用域限制在需要适配的应用
- 建议每次设置应用适配后重启目标应用进程

模块离线运行，不需要账号、网络授权或在线更新。底栏设置通过本地配置接口传递给目标进程。

## 当前正式适配范围

正式版作用域见 [LSPosed 作用域清单](app/src/main/resources/META-INF/xposed/scope.list)。其中包含 31 个适配应用，以及供哔哩哔哩超级岛联动使用的 SystemUI 作用域；后者不是独立底栏适配。

2.1.0 不包含 Reddit、超级小爱或小米相册适配。

## 主要设置与默认值

- 默认中文；液态玻璃和描边开启。
- 纯色模式、磨砂玻璃、姿态动态效果、经典导航键、动态配色及底部渐变模糊关闭。
- 底色透明度 30%（深浅色一致），模糊半径 0，底栏高度 50 dp，深色高光描边强度 50%。
- 以上为未保存偏好时的默认值，覆盖安装保留已保存设置。

## 项目结构

- `app/src/main/java/io/github/offlineglass/hook/adapters/<应用键>/`：应用专属配置、适配器、Hook 信号与该应用的工作说明。
- `app/src/main/java/io/github/offlineglass/targets/`：按稳定顺序汇总应用配置，并管理正式列表和查找。
- `app/src/main/java/io/github/offlineglass/hook/`：模块入口、适配调度、通用导航发现和共享宿主/绘制生命周期。
- `app/src/main/java/io/github/offlineglass/rendering/`：跨应用共享的液态玻璃、模糊和材质绘制实现。
- `app/src/main/java/io/github/offlineglass/config/`：模块及目标应用共用的配置协议、默认值和本地存储。
- `app/src/main/java/io/github/offlineglass/ui/`：模块主页、应用列表、设置页和说明二级页面。

## 构建

需要 JDK 21、Android SDK Platform 37、Build Tools 37 和网络可用的 Gradle 依赖源。Windows PowerShell：

```powershell
./gradlew.bat :app:assembleRelease
```

Release 构建产物默认位于 `app/build/outputs/apk/release/`。工程不携带签名密钥，也不在源码中设置个人签名信息；发布者应使用自己的密钥签名，并安全保存密钥。

## 开源与第三方材料

LiquidTab 源码按 GNU GPL v3 发布，完整文本见 `LICENSE`。直接依赖及授权说明见 `NOTICE.md`。随 APK 使用的若干应用导航图标属于第三方应用素材，详见 `THIRD_PARTY_ASSETS.md`；本项目许可证不授予这些素材的额外权利。请在公开分发前确认相关素材的授权范围。

本工程不包含第三方应用 APK、反编译输出、用户数据、签名密钥或设备调试记录。赞赏码由项目作者提供，仅用于模块说明页。


