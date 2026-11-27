# qqmod — QQ/TIM 增强模块战线

> 仓库规则与设备红线见 [AGENTS.md](../../AGENTS.md)。

> 本战线做两件事：一是登记第三方 QQ/TIM 增强模块的**获取渠道与真实性凭据**，便于换机找回、验证同源、识别二次打包；二是预留给后续针对这些模块的**本地补丁**。
> 当前阶段：**仅登记，尚无补丁**。若后续出现 `.patch` / `source-*.patch` / 构建脚本，直接放本目录，仍只维护这一份 `README.md`。
> 不含设备操作、不含任何构建产物或 APK。
>
> 采集时间：2026-11-23 ｜ 实测宿主：QQ 9.3.85（versionCode 16920）/ Android 17（OriginOS 7）/ Vector 2.2（libxposed 102）
> 所有地址均于采集当日实到 200；异常项已单独标注。

## 1. 一览

| | TCQT | FunBox |
|---|---|---|
| 包名 | `com.owo233.tcqt` | `have.fun` |
| 作者 | owo233（callng） | Hicore（前身 QTool 作者） |
| 设备实测版本 | `4.0.2.r700.0fbcae1`（700） | `v3045`（3045） |
| 许可 | GPL-3.0（开源源码） | 闭源，仅分发 APK |
| 活跃分发渠道 | GitHub Releases + Xposed 模块仓库 | **Telegram 频道**（GitHub 仅剩旧镜像） |
| 签名证书 DN | `C=CN, ST=BJ, L=BJ, O=owo233, OU=owo233, CN=TCQT` | `CN=HavaFun` |
| scope | `com.tencent.mobileqq`、`com.tencent.tim` | `com.tencent.mobileqq`、`com.tencent.mm`、`com.tencent.tim`、`com.ss.android.ugc.aweme`、`com.ss.android.ugc.aweme.mobile` |
| 安装形态 | 双格式：直接装 = Xposed 模块；改名 `.zip` 刷入 = Zygisk 模块（**二选一，不可同开**） | 同左：APK = Xposed 模块；含 `zygisk/funloader.so` 可作 Zygisk 刷入（**二选一**） |

## 2. TCQT

### 官方地址

| 用途 | 地址 |
|---|---|
| 源码 / 主发行 | <https://github.com/callng/TCQT> |
| Xposed 模块仓库（Release 镜像） | <https://github.com/Xposed-Modules-Repo/com.owo233.tcqt> |
| Telegram 交流 | <https://t.me/astcqt> |
| Telegram CI（构建） | <https://t.me/citcqt> |
| Discord | <https://discord.gg/PjYenv5F9s> |

### 版本与内容

- 最新 Release：`v4.0.2`（2026-10-28），产物 `TCQT-4.0.2.r700.0fbcae1-release.apk`
- 仓库最近提交：2026-11-22（活跃维护），GPL-3.0，Kotlin，约 317★ / 36 fork（采集当日）
- 设备侧能力：97 个注册 Action / **90 个可见功能**，覆盖消息、净化、通知、外观、系统级（WebView、QUIC、通知渠道、A/B 实验等）
- 注入方式：libxposed API 102（modern）+ legacy + Zygisk 三套 loader
- 文档：`docs/zygisk-art-compatibility.md`（Android 13–17 兼容记录）、`DEXKIT.md`（DexKit 缓存规则）

### 固定值（用于校验后续下载）

```text
证书 SHA-256 : 2c9509a5c9bd7daabd5dbb32cf2cd9250f81a4f7a132ff764836c11c74fe9729
v4.0.2 APK   : b8066708f7fd6604f6a7e9d33334714b33a43c791749bda332fdfda7a25a47dd
```

## 3. FunBox

> ⚠️ **GitHub 上的 FunBox 不是最新版**。仓库 `Xposed-Modules-Repo/have.fun` 只剩发布元数据与两个旧版本，最新构建只发在 Telegram 频道。

### 官方地址

| 用途 | 地址 | 状态 |
|---|---|---|
| 更新频道（最新 APK 在这里） | <https://t.me/QToolCI> | 200（公告名 `FunProject`） |
| 交流 / 反馈群 | <https://t.me/QToolC> | 200（`FunBox Chat`） |
| 贴纸转换机器人 | <https://t.me/QToolHelperBot> | 200（`Sticker Downloader`） |
| GitHub 发布镜像（**旧**） | <https://github.com/Xposed-Modules-Repo/have.fun> | 200，停在 v2173 |
| 后端状态页 | <https://uptime.fpfast.top/> | **522，当前不可用** |

### 版本与内容

- 最新构建：**`FunBox_v3045.Apk`（2026-11-22，8.1 MB，TG 频道第 662 条）**
- GitHub 镜像最新：`v2173`（2026-08-04）；更早 `v1972`（2026-06-21）
- 定位：QQ/微信/TIM 表情包 + 语音包模块（长按表情图标唤起面板、长按消息保存到面板、TG 贴纸转 QQ 表情）；同时带有大量 QQ 增强项（净化、长按菜单屏蔽、聊天聚合等）
- 宿主版本门槛（自 `v2871` 起）：**QQ 9.1.0+ / 微信 8.0.60+ / 抖音 39.0.0+ / TIM 4.1.0+**
- 前身：**QTool** <https://github.com/Hicores/QTool>（AGPL-3.0，840★，**2024-02-09 已归档**；`StickerPanelPlus`/`VoiceHelper`/`QQCleaner` 等模块即 FunBox 的祖先）
- 结构变化（v2173 → v3045）：`libfvm.so`（自研 VM）、`libshooker.so`、`libmaphider.so`（内存映射隐藏）**已全部移除**，只剩 `libfun.so` + `libloader.so`；dex 由 Java 改为 Kotlin 重写

### 固定值（用于校验后续下载）

```text
证书 DN      : CN=HavaFun
证书 SHA-256 : 7e4eaa9b14140bc00ff764c45627d86f87259c95bf007467b0237823c220a26f
              （v2173 与 v3045 同一把证书，可据此确认 TG 包与 GitHub 包同源）
v3045 APK    : 139404d48dae136de8742d2ced25fd192a2cc77a016d9fac222fbc2777235c0a
v2173 APK    : 029b409a3f3c5c9c54d12996438eb83af6b9f4186a3721f3c40428379b6d658c
```

### 运行时落点（排查用）

| 内容 | 路径 |
|---|---|
| 模块自身把 APK/so 解到宿主的 | `/data/user/0/com.tencent.mobileqq/files/mmkv<U+200B>/`（`mmkv` 后跟零宽空格） |
| 按账号的状态记录 | `/data/user/0/com.tencent.mobileqq/cache/funbox-user-status/` |
| 总开关 | `/data/user/0/com.tencent.mobileqq/shared_prefs/funconf.xml`（只有 `disable_module`，功能开关不落盘） |

## 4. 校验方法

```bash
# 1) 证书指纹（与上面的固定值比对）
apksigner verify --print-certs <apk> | grep -E "DN:|SHA-256 digest"

# 2) 包内容指纹
sha256sum <apk>
```

FunBox 没有公开源码、没有 hash 清单，**升级前比对证书指纹是唯一可靠手段**；证书不一致即视为第三方重打包，不要安装。

## 5. 不收录说明

- **QWish**（`top.qwish.xposed`）：闭源 + 商业加固 + 账号/`pay/redeem-remaining-time` 时长兑换，仅有站点 `plugin.qwish.top`、`sticker.qwish.top`，无公开仓库与可校验发布渠道，故不登记。
- 本目录不存放任何 APK、反编译产物或抓取的 TG 页面；这些一律放 `cache/`（已被 `.gitignore` 屏蔽）。
