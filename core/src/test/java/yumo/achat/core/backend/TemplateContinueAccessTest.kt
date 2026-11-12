package yumo.achat.core.backend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TemplateContinueAccessTest {
    @Test fun ordinaryTemplateDoesNotQueryMembership() = runBlocking {
        assertEquals(TemplateVipAccess.Allowed, checkTemplateContinueAccess(false) { error("must not query") })
    }
    @Test fun vipTemplateContinueChecksEffectiveSubscription() = runBlocking {
        var checks = 0
        for ((membership, expected) in listOf(true to TemplateVipAccess.Allowed,
            false to TemplateVipAccess.SubscriptionRequired, null to TemplateVipAccess.CheckRequired)) {
            assertEquals(expected, checkTemplateContinueAccess(true) { checks++; membership })
        }
        assertEquals(3, checks)
    }
    @Test fun failedMembershipCheckDoesNotSubmitOrAssumeNonVip() = runBlocking {
        assertEquals(TemplateVipAccess.CheckRequired, checkTemplateContinueAccess(true) { throw java.io.IOException() })
    }
}
