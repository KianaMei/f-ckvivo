# ExteraLess 直接注入已撤回

`TelegramSelectionFeature.java.disabled` 为 0.1.7 失败实验的原代码，只保留供分析，不能编入 APK。

ExteraLess 13.2.1-979e175 启动期实际安装三项 hook 后，多次 native SIGSEGV。故障指令地址相同，为 `0x2fd61f0220`；栈来自 ART `ClassLinker::SetupClass` / `MarkClassInitialized`，分别经过消息数据库线程、Chaquopy / `com.extera.plugins.scrolltomedia.MediaScrollCore.load`。尚未用隔离实验确定具体冲突 hook，不能把“延迟安装”视为已验证修复。

通过 Vector 官方 CLI 移除 `com.exteraless.app/0` scope 后，应用冷启动成功并保持 PID 28717。没有清除应用数据或禁用用户插件。完整物证见 `../../logs/exteraless-crash-0.1.7.txt`、`../../logs/exteraless-emergency-scope-verified.json` 和 `../../logs/exteraless-recovery-confirmed.txt`。

0.1.8 的代码拒绝此进程和包的模块生命周期；推荐 scope 删除此包，打包检查禁止包含上述实验类。禁止自动重新添加该 scope 或用连续闪退试错。
