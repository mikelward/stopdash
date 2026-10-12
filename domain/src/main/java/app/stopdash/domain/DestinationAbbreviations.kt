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
    )

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
     * identity ("Finchley", "Barnet"). Only when nothing maps does it fall to **first word in full,
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
            else -> words.first() + " " + words.drop(1).joinToString(" ") { word ->
                val first = word.first()
                if (first.isLetter()) "${first.uppercaseChar()}." else word
            }
        }
    }

    /** [name] with [shorten] applied to each slash-separated part, the slashes and their spacing kept. */
    private fun eachPart(name: String, shorten: (String) -> String): String =
        name.split("/").joinToString("/") { part ->
            val body = part.trim()
            if (body.isEmpty()) part else part.substringBefore(body) + shorten(body) + part.substringAfterLast(body)
        }
}
