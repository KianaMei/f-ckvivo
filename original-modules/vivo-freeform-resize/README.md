# VivoFreeformResize（小窗无极调比例）

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

面向 **vivo X Fold6（PD2545D / OriginOS 7）** 的纯 **libxposed API 102** 原创小窗增强模块。

- **纯 `com.android.systemui`（WMShell）作用域，零 `system_server` Hook，零重启风险**。
- 折叠屏**内屏 & 外屏**、横竖屏自适应，松手通过原生 `TRANSIT 1510` + `WCT.setBounds` 触发 App 真实重新排版。

## 核心原理与 Hook 锚点

| # | 类与方法 | 作用 |
|---|---|---|
| 1 | `DragInputListener.<init>` | 非桌面模式下将默认等比锁死的 `ScaleGestureEventHandler` 替换为原生 `VeiledScaleGestureEventHandler`，并重建共享 `DragScaleAnimationController` 的 `MiniFreeFormGestureEventHandler` |
| 2 | `ScaleHelper.getBoundaryDimens(int[], Rect, int, boolean)` | 动态按当前屏幕尺寸（内屏/外屏）下发独立宽高最小/最大范围 |
| 3 | `ScaleHelper.changeBounds(...)` | 左/右边独立调宽、底边独立调高、左下/右下角独立自由调宽高，越界软阻尼 |
| 4 | `ScaleHelper.calculateFinalSize(...)` | 取消平板 S/M/L 三档比例吸附，按用户实际尺寸计算 `finalDisplayBounds`、密度自适应 `scale`（严格满足服务端 `0 < scale < 1.0f`）与真实 `taskBounds` |
| 5 | `VeiledScaleGestureEventHandler.resizeTask(...)` | 当 `taskBounds` 尺寸有变化时强制触发 `WCT.setBounds`，确保真实重新排版 |
| 6 | `VeiledScaleGestureEventHandler.onDragScaleStateChanged` + `FreeformDefaultSizeManage.isLessThanFreeformMiniWindowCritical` | 仅四角同时缩至极小时触发迷你挂起窗，单边拉扁（横屏视频）/拉窄不误触 |

## 构建

```sh
cd original-modules/vivo-freeform-resize
sh build.sh
# 产物: original-modules/vivo-freeform-resize/dist/VivoFreeformResize-0.1.0.apk
```

## 真机验证记录（2026-11-28）

- 设备：vivo X Fold6（`PD2545D` / OriginOS 7 / Android 17，官方 LSPosed `2.2.1 (7912)` API 102）
- 签名证书 SHA-256：`22:BF:…:64:15`，仅勾选 `com.android.systemui`（零 `system_server` Hook）
- 按精确 PID 重载 `com.android.systemui` 后实机日志：`VivoFreeformResize 0.1.0 loaded process=com.android.systemui systemServer=false` → `VivoFreeformResize ready hooks=7`（7/7 零报错），支持通过 `settings put global ganvivo_freeform_resize_enabled 0/1` 运行期实时开关。
