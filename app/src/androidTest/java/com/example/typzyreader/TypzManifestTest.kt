package com.example.typzyreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEntrypointMissingFromArchive() {
        TypstBundle.open(read("[project]\nentrypoint = \"nope.typ\"", "main.typ" to "")).close()
    }
}
