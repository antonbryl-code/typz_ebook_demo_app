package com.example.typzyreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentHtmlTest {
    private fun page(widthPt: Float, heightPt: Float, id: String) =
        TypstPage("<svg id=\"$id\" width=\"${widthPt}pt\"></svg>", "<div class=\"l\">$id</div>", widthPt, heightPt)

    @Test
    fun stacksPagesInOrder() {
        val html = DocumentHtml.build(TypstDocument(listOf(page(300f, 40f, "a"), page(300f, 60f, "b")), ""), dark = false)
        assertEquals(2, Regex("<div class=\"page\"").findAll(html).count())
        assertTrue(html.indexOf("id=\"a\"") < html.indexOf("id=\"b\""))
    }

    @Test
    fun sizesPagesInCssPixels() {
        // 300pt = 400 CSS px; scaled down if the view is narrower, never up.
        val html = DocumentHtml.build(TypstDocument(listOf(page(300f, 41.5f, "a")), ""), dark = false)
        assertTrue(html, html.contains("width: min(100%, 400.000px); aspect-ratio: 300.000 / 41.500"))
    }

    @Test
    fun sizesPagesForTheTextSize() {
        // At 0.5pt per CSS px, a 600pt page next to a reflowed 300pt one is 1200 CSS px wide.
        val document = TypstDocument(listOf(page(300f, 40f, "a"), page(600f, 60f, "b")), "")
        val html = DocumentHtml.build(document, dark = false, viewWidthPt = 300f, ptPerCssPx = 0.5f)
        assertTrue(html, html.contains("width: 1200.000px; aspect-ratio: 600.000 / 60.000"))
    }

    @Test
    fun findsSectionsBelowThePagesAboveThem() {
        val document = TypstDocument(listOf(page(300f, 40f, "a"), page(300f, 60f, "b")), "")
        // In a 400 CSS px view at 0.75pt per CSS px: 40pt and 30pt make 70pt, or 93.33 CSS px.
        val top = DocumentHtml.sectionTopPx(document, TypstSection("S", 1, 30f), 400f, 300f, 0.75f)
        assertEquals(93.33f, top, 0.01f)
    }

    @Test
    fun findsSectionsOnPagesThatFillTheView() {
        // A reflowed page fills the view even if that's a fraction wider than its compiled width.
        val document = TypstDocument(listOf(page(300f, 30f, "a")), "")
        val top = DocumentHtml.sectionTopPx(document, TypstSection("S", 0, 30f), 410f, 300f, 0.75f)
        assertEquals(41f, top, 0.01f)
    }

    @Test
    fun findsSectionsOnWidePagesOfReflowedDocuments() {
        // The 600pt page keeps its size, at the same 4/3 CSS px per point as the 300pt one above.
        val document = TypstDocument(listOf(page(300f, 30f, "a"), page(600f, 60f, "b")), "")
        val top = DocumentHtml.sectionTopPx(document, TypstSection("S", 1, 30f), 400f, 300f, 0.75f)
        assertEquals(80f, top, 0.01f)
    }

    @Test
    fun findsSectionsOnPagesScaledDownToFit() {
        // Not reflowed: a 600pt page in a 400 CSS px view is at 2/3 CSS px per point.
        val document = TypstDocument(listOf(page(600f, 60f, "a"), page(600f, 60f, "b")), "")
        val top = DocumentHtml.sectionTopPx(document, TypstSection("S", 1, 30f), 400f, 300f, 0.75f)
        assertEquals(60f, top, 0.01f)
    }

    // Sections starting 100, 600 and 1100 CSS px down, in an 800 px view with a 64 px toolbar.
    private val tops = listOf(100f, 600f, 1100f)
    private fun current(scrollTop: Float, atEnd: Boolean = false) =
        DocumentHtml.currentSection(tops, scrollTop, 800f, 64f, atEnd)

    @Test
    fun takesTheLastSectionThatReachedTheToolbar() {
        assertEquals(0, current(100f))
        assertEquals(0, current(400f))
        assertEquals(1, current(592f)) // where jumping to it puts it: 8 px below the toolbar
        assertEquals(1, current(1000f))
        assertEquals(2, current(1100f))
    }

    @Test
    fun takesTheFirstSectionWhileItIsStillBelow() {
        // At the start, with a title above the first section.
        assertEquals(0, current(0f))
        assertEquals(null, DocumentHtml.currentSection(listOf(2000f), 0f, 800f, 64f, atEnd = false))
        assertEquals(null, DocumentHtml.currentSection(emptyList(), 0f, 800f, 64f, atEnd = false))
    }

    @Test
    fun takesTheLastVisibleSectionAtTheEnd() {
        // A short last section that can't scroll up to the toolbar.
        assertEquals(2, current(700f, atEnd = true))
        // At the end, but the last section starts below the screen: the usual rule.
        assertEquals(1, DocumentHtml.currentSection(listOf(100f, 600f, 5000f), 700f, 800f, 64f, atEnd = true))
    }

    @Test
    fun putsEachPageInARowThatScrollsSideways() {
        val html = DocumentHtml.build(TypstDocument(listOf(page(300f, 40f, "a"), page(600f, 60f, "b")), ""), dark = false)
        assertEquals(2, Regex("<div class=\"row\"><div class=\"scroller\"><div class=\"page\"").findAll(html).count())
        assertTrue(html, html.contains(".scroller { overflow-x: auto; overflow-y: hidden; scroll-timeline: --x inline; }"))
    }

    @Test
    fun leavesNoGapsBetweenPages() {
        val html = DocumentHtml.build(TypstDocument(listOf(page(300f, 40f, "a"), page(300f, 60f, "b")), ""), dark = false)
        assertFalse(html, html.contains("margin-bottom"))
        assertTrue(html, html.contains("margin: 0 auto;"))
    }

    @Test
    fun serratesEdgesWhereThePageScrollsOn() {
        // Hidden unless the row's scroll timeline says there's more to the left or right.
        val html = DocumentHtml.build(TypstDocument(listOf(page(600f, 60f, "a")), ""), dark = true, viewWidthPt = 300f)
        assertTrue(html, html.contains("width: 5px; opacity: 0; pointer-events: none;"))
        assertTrue(html, html.contains("left: 0; animation-name: more-left;"))
        assertTrue(html, html.contains("right: 0; animation-name: more-right;"))
        assertTrue(html, html.contains("@keyframes more-left { 0% { opacity: 0; } 1%, 100% { opacity: 1; } }"))
        assertTrue(html, html.contains("@keyframes more-right { 0%, 99% { opacity: 1; } 100% { opacity: 0; } }"))
        // The teeth stand out from the white page in both modes.
        assertEquals(2, Regex("conic-gradient\\([^,]*, #202124 0 90deg").findAll(html).count())
        val light = DocumentHtml.build(TypstDocument(listOf(page(600f, 60f, "a")), ""), dark = false, viewWidthPt = 300f)
        assertEquals(2, Regex("conic-gradient\\([^,]*, #9e9e9e 0 90deg").findAll(light).count())
    }

    @Test
    fun keepsWiderPagesOfReflowedDocumentsAtTheirSize() {
        // The 300pt page matches the view, so the document reflowed and its 600pt table page
        // (800 CSS px) is meant to be that wide.
        val document = TypstDocument(listOf(page(300f, 40f, "a"), page(600f, 60f, "b")), "")
        val html = DocumentHtml.build(document, dark = false, viewWidthPt = 300f)
        // Reflowed pages fill the view exactly, whatever its fractional width in CSS px.
        assertTrue(html, html.contains("width: 100%; aspect-ratio: 300.000 / 40.000"))
        assertTrue(html, html.contains("width: 800.000px; aspect-ratio: 600.000 / 60.000"))
        assertFalse(html, html.contains("min(100%"))
    }

    @Test
    fun scalesWidePagesOfDocumentsRenderedAsAuthored() {
        // No page matches the 300pt view: an A4 document laid out for print.
        val html = DocumentHtml.build(TypstDocument(listOf(page(595.28f, 841.89f, "a")), ""), dark = false, viewWidthPt = 300f)
        assertTrue(html, html.contains("width: min(100%, 793.707px); aspect-ratio: 595.280 / 841.890"))
    }

    @Test
    fun laysTextLayerOverEachPage() {
        val html = DocumentHtml.build(TypstDocument(listOf(page(300f, 41.5f, "a")), ""), dark = false)
        // After the SVG, inside the page, with the page's point unit for its lengths.
        assertTrue(html, html.contains("--u: calc(100cqw / 300.000)\"><svg id=\"a\""))
        assertTrue(html, html.contains("</svg><div class=\"t\"><div class=\"l\">a</div></div></div>"))
    }

    @Test
    fun followsDarkMode() {
        val empty = TypstDocument(emptyList(), "")
        val light = DocumentHtml.build(empty, dark = false)
        val dark = DocumentHtml.build(empty, dark = true)
        assertTrue(light, light.contains("background: #e8e8e8") && light.contains("content=\"light\""))
        assertTrue(dark, dark.contains("background: #202124") && dark.contains("content=\"dark\""))
    }

    @Test
    fun leavesRoomForToolbar() {
        val empty = TypstDocument(emptyList(), "")
        assertTrue(DocumentHtml.build(empty, dark = false).contains("body { padding-top: 0px; }"))
        assertTrue(DocumentHtml.build(empty, dark = false, topPaddingPx = 64).contains("body { padding-top: 64px; }"))
    }

    @Test
    fun usesDeviceWidthViewport() {
        val html = DocumentHtml.build(TypstDocument(emptyList(), ""), dark = false)
        assertTrue(html.contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"))
    }
}
