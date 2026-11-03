package com.example.typzyreader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSettingsTest {
    @Test
    fun keepsSelectableTextSizes() {
        for (percent in listOf(60, 70, 80, 90, 100, 110, 120)) {
            assertEquals(percent, ReaderSettings.nearestTextSize(percent))
        }
    }

    @Test
    fun snapsOtherValuesToTheRange() {
        assertEquals(60, ReaderSettings.nearestTextSize(0))
        assertEquals(120, ReaderSettings.nearestTextSize(500))
        assertEquals(80, ReaderSettings.nearestTextSize(84))
        assertEquals(90, ReaderSettings.nearestTextSize(85))
    }

    @Test
    fun defaultsTo70Percent() {
        assertEquals(70, ReaderSettings.DEFAULT_TEXT_SIZE)
    }
}
