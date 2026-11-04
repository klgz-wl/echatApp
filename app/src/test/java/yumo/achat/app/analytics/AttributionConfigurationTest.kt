package yumo.achat.app.analytics

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import yumo.achat.app.BuildConfig

class AttributionConfigurationTest {
    private fun root(): File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "config/app.properties").isFile }

    @Test fun `actual flavor version and backend version use the same configured value`() {
        val config = Properties().apply {
            File(root(), "config/app.properties").inputStream().use(::load)
            File(root(), "config/${BuildConfig.FLAVOR}.properties").inputStream().use(::load)
        }
        assertEquals(config.getProperty("app.version.name"), BuildConfig.VERSION_NAME)
        assertEquals(BuildConfig.VERSION_NAME, BuildConfig.ACHAT_CLIENT_VERSION)
        assertEquals(1000, BuildConfig.ADVERTISING_ID_TIMEOUT_MS)
        assertEquals("https://gcdsdk.appsflyer.com/", BuildConfig.APPSFLYER_GCD_BASE_URL)
        assertNull(config.getProperty("build.string.ACHAT_CLIENT_VERSION"))
    }

    @Test fun `backup and transfer exclude installation data explicitly`() {
        val manifest = File(root(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:name=\"attribution.storage.name\""))
        assertTrue(manifest.contains("android:value=\"\${attributionStorageName}\""))
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (name in listOf("backup_rules", "data_extraction_rules")) {
            val document = parser.parse(File(root(), "app/src/main/res/xml/$name.xml"))
            val groups = if (name == "backup_rules") listOf(document.documentElement) else
                listOf("cloud-backup", "device-transfer").map { document.getElementsByTagName(it).item(0) }
            groups.forEach { group ->
                val excluded = (0 until group.childNodes.length).map { group.childNodes.item(it) }
                    .filter { it.nodeName == "exclude" }.associate {
                        it.attributes.getNamedItem("domain").nodeValue to it.attributes.getNamedItem("path").nodeValue
                    }
                for (domain in listOf("root", "file", "sharedpref", "database", "device_root", "device_file", "device_sharedpref", "device_database")) {
                    assertEquals("$name/$domain", ".", excluded[domain])
                }
            }
        }
    }
}
