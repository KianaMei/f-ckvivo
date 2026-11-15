# X 后台回来“跳回首页”的根因证据

设备：vivo X Fold6 (V2545A)，Android 16，X 12.42.0-prod.01。
命令（只读）：

```bash
adb shell dumpsys activity exit-info com.twitter.android
```

## 关键记录

```
timestamp=2026-11-14 09:04:07.722 pid=17571 ... process=com.twitter.android
reason=10 (USER REQUESTED) subreason=21 (FORCE STOP)
description=stop com.twitter.android due to single-cleaner

timestamp=2026-11-12 12:59:28.750 pid=18995 ... process=com.twitter.android
reason=10 (USER REQUESTED) subreason=21 (FORCE STOP)
description=stop com.twitter.android due to single-cleaner

timestamp=2026-11-09 20:29:23.787 pid=20314 ... process=com.twitter.android
reason=10 (USER REQUESTED) subreason=21 (FORCE STOP)
description=stop com.twitter.android due to single-cleaner

timestamp=2026-11-09 14:12:47.629 pid=13387 ... process=com.twitter.android
reason=3 (LOW_MEMORY) subreason=0 (UNKNOWN) description=low memory
```

## 这说明什么

1. **不是 X 的“返回”逻辑把页面弹回首页，而是 X 进程被系统杀掉了。**
   `single-cleaner` 是 OriginOS 的后台清理器（不是 Android 的 LMK），它会
   `force-stop` 后台应用；X 的 `rss` 经常在 1.1–1.4 GB，更容易被盯上。
2. 进程被杀后重新打开只能**冷启动**：X 从首页或账号页重新加载，自然看不到
   之前的视频/帖子详情。任何 Xposed hook 都无法跨越进程死亡保留页面栈。
3. 另外还有 `LOW_MEMORY` 记录（2026-11-09），说明内存压力也在参与。

## 两个问题要分开

| 现象 | 根因 | 能不能用 hook 解决 |
|---|---|---|
| 一段时间后台回来，首页自动刷新 | X 生命周期/自动刷新逻辑（进程还活着） | ✅ ReX 的 Home reselect / lifecycle guard，已移植到 XHook |
| 从账号视频/帖子详情回来，变成账号首页/信息流 | **进程被 single-cleaner / LMK 杀掉，冷启动** | ❌ hook 不能跨进程；需要系统保活 |

## 处理顺序

### 1. 先让系统别杀 X（推荐，先做）

OriginOS 常见路径（不同版本名称略有差异）：

- 设置 → 电池 → 后台耗电管理 → X → **允许后台高耗电 / 允许后台运行**
- 设置 → 应用管理 → X → 电池 → **允许后台活动**
- 设置 → 电池 → 更多设置 → 关闭 **“后台应用清理 / 智能清理 / 单应用清理”**
- 最近任务卡片下拉 → **锁定 X**（防止一键清理）

如果系统有“single-cleaner / 单应用清理”开关，优先关掉它。

### 2. adb / root 白名单（可选，需要你手动执行）

```bash
adb shell dumpsys deviceidle whitelist +com.twitter.android
adb shell cmd appops set com.twitter.android RUN_IN_BACKGROUND allow
adb shell cmd appops set com.twitter.android RUN_ANY_IN_BACKGROUND allow
```

这些命令不一定覆盖 vivo 自己的 cleaner，但能排除 Doze/后台限制；执行后观察
`exit-info` 是否还出现 `single-cleaner`。

### 3. 模块层面

- 已加 `RefreshGuard`：
  - Home reselect：把 `(boolean, boolean) -> boolean` 的第二个参数置 false（照抄
    ReX `ja2` case 18）。
  - lifecycle：拦截 `VIEWPORT_AWARE_AUTO_REFRESH`（刚回前台且已有内容时）。
  - 这只解决“进程还活着但自动刷新”的情况。
- **不建议**做“root 定时拉起 X”之类的保活：耗电、违反系统意图，而且冷启动仍会丢页面。

### 4. 怎么验证是进程被杀还是导航重置

```bash
# 记下当前 PID
adb shell pidof com.twitter.android
# 锁屏/后台 10 分钟，再回来
adb shell pidof com.twitter.android
# 如果 PID 变了，或 exit-info 新增 single-cleaner/LOW_MEMORY，就是进程被杀
adb shell dumpsys activity exit-info com.twitter.android | head -30
```

如果 PID 没变、仍然跳回首页，那才是 X 自己的导航/刷新逻辑，我们再继续挖
X 12.42 的 Activity/Compose 导航状态。
