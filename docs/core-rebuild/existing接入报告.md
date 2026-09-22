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
- `doctor --project .`：锁定 kit 完整性通过，随后退出码 2，首个缺项为 `config/dev.properties`。

## Git 与远程

- `3f81d86 初始化Core已有项目接入基线`：已推送 `origin/core_v1`。
- `4e36f8e 将现有后端能力接入独立Core模块`：已推送 `origin/core_v1`。
- 未修改现有 Git 历史或远程，未 force push，未提交本机配置、密码或签名材料。

## 未验收与继续条件

- 已在用户目录安装并登记 Temurin JDK `17.0.20.1`：`/Users/kuailegeziwl/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`。验收命令临时将 Gradle daemon 条件切到 17 并验证后恢复仓库原值 25，未留下工具链配置改动。
- 未找到独立 `private-dev-signing` 附件，未装配 sharedDev；没有用默认 debug 证书冒充。
- 真实工程尚未接入 scaffold 的 `config/app.properties`、dev/prod 配置、Firebase文件、environment flavor 及两个 analytics integration 模块。现有 release 服务地址被保留，未按 prepared 的 DEV_REUSE 静默覆盖。
- 因上述条件，未运行普通 scaffold `verify`，未验证 sharedDev APK 身份/dev-prod 矩阵；本范围不运行 `verify --refactored` 或 `pack`。
- 未执行真实匿名登录、生成扣钻/退款、Google Billing、第三方支付、AppsFlyer/Referrer、Firebase/数数/backend 四端收数验收。

继续 scaffold 全矩阵接入前，需要独立签名附件绝对路径，以及对“保留现有 release 正式地址”与包内 `prod=DEV_REUSE` 冲突的明确取舍。无需、也不应在聊天中提供任何签名密码。
