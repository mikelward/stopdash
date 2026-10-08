package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * The type-to-find search behind *Lines…* (SPEC *Finding a line*): TfL's lines, searched on the device
 * as the rider types, by name ("Victoria", "Elizabeth") or by number ("299"). It scores each line
 * as the station search does ([StationMatcher]), its name then its TfL id, so the two searches agree.
 * A number is never matched loosely: "29" finds the 29, then 290 to 299, then the routes with 29 inside
 * (129, N29), but not the 209 or the 249 a fuzzy match would add; nor does "N25" bring up the N205.
 */
object LineSearch {
    /** How many matches are listed: more than a screen, since "2" alone matches dozens of routes. */
    const val DEFAULT_LIMIT = 40

    /**
     * The lines in [lines] matching [query], best first, at most [limit]: by how well they match, then
     * the shorter name (the 29 above the 290), then by name. A blank query matches none. A trailing
     * "line" is dropped, as the lines go by their names alone: "Elizabeth line" finds the Elizabeth,
     * "Victoria line" the Victoria.
     */
    @WorkerThread
    fun search(rawQuery: String, lines: List<LineRef>, limit: Int = DEFAULT_LIMIT): List<LineRef> {
        val query = rawQuery.trim().let { q -> TRAILING_LINE.replace(q, "").ifBlank { q } }
        // A route number, all digits or with a letter ("N25", "SL6"): never loose, or "N25" would bring
        // up the N205 by skipping its 0.
        val number = query.any { it.isDigit() }
        return lines
            .mapNotNull { line ->
                val tier = StationMatcher.tier(query, line.name, line.id)
                tier?.takeIf { !number || it != StationMatchTier.Fuzzy }?.let { line to it }
            }
            .sortedWith(compareBy({ it.second }, { it.first.name.length }, { it.first.name.lowercase() }))
            .take(limit)
            .map { it.first }
    }

    private val TRAILING_LINE = Regex("""\s+line$""", RegexOption.IGNORE_CASE)
}

/** The lines recently opened from *Lines…*, newest first: pure, so the rule is JVM-tested apart from storage. */
object RecentLines {
    /** How many are remembered, as for stations ([RecentStations.MAX]). */
    const val MAX = 8

    /** [current] with [opened] moved (or added) to the front, capped at [max]. A line is the same by its id. */
    fun add(current: List<LineRef>, opened: LineRef, max: Int = MAX): List<LineRef> =
        (listOf(opened) + current.filter { it.id != opened.id }).take(max)
}
