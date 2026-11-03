package com.example.typzyreader

import kotlin.math.roundToInt

/**
 * How documents are laid out for the view: its width in Typst points, offered to documents (see
 * [TypstBundle.compile]), and how many of those points one CSS px shows (see [DocumentHtml]).
 */
data class ViewLayout(val widthPt: Float, val ptPerCssPx: Float)

/** Geometry offered to documents, derived from the document view's size and the screen. */
object ViewGeometry {
    /** Margin suggested to documents on every side, in points. */
    const val MARGIN_PT = 12f

    /**
     * The layout of a view [widthPx] pixels wide. Rounding to whole dp keeps sub-pixel layout
     * jitter from triggering recompiles. See [ptPerCssPx] for the other parameters.
     */
    fun layout(widthPx: Int, density: Float, dpi: Float, scale: Float): ViewLayout {
        val ptPerCssPx = ptPerCssPx(density, dpi, scale)
        return ViewLayout((widthPx / density).roundToInt() * ptPerCssPx, ptPerCssPx)
    }

    /**
     * Typst points per CSS px, such that a document appears at [scale] times its printed size on
     * a screen of [dpi] physical pixels per inch. A WebView with a `device-width` viewport has 1
     * CSS px per dp, which is [density] pixels, or [density] / [dpi] inches, and an inch is 72pt.
     */
    fun ptPerCssPx(density: Float, dpi: Float, scale: Float): Float = 72f * density / (dpi * scale)

    /**
     * The screen's physical pixels per inch: [xdpi] as the device reports it, unless that's
     * implausibly far from its [densityDpi] (some devices report wrong values).
     */
    fun physicalDpi(xdpi: Float, densityDpi: Int): Float =
        if (xdpi >= densityDpi / 2f && xdpi <= densityDpi * 2f) xdpi else densityDpi.toFloat()
}
