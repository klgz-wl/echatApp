package yumo.achat.app.ui.imagevideo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import yumo.achat.app.ui.error.withDiagnosticCode
import yumo.achat.app.ui.error.isRegionDiagnosticCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ErrorDiagnosticTest {
    @Test
    fun `diagnostics preserve body and append exactly once`() {
        val format = "%1\$s (%2\$s)"
        assertEquals("Please try again. (E-501)", withDiagnosticCode("Please try again.", "E-501", format))
        assertEquals("Please try again. (E-501)", withDiagnosticCode("Please try again. (E-501)", "E-501", format))
        assertEquals("Please try again. (E-207)", withDiagnosticCode("Please try again. (E-501) (E-501)", "E-207", format))
        assertEquals("We couldn’t verify availability. (R205-429)",
            withDiagnosticCode("We couldn’t verify availability.", "R205-429", format))
        assertEquals("Recovered payment.\npack: USD 1.00 (E-602)",
            withDiagnosticCode("Recovered payment. (E-602)\npack: USD 1.00", "E-602", format))
        assertEquals("Recovered payment.\r\npack: USD 1.00 (E-602)",
            withDiagnosticCode("Recovered payment. (E-602)\r\npack: USD 1.00", "E-602", format))
    }

    @Test
    fun `region diagnostics accept only dictionary codes and numeric statuses`() {
        assertTrue(isRegionDiagnosticCode("R205-403"))
        assertTrue(isRegionDiagnosticCode("R200"))
        assertFalse(isRegionDiagnosticCode("R205-403 secret"))
        assertFalse(isRegionDiagnosticCode("R205-secret"))
        assertFalse(isRegionDiagnosticCode("R205-999"))
        assertFalse(isRegionDiagnosticCode("R999"))
    }

    @Test
    fun `error suffix format is an Android resource`() {
        val file = sequenceOf(File("src/main/res/values/error_strings.xml"), File("app/src/main/res/values/error_strings.xml"))
            .first { it.isFile }
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            .getElementsByTagName("string")
        val format = (0 until strings.length).map { strings.item(it) }
            .firstOrNull { it.attributes.getNamedItem("name").nodeValue == "error_with_code" }?.textContent
        assertEquals("%1\$s (%2\$s)", format)
    }

    @Test
    fun `generation errors never expose technical response details`() {
        val fallback = "Upload failed"
        listOf(
            "<html>secret response</html>",
            "{\"message\":\"<script>secret</script>\"}",
            "{broken JSON with secret trace",
            "Unable to connect to https://secret.example/user?token=private",
            "java.io.IOException: secret exception",
            "Failed to connect to /192.168.1.10:443",
            "Unable to resolve host api.example.test: No address associated with hostname",
            "[{\"internal\":\"response body\"}]",
            "HTTP 503",
            "No content provider: content://media/external/images/media/42",
            "/storage/emulated/0/Pictures/private.jpg",
            "JSONObject[\"task_id\"] not found.",
        ).forEach { raw -> assertEquals(fallback, visualGenerationUserMessage(raw, fallback)) }
    }
}
