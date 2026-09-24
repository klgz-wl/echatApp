package yumo.achat.app

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourceTest {
    private val mainRoot = Path.of("src/main")
    private val launcherSafeScale = 0.61

    @Test
    fun `manifest wires launcher icons and logo assets exist`() {
        val manifest = mainRoot.resolve("AndroidManifest.xml").readText()

        assertTrue(manifest.contains("""android:icon="@mipmap/ic_launcher""""))
        assertTrue(manifest.contains("""android:roundIcon="@mipmap/ic_launcher_round""""))
        assertTrue(Files.exists(mainRoot.resolve("res/drawable-nodpi/nexor_logo_wordmark.png")))
        assertTrue(Files.exists(mainRoot.resolve("res/mipmap-anydpi-v26/ic_launcher.xml")))
        assertTrue(Files.exists(mainRoot.resolve("res/mipmap-anydpi-v26/ic_launcher_round.xml")))
        assertTrue(Files.exists(mainRoot.resolve("res/mipmap-xxxhdpi/ic_launcher.png")))
        assertTrue(Files.exists(mainRoot.resolve("res/mipmap-xxxhdpi/ic_launcher_foreground.png")))
    }

    @Test
    fun `launcher foreground keeps full logo artwork inside adaptive icon safe area`() {
        val sourceLogo = ImageIO.read(mainRoot.resolve("res/drawable-nodpi/nexor_logo_wordmark.png").toFile())
        val foreground = ImageIO.read(mainRoot.resolve("res/mipmap-xxxhdpi/ic_launcher_foreground.png").toFile())
        val expected = sourceLogo.scaledIntoSafeLauncherCanvas(foreground.width, launcherSafeScale)

        assertTrue(
            "ic_launcher_foreground.png should be generated from the full logo with safe padding, not a cropped mark",
            foreground.averageChannelDifference(expected) < 2.0,
        )
    }

    private fun BufferedImage.scaledIntoSafeLauncherCanvas(size: Int, scale: Double): BufferedImage {
        val canvas = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val graphics = canvas.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val background = getRGB(0, 0)
        graphics.color = java.awt.Color(background)
        graphics.fillRect(0, 0, size, size)
        val innerSize = (size * scale).toInt()
        val offset = (size - innerSize) / 2
        graphics.drawImage(this, offset, offset, innerSize, innerSize, null)
        graphics.dispose()
        return canvas
    }

    private fun BufferedImage.averageChannelDifference(other: BufferedImage): Double {
        var total = 0L
        var channels = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val actual = getRGB(x, y)
                val expected = other.getRGB(x, y)
                total += abs(((actual shr 16) and 0xff) - ((expected shr 16) and 0xff))
                total += abs(((actual shr 8) and 0xff) - ((expected shr 8) and 0xff))
                total += abs((actual and 0xff) - (expected and 0xff))
                channels += 3
            }
        }
        return total.toDouble() / channels.toDouble()
    }
}
