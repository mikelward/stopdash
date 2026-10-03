package app.stopdash.domain

/**
 * A trailing "(… Line)"/"(… Lines)" parenthetical — TfL disambiguates co-located stations
 * by the line that serves them ("Hammersmith (H&C Line)", "Hammersmith (Dist&Picc Line)").
 */
private val LINE_PARENTHETICAL = Regex("""\s*\([^()]*\bLines?\)\s*$""", RegexOption.IGNORE_CASE)

/**
 * Trims TfL's `commonName` down to what a rider reads on a sign. TfL suffixes a stop's
 * type onto the name — "Charing Cross Underground Station", "London Bridge Rail Station",
 * "Canary Wharf DLR Station" — which is noise once the app is already a departures board;
 * dropping it keeps the list glanceable (SPEC *Concise copy*). National Rail's board spells the
 * same thing as a parenthetical on a station that shares its name with a tube station —
 * "Heathrow Terminal 5 (Rail Station Only)" — and it goes too, so a train's destination there
 * matches the stop TfL's route names ("Heathrow Terminal 5"). Suffix-only: a name with
 * no type suffix (most bus stops) is returned unchanged, and a stop literally called
 * "Station" is never emptied.
 *
 * Also drops a trailing line-name parenthetical — "Hammersmith (H&C Line)" → "Hammersmith" —
 * which just names the line that serves the stop, already shown by the row's line pill. A
 * *geographic* parenthetical has no "Line" and is kept ("Stratford (London)"), so a name that
 * genuinely needs the disambiguator keeps it. The two Hammersmiths (H&C vs Dist&Picc) both
 * collapse to "Hammersmith"; the line pill tells them apart.
 *
 * Order matters twice. Among the type suffixes the specific multi-word ones are tried before
 * the bare " Station" catch-all, so "X Underground Station" loses the whole phrase, not just
 * "Station". And the type suffix is stripped *before* the parenthetical, because TfL puts the
 * suffix last — the full `commonName` is "Hammersmith (H&C Line) Underground Station", so the
 * parenthetical only reaches the end (where the end-anchored [LINE_PARENTHETICAL] can catch it)
 * once "Underground Station" is gone.
 *
 * A bus stop named with its cross street carries the suffix on either part ("Parkside Station  /
 * High Road", "Market Place / Riverside Station"): each part is cleaned the same way
 * ("Parkside/High Road"; maintainer, 2026-09-27: "Station" is stripped everywhere), and the slash
 * loses the spaces TfL pads it with (maintainer, 2026-10-03), so a narrow row fits more of each
 * place. A road named for one ("Station Road") has no suffix to strip. Runs of whitespace fold to one.
 */
fun cleanStopName(raw: String): String {
    val name = raw.trim().replace(WHITESPACE_RUN, " ")
    return name.split(CROSS_STREET).joinToString("/", transform = ::cleanPart)
}

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Between a bus stop's own name and the street or place it's by: TfL's "Aldwych / Somerset House",
 * however spaced. Cleaned, the slash is unspaced ("Aldwych/Somerset House"), so a narrow row shows
 * more of each place.
 */
private val CROSS_STREET = Regex("\\s*/\\s*")

// One place name: its type suffix, then a trailing line parenthetical ([cleanStopName]).
private fun cleanPart(part: String): String {
    var name = part.trim()
    val suffixes = listOf(
        " (Rail Station Only)",
        " Underground Station",
        " DLR Station",
        " Rail Station",
        " Overground Station",
        " Bus Station",
        " Coach Station",
        " Station",
    )
    for (suffix in suffixes) {
        if (name.length > suffix.length && name.endsWith(suffix, ignoreCase = true)) {
            name = name.substring(0, name.length - suffix.length).trim()
            break
        }
    }
    LINE_PARENTHETICAL.find(name)?.let { match ->
        val stripped = name.removeRange(match.range).trim()
        if (stripped.isNotEmpty()) name = stripped
    }
    return name
}

private val STATION_WORD_ABBREVIATIONS = mapOf("international" to "Intl")

/**
 * A **display-only** shortening of a stop name: long words a rider doesn't need spelled out on a sign,
 * today "International" → "Intl" (maintainer, 2026-09-27), so a name like "King's Cross & St Pancras
 * International" fits a row (SPEC *Concise copy*).
 *
 * Never fold this into [cleanStopName] or any name used to **match** alert text: disruption matching
 * relies on the full spelling TfL uses, and unlike a stripped suffix, "Intl" is *not* a substring of
 * "International", so abbreviating a matched alias would make a disruption at that station go unseen
 * (SPEC *Disruptions*). Whole-word and case-insensitive; spacing is preserved.
 */
fun abbreviateStationName(name: String): String =
    name.split(" ").joinToString(" ") { word -> STATION_WORD_ABBREVIATIONS[word.lowercase()] ?: word }

private const val VIA = " via "
private const val BRANCH_SUFFIX = " Branch"

/**
 * The "via" branch TfL names in a prediction's `towards` — `Charing X` from
 * "Battersea Power Station via Charing Cross" — or null when there is no "via" (most
 * services, and buses, whose `towards` is a plain destination or a comma list). This is
 * the branch a rider reads off the platform board to pick a train, distinct from the
 * terminus. Anything past a comma is dropped as noise, matching the destination cleaning.
 *
 * TfL's live feed spells the same trunk several ways — the Northern line's two central
 * trunks arrive as `Bank`, `Bank Branch`, and `CX` — so this folds them to one short
 * label the rider reads the same every time: a trailing " Branch" is dropped as noise,
 * and TfL's cryptic `CX` and the full `Charing Cross` both render as the board's own
 * `Charing X`. So the label is already a short form; [abbreviateBranch] only shortens a
 * still-longer branch (e.g. the Central line's Hainault-loop vias) when a row can't fit it.
 */
fun branchOf(towards: String?): String? {
    if (towards == null) return null
    val idx = towards.indexOf(VIA, ignoreCase = true)
    if (idx < 0) return null
    return normalizeBranch(towards.substring(idx + VIA.length).substringBefore(",").trim())
}

/**
 * Folds a branch label to the one short board form — see [branchOf] for the spellings TfL
 * uses and why. Applied both when parsing a prediction's `towards` and when restoring a
 * persisted snapshot, so a value an older build stored ("Charing Cross", "Bank Branch")
 * reads back as the same canonical label ("Charing X", "Bank") a fresh fetch produces — a row
 * never shows two spellings for one trunk across a process restart. `null`/blank is `null`.
 */
fun normalizeBranch(raw: String?): String? {
    if (raw == null) return null
    var branch = raw.trim()
    if (branch.endsWith(BRANCH_SUFFIX, ignoreCase = true)) {
        branch = branch.dropLast(BRANCH_SUFFIX.length).trim()
    }
    if (branch.equals("CX", ignoreCase = true) || branch.equals("Charing Cross", ignoreCase = true)) {
        return "Charing X"
    }
    return branch.ifBlank { null }
}

/**
 * A shorter form of a branch for a row too narrow to fit the full one: the same whole-word forms a
 * destination takes ([DestinationAbbreviations] — "Newbury Park" → "Newbury Pk", "East Ham" →
 * "E. Ham", "Walthamstow Central" → "Walthamstow C."), plus "Cross" → "X", which a board uses for a
 * branch ("Charing X"). The row abbreviates both the terminus and the branch before cutting either
 * (SPEC destination-label). A branch with no such word comes back unchanged. Kept off the value
 * stored in [branchOf] so the full name shows wherever it fits; the UI measures and falls back to
 * this only when it must.
 */
fun abbreviateBranch(branch: String): String =
    DestinationAbbreviations.abbreviate(branch)
        .split(" ")
        .joinToString(" ") { word -> if (word == "Cross") "X" else word }

/**
 * A point the Journey Planner names, cleaned ([cleanStopName]). A station entrance a walk ends at
 * comes as its street, then the station ("Cannon Street, Cannon Street Rail Station"): it goes by
 * the station. Any other name is only cleaned ("High Street, Kensington" stays whole).
 */
fun pointName(commonName: String): String {
    val station = commonName.substringAfterLast(", ", missingDelimiterValue = "").trim()
    return cleanStopName(station.takeIf { it.endsWith(" Station", ignoreCase = true) } ?: commonName)
}
