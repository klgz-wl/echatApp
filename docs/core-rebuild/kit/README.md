# Core 重建交付包

这是独立交付的行为参考、完整dev配置、最小验证宿主和重建提示词。`reference/core`仍是原始基线；新的包结构和每个Model的1–5个冗余字段，由新项目AI在开发者确认参数后实现。此包不是已完成全部字段改造的目标应用。

## 新项目入口

推荐先使用 [Python3脚手架](templates/脚手架使用说明.md)：运行 `python3 validation/core_scaffold.py configure --output ../scaffold.local.json` 填写一次参数，再运行 `python3 validation/core_scaffold.py init --config ../scaffold.local.json`。支持新建和已有项目，自动生成本地AI任务；把生成的 `docs/core-rebuild/开始重构.md` 交给AI继续。脚手架同时提供检查、构建、已重构包部署及回滚。

手工入口先将 [首次问答](prompts/首次问答.md) 交给AI，再提供 [完整提示词](prompts/完整重建提示词.md) 和 [阶段提示词](prompts/阶段提示词.md)。使用脚手架时复用已确认参数，AI直接实施，不重复问答；只有未确认的必要参数才补问，不得自行假定VEX_前缀。对照 [模型台账](manifests/models.json) 扩展所有实际目标Model，包含private持久化、有数据sealed分支和新增状态/参数/值快照；schema=2来源映射支持拆分与合并，格式见Model方法。

- [功能与公共API](contracts/Core功能与公共接口清单.md)；核心协议也可直接查reference中的Retrofit声明。
- [配置来源](manifests/configuration.json)、[有效dev值](config/dev/effective.properties)、[源码哈希](manifests/sources.json)。客户端SDK参数在config/dev文件内，不应粘贴到日志或提示词。
- [开发证书指纹](manifests/dev-signature.json)；私钥及密码在同级独立 `private-dev-signing` 附件中，不属于本压缩包。
- [验证报告](reports/验证报告.md) 明确区分实际通过和未验证；基线宿主并非品牌UI或完整支付/创作页面。
- [职责与接入映射](templates/职责与接入映射.md)、[Model审查与改造方法](templates/Model审查与改造方法.md)。

## 装配基线验证宿主

需要Python 3.9+、JDK17、Android SDK（版本以config/dev/app.properties为准）。设置JAVA_HOME和ANDROID_HOME，私密附件放在本包同级；确认目标路径不存在。下面命令从本包根目录运行：

```sh
python3 validation/create_workspace.py --output ../harness --private-signing ../private-dev-signing
```

在harness根目录创建本机忽略的local.properties，填写 `sdk.dir=<本机Android SDK路径>`。不要复制原开发机的路径。然后在harness运行：

```sh
./gradlew :core:testReleaseUnitTest :integration:analytics-appsflyer:testReleaseUnitTest :app:testDevReleaseUnitTest :app:assembleDevDebug :app:assembleDevRelease :app:lintDevRelease
```

交付模板显式绑定sharedDev，使devRelease可直接生成签名APK（参考项目原流程为后签名）；R8和资源收缩保持开启。验证APK时从本包根目录运行，SDK_PATH替换为本机SDK：

```sh
python3 validation/verify.py --workspace ../harness --apk ../harness/app/build/outputs/apk/dev/release/app-dev-release.apk --sdk SDK_PATH --variant devRelease
```

首次安装前检查同包名应用；所有dev及临时prod都使用 `yumo.achat.app`，可能覆盖已有应用。自动验证不会安装APK、购买、发起生成或调用真实后台。宿主安装并启动后才进行真实匿名登录/归因，钱包按钮只读真实钱包；其余页面由目标项目按协议实现，参考ViewModel和生命周期源代码随包提供。

## prod先复用dev，随后替换

初始 `config/app.properties` 的 `kit.prod.mode=DEV_REUSE`；prod文件是独立的dev副本，包名/Firebase/SDK/测试地址/签名匹配。可在隔离宿主构建 `:app:assembleProdRelease`，仅用于验证临时配置；对外交付时命名 `prod-dev-reuse-release.apk`，其内页面标明DEV_REUSE。当前Vexora仓库仍只使用devRelease回归。

正式资料齐全后：

1. 由新项目开发者确认正式applicationId和接口，在app/build.gradle.kts的prod表填写。
2. 替换config/prod.properties、app/src/prod/google-services.json；仅需要Google登录时配置OAuth。原模板提供的dev值不能默认为正式已确认。
3. 按config/signing-release.properties.example建立本地忽略的正式签名配置，并放置开发者自己的正式证书。
4. 逐项确认后设置本地prod-confirmation.local.properties的confirmed=true，再将kit.prod.mode改为FORMAL。此确认不能替代Firebase/包名/签名校验。
5. 在正式打包机运行 `:app:verifyFormalRelease :app:assembleProdRelease :app:bundleProdRelease`。正式release任务依赖校验，拒绝占位包名、缺配置、原dev Firebase和sharedDev证书。

临时状态运行verifyFormalRelease必须失败，这是防止误交付测试包的预期行为。仅LEGACY时备用SERVICE地址可以为空；正式appVersion、深链、统计配置及签名由开发者确认，不能猜测。

## 文件与交付边界

reference保留Core、SDK及必要宿主业务状态代码作为重构依据，不要求按旧目录复制。templates/app是可编译最小宿主；reference/app中的页面ViewModel片段不是独立UI工程，缺失的品牌展示部分由新项目实现。页面/系统照片选择/浏览器/共享生成轮询的责任见完整提示词，不能以宿主启动通过替代支付/生成验收。

不提交私密附件，不将签名配置放进assets。普通包完整性运行 `python3 validation/verify.py`；有意修改包后重封装才运行 `python3 validation/seal.py`，不能用重封装掩盖未经review的改动。SDK版本以本次锁定基线为准，发布前另核对支持周期。
