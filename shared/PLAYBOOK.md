# HOOK-DEBUG-PLAYBOOK — libxposed / Vector 模块排障方法论

> 2026-11-21 从静盒×小红书 UI-07 冷启动刷新攻坚（0.9.13→0.9.27，11 个版本、4 次"假修复"）
> 提炼。适用于本仓库所有 libxposed API 102 模块（静盒、LiquidTab、drag-all、XHook、nuke…）。
> 各战线自己的细节留在各自 README；这里是**跨战线通用**的判据与纪律。

## 0. 一句话总则

**凡是"看起来生效了"的结论，都必须有一条不依赖 logcat 的落盘证据。**
本项目已经踩过至少四次"日志写成功、行为没生效"和"日志没出现、其实已生效"的坑。

## 1. 证据从哪里来（按可信度排序）

| 证据 | 可信度 | 说明 |
|---|---|---|
| 状态回传文件（如静盒 `shared_prefs/status_report.xml` 的 `meta.session`/`updated_at`） | ★★★★★ | 每进程会话一条，能和进程 `STIME` 对齐，证明"模块已注入且 hook 已装" |
| 模块自己写的落盘日志（宿主 files 目录 / `/data/local/tmp`） | ★★★★★ | 完全绕开 logd；`chatty` 吞不掉 |
| 行为判据（功能是否 hit、UI 是否变化） | ★★★★★ | 最终裁决，但需要用户操作 |
| `logcat` 按 tag 抓 | ★★☆☆☆ | **会被 logd chatty 静默丢弃**；只在进程不刷屏时可信 |
| `/proc/<pid>/maps` 找模块 APK / liblspd | ★☆☆☆☆ | 模块类多由内存/dex 装载，**看不到不等于没注入**；只配合作辅证 |
| "日志里没有 X" 推"X 没发生" | ☆☆☆☆☆ | 错误率最高的一条。零日志 ≠ 没加载/没调用 |

**落盘日志的规范写法**（模板见 `xhs/NOTES.md`「探针模板」）：
- hook 安装时 Application 往往还没创建 → 拿不到 Context，**先写内存缓冲，15s/45s `postDelayed` 补写**；
- 首选 `context.getFilesDir()`，无 Context 时退回外置绝对路径
  `/storage/emulated/0/Android/data/<pkg>/files/`（属主 App 可写，root 可读）；
- 文件 200KB 截断重置；每行带 `SystemClock.elapsedRealtime()`。

## 2. 定位"真链路"：探针 + 栈

逆向静态分析（jadx/xref）能给出候选，但**调用是否发生只有运行时有发言权**。

1. **先列候选**：xref 找目标方法的调用方（`python -I <tools>/run_xref.py <apk> method "cls#name"`），
   把"入口类/执行器类/回调类"都列上。
2. **全挂只读探针**：按类名+方法名挂**所有重载**（注意 Kotlin 默认参数桥接会生成多个同名方法），
   记录参数前 3 个（`toString()` 截断 500 字符）+ 命中次数上限。
3. **关键节点附调用栈**：在"结果落地/请求组装"这类方法里追加栈（过滤框架帧），
   栈会直接告诉你真正的发起方——比猜一百遍强。
4. **一次冷启动出结果**：复现路径越短越好（冷启动 = 最稳定的一次性触发）。
5. **探针只存在于临时版本**：出正式版前整块删除，不随包发布。

**实锤案例**：UI-07 原锚点 `zid.m0#u0` 静态审计 72/72 命中，但运行时**整场冷启动零调用**——
真正发请求的是 `djd.f#b(djd.k)`。锚点命中只证明方法存在，不证明它被调用。

## 3. 钩子里的三个致命细节

### 3.1 反射必须用**宿主** classloader

```java
// install(module, cl) 时缓存：hostCl = cl;
Class<?> c = Class.forName("io.reactivex.Observable", false, hostCl);
```

模块自己的 classloader 解析不到宿主依赖（RxJava/OkHttp/Retrofit…）会抛 `ClassNotFoundException`。
**如果 catch 里 `return chain.proceed()`，就变成静默放行**——日志写着 SKIP、行为照旧，
排查成本极高（静盒 0.9.20–0.9.23 四轮全栽在这）。

### 3.2 所有 catch 分支**必须落盘**

```java
} catch (Throwable t) {
    diagFile("GATE FAILED " + t);          // ← 缺这一行就会重演上面的悲剧
    HookKit.error(module, ...);
    return chain.proceed();
}
```

### 3.3 返回值**形态**决定宿主状态机

替换 RxJava/协程链的返回值时，"逻辑上等价"不等于"状态机等价"：

| 返回 | 效果 |
|---|---|
| `Observable.empty()` | 只有 onComplete，**无 onNext** → 依赖 onNext 记录"已加载"的宿主会停在未加载态（静盒实测：下拉刷新永久禁用） |
| `Observable.just(emptyList())` | onNext + onComplete 都发生 → 状态机收尾正常，同时"没有新内容"可应用 ✅ |

**排除法**：如果跳过请求后出现"功能 A 好了但功能 B 坏了"，先怀疑状态机，而不是功能 B 另有 hook 点。

### 3.4 jadx 字段名 ≠ 运行时字段名

jadx 显示的混淆字段（`f188605g` 之类）运行时 `getDeclaredField` 可能取不到
（不同构建/内联/合成差异）。**参数判定优先解析 `obj.toString()`**（Kotlin data class 的
toString 包含全部字段名=值），比反射稳。字段名仅在实测可用时使用。

## 4. 判别字段：先找"只有这条路径才有"的特征

同一功能的多条触发路径，参数常常高度相似。找判别字段而不是加大概率阈值：

- 静盒冷启动自动首屏 vs 手动下拉：两者都是 `INITIAL_REFRESH + noteIndex=0 + cursorScore=''`，
  **唯一区别是 `launchScenario`（冷启动=1，手动=null）**。少了它就把下拉刷新一起拦了（0.9.25 实测）。
- 找判别字段的方法：把两条路径的完整参数 toString 各抓一份，diff。

## 5. 装机与验收纪律（与 AGENTS.md §1 红线配合）

- 装 APK 永远走 `adb install -r`（同签名覆盖），**用户在场 + 亮屏解锁**，每次单独授权。
- 装完**不必**一律打开模块 App：配置镜像跨 APK 更新保留，只有首次安装、改过设置、或宿主状态文件里 `keys` 明显偏少时才需要（判定见 AGENTS.md §3），然后重启目标 App 进程。
- 复现后 **10 秒内**拉日志/落盘文件——晚了缓冲区就轮转/被 chatty 吃掉。
- 用户体感是最终裁决；一轮改动只验一个行为点，别把多个变量混在一次验收里。

## 6. 通用 debug 循环（照抄即可）

```
1. 静态：xref 列候选入口 → 读方法体确认语义
2. 动态：探针（全重载）+ 关键节点栈 → 落盘
3. 复现：一次最短路径（冷启动/单次点击）
4. 读证：优先状态回传/落盘文件；logcat 只作辅证
5. 定位：确认真链路 + 判别字段 + 返回形态
6. 收窄：只改一个决定，探针保留
7. 复验：同一路径重跑，对比落盘
8. 固化：拆探针 → 正式版 → patch 入库 → 文档记录（含失败判据）
```

## 7. 战绩索引

| 战线 | 案例 | 结论 |
|---|---|---|
| 静盒 × 小红书 | UI-07 冷启动不刷新（2026-11-21） | `djd.f#b` 闸门 + `just(emptyList())` + `hostCl`；详见 `xhs/NOTES.md` |
| LiquidTab × 小红书/B站 | 返回动画丢失（coverfix2/3、dskinfreeze） | 采样闸门 recipe + 详情侧 `isFinishing` 冻结；详见 `liquidtab-mine/README-MINE.md` |
| nuke × 抖音 | 冷启动 UI 丢失（已破案） | 双模块各掐一刀开屏状态机；详见 `nuke/ANALYSIS.md` |
| XHook × X | 去广告锚点失效（0.2.9→0.3.1） | 锚点随版本漂移，必须运行时验证 hit；详见 `xhook/notes/X-12.42-ANCHORS.md` |
