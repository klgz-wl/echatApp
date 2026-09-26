# Core重构验收审查记录

## 职责与公共API映射

本轮在 existing 模式下保留 echatApp 既有业务、Git 与远程，仅把可复用后端数据访问层从 `app` 迁入 `core`，由宿主通过 `AchatCoreBackendFactory` 注入 `AchatBackendConfiguration`。Core 不再读取宿主 `BuildConfig`，网络 baseUrl、applicationId、clientVersion 均由 app 边界传入；宿主 UI、Compose 页面、ViewModel 状态和业务流程保持原入口。

基线装配已覆盖 `core`、`integration/analytics-appsflyer`、`integration/analytics-thinkingdata`、dev/prod flavor、sharedDev 签名、dev/prod Google resources、配置指纹与 APK 身份检查。`prod` 当前为 FORMAL 运行配置：包名 `com.zorv.app`、显示名 `zorv`、运行时 REST/Payment/WebSocket 指向 release 后端 `release.appjoly.com`，新发起的 Top Up 直接使用 `LEGACY` 路由；历史已持久化的 `SERVICE` 订单仍沿原路由恢复和对账。`release.zorv.date` 是用户访问 App/站点的域名，不作为 Android 后端 API 域名。当前 release 仍使用 sharedDev 签名用于本地验证，不把它冒充为 Google Play 正式上传签名验收。

`.core-scaffold/project.json` 中的 `prod_mode=DEV_REUSE` 是初始化阶段的锁定问答元信息，脚手架 `doctor` 会按该值校验历史接入参数；正式运行模式以后续 `config/app.properties` 的 `kit.prod.mode=FORMAL` 与生成的 `BuildConfig` 为准。不要为了让 project 元信息看起来与正式运行配置一致而改写该字段，否则会破坏脚手架校验。

## Model台账与兼容策略

`model-plan.json` 使用 schema=2，完整登记脚手架基线 100 个自有 Model 的目标源码、原字段去向和新增字段策略。新增字段前缀为开发者明确确认的 `ach_`，统一冻结为 `ach_modelTrace`，实现入口为生产源码 `core/src/main/java/yumo/achat/core/model/CoreRefactorModelRegistry.kt`。对当前工程中不存在的脚手架参考 App UI 状态模型，台账明确由 Registry 承接兼容审查入口；当前 echatApp 自有 Image/Video UI 业务不被替换。

扩展登记不注入既有 data class 主构造器，因此不改变 equals/hashCode、copy、StateFlow 去重、支付/上传/任务重试 key 或旧本地记录反序列化行为。旧数据缺失扩展字段时按空串默认值恢复；敏感字段 token/password/secret/key/url/originalJson 不作为 join 来源。

## 宿主接入与验证边界

已完成的真实本地验收包括脚手架完整性、doctor、基线 verify、refactored 静态计划检查、Gradle 单测、lint 和 dev/prod APK 身份检查。脚手架官方 verify 任务列表仍按锁定工具执行；此外，本工程额外提供 `:app:verifyProdReleaseRuntimeConfig`、`:app:verifyFormalRelease` 和 `:app:verifyCoreFullChainEvidence`，分别用于可重复检查 prod/release 运行地址、支付路由、Firebase 开关、正式签名/确认门禁，以及在缺少本机平台验收 evidence 文件时阻止误称“全链路完整”。真实平台验收仍需外部条件：后端 dev/release 服务联通、Google Play Billing 测试账号、AppsFlyer/ThinkingData/Firebase/后端事件控台回看、上传/生成/支付的真实沙箱数据。dev 已接入 Firebase Analytics、Crashlytics、Messaging 依赖与延迟初始化 sink/service，Manifest 显式移除 FirebaseInitProvider，并在地区及用户同意门禁之后初始化；prod Firebase 相关开关仍保持关闭，直到正式 Firebase/Crashlytics/Messaging 平台验收完成。本报告不把本地构建通过等同于这些平台验收。

当前手写宿主已把真实 Compose、生成和支付控制器接入应用级统计 runtime：包括冷/暖启动、后台超时、页面/商店曝光、Tab、模板曝光与使用、匿名登录、上传/生成终态、支付发起/结果和第三方页面事件。匿名启动会强制重新认证并要求 profile.is_new；真实迟到归因补报成功后按当前 user 刷新 profile 与 app_mode。Google Play 收入只在 ProductDetails 提供真实金额/币种且后端确认 fulfilled 时记录。上述仍属于源码与本地验证，不代表四端控制台、真实投放或真实交易已经验收。
