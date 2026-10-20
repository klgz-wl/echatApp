package yumo.achat.core.integration.appsflyer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallReferrerEvidenceTest {
    private val paid = setOf("cpc", "ppc", "paid", "paid_social", "paid_search", "display", "cpm")

    @Test fun `gclid is paid evidence and preserves source`() {
        val result = installReferrerSupplement(
            raw = InstallReferrerRaw("utm_source=google&gclid=abc123", 11, 22),
            afUid = "af-uid",
            paidMediums = paid,
        )
        assertEquals("Non-organic", result?.get("af_status")?.toString()?.trim('"'))
        assertEquals("google", result?.get("media_source")?.toString()?.trim('"'))
    }

    @Test fun `utm requires paid medium and source while conflicts remain unknown`() {
        assertNull(installReferrerSupplement(InstallReferrerRaw("utm_source=google", 1, 2), "uid", paid))
        assertNull(installReferrerSupplement(InstallReferrerRaw("utm_source=google&utm_medium=organic", 1, 2), "uid", paid))
        assertNull(installReferrerSupplement(InstallReferrerRaw("utm_source=a&utm_source=b&utm_medium=cpc", 1, 2), "uid", paid))
        assertEquals(
            "meta",
            installReferrerSupplement(InstallReferrerRaw("utm_source=meta&utm_medium=paid_social", 1, 2), "uid", paid)
                ?.get("media_source")?.toString()?.trim('"'),
        )
    }
}
