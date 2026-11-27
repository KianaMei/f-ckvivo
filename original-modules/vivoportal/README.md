# VPortal Plus

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

在 vivo 文件库的选中动作栏保留原生「分享」，加入「在图库中打开」「在文件管理器中打开」和「删除…」。模块包名为 `local.codex.vivoportal`，Vector 建议作用域为 `com.vivo.vportal`。

- 「在图库中打开」用于图片和视频，从首个选中项开始预览，可继续滑动浏览当前列表。
- 「在文件管理器中打开」用于单选，打开父目录并请求定位、高亮选中项。媒体无法读取路径时使用 URI 跳转。
- 「删除…」把选中项交给 vivo 文件管理器的确认界面，具体删除语义以原生提示为准。

## 开发历程

2026-11-18 在 vivo X Fold6（V2545A，Android 17 / OriginOS 7）确认 `VPortalPanel` 属于系统文件库 `com.vivo.vportal` 17.0.1.180。普通布局和 PC 布局各自只创建分享项，因此从文件库进程扩展选中动作栏。

在 OnePlus OPD2413 对照了 ColorOS 文件袋 17.10.2。`FileManagerFileBag` 属于 `com.coloros.filemanager`，其 `FileActionDelete` 通过同进程的 `RecycleOperation` 实现最近删除。回到 vivo 后确认文件库持有 `OPEN_FILE_LIST`，将删除交给 vivo 文件管理器的 `RecycleMoveInOutSideActivity`。

| 版本 | 关键步骤与验证 |
|---|---|
| `0.1.0` / 1 | 根据原始 DEX 校正 `O2.o` 等反射锚点，接通两套动作栏及三个新入口。安装后用户确认图库打开可用。 |
| `0.1.1` / 2 | 修正建议作用域字符串数组，增加自适应应用图标和三枚矢量图标，为 `FILE_OPEN` 补齐定位与高亮参数。已同签名覆盖安装，用户确认删除可用。 |

滚动和高亮已通过本地 Intent 参数重放，实际动画仍待真机确认。

## 构建

在 Git Bash 中使用 JDK 17+ 和 Android SDK 构建。

```sh
cd vivoportal
./build.sh
```

产物为 `dist/vivoportal-0.1.1.apk`。签名固定使用 `.signing/vivoportal.keystore`，证书 SHA-256 为 `b9276b1ff42d72c21f2e467820d93889340f75f13b7e7ea99de3e839678c92e0`。安装和进程操作遵循仓库根目录的设备规则。

## 关键文件

- 模块入口与三个动作的实现见 [MainHook.java](src/local/codex/vivoportal/MainHook.java)。
- Hook 锚点、定位参数和反编译复查方法见 [ANALYSIS.md](#实现锚点与系统应用跳转原-analysismd)。
- vivo 文件管理器的删除契约与验证边界见 [VIVO-RECYCLE.md](#vivo-文件管理器删除契约原-vivo-recyclemd)。

> 本文件已合并原 `ANALYSIS.md` 与 `VIVO-RECYCLE.md`（单文档维护）；签名位于 `../../shared/signing/vivoportal.keystore`。

---

## 实现锚点与系统应用跳转（原 ANALYSIS.md）

# VPortal 实现锚点

## 适配基线

| APK | 版本 | APK SHA-256 |
|---|---|---|
| `com.vivo.vportal` | `17.0.1.180` / `170001180` | `3edd1d38c952d2afbff48d5a7bb7f64655107ff6b2b465cdf1d8d3fe1c48f129` |
| `com.vivo.gallery` | `11.0.3.111` / `110003111` | `65e163205188fad4b202efef9449cc47e06e1d6f31060ee8340063f02d7325ff` |
| `com.android.filemanager` | `12.4.6.4` / `120406040` | `d756da9f14714175f15f746b4c6edd6cb5ba720b0f2e5070469011887d625557` |

`dumpsys window` 显示 `VPortalPanel` 是 `com.vivo.vportal` 进程提供的 `APPLICATION_OVERLAY`。内屏桌面上看到的动作栏来自 `pc_root_layout.xml`，普通布局来自 `root_layout.xml`。

## Hook 与选择状态

- `RootLayout.o()` 和 `PCRootLayout.o()` 各自添加一个原生分享项。`RootLayout.onFinishInflate()` 随后安装 `V3.H` 点击监听器。
- 模块在两套布局的 `x(int, int)` 后按选中数量更新动作。`VLinearMenuView.m(O2.o)` 添加菜单项，`t()` 刷新。模块按自有菜单 ID 去重，并在 `V3.H.a(O2.o)` 里分流，原生分享继续交给宿主。
- `O2.C0053o` 是 JADX 给 `O2.o` 起的显示名。运行时用 `O2.o.d` 取菜单 ID，用 `V3.H.a` 字段取根布局。遇到名称漂移时以原始 DEX 的类、方法与字段为准。
- 图库列表由 `GalleryPage.l(false)` 获取。`N3.g` 的原始字段 `b` 为 ID、`e` 为 URI、`f` 为 MIME、`h` 为视频标记、`k` 为选中态。
- 文件列表由当前文件子页的 `BaseFileTab.h(false)` 获取。`N3.c.b` 为路径，`N3.c.h` 为选中态。只在点击动作时读取当前列表。

实现见 [MainHook.java](src/local/codex/vivoportal/MainHook.java)。

## 系统应用跳转

图库打开使用 `com.vivo.gallery.ACTION_VIEW`，按图片或视频类型构造 `MediaStore` URI，限定包 `com.vivo.gallery` 后解析组件。`view_src=VPortal`、`local_file_custom_flag=true`、`local_file_custom_id_list` 和 `local_file_custom_index` 沿用宿主 `b4.h.a()` 的约定。跳转后通过 `b4.q.l(Context)` 收起文件库。

文件管理器 `12.4.6.4` 的 `FileManagerListActivityViewHelper.g0()` 分别接收导航目录与定位目标。`0.1.0` 传入文件路径和 `OpenParentAndLocationDestFile=true`，本地重放发现定位与高亮参数缺失。`0.1.1` 使用以下契约。

```text
Component = com.android.filemanager/.FileManagerListActivity
Action = com.android.filemanager.FILE_OPEN
FilePathToBeOpenAfterScan = 父目录的绝对路径
AfterCreateFileToLocationDestFile = 选中文件的绝对路径
is_need_show_highlight = true
OutsideOpenDir = false
```

`FileManagerListActivityViewHelper.d0()` 把目标转发为 `key_location_path`，列表用它滚动定位。高亮分支 `lf2.h()` 与 `kf2.c(RecyclerView)` 读取高亮开关和目标路径。图片和视频优先从选中 URI 读取 `_data`，取不到时通过 `ACTION_VIEW` 交给 `ViewNonCompressedFilePositionActivity`。本地重放确认新版同时传对目录和目标路径。

删除使用文件管理器自己的确认界面，完整参数见 [VIVO-RECYCLE.md](#vivo-文件管理器删除契约原-vivo-recyclemd)。

## 打包与复查

- 模块沿用 `assets/xposed_init`。`AndroidManifest.xml` 的 `xposedscope` 引用 `res/values/xposed_scope.xml` 中的字符串数组。旧版把它放在 `res/array/`，`aapt2 dump resources` 显示为文件型资源，Vector 读取不到数组。新版显示 `(array) size=1` 且值为 `com.vivo.vportal`。
- 应用自适应图标在 `res/mipmap-anydpi-v26/`，三个 `24dp` 动作矢量在 `res/drawable/`。模块从自己的资源包加载动作图标，沿用宿主当前显示配置。
- 复查 ROM 更新时先通过 `pm path` 和 `dumpsys package` 核对包路径、版本和权限，再只读拉取目标 APK。文件库基线来自 `/system/app/vPortal/vPortal.apk`。用 JADX 1.5.6 查调用链，用 `dexdump` 核对真实符号，用 `aapt2 dump xmltree` 核对导出组件及权限。
- 本地验证覆盖 APK 构建、签名一致性、建议作用域、矢量资源与文件定位参数。装机与用户验证结果见 [README.md](README.md)。

---

## vivo 文件管理器删除契约（原 VIVO-RECYCLE.md）

# vivo 文件管理器删除契约

VPortal Plus `0.1.1` 已在 vivo X Fold6 上由用户确认删除可用。组件、权限及参数取自 vivo 文件管理器 `12.4.6.4`，APK 指纹见 [ANALYSIS.md](#实现锚点与系统应用跳转原-analysismd)。

## 启动确认

```text
Action = com.vivo.filemanager.intent.action.OPEN_RECYCLE_FILE_CONFIRM_DELETE
Component = com.android.filemanager/.recycle.view.RecycleMoveInOutSideActivity
Permission = com.vivo.filemanager.permission.OPEN_FILE_LIST
```

目标 Activity 声明 `exported=true`。设备的 `dumpsys package com.vivo.vportal` 显示该权限已经授予文件库。模块在 `com.vivo.vportal` 进程内调用，具体见 [MainHook.java](src/local/codex/vivoportal/MainHook.java) 的 `recycleIntent()`。

| 选中项 | Intent 参数 |
|---|---|
| 图片、视频 | `del_is_path=false`，`del_media_ids` 为有效的 MediaStore ID 列表 |
| 普通文件 | `del_is_path=true`，`del_paths` 为选中的共享存储绝对路径列表 |

两种请求都带 `from=com.vivo.vportal`。模块检查权限、去重，并在发送前校验媒体 ID 和文件路径范围。

## vivo 删除与恢复

`RecycleMoveInOutSideActivityViewHelper.b()` 读取参数。图片和视频先由文件管理器把媒体 ID 解析为路径，再由该 Activity 展示原生确认。确认后 `zs4.onDeleteFileStart()` 交给文件管理器的删除任务。

任务按设置和文件类型决定处理方式。可回收文件由 `ft4` 移入共享存储的 `.vivoFileRecycleBin`，`sr4` 对应的 `fm_recycle.db` 保存 `src_path`、`recycle_path` 等信息。用户以确认框文案判断能否恢复。恢复入口是文件管理器的「最近删除」，其 Activity 为 `RecycleMainActivity`。

文件操作、媒体索引与回收站数据由 vivo 文件管理器管理。用户实测覆盖删除操作。

