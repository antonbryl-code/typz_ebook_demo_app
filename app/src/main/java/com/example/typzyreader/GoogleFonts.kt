package com.example.typzyreader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Font families from Google Fonts, for documents whose manifest lists them as `required-fonts`.
 * They come through the CSS API that web pages use, which serves static TrueType files for the
 * styles asked for, and are kept in [cacheDir] so each family is downloaded once. Blocks, so call
 * it off the main thread.
 */
class GoogleFonts(private val cacheDir: File) {
    /**
     * The font files of [family] (spelled as Google Fonts does, e.g. "Noto Serif"): its regular,
     * bold, italic and bold italic styles, those it has. Empty if Google Fonts doesn't have it.
     * @throws IOException if Google Fonts can't be reached.
     */
    fun fetch(family: String): List<ByteArray> {
        val dir = File(cacheDir, family.lowercase().replace(Regex("[^a-z0-9]+"), "-"))
        dir.listFiles { file -> file.extension == "ttf" }?.takeIf { it.isNotEmpty() }?.let { cached ->
            return cached.sortedBy { it.name }.map { it.readBytes() }
        }
        val urls = LinkedHashSet<String>()
        // One request per style: asking for a style the family lacks fails the whole request.
        for ((italic, weight) in STYLES) {
            stylesheet("$family:ital,wght@$italic,$weight")?.let { urls += fontUrls(it) }
        }
        // E.g. families with only lighter or heavier weights: whatever Google serves by default.
        if (urls.isEmpty()) stylesheet(family)?.let { urls += fontUrls(it) }
        if (urls.isEmpty()) return emptyList()
        val fonts = urls.map(::download)
        // Written to a new directory that's then moved into place, so a cut-off download isn't
        // later taken for the whole family.
        val partial = File(cacheDir, "${dir.name}.partial").apply { deleteRecursively(); mkdirs() }
        fonts.forEachIndexed { i, data -> File(partial, "$i.ttf").writeBytes(data) }
        if (!partial.renameTo(dir)) partial.deleteRecursively()
        return fonts
    }

    /** The stylesheet for [query] (a `family` value of the CSS API), null if Google Fonts refuses it. */
    private fun stylesheet(query: String): String? {
        val connection = open("$CSS_API?family=${URLEncoder.encode(query, "UTF-8")}")
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.use { it.readBytes().decodeToString() }
                // Unknown family, or a style it doesn't have.
                HttpURLConnection.HTTP_BAD_REQUEST, HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("Google Fonts answered HTTP $code for $query")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String): ByteArray {
        val connection = open(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Google Fonts answered HTTP ${connection.responseCode} for $url")
            }
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_FONT_SIZE) throw IOException("$url is larger than 32 MB")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "TypzyReader")
    }

    companion object {
        private const val CSS_API = "https://fonts.googleapis.com/css2"
        private const val MAX_FONT_SIZE = 32 * 1024 * 1024

        /** The styles fetched: (italic, weight) for regular, bold, italic and bold italic. */
        private val STYLES = listOf(0 to 400, 0 to 700, 1 to 400, 1 to 700)

        private val FONT_URL = Regex("""url\((https://fonts\.gstatic\.com/[^)\s]+\.ttf)\)""")

        /** The TrueType files a stylesheet points to; only Google's own font host is trusted. */
        internal fun fontUrls(css: String): List<String> = FONT_URL.findAll(css).map { it.groupValues[1] }.toList()
    }
}
