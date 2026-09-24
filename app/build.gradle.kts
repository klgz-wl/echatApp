import java.security.MessageDigest
import java.security.KeyStore
import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

fun readProperties(path: String) = Properties().apply {
    rootProject.file(path).takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val common = rootProject.extra["appConfig"] as Properties
val mode = common.getProperty("kit.prod.mode", "DEV_REUSE")
check(mode in setOf("DEV_REUSE", "FORMAL")) { "kit.prod.mode must be DEV_REUSE or FORMAL" }
val devProperties = readProperties("config/dev.properties")
val prodProperties = readProperties("config/prod.properties")
val signingProperties = readProperties("config/signing.local.properties")
val formalSigningProperties = readProperties("config/signing-release.local.properties")
val prodConfirmationProperties = readProperties("config/prod-confirmation.local.properties")

val flavorEndpoints = mapOf(
    "dev" to mapOf(
        "CORE_BASE_URL" to "https://test.appjoly.com/api/v1/",
        "PAYMENT_BASE_URL" to "https://test.appjoly.com/payment-api/v1/",
        "CORE_STREAM_URL" to "wss://test.appjoly.com",
        "CORE_CDN_URL" to "https://cdn.appjoly.com",
        "REGION_LOOKUP_URL" to "https://api.country.is/",
    ),
    "prod" to mapOf(
        "CORE_BASE_URL" to "https://release.appjoly.com/api/v1/",
        "PAYMENT_BASE_URL" to "https://release.appjoly.com/payment-api/v1/",
        "CORE_STREAM_URL" to "wss://release.appjoly.com",
        "CORE_CDN_URL" to "https://cdn.appjoly.com",
        "REGION_LOOKUP_URL" to "",
    ),
)

val flavorApplicationIds = mapOf(
    "dev" to devProperties.getProperty("applicationId", "yumo.achat.app"),
    "prod" to if (mode == "DEV_REUSE") {
        devProperties.getProperty("applicationId", "yumo.achat.app")
    } else {
        prodProperties.getProperty("applicationId")
            ?: error("config/prod.properties must define applicationId for FORMAL prod")
    },
)

val flavorConfigurations = listOf("dev", "prod").associateWith { env ->
    Properties().apply {
        putAll(common)
        putAll(readProperties("config/$env.properties"))
        setProperty("applicationId", flavorApplicationIds.getValue(env))
        val endpointKey = if (env == "prod" && mode == "DEV_REUSE") "dev" else env
        flavorEndpoints.getValue(endpointKey).forEach { (key, value) ->
            setProperty("build.string.$key", value)
        }
    }
}

fun configurationFingerprint(config: Properties): String {
    val canonical = config.stringPropertyNames()
        .sorted()
        .joinToString(separator = "", postfix = "") { "$it=${config.getProperty(it)}\n" }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

android {
    namespace = common.getProperty("app.namespace")
    compileSdk = common.getProperty("sdk.compile").toInt()

    defaultConfig {
        applicationId = "yumo.achat.app"
        minSdk = common.getProperty("sdk.min").toInt()
        targetSdk = common.getProperty("sdk.target").toInt()
        versionCode = common.getProperty("app.version.code").toInt()
        versionName = common.getProperty("app.version.name")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ACHAT_API_BASE_URL", "\"https://test.appjoly.com\"")
        buildConfigField("String", "ACHAT_WS_URL", "\"wss://test.appjoly.com/connection/websocket\"")
        buildConfigField("String", "ACHAT_CLIENT_VERSION", "\"2.0.0\"")
    }

    signingConfigs {
        create("sharedDev") {
            storeFile = signingProperties.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
        create("formal") {
            storeFile = formalSigningProperties.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = formalSigningProperties.getProperty("storePassword")
            keyAlias = formalSigningProperties.getProperty("keyAlias")
            keyPassword = formalSigningProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sharedDev")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        flavorConfigurations.forEach { (env, config) ->
            create(env) {
                dimension = "environment"
                applicationId = config.getProperty("applicationId")
                signingConfig = signingConfigs.getByName(
                    if (env == "prod" && mode == "FORMAL") "formal" else "sharedDev",
                )
                manifestPlaceholders["appLabel"] = config.getProperty("app.display.name", "Achat")
                val baseUrl = config.getProperty("build.string.CORE_BASE_URL").removeSuffix("/api/v1/")
                val wsUrl = config.getProperty("build.string.CORE_STREAM_URL") + "/connection/websocket"
                buildConfigField("String", "ACHAT_API_BASE_URL", quoted(baseUrl))
                buildConfigField("String", "ACHAT_WS_URL", quoted(wsUrl))
                buildConfigField("String", "ACHAT_CLIENT_VERSION", quoted("2.0.0"))
                buildConfigField("String", "PROD_CONFIG_STATUS", quoted(mode))
                val expectedKeys = (common.stringPropertyNames() + devProperties.stringPropertyNames() + config.stringPropertyNames())
                    .filter { it.startsWith("build.") }
                    .toSet()
                expectedKeys.forEach { key ->
                    val (_, type, field) = key.split('.', limit = 3)
                    val value = config.getProperty(key).orEmpty()
                    when (type) {
                        "string" -> buildConfigField("String", field, quoted(value))
                        "boolean" -> buildConfigField("boolean", field, value.toBooleanStrictOrNull()?.toString() ?: "false")
                        "int" -> buildConfigField("int", field, value.toIntOrNull()?.toString() ?: "0")
                    }
                }
                resValue("string", "kit_configuration_fingerprint", configurationFingerprint(config))
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integration:analytics-appsflyer"))
    implementation(project(":integration:analytics-thinkingdata"))
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    implementation(libs.kotlinx.serialization.json)

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-database:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

tasks.register("testDevReleaseUnitTest") {
    dependsOn("testDevDebugUnitTest")
}

fun requireSigning(properties: Properties, label: String) {
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
        check(!properties.getProperty(key).isNullOrBlank()) {
            "$label signing config is missing $key"
        }
    }
    check(rootProject.file(properties.getProperty("storeFile")).isFile) {
        "$label signing store file does not exist"
    }
}

fun certificateBytes(properties: Properties): ByteArray {
    val keyStoreFile = rootProject.file(properties.getProperty("storeFile"))
    val keyStore = KeyStore.getInstance(keyStoreFile, properties.getProperty("storePassword").toCharArray())
    return keyStore.getCertificate(properties.getProperty("keyAlias")).encoded
}

fun sha1(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

tasks.register("verifyFormalRelease") {
    notCompatibleWithConfigurationCache("Reads local formal signing and confirmation files during execution.")
    doLast {
        check(mode == "FORMAL") { "DEV_REUSE临时状态禁止正式发布" }
        check(prodConfirmationProperties.getProperty("confirmed") == "true") {
            "请填写正式配置确认清单：config/prod-confirmation.local.properties"
        }
        check(flavorApplicationIds.getValue("prod") == "com.zorv.app") {
            "必须确认正式包名 com.zorv.app"
        }
        requireSigning(formalSigningProperties, "formal release")
        val devCertificate = rootProject.file("config/dev-certificate.der")
        check(devCertificate.isFile) { "缺少sharedDev证书指纹文件" }
        val formalCertificate = certificateBytes(formalSigningProperties)
        check(!devCertificate.readBytes().contentEquals(formalCertificate)) {
            "正式签名不能使用sharedDev证书"
        }
        val googleServices = rootProject.file("app/src/prod/google-services.json")
        check(googleServices.isFile) { "缺少prod google-services.json" }
        val formalSha1 = sha1(formalCertificate)
        @Suppress("UNCHECKED_CAST")
        val googleJson = JsonSlurper().parse(googleServices) as Map<String, Any?>
        val clients = googleJson["client"] as? List<*> ?: emptyList<Any>()
        val matchingClient = clients.filterIsInstance<Map<String, Any?>>().firstOrNull { client ->
            val clientInfo = client["client_info"] as? Map<*, *>
            val androidInfo = clientInfo?.get("android_client_info") as? Map<*, *>
            androidInfo?.get("package_name") == "com.zorv.app"
        } ?: error("Firebase与当前包名不匹配")
        val oauthClients = matchingClient["oauth_client"] as? List<*> ?: emptyList<Any>()
        val hasMatchingCertificate = oauthClients.filterIsInstance<Map<String, Any?>>().any { oauth ->
            val androidInfo = oauth["android_info"] as? Map<*, *>
            androidInfo?.get("package_name") == "com.zorv.app" &&
                androidInfo["certificate_hash"]?.toString()?.lowercase() == formalSha1
        }
        check(hasMatchingCertificate) {
            "Firebase/OAuth证书指纹与正式签名不匹配"
        }
    }
}

tasks.configureEach {
    if (mode == "FORMAL" && name in setOf("assembleProdRelease", "bundleProdRelease")) {
        dependsOn("verifyProdReleaseRuntimeConfig", "verifyFormalRelease", "verifyCoreFullChainEvidence")
    }
}

tasks.register("verifyCoreFullChainEvidence") {
    notCompatibleWithConfigurationCache("Reads local platform-evidence.json during execution.")
    doLast {
        val evidence = rootProject.file(".core-scaffold/platform-evidence.json")
        check(evidence.isFile) {
            "缺少真实平台验收证据：.core-scaffold/platform-evidence.json"
        }
        @Suppress("UNCHECKED_CAST")
        val value = JsonSlurper().parse(evidence) as Map<String, Any?>
        check(value["package_name"] == "com.zorv.app") { "真实平台验收证据包名不匹配" }
        check(value["backend_environment"] == "release") { "真实平台验收证据后端环境不匹配" }
        check((value["distribution"] as? String).orEmpty().isNotBlank()) { "真实平台验收证据缺少distribution" }
        check((value["device"] as? String).orEmpty().isNotBlank()) { "真实平台验收证据缺少device" }
        check((value["tester_account"] as? String).orEmpty().isNotBlank()) { "真实平台验收证据缺少tester_account" }
        check((value["play_product_ids"] as? List<*>)?.isNotEmpty() == true) { "真实平台验收证据缺少play_product_ids" }
        check((value["verified_order_ids"] as? List<*>)?.isNotEmpty() == true) { "真实平台验收证据缺少verified_order_ids" }
        check((value["verified_generation_task_ids"] as? List<*>)?.isNotEmpty() == true) { "真实平台验收证据缺少verified_generation_task_ids" }
        check((value["wallet_transaction_ids"] as? List<*>)?.isNotEmpty() == true) { "真实平台验收证据缺少wallet_transaction_ids" }
        listOf(
            "device_platform_verified",
            "play_billing_verified",
            "payment_fulfillment_verified",
            "generation_verified",
            "backend_api_smoke_verified",
            "wallet_reconciliation_verified",
            "attribution_verified",
            "analytics_console_verified",
        ).forEach { key ->
            check(value[key] == true) {
                "真实平台验收证据缺少或未通过：$key"
            }
        }
    }
}

tasks.register("verifyProdReleaseRuntimeConfig") {
    dependsOn("generateProdReleaseBuildConfig")
    val buildConfig = layout.buildDirectory.file(
        "generated/source/buildConfig/prod/release/yumo/achat/app/BuildConfig.java",
    )
    inputs.file(buildConfig)
    doLast {
        val text = buildConfig.get().asFile.readText()
        val expected = mapOf(
            "APPLICATION_ID" to "com.zorv.app",
            "ACHAT_API_BASE_URL" to "https://release.appjoly.com",
            "ACHAT_WS_URL" to "wss://release.appjoly.com/connection/websocket",
            "PAYMENT_BASE_URL" to "https://release.appjoly.com/payment-api/v1/",
            "PAYMENT_FLOW" to "SERVICE",
        )
        expected.forEach { (field, value) ->
            check("""$field = "$value""" in text) {
                "prodRelease BuildConfig $field must be $value"
            }
        }
        listOf(
            "ENABLE_FIREBASE_ANALYTICS",
            "ENABLE_FIREBASE_CRASHLYTICS",
            "ENABLE_FIREBASE_MESSAGING",
        ).forEach { field ->
            check("""$field = false""" in text) {
                "prodRelease BuildConfig $field must stay false until the Firebase SDK/sink is wired"
            }
        }
    }
}
