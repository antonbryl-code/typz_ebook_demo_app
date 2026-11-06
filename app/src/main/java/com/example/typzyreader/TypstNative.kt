package com.example.typzyreader

/**
 * Kotlin side of the Rust library built from app/src/main/rust.
 * Native failures surface as IllegalArgumentException (bad input) or RuntimeException (panics).
 */
internal object TypstNative {
    init {
        System.loadLibrary("typzyreader")
    }

    /**
     * Copies the files into a native bundle and returns its handle; free it with [closeBundle].
     * Downloaded packages are kept in [packageCache]; null allows only bundled packages.
     */
    external fun openBundle(
        paths: Array<String>,
        contents: Array<ByteArray>,
        entrypoint: String,
        packageCache: String?,
    ): Long
    external fun closeBundle(handle: Long)
    /**
     * The `entrypoint` in the `[project]` section of a typst.toml. Throws [TypzFormatException]
     * if the manifest is malformed or names none.
     */
    external fun manifestEntrypoint(manifest: ByteArray): String
    /**
     * The `format` in the `[tool.typzy-reader]` section of a typst.toml, or the default format if
     * it names none. Throws [TypzFormatException] if the manifest is malformed.
     */
    external fun manifestFormat(manifest: ByteArray): String
    /**
     * The `cover-img` in the `[tool.typzy-reader]` section of a typst.toml, as a normalized path
     * from the root of the .typz; null if it names none or the path can't be inside the .typz.
     * Throws [TypzFormatException] if the manifest is malformed.
     */
    external fun manifestCoverImg(manifest: ByteArray): String?
    external fun bundleEntrypoint(handle: Long): String
    external fun bundleFileCount(handle: Long): Int
    /** Size in bytes of the file at [path], or -1 if the bundle has no such file. */
    external fun bundleFileSize(handle: Long, path: String): Long
    /**
     * Compiles the bundle, offering [widthPt], [marginPt] and, unless it's 0, [heightPt] through
     * `sys.inputs`, and renders it.
     * Returns `[sizes: FloatArray, svgs: Array<String>, textLayers: Array<String>, warnings: String,
     * null, title: String?, sections: Array<String>, sectionPositions: FloatArray]`, sizes being
     * `width0, height0, width1, height1, ..` in points, and sectionPositions `page0, y0, page1, y1,
     * ..` in page indices and points. If packages must be downloaded first, returns `[null, null,
     * null, null, missing: Array<String>, null, null, null]` with specs like `@preview/cetz:0.3.4`
     * instead. Throws [TypstCompileException] on errors.
     */
    external fun compile(handle: Long, widthPt: Float, marginPt: Float, heightPt: Float): Array<Any?>

    /** Unpacks a package's `.tar.gz` (as served by Typst Universe) into [cacheDir]. */
    external fun installPackage(cacheDir: String, spec: String, archive: ByteArray)
}
