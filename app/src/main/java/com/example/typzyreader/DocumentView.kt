package com.example.typzyreader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shows a compiled document in a WebView; see [DocumentHtml] for the page layout.
 * [layout] is what the document was compiled for, [topPadding] leaves room above the document
 * for a toolbar laid over the view, and [onScroll] gets each vertical scroll step in pixels
 * (positive when scrolling down). Each section from [sectionJumps] is scrolled to, leaving it just
 * below that toolbar (or turned to, in the swiped [PageMode]s), and [onCurrentSectionChange] gets
 * the index in [TypstDocument.sections] of the one being read (see [DocumentHtml.currentSection])
 * whenever the view settles on content. [onTap] gets taps that aren't part of a scroll or a long
 * press, and [onPageTurn] each step of a sideways scroll in the swiped modes.
 */
@Composable
fun DocumentView(
    document: TypstDocument,
    layout: ViewLayout,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
    onScroll: (Int) -> Unit = {},
    sectionJumps: Flow<TypstSection> = emptyFlow(),
    onCurrentSectionChange: (Int?) -> Unit = {},
    onTap: () -> Unit = {},
    onPageTurn: () -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    // With the device-width viewport, 1 CSS px = 1 dp.
    val html = remember(document, layout, dark, topPadding) {
        DocumentHtml.build(document, dark, topPadding.value.roundToInt(), layout.widthPt, layout.ptPerCssPx, layout.mode)
    }
    var webView by remember { mutableStateOf<DocumentWebView?>(null) }
    // Where the reader is, carried over when the document is laid out anew (e.g. on rotation).
    val reading = remember { mutableStateOf<ReadingPosition?>(null) }
    LaunchedEffect(webView, document, layout, sectionJumps) {
        val view = webView ?: return@LaunchedEffect
        sectionJumps.collect { section ->
            val index = document.sections.indexOf(section)
            if (index >= 0) reading.value = ReadingPosition(index, 0f)
            if (layout.mode.swiped) {
                view.turnTo(DocumentHtml.screenOfPage(section.pageIndex, layout.mode))
            } else {
                val viewWidthCssPx = view.width / view.resources.displayMetrics.density
                val top = DocumentHtml.sectionTopPx(document, section, viewWidthCssPx, layout.widthPt, layout.ptPerCssPx, layout.mode)
                view.jumpTo(top - SECTION_GAP_PX)
            }
        }
    }
    AndroidView(
        factory = { context ->
            DocumentWebView(context).also {
                // With AndroidView's default wrap_content, the WebView lays out its content for a
                // viewport 0 px tall, leaving books (whose pages are sized by its height) blank.
                it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                webView = it
            }
        },
        update = { view ->
            view.onScroll = onScroll
            view.onTap = onTap
            view.onPageTurn = onPageTurn
            view.onPositionChange = { position ->
                val current = if (layout.mode.swiped) {
                    DocumentHtml.currentSectionOnScreen(document.sections, position.screen, layout.mode)
                } else {
                    val viewWidthCssPx = view.width / view.resources.displayMetrics.density
                    val tops = document.sections.map { section ->
                        DocumentHtml.sectionTopPx(document, section, viewWidthCssPx, layout.widthPt, layout.ptPerCssPx, layout.mode)
                    }
                    DocumentHtml.currentSection(
                        tops, position.scrollTopCssPx, position.viewportHeightCssPx, topPadding.value, position.atEnd,
                    )
                }
                onCurrentSectionChange(current)
                // Only from the reader's own scrolling (a restored place stays as it was, rather
                // than drifting with rounding), and only while the view shows this layout: not
                // mid-rotation, when it has already changed size, nor while the next one loads.
                if (position.byUser && !position.loading && view.shows(layout)) {
                    val at = if (layout.mode.swiped) {
                        DocumentHtml.firstPageOnScreen(position.screen, layout.mode).toFloat()
                    } else {
                        position.scrollTopCssPx
                    }
                    reading.value = DocumentHtml.readingPosition(DocumentHtml.sectionBounds(document, layout, view.widthCssPx()), at)
                }
            }
            view.show(html, layout.mode.swiped) {
                val bounds = DocumentHtml.sectionBounds(document, layout, view.widthCssPx())
                val target = reading.value?.let { DocumentHtml.locate(bounds, it) } ?: 0f
                if (layout.mode.swiped) {
                    // The page the place is on; the margin absorbs float error at page tops.
                    val page = (target + 0.001f).toInt().coerceIn(0, maxOf(0, document.pages.size - 1))
                    RestoreTarget.Screen(DocumentHtml.screenOfPage(page, layout.mode))
                } else {
                    RestoreTarget.Scroll(target)
                }
            }
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}

/** Space kept between the toolbar and a section scrolled to, in CSS px. */
private const val SECTION_GAP_PX = 8f

/**
 * How long, and how often, to wait after new content is drawn for the WebView to report its size,
 * before restoring the reader's place in it.
 */
private const val RESTORE_ATTEMPTS = 30
private const val RESTORE_RETRY_MS = 33L

/**
 * How long, and how often, to check after restoring that the view stayed there: the WebView can
 * still move it (clamping it to a scroll range it hasn't updated yet, or re-snapping), and then
 * it's put back.
 */
private const val VERIFY_CHECKS = 10
private const val VERIFY_INTERVAL_MS = 50L

/**
 * How long after the reader last touched the view its scrolling counts as theirs (a fling goes on
 * after the finger lifts); other scrolling is the WebView's own, e.g. clamping or re-snapping.
 */
private const val USER_SCROLL_WINDOW_MS = 3000L

/** Where to put the view once new content is shown: a scroll position, or a screen of pages. */
private sealed interface RestoreTarget {
    /** CSS px from the top of the first page. */
    class Scroll(val cssPx: Float) : RestoreTarget

    /** A screen of swiped pages (see [DocumentHtml.screens]). */
    class Screen(val index: Int) : RestoreTarget
}

private fun View.widthCssPx() = width / resources.displayMetrics.density

/**
 * Whether the view is the size [layout] was made for, give or take the rounding to whole dp:
 * as wide as its pages (two of them, for spreads) and, when swiped, as tall.
 */
private fun View.shows(layout: ViewLayout): Boolean {
    val pagesAcross = if (layout.mode == PageMode.SPREAD) 2 else 1
    val widthOk = abs(layout.widthPt / layout.ptPerCssPx * pagesAcross - widthCssPx()) < SIZE_TOLERANCE_CSS_PX
    val heightPt = layout.heightPt
    val heightOk = !layout.mode.swiped || heightPt == null ||
        abs(heightPt / layout.ptPerCssPx - height / resources.displayMetrics.density) < SIZE_TOLERANCE_CSS_PX
    return widthOk && heightOk
}

/** More than the rounding of a layout to whole dp (see [ViewGeometry]), in CSS px. */
private const val SIZE_TOLERANCE_CSS_PX = 1.5f

/**
 * What a [DocumentWebView] shows: how far down it's scrolled and how tall it is, both in CSS px
 * at no zoom, whether it's at the end, which screen it's on when pages are swiped, and whether
 * new content is still [loading], in which case the position isn't the reader's.
 */
private class ViewPosition(
    val scrollTopCssPx: Float,
    val viewportHeightCssPx: Float,
    val atEnd: Boolean,
    val screen: Int,
    val loading: Boolean,
    /** Whether the reader moved there (not [DocumentWebView.show], a jump or a page turn to a section). */
    val byUser: Boolean,
)

/**
 * A WebView locked down for untrusted content: no JavaScript, file, content or network access,
 * and links open outside the app. New content (a recompile at a new size, e.g. on rotation) is
 * shown at a place its caller works out, once the view has measured it (see [show]).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class DocumentWebView(context: Context) : WebView(context) {
    private var shownHtml: String? = null
    var onScroll: (Int) -> Unit = {}
    var onTap: () -> Unit = {}
    var onPageTurn: () -> Unit = {}

    /** Gets what's on screen after each scroll and once new content is shown. */
    var onPositionChange: (ViewPosition) -> Unit = {}

    /**
     * Where [show], [jumpTo] or [turnTo] scrolls to; reaching it isn't reported to [onScroll] or
     * [onPageTurn].
     */
    private var restoringTo: Int? = null
    private var restoringToX: Int? = null

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap()
            return false
        }
    })

    init {
        settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            useWideViewPort = true // honor the page's viewport meta tag
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100 // SVG text doesn't scale with it anyway; keep the layout as compiled
        }
        setBackgroundColor(Color.TRANSPARENT) // the HTML paints the background (see DocumentHtml)
        webViewClient = LinkOpeningClient()
    }

    /** When the reader last touched the view, in [SystemClock.uptimeMillis]. */
    private var lastTouchMs = Long.MIN_VALUE / 2

    private fun touchedRecently() = SystemClock.uptimeMillis() - lastTouchMs < USER_SCROLL_WINDOW_MS

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastTouchMs = SystemClock.uptimeMillis()
        taps.onTouchEvent(event) // only watches; the WebView still handles every event
        return super.onTouchEvent(event)
    }

    /** Counts [show]s, so a restore for content that's since been replaced can tell. */
    private var loads = 0

    /** Whether new content is loading, and the reader's place not yet restored in it. */
    private var loading = false

    /**
     * Shows [html], whose pages are [swiped] or scrolled, then puts the view where [restoreTo]
     * says (asked only once the content is laid out, so it can measure the view as it is then).
     */
    fun show(html: String, swiped: Boolean, restoreTo: () -> RestoreTarget) {
        if (html == shownHtml) return
        val load = ++loads
        val before = scrollRange(swiped)
        loading = true
        shownHtml = html
        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        // Fires once the new content is drawn.
        postVisualStateCallback(0, object : VisualStateCallback() {
            override fun onComplete(requestId: Long) = restore(load, swiped, before, restoreTo, RESTORE_ATTEMPTS)
        })
    }

    /**
     * Restores the reader's place once the view knows the new content's size, which it learns a
     * little after the content is drawn: until then, its scroll range is still the old content's.
     */
    private fun restore(load: Int, swiped: Boolean, rangeBefore: Int, restoreTo: () -> RestoreTarget, attemptsLeft: Int) {
        if (load != loads) return // other content has replaced it meanwhile
        val sized = scrollRange(swiped) != rangeBefore && (!swiped || swiped())
        if (!sized && attemptsLeft > 0) {
            postDelayed({ restore(load, swiped, rangeBefore, restoreTo, attemptsLeft - 1) }, RESTORE_RETRY_MS)
            return
        }
        val target = restoreTo()
        moveTo(target)
        verify(load, target, VERIFY_CHECKS)
    }

    /**
     * Puts the view back at [target] whenever the WebView has moved it since, until it stays put
     * or the reader takes over; only then does the reader's place follow the view again.
     */
    private fun verify(load: Int, target: RestoreTarget, checksLeft: Int) {
        if (load != loads) return
        if (!touchedRecently()) {
            val (x, y) = scrollFor(target)
            if (scrollX != x || scrollY != y) moveTo(target)
            if (checksLeft > 0) {
                postDelayed({ verify(load, target, checksLeft - 1) }, VERIFY_INTERVAL_MS)
                return
            }
        }
        loading = false
        reportPosition(byUser = false) // also if that didn't scroll
    }

    private fun moveTo(target: RestoreTarget) = when (target) {
        is RestoreTarget.Scroll -> jumpTo(target.cssPx)
        is RestoreTarget.Screen -> turnTo(target.index)
    }

    /** Where [target] puts the view, as scroll offsets in pixels. */
    private fun scrollFor(target: RestoreTarget): Pair<Int, Int> = when (target) {
        is RestoreTarget.Scroll -> 0 to scrollYFor(target.cssPx)
        is RestoreTarget.Screen -> scrollXFor(target.index) to 0
    }

    private fun scrollRange(swiped: Boolean) = if (swiped) computeHorizontalScrollRange() else computeVerticalScrollRange()

    /** Whether the content scrolls sideways a screen at a time, i.e. pages are swiped. */
    private fun swiped() = width > 0 && computeHorizontalScrollRange() > width

    private fun reportPosition(byUser: Boolean) {
        if (contentHeight == 0) return
        // Allows for pinch zoom, which changes the pixels per CSS px.
        val pxPerCssPx = computeVerticalScrollRange().toFloat() / contentHeight
        val maxY = computeVerticalScrollRange() - computeVerticalScrollExtent()
        val screen = if (width > 0) (scrollX.toFloat() / width).roundToInt() else 0
        val atEnd = maxY > 0 && scrollY >= maxY - 1
        onPositionChange(ViewPosition(scrollY / pxPerCssPx, height / pxPerCssPx, atEnd, screen, loading, byUser))
    }

    /** Scrolls to [cssPxBelowTop] CSS px from the top of the page, as far as the document allows. */
    fun jumpTo(cssPxBelowTop: Float) {
        if (contentHeight == 0) return
        val y = scrollYFor(cssPxBelowTop)
        if (y != scrollY) restoringTo = y
        scrollTo(0, y)
    }

    private fun scrollYFor(cssPxBelowTop: Float): Int {
        if (contentHeight == 0) return 0
        // Allows for pinch zoom, which changes the pixels per CSS px.
        val pxPerCssPx = computeVerticalScrollRange().toFloat() / contentHeight
        val maxY = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
        return (cssPxBelowTop * pxPerCssPx).roundToInt().coerceIn(0, maxY)
    }

    /** Shows [screen] of swiped pages (see [DocumentHtml.screens]), or the nearest one there is. */
    fun turnTo(screen: Int) {
        val x = scrollXFor(screen)
        if (x != scrollX) restoringToX = x
        scrollTo(x, 0)
    }

    private fun scrollXFor(screen: Int): Int {
        val maxX = (computeHorizontalScrollRange() - width).coerceAtLeast(0)
        return (screen * width).coerceIn(0, maxX)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        var moved = false
        if (l == restoringToX) {
            restoringToX = null
        } else if (l != oldl) {
            onPageTurn()
            moved = true
        }
        if (t == restoringTo) {
            restoringTo = null
        } else if (t != oldt) {
            onScroll(t - oldt)
            moved = true
        }
        reportPosition(byUser = moved && touchedRecently())
    }

    private class LinkOpeningClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme in setOf("http", "https", "mailto", "tel")) {
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    // Nothing on the device handles it; ignore the tap.
                }
            }
            return true // never navigate away from the document
        }
    }
}
