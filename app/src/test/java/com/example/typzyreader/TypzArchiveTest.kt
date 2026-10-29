package com.example.typzyreader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The zip side of [TypzArchive]; the typst.toml manifest is read natively (see TypzManifestTest). */
class TypzArchiveTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun readsAllFilesButDirectories() {
        val files = TypzArchive.readEntries(
            zip(
                "typst.toml" to "[project]\nentrypoint = \"main.typ\"",
                "main.typ" to "= Hello",
                "images/" to "",
                "images/cover.png" to "png",
            )
        )
        assertEquals(setOf("typst.toml", "main.typ", "images/cover.png"), files.keys)
        assertArrayEquals("= Hello".toByteArray(), files["main.typ"])
    }

    @Test(expected = TypzFormatException::class)
    fun failsWithoutManifest() {
        TypzArchive.read(zip("main.typ" to "= Hello"))
    }

    @Test(expected = TypzFormatException::class)
    fun failsWithManifestOnlyInSubfolder() {
        TypzArchive.read(zip("book/typst.toml" to "[project]\nentrypoint = \"main.typ\"", "book/main.typ" to ""))
    }

    @Test(expected = TypzFormatException::class)
    fun failsWhenTooLarge() {
        TypzArchive.readEntries(zip("main.typ" to "x".repeat(2000)), maxTotalSize = 1000)
    }
}
