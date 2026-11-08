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
    fun showsBooksAPageAtATimeOnNarrowViews() {
        // A phone held upright: 411 x 820 dp, narrower than 0.8 of its height.
        val layout = ViewGeometry.bookLayout(1080, 2152, 2.625f, 428.625f, 424.6f, 1f)
        assertEquals(PageMode.SINGLE, layout.mode)
        assertEquals(411 * layout.ptPerCssPx, layout.widthPt, 0.01f)
        assertEquals(820 * layout.ptPerCssPx, layout.heightPt!!, 0.01f)
    }

    @Test
    fun scrollsBooksOnWideButShortViews() {
        // The same phone on its side: 6.3 cm tall, so pages as wide as the view and √2 times as tall.
        val layout = ViewGeometry.bookLayout(2152, 1050, 2.625f, 428.625f, 424.6f, 1f)
        assertEquals(PageMode.SCROLLED, layout.mode)
        assertEquals(820 * layout.ptPerCssPx, layout.widthPt, 0.01f)
        assertEquals(layout.widthPt * 1.414f, layout.heightPt!!, 0.01f)
    }

    @Test
    fun showsSpreadsOnWideAndTallViews() {
        // A tablet on its side: 1280 x 752 dp at 2x and 274 dpi, 13.9 cm tall.
        val layout = ViewGeometry.bookLayout(2560, 1504, 2f, 274f, 274f, 1f)
        assertEquals(PageMode.SPREAD, layout.mode)
        assertEquals(640 * layout.ptPerCssPx, layout.widthPt, 0.01f)
        assertEquals(752 * layout.ptPerCssPx, layout.heightPt!!, 0.01f)
    }

    @Test
    fun switchesLayoutsAtTheThresholds() {
        // Exactly 0.8: no longer one page at a time.
        assertNotEquals(PageMode.SINGLE, ViewGeometry.bookLayout(800, 1000, 1f, 160f, 160f, 1f).mode)
        assertEquals(PageMode.SINGLE, ViewGeometry.bookLayout(799, 1000, 1f, 160f, 160f, 1f).mode)
        // 9 cm at 254 dpi is 900 px: still scrolled at 900, spreads above.
        assertEquals(PageMode.SCROLLED, ViewGeometry.bookLayout(2000, 900, 1f, 254f, 254f, 1f).mode)
        assertEquals(PageMode.SPREAD, ViewGeometry.bookLayout(2000, 901, 1f, 254f, 254f, 1f).mode)
    }

    @Test
    fun laysOutPagelessDocumentsWithoutAHeight() {
        val layout = ViewGeometry.layout(400, 1f, 96f, 1f)
        assertEquals(PageMode.PAGELESS, layout.mode)
        assertEquals(null, layout.heightPt)
    }

    @Test
    fun keepsTheTextFieldWithin15cm() {
        // Narrow pages keep the usual margin.
        assertEquals(12f, ViewGeometry.marginPt(300f), 0f)
        assertEquals(12f, ViewGeometry.marginPt(ViewGeometry.MAX_TEXT_WIDTH_PT + 24f), 0.001f)
        // Wider ones get margins that leave exactly 15 cm (425.2pt) of text.
        val wide = 800f
        val margin = ViewGeometry.marginPt(wide)
        assertEquals(425.197f, wide - 2 * margin, 0.01f)
    }

    @Test
    fun keepsBookLinesWithin12AndAHalfCm() {
        // A phone on its side at 60%: a 690pt scrolled page, so 354.3pt (12.5 cm) of text.
        val scrolled = ViewGeometry.bookLayout(2152, 1050, 2.625f, 428.625f, 424.6f, 0.6f)
        assertEquals(PageMode.SCROLLED, scrolled.mode)
        assertEquals(354.33f, scrolled.widthPt - 2 * scrolled.marginPt, 0.01f)
        // Upright, the page is narrow enough for the usual margin.
        val single = ViewGeometry.bookLayout(1080, 2152, 2.625f, 428.625f, 424.6f, 0.7f)
        assertEquals(12f, single.marginPt, 0f)
    }

    @Test
    fun suggestsMarginsForTheLaidOutPageWidth() {
        // A tablet on its side at 100%: each page of a spread gets its own margin.
        val spread = ViewGeometry.bookLayout(2560, 1504, 2f, 274f, 274f, 1f)
        assertEquals(ViewGeometry.marginPt(spread.widthPt, ViewGeometry.BOOK_MAX_TEXT_WIDTH_PT), spread.marginPt, 0f)
        // A wide pageless view (1280 dp at 0.6 scale: 1600pt) gets wide margins.
        val pageless = ViewGeometry.layout(2560, 2f, 274f, 0.6f)
        assertEquals(425.197f, pageless.widthPt - 2 * pageless.marginPt, 0.01f)
    }

    @Test
    fun distrustsImplausibleDpi() {
        assertEquals(428.625f, ViewGeometry.physicalDpi(428.625f, 420), 0f)
        assertEquals(420f, ViewGeometry.physicalDpi(0f, 420), 0f)
        assertEquals(420f, ViewGeometry.physicalDpi(160f, 420), 0f)
        assertEquals(420f, ViewGeometry.physicalDpi(1000f, 420), 0f)
    }
}
