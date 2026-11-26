package com.example.typzyreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [TypzArchive.read] with the native typst.toml parsing. */
@RunWith(AndroidJUnit4::class)
class TypzManifestTest {
    private fun read(manifest: String, vararg files: Pair<String, String>): TypzContents {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in listOf("typst.toml" to manifest) + files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return TypzArchive.read(ByteArrayInputStream(out.toByteArray()))
    }

    /** The section every manifest must have. */
    private val section = "\n[tool.typz-ebook]\n"

    @Test
    fun readsEntrypointFromProjectSection() {
        val contents = read(
            "[package]\nentrypoint = \"lib.typ\"\n\n[project]\nentrypoint = \"src/book.typ\"\n" + section,
            "src/book.typ" to "= Hello",
        )
        assertEquals("src/book.typ", contents.entrypoint)
        assertEquals(setOf("typst.toml", "src/book.typ"), contents.files.keys)
    }

    @Test
    fun opensAndCompilesTheEntrypoint() {
        val contents = read("[project]\nentrypoint = \"main.typ\"" + section, "main.typ" to "= Hello", "other.typ" to "#panic()")
        TypstBundle.open(contents).use { bundle ->
            assertEquals("main.typ", bundle.entrypoint)
            assertEquals(1, bundle.compile(300f, 420f).pages.size)
        }
    }

    @Test
    fun rejectsManifestsWithoutEntrypoint() {
        for (manifest in listOf("", "[project]\nname = \"x\"", "[package]\nentrypoint = \"a.typ\"", "[project\n", "[project]\nentrypoint = 1")) {
            assertThrows(manifest, TypzFormatException::class.java) { read(manifest + section, "a.typ" to "") }
        }
    }

    @Test
    fun requiresTheTypzEbookSection() {
        val project = "[project]\nentrypoint = \"main.typ\"\n"
        for (manifest in listOf(project, project + "[tool.typzy-reader]\ncover-img = \"c.png\"", project + "[tool.other]")) {
            val error = assertThrows(manifest, TypzFormatException::class.java) { read(manifest, "main.typ" to "") }
            assertEquals("No [tool.typz-ebook] section in typst.toml", error.message)
        }
        assertThrows(TypzFormatException::class.java) { read(project + "[tool]\ntypz-ebook = 1", "main.typ" to "") }
        assertEquals("main.typ", read(project + "[tool.typz-ebook]", "main.typ" to "").entrypoint)
    }

    @Test
    fun ignoresTheFormerFormatSetting() {
        val project = "[project]\nentrypoint = \"main.typ\"\n"
        for (format in listOf("book", "pageless", "other")) {
            assertEquals("main.typ", read(project + "[tool.typz-ebook]\nformat = \"$format\"", "main.typ" to "").entrypoint)
        }
    }

    private fun manifestProblem(vararg entries: Pair<String, String>): String? {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return TypzArchive.manifestProblem(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun findsManifestProblemsAfterOtherEntries() {
        assertNull(manifestProblem("a.typ" to "= A", "typst.toml" to "[tool.typz-ebook]"))
        assertEquals(
            "No [tool.typz-ebook] section in typst.toml",
            manifestProblem("a.typ" to "= A", "typst.toml" to "[project]\nentrypoint = \"a.typ\""),
        )
        assertTrue(manifestProblem("typst.toml" to "[tool\n")!!.startsWith("Malformed typst.toml"))
    }

    @Test
    fun readsRequiredFonts() {
        val project = "[project]\nentrypoint = \"main.typ\"\n"
        val listed = read(project + "[tool.typz-ebook]\nrequired-fonts = [\"Inter\", \"Noto Serif\", \"Inter\", 3]", "main.typ" to "")
        assertEquals(listOf("Inter", "Noto Serif"), listed.requiredFonts)
        assertEquals(emptyList<String>(), read(project + section, "main.typ" to "").requiredFonts)
    }

    @Test
    fun readsCoverImageNamedByManifest() {
        val contents = read(
            "[project]\nentrypoint = \"main.typ\"\n[tool.typz-ebook]\ncover-img = \"./images/cover.png\"\n",
            "main.typ" to "= Hello",
            "images/cover.png" to "png",
        )
        assertEquals("images/cover.png", contents.coverImg)
        assertArrayEquals("png".toByteArray(), contents.coverImage)
    }

    @Test
    fun hasNoCoverImageWithoutAUsableOne() {
        val project = "[project]\nentrypoint = \"main.typ\"\n"
        for (extra in listOf("[tool.typz-ebook]", "[tool.typz-ebook]\ncover-img = 3", "[tool.typz-ebook]\ncover-img = \"../x.png\"", "[tool.typz-ebook]\ncover-img = \"missing.png\"")) {
            assertNull(extra, read(project + extra, "main.typ" to "= Hello").coverImage)
        }
    }

    @Test
    fun defaultCoverIsA4Shaped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cover = context.getDrawable(R.drawable.default_cover)!!
        assertEquals(1.414f, cover.intrinsicHeight.toFloat() / cover.intrinsicWidth, 0.005f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEntrypointMissingFromArchive() {
        TypstBundle.open(read("[project]\nentrypoint = \"nope.typ\"" + section, "main.typ" to "")).close()
    }
}
