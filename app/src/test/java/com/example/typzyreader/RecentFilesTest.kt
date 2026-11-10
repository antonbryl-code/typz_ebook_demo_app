package com.example.typzyreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentFilesTest {
    private fun recent(uri: String, title: String = uri) = RecentFile(uri, title, "$uri.typz", "/$uri.typz", cover = null)

    @Test
    fun putsTheOpenedFileFirst() {
        val (kept, dropped) = RecentFiles.withRecent(listOf(recent("a"), recent("b")), recent("c"))
        assertEquals(listOf("c", "a", "b"), kept.map { it.uri })
        assertEquals(emptyList<RecentFile>(), dropped)
    }

    @Test
    fun movesAReopenedFileToTheFrontWithItsNewDetails() {
        val (kept, dropped) = RecentFiles.withRecent(listOf(recent("a"), recent("b")), recent("b", title = "New"))
        assertEquals(listOf("b", "a"), kept.map { it.uri })
        assertEquals("New", kept.first().title)
        assertEquals(emptyList<RecentFile>(), dropped)
    }

    @Test
    fun keepsOnlyTheNewestThree() {
        val (kept, dropped) = RecentFiles.withRecent(listOf(recent("a"), recent("b"), recent("c")), recent("d"))
        assertEquals(listOf("d", "a", "b"), kept.map { it.uri })
        assertEquals(listOf("c"), dropped.map { it.uri })
    }

    @Test
    fun findsPathsOfFilesAndOfTheirStorage() {
        assertEquals("/data/user/0/app/cache/a.typz", RecentFiles.filePath("file", null, null, "/data/user/0/app/cache/a.typz"))
        val storage = "com.android.externalstorage.documents"
        assertEquals(
            "/storage/emulated/0/Download/book.typz",
            RecentFiles.filePath("content", storage, "primary:Download/book.typz", "/document/primary:Download/book.typz"),
        )
        assertEquals("/storage/1234-ABCD/Books/b.typz", RecentFiles.filePath("content", storage, "1234-ABCD:Books/b.typz", null))
        val downloads = "com.android.providers.downloads.documents"
        assertEquals(
            "/storage/emulated/0/Download/c.typz",
            RecentFiles.filePath("content", downloads, "raw:/storage/emulated/0/Download/c.typz", null),
        )
    }

    @Test
    fun hasNoPathWhenTheUriDoesNotTell() {
        assertNull(RecentFiles.filePath("content", "com.android.providers.downloads.documents", "msf:1234", null))
        assertNull(RecentFiles.filePath("content", "com.google.android.apps.docs.storage", "acc=1;doc=encoded", null))
        assertNull(RecentFiles.filePath("content", "com.example.provider", null, "/files/book.typz"))
    }
}
