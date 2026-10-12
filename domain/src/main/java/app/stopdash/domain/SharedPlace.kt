package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * What to search for when another app shares a place with StopDash (SPEC *Sharing a place in*):
 * the place's name, read from the shared text without sending anything anywhere.
 *
 * Google Maps shares a place as its name, sometimes its address on the next line, and a short link
 * (`https://maps.app.goo.gl/…`); other apps share a name, a line of text, or a link alone. The search
 * is for the first line that names something — not a link, and not blank — with any link run on to
 * the end of it dropped. Following the link to the exact spot would ask Google, which nothing here
 * does (`TODO.md`). A share with no such line ([subject] aside, a title some apps send apart from the
 * text) has nothing to search for, and gives null. Shared text can be any length, so both readings
 * run off the main thread.
 */
object SharedPlace {
    /** What a shared or opened place asks for: a search ([Search]), or a point to route to ([Point]). */
    sealed interface Ask {
        data class Search(val query: String) : Ask

        /** A spot by its [coordinate], and the [label] it came with, if any ("Tate Modern"). */
        data class Point(val coordinate: Coordinates, val label: String?) : Ask
    }

    /** Longer than any place name; past it the text is a message, not a name, and is cut at a word. */
    const val MAX_LENGTH = 80

    /**
     * As much of what was shared as is kept, read, or saved across a recreation: far more than a name,
     * an address and a link take, so a long share is cut here before any of it is held.
     */
    const val MAX_SHARED = 2_000

    /** [text] cut to [MAX_SHARED], copying no more than that: what is taken off a share. */
    fun bounded(text: CharSequence?): String? =
        text?.let { it.subSequence(0, minOf(it.length, MAX_SHARED)).toString() }

    @WorkerThread
    fun queryOf(text: String?, subject: String? = null): String? =
        sequenceOf(text, subject)
            .filterNotNull()
            .flatMap { it.lineSequence() }
            .map { URL.replace(it, " ").replace(SPACES, " ").trim().trim('"', '“', '”').trim() }
            .firstOrNull { line -> line.any(Char::isLetterOrDigit) }
            ?.let(::capped)

    /**
     * What a `geo:` link opened with StopDash asks for (RFC 5870, and Android's `?q=` form): the point
     * it names — `geo:51.5,-0.12`, or `geo:0,0?q=51.5,-0.12(Tate Modern)` with its label — or, where it
     * names an address or a place by text (`geo:0,0?q=Tate+Modern`, as Contacts and Calendar send), a
     * search for that text ([queryOf]). A query beside a real point (`geo:51.5,-0.12?q=cafe`) searches
     * the text, as a maps app would, since the point is only where to look. Null for anything else, or
     * a point off the globe; `0,0` is "no point", as Android's maps intents use it.
     */
    @WorkerThread
    fun fromGeo(uri: String?): Ask? {
        if (uri == null || !uri.startsWith(GEO, ignoreCase = true)) return null
        val rest = uri.substring(GEO.length)
        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .firstOrNull { it.startsWith("q=", ignoreCase = true) }
            ?.substring(2)
            ?.let(::decoded)
            ?.trim()
            .orEmpty()
        if (query.isNotEmpty()) {
            LABELED_POINT.matchEntire(query)?.let { match ->
                val point = pointOf(match.groupValues[1], match.groupValues[2]) ?: return null
                return Ask.Point(point, match.groupValues[3].trim().ifEmpty { null }?.let(::capped))
            }
            return queryOf(query)?.let(Ask::Search)
        }
        val numbers = path.substringBefore(';').split(',')
        if (numbers.size < 2) return null
        return pointOf(numbers[0], numbers[1])?.let { Ask.Point(it, null) }
    }

    // A real point from a latitude and a longitude, or null for 0,0, a number that isn't one, or off the globe.
    private fun pointOf(latitude: String, longitude: String): Coordinates? {
        val lat = latitude.trim().toDoubleOrNull() ?: return null
        val lon = longitude.trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        return Coordinates(lat, lon)
    }

    // A `q=` value as typed: percent escapes read, a plus a space. A malformed escape leaves it as sent.
    /**
     * Form-decodes [value] strictly: "+" is a space, "%" and two hex digits a byte, and any other
     * "%" stays literal. `URLDecoder` won't do: it reads "%+C" as an escape (its hex parse takes a
     * sign), turning "100%+Cafe" into a control character instead of "100% Cafe".
     */
    private fun decoded(value: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hi = if (c == '%') hexDigit(value.getOrNull(i + 1)) else -1
            val lo = if (hi >= 0) hexDigit(value.getOrNull(i + 2)) else -1
            when {
                hi >= 0 && lo >= 0 -> { bytes.write(hi * 16 + lo); i += 3 }
                c == '+' -> { bytes.write(' '.code); i++ }
                else -> {
                    // A whole run of plain text at once, so a surrogate pair is encoded as one character.
                    var end = i + 1
                    while (end < value.length && value[end] != '%' && value[end] != '+') end++
                    bytes.write(value.substring(i, end).toByteArray(Charsets.UTF_8))
                    i = end
                }
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    // ASCII only: Character.digit alone also reads full-width and other script digits.
    private fun hexDigit(c: Char?): Int =
        if (c != null && (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F')) Character.digit(c, 16) else -1

    private fun capped(line: String): String {
        if (line.length <= MAX_LENGTH) return line
        val cut = line.substring(0, MAX_LENGTH)
        val atWord = cut.substringBeforeLast(' ', missingDelimiterValue = cut)
        return atWord.trimEnd(',', ';', ':', '-', ' ')
    }

    private const val GEO = "geo:"
    private val LABELED_POINT = Regex("""\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*(?:\((.*)\))?\s*""")
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val SPACES = Regex("""\s+""")
}
