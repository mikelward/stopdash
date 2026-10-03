package app.stopdash.domain

import java.time.Instant

/**
 * A disruption reported for a whole stop (TfL `/StopPoint/{id}/Disruption`) — a station
 * closure, a moved stop, a blocked entrance. Distinct from a [LineStatus]: a stop can be
 * closed while its lines run normally elsewhere, so a closed stop would otherwise show
 * valid-looking departures you can't actually catch there (SPEC *Disruptions* / D3, the
 * quietly-wrong failure by a different path).
 *
 * [description] is TfL's own human-readable text. StopDash surfaces any stop disruption
 * rather than trying to tell a full closure from a lift outage — TfL's closure fields are
 * coarse and the data is often absent, so surfacing what it does report beats hiding it;
 * classifying severity is left to the full disruptions work (Phase 3).
 *
 * [validFrom]/[validTo] are TfL's `fromDate`/`toDate`: TfL publishes a scheduled closure ahead of
 * its window (a stop "closed" 10:00–15:00 is reported from the early morning), so only a notice
 * [isActiveAt] the render clock is shown. A missing bound is open-ended — a notice TfL didn't
 * date is surfaced rather than hidden (SPEC principle 1).
 */
data class StopDisruption(
    val description: String,
    val validFrom: Instant? = null,
    val validTo: Instant? = null,
) {
    /** Whether this notice's window covers [now]: started (inclusive) and not yet ended. */
    fun isActiveAt(now: Instant): Boolean =
        (validFrom == null || !now.isBefore(validFrom)) && (validTo == null || now.isBefore(validTo))
}

/**
 * Turns a raw TfL stop-disruption [rawDescription] into the **member-independent** form stored on
 * the row: TfL's escaped and raw line breaks become real newlines, each line is trimmed, and blank
 * lines are dropped. Bus disruption text arrives with **literal** backslash-n (the two characters
 * `\` and `n`) and runs of indent spaces — "Bus Stop Closed\n    Please use the next stop" — which
 * render as visible `\n` noise unless converted; a station notice may instead carry real CR/LF.
 *
 * The place-name strip is deliberately **not** done here. It depends on the stop's own name, so
 * doing it before the near-me fold ([DepartureRows.nearbyDeduped]) would give two members of one
 * hub that share a name-led notice ("King's Cross St. Pancras Underground Station: …") different
 * cleaned text — one member's name matches and strips, the other's does not — and the fold, which
 * keys on this text, would then render the shared notice as two cards (Codex). Normalizing alone
 * keeps the text identical across members, so the fold still collapses them; the name is stripped
 * later, per the shown row, by [cleanDisruptionBody].
 */
fun normalizeDisruptionText(rawDescription: String): String =
    rawDescription.replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\r", "\n")
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

/**
 * Prepares a stop-disruption description for **display** beneath a separate place heading (the
 * stop or interchange name): normalizes it ([normalizeDisruptionText], idempotent on already-
 * normalized text), then drops a leading run that merely repeats the place's own name so the
 * heading isn't the body's first words said twice. Applied to the **shown** row after the fold,
 * not before it, so the fold's identity stays member-independent (see [normalizeDisruptionText]).
 *
 * Whether TfL leads with the name is **not** decided by mode — a tube closure usually opens
 * with "&lt;Station&gt; Underground Station: …" while a bus "Bus Stop Closed" notice never
 * names the stop — so the name is stripped by **detection, not by mode**: a prefix is removed
 * only when the text actually starts with a form of [stopName], [hubName], or any of [aliases]
 * (the bare name, or the name plus one of TfL's station-type words, followed by a punctuation
 * separator or a line break). [aliases] are the interchange's other member-station spellings, so a
 * King's Cross notice that leads with a *different* member's name than the watched stop
 * ("St Pancras International: …" while you watch "King's Cross St. Pancras") is still stripped — no
 * one name catches the interchange's dozen spellings, but the union of its members does. The bus
 * case, whose body names no stop, is returned unchanged and relies on the heading to supply the
 * name. The strip never empties the notice: a text that is only the name is kept as-is, so a
 * heading-plus-nothing card is never produced.
 */
fun cleanDisruptionBody(
    rawDescription: String,
    stopName: String,
    hubName: String = "",
    aliases: List<String> = emptyList(),
): String {
    val text = normalizeDisruptionText(rawDescription)
    if (text.isEmpty()) return text
    val stripped = stripLeadingPlaceName(text, stopName = stopName, hubName = hubName, aliases = aliases)
    return stripped.ifBlank { text }
}

/** TfL's station-type words, longest first so "Underground Station" wins over "Station". */
private val STATION_TYPES = listOf(
    "Underground Station",
    "Overground Station",
    "DLR Station",
    "Rail Station",
    "Bus Station",
    "Coach Station",
    "Tram Stop",
    "Station",
)

/** The punctuation TfL puts between a leading station name and the notice body. */
private const val SEPARATORS = ":.,;–—-"

/** **Horizontal** whitespace only — spaces and tabs, never a newline (see the strip regex). */
private const val H = "[ \\t]"

/** Optional same-line whitespace/punctuation between a name's word tokens — King's/Kings, St./St. */
private const val NAME_TOKEN_SEP = "[ \\t.,'’&/-]*"

/**
 * Strips a leading "&lt;place name&gt;: " / "&lt;place name&gt; Underground Station: " run when
 * [text] begins with one. Tries [hubName], [stopName], and every [aliases] entry (longest first,
 * so an interchange name that contains a member's is preferred), so any of an interchange's member
 * spellings can match.
 *
 * Matching is **spelling-tolerant**: TfL spells one station many ways — "King's Cross St. Pancras"
 * vs "Kings Cross St Pancras" vs "King's Cross St. Pancras International" — so the name is matched
 * by its alphanumeric word tokens in order with a flexible separator ([NAME_TOKEN_SEP]) between
 * them, rather than character-for-character. A real boundary is still required after the name — a
 * punctuation separator, or a station-type word then end-of-line — so "Kilburn" never strips the
 * front off a "Kilburn Park …" notice, and a name that runs straight into more prose (only a space
 * after it) is left alone.
 *
 * **Whitespace between the name, its type suffix, and the separator is same-line only** ([H], not
 * `\s`): a newline is not that whitespace, so a bare name on its own line above the body does not
 * pull the body's first word up as a type suffix — "Victoria\nStation closed" is left intact rather
 * than mangled to "closed" (Codex). Only a separator run, or a type suffix immediately before the
 * line break, is a boundary; the trailing `\s*`/`\n` then eats the break before the body.
 *
 * When the precise match misses, a **colon-label fallback** ([colonLabelStripEnd]) catches a
 * name-led "&lt;label&gt;: …" whose spelling the tokens didn't line up with, by comparing the
 * whole leading label to the name reduced to letters — so King's Cross St. Pancras, spelled a dozen
 * ways, still matches. It compares the full letters, not just initials, so two different stations
 * that merely share an initial never collapse.
 *
 * Best-effort: [text] is returned unchanged when nothing matches (the heading still carries the
 * name), so a spelling neither path covers only misses the strip, never mangles the notice.
 */
private fun stripLeadingPlaceName(
    text: String,
    stopName: String,
    hubName: String,
    aliases: List<String>,
): String {
    // Each also in its matching form: a notice may lead with "Hammersmith" where the stop is
    // "Hammersmith (H&C)" ([matchStopName]).
    val names = (listOf(hubName, stopName) + aliases)
        .flatMap { listOf(it, matchStopName(it)) }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
    val types = STATION_TYPES.joinToString("|") { Regex.escape(it) }
    for (name in names) {
        val namePattern = flexibleNamePattern(name) ?: continue
        // Two shapes count as a leading name, both keeping the name/type/separator on one line:
        //   a) name (+ same-line type) then a same-line separator run — "Bank: …", "X Station: …";
        //      the trailing `\s*` then eats the space or newline before the body.
        //   b) name + a same-line type then the line's end — a type immediately before a newline (or
        //      end of text) is boundary enough — "X Underground Station\n<body>".
        // A newline never satisfies the intra-name whitespace, so a bare name line with no type or
        // separator ("Victoria\nStation closed") matches neither and is left intact.
        val end = Patterns.of(
            "^$H*$namePattern(?:$H+(?:$types))?$H*[$SEPARATORS]+\\s*" +
                "|^$H*$namePattern$H+(?:$types)$H*(?:\\r?\\n\\s*|$)",
            ignoreCase = true,
        ).find(text)?.let { it.range.last + 1 }
            ?: colonLabelStripEnd(text, letterKey(name))
            ?: continue
        val remainder = text.substring(end).trimStart()
        if (remainder.isNotBlank()) return remainder
    }
    return text
}

/**
 * When [text] opens with "&lt;label&gt;: " on its first line and the label reduces to the same
 * letters as [nameKey] (see [letterKey]), returns the offset just past the colon and its trailing
 * whitespace — the run to strip. Null when there is no such colon, or the label's letters differ.
 *
 * This is the spelling-tolerant fallback for TfL's most-variably-spelled interchanges (King's Cross
 * St. Pancras): the label and the name are each reduced to their letters with type words dropped,
 * so punctuation, case and "Station"/"Underground" noise fall away, and their **full** letter runs
 * are compared — never just initials, so "Brixton" is not stripped against a "Bank" heading.
 */
private fun colonLabelStripEnd(text: String, nameKey: String): Int? {
    if (nameKey.isEmpty()) return null
    val firstLineEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
    val colon = text.indexOf(':')
    if (colon < 0 || colon >= firstLineEnd) return null
    if (letterKey(text.substring(0, colon)) != nameKey) return null
    var end = colon + 1
    while (end < text.length && text[end].isWhitespace()) end++
    return end
}

/**
 * Reduces a place name or a leading label to a spelling-independent key: TfL's station-type words
 * are dropped, then everything but letters and digits is removed and the rest upper-cased. So
 * "King's Cross St. Pancras", "Kings Cross St Pancras" and "KINGS CROSS ST PANCRAS STATION" all key
 * to `KINGSCROSSSTPANCRAS`.
 */
private fun letterKey(name: String): String =
    TYPE_WORD.replace(name, " ").filter { it.isLetterOrDigit() }.uppercase()

/** A name's alphanumeric word tokens, for [flexibleNamePattern]. */
private val WORD_TOKEN = Regex("[A-Za-z0-9]+")

/** The station-type words dropped by [letterKey] before comparing a label to a name. */
private val TYPE_WORD =
    Regex("(?i)\\b(?:underground|overground|dlr|rail|coach|tram|station)\\b")

/**
 * A regex fragment matching [name] tolerant of TfL's spelling variance: the name's alphanumeric
 * word tokens in order, separated by [NAME_TOKEN_SEP] (optional punctuation/whitespace), so
 * "King's Cross St. Pancras" also matches "Kings Cross St Pancras". Null when the name has no word
 * tokens (nothing to anchor on).
 */
private fun flexibleNamePattern(name: String): String? {
    val tokens = WORD_TOKEN.findAll(name).map { Regex.escape(it.value) }.toList()
    if (tokens.isEmpty()) return null
    return tokens.joinToString(NAME_TOKEN_SEP)
}
