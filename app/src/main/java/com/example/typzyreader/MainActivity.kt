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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
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
import androidx.compose.ui.unit.dp
import com.example.typzyreader.ui.theme.TypzyReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
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

/** How long after the last scroll step a half-hidden toolbar snaps fully in or out. */
private const val BAR_SNAP_DELAY_MS = 150L

/** The size of the cover in the book panel: its height, and at most the panel's usable width. */
private val COVER_HEIGHT = 240.dp
private val COVER_MAX_WIDTH = 312.dp

/** What the document area shows. */
private sealed interface ViewerContent {
    data class Message(val text: String) : ViewerContent

    /** Reading the bundle and compiling it for the first time. */
    data object Loading : ViewerContent

    /** [document] as compiled for [layout]. */
    data class Document(val document: TypstDocument, val layout: ViewLayout) : ViewerContent
}

/**
 * With no file open, a prompt to open one. Otherwise the document (or why it can't be shown),
 * under a toolbar with its title (or file name) and a menu; the toolbar slides away while
 * scrolling down the document and comes back when scrolling up.
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
    var refusal by remember { mutableStateOf<String?>(null) }
    var cover by remember { mutableStateOf<ImageBitmap?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf<String?>(null) }
    var bundle by remember { mutableStateOf<TypstBundle?>(null) }
    var viewWidthPx by remember { mutableStateOf<Int?>(null) }
    val settings = remember { ReaderSettings(context) }
    var textSizePercent by remember { mutableIntStateOf(settings.textSizePercent) }
    val layout = viewWidthPx?.let { widthPx ->
        val metrics = context.resources.displayMetrics
        val dpi = ViewGeometry.physicalDpi(metrics.xdpi, metrics.densityDpi)
        ViewGeometry.layout(widthPx, density, dpi, textSizePercent / 100f)
    }
    var content by remember { mutableStateOf<ViewerContent>(ViewerContent.Loading) }
    var recompiling by remember { mutableStateOf(false) }
    var showWarnings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val barState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(barState)
    var documentScrolls by remember { mutableIntStateOf(0) }

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
        val problem = withContext(Dispatchers.IO) { formatProblem(context, requested) }
        if (problem == null) uri = requested else refusal = problem
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

    LaunchedEffect(bundle, layout) {
        val current = bundle ?: return@LaunchedEffect
        val target = layout ?: return@LaunchedEffect
        delay(RESIZE_DEBOUNCE_MS)
        // The previous rendering stays up meanwhile, so a resize doesn't flash an empty view.
        recompiling = true
        try {
            content = withContext(Dispatchers.IO) { compile(current, target) }
        } finally {
            recompiling = false
        }
    }

    // Only the document scrolls the toolbar away; anything else shows it in full.
    val showingDocument = content is ViewerContent.Document
    LaunchedEffect(showingDocument) {
        if (!showingDocument) barState.heightOffset = 0f
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
                )
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                // The document area, under the toolbar; its width is what documents are offered
                // to lay out at.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .onSizeChanged { viewWidthPx = it.width }
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
                            topPadding = TopAppBarDefaults.TopAppBarExpandedHeight,
                            onScroll = { dy ->
                                barState.heightOffset -= dy // kept within the bar's height
                                documentScrolls++
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics {
                                    contentDescription = "Typst document"
                                    stateDescription = describe(shown)
                                },
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

/** The panel opened from the toolbar: the book's cover, its title and its top-level sections. */
@Composable
private fun BookPanel(cover: ImageBitmap?, title: String, sections: List<String>) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
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
            Text(text = title.uppercase(), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            for (section in sections) {
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("•")
                    Spacer(Modifier.width(8.dp))
                    Text(section)
                }
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

/** E.g. "2 pages laid out for a 300.00pt wide view: 300 × 410 pt, 300 × 52 pt". */
private fun describe(shown: ViewerContent.Document): String {
    val pages = shown.document.pages
    return "${pages.size} ${if (pages.size == 1) "page" else "pages"} laid out for a " +
        "${"%.2f".format(shown.layout.widthPt)}pt wide view: " +
        pages.joinToString { "%.0f × %.0f pt".format(it.widthPt, it.heightPt) }
}

/** Why the file at [uri] must not be opened, because of its format; null lets opening it proceed. */
private fun formatProblem(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { TypzArchive.formatProblem(it) }
} catch (_: Exception) {
    null // e.g. not a zip or a malformed manifest: opening it reports that, in place of the document
}

private fun openBundle(context: Context, uri: Uri): TypstBundle {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Cannot open $uri")
    // Packages are re-downloadable, so the cache directory (which Android may clear) suits them.
    val packageCache = File(context.cacheDir, "typst-packages")
    return TypstBundle.open(stream.use { TypzArchive.read(it) }, packageCache)
}

private fun compile(bundle: TypstBundle, layout: ViewLayout): ViewerContent = try {
    ViewerContent.Document(bundle.compile(layout.widthPt, ViewGeometry.MARGIN_PT, TypstUniverse), layout)
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
