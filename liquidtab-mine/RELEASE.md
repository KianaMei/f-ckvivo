# LiquidTab 2.1.0

- 应用 ID：`io.github.offlineglass`，versionCode：223。
- 默认中文；默认开启液态玻璃与描边。纯色、磨砂、姿态动态、经典导航键、动态配色关闭。
- 底色透明度 30%，模糊半径 0，底栏高度 50dp，深色高光描边强度 50%。
- 默认值只用于无已保存设置的情况，不重置用户偏好。
- 正式作用域：31 个适配应用及 SystemUI（哔哩哔哩超级岛联动）。不包含 Reddit、超级小爱、小米相册；不启用淘宝回落混合试点。
- 保持离线，不包含网络、VPN、地区伪装功能。
- 本地验包与覆盖安装后等待用户验证；未上传 GitHub。

## 构建与验收记录

- 首页闪电与卡片使用 KernelSU 首页状态绿配色，图标外缘与下方卡片对齐。
- 离线环境缺少 Compose 组映射与 Lint 工具，使用
  `./gradlew.bat :app:assembleRelease -x :app:produceReleaseComposeMapping -x :app:mergeReleaseComposeMapping -x :app:lintVitalRelease --offline --max-workers=2`。
  这次不生成 Compose 组映射，也未运行发布 Lint；R8 和资源裁剪实际执行。
- 最终 APK 为 10,766,587 字节，SHA-256：
  `24BA25FA3B0A27BB83A83D1D549E64124F0B209490F6CE048F1DB7C0995F8B32`。
- 已核对与 2.0 的签名证书指纹一致，成品为非 debuggable，版本号和作用域正确；
  DEX、清单及原生/资产条目审计通过。覆盖安装成功；视觉及目标应用行为待用户验收。
