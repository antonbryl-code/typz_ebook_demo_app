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
    fun startsSpreadsWithTheFirstPageAloneOnTheRight() {
        assertEquals(listOf(listOf(null, 0), listOf(1, 2), listOf(3, null)), DocumentHtml.screens(4, PageMode.SPREAD))
        assertEquals(listOf(listOf(null, 0), listOf(1, 2)), DocumentHtml.screens(3, PageMode.SPREAD))
        assertEquals(listOf(listOf(null, 0)), DocumentHtml.screens(1, PageMode.SPREAD))
        assertEquals(emptyList<List<Int?>>(), DocumentHtml.screens(0, PageMode.SPREAD))
        assertEquals(listOf(listOf(0), listOf(1)), DocumentHtml.screens(2, PageMode.SINGLE))
    }

    @Test
    fun findsTheScreenOfEachPage() {
        assertEquals(listOf(0, 1, 1, 2, 2), (0..4).map { DocumentHtml.screenOfPage(it, PageMode.SPREAD) })
        assertEquals(listOf(0, 1, 2), (0..2).map { DocumentHtml.screenOfPage(it, PageMode.SINGLE) })
    }

    @Test
    fun takesTheLastSectionStartedOnOrBeforeTheScreen() {
        val sections = listOf(TypstSection("A", 1, 0f), TypstSection("B", 2, 300f), TypstSection("C", 5, 0f))
        assertEquals(null, DocumentHtml.currentSectionOnScreen(sections, 0, PageMode.SINGLE))
        assertEquals(0, DocumentHtml.currentSectionOnScreen(sections, 1, PageMode.SINGLE))
        assertEquals(1, DocumentHtml.currentSectionOnScreen(sections, 4, PageMode.SINGLE))
        // The second spread shows pages 1 and 2.
        assertEquals(1, DocumentHtml.currentSectionOnScreen(sections, 1, PageMode.SPREAD))
        assertEquals(2, DocumentHtml.currentSectionOnScreen(sections, 3, PageMode.SPREAD))
    }

    @Test
    fun laysSwipedPagesSideBySideAScreenAtATime() {
        val document = TypstDocument(listOf(page(300f, 600f, "a"), page(300f, 600f, "b"), page(300f, 600f, "c")), "")
        val html = DocumentHtml.build(document, dark = false, mode = PageMode.SPREAD)
        assertTrue(html, html.contains("html { overflow-x: auto; overflow-y: hidden; scroll-snap-type: x mandatory; }"))
        assertTrue(html, html.contains("scroll-snap-align: start; scroll-snap-stop: always;"))
        assertTrue(html, html.contains("minimum-scale=1"))
        assertEquals(2, Regex("<div class=\"spread\">").findAll(html).count())
        // The first spread has a blank slot on the left, then page a.
        assertTrue(html, html.contains("<div class=\"spread\"><div class=\"slot\"></div><div class=\"slot\"><div class=\"page\""))
        assertTrue(html.indexOf("id=\"a\"") < html.indexOf("id=\"b\"") && html.indexOf("id=\"b\"") < html.indexOf("id=\"c\""))
        // Pages as large as fits their slot.
        assertTrue(html, html.contains("width: min(100%, calc(100vh * 300.000 / 600.000)); aspect-ratio: 300.000 / 600.000"))
    }

    @Test
    fun scrollsBookPagesWithGapsBetweenThem() {
        val document = TypstDocument(listOf(page(300f, 424f, "a"), page(300f, 424f, "b")), "")
        val html = DocumentHtml.build(document, dark = false, viewWidthPt = 300f, mode = PageMode.SCROLLED)
        assertTrue(html, html.contains(".row { margin-bottom: 12px; } .row:last-child { margin-bottom: 0; }"))
        assertTrue(html, html.contains("width: min(100%, 400.000px)"))
        // Sections below the first page are lower by the gap above them.
        val top = DocumentHtml.sectionTopPx(document, TypstSection("S", 1, 30f), 400f, 300f, 0.75f, PageMode.SCROLLED)
        assertEquals(424f * 4 / 3 + 12f + 40f, top, 0.01f)
    }

    @Test
    fun labelsPositions() {
        assertEquals("Page 3 of 12", DocumentHtml.pagesLabel(listOf(2), 12))
        assertEquals("Pages 2–3 of 12", DocumentHtml.pagesLabel(listOf(1, 2), 12))
        assertEquals("", DocumentHtml.pagesLabel(emptyList(), 12))
        assertEquals("0%", DocumentHtml.percentLabel(0f))
        assertEquals("42%", DocumentHtml.percentLabel(0.42f))
        assertEquals("100%", DocumentHtml.percentLabel(1f))
    }

    @Test
    fun findsThePageAtTheTopOfAScrolledBook() {
        // Pages 400 CSS px tall at 0.75pt per px (300pt), with 12 px gaps.
        val document = TypstDocument(List(3) { page(300f, 300f, "p$it") }, "")
        val layout = ViewLayout(300f, 0.75f, 300f, PageMode.SCROLLED)
        assertEquals(0, DocumentHtml.pageAt(document, layout, 400f, 0f))
        assertEquals(0, DocumentHtml.pageAt(document, layout, 400f, 410f))
        assertEquals(1, DocumentHtml.pageAt(document, layout, 400f, 412f))
        assertEquals(2, DocumentHtml.pageAt(document, layout, 400f, 2000f))
    }

    @Test
    fun leavesRoomForTheBottomBar() {
        val html = DocumentHtml.build(TypstDocument(emptyList(), ""), dark = false, topPaddingPx = 64, bottomPaddingPx = 64)
        assertTrue(html, html.contains("body { padding-top: 64px; padding-bottom: 64px; }"))
        // No thin scrollbars, on the page or on sideways-scrolling strips.
        assertTrue(html, html.contains("scrollbar-width: none"))
    }

    @Test
    fun keepsTheReadingPositionAcrossLayouts() {
        // Portrait: sections at 100 and 600 CSS px of a 1000 px document; the reader is at 350,
        // halfway through the first section.
        val portrait = SectionBounds(listOf(100f, 600f), 1000f)
        val position = DocumentHtml.readingPosition(portrait, 350f)
        assertEquals(ReadingPosition(0, 0.5f), position)
        // Landscape, swiped: the same sections start on pages 1 and 3.5 of 5; halfway is page 2.25.
        val landscape = SectionBounds(listOf(1f, 3.5f), 5f)
        assertEquals(2.25f, DocumentHtml.locate(landscape, position), 0.001f)
        // And back again.
        assertEquals(350f, DocumentHtml.locate(portrait, DocumentHtml.readingPosition(landscape, 2.25f)), 0.01f)
    }

    @Test
    fun keepsPositionsBeforeTheFirstSectionAndInTheLast() {
        val bounds = SectionBounds(listOf(100f, 600f), 1000f)
        assertEquals(ReadingPosition(-1, 0.5f), DocumentHtml.readingPosition(bounds, 50f))
        assertEquals(ReadingPosition(1, 0.5f), DocumentHtml.readingPosition(bounds, 800f))
        assertEquals(800f, DocumentHtml.locate(bounds, ReadingPosition(1, 0.5f)), 0.001f)
        // A document without sections is one span from start to end.
        val plain = SectionBounds(emptyList(), 400f)
        assertEquals(200f, DocumentHtml.locate(plain, DocumentHtml.readingPosition(plain, 200f)), 0.001f)
    }

    @Test
    fun measuresSwipedDocumentsInPages() {
        val document = TypstDocument(
            listOf(page(300f, 600f, "a"), page(300f, 600f, "b"), page(300f, 600f, "c")), "", null,
            listOf(TypstSection("One", 0, 0f), TypstSection("Two", 1, 300f)),
        )
        val bounds = DocumentHtml.sectionBounds(document, ViewLayout(300f, 0.75f, 600f, PageMode.SINGLE), 400f)
        assertEquals(listOf(0f, 1.5f), bounds.starts)
        assertEquals(3f, bounds.end, 0f)
        // The second spread starts at page 1.
        assertEquals(1, DocumentHtml.firstPageOnScreen(1, PageMode.SPREAD))
        assertEquals(0, DocumentHtml.firstPageOnScreen(0, PageMode.SPREAD))
    }

    @Test
    fun putsEachPageInARowThatScrollsSideways() {
        val html = DocumentHtml.build(TypstDocument(listOf(page(300f, 40f, "a"), page(600f, 60f, "b")), ""), dark = false)
        assertEquals(2, Regex("<div class=\"row\"><div class=\"scroller\"><div class=\"page\"").findAll(html).count())
        assertTrue(html, html.contains(".scroller { overflow-x: auto; overflow-y: hidden; scroll-timeline: --x inline; scrollbar-width: none; }"))
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
        assertTrue(DocumentHtml.build(empty, dark = false).contains("body { padding-top: 0px; padding-bottom: 0px; }"))
        assertTrue(DocumentHtml.build(empty, dark = false, topPaddingPx = 64).contains("body { padding-top: 64px; padding-bottom: 0px; }"))
    }

    @Test
    fun usesDeviceWidthViewport() {
        val html = DocumentHtml.build(TypstDocument(emptyList(), ""), dark = false)
        assertTrue(html.contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"))
    }
}
