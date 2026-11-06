package com.example.typzyreader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
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
    LaunchedEffect(webView, document, layout, sectionJumps) {
        val view = webView ?: return@LaunchedEffect
        sectionJumps.collect { section ->
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
            }
            view.show(html)
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}

/** Space kept between the toolbar and a section scrolled to, in CSS px. */
private const val SECTION_GAP_PX = 8f

/**
 * What a [DocumentWebView] shows: how far down it's scrolled and how tall it is, both in CSS px
 * at no zoom, whether it's at the end, and which screen it's on when pages are swiped.
 */
private class ViewPosition(
    val scrollTopCssPx: Float,
    val viewportHeightCssPx: Float,
    val atEnd: Boolean,
    val screen: Int,
)

/**
 * A WebView locked down for untrusted content: no JavaScript, file, content or network access,
 * and links open outside the app. Keeps the relative position across documents, so a recompile
 * at a new size (e.g. on rotation) stays at about the same place, even when that switches between
 * scrolling and swiping.
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        taps.onTouchEvent(event) // only watches; the WebView still handles every event
        return super.onTouchEvent(event)
    }

    fun show(html: String) {
        if (html == shownHtml) return
        val progress = progress()
        shownHtml = html
        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        // Fires once the new content is drawn, i.e. when its size is known.
        postVisualStateCallback(0, object : VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (swiped()) {
                    turnTo((progress * computeHorizontalScrollRange() / width).roundToInt())
                } else {
                    val y = (progress * computeVerticalScrollRange()).toInt()
                    if (y != scrollY) restoringTo = y
                    scrollTo(0, y)
                }
                reportPosition() // also if that didn't scroll
            }
        })
    }

    /** Whether the content scrolls sideways a screen at a time, i.e. pages are swiped. */
    private fun swiped() = width > 0 && computeHorizontalScrollRange() > width

    /** How far through the document the view is, from 0 to 1, along whichever way it scrolls. */
    private fun progress(): Float = when {
        swiped() -> scrollX.toFloat() / computeHorizontalScrollRange()
        computeVerticalScrollRange() > 0 -> scrollY.toFloat() / computeVerticalScrollRange()
        else -> 0f
    }

    private fun reportPosition() {
        if (contentHeight == 0) return
        // Allows for pinch zoom, which changes the pixels per CSS px.
        val pxPerCssPx = computeVerticalScrollRange().toFloat() / contentHeight
        val maxY = computeVerticalScrollRange() - computeVerticalScrollExtent()
        val screen = if (width > 0) (scrollX.toFloat() / width).roundToInt() else 0
        onPositionChange(ViewPosition(scrollY / pxPerCssPx, height / pxPerCssPx, maxY > 0 && scrollY >= maxY - 1, screen))
    }

    /** Scrolls to [cssPxBelowTop] CSS px from the top of the page, as far as the document allows. */
    fun jumpTo(cssPxBelowTop: Float) {
        if (contentHeight == 0) return
        // Allows for pinch zoom, which changes the pixels per CSS px.
        val pxPerCssPx = computeVerticalScrollRange().toFloat() / contentHeight
        val maxY = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
        val y = (cssPxBelowTop * pxPerCssPx).roundToInt().coerceIn(0, maxY)
        if (y != scrollY) restoringTo = y
        scrollTo(0, y)
    }

    /** Shows [screen] of swiped pages (see [DocumentHtml.screens]), or the nearest one there is. */
    fun turnTo(screen: Int) {
        val maxX = (computeHorizontalScrollRange() - width).coerceAtLeast(0)
        val x = (screen * width).coerceIn(0, maxX)
        if (x != scrollX) restoringToX = x
        scrollTo(x, 0)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (l == restoringToX) {
            restoringToX = null
        } else if (l != oldl) {
            onPageTurn()
        }
        if (t == restoringTo) {
            restoringTo = null
        } else if (t != oldt) {
            onScroll(t - oldt)
        }
        reportPosition()
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
