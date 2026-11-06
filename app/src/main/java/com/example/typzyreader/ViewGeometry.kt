package com.example.typzyreader

import kotlin.math.roundToInt

/** How a document's pages are shown. */
enum class PageMode {
    /** Pageless documents: pages as wide as the view (or as authored), stacked without gaps. */
    PAGELESS,

    /** A book, a page filling the view at a time, turned by swiping sideways. */
    SINGLE,

    /** A book with pages as wide as the view and √2 times as tall, scrolled vertically with gaps. */
    SCROLLED,

    /** A book, two pages side by side filling the view, turned by swiping sideways. */
    SPREAD;

    /** Whether pages are turned by swiping sideways, a screen at a time. */
    val swiped: Boolean get() = this == SINGLE || this == SPREAD
}

/**
 * How documents are laid out for the view: the page width in Typst points offered to documents
 * (see [TypstBundle.compile]), the page height for books (null for pageless documents), how many
 * of those points one CSS px shows (see [DocumentHtml]), how the pages are shown, and the margin
 * suggested to documents (see [ViewGeometry.marginPt]).
 */
data class ViewLayout(
    val widthPt: Float,
    val ptPerCssPx: Float,
    val heightPt: Float? = null,
    val mode: PageMode = PageMode.PAGELESS,
    val marginPt: Float = ViewGeometry.marginPt(widthPt),
)

/** Geometry offered to documents, derived from the document view's size and the screen. */
object ViewGeometry {
    /** Margin suggested to documents on every side, in points, unless pages are too wide for it. */
    const val MARGIN_PT = 12f

    /** The widest text field (page width less both margins) suggested to documents: 15 cm, in points. */
    const val MAX_TEXT_WIDTH_PT = 15f / 2.54f * 72f

    /**
     * The margin suggested on every side of a page [widthPt] wide: [MARGIN_PT], or more, so that
     * lines are at most [MAX_TEXT_WIDTH_PT] long (in the document's own units, so at any text size).
     */
    fun marginPt(widthPt: Float): Float = maxOf(MARGIN_PT, (widthPt - MAX_TEXT_WIDTH_PT) / 2)

    /** Below this width-to-height ratio, a book shows one page filling the view. */
    const val SINGLE_PAGE_MAX_ASPECT = 0.8f

    /** Up to this view height, in centimeters, a wide view scrolls a book's pages instead of showing spreads. */
    const val SCROLLED_MAX_HEIGHT_CM = 9f

    /** The height-to-width ratio of a book's pages when scrolled, that of A-series paper. */
    const val SCROLLED_PAGE_ASPECT = 1.414f

    /**
     * The layout for a pageless document in a view [widthPx] pixels wide. Rounding to whole dp
     * keeps sub-pixel layout jitter from triggering recompiles. See [ptPerCssPx] for the other
     * parameters.
     */
    fun layout(widthPx: Int, density: Float, dpi: Float, scale: Float): ViewLayout {
        val ptPerCssPx = ptPerCssPx(density, dpi, scale)
        return ViewLayout((widthPx / density).roundToInt() * ptPerCssPx, ptPerCssPx)
    }

    /**
     * The layout for a book in a view [widthPx] by [heightPx] pixels, on a screen of [dpi] and
     * [verticalDpi] physical pixels per inch across and down. A view narrower than 0.8 times its
     * height shows one page at a time; a wider one scrolls pages if it's at most 9 cm tall, and
     * shows two-page spreads otherwise.
     */
    fun bookLayout(widthPx: Int, heightPx: Int, density: Float, dpi: Float, verticalDpi: Float, scale: Float): ViewLayout {
        val ptPerCssPx = ptPerCssPx(density, dpi, scale)
        val widthPt = (widthPx / density).roundToInt() * ptPerCssPx
        val heightPt = (heightPx / density).roundToInt() * ptPerCssPx
        val heightCm = heightPx / verticalDpi * 2.54f
        return when {
            widthPx < SINGLE_PAGE_MAX_ASPECT * heightPx -> ViewLayout(widthPt, ptPerCssPx, heightPt, PageMode.SINGLE)
            heightCm <= SCROLLED_MAX_HEIGHT_CM ->
                ViewLayout(widthPt, ptPerCssPx, widthPt * SCROLLED_PAGE_ASPECT, PageMode.SCROLLED)
            else -> ViewLayout(widthPt / 2, ptPerCssPx, heightPt, PageMode.SPREAD)
        }
    }

    /**
     * Typst points per CSS px, such that a document appears at [scale] times its printed size on
     * a screen of [dpi] physical pixels per inch. A WebView with a `device-width` viewport has 1
     * CSS px per dp, which is [density] pixels, or [density] / [dpi] inches, and an inch is 72pt.
     */
    fun ptPerCssPx(density: Float, dpi: Float, scale: Float): Float = 72f * density / (dpi * scale)

    /**
     * The screen's physical pixels per inch: [xdpi] as the device reports it (or its ydpi, for
     * the vertical), unless that's implausibly far from its [densityDpi] (some devices report
     * wrong values).
     */
    fun physicalDpi(xdpi: Float, densityDpi: Int): Float =
        if (xdpi >= densityDpi / 2f && xdpi <= densityDpi * 2f) xdpi else densityDpi.toFloat()
}
