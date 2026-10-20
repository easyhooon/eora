package com.easyhooon.eora.internal.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

class ImageTransformTest {
    @Test
    fun `fits the image centered and maps both ways`() {
        // 1000x2000 image in a 500x500 view: scale 0.25, image is 250 wide, centered horizontally.
        val t = ImageTransform(1000, 2000, 500f, 500f)
        assertEquals(0.25f, t.scale, 0.0001f)
        assertEquals(125f, t.offsetX, 0.0001f)
        assertEquals(ImagePoint(400f, 800f), t.toImage(t.toViewX(400f), t.toViewY(800f)))
    }

    @Test
    fun `zoom and pan keep image coordinates consistent`() {
        val t = ImageTransform(1000, 2000, 500f, 500f).zoomedBy(3f, 40f, -60f)
        val p = t.toImage(t.toViewX(123f), t.toViewY(1500f))
        assertEquals(123f, p.x, 0.01f)
        assertEquals(1500f, p.y, 0.01f)
    }

    @Test
    fun `a rotated view still maps to the same image pixel`() {
        val portrait = ImageTransform(1080, 2400, 1080f, 2000f)
        val landscape = ImageTransform(1080, 2400, 2000f, 1000f)
        val pixel = ImagePoint(540f, 1200f)
        assertEquals(pixel, portrait.toImage(portrait.toViewX(pixel.x), portrait.toViewY(pixel.y)))
        assertEquals(pixel, landscape.toImage(landscape.toViewX(pixel.x), landscape.toViewY(pixel.y)))
    }

    @Test
    fun `the matrix given to Ink maps view pixels to the same image pixels`() {
        val t = ImageTransform(1080, 2400, 900f, 1600f).zoomedBy(2.5f, 30f, -120f)
        val m = t.viewToImageComposeMatrix()
        listOf(androidx.compose.ui.geometry.Offset(10f, 20f), androidx.compose.ui.geometry.Offset(450f, 800f)).forEach { view ->
            val expected = t.toImage(view.x, view.y)
            val actual = m.map(view)
            assertEquals(expected.x, actual.x, 0.01f)
            assertEquals(expected.y, actual.y, 0.01f)
        }
    }

    @Test
    fun `points outside the image clamp to its edge`() {
        val t = ImageTransform(100, 100, 100f, 100f)
        assertEquals(ImagePoint(0f, 100f), t.toImage(-50f, 500f))
    }

    @Test
    fun `zoom is bounded`() {
        assertEquals(ImageTransform.MAX_ZOOM, ImageTransform(1, 1, 1f, 1f).zoomedBy(100f, 0f, 0f).zoom)
        assertEquals(ImageTransform.MIN_ZOOM, ImageTransform(1, 1, 1f, 1f).zoomedBy(0.01f, 0f, 0f).zoom)
    }

    @Test
    fun `tiny accidental shapes are dropped`() {
        assertEquals(false, Annotation.Redaction(ImageRect(0f, 0f, 3f, 50f)).isMeaningful())
        assertEquals(false, Annotation.Text(ImagePoint(0f, 0f), "  ").isMeaningful())
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnotationRendererTest {
    private fun source() = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    @Test
    fun `redaction is fully opaque in the encoded file and drawn over other annotations`() {
        val annotations = listOf(
            Annotation.Redaction(ImageRect(20f, 20f, 120f, 120f)),
            // Drawn after the redaction in the list, but must still end up underneath it.
            Annotation.Text(ImagePoint(30f, 80f), "SECRET"),
        )
        val png = ByteArrayOutputStream().also { AnnotationRenderer.writePng(AnnotationRenderer.render(source(), annotations), it) }.toByteArray()
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
        for (x in 20 until 120 step 7) for (y in 20 until 120 step 7) assertEquals(Color.BLACK, decoded.getPixel(x, y))
        assertEquals(Color.WHITE, decoded.getPixel(150, 150))
    }

    @Test
    fun `the source capture is not modified`() {
        val src = source()
        AnnotationRenderer.render(src, listOf(Annotation.Redaction(ImageRect(0f, 0f, 200f, 200f))))
        assertEquals(Color.WHITE, src.getPixel(100, 100))
    }

    @Test
    fun `circles and arrows are drawn`() {
        val out = AnnotationRenderer.render(source(), listOf(Annotation.Arrow(ImagePoint(10f, 100f), ImagePoint(190f, 100f))))
        assertNotEquals(Color.WHITE, out.getPixel(100, 100))
    }
}
