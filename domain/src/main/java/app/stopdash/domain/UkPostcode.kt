package app.stopdash.domain

/**
 * Recognizes and canonicalizes UK postcodes, so the place search can offer to resolve one to a
 * location (SPEC D9's postcode entry) rather than treating it as a station name that will never match.
 *
 * Two questions the search asks as the rider types:
 * - [looksLikePartial] — could what's typed so far be the start of a postcode? Deliberately loose: it
 *   wants area letters, and once a digit follows, more of the outcode/incode. It only decides when to
 *   show the "Postcode" affordance (from two characters, the same floor station completions use), so a
 *   near-miss that shows the offer a keystroke early is harmless — [isComplete] is the real gate.
 * - [isComplete] — is it a whole, well-formed postcode? Only then is it resolvable.
 *
 * The match is **structural**: it checks the shape (one or two area letters, a district, a sector, a
 * two-letter unit), not that the outcode is a real one — TfL is the authority on whether a postcode
 * places, so a stricter check here (or an allow-list of London areas) would only reject valid codes
 * this app hasn't heard of, and TfL plans well beyond the London postal areas. The defunct `GIR 0AA`
 * is left out for the same reason it never helps a London rider. Whitespace and case are ignored
 * ([normalize]); a complete code renders in its canonical `OUTCODE INCODE` form ([format]).
 */
object UkPostcode {
    // A complete code, space removed: area letters, district digit, optional second district character,
    // then the inward code (sector digit + two-letter unit). The optional character backtracks so the
    // sector digit is found whether or not a second district character was typed (e.g. N1 9GU).
    private val COMPLETE = Regex("^[A-Z]{1,2}[0-9][0-9A-Z]?[0-9][A-Z]{2}$")

    // A leading fragment of the above: area letters alone, or area letters then a digit and up to the
    // four remaining postcode characters. Bounded so a run of text past a full code stops matching.
    private val PARTIAL = Regex("^[A-Z]{1,2}([0-9][0-9A-Z]{0,4})?$")

    /**
     * Whitespace removed and ASCII letters uppercased — the form the patterns match against. Only
     * `a`–`z` are folded (not the full-Unicode `String.uppercase()`, whose expansions like `ß` → `SS`
     * could forge a valid-looking postcode from non-ASCII input); every other character is kept as-is
     * so the ASCII-only patterns reject anything that isn't a real `A`–`Z`/`0`–`9` postcode character.
     */
    fun normalize(text: String): String = buildString {
        for (c in text) if (!c.isWhitespace()) append(if (c in 'a'..'z') c.uppercaseChar() else c)
    }

    /** True when [text] could be the start of a UK postcode (see the class doc). Blank is not. */
    fun looksLikePartial(text: String): Boolean = normalize(text).let { it.isNotEmpty() && PARTIAL.matches(it) }

    /** True when [text] is a complete, well-formed UK postcode. */
    fun isComplete(text: String): Boolean = COMPLETE.matches(normalize(text))

    /**
     * [text] in canonical `OUTCODE INCODE` form (a single space before the last three characters),
     * or null when it isn't a complete postcode.
     */
    fun format(text: String): String? {
        val normalized = normalize(text)
        if (!COMPLETE.matches(normalized)) return null
        val split = normalized.length - 3
        return "${normalized.substring(0, split)} ${normalized.substring(split)}"
    }
}
