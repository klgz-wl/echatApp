package yumo.achat.core.integration.appsflyer

import org.junit.Assert.*
import org.junit.Test

class Core8ReferrerAuditRegressionTest {
    @Test fun `conflicting gclid cannot be rescued by a paid UTM branch`() {
        val raw = InstallReferrerRaw("gclid=x&gclid=y&utm_source=google&utm_medium=cpc", 1, 2)
        assertNull(installReferrerSupplement(raw, "af-install", setOf("cpc")))
    }

    @Test fun `conflicting source cannot be rescued by a valid gclid branch`() {
        val raw = InstallReferrerRaw("gclid=valid&utm_source=google&utm_source=other", 1, 2)
        assertNull(installReferrerSupplement(raw, "af-install", setOf("cpc")))
    }

    @Test fun `any conflicting decoded parameter rejects all paid evidence`() {
        listOf("utm_medium=cpc&utm_medium=organic", "utm_campaign=a&utm_campaign=b",
            "utm_id=1&utm_id=2", "extra=a&extra=b", "utm_source=google&%75tm_source=other")
            .forEach { conflict ->
                assertNull(conflict, installReferrerSupplement(
                    InstallReferrerRaw("gclid=valid&$conflict", 1, 2), "af-install", setOf("cpc")))
            }
    }

    @Test fun `identical decoded duplicates remain valid but malformed encoding does not`() {
        assertNotNull(installReferrerSupplement(
            InstallReferrerRaw("gclid=x&gclid=%78&utm_campaign=a&utm_campaign=a", 1, 2),
            "af-install", setOf("cpc")))
        assertNull(installReferrerSupplement(
            InstallReferrerRaw("gclid=x&extra=%XX", 1, 2), "af-install", setOf("cpc")))
    }
}
