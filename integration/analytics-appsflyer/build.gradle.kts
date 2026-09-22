plugins {
    id("com.android.library")
}

android {
    namespace = "yumo.achat.core.integration.appsflyer"
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
    implementation("com.appsflyer:af-android-sdk:6.18.1")
    implementation("com.android.installreferrer:installreferrer:2.2")
    implementation("com.google.android.gms:play-services-ads-identifier:18.0.0")
    testImplementation("junit:junit:4.13.2")
}
