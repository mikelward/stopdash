package app.stopdash.domain

/**
 * Shortens common whole words in a destination name so more of the name survives before a
 * hard clip on a narrow row (SPEC destination-label). The row applies this only as a
 * **shrink step**: the full name shows when it fits, and this is tried before the clean
 * cut — so `East Finchley` stays whole where there is room and becomes `E. Finchley` only
 * when the row would otherwise truncate it.
 *
 * **Whole words only.** A word is replaced only when it stands alone (space-delimited) and
 * matches exactly (case-sensitive — destinations are Title Case), never as a substring, so
 * `High Barnet` → `H. Barnet` but `Highgate`, `Eastcote`, and `Upminster` are untouched.
 * A short form ends in `.` and is never itself a key, so [abbreviate] is idempotent.
 *
 * The map is maintainer-chosen copy (2026-09-21): single-letter forms take a trailing
 * period so they read as abbreviations, not initials; multi-letter contractions take none.
 * A form is chosen to be useful to a rider: TfL's own form where it has one (`Charing X`),
 * otherwise a conventional one a Londoner reads at once. Ordnance Survey's road-name forms
 * (`Bwy`, `Sq`, `Gdns`, `Gn`, …) are a guide, not a closed list — `Br`, `Hth` and `Hosp` are
 * added though OS leaves those words whole (maintainer, 2026-10-12).
 */
object DestinationAbbreviations {
    private val forms = mapOf(
        "North" to "N.",
        "South" to "S.",
        "East" to "E.",
        "West" to "W.",
        "High" to "H.",
        "Central" to "C.",
        "Upper" to "U.",
        "Lower" to "L.",
        "Great" to "Gt",
        "Junction" to "Jct",
        "Park" to "Pk",
        "Road" to "Rd",
        "Street" to "St",
        "Lane" to "Ln",
        "Market" to "Mkt",
        "Station" to "Stn",
        "Point" to "Pt",
        "Broadway" to "Bwy",
        "Square" to "Sq",
        "Gardens" to "Gdns",
        "Green" to "Gn",
        "Avenue" to "Ave",
        "Place" to "Pl",
        "Crescent" to "Cres",
        "Bridge" to "Br",
        "Heath" to "Hth",
        "Hospital" to "Hosp",
        // As a station's name already shows it ([abbreviateStationName]), so a destination or a ride's
        // end shortens the same way (maintainer, 2026-10-14).
        "International" to "Intl",
        // TfL's own form, and the riders' ("King's X", "Charing X"), so no period (maintainer, 2026-10-14).
        "Cross" to "X",
        // An airport's terminals (maintainer, 2026-10-14): "Heathrow T. 5", "Heathrow T. 2 & 3".
        "Terminal" to "T.",
        "Terminals" to "T.",
    )

    /**
     * Places that carry a name's identity when they end it: each is the last word of a bundled
     * station named `<qualifier> <place>` with a [forms] qualifier (`High Barnet`, `North Acton`,
     * `East Croydon`), so an un-mapped word before it is the throwaway part (maintainer,
     * 2026-10-18). A generic word (`Quay`, `Town`) is left out, so `Kentish Town` floors as before.
     */
    private val placeStems = setOf(
        "Acton", "Barnet", "Bentley", "Bermondsey", "Brompton", "Brooms", "Byfleet", "Chesterford",
        "Croydon", "Drayton", "Dulwich", "Ealing", "Fambridge", "Farleigh", "Finchley", "Greenford",
        "Greenwich", "Grinstead", "Halliford", "Ham", "Hampstead", "Harrow", "Holloway", "Horndon",
        "India", "Kensington", "Kenton", "Malling", "Merton", "Missenden", "Norwood", "Putney",
        "Ruislip", "Sheen", "Silvertown", "Sutton", "Sydenham", "Tilbury", "Tottenham", "Warlingham",
        "Wembley", "Wickham", "Wimbledon", "Woodford", "Worthing", "Wycombe",
    )

    /**
     * The forms every shortened name steps through, longest first: in full, then [abbreviate], then
     * [floor], and only below that an elision. One list for every place a name shortens — a
     * departure's destination, a trip's banner, a favorite ride's heading — so each shortens alike
     * (maintainer, 2026-10-14). The same form may repeat.
     */
    fun forms(name: String): List<String> = listOf(name, abbreviate(name), floor(name))

    /**
     * The name with each recognized standalone word replaced by its short form, in each part of a
     * slash-separated name alike (`Shepherd's Bush Market / Wood Lane` → `… / Wood Ln`).
     */
    fun abbreviate(name: String): String = eachPart(name) { part ->
        part.split(" ").joinToString(" ") { token -> forms[token] ?: token }
    }

    /**
     * The shortest still-recognizable form, the **floor** below [abbreviate]: the row shows the
     * longest form that fits and drops to this before any clean `…`, so a cut lands on a word or
     * initial boundary — never mid-glyph (SPEC destination-label).
     *
     * When [abbreviate] already shortens a word it is the floor (`North Finchley` → `N. Finchley`,
     * `High Barnet` → `H. Barnet`) — the mapped word is the throwaway one, so this keeps the
     * identity ("Finchley", "Barnet"). When nothing maps but the **last word is a place a compass or
     * "High" qualifies elsewhere** ([placeStems]: `High Barnet`, `West Harrow`), that last word is the
     * identity and each earlier word takes its initial (`Sudbury Hill Harrow` → `S. H. Harrow`,
     * `New Barnet` → `N. Barnet`). Otherwise it falls to **first word in full,
     * each later word an initial** (`Battersea Power` → `Battersea P.`), where the first word carries
     * the identity. A non-letter token (`&`, a number) is left whole so `Elephant & Castle` reads
     * `Elephant & C.`, not `Elephant &. C.`. A one-word or blank name is returned unchanged. Each
     * part of a slash-separated name is floored on its own, so each keeps its own identity
     * (`Wood Lane / White City Road` → `Wood Ln / White City Rd`).
     */
    fun floor(name: String): String = eachPart(name) { part ->
        val abbreviated = abbreviate(part)
        val words = part.split(" ").filter { it.isNotEmpty() }
        when {
            abbreviated != part -> abbreviated
            words.size <= 1 -> part
            words.last() in placeStems -> stemFloor(words)
            else -> words.first() + " " + words.drop(1).joinToString(" ") { word ->
                val first = word.first()
                if (first.isLetter()) "${first.uppercaseChar()}." else word
            }
        }
    }

    /** [words] with each word before the last an initial, non-letter tokens kept whole. */
    private fun stemFloor(words: List<String>): String =
        (words.dropLast(1).map { word ->
            val first = word.first()
            if (!first.isLetter()) word else "${first.uppercaseChar()}."
        } + words.last()).joinToString(" ")

    /** [name] with [shorten] applied to each slash-separated part, the slashes and their spacing kept. */
    private fun eachPart(name: String, shorten: (String) -> String): String =
        name.split("/").joinToString("/") { part ->
            val body = part.trim()
            if (body.isEmpty()) part else part.substringBefore(body) + shorten(body) + part.substringAfterLast(body)
        }
}
