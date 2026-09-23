import java.security.MessageDigest
import java.util.Properties

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
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sharedDev")
        }
        release {
            signingConfig = signingConfigs.getByName("sharedDev")
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
                signingConfig = signingConfigs.getByName("sharedDev")
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
