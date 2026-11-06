package com.example.typzyreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutCacheTest {
    private val portrait = ViewLayout(300f, 0.75f, 600f, PageMode.SINGLE)
    private val landscape = ViewLayout(600f, 0.75f, 848f, PageMode.SCROLLED)

    @Test
    fun keepsLayoutsToGoBackTo() {
        val cache = LayoutCache<ViewLayout, String>(4)
        cache[portrait] = "portrait pages"
        cache[landscape] = "landscape pages"
        assertEquals("portrait pages", cache[portrait])
        assertEquals("landscape pages", cache[ViewLayout(600f, 0.75f, 848f, PageMode.SCROLLED)])
        assertNull(cache[ViewLayout(320f, 0.75f, 600f, PageMode.SINGLE)])
    }

    @Test
    fun dropsTheLeastRecentlyUsedBeyondItsCapacity() {
        val cache = LayoutCache<Int, String>(2)
        cache[1] = "a"
        cache[2] = "b"
        cache[1] // used again, so 2 is now the stalest
        cache[3] = "c"
        assertEquals(2, cache.size)
        assertEquals("a", cache[1])
        assertNull(cache[2])
        assertEquals("c", cache[3])
    }
}
