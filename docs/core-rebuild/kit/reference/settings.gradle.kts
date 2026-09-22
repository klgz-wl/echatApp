pluginManagement {
    repositories {
        google {
            // 打包机可通过 -PgoogleMavenMirror 或用户级 gradle.properties 指定 HTTPS 镜像。
            providers.gradleProperty("googleMavenMirror").orNull?.takeIf { it.isNotBlank() }?.let { mirror ->
                val endpoint = uri(mirror)
                require(endpoint.scheme == "https" && !endpoint.host.isNullOrBlank()) {
                    "googleMavenMirror 必须是有效的 HTTPS Maven 仓库地址"
                }
                url = endpoint
            }
        }
        mavenCentral {
            providers.gradleProperty("mavenCentralMirror").orNull?.takeIf { it.isNotBlank() }?.let { mirror ->
                val endpoint = uri(mirror)
                require(endpoint.scheme == "https" && !endpoint.host.isNullOrBlank()) {
                    "mavenCentralMirror 必须是有效的 HTTPS Maven 仓库地址"
                }
                url = endpoint
            }
        }
        val pluginPortalMirror = providers.gradleProperty("gradlePluginPortalMirror")
            .orNull?.takeIf { it.isNotBlank() }
        if (pluginPortalMirror == null) {
            gradlePluginPortal()
        } else {
            maven {
                name = "GradlePluginPortalMirror"
                val endpoint = uri(pluginPortalMirror)
                require(endpoint.scheme == "https" && !endpoint.host.isNullOrBlank()) {
                    "gradlePluginPortalMirror 必须是有效的 HTTPS Maven 仓库地址"
                }
                url = endpoint
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            // 打包机可通过 -PgoogleMavenMirror 或用户级 gradle.properties 指定 HTTPS 镜像。
            providers.gradleProperty("googleMavenMirror").orNull?.takeIf { it.isNotBlank() }?.let { mirror ->
                val endpoint = uri(mirror)
                require(endpoint.scheme == "https" && !endpoint.host.isNullOrBlank()) {
                    "googleMavenMirror 必须是有效的 HTTPS Maven 仓库地址"
                }
                url = endpoint
            }
        }
        mavenCentral {
            providers.gradleProperty("mavenCentralMirror").orNull?.takeIf { it.isNotBlank() }?.let { mirror ->
                val endpoint = uri(mirror)
                require(endpoint.scheme == "https" && !endpoint.host.isNullOrBlank()) {
                    "mavenCentralMirror 必须是有效的 HTTPS Maven 仓库地址"
                }
                url = endpoint
            }
        }
        // AppsFlyer SDK 仓库
        maven { url = uri("https://maven.appsflyer.com/maven2") }
        // 数数 SDK 与第三方插件仓库
        maven { url = uri("https://repo.thinkingdata.cn/maven/") }
    }
}

rootProject.name = "Vexora"
include(":app")
include(":core")
include(":integration:analytics-appsflyer")
include(":integration:analytics-thinkingdata")
