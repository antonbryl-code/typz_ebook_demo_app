package com.example.typzyreader

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
 * below that toolbar, and [onCurrentSectionChange] gets the index in [TypstDocument.sections] of
 * the one being read (see [DocumentHtml.currentSection]) whenever the view settles on content.
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
) {
    val dark = isSystemInDarkTheme()
    // With the device-width viewport, 1 CSS px = 1 dp.
    val html = remember(document, layout, dark, topPadding) {
        DocumentHtml.build(document, dark, topPadding.value.roundToInt(), layout.widthPt, layout.ptPerCssPx)
    }
    var webView by remember { mutableStateOf<DocumentWebView?>(null) }
    LaunchedEffect(webView, document, layout, sectionJumps) {
        val view = webView ?: return@LaunchedEffect
        sectionJumps.collect { section ->
            val viewWidthCssPx = view.width / view.resources.displayMetrics.density
            val top = DocumentHtml.sectionTopPx(document, section, viewWidthCssPx, layout.widthPt, layout.ptPerCssPx)
            view.jumpTo(top - SECTION_GAP_PX)
        }
    }
    AndroidView(
        factory = { context -> DocumentWebView(context).also { webView = it } },
        update = { view ->
            view.onScroll = onScroll
            view.onPositionChange = { scrollTopCssPx, viewportHeightCssPx, atEnd ->
                val viewWidthCssPx = view.width / view.resources.displayMetrics.density
                val tops = document.sections.map { section ->
                    DocumentHtml.sectionTopPx(document, section, viewWidthCssPx, layout.widthPt, layout.ptPerCssPx)
                }
                onCurrentSectionChange(
                    DocumentHtml.currentSection(tops, scrollTopCssPx, viewportHeightCssPx, topPadding.value, atEnd)
                )
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
 * A WebView locked down for untrusted content: no JavaScript, file, content or network access,
 * and links open outside the app. Keeps the relative scroll position across documents, so a
 * recompile at a new width (e.g. on rotation) stays at about the same place.
 */
@SuppressLint("ViewConstructor")
private class DocumentWebView(context: Context) : WebView(context) {
    private var shownHtml: String? = null
    var onScroll: (Int) -> Unit = {}

    /**
     * Gets what's on screen after each scroll and once new content is shown: how far down it's
     * scrolled and how tall the view is, both in CSS px at no zoom, and whether it's at the end.
     */
    var onPositionChange: (scrollTopCssPx: Float, viewportHeightCssPx: Float, atEnd: Boolean) -> Unit = { _, _, _ -> }

    /** Where [show] or [jumpTo] scrolls to; that scroll isn't reported to [onScroll]. */
    private var restoringTo: Int? = null

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

    fun show(html: String) {
        if (html == shownHtml) return
        val fraction = if (contentHeight > 0) scrollY / (contentHeight * resources.displayMetrics.density) else 0f
        shownHtml = html
        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        // Fires once the new content is drawn, i.e. when its height is known.
        postVisualStateCallback(0, object : VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                val y = (fraction * contentHeight * resources.displayMetrics.density).toInt()
                if (y != scrollY) restoringTo = y
                scrollTo(0, y)
                reportPosition() // also if that didn't scroll
            }
        })
    }

    private fun reportPosition() {
        if (contentHeight == 0) return
        // Allows for pinch zoom, which changes the pixels per CSS px.
        val pxPerCssPx = computeVerticalScrollRange().toFloat() / contentHeight
        val maxY = computeVerticalScrollRange() - computeVerticalScrollExtent()
        onPositionChange(scrollY / pxPerCssPx, height / pxPerCssPx, maxY > 0 && scrollY >= maxY - 1)
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

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
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
