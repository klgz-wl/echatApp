import java.util.Properties
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
val config = rootProject.extra["appConfig"] as Properties
android {
    namespace = config.getProperty("core.namespace")
    compileSdk = config.getProperty("sdk.compile").toInt()
    compileSdkMinor = config.getProperty("sdk.minor").toInt()
    defaultConfig {
        minSdk = config.getProperty("sdk.min").toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    api(libs.retrofit)
    api(libs.okhttp)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.androidx.datastore.preferences)
    api(libs.billing.ktx)
}

tasks.register("testReleaseUnitTest") {
    dependsOn("testDebugUnitTest")
}
