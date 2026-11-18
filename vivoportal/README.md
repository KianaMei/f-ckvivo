# VPortal Plus

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
- Hook 锚点、定位参数和反编译复查方法见 [ANALYSIS.md](ANALYSIS.md)。
- vivo 文件管理器的删除契约与验证边界见 [VIVO-RECYCLE.md](VIVO-RECYCLE.md)。
