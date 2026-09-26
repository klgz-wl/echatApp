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
    fun `all launcher flavors use lowercase zorv brand`() {
        val dev = rootDir.resolve("config/dev.properties").readProperties()
        val prod = rootDir.resolve("config/prod.properties").readProperties()

        assertEquals("zorv", dev.getProperty("app.display.name"))
        assertEquals("zorv", prod.getProperty("app.display.name"))
    }

    @Test
    fun `prod flavor uses formal zorv identity instead of dev reuse`() {
        val app = rootDir.resolve("config/app.properties").readProperties()
        val prod = rootDir.resolve("config/prod.properties").readProperties()
        val gradle = rootDir.resolve("app/build.gradle.kts").readText()

        assertEquals("FORMAL", app.getProperty("kit.prod.mode"))
        assertEquals("com.zorv.app", prod.getProperty("applicationId"))
        assertEquals("zorv", prod.getProperty("app.display.name"))
        assertEquals("PhippenLautner@gmail.com", prod.getProperty("build.string.CONTACT_EMAIL"))
        assertEquals(
            2,
            Regex("CORE_CDN_URL\\\" to \\\"https://cdn\\.zorv\\.date").findAll(gradle).count(),
        )
        assert(gradle.contains("requiredProdContactEmail"))
    }

    @Test
    fun `prod attribution configuration is distinct from dev`() {
        val dev = rootDir.resolve("config/dev.properties").readProperties()
        val prod = rootDir.resolve("config/prod.properties").readProperties()
        val gradle = rootDir.resolve("app/build.gradle.kts").readText()

        assertNotEquals(
            dev.getProperty("build.string.APPSFLYER_DEV_KEY"),
            prod.getProperty("build.string.APPSFLYER_DEV_KEY"),
        )
        assertEquals(
            "T4s7v9z0p2k6d5r8m1g3hqxcn4bwyj",
            prod.getProperty("build.string.TD_APP_ID"),
        )
        assertNotEquals(
            dev.getProperty("build.string.TD_APP_ID"),
            prod.getProperty("build.string.TD_APP_ID"),
        )
        assert(gradle.contains("requiredProdThinkingDataAppId"))
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

    @Test
    fun `formal prod region restriction has a real lookup endpoint`() {
        val app = rootDir.resolve("config/app.properties").readProperties()
        val prod = rootDir.resolve("config/prod.properties").readProperties()
        val gradle = rootDir.resolve("app/build.gradle.kts").readText()
        val blocked = app.getProperty("build.string.BLOCKED_REGION_CODES")
            .split(',').map(String::trim).filter(String::isNotBlank).toSet()

        assertEquals("true", prod.getProperty("build.boolean.ENABLE_REGION_RESTRICTION"))
        assertEquals("true", prod.getProperty("build.boolean.ENABLE_SECURE_WINDOW"))
        assertEquals(setOf("CN", "TW", "HK", "MO", "MY", "SG"), blocked)
        assertEquals(2, Regex("REGION_LOOKUP_URL\\\" to \\\"https://api\\.country\\.is/").findAll(gradle).count())
        assert(gradle.contains("requiredBlockedRegionCodes"))
    }

    @Test
    fun `every prod release packaging task is protected`() {
        val gradle = rootDir.resolve("app/build.gradle.kts").readText()

        listOf(
            "assembleProdRelease",
            "bundleProdRelease",
            "packageProdRelease",
            "packageProdReleaseBundle",
            "signProdReleaseBundle",
        ).forEach { task -> assert(gradle.contains("\"$task\"")) { "missing protected task: $task" } }
        assert(gradle.contains("\"ENABLE_SECURE_WINDOW\" to true"))
        assert(gradle.contains("\"ENABLE_REGION_RESTRICTION\" to true"))
    }

}

private fun File.readProperties(): Properties =
    Properties().also { properties ->
        inputStream().use(properties::load)
    }
