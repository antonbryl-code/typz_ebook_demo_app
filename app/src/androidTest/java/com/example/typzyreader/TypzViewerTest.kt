package com.example.typzyreader

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private fun writeTypz(
        mainTyp: String = """
            #let width = sys.inputs.at("ebook-width", default: none)
            #set page(width: eval(width), height: auto) if width != none
            Hello
        """.trimIndent(),
    ): File {
        val files = mapOf(
            "typst.toml" to "[project]\nentrypoint = \"main.typ\"\n",
            "main.typ" to mainTyp,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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

    @Test
    fun recompilesWhenWidthChanges() {
        val uri = Uri.fromFile(writeTypz())
        var width by mutableStateOf(400.dp)
        compose.setContent {
            Box(Modifier.width(width)) { TypzViewer(initialUri = uri) }
        }
        // 400dp = 400 CSS px = 300pt, and the document lays out at exactly that width.
        waitForDocument("1 page laid out for a 300.00pt wide view: 300 × ")

        width = 320.dp
        waitForDocument("1 page laid out for a 240.00pt wide view: 240 × ")
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
        compose.onNodeWithContentDescription("Open file").assertDoesNotExist() // no toolbar
    }

    @Test
    fun showsFileNameAndOpenButtonInToolbar() {
        val uri = Uri.fromFile(writeTypz())
        compose.setContent {
            Box(Modifier.width(400.dp)) { TypzViewer(initialUri = uri) }
        }
        waitForDocument("1 page laid out")
        compose.onNodeWithText("viewer-test.typz").assertExists()
        compose.onNodeWithContentDescription("Open file").assertExists()
        compose.onNodeWithText("Open file").assertDoesNotExist() // the prompt is gone
        compose.onNodeWithContentDescription("Show warnings").assertDoesNotExist()
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
        waitForDocument("1 page laid out for a 300.00pt wide view")

        // Like an activity recreation: remembered state (and the open bundle) is gone, saved
        // state comes back, and the viewer must reopen and recompile the document.
        restoration.emulateSavedInstanceStateRestore()
        waitForDocument("1 page laid out for a 300.00pt wide view")
    }
}
