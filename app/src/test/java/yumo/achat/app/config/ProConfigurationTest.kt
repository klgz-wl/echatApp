package yumo.achat.app.config

import java.io.File
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.app.BuildConfig

class ProConfigurationTest {
    private val root = requireNotNull(File(requireNotNull(System.getProperty("user.dir"))).parentFile)
    private fun properties(path: String) = Properties().apply { root.resolve(path).inputStream().use(::load) }

    @Test
    fun `pro has independent identity and attribution without replacing zorv`() {
        assertTrue("Pro needs its own configuration", root.resolve("config/pro.properties").isFile)
        val pro = properties("config/pro.properties")
        val prod = properties("config/prod.properties")
        assertEquals("com.zorv.pro", pro.getProperty("applicationId"))
        assertEquals("zorv pro", pro.getProperty("app.display.name"))
        assertEquals("1.0.0", pro.getProperty("app.version.name"))
        assertEquals("1", pro.getProperty("app.version.code"))
        assertEquals("T4wgmaaoaidaaey5aixxmnbpmfyhalld", pro.getProperty("build.string.TD_APP_ID"))
        assertEquals("XQPMSpakntMRgsQxTgkisS", pro.getProperty("build.string.APPSFLYER_DEV_KEY"))
        assertEquals("com.zorv.app", prod.getProperty("applicationId"))
        assertEquals("zorv", prod.getProperty("app.display.name"))
        assertNotEquals(prod.getProperty("build.string.TD_APP_ID"), pro.getProperty("build.string.TD_APP_ID"))
        assertNotEquals(prod.getProperty("build.string.APPSFLYER_DEV_KEY"), pro.getProperty("build.string.APPSFLYER_DEV_KEY"))
    }

    @Test
    fun `generated pro runtime uses new identity and existing production behavior`() {
        if (BuildConfig.FLAVOR != "pro") return
        val pro = properties("config/pro.properties")
        assertEquals("com.zorv.pro", BuildConfig.APPLICATION_ID)
        assertEquals(pro.getProperty("app.version.name"), BuildConfig.VERSION_NAME)
        assertEquals(BuildConfig.VERSION_NAME, BuildConfig.ACHAT_CLIENT_VERSION)
        assertEquals(pro.getProperty("app.version.code").toInt(), BuildConfig.VERSION_CODE)
        assertEquals(pro.getProperty("build.string.TD_APP_ID"), BuildConfig.TD_APP_ID)
        assertEquals(pro.getProperty("build.string.APPSFLYER_DEV_KEY"), BuildConfig.APPSFLYER_DEV_KEY)
        assertEquals("https://release.zorv.date/api/v1/", BuildConfig.CORE_BASE_URL)
        assertEquals("https://cdn.zorv.date", BuildConfig.CORE_CDN_URL)
        assertEquals("SERVICE", BuildConfig.PAYMENT_FLOW)
        assertTrue(BuildConfig.ENABLE_THINKINGDATA)
        assertTrue(BuildConfig.ENABLE_APPSFLYER)
        assertTrue(BuildConfig.ENABLE_SECURE_WINDOW)
        assertTrue(BuildConfig.ENABLE_REGION_RESTRICTION)
        assertFalse(BuildConfig.ENABLE_FIREBASE_ANALYTICS)
        assertFalse(BuildConfig.ENABLE_FIREBASE_CRASHLYTICS)
        assertFalse(BuildConfig.ENABLE_FIREBASE_MESSAGING)
    }
}
