import java.util.Properties
import java.security.KeyStore
import java.security.PrivateKey
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}
fun readProperties(path: String) = Properties().apply {
    rootProject.file(path).takeIf { it.isFile }?.inputStream()?.use(::load)
}
val common = rootProject.extra["appConfig"] as Properties
val mode = common.getProperty("kit.prod.mode", "DEV_REUSE")
check(mode in setOf("DEV_REUSE", "FORMAL")) { "kit.prod.mode只能为DEV_REUSE或FORMAL" }
// 正式信息由新项目开发者确认后填写；包名和地址只在此表维护。
val flavorApplicationIds = mapOf(
    "dev" to "yumo.achat.app",
    "prod" to if (mode == "DEV_REUSE") "yumo.achat.app" else "REPLACE_PROD_APPLICATION_ID",
)
val flavorEndpoints = mapOf(
    "dev" to mapOf(
        "CORE_BASE_URL" to "https://test.appjoly.com/api/v1/",
        "PAYMENT_BASE_URL" to "https://test.appjoly.com/payment-api/v1/",
        "CORE_STREAM_URL" to "wss://test.appjoly.com",
        "CORE_CDN_URL" to "https://cdn.appjoly.com",
        "REGION_LOOKUP_URL" to "https://api.country.is/",
    ),
    "prod" to mapOf(
        "CORE_BASE_URL" to "",
        "PAYMENT_BASE_URL" to "",
        "CORE_STREAM_URL" to "",
        "CORE_CDN_URL" to "",
        "REGION_LOOKUP_URL" to "",
    ),
)
val devProperties = readProperties("config/dev.properties")
val configurations = listOf("dev", "prod").associateWith { env ->
    Properties().apply {
        putAll(common)
        putAll(readProperties("config/$env.properties"))
        // 临时prod独立文件应与dev保持完整同步；不在运行时偷偷兜底缺项。
        setProperty("applicationId", flavorApplicationIds.getValue(env))
        val addresses = flavorEndpoints.getValue(if (env == "prod" && mode == "DEV_REUSE") "dev" else env)
        addresses.forEach { (key, value) -> setProperty("build.string.$key", value) }
    }
}
val shared = readProperties("config/signing.local.properties")
val formal = if (mode == "FORMAL") readProperties("config/signing-release.local.properties") else Properties()
fun verifySigning(p: Properties) {
    check(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !p.getProperty(it).isNullOrBlank() }) { "签名四项配置未齐全" }
    val file = rootProject.file(p.getProperty("storeFile"))
    check(file.isFile) { "签名文件不存在" }
    val store = KeyStore.getInstance(file, p.getProperty("storePassword").toCharArray())
    check(store.getKey(p.getProperty("keyAlias"), p.getProperty("keyPassword").toCharArray()) is PrivateKey) { "签名条目不是私钥" }
    (store.getCertificate(p.getProperty("keyAlias")) as java.security.cert.X509Certificate).checkValidity()
}
fun verifyConfiguration(env: String) {
    val config = configurations.getValue(env)
    val prototype = Properties().apply { putAll(common); putAll(devProperties) }
    val optional = setOf("build.string.GOOGLE_WEB_CLIENT_ID", "build.string.PAYMENT_BASE_URL")
    val missing = prototype.stringPropertyNames().filter { it.startsWith("build.") && it !in optional && config.getProperty(it).isNullOrBlank() }
    check(missing.isEmpty()) { "配置缺项：${missing.joinToString()}" }
    if (env == "prod" && mode == "DEV_REUSE") {
        check(rootProject.file("config/prod.properties").isFile) { "缺少独立临时prod配置文件" }
        check(readProperties("config/prod.properties") == devProperties) { "临时prod与dev配置不一致，请同步或确认切换正式状态" }
        check(file("src/prod/google-services.json").readBytes().contentEquals(file("src/dev/google-services.json").readBytes())) { "临时prod的Firebase应完整复用dev" }
    }
    config.stringPropertyNames().forEach { key ->
        val value = config.getProperty(key)
        if (key.startsWith("build.boolean.")) check(value.toBooleanStrictOrNull() != null) { "布尔配置无效：$key" }
        if (key.startsWith("build.int.")) check(value.toIntOrNull() != null) { "整数配置无效：$key" }
    }
    check(config.getProperty("build.string.PAYMENT_FLOW") in setOf("LEGACY", "SERVICE")) { "支付流程无效" }
    if (config.getProperty("build.string.PAYMENT_FLOW") == "SERVICE") check(!config.getProperty("build.string.PAYMENT_BASE_URL").isNullOrBlank()) { "SERVICE缺支付地址" }
    check(file("src/$env/google-services.json").isFile) { "缺少当前环境Firebase原件" }
    check(!config.getProperty("applicationId").startsWith("REPLACE_")) { "必须确认正式包名" }
    check(config.getProperty("applicationId").matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) { "包名格式无效" }
    val firebase = groovy.json.JsonSlurper().parse(file("src/$env/google-services.json")) as Map<*, *>
    val packages = (firebase["client"] as List<*>).map { client ->
        val info = (client as Map<*, *>)["client_info"] as Map<*, *>
        (info["android_client_info"] as Map<*, *>)["package_name"]
    }
    check(config.getProperty("applicationId") in packages) { "Firebase与当前包名不匹配" }
    listOf("CORE_BASE_URL", "CORE_STREAM_URL", "CORE_CDN_URL", "REGION_LOOKUP_URL").forEach {
        check(!config.getProperty("build.string.$it").isNullOrBlank()) { "缺少地址：$it" }
    }
    flavorEndpoints.getValue("dev").keys.forEach { name ->
        val value = config.getProperty("build.string.$name").orEmpty()
        if (value.isNotEmpty()) {
            val uri = runCatching { URI(value) }.getOrNull()
            val scheme = if (name == "CORE_STREAM_URL") "wss" else "https"
            check(uri != null && uri.scheme == scheme && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "地址格式无效：$name" }
            if (name.endsWith("BASE_URL")) check(value.endsWith('/')) { "API基址必须以斜线结尾：$name" }
        }
    }
    if (env == "prod" && mode == "FORMAL") {
        check(readProperties("config/prod-confirmation.local.properties").getProperty("confirmed") == "true") { "请填写正式配置确认清单" }
        verifySigning(formal)
        check(config.getProperty("applicationId") != flavorApplicationIds.getValue("dev")) { "正式包名仍为固定dev身份" }
        val devJson = file("src/dev/google-services.json").readBytes()
        check(!file("src/prod/google-services.json").readBytes().contentEquals(devJson)) { "正式Firebase仍为dev原件" }
    }
}
val verifyDev = tasks.register("verifyDevConfiguration") { doLast { verifyConfiguration("dev"); verifySigning(shared) } }
val verifyProd = tasks.register("verifyProdConfiguration") { doLast {
    verifyConfiguration("prod")
    if (mode == "DEV_REUSE") { verifySigning(shared); logger.lifecycle("临时prod：DEV_REUSE，测试服务和sharedDev签名，不是正式发布包") }
} }
tasks.register("verifyFormalRelease") { doLast {
    check(mode == "FORMAL") { "DEV_REUSE临时状态禁止正式发布，请替换正式资料" }
    verifyConfiguration("prod")
    // 对照dev证书内容，不能仅通过改变alias绕过正式签名检查。
    val release = KeyStore.getInstance(rootProject.file(formal.getProperty("storeFile")), formal.getProperty("storePassword").toCharArray())
    val cert = release.getCertificate(formal.getProperty("keyAlias")).encoded
    val expected = rootProject.file("config/dev-certificate.der")
    check(expected.isFile && !expected.readBytes().contentEquals(cert)) { "正式签名不能使用sharedDev证书" }
} }
tasks.configureEach {
    // 演练只构建本地产物；目标项目发布时再单独接入mapping上传。
    if (name.startsWith("uploadCrashlyticsMappingFile")) enabled = false
    if (name == "preDevDebugBuild" || name == "preDevReleaseBuild") dependsOn(verifyDev)
    if (name == "preProdDebugBuild" || name == "preProdReleaseBuild") dependsOn(verifyProd)
    if (mode == "FORMAL" && (name == "preProdReleaseBuild" || name == "bundleProdRelease" || name == "assembleProdRelease")) dependsOn("verifyFormalRelease")
}
fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
    namespace = common.getProperty("app.namespace")
    compileSdk = common.getProperty("sdk.compile").toInt()
    compileSdkMinor = common.getProperty("sdk.minor").toInt()
    defaultConfig { minSdk = common.getProperty("sdk.min").toInt(); targetSdk = common.getProperty("sdk.target").toInt() }
    signingConfigs {
        create("sharedDev") {
            storeFile = shared.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = shared.getProperty("storePassword"); keyAlias = shared.getProperty("keyAlias"); keyPassword = shared.getProperty("keyPassword")
        }
        create("formal") {
            storeFile = formal.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = formal.getProperty("storePassword"); keyAlias = formal.getProperty("keyAlias"); keyPassword = formal.getProperty("keyPassword")
        }
    }
    flavorDimensions += "environment"
    productFlavors {
        configurations.forEach { (env, config) -> create(env) {
            dimension = "environment"; applicationId = config.getProperty("applicationId")
            versionCode = config.getProperty("app.version.code").toInt(); versionName = config.getProperty("app.version.name")
            signingConfig = signingConfigs.getByName(if (env == "prod" && mode == "FORMAL") "formal" else "sharedDev")
            buildConfigField("String", "PROD_CONFIG_STATUS", quoted(mode))
            resValue("string", "build_identity", if (env == "prod") "prod / $mode" else "dev / test")
            val canonical = config.stringPropertyNames().sorted().joinToString("\n", postfix = "\n") { "$it=${config.getProperty(it)}" }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            resValue("string", "kit_configuration_fingerprint", fingerprint)
            val keys = (common.stringPropertyNames() + devProperties.stringPropertyNames() + config.stringPropertyNames()).filter { it.startsWith("build.") }.toSet()
            keys.forEach { key ->
                val (_, type, field) = key.split('.', limit = 3)
                val value = config.getProperty(key).orEmpty()
                when (type) {
                    "string" -> buildConfigField("String", field, quoted(value))
                    "boolean" -> buildConfigField("boolean", field, value.toBooleanStrictOrNull()?.toString() ?: "false")
                    "int" -> buildConfigField("int", field, value.toIntOrNull()?.toString() ?: "0")
                }
            }
            manifestPlaceholders["deepLinkScheme"] = config.getProperty("build.string.DEEP_LINK_SCHEME", "unset")
            manifestPlaceholders["firebaseAnalyticsEnabled"] = config.getProperty("build.boolean.ENABLE_FIREBASE_ANALYTICS", "false")
            manifestPlaceholders["firebaseCrashlyticsEnabled"] = config.getProperty("build.boolean.ENABLE_FIREBASE_CRASHLYTICS", "false")
            manifestPlaceholders["firebaseMessagingEnabled"] = config.getProperty("build.boolean.ENABLE_FIREBASE_MESSAGING", "false")
        } }
    }
    buildTypes {
        debug { signingConfig = null }
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").assets.exclude("mock/**", "config/catalog_sources.json", "config/profile_preview.json", "config/generation_options.json")
}
dependencies {
    implementation(project(":core"))
    implementation(project(":integration:analytics-appsflyer"))
    implementation(project(":integration:analytics-thinkingdata"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.timber)
    implementation(platform("com.google.firebase:firebase-bom:${libs.versions.firebaseBom.get()}"))
    implementation(libs.firebase.analytics); implementation(libs.firebase.crashlytics); implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
}
