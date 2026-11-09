package com.example.typzyreader

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A reader's place in a document that carries over to any layout of it: the index of the section
 * being read (-1 before the first one) and how far through it, from 0 to 1.
 */
data class ReadingPosition(val section: Int, val fraction: Float)

/**
 * Where the view is in a document, for the slider in the bottom bar: [value] from 0 to [max]
 * (screens of swiped pages, or the fraction scrolled), and a [label] like "Page 3 of 12".
 */
data class DocumentProgress(val value: Float, val max: Float, val label: String)

/**
 * Where a document's sections start in one of its layouts ([starts], in order) and where it
 * [end]s; see [DocumentHtml.sectionBounds].
 */
class SectionBounds(val starts: List<Float>, val end: Float) {
    /** Where section [index] starts and the next one (or the end) begins; -1 is before the first. */
    fun span(index: Int): Pair<Float, Float> =
        (if (index >= 0) starts[index] else 0f) to (starts.getOrNull(index + 1) ?: end)
}

/**
 * Builds the page shown in the document WebView: the page SVGs inlined, in one of the
 * [PageMode]s.
 *
 * The viewport is `device-width`, so 1 CSS px = 1 dp, showing however many points the text size
 * calls for (see [ViewGeometry]), and a page reflowed to the view's width fills it.
 *
 * Pageless documents' pages are stacked vertically without gaps. Pages wider than the view are
 * scaled down to fit in documents rendered as authored; in documents that reflowed, they are
 * pages the document deliberately made wider (e.g. for a big table), so they keep their size and
 * scroll sideways, with a serrated edge on each side there's more of the page to scroll to.
 * Narrower pages keep their size and are centered.
 *
 * Books in [PageMode.SCROLLED] are stacked the same way, with gaps between the pages. In the
 * swiped modes, pages sit side by side, one or two to a screen, and the page itself scrolls
 * sideways a whole screen at a time (CSS scroll snapping, so no JavaScript is needed).
 */
object DocumentHtml {
    /** Serration teeth on the edges of sideways-scrolling pages: how far they cut in, and their pitch. */
    private const val TOOTH_DEPTH_PX = 5
    private const val TOOTH_PITCH_PX = 10

    /**
     * Pages with SVGs up to this long (in chars) are shown as images; larger ones inline, as
     * WebViews may refuse very long `data:` URLs. A page rarely comes near it.
     */
    private const val MAX_IMAGE_SVG_CHARS = 1_500_000

    /** Space between the pages of a book in [PageMode.SCROLLED], in CSS px. */
    const val BOOK_PAGE_GAP_PX = 12

    /** How close to the view's width a page must be to count as reflowed, in points. */
    private const val WIDTH_TOLERANCE_PT = 0.5f

    /**
     * [dark] picks the background around the pages (the pages themselves stay as the document
     * paints them). It's passed in rather than left to `prefers-color-scheme`, which the WebView
     * derives from the app's XML theme instead of the system's dark mode. [topPaddingPx] keeps
     * the top of the document clear of a toolbar laid over the view. [viewWidthPt] is the width
     * the document was compiled for; null treats it as not reflowed. [ptPerCssPx] sets the size
     * pages appear at; the default is CSS's own 96 px per 72pt. In the swiped [mode]s, pages
     * are sized to fit their place on screen instead.
     */
    fun build(
        document: TypstDocument,
        dark: Boolean,
        topPaddingPx: Int = 0,
        viewWidthPt: Float? = null,
        ptPerCssPx: Float = 0.75f,
        mode: PageMode = PageMode.PAGELESS,
        bottomPaddingPx: Int = 0,
    ): String = if (mode.swiped) {
        buildSwiped(document, dark, mode)
    } else {
        buildScrolled(document, dark, topPaddingPx, bottomPaddingPx, viewWidthPt, ptPerCssPx, mode)
    }

    private fun buildScrolled(
        document: TypstDocument,
        dark: Boolean,
        topPaddingPx: Int,
        bottomPaddingPx: Int,
        viewWidthPt: Float?,
        ptPerCssPx: Float,
        mode: PageMode,
    ): String = buildString {
        val reflowed = mode == PageMode.PAGELESS && reflowed(document, viewWidthPt)
        val background = background(dark)
        // Against a white page, the light background would barely show.
        val toothColor = if (dark) background else "#9e9e9e"
        val gaps = if (mode == PageMode.SCROLLED) {
            ".row { margin-bottom: ${BOOK_PAGE_GAP_PX}px; } .row:last-child { margin-bottom: 0; }"
        } else {
            ""
        }
        appendHead(dark, mode, """
              body { padding-top: ${topPaddingPx}px; padding-bottom: ${bottomPaddingPx}px; }
              /* One per page; its scroller scrolls sideways when the page is wider than the view. */
              .row { position: relative; timeline-scope: --x; }
              $gaps
              .scroller { overflow-x: auto; overflow-y: hidden; scroll-timeline: --x inline; scrollbar-width: none; }
              /* Serrated edges, cut in a color that stands out from the page, where it continues
                 beyond the view. They're shown by animations driven by the scroller's position (no
                 JavaScript needed); a page that doesn't scroll has no active timeline, so they
                 stay hidden, as they do on WebViews without scroll-driven animations. */
              .row::before, .row::after {
                content: ""; position: absolute; top: 0; bottom: 0; z-index: 1;
                width: ${TOOTH_DEPTH_PX}px; opacity: 0; pointer-events: none;
                animation-duration: auto; animation-timing-function: linear;
                animation-fill-mode: both; animation-timeline: --x;
              }
              .row::before {
                left: 0; animation-name: more-left;
                background: conic-gradient(from 225deg at 100% 50%, $toothColor 0 90deg, transparent 0)
                  0 0 / ${TOOTH_DEPTH_PX}px ${TOOTH_PITCH_PX}px repeat-y;
              }
              .row::after {
                right: 0; animation-name: more-right;
                background: conic-gradient(from 45deg at 0 50%, $toothColor 0 90deg, transparent 0)
                  0 0 / ${TOOTH_DEPTH_PX}px ${TOOTH_PITCH_PX}px repeat-y;
              }
              @keyframes more-left { 0% { opacity: 0; } 1%, 100% { opacity: 1; } }
              @keyframes more-right { 0%, 99% { opacity: 1; } 100% { opacity: 0; } }
              .page { margin: 0 auto; }
        """, background)
        for (page in document.pages) {
            // Typst sizes pages in pt; the aspect ratio keeps scaled-down pages proportional.
            val widthPx = css(page.widthPt / ptPerCssPx)
            val width = when {
                // Fill the view exactly: its width was rounded to whole dp for compiling, so
                // the page's own width can be a fraction of a pixel short of it.
                reflowed && matches(page, viewWidthPt) -> "100%"
                reflowed -> "${widthPx}px"
                else -> "min(100%, ${widthPx}px)"
            }
            append("<div class=\"row\"><div class=\"scroller\">")
            appendPage(page, width)
            append("</div></div>\n")
        }
        append("</body></html>")
    }

    private fun buildSwiped(document: TypstDocument, dark: Boolean, mode: PageMode): String = buildString {
        appendHead(dark, mode, """
              /* The page itself scrolls sideways, so the WebView reports the scroll position. */
              html { overflow-x: auto; overflow-y: hidden; scroll-snap-type: x mandatory; }
              body { display: flex; width: max-content; height: 100vh; }
              /* One screen; a page turn always moves exactly one. */
              .spread {
                flex: 0 0 100vw; height: 100vh; display: flex;
                scroll-snap-align: start; scroll-snap-stop: always;
              }
              .slot { flex: 1 1 0; min-width: 0; display: flex; align-items: center; justify-content: center; }
              /* The two pages of a spread meet in the middle, like a book's. */
              .spread > .slot:first-child:not(:last-child) { justify-content: flex-end; }
              .spread > .slot:last-child:not(:first-child) { justify-content: flex-start; }
              .page { flex: none; }
        """, background(dark))
        for (screen in screens(document.pages.size, mode)) {
            append("<div class=\"spread\">")
            for (index in screen) {
                append("<div class=\"slot\">")
                if (index != null) {
                    val page = document.pages[index]
                    // As large as fits the slot, which documents are compiled to fill exactly.
                    appendPage(page, "min(100%, calc(100vh * ${css(page.widthPt)} / ${css(page.heightPt)}))")
                }
                append("</div>")
            }
            append("</div>\n")
        }
        append("</body></html>")
    }

    private fun StringBuilder.appendHead(dark: Boolean, mode: PageMode, css: String, background: String) {
        val scheme = if (dark) "dark" else "light"
        // Books are laid out for the view; zooming out would only show the next pages, small.
        val viewport = if (mode == PageMode.PAGELESS) "width=device-width, initial-scale=1" else "width=device-width, initial-scale=1, minimum-scale=1"
        append(
            """
            <!DOCTYPE html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="$viewport">
            <meta name="color-scheme" content="$scheme">
            <style>
              html, body { margin: 0; padding: 0; background: $background; scrollbar-width: none; }
            """.trimIndent()
        )
        append("\n")
        append(css.trimIndent())
        append("\n")
        append(
            """
              .page { background: white; position: relative; container-type: inline-size; }
              .page > svg, .page > img.svg { display: block; width: 100%; height: 100%; }
              /* The invisible, selectable text layer over each page (see text_layer.rs). Its
                 lengths are in --u, one Typst point at the page's current size. */
              .t {
                position: absolute; inset: 0; overflow: hidden;
                color: transparent; font-family: monospace; line-height: 1; white-space: pre;
              }
              .t .l { position: absolute; white-space: pre; }
              .t .r { display: inline-block; vertical-align: top; }
              /* Words: letter-spaced to fill their box exactly, so nothing is clipped (Chromium
                 hides selection handles that fall into clipped content). */
              .t .l > span, .t .r > span { display: inline-block; vertical-align: top; height: 1em; }
              .t .k { position: absolute; transform-origin: 0 0; }
              /* Highlight selections, but keep the stand-in font hidden while selected. */
              .t ::selection { color: transparent; background: rgba(66, 133, 244, 0.35); }
            </style>
            </head><body>
            """.trimIndent()
        )
        append("\n")
    }

    /** A page, [width] wide (CSS), its height following from its aspect ratio. */
    private fun StringBuilder.appendPage(page: TypstPage, width: String) {
        append("<div class=\"page\" style=\"width: ")
        append(width)
        append("; aspect-ratio: ")
        append(css(page.widthPt))
        append(" / ")
        append(css(page.heightPt))
        // The text layer's unit: one Typst point at the page's actual width.
        append("; --u: calc(100cqw / ")
        append(css(page.widthPt))
        append(")\">")
        if (page.svg.length <= MAX_IMAGE_SVG_CHARS) {
            // As an image, a page's SVG is only read when it's about to be seen, so the WebView
            // lays out a long document at once. Its links are in the text layer anyway.
            append("<img class=\"svg\" alt=\"\" loading=\"lazy\" decoding=\"async\" src=\"data:image/svg+xml,")
            appendDataUrlEncoded(page.svg)
            append("\">")
        } else {
            append(page.svg)
        }
        append("<div class=\"t\">")
        append(page.textLayer)
        append("</div></div>")
    }

    /**
     * Appends [text] for a `data:` URL in a double-quoted HTML attribute: escapes what would end
     * the attribute, start a character reference or a fragment, or be dropped from URLs (line
     * breaks, which can separate SVG attributes), and the escape character itself.
     */
    internal fun StringBuilder.appendDataUrlEncoded(text: String) {
        for (c in text) {
            when (c) {
                '%' -> append("%25")
                '#' -> append("%23")
                '"' -> append("%22")
                '&' -> append("%26")
                '<' -> append("%3C")
                '>' -> append("%3E")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                '\t' -> append("%09")
                else -> append(c)
            }
        }
    }

    private fun background(dark: Boolean) = if (dark) "#202124" else "#e8e8e8"

    /**
     * The pages on each screen of a swiped [mode], by index; null leaves a slot blank. A spread
     * starts like a book block: the first page alone on the right, then pairs.
     */
    fun screens(pageCount: Int, mode: PageMode): List<List<Int?>> = when (mode) {
        PageMode.SPREAD -> (0..(pageCount / 2)).map { screen ->
            listOf(2 * screen - 1, 2 * screen).map { it.takeIf { index -> index in 0 until pageCount } }
        }.filter { screen -> screen.any { it != null } }
        else -> (0 until pageCount).map { listOf(it) }
    }

    /** The screen of a swiped [mode] that shows the page [pageIndex]. */
    fun screenOfPage(pageIndex: Int, mode: PageMode): Int =
        if (mode == PageMode.SPREAD) (pageIndex + 1) / 2 else pageIndex

    /**
     * The index of the section being read on [screen] of a swiped [mode]: the last one starting
     * on that screen's pages or before. Null if none has started yet.
     */
    fun currentSectionOnScreen(sections: List<TypstSection>, screen: Int, mode: PageMode): Int? {
        val lastPage = if (mode == PageMode.SPREAD) 2 * screen else screen
        return sections.indexOfLast { it.pageIndex <= lastPage }.takeIf { it >= 0 }
    }

    /** The page at the top of the view when it's scrolled [scrollTopCssPx] down (not swiped). */
    fun pageAt(document: TypstDocument, layout: ViewLayout, viewWidthCssPx: Float, scrollTopCssPx: Float): Int {
        fun top(page: Int) = sectionTopPx(
            document, TypstSection("", page, 0f), viewWidthCssPx, layout.widthPt, layout.ptPerCssPx, layout.mode,
        )
        return document.pages.indices.lastOrNull { top(it) <= scrollTopCssPx + 1f } ?: 0
    }

    /** E.g. "Page 3 of 12", or "Pages 2–3 of 12" for a spread; empty for no [pages]. */
    fun pagesLabel(pages: List<Int>, pageCount: Int): String = when (pages.size) {
        0 -> ""
        1 -> "Page ${pages[0] + 1} of $pageCount"
        else -> "Pages ${pages.first() + 1}\u2013${pages.last() + 1} of $pageCount"
    }

    /** E.g. "42%" for [fraction] 0.42. */
    fun percentLabel(fraction: Float): String = "${(fraction * 100).roundToInt()}%"

    /**
     * The first page shown on [screen] of a swiped [mode]: the page whose top a reader starts at.
     */
    fun firstPageOnScreen(screen: Int, mode: PageMode): Int =
        if (mode == PageMode.SPREAD) maxOf(0, 2 * screen - 1) else screen

    /**
     * Where the sections of [document] start in [layout], and where it ends, in the units its
     * position is measured in: CSS px from the top of the first page when scrolled, pages (with
     * fractions for how far down one) when swiped. The view is [viewWidthCssPx] wide.
     */
    fun sectionBounds(document: TypstDocument, layout: ViewLayout, viewWidthCssPx: Float): SectionBounds {
        if (layout.mode.swiped) {
            val starts = document.sections.map { section ->
                val pageHeight = document.pages.getOrNull(section.pageIndex)?.heightPt ?: 1f
                section.pageIndex + section.yPt / pageHeight
            }
            return SectionBounds(starts, document.pages.size.toFloat())
        }
        fun top(section: TypstSection) =
            sectionTopPx(document, section, viewWidthCssPx, layout.widthPt, layout.ptPerCssPx, layout.mode)
        return SectionBounds(document.sections.map(::top), top(TypstSection("", document.pages.size, 0f)))
    }

    /** Where [at] is in terms of [bounds]'s sections, which holds in any layout of the document. */
    fun readingPosition(bounds: SectionBounds, at: Float): ReadingPosition {
        val section = bounds.starts.indexOfLast { it <= at }
        val (from, to) = bounds.span(section)
        val fraction = if (to > from) ((at - from) / (to - from)).coerceIn(0f, 1f) else 0f
        return ReadingPosition(section, fraction)
    }

    /** Where [position] is in [bounds]'s layout, in its units (see [sectionBounds]). */
    fun locate(bounds: SectionBounds, position: ReadingPosition): Float {
        val (from, to) = bounds.span(position.section.coerceAtMost(bounds.starts.size - 1))
        return from + position.fraction * (to - from)
    }

    /**
     * How far below the first page's top [section] starts, in CSS px at no zoom, for a view
     * [viewWidthCssPx] wide, in [PageMode.PAGELESS] or [PageMode.SCROLLED]. Follows the page sizes
     * that [build] asks for with the same arguments.
     */
    fun sectionTopPx(
        document: TypstDocument,
        section: TypstSection,
        viewWidthCssPx: Float,
        viewWidthPt: Float?,
        ptPerCssPx: Float,
        mode: PageMode = PageMode.PAGELESS,
    ): Float {
        val reflowed = mode == PageMode.PAGELESS && reflowed(document, viewWidthPt)
        val gap = if (mode == PageMode.SCROLLED) BOOK_PAGE_GAP_PX.toFloat() else 0f
        // The width a page ends up at sets the scale of everything on it.
        fun cssPxPerPt(page: TypstPage): Float {
            val widthPx = page.widthPt / ptPerCssPx
            return when {
                reflowed && matches(page, viewWidthPt) -> viewWidthCssPx
                reflowed -> widthPx
                else -> minOf(viewWidthCssPx, widthPx)
            } / page.widthPt
        }
        val above = document.pages.take(section.pageIndex)
        val pagesAbove = above.sumOf { (it.heightPt * cssPxPerPt(it)).toDouble() }.toFloat() + above.size * gap
        val page = document.pages.getOrNull(section.pageIndex) ?: return pagesAbove
        return pagesAbove + section.yPt * cssPxPerPt(page)
    }

    /**
     * The index of the section being read, given where the sections start ([sectionTops], from
     * [sectionTopPx]) and what's on screen, all in CSS px: [scrollTopCssPx] scrolled down, in a
     * view [viewportHeightCssPx] tall whose top [topPaddingCssPx] are covered by the toolbar.
     *
     * It's the last section whose heading has reached the top of the visible area, where jumping
     * to a section puts it. Near the start, before any has, it's the first section if that's on
     * screen; at the [atEnd] of the document, the last one on screen, since a section too close to
     * the end can't scroll up that far. Null if there are no sections, or none of these applies.
     */
    fun currentSection(
        sectionTops: List<Float>,
        scrollTopCssPx: Float,
        viewportHeightCssPx: Float,
        topPaddingCssPx: Float,
        atEnd: Boolean,
    ): Int? {
        // Where a section's top is on screen, measured from the bottom of the toolbar.
        fun onScreen(top: Float) = top - scrollTopCssPx
        val visibleHeight = viewportHeightCssPx - topPaddingCssPx
        if (atEnd) {
            val last = sectionTops.indexOfLast { onScreen(it) < visibleHeight }
            if (last >= 0) return last
        }
        val reached = sectionTops.indexOfLast { onScreen(it) <= READING_LINE_PX }
        if (reached >= 0) return reached
        return sectionTops.firstOrNull()?.takeIf { onScreen(it) < visibleHeight }?.let { 0 }
    }

    /**
     * How far below the toolbar a section's top can be and still count as reached, in CSS px:
     * more than the gap a jump leaves above it (see DocumentView), less than a line of text.
     */
    private const val READING_LINE_PX = 16f

    /** A document that reflowed has a page exactly as wide as the view. */
    private fun reflowed(document: TypstDocument, viewWidthPt: Float?) =
        document.pages.any { matches(it, viewWidthPt) }

    /** Whether [page] is as wide as a view [viewWidthPt] wide, i.e. was reflowed to it. */
    private fun matches(page: TypstPage, viewWidthPt: Float?) =
        viewWidthPt != null && abs(page.widthPt - viewWidthPt) < WIDTH_TOLERANCE_PT

    private fun css(value: Float) = String.format(Locale.ROOT, "%.3f", value)
}
