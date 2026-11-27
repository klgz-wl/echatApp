# Zorv Pro

同一个仓库增加 `pro` 变体，共享原来的 UI 和业务逻辑。原有 `dev`、`prod` 变体、包名、版本和发布门禁不变。

- 原版正式应用：`prod` / `com.zorv.app`。
- Pro：`pro` / `com.zorv.pro` / `zorv pro` / 首版 `1.0.0`（versionCode 1）。
- Pro 配置顺序：`config/app.properties` → `config/prod.properties` → `config/pro.properties`。Pro 固定使用现有正式 API/CDN；独立 SDK 身份仅保存在对应配置文件，避免复制到文档。
- 应用图标为用户提供的 Pro 素材，仅覆盖 `src/pro/res`；应用名称和首页品牌文字也仅覆盖 Pro。

## 本地验证与打包

```sh
./gradlew --no-configuration-cache :app:testDevDebugUnitTest :app:testProdDebugUnitTest :app:testProDebugUnitTest :app:assembleDebug :app:lintDevDebug :app:lintProdDebug :app:lintProDebug
```

Pro 调试包：`app/build/outputs/apk/pro/debug/app-pro-debug.apk`。Android Studio 选择 `proDebug`。调试包连接正式后端，并复用原有区域限制；调试签名不能用于正式发布。

## 正式发布

1. 服务端登记 `com.zorv.pro`，配置对应的应用、商品及支付映射。AppsFlyer、数数、Google Play 等平台完成新包名和正式证书的登记。编译成功不代表真实平台验收完成。
2. 按 `config/signing-pro-release.properties.example` 创建 `config/signing-pro-release.local.properties`，填入真实上传签名。该配置与原版分别维护，禁止开发签名。
3. 按 `config/pro-confirmation.properties.example` 创建 `config/pro-confirmation.local.properties`。验收后填写包名及 `confirmed=true`。
4. 提供 `.core-scaffold/pro-platform-evidence.local.json`，字段沿用原版 `verifyCoreFullChainEvidence` 的结构，但 `package_name` 必须为 `com.zorv.pro`；禁止复用原版验收结果。
5. 运行 `./gradlew --no-configuration-cache :app:bundleProRelease`。所有 Pro release 打包入口均依赖独立签名、运行配置和平台验收门禁。

目前 Firebase 三项能力均关闭。没有 Pro 的真实 `app/src/pro/google-services.json` 时仅跳过 Pro 的 Google Services 生成步骤，不复制或伪造原版注册信息；启用任何 Firebase 能力前必须补齐 Pro 注册文件。Google 登录等平台能力仍须按新包名和证书完成实际登记与验收。

## 专用打包目录的签名验证

仅配置 Pro 正式签名的目录不需要原版或 sharedDev 私钥。不要使用全变体 `signingReport` 作为必经检查，它可能在缺少开发签名的 devDebug 上失败。

```powershell
.\gradlew.bat --gradle-user-home "$env:LOCALAPPDATA\Gradle\zorv-pro" --no-configuration-cache :app:verifyProReleaseSigning
```

该任务只验证 Pro keystore、私钥密码、alias、证书有效期和开发证书隔离，输出公开 SHA-256 指纹；不依赖发布确认或平台验收。实际 release 打包继续要求独立发布确认、运行配置和真实平台验收，不能以签名验证通过代替发布验收。
