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
        // The teeth are cut in the background's color.
        assertEquals(2, Regex("conic-gradient\\([^,]*, #202124 0 90deg").findAll(html).count())
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
