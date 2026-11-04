package com.example.typzyreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ViewGeometryTest {
    @Test
    fun scalesToPhysicalSize() {
        // A Pixel 10a: at 100%, a CSS px is 2.625 of its 428.625 pixels per inch, or 0.441pt.
        assertEquals(0.441f, ViewGeometry.ptPerCssPx(2.625f, 428.625f, 1f), 0.001f)
        // At half the size, the same CSS px shows twice the points.
        assertEquals(0.882f, ViewGeometry.ptPerCssPx(2.625f, 428.625f, 0.5f), 0.001f)
    }

    @Test
    fun laysOutWholeDpInPoints() {
        // 400 px at 1x and 96 dpi: 400 dp, the CSS reference of 0.75pt each.
        assertEquals(ViewLayout(300f, 0.75f), ViewGeometry.layout(400, 1f, 96f, 1f))
        // Larger text leaves documents fewer points to lay out in.
        assertEquals(ViewLayout(150f, 0.375f), ViewGeometry.layout(400, 1f, 96f, 2f))
    }

    @Test
    fun fillsTheViewAtAnyScale() {
        // Compiled width over points per CSS px is the view's 411 dp, whatever the scale.
        for (scale in listOf(0.6f, 0.8f, 1f, 1.2f)) {
            val layout = ViewGeometry.layout(1080, 2.625f, 428.625f, scale)
            assertEquals(411f, layout.widthPt / layout.ptPerCssPx, 0.001f)
        }
    }

    @Test
    fun ignoresSubDpChanges() {
        // 1080 px at 2.625x is 411.43 dp and 1079 px is 411.05 dp, both 411 dp.
        assertEquals(ViewGeometry.layout(1080, 2.625f, 428.625f, 0.8f), ViewGeometry.layout(1079, 2.625f, 428.625f, 0.8f))
        assertNotEquals(ViewGeometry.layout(1080, 2.625f, 428.625f, 0.8f), ViewGeometry.layout(1080, 2.625f, 428.625f, 0.9f))
    }

    @Test
    fun shrinksCoversToAboutTheMaximum() {
        assertEquals(1, coverSampleSize(100, 150, 300, 450))
        assertEquals(1, coverSampleSize(599, 899, 300, 450))
        assertEquals(2, coverSampleSize(600, 900, 300, 450))
        assertEquals(8, coverSampleSize(2400, 3600, 300, 450))
        // Stays large enough in both directions, however unusual the shape.
        assertEquals(1, coverSampleSize(5000, 100, 300, 450))
    }

    @Test
    fun distrustsImplausibleDpi() {
        assertEquals(428.625f, ViewGeometry.physicalDpi(428.625f, 420), 0f)
        assertEquals(420f, ViewGeometry.physicalDpi(0f, 420), 0f)
        assertEquals(420f, ViewGeometry.physicalDpi(160f, 420), 0f)
        assertEquals(420f, ViewGeometry.physicalDpi(1000f, 420), 0f)
    }
}
