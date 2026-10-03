package app.stopdash.domain

/**
 * A trailing "(… Line)"/"(… Lines)" parenthetical — TfL disambiguates co-located stations
 * by the line that serves them ("Hammersmith (H&C Line)", "Hammersmith (Dist&Picc Line)").
 */
private val LINE_PARENTHETICAL = Regex("""\s*\(([^()]*?)\s*\bLines?\)\s*$""", RegexOption.IGNORE_CASE)

/**
 * A line qualifier as [cleanStopName] leaves it, its "Line" already gone ("Hammersmith (H&C)",
 * "Hammersmith (H&C and Circle)"), so [matchStopName] can still recognize one in a name that was
 * cleaned before it was compared. Only the lines TfL qualifies a station by: "(Bakerloo)" and
 * "(Central)" are TfL's own names with no "Line" ("Edgware Road (Bakerloo)"), so they stay, and so
 * does a bus stop's letter ("(C)") or a place ("(London)").
 */
private val SHORT_LINE_QUALIFIER = run {
    val line = """(?:H&C|Hammersmith\s*&\s*City|Dist&Picc|District|Piccadilly|Circle|Metropolitan|Met)"""
    Regex("""\s*\($line(?:\s*(?:,|&|\band\b)\s*$line)*\)\s*$""", RegexOption.IGNORE_CASE)
}

/**
 * Trims TfL's `commonName` down to what a rider reads on a sign. TfL suffixes a stop's
 * type onto the name — "Charing Cross Underground Station", "London Bridge Rail Station",
 * "Canary Wharf DLR Station" — which is noise once the app is already a departures board;
 * dropping it keeps the list glanceable (SPEC *Concise copy*). National Rail's board spells the
 * same thing as a parenthetical on a station that shares its name with a tube station —
 * "Heathrow Terminal 5 (Rail Station Only)" — and it goes too, so a train's destination there
 * matches the stop TfL's route names ("Heathrow Terminal 5"). TfL also names one station
 * "Paddington (H&C Line)-Underground", whose "-Underground" goes the same way. Suffix-only: a name
 * with no type suffix (most bus stops) is returned unchanged, and a stop literally called
 * "Station" is never emptied.
 *
 * A trailing line-name parenthetical is kept but shortened — "Hammersmith (H&C Line)" →
 * "Hammersmith (H&C)" (maintainer, 2026-10-03). TfL only adds that bracket to tell apart two
 * stations of one name a street apart, served by different lines (Hammersmith's two, Edgware Road's
 * Circle beside its Bakerloo), so a rider needs it wherever the station is named — a headline, a
 * card header, a train's destination — and "Line" is the only word in it that says nothing. A
 * *geographic* parenthetical has no "Line" and is kept whole ("Stratford (London)").
 *
 * This is the **display** form. Two TfL sources can name one station with and without the bracket
 * ("Hammersmith (H&C Line)" in one, "Hammersmith" or "Hammersmith (H&C and Circle Lines)" in
 * another), so anything that pairs names across sources compares [matchStopName] instead.
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
fun cleanStopName(raw: String): String = clean(raw, forMatching = false)

/**
 * The form of a stop name to **compare** by, never to show: [cleanStopName] without a line
 * qualifier at all ("Hammersmith (H&C Line)", "Hammersmith (H&C)" and "Hammersmith" all give
 * "Hammersmith"), as every name was cleaned before the qualifier was kept. TfL brackets a station
 * by its line in some sources and not others, so pairing by this keeps every match that worked
 * when the bracket was dropped. Takes a raw or an already-cleaned name alike, and is idempotent.
 */
fun matchStopName(raw: String): String = clean(raw, forMatching = true)

private fun clean(raw: String, forMatching: Boolean): String {
    val name = raw.trim().replace(WHITESPACE_RUN, " ")
    return name.split(CROSS_STREET).joinToString("/") { cleanPart(it, forMatching) }
}

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Between a bus stop's own name and the street or place it's by: TfL's "Aldwych / Somerset House",
 * however spaced. Cleaned, the slash is unspaced ("Aldwych/Somerset House"), so a narrow row shows
 * more of each place.
 */
private val CROSS_STREET = Regex("\\s*/\\s*")

// One place name: its type suffix, then a trailing line parenthetical ([cleanStopName]), shortened
// for display or dropped for matching ([matchStopName]).
private fun cleanPart(part: String, forMatching: Boolean): String {
    var name = part.trim()
    val suffixes = listOf(
        " (Rail Station Only)",
        " Underground Station",
        " DLR Station",
        " Rail Station",
        " Overground Station",
        " Bus Station",
        " Coach Station",
        "-Underground",
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
        val lines = match.groupValues[1].trim()
        if (stripped.isNotEmpty()) name = if (forMatching || lines.isEmpty()) stripped else "$stripped ($lines)"
    }
    if (forMatching) {
        SHORT_LINE_QUALIFIER.find(name)?.let { match ->
            val stripped = name.removeRange(match.range).trim()
            if (stripped.isNotEmpty()) name = stripped
        }
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

/**
 * Whether [a] and [b] name the same stop as far as a name can say: equal by [matchStopName],
 * ignoring case, and not told apart by their line qualifiers ([conflictingQualifiers]). A qualifier
 * on one side only is a source that left it off, so "Hammersmith (H&C)" is "Hammersmith", but not
 * "Hammersmith (Dist&Picc)" (maintainer, 2026-10-03). False when either is null.
 */
fun sameStopName(a: String?, b: String?): Boolean =
    a != null && b != null && matchStopName(a).equals(matchStopName(b), ignoreCase = true) &&
        !conflictingQualifiers(a, b)

/**
 * Whether [a] and [b] both carry a line qualifier and the two name no line in common: "(H&C)" and
 * "(Dist&Picc)" are two stations, "(H&C)" and "(H&C and Circle)" one station that two sources
 * qualify differently. A name with no qualifier conflicts with nothing.
 */
fun conflictingQualifiers(a: String, b: String): Boolean {
    val linesA = qualifierLines(a)
    val linesB = qualifierLines(b)
    return linesA.isNotEmpty() && linesB.isNotEmpty() && linesA.none { it in linesB }
}

/**
 * The lines [name]'s qualifier names, each in one spelling ("Hammersmith (Dist&Picc Line)" →
 * district, piccadilly), or none when it has no qualifier. TfL abbreviates some ("H&C",
 * "Dist&Picc", "Met") and spells them out elsewhere, so both spellings give one line.
 */
internal fun qualifierLines(name: String): Set<String> {
    if (!isLineQualified(name)) return emptySet()
    val bracket = TRAILING_BRACKET.find(cleanStopName(name))?.groupValues?.get(1) ?: return emptySet()
    var text = bracket.lowercase()
    for ((spelling, line) in LINE_SPELLINGS) text = spelling.replace(text, line)
    return text.split(LINE_SEPARATOR).map(String::trim).filterTo(LinkedHashSet()) { it.isNotEmpty() }
}

private val TRAILING_BRACKET = Regex("""\(([^()]*)\)\s*$""")

// Abbreviations first, so "&" inside one isn't read as a separator.
private val LINE_SPELLINGS = listOf(
    Regex("""\bhammersmith\s*&\s*city\b""") to "hammersmith-city",
    Regex("""\bh\s*&\s*c\b""") to "hammersmith-city",
    Regex("""\bdist\s*&\s*picc\b""") to "district,piccadilly",
    Regex("""\bdist\b""") to "district",
    Regex("""\bpicc\b""") to "piccadilly",
    Regex("""\bmet\b""") to "metropolitan",
)

private val LINE_SEPARATOR = Regex("""\s*(?:,|&|\band\b)\s*""")

/**
 * Whether [a] and [b] are the same name as shown ([cleanStopName]), line qualifier and all, ignoring
 * case. False when either is null.
 */
fun exactStopName(a: String?, b: String?): Boolean =
    a != null && b != null && cleanStopName(a).equals(cleanStopName(b), ignoreCase = true)

/**
 * Whether [name] carries a line qualifier ("Paddington (H&C)"). Such a name picks its own station
 * where one is found by [exactStopName], before falling back to [sameStopName]: a Circle train to
 * "Paddington (H&C)" must not also match the plain "Paddington" it passes on the way.
 */
fun isLineQualified(name: String): Boolean =
    !cleanStopName(name).equals(matchStopName(name), ignoreCase = true)
