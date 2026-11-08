package com.example.typzyreader

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.typzyreader.ui.theme.TypzyReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialUri = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
        setContent {
            TypzyReaderTheme {
                // Draws edge to edge, keeping its content clear of the system bars itself.
                TypzViewer(initialUri = initialUri, modifier = Modifier.fillMaxSize())
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode changes are handled in place (see the manifest), so re-pick the system bar
        // styles for the new light/dark mode; Compose follows on its own.
        enableEdgeToEdge()
    }
}

/** How long the document view's width must stay put before recompiling, to coalesce resizes. */
private const val RESIZE_DEBOUNCE_MS = 150L

/**
 * How many layouts of the open document are kept compiled: enough to turn the phone back and
 * forth, or try a few text sizes, without recompiling; each holds all of its pages' SVGs.
 */
private const val COMPILED_LAYOUTS = 4

/** How long after the last scroll step a half-hidden toolbar snaps fully in or out. */
private const val BAR_SNAP_DELAY_MS = 150L

/** The size of the cover in the book panel: its height, and at most the panel's usable width. */
private val COVER_HEIGHT = 240.dp
private val COVER_MAX_WIDTH = 312.dp

/** Where the panel's text starts, lined up with the labels of its section items. */
private val PANEL_TEXT_INSET = 28.dp

/** What the document area shows. */
private sealed interface ViewerContent {
    data class Message(val text: String) : ViewerContent

    /** Reading the bundle and compiling it for the first time. */
    data object Loading : ViewerContent

    /** [document] as compiled for [layout]. */
    data class Document(val document: TypstDocument, val layout: ViewLayout) : ViewerContent {
        /** Whether it's a book, with pages laid out for the view. */
        val book: Boolean get() = layout.mode != PageMode.PAGELESS
    }
}

/**
 * With no file open, a prompt to open one. Otherwise the document (or why it can't be shown),
 * under a toolbar with its title (or file name) and a menu. In pageless documents the toolbar
 * slides away while scrolling down and comes back when scrolling up; in books, which use the
 * whole view, it starts hidden, a tap shows or hides it, and turning or scrolling the pages hides it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypzViewer(initialUri: Uri?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // Saved, so the document comes back if Android recreates the activity or restores the process.
    var uri by rememberSaveable { mutableStateOf<Uri?>(null) }
    // A file asked to be opened, until its format is checked; a refused one leaves [uri] alone.
    var requestedUri by rememberSaveable { mutableStateOf(initialUri) }
    // The format of the document at [uri], as its manifest names it.
    var format by rememberSaveable { mutableStateOf(TypzFormat.PAGELESS) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var cover by remember { mutableStateOf<ImageBitmap?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val sectionJumps = remember { MutableSharedFlow<TypstSection>(extraBufferCapacity = 1) }
    // The index of the section being read, as the document view reports it.
    var currentSection by remember { mutableStateOf<Int?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var bundle by remember { mutableStateOf<TypstBundle?>(null) }
    var viewSize by remember { mutableStateOf<IntSize?>(null) }
    val settings = remember { ReaderSettings(context) }
    var textSizePercent by remember { mutableIntStateOf(settings.textSizePercent) }
    val layout = viewSize?.let { size ->
        val metrics = context.resources.displayMetrics
        val dpi = ViewGeometry.physicalDpi(metrics.xdpi, metrics.densityDpi)
        val scale = textSizePercent / 100f
        if (format == TypzFormat.BOOK) {
            val verticalDpi = ViewGeometry.physicalDpi(metrics.ydpi, metrics.densityDpi)
            ViewGeometry.bookLayout(size.width, size.height, density, dpi, verticalDpi, scale)
        } else {
            ViewGeometry.layout(size.width, density, dpi, scale)
        }
    }
    var content by remember { mutableStateOf<ViewerContent>(ViewerContent.Loading) }
    var recompiling by remember { mutableStateOf(false) }
    var showWarnings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val barState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(barState)
    var documentScrolls by remember { mutableIntStateOf(0) }
    // Whether the document view shows its document, rather than hiding it while it's set up.
    var documentShowing by remember { mutableStateOf(true) }
    // Where the view is in the document, for the bottom bar's slider, and the slider's moves.
    var progress by remember { mutableStateOf<DocumentProgress?>(null) }
    val slides = remember { MutableSharedFlow<Float>(extraBufferCapacity = 1) }
    // While the slider is dragged, the scrolling it causes doesn't hide the bars.
    var sliding by remember { mutableStateOf(false) }
    val barAnimation = remember { mutableStateOf<Job?>(null) }
    // Slides the toolbar fully in or out, as books do on taps and page turns.
    val showBar = { shown: Boolean ->
        val target = if (shown) 0f else barState.heightOffsetLimit
        if (barState.heightOffset != target) {
            barAnimation.value?.cancel()
            barAnimation.value = scope.launch {
                animate(barState.heightOffset, target) { value, _ -> barState.heightOffset = value }
            }
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { picked ->
        if (picked != null) {
            keepReadAccess(context, picked)
            requestedUri = picked
        }
    }
    val pickFile = { openDocument.launch(arrayOf("*/*")) }

    LaunchedEffect(requestedUri) {
        val requested = requestedUri ?: return@LaunchedEffect
        val named = withContext(Dispatchers.IO) { formatOf(context, requested) }
        val known = TypzFormat.of(named)
        if (known != null) {
            format = known
            uri = requested
        } else {
            refusal = TypzArchive.formatProblem(named)
        }
        requestedUri = null
    }

    LaunchedEffect(uri) {
        val current = uri ?: return@LaunchedEffect
        fileName = withContext(Dispatchers.IO) { displayName(context, current) }
    }

    // Keep the bundle open while its document is shown, so width changes only recompile.
    LaunchedEffect(uri) {
        val current = uri ?: return@LaunchedEffect
        content = ViewerContent.Loading
        val opened = try {
            // Not cancellable, so a bundle opened just as the URI changes is still closed below.
            withContext(Dispatchers.IO + NonCancellable) { openBundle(context, current) }
        } catch (e: Exception) {
            content = ViewerContent.Message("Error: ${e.message}")
            return@LaunchedEffect
        }
        try {
            bundle = opened
            awaitCancellation()
        } finally {
            bundle = null
            // Closing waits for a running compile, so keep it off the main thread.
            withContext(Dispatchers.IO + NonCancellable) { opened.close() }
        }
    }

    LaunchedEffect(bundle) {
        cover = null
        val data = bundle?.coverImage ?: return@LaunchedEffect
        val maxWidthPx = (COVER_MAX_WIDTH.value * density).roundToInt()
        val maxHeightPx = (COVER_HEIGHT.value * density).roundToInt()
        cover = withContext(Dispatchers.Default) { decodeCover(data, maxWidthPx, maxHeightPx) }
    }

    // The document as compiled for the layouts it was recently shown at, e.g. both orientations.
    val compiled = remember(bundle) { LayoutCache<ViewLayout, ViewerContent.Document>(COMPILED_LAYOUTS) }
    LaunchedEffect(bundle, layout) {
        val current = bundle ?: return@LaunchedEffect
        val target = layout ?: return@LaunchedEffect
        compiled[target]?.let {
            content = it
            return@LaunchedEffect
        }
        delay(RESIZE_DEBOUNCE_MS)
        // The previous rendering stays up meanwhile, so a resize doesn't flash an empty view.
        recompiling = true
        try {
            val result = withContext(Dispatchers.IO) { compile(current, target) }
            // Errors aren't kept: a later attempt may succeed, e.g. once packages can be downloaded.
            if (result is ViewerContent.Document) compiled[target] = result
            content = result
        } finally {
            recompiling = false
        }
    }

    // Only the document scrolls the toolbar away; anything else shows it in full.
    val showingDocument = content is ViewerContent.Document
    LaunchedEffect(showingDocument) {
        if (!showingDocument) barState.heightOffset = 0f
    }
    // A book opens with the toolbar hidden, so it doesn't cover the first page; a tap shows it.
    // Once per document: recompiling it (e.g. on rotation) leaves the toolbar as it was.
    val showingBook = (content as? ViewerContent.Document)?.book == true
    LaunchedEffect(uri, showingBook) {
        if (!showingBook) return@LaunchedEffect
        // The limit is unknown until the toolbar has been measured.
        barState.heightOffset = snapshotFlow { barState.heightOffsetLimit }.first { it > -Float.MAX_VALUE }
    }
    // The WebView doesn't take part in Compose's nested scrolling, so the toolbar follows its
    // scroll steps directly (see DocumentView), and snaps once they stop instead of on fling end.
    LaunchedEffect(documentScrolls) {
        if (documentScrolls == 0) return@LaunchedEffect
        delay(BAR_SNAP_DELAY_MS)
        val offset = barState.heightOffset
        val limit = barState.heightOffsetLimit
        if (offset < 0f && offset > limit) {
            val target = if (offset < limit / 2) limit else 0f
            animate(offset, target) { value, _ -> barState.heightOffset = value }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (uri == null && requestedUri == null) {
            OpenFilePrompt(onOpen = pickFile)
            return@Surface
        }
        val documentTitle = (content as? ViewerContent.Document)?.document?.title ?: fileName
        // Back closes the panel rather than leaving the app.
        BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Only for closing it: swiping it open would take sideways swipes from wide pages.
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                BookPanel(
                    cover = cover,
                    title = documentTitle.orEmpty(),
                    sections = (content as? ViewerContent.Document)?.document?.sections.orEmpty(),
                    currentSection = currentSection,
                    onSectionClick = { section ->
                        val book = (content as? ViewerContent.Document)?.layout?.mode != PageMode.PAGELESS
                        // In pageless documents, the section lands just below the toolbar.
                        if (book) showBar(false) else barState.heightOffset = 0f
                        sectionJumps.tryEmit(section)
                        scope.launch { drawerState.close() }
                    },
                )
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                // The document area, under the toolbar; its size is what documents are offered
                // to lay out at.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .onSizeChanged { viewSize = it }
                ) {
                    when (val shown = content) {
                        is ViewerContent.Message -> Text(
                            text = shown.text,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(top = TopAppBarDefaults.TopAppBarExpandedHeight)
                                .padding(16.dp),
                        )
                        // The first compile in a process also scans the fonts, so this can take a moment.
                        ViewerContent.Loading -> CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .semantics { contentDescription = "Loading document" }
                        )
                        is ViewerContent.Document -> DocumentView(
                            document = shown.document,
                            layout = shown.layout,
                            // Laid out for another size (or text size) until the recompile is done.
                            stale = layout != null && layout != shown.layout,
                            onShowingChange = { documentShowing = it },
                            // Books use the whole view; their toolbar shows over the pages on a tap.
                            topPadding = if (shown.book) 0.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
                            sectionJumps = sectionJumps,
                            onCurrentSectionChange = { currentSection = it },
                            bottomPadding = if (shown.book) 0.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
                            onProgressChange = { progress = it },
                            slides = slides,
                            onScroll = { dy ->
                                if (sliding) return@DocumentView
                                if (shown.book) {
                                    showBar(false)
                                } else {
                                    barState.heightOffset -= dy // kept within the bar's height
                                    documentScrolls++
                                }
                            },
                            onTap = {
                                if (shown.book) showBar(barState.heightOffset < barState.heightOffsetLimit / 2)
                            },
                            onPageTurn = { if (shown.book && !sliding) showBar(false) },
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics {
                                    contentDescription = "Typst document"
                                    stateDescription = describe(shown, currentSection)
                                },
                        )
                    }
                    // In place of the document while it's hidden: being laid out for a new size,
                    // or put back at the reader's place.
                    if (content is ViewerContent.Document && !documentShowing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .semantics { contentDescription = "Laying out document" }
                        )
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    TopAppBar(
                        title = {
                            // The document's own title if it sets one, else the file's name.
                            Text(text = documentTitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(painterResource(R.drawable.ic_menu), contentDescription = "Book contents")
                            }
                        },
                        actions = {
                            val shown = content
                            if (shown is ViewerContent.Document && shown.document.warnings.isNotEmpty()) {
                                IconButton(onClick = { showWarnings = true }) {
                                    Icon(painterResource(R.drawable.ic_warning), contentDescription = "Show warnings")
                                }
                            }
                            // In a Box, so the menu drops down from the button.
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Menu")
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Open File") },
                                        onClick = {
                                            showMenu = false
                                            pickFile()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        onClick = {
                                            showMenu = false
                                            showSettings = true
                                        },
                                    )
                                }
                            }
                        },
                        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        scrollBehavior = scrollBehavior,
                    )
                    if (recompiling && showingDocument) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Updating layout" }
                        )
                    }
                }
                val shownProgress = progress
                if (showingDocument && shownProgress != null) {
                    // Slides in and out with the toolbar: the same share of each is on screen.
                    val limit = barState.heightOffsetLimit
                    val barShown = if (limit < 0f && limit > -Float.MAX_VALUE) 1f - barState.heightOffset / limit else 1f
                    PositionBar(
                        progress = shownProgress,
                        shownFraction = barShown,
                        onSlide = {
                            sliding = true
                            slides.tryEmit(it)
                        },
                        onSlideEnd = { sliding = false },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    val shown = content
    if (showWarnings && shown is ViewerContent.Document) {
        AlertDialog(
            onDismissRequest = { showWarnings = false },
            confirmButton = { TextButton(onClick = { showWarnings = false }) { Text("Close") } },
            title = { Text("Warnings") },
            text = {
                Text(
                    text = shown.document.warnings,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
        )
    }
    refusal?.let { problem ->
        AlertDialog(
            onDismissRequest = { refusal = null },
            confirmButton = { TextButton(onClick = { refusal = null }) { Text("Close") } },
            title = { Text("Can't open this file") },
            text = { Text(text = problem, modifier = Modifier.verticalScroll(rememberScrollState())) },
        )
    }
    if (showSettings) {
        SettingsDialog(
            textSizePercent = textSizePercent,
            onTextSizeChange = {
                textSizePercent = it
                settings.textSizePercent = it
            },
            onDismiss = { showSettings = false },
        )
    }
}

/** The app's settings. An open document follows them right away. */
@Composable
private fun SettingsDialog(
    textSizePercent: Int,
    onTextSizeChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Settings") },
        text = {
            Column {
                Text("Text size: $textSizePercent%")
                Slider(
                    value = textSizePercent.toFloat(),
                    onValueChange = { onTextSizeChange(ReaderSettings.nearestTextSize(it.roundToInt())) },
                    valueRange = ReaderSettings.MIN_TEXT_SIZE.toFloat()..ReaderSettings.MAX_TEXT_SIZE.toFloat(),
                    // The positions between the two ends.
                    steps = (ReaderSettings.MAX_TEXT_SIZE - ReaderSettings.MIN_TEXT_SIZE) / ReaderSettings.TEXT_SIZE_STEP - 1,
                    modifier = Modifier.semantics { contentDescription = "Text size" },
                )
                Text(
                    text = "Scales the whole document, images included. 100% matches its printed size.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

/**
 * The bar at the bottom: where the view is in the document, and a slider to move through it. As
 * tall and coloured as the toolbar, and pushed off the bottom of the screen by the share of it
 * that [shownFraction] says is hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PositionBar(
    progress: DocumentProgress,
    shownFraction: Float,
    onSlide: (Float) -> Unit,
    onSlideEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // While dragging, the slider follows the finger rather than the (slightly later) view.
    var dragged by remember(progress.max) { mutableStateOf<Float?>(null) }
    Surface(
        color = TopAppBarDefaults.topAppBarColors().containerColor,
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.place(0, (placeable.height * (1f - shownFraction)).roundToInt())
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .height(TopAppBarDefaults.TopAppBarExpandedHeight)
                .padding(horizontal = 16.dp),
        ) {
            Text(progress.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Spacer(Modifier.width(16.dp))
            Slider(
                value = dragged ?: progress.value,
                onValueChange = {
                    dragged = it
                    onSlide(it)
                },
                onValueChangeFinished = {
                    dragged = null
                    onSlideEnd()
                },
                valueRange = 0f..maxOf(progress.max, 0.001f),
                enabled = progress.max > 0f,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Position in document" },
            )
        }
    }
}

/** The panel opened from the toolbar: the book's cover, its title and its top-level sections to jump to. */
@Composable
private fun BookPanel(
    cover: ImageBitmap?,
    title: String,
    sections: List<TypstSection>,
    currentSection: Int?,
    onSectionClick: (TypstSection) -> Unit,
) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
        ) {
            val coverModifier = Modifier
                .align(Alignment.CenterHorizontally)
                .height(COVER_HEIGHT)
            if (cover != null) {
                Image(cover, contentDescription = "Book cover", modifier = coverModifier)
            } else {
                Image(painterResource(R.drawable.default_cover), contentDescription = "Book cover", modifier = coverModifier)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = PANEL_TEXT_INSET),
            )
            Spacer(Modifier.height(16.dp))
            sections.forEachIndexed { i, section ->
                NavigationDrawerItem(
                    label = { Text(section.name) },
                    selected = i == currentSection,
                    onClick = { onSectionClick(section) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }
    }
}

/** Shown while no file is open. */
@Composable
private fun OpenFilePrompt(onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Open a .typz file to view it.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpen) {
            Icon(
                painterResource(R.drawable.ic_folder_open),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Open file")
        }
    }
}

/** The document's file name as the providing app reports it, e.g. "book.typz". */
private fun displayName(context: Context, uri: Uri): String? {
    val reported = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
        null // e.g. file: URIs, or a provider that refuses queries
    }
    return reported ?: uri.lastPathSegment
}

/**
 * Keeps read access to a picked document beyond this activity instance, so the saved URI still
 * opens after Android restores the process. Grants from other apps' VIEW intents can't be
 * persisted, but those come back with the redelivered intent instead.
 */
private fun keepReadAccess(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // The provider doesn't offer persistable grants; the document still opens this time.
    }
}

/**
 * E.g. "2 pages laid out for a 300.00pt wide view: 300 × 410 pt, 300 × 52 pt", followed by the
 * section being read, if any: "; reading Chapter One".
 */
private fun describe(shown: ViewerContent.Document, currentSection: Int?): String {
    val pages = shown.document.pages
    val reading = currentSection?.let { shown.document.sections.getOrNull(it) }?.let { "; reading ${it.name}" }
    return "${pages.size} ${if (pages.size == 1) "page" else "pages"} laid out for a " +
        "${"%.2f".format(shown.layout.widthPt)}pt wide view: " +
        pages.joinToString { "%.0f × %.0f pt".format(it.widthPt, it.heightPt) } + reading.orEmpty()
}

/** The format named by the manifest of the file at [uri] (see [TypzArchive.format]). */
private fun formatOf(context: Context, uri: Uri): String = try {
    context.contentResolver.openInputStream(uri)?.use { TypzArchive.format(it) } ?: TypzFormat.PAGELESS.value
} catch (_: Exception) {
    // E.g. not a zip or a malformed manifest: opening it reports that, in place of the document.
    TypzFormat.PAGELESS.value
}

private fun openBundle(context: Context, uri: Uri): TypstBundle {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Cannot open $uri")
    // Packages are re-downloadable, so the cache directory (which Android may clear) suits them.
    val packageCache = File(context.cacheDir, "typst-packages")
    return TypstBundle.open(stream.use { TypzArchive.read(it) }, packageCache)
}

private fun compile(bundle: TypstBundle, layout: ViewLayout): ViewerContent = try {
    ViewerContent.Document(
        bundle.compile(layout.widthPt, layout.marginPt, layout.heightPt, TypstUniverse),
        layout,
    )
} catch (e: TypstCompileException) {
    ViewerContent.Message("Typst errors:\n${e.message}")
} catch (e: Exception) {
    // E.g. a native panic, or the bundle being closed while this compile waited for it.
    ViewerContent.Message("Error: ${e.message}")
}

@Preview(showBackground = true)
@Composable
fun TypzViewerPreview() {
    TypzyReaderTheme {
        TypzViewer(initialUri = null)
    }
}
