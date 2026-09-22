import groovy.json.JsonSlurper
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun readProperties(path: String) = Properties().apply {
    rootProject.file(path).takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val common = readProperties("config/app.properties")
val devProperties = readProperties("config/dev.properties")
val mode = common.getProperty("kit.prod.mode", "DEV_REUSE")
check(mode == "DEV_REUSE") { "当前接入阶段仅允许已确认的DEV_REUSE" }

val flavorApplicationIds = mapOf("dev" to "yumo.achat.app", "prod" to "yumo.achat.app")
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
val configurations = listOf("dev", "prod").associateWith { environment ->
    Properties().apply {
        putAll(common)
        putAll(readProperties("config/$environment.properties"))
        setProperty("applicationId", flavorApplicationIds.getValue(environment))
        flavorEndpoints.getValue("dev").forEach { (name, value) -> setProperty("build.string.$name", value) }
    }
}
val sharedSigning = readProperties("config/signing.local.properties")
val sharedSigningReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !sharedSigning.getProperty(it).isNullOrBlank() } &&
    sharedSigning.getProperty("storeFile")?.let { rootProject.file(it).isFile } == true

fun verifySigning(properties: Properties) {
    check(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        !properties.getProperty(it).isNullOrBlank()
    }) { "签名四项配置未齐全" }
    val file = rootProject.file(properties.getProperty("storeFile"))
    check(file.isFile) { "签名文件不存在" }
    val store = KeyStore.getInstance(file, properties.getProperty("storePassword").toCharArray())
    check(store.getKey(properties.getProperty("keyAlias"), properties.getProperty("keyPassword").toCharArray()) is PrivateKey) {
        "签名条目不是私钥"
    }
    val certificate = store.getCertificate(properties.getProperty("keyAlias")) as java.security.cert.X509Certificate
    certificate.checkValidity()
    val expectedCertificate = rootProject.file("config/dev-certificate.der")
    check(expectedCertificate.isFile && expectedCertificate.readBytes().contentEquals(certificate.encoded)) {
        "签名证书不是锁定的sharedDev身份"
    }
}

fun verifyConfiguration(environment: String) {
    val config = configurations.getValue(environment)
    check(rootProject.file("config/$environment.properties").isFile) { "缺少${environment}配置文件" }
    check(rootProject.file("app/src/$environment/google-services.json").isFile) { "缺少${environment} Firebase配置" }
    if (environment == "prod") {
        check(rootProject.file("config/prod.properties").readBytes().contentEquals(rootProject.file("config/dev.properties").readBytes())) {
            "DEV_REUSE要求prod配置完整复用dev"
        }
        check(file("src/prod/google-services.json").readBytes().contentEquals(file("src/dev/google-services.json").readBytes())) {
            "DEV_REUSE要求prod Firebase完整复用dev"
        }
    }
    config.stringPropertyNames().forEach { key ->
        val value = config.getProperty(key)
        if (key.startsWith("build.boolean.")) check(value.toBooleanStrictOrNull() != null) { "布尔配置无效：$key" }
        if (key.startsWith("build.int.")) check(value.toIntOrNull() != null) { "整数配置无效：$key" }
    }
    flavorEndpoints.getValue("dev").keys.forEach { name ->
        val value = config.getProperty("build.string.$name").orEmpty()
        val uri = runCatching { URI(value) }.getOrNull()
        val scheme = if (name == "CORE_STREAM_URL") "wss" else "https"
        check(uri != null && uri.scheme == scheme && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "地址格式无效：$name"
        }
        if (name.endsWith("BASE_URL")) check(value.endsWith('/')) { "API基址必须以斜线结尾：$name" }
    }
}

val verifyDevConfiguration = tasks.register("verifyDevConfiguration") {
    doLast { verifyConfiguration("dev"); verifySigning(sharedSigning) }
}
val verifyProdConfiguration = tasks.register("verifyProdConfiguration") {
    doLast {
        verifyConfiguration("prod")
        verifySigning(sharedSigning)
        logger.lifecycle("临时prod：DEV_REUSE，测试服务和sharedDev签名，不是正式发布包")
    }
}

android {
    namespace = "yumo.achat.app"
    compileSdk = common.getProperty("sdk.compile").toInt()
    compileSdkMinor = common.getProperty("sdk.minor").toInt()

    defaultConfig {
        applicationId = "yumo.achat.app"
        minSdk = 24
        targetSdk = common.getProperty("sdk.target").toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ACHAT_CLIENT_VERSION", quoted("2.0.0"))
    }

    signingConfigs {
        create("sharedDev") {
            storeFile = sharedSigning.getProperty("storeFile")?.let(rootProject::file)
            storePassword = sharedSigning.getProperty("storePassword")
            keyAlias = sharedSigning.getProperty("keyAlias")
            keyPassword = sharedSigning.getProperty("keyPassword")
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        configurations.forEach { (environment, config) ->
            create(environment) {
                dimension = "environment"
                applicationId = config.getProperty("applicationId")
                versionCode = common.getProperty("app.version.code").toInt()
                versionName = common.getProperty("app.version.name")
                if (sharedSigningReady) signingConfig = signingConfigs.getByName("sharedDev")
                buildConfigField("String", "PROD_CONFIG_STATUS", quoted(mode))
                val host = URI(config.getProperty("build.string.CORE_BASE_URL"))
                buildConfigField("String", "ACHAT_API_BASE_URL", quoted("${host.scheme}://${host.host}"))
                buildConfigField("String", "ACHAT_WS_URL", quoted("${config.getProperty("build.string.CORE_STREAM_URL")}/connection/websocket"))
                resValue("string", "build_identity", if (environment == "prod") "prod / DEV_REUSE" else "dev / test")
                val canonical = config.stringPropertyNames().sorted().joinToString("\n", postfix = "\n") {
                    "$it=${config.getProperty(it)}"
                }
                val fingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                resValue("string", "kit_configuration_fingerprint", fingerprint)
                val firebase = JsonSlurper().parse(file("src/$environment/google-services.json")) as Map<*, *>
                val matchingClient = (firebase["client"] as List<*>).map { it as Map<*, *> }.first { client ->
                    val info = client["client_info"] as Map<*, *>
                    val androidInfo = info["android_client_info"] as Map<*, *>
                    androidInfo["package_name"] == applicationId
                }
                val clientInfo = matchingClient["client_info"] as Map<*, *>
                resValue("string", "google_app_id", clientInfo["mobilesdk_app_id"].toString())
                config.stringPropertyNames().filter { it.startsWith("build.") }.forEach { key ->
                    val (_, type, field) = key.split('.', limit = 3)
                    val value = config.getProperty(key).orEmpty()
                    when (type) {
                        "string" -> buildConfigField("String", field, quoted(value))
                        "boolean" -> buildConfigField("boolean", field, value)
                        "int" -> buildConfigField("int", field, value)
                    }
                }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (sharedSigningReady) signingConfig = signingConfigs.getByName("sharedDev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (sharedSigningReady) signingConfig = signingConfigs.getByName("sharedDev")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        val applicationBuilder = variantBuilder as com.android.build.api.variant.ApplicationVariantBuilder
        applicationBuilder.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

tasks.configureEach {
    if (name == "preDevDebugBuild" || name == "preDevReleaseBuild") dependsOn(verifyDevConfiguration)
    if (name == "preProdDebugBuild" || name == "preProdReleaseBuild") dependsOn(verifyProdConfiguration)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":integration:analytics-appsflyer"))
    implementation(project(":integration:analytics-thinkingdata"))
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("com.android.billingclient:billing-ktx:9.1.0")

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
