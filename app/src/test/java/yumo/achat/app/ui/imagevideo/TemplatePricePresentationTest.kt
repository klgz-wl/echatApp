package yumo.achat.app.ui.imagevideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import yumo.achat.core.backend.VisualTemplate

class TemplatePricePresentationTest {
    @Test
    fun `loading state has no synthetic template price`() {
        assertNull(templatePriceForDisplay(null))
    }

    @Test
    fun `selected template displays its backend quote including zero`() {
        assertEquals(9, templatePriceForDisplay(template(fastPrice = 9, qualityPrice = 18)))
        assertEquals(0, templatePriceForDisplay(template(fastPrice = 0, qualityPrice = 18)))
        assertEquals(18, templatePriceForDisplay(template(fastPrice = null, qualityPrice = 18)))
    }

    @Test
    fun `selected template without a backend quote has no displayed price`() {
        assertNull(templatePriceForDisplay(template(fastPrice = null, qualityPrice = null)))
    }

    private fun template(fastPrice: Int?, qualityPrice: Int?) = VisualTemplate(
        id = "template-1",
        categoryId = null,
        categoryName = null,
        name = "Template",
        fileUrl = "",
        mimeType = "image/jpeg",
        width = 720,
        height = 1280,
        durationSeconds = 5,
        hotScore = 1,
        fastPrice = fastPrice,
        qualityPrice = qualityPrice,
    )
}
