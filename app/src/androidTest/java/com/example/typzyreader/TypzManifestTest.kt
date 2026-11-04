package com.example.typzyreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun readsEntrypointFromProjectSection() {
        val contents = read(
            "[package]\nentrypoint = \"lib.typ\"\n\n[project]\nentrypoint = \"src/book.typ\"\n",
            "src/book.typ" to "= Hello",
        )
        assertEquals("src/book.typ", contents.entrypoint)
        assertEquals(setOf("typst.toml", "src/book.typ"), contents.files.keys)
    }

    @Test
    fun opensAndCompilesTheEntrypoint() {
        val contents = read("[project]\nentrypoint = \"main.typ\"", "main.typ" to "= Hello", "other.typ" to "#panic()")
        TypstBundle.open(contents).use { bundle ->
            assertEquals("main.typ", bundle.entrypoint)
            assertEquals(1, bundle.compile(300f, 12f).pages.size)
        }
    }

    @Test
    fun rejectsManifestsWithoutEntrypoint() {
        for (manifest in listOf("", "[project]\nname = \"x\"", "[package]\nentrypoint = \"a.typ\"", "[project\n", "[project]\nentrypoint = 1")) {
            assertThrows(manifest, TypzFormatException::class.java) { read(manifest, "a.typ" to "") }
        }
    }

    private fun format(manifest: String) = TypstNative.manifestFormat(manifest.toByteArray())

    @Test
    fun readsFormatFromToolSection() {
        assertEquals("book", format("[tool.typzy-reader]\nformat = \"book\""))
        assertEquals("pageless", format("[tool.typzy-reader]\nformat = \"pageless\""))
        assertEquals("other", format("[tool.typzy-reader]\nformat = \"other\""))
    }

    @Test
    fun defaultsFormatToPageless() {
        assertEquals("pageless", format("[project]\nentrypoint = \"a.typ\""))
        assertEquals("pageless", format("[tool.typzy-reader]\nother = 1"))
        assertEquals("pageless", format("[tool.other]\nformat = \"book\""))
        assertEquals("pageless", format("[project]\nformat = \"book\""))
    }

    @Test
    fun showsOtherFormatValuesAsToml() {
        assertEquals("3", format("[tool.typzy-reader]\nformat = 3"))
    }

    @Test
    fun rejectsMalformedManifestWhenReadingFormat() {
        assertThrows(TypzFormatException::class.java) { format("[tool\n") }
    }

    @Test
    fun findsFormatProblemAfterOtherEntries() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in listOf("a.typ" to "= A", "typst.toml" to "[tool.typzy-reader]\nformat = \"book\"")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        assertEquals(
            "The \"book\" format is not implemented yet.",
            TypzArchive.formatProblem(ByteArrayInputStream(out.toByteArray())),
        )
    }

    @Test
    fun readsCoverImageNamedByManifest() {
        val contents = read(
            "[project]\nentrypoint = \"main.typ\"\n[tool.typzy-reader]\ncover-img = \"./images/cover.png\"\n",
            "main.typ" to "= Hello",
            "images/cover.png" to "png",
        )
        assertEquals("images/cover.png", contents.coverImg)
        assertArrayEquals("png".toByteArray(), contents.coverImage)
    }

    @Test
    fun hasNoCoverImageWithoutAUsableOne() {
        val project = "[project]\nentrypoint = \"main.typ\"\n"
        for (extra in listOf("", "[tool.typzy-reader]\nformat = \"pageless\"", "[tool.typzy-reader]\ncover-img = 3", "[tool.typzy-reader]\ncover-img = \"../x.png\"", "[tool.typzy-reader]\ncover-img = \"missing.png\"")) {
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
        TypstBundle.open(read("[project]\nentrypoint = \"nope.typ\"", "main.typ" to "")).close()
    }
}
