pluginManagement {
    fun httpsMirror(propertyName: String): java.net.URI? =
        providers.gradleProperty(propertyName).orNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { raw ->
                java.net.URI(raw).also { mirror ->
                    require(mirror.scheme == "https" && !mirror.host.isNullOrBlank()) {
                        "$propertyName must be an absolute HTTPS URL"
                    }
                }
            }
    val googleMavenMirror = httpsMirror("googleMavenMirror")
    val mavenCentralMirror = httpsMirror("mavenCentralMirror")
    val gradlePluginPortalMirror = httpsMirror("gradlePluginPortalMirror")

    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application", "com.android.library" ->
                    useModule("com.android.tools.build:gradle:${requested.version}")
                "com.google.gms.google-services" ->
                    useModule("com.google.gms:google-services:${requested.version}")
                "com.google.firebase.crashlytics" ->
                    useModule("com.google.firebase:firebase-crashlytics-gradle:${requested.version}")
                "com.google.dagger.hilt.android" ->
                    useModule("com.google.dagger:hilt-android-gradle-plugin:${requested.version}")
                "com.google.devtools.ksp" ->
                    useModule(
                        "com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}",
                    )
            }
        }
    }

    repositories {
        if (googleMavenMirror == null) google() else maven {
            name = "googleMavenMirror"
            url = googleMavenMirror
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.dagger(\\..*)?")
                includeGroupByRegex("com\\.google\\.firebase(\\..*)?")
                includeGroupByRegex("com\\.google\\.gms(\\..*)?")
            }
        }
        if (gradlePluginPortalMirror == null) gradlePluginPortal() else maven {
            name = "gradlePluginPortalMirror"
            url = gradlePluginPortalMirror
        }
        if (mavenCentralMirror == null) mavenCentral() else maven {
            name = "mavenCentralMirror"
            url = mavenCentralMirror
        }
    }
}

dependencyResolutionManagement {
    fun httpsMirror(propertyName: String): java.net.URI? =
        providers.gradleProperty(propertyName).orNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { raw ->
                java.net.URI(raw).also { mirror ->
                    require(mirror.scheme == "https" && !mirror.host.isNullOrBlank()) {
                        "$propertyName must be an absolute HTTPS URL"
                    }
                }
            }
    val googleMavenMirror = httpsMirror("googleMavenMirror")
    val mavenCentralMirror = httpsMirror("mavenCentralMirror")

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (googleMavenMirror == null) google() else maven {
            name = "googleMavenMirror"
            url = googleMavenMirror
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.dagger(\\..*)?")
                includeGroupByRegex("com\\.google\\.firebase(\\..*)?")
                includeGroupByRegex("com\\.google\\.gms(\\..*)?")
            }
        }
        if (mavenCentralMirror == null) mavenCentral() else maven {
            name = "mavenCentralMirror"
            url = mavenCentralMirror
        }
        maven { url = uri("https://maven.appsflyer.com/maven2") }
        maven { url = uri("https://repo.thinkingdata.cn/maven/") }
    }
}

rootProject.name = "echatApp"
include(":app")
include(":core")
include(":integration:analytics-appsflyer")
include(":integration:analytics-thinkingdata")
