# FuckDrag 0.2.0

OriginOS 超级拖拽的最小放行模块。**只在 `com.vivo.globaldragdrop` 进程里工作**，
不碰 system_server / launcher / recents / 分屏 / 工作台。

## 四个功能

1. **任意 App 拖拽放行**
   - hook `vb.p.h(String, String)`：正常包名/activity 返回 `true`；
   - hook `bb.a.a(String, String)`：仅 `yb.f` 调用、原值 `null`、列表名是
     `EnhancedDragFeatureListV2` / `EnhancedDragWhiteListV2` 时返回非空占位值。
2. **拖拽设置页列出全部 App**
   - 只对 `jh.z`（设置页加载器）调用 `bb.a.c()` 时合并启动器可见的全部 App；
   - 只对 `jh.z` 调用 `kb.i1.b()` 时取消厂商包过滤；
   - 不修改原缓存，不影响运行时白名单判断。
3. **超级拖拽菜单加入“系统分享”**
   - 在 `TransIslandIntentionBean.getMServiceInfo()` 的列表头部注入一个
     `IntentionService`，operation 为 `skill=shareText`；
   - hook `ne.f.a`，识别 marker 后用拖拽的 `ClipData` 拉起
     `Intent.createChooser(ACTION_SEND / SEND_MULTIPLE)`；
   - hook `ge.a.b` / `he.n.q`，让这个合成项通过能力/安装过滤；
   - 系统 chooser 就是“全部分享目标”的入口：不读厂商白名单，所有能处理
     `ACTION_SEND` 的 App 都会出现在里面。
4. **顶部拖拽岛黑底 -> 液态玻璃**
   - `SpringTransIslandView.g()` 原本把卡片背景设成黑色渐变：
     `0xff000000 -> 0xb3000000`；
   - hook 在它之后把 `Paint` shader 换成半透明白色玻璃渐变
     `0x40FFFFFF -> 0x66FFFFFF`；
   - 这个 View 本身已经在 `onDraw()` 里调用 `VBlurUtils.setBlurRegion()`，
     黑色 paint 换成半透明后，后面的 blur 就会透出来，形成液态玻璃；
   - 不改资源、不改窗口，只在目标进程里换 shader。

## 打包

```sh
./build.sh
# -> dist/FuckDrag-0.2.0.apk
```

APK 结构（modern libxposed）：

```text
AndroidManifest.xml
classes.dex
META-INF/xposed/module.prop
META-INF/xposed/java_init.list
META-INF/xposed/scope.list
```

`module.prop`：

```properties
minApiVersion=102
targetApiVersion=102
staticScope=true
```

`scope.list` 只有一行：`com.vivo.globaldragdrop`。

## 安装/验证

1. 屏幕解锁、人在场；
2. `adb install dist/FuckDrag-0.2.0.apk`；
3. Vector 里启用 `FuckDrag`，scope 勾 **拖放直达**
   (`com.vivo.globaldragdrop`)；
4. 让 `com.vivo.globaldragdrop` 换一个新进程（强停后重新打开，或重启该 App）；
5. 看日志：

```sh
adb logcat -b main -s FuckDrag VectorModuleManager
```

期望：

```text
VectorModuleManager: Loading module dev.ganvivo.fuckdrag
VectorModuleManager: Loaded module dev.ganvivo.fuckdrag successfully.
FuckDrag: FuckDrag 0.2.0 libxposed module loaded process=com.vivo.globaldragdrop
FuckDrag: drag gate vb.p.h -> true
FuckDrag: drag config bb.a.a placeholder installed
FuckDrag: settings app list hook installed (bb.a.c)
FuckDrag: super-drag share menu hook installed
FuckDrag: shareText skill hook installed
FuckDrag: island glass hook installed
```

实际拖拽后还应出现：

```text
FuckDrag: drag gate pass pkg=... activity=...
FuckDrag: settings app list expanded 241 -> 454
FuckDrag: system share chooser launched
FuckDrag: island glass applied
```

## 边界 / 未验证

- 系统分享 chooser 在拖拽浮层场景能否后台启动，要真机点一次验证；
  如果被后台启动限制拦住，日志会是 `system share chooser not launched; skipped`。
- 液态玻璃依赖 `SpringTransIslandView` 的 `VBlurUtils.setBlurRegion()` 路径；
  当前 4.1.3.0 的类名/方法名对得上，但实际 blur 效果要真机看。
- 设置页“全部 App”只影响设置页；运行时拖拽放行由第 1 点负责，不依赖这个列表。
- 没有做“模块内勾选具体分享渠道”的 UI；当前系统 chooser 列出全部。若要按
  App 过滤/排除，需要再加一个模块设置页，属于下一步。
- 不注册 `system` scope，因此不参与 Vector 的 `serial` / system_server 会合；
  `system_server` 没注入不影响本模块。
