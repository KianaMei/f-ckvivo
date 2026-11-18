# vivo 文件管理器删除契约

VPortal Plus `0.1.1` 已在 vivo X Fold6 上由用户确认删除可用。组件、权限及参数取自 vivo 文件管理器 `12.4.6.4`，APK 指纹见 [ANALYSIS.md](ANALYSIS.md)。

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
