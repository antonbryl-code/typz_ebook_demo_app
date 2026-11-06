package com.example.typzyreader

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isNotDisplayed
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class TypzViewerTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetSettings() {
        context.getSharedPreferences(ReaderSettings.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** How a one-page document describes itself laid out in a view [dp] wide at a text size. */
    private fun laidOut(dp: Int, textSizePercent: Int = ReaderSettings.DEFAULT_TEXT_SIZE): String {
        val metrics = context.resources.displayMetrics
        val dpi = ViewGeometry.physicalDpi(metrics.xdpi, metrics.densityDpi)
        val widthPt = dp * ViewGeometry.ptPerCssPx(metrics.density, dpi, textSizePercent / 100f)
        return "1 page laid out for a ${"%.2f".format(widthPt)}pt wide view: "
    }

    private fun writeTypz(
        mainTyp: String = """
            #let width = sys.inputs.at("ebook-width", default: none)
            #set page(width: eval(width), height: auto) if width != none
            Hello
        """.trimIndent(),
        manifestExtra: String = "",
    ): File {
        val files = mapOf(
            "typst.toml" to "[project]\nentrypoint = \"main.typ\"\n$manifestExtra",
            "main.typ" to mainTyp,
        )
        val file = File(context.cacheDir, "viewer-test.typz")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    /** Waits until the document view describes its state as containing [text]. */
    private fun waitForDocument(text: String) {
        val matcher = SemanticsMatcher("document state contains \"$text\"") { node ->
            node.config.getOrNull(SemanticsProperties.StateDescription)?.contains(text) == true
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Opens a .typz in [format] and expects a dialog with [message] and no document behind it. */
    private fun assertRefused(format: String, message: String) {
        val uri = Uri.fromFile(writeTypz(manifestExtra = "[tool.typzy-reader]\nformat = \"$format\"\n"))
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Can't open this file").assertExists()
        compose.onNodeWithText("Open a .typz file to view it.").assertExists()
        compose.onNodeWithContentDescription("Typst document").assertDoesNotExist()

        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.onNodeWithText("Open a .typz file to view it.").assertExists()
    }

    @Test
    fun opensBooksWithPagesSizedForTheView() {
        val mainTyp = """
            #set page(width: eval(sys.inputs.at("ebook-width")), height: eval(sys.inputs.at("ebook-height")))
            = One
            #pagebreak()
            = Two
        """.trimIndent()
        val uri = Uri.fromFile(writeTypz(mainTyp = mainTyp, manifestExtra = "[tool.typzy-reader]\nformat = \"book\"\n"))
        compose.setContent {
            // Taller than 1.25 times its width: one page at a time, as large as the view.
            Box(Modifier.width(400.dp).height(700.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument("2 pages laid out")
        compose.onNodeWithText("Can't open this file").assertDoesNotExist()
        // The toolbar starts hidden, so it doesn't cover the first page.
        compose.waitUntil(timeoutMillis = 5_000) { compose.onNodeWithContentDescription("Book contents").isNotDisplayed() }

        // A page as wide as the view (system bars may take some of its height, so that's not checked).
        val metrics = context.resources.displayMetrics
        val dpi = ViewGeometry.physicalDpi(metrics.xdpi, metrics.densityDpi)
        val widthPt = 400 * ViewGeometry.ptPerCssPx(metrics.density, dpi, ReaderSettings.DEFAULT_TEXT_SIZE / 100f)
        waitForDocument(": %.0f × ".format(widthPt))
    }

    @Test
    fun keepsTheReadersPlaceWhenTheViewTurns() {
        val chapters = (1..4).joinToString("\n") { "#pagebreak(weak: true)\n= Chapter $it\n#lorem(500)" }
        val mainTyp = """
            #set page(
              width: eval(sys.inputs.at("ebook-width")),
              height: eval(sys.inputs.at("ebook-height")),
              margin: eval(sys.inputs.at("ebook-margin")),
            )
        """.trimIndent() + "\n" + chapters
        val uri = Uri.fromFile(writeTypz(mainTyp = mainTyp, manifestExtra = "[tool.typzy-reader]\nformat = \"book\"\n"))
        var portrait by mutableStateOf(true)
        compose.setContent {
            // Like a phone turning: one page at a time upright, pages scrolled sideways.
            val size = if (portrait) Modifier.width(400.dp).height(700.dp) else Modifier.width(700.dp).height(400.dp)
            Box(size) { TypzViewer(initialUri = uri) }
        }
        waitForDocument("; reading Chapter 1")
        val portraitWidth = compose.onNodeWithContentDescription("Typst document").fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription].substringBefore("pt wide")

        // Show the toolbar with a tap, and jump to Chapter 3.
        compose.onNodeWithContentDescription("Typst document").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onNodeWithContentDescription("Book contents").isDisplayed() }
        compose.onNodeWithContentDescription("Book contents").performClick()
        compose.onNodeWithText("Chapter 3").performClick()
        waitForDocument("; reading Chapter 3")

        portrait = false
        compose.waitUntil(timeoutMillis = 15_000) {
            val state = compose.onNodeWithContentDescription("Typst document").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription]
            !state.startsWith(portraitWidth) && state.endsWith("; reading Chapter 3")
        }

        portrait = true
        compose.waitUntil(timeoutMillis = 15_000) {
            val state = compose.onNodeWithContentDescription("Typst document").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription]
            state.startsWith(portraitWidth) && state.endsWith("; reading Chapter 3")
        }
    }

    @Test
    fun refusesUnknownFormat() {
        assertRefused("scroll", "\"scroll\" is an unknown value for the \"format\" parameter.")
    }

    @Test
    fun opensExplicitPagelessFormat() {
        val uri = Uri.fromFile(writeTypz(manifestExtra = "[tool.typzy-reader]\nformat = \"pageless\"\n"))
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument(laidOut(400))
        compose.onNodeWithText("Can't open this file").assertDoesNotExist()
    }

    @Test
    fun bookPanelShowsCoverTitleAndSections() {
        val mainTyp = "#set document(title: \"My Book\")\n= Chapter One\n== Part\n= Chapter Two"
        val uri = Uri.fromFile(writeTypz(mainTyp = mainTyp))
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument("1 page laid out")
        compose.onNodeWithText("MY BOOK").assertIsNotDisplayed()

        compose.onNodeWithContentDescription("Book contents").performClick()
        compose.onNodeWithContentDescription("Book cover").assertIsDisplayed()
        compose.onNodeWithText("MY BOOK").assertIsDisplayed()
        compose.onNodeWithText("Chapter One").assertIsDisplayed()
        compose.onNodeWithText("Chapter Two").assertIsDisplayed()
        compose.onNodeWithText("Part").assertDoesNotExist()
    }

    @Test
    fun tappingASectionClosesThePanel() {
        val uri = Uri.fromFile(writeTypz(mainTyp = "= Chapter One\n#pagebreak()\n= Chapter Two"))
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument("2 pages laid out")
        compose.onNodeWithContentDescription("Book contents").performClick()
        compose.onNodeWithText("Chapter Two").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Chapter Two").assertIsNotDisplayed()
    }

    @Test
    fun highlightsTheSectionBeingRead() {
        val mainTyp = """
            #let width = sys.inputs.at("ebook-width", default: none)
            #set page(width: eval(width), height: auto) if width != none
            = Chapter One
            #lorem(600)
            = Chapter Two
            #lorem(600)
        """.trimIndent()
        val uri = Uri.fromFile(writeTypz(mainTyp = mainTyp))
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument("1 page laid out")
        compose.onNodeWithContentDescription("Book contents").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("Chapter One") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Chapter Two").assertIsNotSelected().performClick()

        compose.onNodeWithContentDescription("Book contents").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("Chapter Two") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Chapter One").assertIsNotSelected()
    }

    @Test
    fun bookPanelFallsBackToFileName() {
        val uri = Uri.fromFile(writeTypz())
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument("1 page laid out")
        compose.onNodeWithContentDescription("Book contents").performClick()
        compose.onNodeWithText("VIEWER-TEST.TYPZ").assertIsDisplayed()
    }

    @Test
    fun recompilesWhenWidthChanges() {
        val uri = Uri.fromFile(writeTypz())
        var width by mutableStateOf(400.dp)
        compose.setContent {
            Box(Modifier.width(width)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument(laidOut(400))

        width = 320.dp
        waitForDocument(laidOut(320))
    }

    @Test
    fun appliesAndRemembersTextSize() {
        val uri = Uri.fromFile(writeTypz())
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument(laidOut(400))

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Text size: 70%").assertExists()
        compose.onNodeWithContentDescription("Text size").performSemanticsAction(SemanticsActions.SetProgress) {
            it(100f)
        }
        compose.onNodeWithText("Text size: 100%").assertExists()
        waitForDocument(laidOut(400, textSizePercent = 100))
        assertEquals(100, ReaderSettings(context).textSizePercent)
    }

    @Test
    fun startsAtTheRememberedTextSize() {
        ReaderSettings(context).textSizePercent = 120
        val uri = Uri.fromFile(writeTypz())
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument(laidOut(400, textSizePercent = 120))
    }

    @Test
    fun showsTypstErrorsInPlaceOfTheDocument() {
        val uri = Uri.fromFile(writeTypz(mainTyp = "= Title\n\n#undefined-function()"))
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("main.typ:3:2: unknown variable", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Typst errors:", substring = true).assertExists()
    }

    @Test
    fun promptsToOpenFileWhenNoneIsOpen() {
        compose.setContent { TypzViewer(initialUri = null) }
        compose.onNodeWithText("Open file").assertExists()
        compose.onNodeWithContentDescription("Menu").assertDoesNotExist() // no toolbar
    }

    @Test
    fun showsFileNameAndMenuInToolbar() {
        val uri = Uri.fromFile(writeTypz())
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument("1 page laid out")
        compose.onNodeWithText("viewer-test.typz").assertExists()
        compose.onNodeWithContentDescription("Menu").assertExists()
        compose.onNodeWithText("Open file").assertDoesNotExist() // the prompt is gone
        compose.onNodeWithContentDescription("Show warnings").assertDoesNotExist()
    }

    @Test
    fun menuOffersOpenFileAndOpensSettings() {
        val uri = Uri.fromFile(writeTypz())
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument("1 page laid out")
        compose.onNodeWithText("Open File").assertDoesNotExist()

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Open File").assertExists()
        compose.onNodeWithText("Settings").performClick()

        compose.onNodeWithText("Open File").assertDoesNotExist() // the menu closed
        compose.onNodeWithText("Settings").assertExists() // now the dialog's title
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test
    fun prefersDocumentTitleToFileName() {
        val uri = Uri.fromFile(writeTypz(mainTyp = "#set document(title: \"My Book\")\nHello"))
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument("1 page laid out")
        compose.onNodeWithText("My Book").assertExists()
        compose.onNodeWithText("viewer-test.typz").assertDoesNotExist()
    }

    @Test
    fun showsWarningsOnRequest() {
        val uri = Uri.fromFile(writeTypz(mainTyp = "#set text(font: \"No Such Font\")\nHello"))
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument("1 page laid out")
        compose.onNodeWithText("unknown font family", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show warnings").performClick()
        compose.onNodeWithText("unknown font family: no such font", substring = true).assertExists()
    }

    @Test
    fun reopensDocumentAfterStateRestoration() {
        val uri = Uri.fromFile(writeTypz())
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument(laidOut(400))

        // Like an activity recreation: remembered state (and the open bundle) is gone, saved
        // state comes back, and the viewer must reopen and recompile the document.
        restoration.emulateSavedInstanceStateRestore()
        waitForDocument(laidOut(400))
    }
}
