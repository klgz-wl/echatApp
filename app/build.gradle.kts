plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "yumo.achat.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "yumo.achat.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ACHAT_API_BASE_URL", "\"https://test.appjoly.com\"")
        buildConfigField("String", "ACHAT_WS_URL", "\"wss://test.appjoly.com/connection/websocket\"")
        buildConfigField("String", "ACHAT_CLIENT_VERSION", "\"2.0.0\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "ACHAT_API_BASE_URL", "\"https://test.appjoly.com\"")
            buildConfigField("String", "ACHAT_WS_URL", "\"wss://test.appjoly.com/connection/websocket\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "ACHAT_API_BASE_URL", "\"https://release.appjoly.com\"")
            buildConfigField("String", "ACHAT_WS_URL", "\"wss://release.appjoly.com/connection/websocket\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")

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
