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
}

private fun File.readProperties(): Properties =
    Properties().also { properties ->
        inputStream().use(properties::load)
    }
