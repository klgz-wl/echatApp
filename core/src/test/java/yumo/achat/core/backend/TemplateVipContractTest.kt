package yumo.achat.core.backend

import org.junit.Assert.*
import org.junit.Test

class TemplateVipContractTest {
    @Test fun templateVipIsIndependentOfPriceAndAcceptsLegacyMissingFlag() {
        fun parse(flag: String) = AchatBackendParsers.parseTemplatePage(
            """{"code":0,"data":{"items":[{"id":"t","prices":{"fast":50},$flag"name":"t"}]}}""").items.single()
        assertTrue(parse("\"vip\":true,").vip)
        assertFalse(parse("\"vip\":false,").vip)
        assertFalse(parse("").vip)
        assertThrows(IllegalStateException::class.java) { parse("\"vip\":\"true\",") }
    }
    @Test fun effectiveVipUsesOnlyExplicitBooleanNotRenewalOrDates() {
        assertTrue(AchatBackendParsers.parseVipInfo("""{"code":0,"data":{"is_vip":true,"auto_renew":false,"days_remaining":0}}""").isVip)
        for (body in listOf("""{"code":0,"data":{}}""", """{"code":0,"data":{"is_vip":"true"}}""",
            """{"code":403,"data":{"is_vip":true}}""", """{"code":0,"type":"encrypted","data":{"is_vip":true}}""")) {
            assertThrows(IllegalStateException::class.java) { AchatBackendParsers.parseVipInfo(body) }
        }
    }
    @Test fun onlySpecificGenerationVip403GuidesSubscription() {
        assertTrue(isTemplateVipRejection(AchatBackendHttpException(403, """{"code":200101}""")))
        assertFalse(isTemplateVipRejection(AchatBackendHttpException(403, """{"code":403}""")))
        assertFalse(isTemplateVipRejection(AchatBackendHttpException(401, """{"code":200101}""")))
        assertFalse(isTemplateVipRejection(AchatBackendHttpException(402, """{"code":400101}""")))
        assertFalse(isTemplateVipRejection(AchatBackendHttpException(403, "not json")))
    }
    @Test fun ordinaryTemplatesDoNotNeedMembershipAndUnknownIsNotNonVip() {
        assertEquals(TemplateVipAccess.Allowed, templateVipAccess(false, null))
        assertEquals(TemplateVipAccess.Allowed, templateVipAccess(true, true))
        assertEquals(TemplateVipAccess.SubscriptionRequired, templateVipAccess(true, false))
        assertEquals(TemplateVipAccess.CheckRequired, templateVipAccess(true, null))
    }
}
