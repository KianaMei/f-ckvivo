# 抖音 41.2.0：平板消息页与双栏条件

依据：本地 `douyin/apk/douyin-41.2.0.apk` 的 SHA-256 为 `4d161a8eb4da14ee9931b87476e9f0de3847797006e3485e51c4cf497a9d5a99`，与装机 APK 读回一致。

## 官方判断

- **选择容器**：`classes24.dex` 中的 `X.0r4f.LIZ()` 根据 `MessageListService.LIZ()` 选择 `MessageFragmentPad` 或普通消息页。`MessageListService.LIZ()` 要求 `(im_pad_opt_exp & 1) != 0`，且设备被抖音识别为 Pad 或折叠屏。`im_pad_opt_exp` 的实包默认值为 `0`，账号实际值未读取。
- **识别折叠屏**：`classes48.dex` 的 `IFoldCommonServiceDelegate.isFoldDevice()` 通往 `FoldCommonServiceImpl`。`classes55.dex` 的 `X.0BZH.LIZ()` 有 vivo 分支，读取 `FtDeviceInfo.getDeviceType()` 是否为 `foldable`；函数还有缓存和配置判断。
- **旧版布局**：`classes46.dex` 中 `MessageFragmentPad.YK(widthDp, heightDp)` 要求 `getFoldStatusV3(context)` 不为 `0` 或 `1`，并达到 `im_pad_or_fold_opt_size_setting` 的 `fold_min_width` 和 `fold_min_scale`，实包默认分别为 `690dp` 和 `0.66`。真平板分支 `ZK()` 另有配置判断，要求宽度至少 `800dp` 且宽不小于高。
- **新版布局**：`im_split_screen_framework_exp` 启用时，`MessageFragmentPad.sL(widthDp, heightDp)` 要求页面宽度至少 `690dp`、宽高比大于 `0.8`、右栏至少 `320dp`。左栏随宽度取 `345dp`、`360dp` 或 `390dp`，右栏使用余宽减 `0.5dp`。该页在创建时计算尺寸，并响应 `DuxWindowAdaptManager` 的断点更新。

## Nuke 接法与验证

- `FoldableChatSplit` 仅在抖音已经识别设备为可折叠时，把 `MessageListService.LIZ()` 的否定结果改为肯定。不改全局设备类型、DPI、AB 配置或单栏/双栏的原生切换条件。开关默认关闭。
- 本次只读核对：vivo 的展开内屏显示配置约 `801 × 867dp`，外屏竖屏约 `345 × 778dp`。这不是消息页实际测量值，不能代替实机切换验证。
- 两组 JVM 测试 26 项通过；APK 构建、签名、功能类和装机哈希核对通过。模块全局架构检查仍报告三处不在本次差异中的依赖问题。待验证：展开进「消息」点开会话后出现双栏，折回外屏后退回单栏。
