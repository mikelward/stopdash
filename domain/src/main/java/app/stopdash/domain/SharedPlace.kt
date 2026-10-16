package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * What to search for when another app shares a place with StopDash (SPEC *Sharing a place in*):
 * the place's name, read from the shared text without sending anything anywhere.
 *
 * Google Maps shares a place as its name, sometimes its address on the next line, and a short link
 * (`https://maps.app.goo.gl/…`); other apps share a name, a line of text, or a link alone. The search
 * is for the first line that names something — not a link, and not blank — with any link run on to
 * the end of it dropped. A Google Maps link ([mapsLinkOf]) is read for the exact spot instead
 * ([fromMapsUrl]); a short one has to be followed first, which asks Google, and that is the app's job,
 * not this object's. A share with no such line ([subject] aside, a title some apps send apart from the
 * text) has nothing to search for, and gives null. Shared text can be any length, so every reading
 * runs off the main thread.
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

    /**
     * The search a share names: its [lineOf], cut to [MAX_LENGTH]. An address ("10 Example Street,
     * London") searches its first part, the house and street: the town after it only keeps the search
     * from matching a street's name.
     */
    @WorkerThread
    fun queryOf(text: String?, subject: String? = null): String? = lineOf(text, subject)?.let(::streetOf)?.let(::capped)

    /** The first line of what was shared ([text], then [subject]) that names something, whole and tidied. */
    @WorkerThread
    fun lineOf(text: String?, subject: String? = null): String? =
        sequenceOf(text, subject)
            .filterNotNull()
            .flatMap { it.lineSequence() }
            .map { URL.replace(it, " ").replace(SPACES, " ").trim().trim('"', '“', '”').trim() }
            .firstOrNull { line -> line.any(Char::isLetterOrDigit) }

    /**
     * A shared address's complete UK [postcode] ("XX1 1XX"), and the [label] it's shown by ("10 Example
     * Street"). [capitals]: written as a postcode is; one in lowercase ("xx1 1xx") may be other words
     * that only look like one ("b12 3rd"), so it counts only where a list of real postcodes holds it.
     */
    data class Address(val postcode: String, val label: String, val capitals: Boolean = true)

    /**
     * Every complete UK postcode in [line], in order, each as an [Address], as Calendar and Contacts share
     * an event's or a contact's address ("10 Example Street, London XX1 1XX, UK"). All of them, since
     * words before the real one can look like one ("Room b12 3rd floor, …"): which counts is for a list
     * of real postcodes to say. Each one's label is the text before it up to its first comma, or the
     * postcode where nothing comes before it.
     */
    @WorkerThread
    fun addressesOf(line: String?): List<Address> =
        // Every whitespace character as a space, as [UkPostcode] reads them all, the line's length kept.
        POSTCODE.findAll(line?.map { if (it.isWhitespace()) ' ' else it }?.joinToString("") ?: return emptyList()).mapNotNull { match ->
            val postcode = UkPostcode.format(match.value) ?: return@mapNotNull null
            val before = line.substring(0, match.range.first).trim().trimEnd(',').trim()
            val capitals = match.value.none { it in 'a'..'z' }
            Address(postcode, capped(before.substringBefore(',').trim().ifEmpty { postcode }), capitals)
        }.toList()

    /** The text a `geo:` link names a place by (`geo:0,0?q=…`, decoded), or null where it names a point or nothing. */
    @WorkerThread
    fun geoText(uri: String?): String? = geoQuery(uri)?.takeUnless { LABELED_POINT.matches(it) }

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
        val path = uri.substring(GEO.length).substringBefore('?')
        val query = geoQuery(uri).orEmpty()
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

    /**
     * The first Google Maps link in what was shared ([text], then [subject]), or null: a short link
     * (`maps.app.goo.gl/…`, `goo.gl/maps/…`), to follow with [isShortMapsLink], or a full one
     * (`www.google.com/maps/…`, `maps.google.com/?q=…`), to read as it is. Only `https`, so a link
     * is never followed in the clear.
     */
    @WorkerThread
    fun mapsLinkOf(text: String?, subject: String? = null): String? =
        sequenceOf(text, subject)
            .filterNotNull()
            .flatMap { URL.findAll(it) }
            .map { it.value.trimEnd('.', ',', ')', '"', '”') }
            .firstOrNull { isShortMapsLink(it) || isFullMapsLink(it) }

    /** Whether [url] is a Google Maps short link, which names its place only once followed. */
    fun isShortMapsLink(url: String): Boolean {
        val (host, path) = hostAndPath(url) ?: return false
        return host == "maps.app.goo.gl" || (host == "goo.gl" && path.startsWith("/maps/"))
    }

    /**
     * Whether [url] may be followed or read as a Google Maps link: a short link, or a page on Google's
     * own Maps (or its consent page, which carries the Maps link it stands in front of). Anything else
     * a short link redirects to is not followed.
     */
    fun isGoogleMapsHop(url: String): Boolean {
        if (isShortMapsLink(url) || isFullMapsLink(url)) return true
        val (host, _) = hostAndPath(url) ?: return false
        return host == "consent.google.com"
    }

    /**
     * What a Google Maps link ([url], a full one, or where a short one led) asks for: the place's own
     * pin (`!3d…!4d…`), a point it names (`?q=51.5,-0.12`, `/search/51.5,+-0.12`), or the spot the map
     * is centered on (`/@51.5,-0.12,17z`), as a [Ask.Point] labeled [label]; otherwise a search for
     * the place it names by text (`?q=Tate+Modern,+Bankside,+London`); null when it names neither. A
     * consent page is read for the Maps link it carries.
     */
    @WorkerThread
    fun fromMapsUrl(url: String, label: String? = null): Ask? {
        val (host, path) = hostAndPath(url) ?: return null
        val params = paramsOf(url)
        if (host == "consent.google.com") {
            return params["continue"]?.takeIf(::isFullMapsLink)?.let { fromMapsUrl(it, label) }
        }
        if (!isFullMapsLink(url)) return null
        val point = PIN.find(url)?.let { pointOf(it.groupValues[1], it.groupValues[2]) }
            ?: sequenceOf("q", "query", "ll", "daddr", "destination").mapNotNull { params[it] }
                .firstNotNullOfOrNull { value -> LABELED_POINT.matchEntire(value)?.let { pointOf(it.groupValues[1], it.groupValues[2]) } }
            ?: SEARCH_POINT.find(decoded(url.substringBefore('?')))?.let { pointOf(it.groupValues[1], it.groupValues[2]) }
            // A map's center is the place only on a place's page or a bare map; on directions it is
            // somewhere between the ends.
            ?: CENTER.find(path)?.takeIf { path.startsWith("/maps/place/") || path.startsWith("/maps/@") }
                ?.let { pointOf(it.groupValues[1], it.groupValues[2]) }
        if (point != null) return Ask.Point(point, label?.let(::capped))
        return mapsTextOf(url)?.let(::queryOf)?.let(Ask::Search)
    }

    /**
     * The text a Google Maps link ([url], a full one, or where a short one led) names its place by, whole
     * and decoded, or null where it names none. A short link shared from the Maps app leads to its place's
     * name and address with no spot in it (`maps.google.com?q=Tate+Modern,+Bankside,+London+SE1+9TG&ftid=…`),
     * so this is where its postcode is ([addressesOf]). A consent page is read for the Maps link it carries.
     */
    @WorkerThread
    fun mapsTextOf(url: String): String? {
        val (host, _) = hostAndPath(url) ?: return null
        val params = paramsOf(url)
        if (host == "consent.google.com") return params["continue"]?.takeIf(::isFullMapsLink)?.let(::mapsTextOf)
        if (!isFullMapsLink(url)) return null
        // A point that isn't a real one (0,0) is no place, not text to search.
        return sequenceOf("q", "query", "destination", "daddr").mapNotNull { params[it] }
            .filterNot { LABELED_POINT.matches(it) }
            .firstOrNull { lineOf(it) != null }
            ?.let(::bounded)
    }

    // A `geo:` link's `q=` text, decoded and trimmed; null for no link or no text.
    private fun geoQuery(uri: String?): String? {
        if (uri == null || !uri.startsWith(GEO, ignoreCase = true)) return null
        return uri.substring(GEO.length).substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .firstOrNull { it.startsWith("q=", ignoreCase = true) }
            ?.substring(2)
            ?.let(::decoded)
            ?.trim()
            ?.ifEmpty { null }
    }

    // An address's first part, where [line] reads as one: a house number, then more after a comma.
    // A name with a comma in it ("Smith, Jones & Co") is left whole.
    private fun streetOf(line: String): String {
        val first = line.substringBefore(',').trim()
        return if (',' in line && first.firstOrNull()?.isDigit() == true) first else line
    }

    private fun isFullMapsLink(url: String): Boolean {
        val (host, path) = hostAndPath(url) ?: return false
        val google = GOOGLE_HOST.matchEntire(host) ?: return false
        return if (google.groupValues[1] == "maps.") true else path == "/maps" || path.startsWith("/maps/")
    }

    // The lowercase host and the path of an https [url], or null for anything else.
    private fun hostAndPath(url: String): Pair<String, String>? {
        if (!url.startsWith(HTTPS, ignoreCase = true)) return null
        val rest = url.substring(HTTPS.length)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, end)
        if ('@' in authority) return null
        val host = authority.substringBefore(':').lowercase()
        val path = rest.substring(end).substringBefore('?').substringBefore('#')
        return host to path
    }

    // A link's query parameters, each read as typed ([decoded]); the first of a repeated name wins.
    private fun paramsOf(url: String): Map<String, String> =
        url.substringBefore('#').substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .filter { '=' in it }
            .map { it.substringBefore('=').lowercase() to decoded(it.substringAfter('=')) }
            .reversed()
            .toMap()

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

    // A complete UK postcode standing alone in a line, in either case ([Address.capitals]), its halves
    // apart by any spaces or none ([addressesOf] makes every whitespace character one).
    private val POSTCODE = Regex("""(?<![A-Za-z0-9])[A-Za-z]{1,2}[0-9][0-9A-Za-z]? *[0-9][A-Za-z]{2}(?![A-Za-z0-9])""")
    private const val HTTPS = "https://"
    private val GOOGLE_HOST = Regex("""(?:www\.)?(maps\.)?google\.(?:com|co\.uk|[a-z]{2}|com?\.[a-z]{2})""")
    private val PIN = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    private val SEARCH_POINT = Regex("""/maps/(?:search|place|dir/[^/]*)/\s*(-?\d+(?:\.\d+)?)\s*,\s*\+?\s*(-?\d+(?:\.\d+)?)""")
    private val CENTER = Regex("""/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    private val LABELED_POINT = Regex("""\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*(?:\((.*)\))?\s*""")
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val SPACES = Regex("""\s+""")
}
