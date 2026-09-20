package yumo.achat.app.ui.theme

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class AchatTypographyTest {
    @Test
    fun `typography keeps me screen text at readable sizes`() {
        assertEquals(22.sp, AchatTypography.headlineMedium.fontSize)
        assertEquals(20.sp, AchatTypography.titleLarge.fontSize)
        assertEquals(14.sp, AchatTypography.titleMedium.fontSize)
        assertEquals(13.sp, AchatTypography.labelLarge.fontSize)
        assertEquals(11.sp, AchatTypography.bodyMedium.fontSize)
        assertEquals(10.sp, AchatTypography.labelMedium.fontSize)
        assertEquals(9.sp, AchatTypography.labelSmall.fontSize)
    }
}
