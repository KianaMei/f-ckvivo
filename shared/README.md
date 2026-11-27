# 公共构建与诊断工具

跨战线的通用工具在这里维护。先读仓库规则和设备红线。[AGENTS.md](../AGENTS.md)

| 要做的事 | 入口 |
|---|---|
| 在模块 `build.sh` 中复用 SDK/JDK 探测、打包与签名 | [build-lib.sh](build-lib.sh)。默认从仓库的 `cache/`、`forks/` 和 `shared/signing/` 取输入，可用 `LAB_CACHE`、`FORKS_DIR`、`SIGNING_DIR` 覆盖 |
| 核对构建签名或查模块使用的 keystore | [签名台账](signing/README.md) |
| 编译 libxposed API 102 或旧版 Xposed 模块 | [API 102 编译依赖](libs/libxposed-api-102.jar) · [旧版 API 编译桩](stubs/xposed-legacy/) |
| 做 ADB 只读采集与框架状态检查 | [ADB 辅助库](adb-lib.sh) · [只读 MCP 网关](tools/adb-mcp/README.md) |
| 查通用 Hook 排障方法、证据口径与工具链自检 | [PLAYBOOK.md](PLAYBOOK.md) |
| 查抖音 41.2 DEX 分析、规则指纹与构建辅助脚本 | [tools/douyin/](tools/douyin/) |

模块版本、宿主适配与构建入口留在各战线的维护文档。下载样本、抓取输出与其他中间产物放在仓库的 `cache/`。
