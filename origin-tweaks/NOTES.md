# OriginTweaks 0.1.6 运行记录

> 这份文件只记录**真机证据**。没有 logcat 的项，保持“未验证”。
> 加载规则与弯路复盘见 `../origin-tweaks-REVIEW.md`。

## 1. 安装前

- [ ] 目标 App 版本（`com.vivo.globaldragdrop` / `com.bbk.launcher2` /
      `com.vivo.recents`）记录到下表
- [ ] Vector manager / daemon 版本一致
- [ ] 只有一个 `vectord`
- [ ] 备份当前 `daemon.apk` / `manager.apk`

## 2. 加载验证

```sh
adb logcat -c
adb logcat -s VectorModuleManager VectorDaemon VectorModuleManager OriginTweaks
```

期望顺序：

1. `VectorModuleManager: Loading module dev.ganvivo.origintweaks`
2. `VectorModuleManager: Loaded module dev.ganvivo.origintweaks successfully`
3. `OriginTweaks: libxposed module loaded process=... systemServer=...`
4. `OriginTweaks: libxposed onPackageLoaded pkg=...`

任何一步缺失，先停下查加载/scope，不要继续加 hook。

## 3. 功能矩阵

| # | 功能 | scope | 目标版本 | 操作 | 日志/现象 | 结论 |
|---|---|---|---|---|---|---|
| 1 | 拖放直达 | globaldragdrop | 4.1.3.0 | 长按拖文字/图片 | `drag gate pass` | 未验证 |
| 2 | 系统分享 | globaldragdrop | 4.1.3.0 | 超级拖拽菜单 | `system share chooser launched` | 未验证 |
| 3 | 液态玻璃 | globaldragdrop | 4.1.3.0 | 打开超级拖拽设置 | `glass applied` | 未验证 |
| 4 | 设置页全部 App | globaldragdrop | 4.1.3.0 | 打开设置页 | `settings app list expanded` | 未验证 |
| 5 | 内屏堆叠 | recents | — | 设置里选“堆叠”后进后台 | `inner recents layout -> 堆叠` | 未验证 |
| 6 | 任务菜单工作台 | recents | — | 外屏任务菜单 | `recents atomic-task menu unlocked` | 未验证 |
| 7 | 外屏手势 | launcher | — | 外屏多任务手势区 | `outer multi-task gesture unlocked` | 未验证 |
| 8 | 外屏 4 分屏 | launcher | — | 分屏选择 3/4 个 App | UI 槽位/实际分屏 | 未验证 |
| 9 | 原子工作台 | system | — | 外屏进入工作台 | system_server 异常/卡片 | 未验证（高风险） |

## 4. 失败时先看什么

1. `logcat -s VectorModuleManager` 有没有 `Loaded module ... successfully`；
2. `logcat -s OriginTweaks` 有没有 `onPackageLoaded pkg=...`；
3. 没有第 2 条 → 目标进程没被注入：检查 scope、目标进程是否重启、
   `ConfigCache` 是否已刷新；
4. 有第 2 条但没有 hook 日志 → `Class.forName` / 方法签名失败，
   看 `hook failed` / `NoSuchMethodError`；
5. 有 hook 日志但功能无效 → 锚点选错或返回值被后续逻辑覆盖，
   回到反编译核对 call site。

## 5. 已知未验证项

- WMShell `mRootTaskController` 在外屏的卡片布局/动画；
- 外屏 4 分屏的实际几何与 App 最小尺寸拒绝；
- 系统 chooser 在拖拽浮层场景的后台启动限制；
- `system` scope 下 hook 对 system_server 稳定性的影响。

这些只能真机验证；静态审计不能替代。
