import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}
extra["appConfig"] = Properties().apply { file("config/app.properties").inputStream().use(::load) }

// 使用 Android 官方转换器保留 Figma 路径，不按图标名称重新绘制。
tasks.register("convertFigmaVectors") {
    group = "design"
    description = "将清单中的 Figma SVG 转为 Android VectorDrawable"
    doLast {
        val assets = java.util.Properties().apply {
            file("config/figma-vectors.properties").inputStream().use(::load)
        }
        val destination = file("app/src/main/res/drawable").apply { mkdirs() }
        assets.stringPropertyNames().sorted().forEach { name ->
            val source = file("docs/figma-assets/${assets.getProperty(name)}")
            val output = destination.resolve("$name.xml")
            val errors = output.outputStream().use {
                com.android.ide.common.vectordrawable.Svg2Vector.parseSvgToXml(source.toPath(), it)
            }
            check(errors.isNullOrBlank()) { "$name 转换失败：$errors" }
        }
    }
}
