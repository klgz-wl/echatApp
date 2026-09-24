package yumo.achat.app.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsConsentTest {
    @Test
    fun defaultAndLegacyDeclineStillAllowAttributionStartup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("achat_privacy_consent", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        assertEquals(true, AnalyticsConsent.decision(context))
        assertTrue(AnalyticsConsent.granted(context))

        AnalyticsConsent.save(context, false)

        assertEquals(true, AnalyticsConsent.decision(context))
        assertTrue(AnalyticsConsent.granted(context))
    }
}
