package com.example.typzyreader

import android.content.Context
import androidx.core.content.edit

/** The user's settings, kept between sessions. */
class ReaderSettings(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** How large documents appear, as a percentage of their printed size. */
    var textSizePercent: Int
        get() = nearestTextSize(preferences.getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE))
        set(value) = preferences.edit { putInt(KEY_TEXT_SIZE, value) }

    companion object {
        const val FILE_NAME = "settings"
        private const val KEY_TEXT_SIZE = "text_size_percent"

        const val MIN_TEXT_SIZE = 60
        const val MAX_TEXT_SIZE = 120
        const val TEXT_SIZE_STEP = 10
        const val DEFAULT_TEXT_SIZE = 80

        /** The selectable text size closest to [percent]. */
        fun nearestTextSize(percent: Int): Int {
            val steps = (percent.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE) - MIN_TEXT_SIZE + TEXT_SIZE_STEP / 2) / TEXT_SIZE_STEP
            return MIN_TEXT_SIZE + steps * TEXT_SIZE_STEP
        }
    }
}
