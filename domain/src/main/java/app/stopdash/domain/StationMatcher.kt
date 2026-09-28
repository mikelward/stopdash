package app.stopdash.domain

import java.text.Normalizer

/**
 * How well a station matches a typed query, best first — the `ordinal` drives ranking (SPEC
 * *Finding stops → Find a station*). Ported from TypeLauncher's launcher search
 * (`launcherMatchTier`), so the fleet's two type-to-find surfaces rank alike:
 *
 * - [Prefix]: the name starts with the query ("king" → King's Cross), or a station code is the
 *   query ("kgx" → the King's Cross hub, `HUBKGX`).
 * - [Anchored]: the query's first letter starts a word and each later letter either follows on
 *   or starts a later word ("kc" → **K**ing's **C**ross, "kx" → **K**ing's **X**).
 * - [Substring]: the query appears anywhere ("cross" → Charing Cross), or starts a station code.
 * - [Fuzzy]: the first letter starts a word, the rest follow in order anywhere after it
 *   ("vctra" → Victoria). The loosest, so it ranks below every precise match.
 */
enum class StationMatchTier { Prefix, Anchored, Substring, Fuzzy }

/**
 * The type-to-find matcher behind "Find a station": a station's name, the abbreviations it
 * generates, and its TfL codes, each scored and the best kept. Pure, so it is JVM-tested.
 *
 * Names are compared **normalized**: accents folded ("é" → "e") and apostrophes and other
 * punctuation dropped, so "kings" is a prefix of "King's Cross" and "st pancras" of "St. Pancras".
 * Abbreviations are **generated, not listed** (maintainer, 2026-09-24): a word "Cross" also reads
 * "X", the way TfL and riders shorten it ("King's X", "Charing X"), so "kx" and "cx" anchor
 * without an alias table to keep up to date.
 */
object StationMatcher {
    /** Below this length the fuzzy tier stays off: one letter would only re-find word starts. */
    private const val FUZZY_MIN_QUERY_LENGTH = 2

    /**
     * The best tier at which [query] matches a station named [name] with TfL id [id] (and
     * [hubId], its interchange), or null for no match. A blank query matches nothing: the
     * search shows its prompt instead of every station.
     */
    fun tier(query: String, name: String, id: String = "", hubId: String = ""): StationMatchTier? {
        val q = normalize(query).replace(" ", "")
        if (q.isEmpty()) return null
        val nameTier = namesOf(name).mapNotNull { nameTier(q, it) }.minOrNull()
        val codeTier = listOf(id, hubId).filter { it.isNotBlank() }.mapNotNull { codeTier(q, it) }.minOrNull()
        return listOfNotNull(nameTier, codeTier).minOrNull()
    }

    /**
     * The name as matched: accents folded, punctuation other than spaces dropped, runs of spaces
     * collapsed, case kept — the anchored tier reads a capital as a word start.
     */
    internal fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace(PUNCTUATION, "")
            .replace(SPACES, " ")
            .trim()

    /**
     * The name and the abbreviated forms it generates ("King's Cross" → also "Kings X"), plus each
     * of its parts when it has several: a stop named with its cross street ("Foo Street / Bar Road")
     * or a place named after its area ("City of Westminster, Tate Britain") matches a query that
     * starts any part as a prefix, so "ba" finds "Bar Road" and "tate" finds "Tate Britain" as
     * readily as a query that starts the whole name (maintainer, 2026-09-28).
     */
    internal fun namesOf(name: String): List<String> {
        val parts = name.split(SUB_LABEL).map { it.trim() }.filter { it.isNotEmpty() }
        val wholes = if (parts.size > 1) listOf(name) + parts else listOf(name)
        return wholes.flatMap { whole ->
            val normalized = normalize(whole)
            val abbreviated = normalized.replace(CROSS_WORD, "X")
            if (abbreviated == normalized) listOf(normalized) else listOf(normalized, abbreviated)
        }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * A TfL id's code: the letters after its fixed prefix ("HUBKGX" → "KGX", "940GZZLUKSX" →
     * "KSX", "910GKGX" → "KGX", "930GBFR" → "BFR"), or the whole id when it has no such prefix.
     */
    internal fun codeOf(id: String): String {
        val upper = id.uppercase()
        val prefix = CODE_PREFIX.find(upper)?.value
        return if (prefix == null) upper else upper.substring(prefix.length)
    }

    private fun codeTier(query: String, id: String): StationMatchTier? {
        val q = query.uppercase()
        val code = codeOf(id)
        return when {
            code == q || id.equals(q, ignoreCase = true) -> StationMatchTier.Prefix
            code.startsWith(q) || id.startsWith(q, ignoreCase = true) -> StationMatchTier.Substring
            else -> null
        }
    }

    // Spaces in the query are ignored (see [tier]), so the name is matched without them too for
    // the literal tiers — "kingsx" still prefixes "Kings X"; the anchored and fuzzy tiers read word
    // starts from the spaced form.
    private fun nameTier(query: String, name: String): StationMatchTier? {
        val compact = name.replace(" ", "")
        return when {
            compact.startsWith(query, ignoreCase = true) -> StationMatchTier.Prefix
            name.matchesAnchored(query) -> StationMatchTier.Anchored
            compact.contains(query, ignoreCase = true) -> StationMatchTier.Substring
            query.length >= FUZZY_MIN_QUERY_LENGTH && name.matchesFuzzy(query) -> StationMatchTier.Fuzzy
            else -> null
        }
    }

    // A word starts at index 0, at a capital, or after a space (TfL's names are title case, but a
    // lower-case word like "the" or "upon" still starts a word).
    private fun String.isWordStart(index: Int): Boolean =
        index == 0 || this[index].isUpperCase() || this[index - 1] == ' '

    private fun String.matchesAnchored(query: String): Boolean =
        anchors(query[0]).any { matchesFrom(query, queryStart = 1, nameStart = it + 1, loose = false) }

    private fun String.matchesFuzzy(query: String): Boolean =
        anchors(query[0]).any { matchesFrom(query, queryStart = 1, nameStart = it + 1, loose = true) }

    private fun String.anchors(first: Char): Sequence<Int> =
        indices.asSequence().filter { isWordStart(it) && this[it].equalsIgnoreCase(first) }

    /**
     * Whether the rest of [query] (from [queryStart]) matches in order from [nameStart]. Strict:
     * after skipping any letter, the next match must start a word. [loose]: any later letter.
     * Spaces in the name are passed over (the query has none).
     */
    private fun String.matchesFrom(query: String, queryStart: Int, nameStart: Int, loose: Boolean): Boolean {
        var nameIndex = nameStart
        var queryIndex = queryStart
        var skipped = false
        while (queryIndex < query.length) {
            if (nameIndex >= length) return false
            val c = this[nameIndex]
            if (c == ' ') {
                nameIndex++
                continue
            }
            if (c.equalsIgnoreCase(query[queryIndex]) && (loose || !skipped || isWordStart(nameIndex))) {
                queryIndex++
                skipped = false
            } else {
                skipped = true
            }
            nameIndex++
        }
        return true
    }

    private fun Char.equalsIgnoreCase(other: Char): Boolean =
        this == other || lowercaseChar() == other.lowercaseChar()

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    // Anything but a letter, a digit or a space: apostrophes, full stops, ampersands, hyphens.
    // A hyphen or ampersand between words becomes nothing, so "Elephant & Castle" reads
    // "Elephant Castle" and "Shepherd's Bush" reads "Shepherds Bush".
    private val PUNCTUATION = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex(" +")
    // Between a name's parts: a stop's cross street ("Aldwych / Somerset House") or a place's
    // area ("City of Westminster, Tate Britain").
    private val SUB_LABEL = Regex("""\s*/\s*|,\s*""")
    private val CROSS_WORD = Regex("\\bCross\\b", RegexOption.IGNORE_CASE)

    // TfL id prefixes ahead of a station's code: interchanges (HUB…); a NaPTAN area code (910G
    // National Rail, 930G piers, 940G…), where 940G's metro stations add ZZ and a two-letter
    // network (LU tube, DL DLR, CR tram, AL cable car, …). Matched only when a code follows.
    private val CODE_PREFIX = Regex("""^(?:HUB|9\d0G(?:ZZ[A-Z]{2})?)(?=.)""")
}
