# echatApp Core existing 接入实施计划

## 已确认范围

- 模式：`existing` 语义接入，不执行完整 Core Model 重构。
- app namespace：`yumo.achat.app`。
- Core namespace：`yumo.achat.core`。
- 宿主模块：`app`。
- Model 字段前缀：`ach_`。本阶段只登记，不实施全量 1–5 字段扩展。
- 存储前缀：`achat`。
- prod 状态：`DEV_REUSE`。
- prod 地址策略：开发者已确认按交接包执行，临时 prod 完整复用 test 服务、dev Firebase 和 sharedDev；不是正式发布配置。

## 实施阶段

1. [x] 记录接入前工程、Git、环境和测试基线。
2. [x] 通过脚手架 existing 模式生成 prepared 参考工程、锁定 kit 和接入任务；核对现有宿主、页面、资源和业务源码未被覆盖。
3. [x] 比较 prepared 与真实工程的 Gradle、模块、配置、生命周期、DI、网络/会话、归因/埋点、生成、支付和恢复职责，保存语义映射与冲突处理。
4. [x] 在真实工程建立独立 `:core` 边界，将当前唯一生效的后端、会话、生成和支付网关连同测试迁入 Core；app 继续保留 UI/生命周期职责，并通过 typed config 显式传入身份和地址。
5. [x] 使用本机 Temurin JDK17 完成真实目标的 Core/app JVM 测试、Core/app 模拟器测试、Debug 构建和 lint；`doctor` 已运行并停在缺少 config，sharedDev 仍缺失，未运行 `verify`。
6. [x] 完成实际 Core 边界接入阶段的验证、中文提交并推送 `origin/core_v1`；scaffold 全矩阵及平台验收继续按未验收项管理。
7. [x] 接入 `dev/prod` environment 矩阵、固定配置及 AppsFlyer/ThinkingData integration 运行时依赖边界；签名附件缺失时保持门禁失败，不用默认 debug 证书冒充。SDK 尚未由宿主实例化，真实收数另行验收。

## 验收边界

- 基线装配只证明 prepared、锁定材料和接入任务已生成。
- 实际接入必须由真实工程源码、测试、构建及脚手架校验共同证明。
- prepared 的 Retrofit/Hilt/DataStore/Billing/Analytics 实现与现有线上协议不等价，本范围不并行启用第二套会话、支付或任务轮询；详细映射见 `existing接入审查.md`。
- 本范围不运行或宣称 `verify --refactored`、Model schema=2 完整重构或 `pack` 已通过。
- 真实后端、支付、归因和生成平台验收需要匹配的 sharedDev 附件、设备及服务条件；本地构建不能替代真实平台验收。
