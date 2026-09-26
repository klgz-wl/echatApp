package yumo.achat.app.config

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.json.JSONObject

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
        assertEquals("info@frostandbites.site", prod.getProperty("build.string.CONTACT_EMAIL"))
        assertEquals(
            2,
            Regex("CORE_CDN_URL\\\" to \\\"https://cdn\\.zorv\\.date").findAll(gradle).count(),
        )
        assert(gradle.contains("requiredProdContactEmail"))
        assert(gradle.contains("info@frostandbites.site"))
    }

    @Test
    fun `prod uses zorv privacy and terms pages`() {
        val prod = rootDir.resolve("config/prod.properties").readProperties()
        val gradle = rootDir.resolve("app/build.gradle.kts").readText()

        assertEquals(
            "https://sites.google.com/view/zorvprivacy/home",
            prod.getProperty("build.string.PRIVACY_POLICY_URL"),
        )
        assertEquals(
            "https://sites.google.com/view/zorvterms/home",
            prod.getProperty("build.string.TERMS_OF_SERVICE_URL"),
        )
        assert(gradle.contains("requiredProdPrivacyPolicyUrl"))
        assert(gradle.contains("requiredProdTermsOfServiceUrl"))
    }

    @Test
    fun `prod backend routes use the zorv host`() {
        val gradle = rootDir.resolve("app/build.gradle.kts").readText()

        assertEquals(
            2,
            Regex("CORE_BASE_URL\\\" to \\\"https://cdn\\.zorv\\.date/api/v1/")
                .findAll(gradle).count(),
        )
        assert(gradle.contains("\"ACHAT_API_BASE_URL\" to \"https://cdn.zorv.date\""))
        assertEquals(
            2,
            Regex("PAYMENT_BASE_URL\\\" to \\\"https://cdn\\.zorv\\.date/payment-api/v1/")
                .findAll(gradle).count(),
        )
        assertEquals(
            2,
            Regex("CORE_STREAM_URL\\\" to \\\"wss://cdn\\.zorv\\.date")
                .findAll(gradle).count(),
        )
        assert(gradle.contains("\"ACHAT_WS_URL\" to \"wss://cdn.zorv.date/connection/websocket\""))
        assert(Regex("CORE_CDN_URL\\\" to \\\"https://cdn\\.zorv\\.date").findAll(gradle).count() >= 2)
        assertFalse(gradle.contains("https://release.appjoly.com"))
        assertFalse(gradle.contains("wss://release.appjoly.com"))
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
    fun `prod google services uses the zorv firebase project`() {
        val google = JSONObject(rootDir.resolve("app/src/prod/google-services.json").readText())
        val project = google.getJSONObject("project_info")
        val client = google.getJSONArray("client").getJSONObject(0).getJSONObject("client_info")

        assertEquals("zorv-and", project.getString("project_id"))
        assertEquals("546418872161", project.getString("project_number"))
        assertEquals("com.zorv.app", client.getJSONObject("android_client_info").getString("package_name"))
        assertEquals("1:546418872161:android:1ec4fb9b9bdc62060dff20", client.getString("mobilesdk_app_id"))
    }

    @Test
    fun `prod new purchases use explicit legacy flow`() {
        val prod = rootDir.resolve("config/prod.properties").readProperties()
        val gradle = rootDir.resolve("app/build.gradle.kts").readText()

        assertEquals("LEGACY", prod.getProperty("build.string.PAYMENT_FLOW"))
        assert(gradle.contains("\"PAYMENT_FLOW\" to \"LEGACY\""))
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
    fun `formal signing gate is independent from disabled firebase capabilities`() {
        val gradle = rootDir.resolve("app/build.gradle.kts").readText()
        val prod = rootDir.resolve("config/prod.properties").readProperties()
        val formalGate = gradle.substringAfter("tasks.register(\"verifyFormalRelease\")")
            .substringBefore("tasks.configureEach")

        listOf(
            "build.boolean.ENABLE_FIREBASE_ANALYTICS",
            "build.boolean.ENABLE_FIREBASE_CRASHLYTICS",
            "build.boolean.ENABLE_FIREBASE_MESSAGING",
        ).forEach { key -> assertEquals("false", prod.getProperty(key)) }
        assertFalse(formalGate.contains("google-services.json"))
        assertFalse(formalGate.contains("certificate_hash"))
        assertFalse(formalGate.contains("Firebase/OAuth证书指纹与正式签名不匹配"))
    }

    @Test
    fun `formal signing gate validates private key certificate and store isolation`() {
        val gradle = rootDir.resolve("app/build.gradle.kts").readText()
        val formalGate = gradle.substringAfter("tasks.register(\"verifyFormalRelease\")")
            .substringBefore("tasks.configureEach")

        assert(gradle.contains("getKey(")) { "formal signing gate must unlock the configured private key" }
        assert(gradle.contains("is PrivateKey")) { "formal signing alias must contain a private key" }
        assert(gradle.contains("checkValidity()")) { "formal certificate must be currently valid" }
        assert(formalGate.contains("signingCertificate(formalSigningProperties"))
        assert(formalGate.contains("canonicalFile")) { "formal and sharedDev stores must be path-isolated" }
        assert(formalGate.contains("config/shared-dev.jks")) {
            "formal store must be isolated from the trusted sharedDev baseline even without local dev properties"
        }
    }

    @Test
    fun `formal confirmation does not require firebase upload certificate while firebase is disabled`() {
        val template = rootDir.resolve("config/prod-confirmation.properties.example").readText()

        assert(template.contains("Android SDK版本在config/app.properties"))
        assert(template.contains("正式三方SDK配置在config/prod.properties"))
        assertFalse(template.contains("同步替换app/src/prod/google-services.json和正式签名"))
        assert(template.contains("Firebase能力保持关闭时，不要求上传证书匹配Firebase/OAuth"))
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
