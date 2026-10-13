package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * What the keyboard's Search key opens once a search has answered [pick]'s query in full (maintainer,
 * 2026-10-13): the top row, when it's the only row or the only one named exactly what was typed.
 * Anything else is a choice for the rider to make, so the key only closes the keyboard and leaves the
 * list in view. "To start with": a looser rule can follow once this one has been used.
 */
object SearchKey {
    /**
     * The row [entries] (ranked, as listed) opens for [query], or null. A complete postcode takes only
     * its own row: a lone near one is the bundle's guess at a postcode it doesn't hold, which TfL is
     * asked for next.
     */
    @WorkerThread
    fun pick(query: String, entries: List<SearchEntry>): SearchEntry? {
        val top = entries.firstOrNull() ?: return null
        val exact = entries.filter { StationMatcher.isExact(query, it.name) }
        return when {
            exact.size == 1 -> top.takeIf { exact[0] == it }
            exact.isNotEmpty() -> null
            entries.size == 1 && !UkPostcode.isComplete(query) -> top
            else -> null
        }
    }

    private val SearchEntry.name: String
        get() = when (this) {
            is SearchEntry.Stop -> match.name
            is SearchEntry.Place -> hit.name
        }
}
