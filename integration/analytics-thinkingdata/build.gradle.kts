plugins {
    id("com.android.library")
}

android {
    namespace = "yumo.achat.core.integration.thinkingdata"
    compileSdk = 37
    compileSdkMinor = 0
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        val libraryBuilder = variantBuilder as com.android.build.api.variant.LibraryVariantBuilder
        libraryBuilder.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation("cn.thinkingdata.android:ThinkingAnalyticsSDK:3.4.0")
    implementation("cn.thinkingdata.android:TAThirdParty:2.0.0")
    testImplementation("junit:junit:4.13.2")
}
