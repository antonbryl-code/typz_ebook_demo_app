package com.example.typzyreader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GoogleFontsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun findsTheTrueTypeFilesOfAStylesheet() {
        val css = """
            /* latin */
            @font-face {
              font-family: 'Inter';
              font-style: normal;
              font-weight: 400;
              src: url(https://fonts.gstatic.com/s/inter/v20/a.ttf) format('truetype');
            }
            @font-face {
              font-family: 'Inter';
              font-style: italic;
              src: url(https://fonts.gstatic.com/s/inter/v20/b.ttf) format('truetype');
            }
        """.trimIndent()
        assertEquals(
            listOf("https://fonts.gstatic.com/s/inter/v20/a.ttf", "https://fonts.gstatic.com/s/inter/v20/b.ttf"),
            GoogleFonts.fontUrls(css),
        )
    }

    @Test
    fun ignoresFilesFromElsewhereOrInOtherFormats() {
        val css = "src: url(https://example.com/evil.ttf); src: url(https://fonts.gstatic.com/s/inter/a.woff2);"
        assertEquals(emptyList<String>(), GoogleFonts.fontUrls(css))
    }

    @Test
    fun servesCachedFamiliesWithoutDownloading() {
        // Cached under the family's name in lowercase with dashes; no network is involved.
        val dir = folder.newFolder("noto-serif")
        dir.resolve("0.ttf").writeBytes(byteArrayOf(1))
        dir.resolve("1.ttf").writeBytes(byteArrayOf(2))
        val fonts = GoogleFonts(folder.root).fetch("Noto Serif")
        assertEquals(2, fonts.size)
        assertArrayEquals(byteArrayOf(1), fonts[0])
        assertArrayEquals(byteArrayOf(2), fonts[1])
    }
}
