# Re:X (one.dot.rex) 1.8.2 静态安全审计

审计日期：2026-11-15
审计对象：`Re.X-1.8.2.apk`（GitHub Release `2611142-1.8.2.261114`，发布 2026-11-13）
样本路径：`xhook/apks/rex/Re.X-1.8.2.apk`

## 0. 结论先行

**未发现恶意行为。** 权限最小、无遥测、无动态加载、无隐私数据采集；唯一的联网行为是
GitHub Release 更新检查与下载，且更新包有完整的包名/版本/minSdk/**签名证书**校验。
模块确实会执行两条 root 命令，但均为设置页里的用户主动按钮（重启 X / 重启系统界面），
命令内容固定，不接受外部输入。

风险点不在“偷数据”，而在：**闭源 + 自动更新 + 需要 SystemUI 作用域**。
建议按第 7 节的用法约束使用。

## 1. 样本身份

| 项 | 值 |
|---|---|
| 包名 | `one.dot.rex` |
| versionName / code | `1.8.2.261114` / `2611142` |
| minSdk / targetSdk | 31 (Android 12) / 37 |
| APK SHA-256 | `788e9bd121665405ce0ae9c10f8242fcbfee4a71b5dabaa10e9fc8a5a3496b51` |
| 大小 | 9,139,082 bytes（解包 4,227 项） |
| 签名方案 | v2（无 v1 / 无 v3） |
| 签名证书 SHA-256 | `0ff9c9d08e92fbaa948643f73ed2b0a422e44f7a7f2c709d2323be57b5be3c09` |
| 证书主体 | CN=1Dot, OU=Dev, O=1Dot, L=Nanjing, ST=Jiangsu, C=CN（自签） |
| 代码规模 | 3,287 类 / 15,480 方法 / 1,056,234 条指令（JADX） |

签名证书用于更新自校验：新 APK 的 `SigningInfo.getApkContentsSigners()` 必须与已安装
模块完全一致，否则抛异常中止。这也意味着**任何第三方重打包/改签的 1.8.2 都无法被官方
更新链覆盖**，反之官方更新也不能被任意证书伪造。

## 2. 权限与组件面

权限只有 3 个：

```xml
android.permission.INTERNET
android.permission.REQUEST_INSTALL_PACKAGES
one.dot.rex.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION   (signature 级，自用)
```

没有存储、通讯录、短信、定位、相机、麦克风、`QUERY_ALL_PACKAGES`、无障碍、悬浮窗等敏感权限。

组件统计：1 个 Activity、4 个 Receiver、7 个 Provider。

### 2.1 导出的组件与访问控制

| 组件 | 导出 | 代码里的实际校验 | 评价 |
|---|---|---|---|
| `StartupLogReceiver` | 是（自定义 action） | 收到广播后只回传一个 Binder；Binder 的 `onTransact` 里 opcode 1 校验调用方为自身 UID 或 `com.twitter.android`，opcode 2 校验为 `com.android.systemui` | 外部应用能触发广播，但写不了日志；影响仅限于空转/刷日志 |
| `StartupLogProvider` | 否 | `Binder.getCallingUid() != Process.myUid()` 直接抛异常 | 仅模块自身可读写 |
| `LocalIdentityAvatarProvider` | 是（`content://one.dot.rex.identity`） | 只允许自身 UID 或持有 `com.twitter.android` 包的 UID 读取；只接受 `r` 模式；FileProvider 式解析，拒绝越界路径 | 头像“伪装”功能的必要暴露，读取面仅限 X |
| `LocalIdentityAvatarVisibilityReceiver` | 是（自定义 action） | 只做 `grantUriPermission("com.twitter.android", ...)`，然后回结果 | 任意应用可触发，但受益者只有 X，不能借此读文件 |
| `XposedProvider`（libxposed 标准服务） | 是 | 上游 libxposed 协议：接收框架侧 binder | 所有 libxposed 模块都这样；不属于 ReX 特有风险 |
| `HistoryProvider` / `HistoryReceiver` | 是 | 但 manifest 里 `android:enabled="false"` | 未启用，实际不生效 |
| `MainActivity` / `LazyLauncher` 别名 | 是 | 普通桌面入口 | 正常 |

FileProvider 路径也做了最小化：

- `one.dot.rex.update.files` → 仅 `cache/updates/`
- `one.dot.rex.logs.files` → 仅 `cache/startup-logs/`

不存在 `root-path`、`external-path` 之类的大范围共享。

## 3. 联网行为

全 APK 提取出的 URL/域名（已排除 AndroidX/许可证噪声）：

| 用途 | 地址 |
|---|---|
| 更新检查 | `https://api.github.com/repos/Xposed-Modules-Repo/one.dot.rex/releases/latest` |
| 更新下载 | `https://github.com/Xposed-Modules-Repo/one.dot.rex/releases/download/...` |
| 项目主页 | `https://github.com/Xposed-Modules-Repo/one.dot.rex`、`https://github.com/1-dot` |
| 赞助 / 社群 | `https://ko-fi.com/1dot`、`https://t.me/re_x_mod`、`https://t.me/re_x_mod/327` |
| 辅助链接 | `https://www.apkmirror.com/uploads/?appcategory=twitter`（版本说明里的外链） |
| 分享域替换 | `x.com`、`twitter.com`、`fxtwitter.com`、`fixupx.com` 等（本地改写分享链接） |

**没有**统计 SDK、崩溃上报、广告、IP 探测、陌生域名或硬编码 IP。`zd0` 里有一个
`BitmapFactory.decodeStream(new URL(str))`，调用方是本地身份头像预览，`str` 来自 X 的
头像地址，只用于显示，不携带模块数据。

## 4. 自动更新链（重点）

代码位置（JADX 反编译）：

- `defpackage/gi1.java:177` — 请求 GitHub latest release
- `defpackage/fw0.java:141-227` — 下载到 `cache/updates/update.part`
- `defpackage/ak2.java:67-118` — 安装前校验
- `defpackage/i7.smali:1519` — `FileProvider` + `ACTION_VIEW` 拉起系统安装器
- `defpackage/dd2.java:699-719` — 无“安装未知应用”权限时跳系统设置页

校验顺序（任何一步失败都会抛 `IllegalArgumentException` 中止，不会安装）：

1. 从 GitHub API 取 release 元数据（HTTPS）。
2. 下载大小 ≤ 100 MiB，且写入字节数必须等于 `Content-Length`（`"Incomplete APK"`）。
3. SHA-256 必须等于 release 资产声明的摘要（`"APK checksum mismatch"`）。
4. `getPackageArchiveInfo` 解析失败 → 拒绝。
5. 包名必须等于 `one.dot.rex`。
6. `versionCode` 必须**大于**当前安装版本。
7. `minSdkVersion` 不得高于本机 SDK。
8. 新 APK 的 `apkContentsSigners` 集合必须与已安装 APK 完全一致，否则
   `"APK signing certificate mismatch"`。
9. 以上全部通过才 `FileProvider.getUriForFile` + `Intent.ACTION_VIEW` +
   `application/vnd.android.package-archive`，由系统安装器弹窗，用户再确认一次。

这套校验比大多数开源模块都严格，**没有静默安装、没有 root 安装、没有忽略签名**。

## 5. root 使用

代码位置：`defpackage/r60.java:j(String)`。实现是 `new ProcessBuilder("su","-c",cmd)`，
60 秒超时，读取输出，非 0 退出视为失败。全 APK 只有两处调用：

| 触发 | 命令 | 位置 |
|---|---|---|
| 设置页「重启系统界面 / Restart System UI」 | `pkill com.android.systemui \|\| killall com.android.systemui` | `ji1.java:62` |
| 设置页「快速重启 X / Restart X」 | `am force-stop --user <uid> com.twitter.android && am start -W --user <uid> -n '<launcher component>'` | `rv.java:253` |

- 命令字符串固定，只有 user id 和 launcher component 是变量；component 来自
  `PackageManager.getLaunchIntentForPackage`，并做了单引号转义。
- 没有任何网络下发命令、没有读取配置文件拼接 shell、没有 `Runtime.exec` 的其他调用点。
- 也没有监听开机广播；不会在后台自动重启 X 或 SystemUI。

`重启系统界面` 是 ReX 主题/通知样式生效的必要操作，属于用户主动行为；如果你不想让它
碰 SystemUI，别点这个按钮，也别把 SystemUI 勾进作用域（代价是通知样式/开屏相关功能不可用）。

## 6. 其他敏感面

| 检查项 | 结果 |
|---|---|
| `DexClassLoader` / `InMemoryDexClassLoader` / `PathClassLoader` | 无 |
| `Runtime.exec` / `ProcessBuilder` | 仅上述 `su` 两处 |
| `System.load` / 自带 `.so` | 只有 `androidx.graphics.path`（AndroidX 官方库），无自定义 native |
| 反射 | 大量，但集中在 Compose 内部与 X 结构扫描（`bf2`/`be2`/`cd2` 等），没有 `Class.forName` 加载外部 dex |
| `DexFile` / `ZipFile` | 用于枚举 X 自己的 APK、扫描方法签名（结构适配），不加载代码 |
| 通讯录 / 短信 / 通话记录 | 无 |
| 定位 / 相机 / 麦克风 / 录音 | 无 |
| 账号 / `AccountManager` | 无 |
| `Settings.Global/System/Secure.put` | 无（只读 `boot_count` 写日志） |
| 通知读取 | 无 `StatusBarNotification` / `NotificationListenerService`；SystemUI 作用域只做开屏/Toast/通知外观 hook |
| 剪贴板 | 只写（复制日志、Compose 文本框），没有读取剪贴板内容外发 |
| 设备标识 | 无 IMEI/SN/AndroidID 读取 |
| 加密/编码 | SHA-256 用于更新校验与备份校验；Base64 仅 Compose 内部 |
| 后台服务 / 开机自启 | 无 Service、无 BOOT_COMPLETED |

## 7. 风险与使用建议

**残余风险**

1. **闭源**：本次审计只覆盖 1.8.2 这一个二进制；作者可以在后续版本改变行为。
2. **自动更新**：应用内检查更新，签名校验 + 系统安装器二次确认，但如果你一直点“安装更新”，
   等于持续信任作者的签名密钥。
3. **`REQUEST_INSTALL_PACKAGES`**：能力本身敏感，虽然当前实现安全。
4. **SystemUI 作用域**：会向 `com.android.systemui` 注入代码；当前未发现读取通知内容，
   但这是额外的攻击面。
5. **root**：两条命令固定且手动触发；但模块一旦被替换为恶意版本，root 能力会被滥用。

**建议**

- 只从官方 Release 下载，安装前核对
  `sha256 = 788e9bd121665405ce0ae9c10f8242fcbfee4a71b5dabaa10e9fc8a5a3496b51`；
- 记录证书指纹 `0ff9c9d0…be3c09`，以后每次更新用 `apksigner verify --print-certs` 或
  `keytool`/androguard 对一下；
- 若不需要通知样式/开屏动画，**不要**把 SystemUI 加进作用域，也不需要点“重启系统界面”；
- 关闭“自动检查更新”或只在需要时手动检查（模块设置里可关；更新仍走上面的校验链）；
- 不要在别的设备上覆盖安装第三方改签版本。

**结论：可以装，按上面约束用。** 它不是“偷数据的模块”，但闭源软件永远保留“下个版本变坏”
的可能性，审计只能证明这个版本干净。
