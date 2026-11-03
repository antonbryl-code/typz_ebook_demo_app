package com.example.typzyreader

import java.util.Locale
import kotlin.math.abs

/**
 * Builds the page shown in the document WebView: the page SVGs inlined and stacked vertically.
 *
 * The viewport is `device-width`, so 1 CSS px = 1 dp, showing however many points the text size
 * calls for (see [ViewGeometry]), and a page reflowed to the view's width fills it. Pages wider than the view are scaled down to fit in documents rendered
 * as authored; in documents that reflowed, they are pages the document deliberately made wider
 * (e.g. for a big table), so they keep their size and scroll sideways, with a serrated edge on
 * each side there's more of the page to scroll to. Narrower pages keep their size and are
 * centered. Pages follow each other without gaps.
 */
object DocumentHtml {
    /** Serration teeth on the edges of sideways-scrolling pages: how far they cut in, and their pitch. */
    private const val TOOTH_DEPTH_PX = 5
    private const val TOOTH_PITCH_PX = 10

    /** How close to the view's width a page must be to count as reflowed, in points. */
    private const val WIDTH_TOLERANCE_PT = 0.5f

    /**
     * [dark] picks the background around the pages (the pages themselves stay as the document
     * paints them). It's passed in rather than left to `prefers-color-scheme`, which the WebView
     * derives from the app's XML theme instead of the system's dark mode. [topPaddingPx] keeps
     * the top of the document clear of a toolbar laid over the view. [viewWidthPt] is the width
     * the document was compiled for; null treats it as not reflowed. [ptPerCssPx] sets the size
     * pages appear at; the default is CSS's own 96 px per 72pt.
     */
    fun build(
        document: TypstDocument,
        dark: Boolean,
        topPaddingPx: Int = 0,
        viewWidthPt: Float? = null,
        ptPerCssPx: Float = 0.75f,
    ): String = buildString {
        // A document that reflowed has a page exactly as wide as the view.
        val reflowed = document.pages.any { matches(it, viewWidthPt) }
        val scheme = if (dark) "dark" else "light"
        val background = if (dark) "#202124" else "#e8e8e8"
        append(
            """
            <!DOCTYPE html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="$scheme">
            <style>
              html, body { margin: 0; padding: 0; background: $background; }
              body { padding-top: ${topPaddingPx}px; }
              /* One per page; its scroller scrolls sideways when the page is wider than the view. */
              .row { position: relative; timeline-scope: --x; }
              .scroller { overflow-x: auto; overflow-y: hidden; scroll-timeline: --x inline; }
              /* Serrated edges, cut in the background's color, where the page continues beyond
                 the view. They're shown by animations driven by the scroller's position (no
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
                background: conic-gradient(from 225deg at 100% 50%, $background 0 90deg, transparent 0)
                  0 0 / ${TOOTH_DEPTH_PX}px ${TOOTH_PITCH_PX}px repeat-y;
              }
              .row::after {
                right: 0; animation-name: more-right;
                background: conic-gradient(from 45deg at 0 50%, $background 0 90deg, transparent 0)
                  0 0 / ${TOOTH_DEPTH_PX}px ${TOOTH_PITCH_PX}px repeat-y;
              }
              @keyframes more-left { 0% { opacity: 0; } 1%, 100% { opacity: 1; } }
              @keyframes more-right { 0%, 99% { opacity: 1; } 100% { opacity: 0; } }
              .page {
                margin: 0 auto; background: white;
                position: relative; container-type: inline-size;
              }
              .page > svg { display: block; width: 100%; height: 100%; }
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
        for (page in document.pages) {
            // Typst sizes pages in pt; the aspect ratio keeps scaled-down pages proportional.
            val widthPx = css(page.widthPt / ptPerCssPx)
            append("<div class=\"row\"><div class=\"scroller\"><div class=\"page\" style=\"width: ")
            append(
                when {
                    // Fill the view exactly: its width was rounded to whole dp for compiling, so
                    // the page's own width can be a fraction of a pixel short of it.
                    reflowed && matches(page, viewWidthPt) -> "100%"
                    reflowed -> "${widthPx}px"
                    else -> "min(100%, ${widthPx}px)"
                }
            )
            append("; aspect-ratio: ")
            append(css(page.widthPt))
            append(" / ")
            append(css(page.heightPt))
            // The text layer's unit: one Typst point at the page's actual width.
            append("; --u: calc(100cqw / ")
            append(css(page.widthPt))
            append(")\">")
            append(page.svg)
            append("<div class=\"t\">")
            append(page.textLayer)
            append("</div></div></div></div>\n")
        }
        append("</body></html>")
    }

    /** Whether [page] is as wide as a view [viewWidthPt] wide, i.e. was reflowed to it. */
    private fun matches(page: TypstPage, viewWidthPt: Float?) =
        viewWidthPt != null && abs(page.widthPt - viewWidthPt) < WIDTH_TOLERANCE_PT

    private fun css(value: Float) = String.format(Locale.ROOT, "%.3f", value)
}
