package yumo.achat.app.config

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FormalProdConfigurationTest {
    private val rootDir: File
        get() = requireNotNull(File(requireNotNull(System.getProperty("user.dir"))).parentFile)

    @Test
    fun `prod flavor uses formal zorv identity instead of dev reuse`() {
        val app = rootDir.resolve("config/app.properties").readProperties()
        val prod = rootDir.resolve("config/prod.properties").readProperties()

        assertEquals("FORMAL", app.getProperty("kit.prod.mode"))
        assertEquals("com.zorv.app", prod.getProperty("applicationId"))
        assertEquals("zorv", prod.getProperty("app.display.name"))
    }

    @Test
    fun `prod attribution configuration is distinct from dev`() {
        val dev = rootDir.resolve("config/dev.properties").readProperties()
        val prod = rootDir.resolve("config/prod.properties").readProperties()

        assertNotEquals(
            dev.getProperty("build.string.APPSFLYER_DEV_KEY"),
            prod.getProperty("build.string.APPSFLYER_DEV_KEY"),
        )
    }

    @Test
    fun `formal prod release requires formal signing and confirmation gate`() {
        val gradle = rootDir.resolve("app/build.gradle.kts").readText()

        assert(gradle.contains("config/signing-release.local.properties")) {
            "prod release must read independent formal signing config"
        }
        assert(gradle.contains("config/prod-confirmation.local.properties")) {
            "prod release must require explicit local formal confirmation"
        }
        assert(gradle.contains("verifyFormalRelease")) {
            "prod release must expose a formal release verification task"
        }
        assert(gradle.contains("正式签名不能使用sharedDev证书")) {
            "formal release gate must reject sharedDev as formal signing"
        }
    }

}

private fun File.readProperties(): Properties =
    Properties().also { properties ->
        inputStream().use(properties::load)
    }
