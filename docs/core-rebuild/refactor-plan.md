# echatApp 完整 Core 重构计划

## 已确认参数

- 模式：existing
- 项目名：echatApp
- app namespace：yumo.achat.app
- Core namespace：yumo.achat.core
- 宿主模块：app
- 存储前缀：achat
- Model 字段前缀：ach_
- prod 策略：DEV_REUSE
- 签名附件：读取本机交接包 private-dev-signing，不在聊天、源码或报告中记录密码。

## 当前状态

- 目标工程已有业务源码、Git 历史和远程，当前分支为 core_v2。
- `.core-scaffold/prepared` 已存在，作为 existing 模式的参考基线；真实工程根此前缺少 `docs/core-rebuild` 与 `.core-scaffold/project.json`，本轮已续接。
- 真实工程当前仍以 `:app` 单模块承载业务与 backend 源码，尚未完成独立 `:core` 与 `:integration` 模块合并。
- `docs/core-rebuild/model-plan.json` 仍是空台账，不能通过 `verify --refactored`，也不能代表实际重构完成。

## 分阶段范围

### 阶段一：基线接入修复

1. 保留目标工程原有 `.git`、分支、远程、业务页面、导航、资源与 backend 行为。
2. 补齐根级 `docs/core-rebuild`、`.core-scaffold/project.json`、签名忽略规则和接入记录。
3. 运行脚手架 doctor，记录缺失项；在不覆盖业务的前提下接入 config、dev/prod 配置、签名附件、Gradle 矩阵和模块声明。
4. 目标是让真实工程进入可执行 `doctor` 和基础 `verify` 的状态；prepared 构建结果只作为参考，不作为真实工程验收。

### 阶段二：真实源码 Core 重构

1. 建立独立 `:core` 模块和必要的 `:integration` 模块，迁移现有 `app/data/backend` 中的会话、网络、模板、上传、任务、资源、钱包与支付协议。
2. 宿主 `:app` 只保留 UI、Android 生命周期桥接、BuildConfig 注入和 Compose 页面状态；Core 不依赖 app BuildConfig。
3. 保留已有匿名登录、Token 刷新、profile、模板、上传、任务、轮询、Top Up、Billing 与 My Tasks 行为，不引入第二套运行时 owner。
4. 用 TDD 为迁移后的解析、状态映射、multipart、支付准备、轮询和持久化行为补测试；每轮先看失败，再实现。

### 阶段三：Model schema=2 台账与字段改造

1. 读取 `docs/core-rebuild/kit/manifests/models.json`，覆盖所有基线 Model 的 `baseline_mapping`。
2. 为每个实际目标 Model 设计并冻结 1-5 个 `ach_` 前缀扩展字段，记录原字段、序列化名、默认/随机/join 策略、敏感源审查、copy、equals/hash、旧数据恢复和重试语义。
3. `target_file` 与 `extension_files` 只指向真实目标生产源码，不指向 reference、prepared、测试夹具或注释文件。
4. 维护 `docs/core-rebuild/model-plan.json` 与 `docs/core-rebuild/refactor-review.md`，静态校验通过后仍需 Kotlin 测试与人工语义审查证据。

### 阶段四：验证、修复、打包

1. 基线/接入验证：`python3 docs/core-rebuild/kit/validation/core_scaffold.py doctor --project .` 与非 refactored `verify`。
2. 完整重构验证：`python3 docs/core-rebuild/kit/validation/core_scaffold.py verify --project . --refactored`。
3. 打包：`python3 docs/core-rebuild/kit/validation/core_scaffold.py pack --project . --output ../core-refactored-bundle-<timestamp>`。
4. Gradle 验证失败必须先修复并重跑；不修改锁定 kit、manifest 或测试预期来伪造通过。

## 验收边界

- 基线装配：本地文件、配置、签名、Gradle 矩阵和 APK 身份验证。
- 实际重构：真实源码职责拆分、Model 台账、兼容测试、`verify --refactored` 和 pack。
- 真实平台验收：设备安装、真实登录、归因、支付、购买、生成、平台审核等；没有真实执行时只报告未验收，不声称通过。

## Git 规则

- 每完成一个明确任务，先验证，再精确暂存本任务文件，中文 commit，并 push 当前分支。
- 禁止 `git add .` / `git add -A`，禁止提交签名、密码、本机配置、构建缓存和 prepared 工作区。
- 当前分支暂无 upstream，首次推送使用 `origin/core_v2` 建立跟踪。
