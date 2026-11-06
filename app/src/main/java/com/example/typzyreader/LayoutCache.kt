package com.example.typzyreader

/**
 * The most recently used [capacity] values by key, e.g. a document's compiled pages by
 * [ViewLayout], so that going back to a layout (turning the phone back, say) needs no recompile.
 * Values for other keys are dropped, least recently used first, to bound the memory they take.
 */
class LayoutCache<K, V>(private val capacity: Int) {
    // Access order: each get moves its entry to the end, so the first entry is the stalest.
    private val entries = LinkedHashMap<K, V>(capacity + 1, 0.75f, true)

    operator fun get(key: K): V? = entries[key]

    operator fun set(key: K, value: V) {
        entries[key] = value
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    val size: Int get() = entries.size
}
