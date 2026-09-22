# echatApp Core existing 接入报告

## 结论

本轮完成了两个可独立核验的阶段：

1. existing 基线装配：锁定 kit、prepared 参考工作区、确认参数和接入计划已生成，未覆盖现有宿主及业务。
2. 真实 Core 边界接入：当前正在运行的后端、会话、生成、钱包/支付及 profile 网关已从 app 迁入独立 `:core`，app 通过 typed config 和公开 gateway 使用 Core；没有并行启动 prepared 的第二套认证、Billing 或任务轮询。

这不是完整 Core 重构，也不是 scaffold 全矩阵或真实平台验收。Model 前缀 `ach_` 仅被确认和登记，没有执行全量 Model 1–5 字段扩展。

## 已确认参数与版本

- 工程与分支：`/Users/kuailegeziwl/android/echatApp`，`core_v1`。
- 模式：`existing`。
- app/Core namespace：`yumo.achat.app` / `yumo.achat.core`。
- 宿主模块：`app`。
- Model/存储前缀：`ach_` / `achat`。
- kit 基线：`273835ab7d6d9ef772bb3dd8c219da5389e60d6b`。
- 目标工具链保持 AGP 9.3.2、Gradle 9.5、compile/target SDK 37；没有用 prepared 的旧版本覆盖。

## 实际改动

- 新增 Android library `:core`，迁入 9 个现有生产文件及 8 个 JVM 测试文件。
- 新增 `AchatBackendConfiguration`、`AchatAppGateway` 和 app `CoreBackendFactory`，Core 不再依赖 app `BuildConfig`。
- 保留 `achat_backend_session` 及原 device/session 格式，升级后不生成第二个身份。
- `ImageToVideoScreen` 与上传流程共享同一个 gateway；生产默认 factory 仍创建真实 Core Repository。
- debug-only TestActivity 使用确定性 fake gateway 和 no-op 支付/profile 控制器；另有生产 MainActivity smoke test覆盖真实默认装配。
- 同步 existing 接入计划、职责/API 取舍和验收边界；实施前后均同步了本地 `echat-android-backend-integration` skill。

## 验证证据

- 外层交接包完整性：208 个文件通过。
- 目标内锁定 kit 完整性：通过。
- 脚手架 Python 测试：58 项通过。
- 接入前目标命令：`testDebugUnitTest assembleDebug lintDebug` 通过。
- 接入后最终门禁：
  - app JVM：63 项通过。
  - Core JVM：45 项通过。
  - app instrumented：29 项在 `emulator-5554` 通过。
  - Core instrumented：1 项在 `emulator-5554` 通过。
  - 使用 Temurin JDK17 运行 app/Core Debug 构建与 lint：通过。
- 初次 `doctor --project .` 在配置装配前停于 `config/dev.properties`；完成 DEV_REUSE 配置后已推进到唯一当前门禁“缺dev签名附件”。

### DEV_REUSE 后续装配

- 开发者确认 prod 按交接包采用 `DEV_REUSE`，不再使用原 `release.appjoly.com` 作为本阶段 prod；dev/prod 均指向 test 服务并使用相同 Firebase 文件。
- 目标已增加 environment flavor、完整配置 BuildConfig、配置指纹、Google app id 资源及两个 analytics integration 模块。SDK 已作为运行时依赖打包，但尚未在宿主生命周期实例化或调用，因此不声称真实收数；合并 Manifest 已禁止备份/迁移并移除 SDK 备份规则。
- 目标配置按现有 Compose/Core 依赖要求适配为 compile/target SDK 37；该值写入目标 `config/app.properties` 并参与配置指纹，锁定 kit 内原文件保持不变。
- JDK17 下，Core、AppsFlyer integration、ThinkingData integration release 单测及 app `testDevReleaseUnitTest/assembleDevDebug/assembleDevRelease/lintDevRelease/assembleProdDebug` 均在显式排除签名门禁时通过。
- 最终诊断计数：Core release 46、AppsFlyer 10、ThinkingData 3、app devRelease 65、app prodRelease 65，均为 0 failure/0 error；devRelease 的 R8/资源收缩和 flavor AndroidTest 编译打包通过。当前无连接设备，因此本轮 flavor 设备测试未执行。
- `verifyDevConfiguration` 不排除时会准确失败于“签名四项配置未齐全”；`doctor` 已推进并准确停在“缺dev签名附件”。
- 诊断 devDebug/prodDebug APK 使用默认 Android Debug 证书（SHA-1 与锁定 sharedDev 不同），devRelease APK 未签名；均未作为签名或 scaffold verify 证据。
- 锁定上游 `core_scaffold.py verify` 的任务列表遗漏 ThinkingData release 单测；本项目不修改锁定 kit，而是在 AGENTS 提交前命令中额外强制运行该测试并单独报告结果。
- AppsFlyer 6.18.1 在 R8 阶段仍输出一条其内部 companion 元数据警告，但 R8、资源收缩和 APK 打包成功；记录为上游 SDK 风险，不通过宽泛 `dontwarn` 隐藏。

## Git 与远程

- `3f81d86 初始化Core已有项目接入基线`：已推送 `origin/core_v1`。
- `4e36f8e 将现有后端能力接入独立Core模块`：已推送 `origin/core_v1`。
- 未修改现有 Git 历史或远程，未 force push，未提交本机配置、密码或签名材料。

## 未验收与继续条件

- 已在用户目录安装并登记 Temurin JDK `17.0.20.1`：`/Users/kuailegeziwl/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`。验收命令临时将 Gradle daemon 条件切到 17 并验证后恢复仓库原值 25，未留下工具链配置改动。
- 未找到独立 `private-dev-signing` 附件，未装配 sharedDev；没有用默认 debug 证书冒充。
- 真实工程已接入 scaffold 的固定配置、Firebase 文件、environment flavor 及两个 analytics integration 模块，但 sharedDev 私密附件仍缺失，且 analytics 宿主生命周期尚未激活。
- 因签名条件缺失，未运行普通 scaffold `verify`，未验证 sharedDev APK 身份；本范围不运行 `verify --refactored` 或 `pack`。
- 未执行真实匿名登录、生成扣钻/退款、Google Billing、第三方支付、AppsFlyer/Referrer、Firebase/数数/backend 四端收数验收。

本次 DEV_REUSE 矩阵的自动验收仍缺独立签名附件；Analytics 真实平台验收另需宿主生命周期接入。完整 Core 重构仍未授权/实施，schema=2 Model 台账为空，`verify --refactored` 与 `pack` 不属于“只差签名”的状态。无需、也不应在聊天中提供任何签名密码。
