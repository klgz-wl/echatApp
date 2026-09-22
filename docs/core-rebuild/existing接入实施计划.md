# echatApp Core existing 接入实施计划

## 已确认范围

- 模式：`existing` 语义接入，不执行完整 Core Model 重构。
- app namespace：`yumo.achat.app`。
- Core namespace：`yumo.achat.core`。
- 宿主模块：`app`。
- Model 字段前缀：`ach_`。本阶段只登记，不实施全量 1–5 字段扩展。
- 存储前缀：`achat`。
- prod 状态：`DEV_REUSE`。

## 实施阶段

1. 记录接入前工程、Git、环境和测试基线。
2. 通过脚手架 existing 模式生成 prepared 参考工程、锁定 kit 和接入任务；核对现有宿主、页面、资源和业务源码未被覆盖。
3. 比较 prepared 与真实工程的 Gradle、模块、配置、生命周期、DI、网络/会话、归因/埋点、生成、支付和恢复职责，保存语义映射与冲突处理。
4. 在真实工程增量接入独立 Core 和 integration 模块，保持当前 echat 后端契约、用户状态及 UI 行为；行为变更先写失败测试，再完成最小实现和回归。
5. 在真实目标根运行项目单测、构建、lint 以及脚手架 `doctor`/`verify`。签名或 JDK17 缺失时保留原始失败，并继续不依赖该条件的源码工作。
6. 将基线装配与实际接入分别验证、中文提交并推送 `origin/core_v1`。

## 验收边界

- 基线装配只证明 prepared、锁定材料和接入任务已生成。
- 实际接入必须由真实工程源码、测试、构建及脚手架校验共同证明。
- 本范围不运行或宣称 `verify --refactored`、Model schema=2 完整重构或 `pack` 已通过。
- 真实后端、支付、归因和生成平台验收需要匹配的 sharedDev 附件、JDK17、设备及服务条件；本地构建不能替代真实平台验收。
