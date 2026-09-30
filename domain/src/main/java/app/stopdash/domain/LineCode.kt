package app.stopdash.domain

/**
 * The short label shown for a service — a line's **first three letters**, uppercased
 * (Victoria → VIC, Bakerloo → BAK, Elizabeth line → ELI); the maintainer's "first three
 * letters" call, not a cleverer abbreviation, so the pill stays narrow and the row keeps
 * its width for the countdown.
 *
 * **National Rail is the exception** (see [railOperatorCode]): first-three-letters collides
 * for operators ("Southern" and "Southeastern" both → SOU) and reads as noise for the rest,
 * so a rail operator shows its initials (East Midlands Railway → EMR) or, for the few that
 * need it, a hand-pinned code.
 *
 * A route identified by a number or code keeps it verbatim: a bus route (`24`, `N73`), a
 * river-bus route (`RB1`, `RB6`), or c2c carries a digit, so it is returned as-is rather than
 * collapsed to its letters — otherwise every `RBn` would read "RB" and be indistinguishable
 * at a shared pier. A name with no letters falls back to itself. `uppercase()` is the
 * no-arg, locale-invariant overload, so it's safe from the Turkish-ı trap.
 *
 * Pure domain logic (no Android, no Compose) so every surface — the in-app pill now, the
 * Glance widget later — derives a service's displayed identity one way.
 */
fun lineCode(lineName: String, mode: String, lineId: String = ""): String {
    if (mode.equals("bus", ignoreCase = true) || lineName.any { it.isDigit() }) {
        return lineName.trim()
    }
    if (mode.equals("national-rail", ignoreCase = true)) {
        railOperatorCode(lineName, lineId)?.let { return it }
    }
    val letters = lineName.filter { it.isLetter() }
    return if (letters.isEmpty()) lineName.trim() else letters.take(3).uppercase()
}

/**
 * The National Rail pill code for [operator], or `null` to let [lineCode] fall back to the
 * first-three-letters rule. Three steps, in order:
 *
 * 1. A **hand-pinned exception** ([railOperatorExceptions], [railOperatorPrefixes]) for the name
 *    wins. This is where the single-word operators that would otherwise collide take their
 *    official two-letter TOC (train operating company) code — Southern SN, Southeastern SE (both
 *    are "SOU" under first-three-letters) — and where any operator whose auto-initials read wrong
 *    can be corrected by hand.
 * 2. Otherwise a code pinned to the **line** ([railLineCodes]), by [lineId]. A rail board's line
 *    id comes from the operator's code, which the feed sends reliably, while its name is spelled
 *    however the feed spells it; TfL's own name for the same line can differ again. So a line
 *    that must read one way from every source is pinned here rather than by each name.
 * 3. Otherwise, an operator whose name carries **more than one capital letter** — i.e. a
 *    multi-word brand — is those capitals: East Midlands Railway → EMR, Great Western Railway
 *    → GWR, London North Eastern Railway → LNER (four chars, the widest the pill holds),
 *    Greater Anglia → GA, Avanti West Coast → AWC, Great Northern → GN. This is the initialism
 *    a rider sees on the train and beats the cryptic legacy TOC codes (EM, GW, GR, LE, VT) for
 *    exactly these. It assumes TfL's Title-Case operator names; if one ever arrives ALL CAPS,
 *    pin it in step 1.
 *
 * A single-capital name not pinned in step 1 (a future single-word operator) is left to the
 * first-three-letters fallback. c2c never reaches here — it carries a digit and stays verbatim
 * ("c2c", its full brand name, which fits) via [lineCode]'s number path. Elizabeth line and
 * London Overground are their own modes, not national-rail; even were one tagged national-rail
 * it is unpinned and single-capital, so it falls back to ELI / LON.
 */
private fun railOperatorCode(operator: String, lineId: String): String? {
    val key = normalizeOperator(operator)
    railOperatorExceptions[key]?.let { return it }
    railOperatorPrefixes.firstOrNull { (prefix, _) -> key.startsWith(prefix) }?.let { return it.second }
    railLineCodes[lineId.lowercase()]?.let { return it }
    val initials = operator.filter { it.isUpperCase() }
    return if (initials.length > 1) initials else null
}

/**
 * Hand-pinned National Rail codes, keyed by a punctuation- and case-insensitive form of the
 * operator name (see [normalizeOperator]). Add an entry whenever the capital-initials heuristic
 * ([railOperatorCode]) produces something wrong or unwanted. These want a final eyeball against
 * a real device — TfL egress isn't reachable from CI to enumerate the live set.
 *
 * The pins so far:
 * - single-word operators whose first-three-letters collide (Southern/Southeastern → SOU) take
 *   their official TOC code (SN, SE); Thameslink (TL) rides along for a consistent single-word set;
 * - the Express services take the "…X" TOC code (Gatwick GX, Heathrow HX) — nicer than the plain
 *   initials GE/HE;
 * - CrossCountry takes its TOC code XC rather than the heuristic's "CC", to stay clear of c2c.
 * - the rail feed names West Midlands Trains' line "LNR & WMR", whose capitals (LNRWMR) overflow the
 *   pill, so it takes LNR (maintainer, 2026-09-30): every train it runs from London is LNR. The
 *   same line under any other name takes LNR from [railLineCodes]; West Midlands Railway, named
 *   on its own, keeps its brand's WMR.
 * - London Northwestern Railway (LNR) is in [railOperatorPrefixes] instead: matched by prefix, since
 *   the capitals read differently depending on how the feed spells the name.
 */
private val railOperatorExceptions: Map<String, String> = mapOf(
    "southern" to "SN",
    "southeastern" to "SE",
    "thameslink" to "TL",
    "gatwickexpress" to "GX",
    "heathrowexpress" to "HX",
    "crosscountry" to "XC",
    "lnrwmr" to "LNR",
    "westmidlandsrailway" to "WMR",
)

/**
 * Codes pinned to a TfL rail line id, for a line whose names don't all yield the code it should
 * read: West Midlands Trains' line is "LNR & WMR" on a rail board and "West Midlands Trains" in
 * TfL's line list, and reads LNR under both.
 */
private val railLineCodes: Map<String, String> = mapOf(
    "west-midlands-trains" to "LNR",
)

/**
 * Hand-pinned codes matched by the start of the normalized operator name, for an operator whose
 * name the rail feed spells more than one way. London Northwestern Railway showed up as a
 * truncated "LNR…" pill, so its spelling there yields a code of five or more letters from the
 * capitals heuristic. Matching on "londonnorthwestern" takes every spelling to LNR, the short
 * name the operator itself uses (its sister brand, West Midlands Railway, is WMR by the
 * heuristic), rather than betting on one.
 */
private val railOperatorPrefixes: List<Pair<String, String>> = listOf(
    "londonnorthwestern" to "LNR",
)

/** An operator name reduced to lowercase letters and digits, so "South Western Railway",
 *  "south western railway" and stray punctuation all key the same [railOperatorExceptions]
 *  entry. */
private fun normalizeOperator(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }
