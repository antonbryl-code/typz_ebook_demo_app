package com.example.typzyreader

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

class TypzFormatException(message: String) : IOException(message)

/** Every file of a .typz keyed by its path inside the zip, plus the entrypoint to compile. */
class TypzContents(val entrypoint: String, val files: Map<String, ByteArray>)

/**
 * Reader for .typz files: zip archives with the Typst sources at their root (and in
 * subfolders), and a typst.toml manifest there whose `[project]` section names the
 * `entrypoint` to compile.
 */
object TypzArchive {
    const val MANIFEST_PATH = "typst.toml"

    /** .typz files are untrusted, so cap the total uncompressed size to guard against zip bombs. */
    const val MAX_TOTAL_SIZE = 256L * 1024 * 1024

    /**
     * Reads all files of the .typz and the entrypoint from its typst.toml. Whether the entrypoint
     * exists is checked when the contents are opened as a [TypstBundle].
     */
    fun read(input: InputStream, maxTotalSize: Long = MAX_TOTAL_SIZE): TypzContents {
        val files = readEntries(input, maxTotalSize)
        val manifest = files[MANIFEST_PATH]
            ?: throw TypzFormatException("$MANIFEST_PATH not found at the root of the .typz file")
        return TypzContents(TypstNative.manifestEntrypoint(manifest), files)
    }

    /** All files of the zip by path; directory entries are skipped. */
    internal fun readEntries(input: InputStream, maxTotalSize: Long = MAX_TOTAL_SIZE): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var remaining = maxTotalSize
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // Copy in chunks rather than readBytes() so an oversized entry fails before exhausting memory.
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    remaining -= n
                    if (remaining < 0) {
                        throw TypzFormatException(".typz file exceeds ${maxTotalSize / (1024 * 1024)} MB uncompressed")
                    }
                    out.write(buffer, 0, n)
                }
                if (files.put(entry.name, out.toByteArray()) != null) {
                    throw TypzFormatException("Duplicate entry ${entry.name} in .typz file")
                }
            }
        }
        return files
    }
}
