pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.appsflyer.com/maven2") }
        maven { url = uri("https://repo.thinkingdata.cn/maven/") }
    }
}

rootProject.name = "echatApp"
include(":app")
include(":core")
include(":integration:analytics-appsflyer")
include(":integration:analytics-thinkingdata")
