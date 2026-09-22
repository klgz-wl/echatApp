# echatApp Core existing 接入审查

## 实际接入结果

- 真实工程新增 Android library `:core`，namespace 为 `yumo.achat.core`，继续使用目标工程的 AGP 9.3.2、Gradle 9.5、compileSdk 37 和 Java 17 字节码目标。
- 原 `app/data/backend` 的网络客户端、解析模型、会话存储、401 恢复、图片上传、生成任务、商品/支付网关和 profile 编辑网关整体迁入 `yumo.achat.core.backend`，对应 JVM 测试一并迁入 Core。
- app 通过 `CoreBackendFactory` 显式传入 `ACHAT_API_BASE_URL`、application id 和后端登记版本 `ACHAT_CLIENT_VERSION=2.0.0`；Core 不读取 app `BuildConfig`。
- `AchatSessionStore` 继续使用已有 `achat_backend_session`，没有生成第二个 device id，也没有启动 prepared 的 DataStore/AnonymousStartup。
- Compose 页面、导航、播放器、图片选择、WebView、Billing Activity 桥接和 ViewModel 保留在 app；app 使用 Core 中同一套 Repository/模型，不存在旧包与新包同时运行。

## prepared 到目标的语义取舍

| 能力 | 目标工程处理 | 未直接启用 prepared 的原因 |
| --- | --- | --- |
| 会话与匿名登录 | 将现有唯一实现迁入 Core | prepared refresh/invalidate 与当前“同 device id 匿名重登、失败保留旧会话”不等价 |
| 模板、profile、钱包 | 保持现有 API 和 UI 聚合语义 | prepared DTO 不完整覆盖当前 profile 编辑、首购资格和页面状态 |
| 图片与生成 | 保持 `resources` 上传后使用 `resource_id` 创建任务 | prepared 使用直接 multipart image，且幂等字段形状不同 |
| 支付与 Billing | 保持现有 server-authoritative 路由和唯一 BillingClient | 并行启用 prepared 会产生双建单、双轮询或双购买恢复 |
| Analytics/归因/地区 | 本阶段不激活第二套 SDK/runtime | 目标尚无已确认 SDK key、Firebase/签名材料和生命周期门禁 |
| Model 扩展 | 仅记录 `ach_` | 本范围不是完整 Core 重构，不实施 1–5 字段或 `verify --refactored` |

## 验证与未验收项

- 接入前已运行真实工程 `testDebugUnitTest assembleDebug lintDebug`。
- Core 边界采用 TDD：新增 app→Core typed-config 测试，先因 Core 类型不存在而失败，再完成模块和迁移使其通过。
- 最终本地命令实际通过：app 63 项 JVM 测试、Core 45 项 JVM 测试；模拟器上 app 29 项、Core 1 项 instrumented 测试；app/Core Debug 构建和 lint。
- 静态 UI 测试改用 debug-only TestActivity 与 `AchatAppGateway` 确定性 fake，避免真实模板/profile 响应改变导航断言；另保留生产 `MainActivity` 冷启动 smoke test，覆盖默认 factory 到真实 Core 的装配路径。
- 脚手架 `doctor --project .` 已实际运行：锁定 kit 完整性通过，随后以退出码 2 停在 `缺少接入文件：config/dev.properties`。目标当前还没有 JDK17 和匹配的独立 sharedDev 附件，因此未运行 `verify`，sharedDev APK 身份和 dev/prod flavor 矩阵尚未验收。
- 未执行真实匿名登录、生成扣钻/退款、Google Billing、第三方支付、归因或四端收数验收；模拟器 UI 与本地构建不代表这些平台已通过。
