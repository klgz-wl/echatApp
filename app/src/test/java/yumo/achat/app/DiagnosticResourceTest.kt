package yumo.achat.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticResourceTest {
    private val values = File("src/main/res/values").listFiles()!!
        .filter { it.extension == "xml" }
        .flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }.toMap()

    @Test fun `common format preserves body and separates diagnostic with one space`() {
        assertEquals("%1\$s (%2\$s)", values["error_with_code"])
    }

    @Test fun `fixed errors retain original body and use the scenario code`() {
        val expected = mapOf(
            "login_agreement_required" to "Please agree to the Terms of Service and Privacy Policy first. (E-201)",
            "login_account_invalid" to "Account must have 3–50 characters without spaces. (E-202)",
            "login_password_invalid" to "Password must have 6–18 characters without spaces. (E-203)",
            "login_failed" to "Login failed. Please try again. (E-204)",
            "login_page_unavailable" to "Unable to load this page. (E-303)",
            "report_failed" to "Couldn’t report this content. Please try again. (E-701)",
            "account_logout_failed" to "Could not log out. Please try again. (E-702)",
            "account_delete_failed" to "Could not delete your account. Please try again. (E-703)",
            "task_result_preview_unavailable" to "Preview unavailable (E-404)",
            "task_result_file_unavailable" to "Result file unavailable, pull to refresh or try later. (E-404)",
            "profile_name_validation" to "Name must be between 2 and 50 characters (E-704)",
            "profile_name_update_failed" to "Unable to update display name (E-705)",
            "profile_avatar_update_failed" to "Unable to update profile photo (E-705)",
            "top_up_payment_cancelled" to "Checkout closed. Retry to check this payment. (E-602)",
            "top_up_payment_waiting" to "Waiting for payment confirmation... (E-602)",
            "top_up_payment_pending" to "Payment is pending in Google Play (E-602)",
            "top_up_payment_unconfirmed" to "Payment could not be confirmed. Retry to check the same order. (E-602)",
            "top_up_payment_failed" to "Payment failed. Check your payment method or try another card. (E-601)",
            "top_up_checkout_page_error" to "Connection lost. Check your network and retry this payment. (E-303)",
            "top_up_payment_network_error" to "Connection lost. Check your network and retry this payment. (E-602)",
        )
        expected.forEach { (name, text) -> assertEquals(name, text, values[name]) }
    }

    @Test fun `shared stage dependent bodies and ordinary retry labels remain uncoded`() {
        mapOf(
            "upload_failed" to "Upload failed",
            "task_failed" to "Task failed: %1\$s",
            "profile_update_failed" to "Unable to update profile",
            "task_failed_fallback" to "Task failed",
            "task_history_load_error" to "Failed to load task history",
            "top_up_payment_storage_unavailable" to "Payment storage is unavailable. Please try again later.",
            "region_retry" to "Retry",
            "top_up_retry" to "Retry",
            "template_feed_retry" to "RETRY",
            "template_feed_error_title" to "Templates unavailable",
            "region_unavailable" to "We couldn’t verify availability. Please try again.",
            "region_restricted" to "This app is not available in your region.",
            "region_offline" to "An internet connection is required. Please connect and try again.",
        ).forEach { (name, text) -> assertEquals(name, text, values[name]) }
    }

    @Test fun `region screen appends actual diagnostic inline and removes reference line`() {
        val activity = File("src/main/java/yumo/achat/app/MainActivity.kt").readText()
        val screen = activity.substringAfter("fun RegionGateScreen(")
        assertFalse("Separate Ref display must be removed", screen.contains("RegionDiagnosticReference"))
        assertEquals("Both restricted and unavailable bodies must use actual state diagnostic", 2,
            Regex("diagnosticString\\([\\s\\S]*?state\\.diagnosticCode").findAll(screen).count())
        assertFalse(values.containsKey("region_reference"))
    }
}
