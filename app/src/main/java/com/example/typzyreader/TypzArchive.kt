package com.example.typzyreader

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

class TypzFormatException(message: String) : IOException(message)

/** The formats a .typz can name in its manifest's `format`, and how they're laid out. */
enum class TypzFormat(val value: String) {
    /** Reflowed to the view's width, without page breaks of its own (see [PageMode.PAGELESS]). */
    PAGELESS("pageless"),

    /** Pages sized for the view, laid out one of three ways (see [ViewGeometry.bookLayout]). */
    BOOK("book");

    companion object {
        /** The format a manifest's `format` value names, null if it's none of them. */
        fun of(value: String): TypzFormat? = entries.find { it.value == value }
    }
}

/**
 * Every file of a .typz keyed by its path inside the zip, plus the entrypoint to compile and the
 * path of the cover image, if the manifest names one.
 */
class TypzContents(
    val entrypoint: String,
    val files: Map<String, ByteArray>,
    val coverImg: String? = null,
    /** Font families the manifest says the document needs, to be fetched if the device lacks them. */
    val requiredFonts: List<String> = emptyList(),
) {
    /**
     * The undecoded cover image. Null if the book has none of its own, or the file named by the
     * manifest isn't in the .typz, either of which calls for [R.drawable.default_cover].
     */
    val coverImage: ByteArray? get() = coverImg?.let(files::get)
}

/**
 * Reader for .typz files: zip archives with the Typst sources at their root (and in
 * subfolders), and a typst.toml manifest there whose `[project]` section names the
 * `entrypoint` to compile, and whose optional `[tool.typzy-reader]` section the `format`, the
 * `cover-img` and the `required-fonts`.
 */
object TypzArchive {
    const val MANIFEST_PATH = "typst.toml"

    /** .typz files are untrusted, so cap the total uncompressed size to guard against zip bombs. */
    const val MAX_TOTAL_SIZE = 256L * 1024 * 1024

    /**
     * Reads all files of the .typz, and the entrypoint and cover image from its typst.toml. Whether
     * the entrypoint exists is checked when the contents are opened as a [TypstBundle].
     */
    fun read(input: InputStream, maxTotalSize: Long = MAX_TOTAL_SIZE): TypzContents {
        val files = readEntries(input, maxTotalSize)
        val manifest = files[MANIFEST_PATH]
            ?: throw TypzFormatException("$MANIFEST_PATH not found at the root of the .typz file")
        return TypzContents(
            TypstNative.manifestEntrypoint(manifest),
            files,
            TypstNative.manifestCoverImg(manifest),
            TypstNative.manifestRequiredFonts(manifest).toList(),
        )
    }

    /**
     * The format the .typz's manifest names, as written there (see [TypzFormat.of]); "pageless"
     * if it names none, or if there's no manifest, which [read] reports.
     */
    fun format(input: InputStream, maxTotalSize: Long = MAX_TOTAL_SIZE): String {
        val manifest = readManifest(input, maxTotalSize) ?: return TypzFormat.PAGELESS.value
        return TypstNative.manifestFormat(manifest)
    }

    /** Why a document in [format] can't be opened, null if it can. */
    fun formatProblem(format: String): String? =
        if (TypzFormat.of(format) != null) null else "\"$format\" is an unknown value for the \"format\" parameter."

    /** The manifest's bytes, read without keeping the other entries; null if there is none. */
    private fun readManifest(input: InputStream, maxTotalSize: Long): ByteArray? {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == MANIFEST_PATH) return readEntry(zip, maxTotalSize, maxTotalSize)
            }
        }
    }

    /** All files of the zip by path; directory entries are skipped. */
    internal fun readEntries(input: InputStream, maxTotalSize: Long = MAX_TOTAL_SIZE): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var remaining = maxTotalSize
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val data = readEntry(zip, remaining, maxTotalSize)
                remaining -= data.size
                if (files.put(entry.name, data) != null) {
                    throw TypzFormatException("Duplicate entry ${entry.name} in .typz file")
                }
            }
        }
        return files
    }

    /** Copies in chunks rather than readBytes() so an oversized entry fails before exhausting memory. */
    private fun readEntry(zip: ZipInputStream, remaining: Long, maxTotalSize: Long): ByteArray {
        val buffer = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream()
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > remaining) {
                throw TypzFormatException(".typz file exceeds ${maxTotalSize / (1024 * 1024)} MB uncompressed")
            }
            out.write(buffer, 0, n)
        }
    }
}
