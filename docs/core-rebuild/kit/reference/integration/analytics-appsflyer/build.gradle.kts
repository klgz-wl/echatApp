import java.util.Properties
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
val config = rootProject.extra["appConfig"] as Properties
android {
    namespace = config.getProperty("core.namespace") + ".integration.appsflyer"
    compileSdk = config.getProperty("sdk.compile").toInt()
    compileSdkMinor = config.getProperty("sdk.minor").toInt()
    defaultConfig { minSdk = config.getProperty("sdk.min").toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    testImplementation(libs.junit)
    implementation(project(":core"))
    implementation(libs.appsflyer.sdk)
    implementation(libs.install.referrer)
    implementation(libs.play.services.ads.identifier)
}
