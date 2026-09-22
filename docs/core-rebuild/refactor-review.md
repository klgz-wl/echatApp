# Core重构验收审查记录

## 职责与公共API映射

本轮在 existing 模式下保留 echatApp 既有业务、Git 与远程，仅把可复用后端数据访问层从 `app` 迁入 `core`，由宿主通过 `AchatCoreBackendFactory` 注入 `AchatBackendConfiguration`。Core 不再读取宿主 `BuildConfig`，网络 baseUrl、applicationId、clientVersion 均由 app 边界传入；宿主 UI、Compose 页面、ViewModel 状态和业务流程保持原入口。

基线装配已覆盖 `core`、`integration/analytics-appsflyer`、`integration/analytics-thinkingdata`、dev/prod flavor、sharedDev 签名、dev/prod Google resources、配置指纹与 APK 身份检查。`prod` 当前按已确认的 `DEV_REUSE` 策略复用 dev 身份，未伪造正式生产资料。

## Model台账与兼容策略

`model-plan.json` 使用 schema=2，完整登记脚手架基线 100 个自有 Model 的目标源码、原字段去向和新增字段策略。新增字段前缀为开发者明确确认的 `ach_`，统一冻结为 `ach_modelTrace`，实现入口为生产源码 `core/src/main/java/yumo/achat/core/model/CoreRefactorModelRegistry.kt`。对当前工程中不存在的脚手架参考 App UI 状态模型，台账明确由 Registry 承接兼容审查入口；当前 echatApp 自有 Image/Video UI 业务不被替换。

扩展登记不注入既有 data class 主构造器，因此不改变 equals/hashCode、copy、StateFlow 去重、支付/上传/任务重试 key 或旧本地记录反序列化行为。旧数据缺失扩展字段时按空串默认值恢复；敏感字段 token/password/secret/key/url/originalJson 不作为 join 来源。

## 宿主接入与验证边界

已完成的真实本地验收包括脚手架完整性、doctor、基线 verify、refactored 静态计划检查、Gradle 单测、lint 和 dev/prod APK 身份检查。真实平台验收仍需外部条件：后端 dev 服务联通、Google Play Billing 测试账号、AppsFlyer/Firebase/ThinkingData 控台事件回看、上传/生成/支付的真实沙箱数据。本报告不把本地构建通过等同于这些平台验收。
