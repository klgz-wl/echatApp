package yumo.achat.app.config

import java.io.File
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.app.BuildConfig

class ProDevConfigurationTest {
    private val root = requireNotNull(File(requireNotNull(System.getProperty("user.dir"))).parentFile)

    @Test
    fun `pro development config is separate from production`() {
        val file = root.resolve("config/proDev.properties")
        assertTrue("Pro development configuration must exist", file.isFile)
        val config = Properties().apply { file.inputStream().use(::load) }
        assertEquals("yumo.achat.app", config.getProperty("applicationId"))
        assertEquals("zorv pro", config.getProperty("app.display.name"))
        assertEquals("2.0.0", config.getProperty("app.version.name"))
        val prod = Properties().apply { root.resolve("config/pro.properties").inputStream().use(::load) }
        assertEquals("com.zorv.pro", prod.getProperty("applicationId"))
    }

    @Test
    fun `pro development runtime uses only test endpoints and shared yumo test identity`() {
        if (BuildConfig.FLAVOR != "proDev") return
        assertTrue(BuildConfig.DEBUG)
        assertEquals("yumo.achat.app", BuildConfig.APPLICATION_ID)
        assertEquals("https://test.appjoly.com", BuildConfig.ACHAT_API_BASE_URL)
        assertEquals("https://test.appjoly.com/api/v1/", BuildConfig.CORE_BASE_URL)
        assertEquals("https://test.appjoly.com/payment-api/v1/", BuildConfig.PAYMENT_BASE_URL)
        assertEquals("wss://test.appjoly.com", BuildConfig.CORE_STREAM_URL)
        assertEquals("https://cdn.appjoly.com", BuildConfig.CORE_CDN_URL)
        assertEquals(BuildConfig.VERSION_NAME, BuildConfig.ACHAT_CLIENT_VERSION)
        assertFalse(BuildConfig.ENABLE_REGION_RESTRICTION)
        assertFalse(BuildConfig.ENABLE_SECURE_WINDOW)
        val dev = Properties().apply { root.resolve("config/dev.properties").inputStream().use(::load) }
        listOf(
            "build.string.APPSFLYER_DEV_KEY" to BuildConfig.APPSFLYER_DEV_KEY,
            "build.string.TD_APP_ID" to BuildConfig.TD_APP_ID,
            "build.string.TD_SERVER_URL" to BuildConfig.TD_SERVER_URL,
            "build.string.DEEP_LINK_SCHEME" to BuildConfig.DEEP_LINK_SCHEME,
            "app.version.name" to BuildConfig.VERSION_NAME,
        ).forEach { (key, actual) -> assertEquals("Shared dev setting: $key", dev.getProperty(key), actual) }
        assertTrue(BuildConfig.ENABLE_FIREBASE_ANALYTICS)
        assertTrue(BuildConfig.ENABLE_FIREBASE_CRASHLYTICS)
        assertTrue(BuildConfig.ENABLE_FIREBASE_MESSAGING)
    }
}
