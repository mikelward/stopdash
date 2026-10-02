package app.stopdash.domain

/**
 * Which stations on a line's page its service alert names (SPEC *Disruptions*): a first guess at
 * the stretch a diversion or closure touches, since TfL gives it only as prose ("Diverted between
 * Moorgate and Monument") with no structured list of affected stops.
 *
 * A match is the station's name as a whole phrase in the text, compared without case but required
 * to start with a capital, since TfL writes place names as proper nouns: that keeps "bank" in "the
 * river bank" from marking Bank. A name followed by a word that makes it something other than a
 * place — "Victoria line", "Bank branch", "Hammersmith & City", "Bank Holiday" — is not a match,
 * because TfL's alerts name lines and branches far more often than those stations.
 *
 * A guess, and deliberately a generous one: a station marked that the alert only mentions in
 * passing costs a glance, where one it misses leaves the rider to read the prose anyway. So it
 * only ever adds a marker and never hides or reorders anything.
 */
object AlertStops {
    /** The ids of [stops] whose names appear in [text]; empty when there is no text. */
    fun mentioned(text: String?, stops: List<RouteStop>): Set<String> {
        if (text.isNullOrBlank() || stops.isEmpty()) return emptySet()
        // The two lines named after a station and "City" are taken out first, so neither marks its
        // station — and a station followed by another starting "City" ("Bank and City Thameslink") is
        // still a station. The placeholder is one word in capitals, so a list of lines around it keeps
        // its shape ("Victoria and Hammersmith & City lines") and an all-caps clause stays all caps.
        val haystack = normalize(text).replace(CITY_LINES, "CITYLINE")
        val aliases = stops.associateWith { names(it.name) }
        val all = aliases.values.flatten().toSet()
        // The same text with every station name on the page in capitals, so an all-caps template that
        // quotes stops in their own case ("… WILL MISS STOPS Camomile Street AND Fenchurch Street")
        // still reads as all caps. Same length, so an index into one is an index into the other.
        val capped = all.fold(haystack) { acc, name ->
            place(name).replace(acc) { match -> match.value.map(Char::uppercaseChar).joinToString("") }
        }
        // Where each name is in the text, searched once: a name inside a longer one ("Bank" in "Bank
        // Station") is checked against these, rather than every longer name on the route being searched
        // again for every match, which grew with the square of the route's length.
        val spans = all.associateWith { name -> place(name).findAll(haystack).map { it.range }.toList() }
        return stops.filterTo(LinkedHashSet()) { stop ->
            aliases.getValue(stop).any { name ->
                named(haystack, capped, name, spans)
            } || crossNames(stop.name).any { name ->
                // Only with its letter: "Moorgate Station (L)" is a stop, a bare "Moorgate Station" may
                // be the road or the station, not the stop listed under another street's name.
                Patterns.of("""${place(name).pattern}$STATION'?$LETTER""", ignoreCase = true).findAll(haystack)
                    .any { haystack[it.range.first].isUpperCase() }
            }
        }.mapTo(LinkedHashSet(), RouteStop::id)
    }

    /**
     * [mentioned], and every station between two of them where the alert gives the stretch it
     * touches — "between Moorgate and Monument", "Moorgate to Monument" — with [stops] in route order,
     * as a train's stop list is: a station between the named ends is in the stretch too, though the
     * alert doesn't name it. Every stretch the alert gives, whatever the sentence says of it
     * (maintainer, 2026-10-01): telling a stretch that still runs from one that doesn't is open-ended
     * prose, and marking one too many costs a glance, as the naming does. Ends that aren't both on
     * the page give no stretch, since what lies between them off the page isn't known. And every
     * stop [stretched] reads as not served, the stops a curtailment leaves out included: what a trip
     * leaves an alert out of a ride by is marked here too, so the page and the trip read an alert the
     * same way (maintainer, 2026-10-01).
     */
    fun affected(text: String?, stops: List<RouteStop>): Set<String> {
        val named = mentioned(text, stops)
        val marked = named + stretches(text, stops, named) + stretched(text, stops)
        return stops.mapNotNullTo(LinkedHashSet()) { stop -> stop.id.takeIf { it in marked } }
    }

    /**
     * [marked] stops of [stops] (in route order) as the runs they make, each by its ends: "A to B" for
     * a run, "A" for a stop alone (maintainer, 2026-10-01), each stop called by [name]. A run whose
     * ends share a name is that name once, and a label already given isn't given again.
     */
    fun runs(marked: Set<String>, stops: List<RouteStop>, name: (RouteStop) -> String): List<String> {
        val runs = ArrayList<List<RouteStop>>()
        var run = ArrayList<RouteStop>()
        for (stop in stops) {
            if (stop.id in marked) {
                run += stop
            } else if (run.isNotEmpty()) {
                runs += run
                run = ArrayList()
            }
        }
        if (run.isNotEmpty()) runs += run
        return runs.map { listOf(name(it.first()), name(it.last())).distinct().joinToString(" to ") }.distinct()
    }

    /**
     * Only the stops of [stops] (in route order) inside a stretch [text] gives, ends included:
     * none where it gives no stretch, however many stops it names, and only a stretch in TfL's words
     * for stops not served ([SKIPPED]: "not serving stops between …"). What places an alert
     * on part of a route, so unlike [affected] it is strict: a stop it merely names may be an aside
     * ("near Moorgate"), and a stretch may be one running normally ("Good service between …"), neither
     * where it applies (Codex, PR #455).
     */
    fun stretched(text: String?, stops: List<RouteStop>): Set<String> {
        val named = mentioned(text, stops)
        val read = ArrayList<IntRange>()
        val cut = curtailed(text, stops, named, read)
        val marked = stretches(text, stops, named, affecting = true, read) + missed(text, stops, named, read) + cut
        // Nor where the alert says stops are missed in words other than these ("… and are not serving
        // Victoria"): those may be the ride's (Codex, PR #455).
        val rest = StringBuilder(normalize(text.orEmpty()).replace(CITY_LINES, "CITYLINE"))
        read.forEach { range -> for (i in range) rest.setCharAt(i, ' ') }
        if (rest.split(*CLAUSE_ENDS).any(::unreadSkip)) return emptySet()
        // Nor where a clause it read names another of the route's stops, besides those its read words
        // name ("not serving stops between A and B and stops C and D"): in the same breath as stops not
        // served, that stop may be one too, however the clause joins it on (Codex, PR #455). One named
        // again is the same stop ("terminating at the stop B 'V' after stop B 'J'"), and one in a list of
        // where buses head ("Towards North End, …") isn't one ([mentioned]).
        val residue = rest.toString()
        val normalized = normalize(text.orEmpty()).replace(CITY_LINES, "CITYLINE")
        val placed = mentioned(read.joinToString(". ") { normalized.substring(it.first, it.last + 1) }, stops)
        if (clauses(residue).any { range -> read.any { it.first in range } && !placed.containsAll(mentioned(residue.substring(range), stops)) }) {
            return emptySet()
        }
        // Nor a curtailment it couldn't place: where buses turn back isn't known unless the alert says
        // where they terminate or start. Each one placed was read, so what's left is what wasn't. Only
        // the route itself cutting short ("Route 99 is cutting short of its normal route") is the summary
        // of those the alert goes on to place, where it places any. Anything narrower ("Buses towards
        // South End", "Some journeys") may be a curtailment of its own, unplaced whatever else is
        // (Codex, PR #455).
        val unplaced = clauses(residue).map(residue::substring).filter { CURTAILMENT.containsMatchIn(it) }
        if (unplaced.any { cut.isEmpty() || !ROUTE_SUMMARY.containsMatchIn(it) || NARROWER.containsMatchIn(it) }) return emptySet()
        // Every stretch the alert gives, or none: one the words don't tie to affected service ("… not
        // serving stops between A and B and between C and D") may be affected too, so where any such
        // reaches stops the confirmed ones don't, where the alert applies isn't known (Codex, PR #455).
        if (!marked.containsAll(stretches(text, stops, named))) return emptySet()
        return stops.mapNotNullTo(LinkedHashSet()) { stop -> stop.id.takeIf { it in marked } }
    }

    // The stops a curtailment leaves out, in TfL's words ([CUT_SHORT]): after where buses terminate,
    // and before where they start again. Each applies to every route of [stops] rather than only the
    // direction it names, so a curtailment's other end can only keep an alert on.
    private fun curtailed(text: String?, stops: List<RouteStop>, named: Set<String>, read: MutableList<IntRange>): Set<String> {
        if (text == null) return emptySet()
        val haystack = normalize(text).replace(CITY_LINES, "CITYLINE")
        val marked = HashSet<String>()
        for ((k, stop) in stops.withIndex()) {
            if (stop.id !in named) continue
            val name = ref(stop.name)
            for ((pattern, after) in CUT_SHORT) {
                val matches = Patterns.of(pattern.replace("STOP", name), ignoreCase = true).findAll(haystack).filter { affirmed(haystack, it) }.toList()
                if (matches.isEmpty()) continue
                matches.mapTo(read) { it.range }
                (if (after) stops.subList(k + 1, stops.size) else stops.subList(0, k)).mapTo(marked) { it.id }
            }
        }
        return marked
    }

    // The stops of [named] that [text] says a bus will miss, named one by one, in TfL's words ([MISSED]).
    private fun missed(text: String?, stops: List<RouteStop>, named: Set<String>, read: MutableList<IntRange>): Set<String> {
        if (text == null) return emptySet()
        val haystack = normalize(text).replace(CITY_LINES, "CITYLINE")
        return stops.filter { it.id in named }.mapNotNullTo(HashSet()) { stop ->
            val matches = MISSED.flatMap { Patterns.of(it.replace("STOP", ref(stop.name)), ignoreCase = true).findAll(haystack).filter { affirmed(haystack, it) } }
            matches.mapTo(read) { it.range }
            stop.id.takeIf { matches.isNotEmpty() }
        }
    }

    // The stops between two of [named] that [text] gives a stretch between; where [affecting], only
    // one in TfL's words for stops not served ([SKIPPED]): "Good service between A and B" is a stretch
    // the alert isn't about, so it places nothing (Codex, PR #455).
    private fun stretches(
        text: String?,
        stops: List<RouteStop>,
        named: Set<String>,
        affecting: Boolean = false,
        read: MutableList<IntRange> = ArrayList(),
    ): Set<String> {
        if (text == null || named.size < 2) return emptySet()
        val haystack = normalize(text).replace(CITY_LINES, "CITYLINE")
        val ends = stops.withIndex().filter { (_, stop) -> stop.id in named }
        val marked = HashSet<String>()
        for ((i, from) in ends) {
            for ((j, to) in ends) {
                if (j > i && stretch(haystack, from.name, to.name, affecting, read)) {
                    stops.subList(i, j + 1).mapTo(marked) { it.id }
                }
            }
        }
        return marked
    }

    // Whether [haystack] gives a stretch from stop [a] to stop [b] ([ref]), either way round;
    // where [affecting], only in the words TfL uses for stops a bus won't serve ([SKIPPED]).
    // The spans it read go in [read].
    private fun stretch(haystack: String, a: String, b: String, affecting: Boolean, read: MutableList<IntRange>): Boolean {
        val patterns = listOf(a to b, b to a).flatMap { (x, y) ->
            val from = ref(x)
            val to = ref(y)
            if (affecting) {
                SKIPPED.map { Patterns.of(it.replace("FROM", from).replace("TO", to), ignoreCase = true) }
            } else {
                listOf(
                    Patterns.of("""\bbetween\s+$from\s+and\s+$to""", ignoreCase = true),
                    Patterns.of("""${ref(x, quoted = false)}\s+to\s+$to""", ignoreCase = true),
                )
            }
        }
        val matches = patterns.flatMap { pattern -> pattern.findAll(haystack).filter { !affecting || affirmed(haystack, it) }.toList() }
        matches.mapTo(read) { it.range }
        return matches.isNotEmpty()
    }

    // The name as listed and as a rider would write it (no "Underground Station", no line
    // parenthetical), each folded the same way as the alert text. A bus stop is listed with its cross
    // street ("Camomile Street / Bishopsgate") where an alert names only the stop's own part, so the
    // part before the slash counts too — but not the part after, which names another road. A place
    // TfL qualifies in brackets ("Stratford (London)") is written without them in an alert, so the
    // name before the bracket counts too. And a bus stop as its sign reads, the way an alert quotes
    // one ([signed]).
    private fun names(name: String): Set<String> {
        val primary = name.substringBefore(" / ").trim()
        val unqualified = cleanStopName(primary).substringBefore(" (").trim()
        return (setOf(name, cleanStopName(name), primary, cleanStopName(primary), unqualified) + signed(name))
            .map { normalize(it).replace("'", "") }.filterTo(LinkedHashSet()) { it.length >= MIN_NAME }
    }

    // A bus stop listed with its cross street, as its sign reads and an alert quotes it: a route lists
    // "Bank / King William Street" ([cleanStopName] takes "Station" off each part), where TfL's alert
    // has "'Bank Station/King William Street'" (maintainer, 2026-10-02). Each part with "Station" or
    // without, the slash spaced or not.
    private fun signed(name: String): List<String> {
        val parts = name.split(" / ").map(String::trim)
        if (parts.size != 2 || parts.any(String::isEmpty)) return emptyList()
        val (own, cross) = parts.map { listOf(it, "$it Station") }
        return own.flatMap { a -> cross.flatMap { b -> listOf("$a/$b", "$a / $b") } }
    }

    // The part of a bus stop's listed name after its slash ("Finsbury Square / Moorgate Station"):
    // another road, as [names] says, or the station it serves, which an alert may call the stop by
    // ("missing stops from Moorgate Station (L)"; maintainer, 2026-10-01). Taken only with the stop's
    // letter after it, which says it is a stop and not the road ([ref], [mentioned]).
    private fun crossNames(name: String): Set<String> {
        val primaries = names(name)
        return name.split(" / ").drop(1)
            .flatMap { listOf(it.trim(), cleanStopName(it.trim()).substringBefore(" (").trim()) }
            .map { normalize(it).replace("'", "") }
            .filterTo(LinkedHashSet()) { it.length >= MIN_NAME && it !in primaries }
    }

    // A reference to the stop named [name] in an alert: by one of its [names], quoted or not and with
    // its letter or none ("'Example Road' (E)"), or by one of its [crossNames] with its letter. A bus
    // alert quotes its stops ("between 'Bank Station' and 'Moorgate Station'"), so where [quoted] a
    // quote may open the reference. Either may carry "Station" where the route's name doesn't, and the
    // alert's own cross street before its slash ("King William Street / Monument Station (G)" for a
    // route's "Monument"; maintainer, 2026-10-01).
    private fun ref(name: String, quoted: Boolean = true): String {
        val open = if (quoted) "'?" else ""
        val own = """$open$CROSSED(?:${names(name).joinToString("|") { place(it).pattern }})$STATION'?$STOP_LETTER"""
        val cross = crossNames(name).takeIf { it.isNotEmpty() } ?: return "(?:$own)"
        return """(?:$own|$open$CROSSED(?:${cross.joinToString("|") { place(it).pattern }})$STATION'?$LETTER)"""
    }

    // Whether [name] appears in [haystack] as a place — not as part of one of the [longer] names of
    // the page's other stations ("Stratford" inside "Stratford International").
    private fun named(haystack: String, capped: String, name: String, spans: Map<String, List<IntRange>>): Boolean =
        spans.getValue(name).any { range ->
            val at = range.first
            val end = range.last + 1
            val rest = haystack.substring(end)
            when {
                haystack[at].isLowerCase() -> false
                spans.any { (other, at2) -> other.length > name.length && at2.any { at in it } } -> false
                NOT_A_PLACE.containsMatchIn(rest) -> false
                (LIST_OF_LINES.containsMatchIn(rest) || inTowardsList(capped, at)) &&
                    !shouting(capped, at) -> false
                else -> true
            }
        }

    // [name] as a whole phrase, ignoring case and apostrophes: the alert keeps its own, and [name]
    // has none, so "Earls Court" and "Earl's Court" match each other either way round. A possessive
    // "'s" straight after it still ends the phrase ("Victoria's platforms"). A quote opening it
    // counts as a break ("between 'Bank Station' and 'Moorgate Station'", as bus alerts quote their
    // stops), an apostrophe inside a word doesn't.
    private fun place(name: String): Regex = Patterns.of(
        """(?<![\p{L}\p{N}])(?<![\p{L}\p{N}]')""" + name.map { Regex.escape(it.toString()) }.joinToString("'?") +
            """(?=(?:'s)?(?![\p{L}\p{N}]))""",
        ignoreCase = true,
    )

    // One spelling for the variants TfL mixes within a single alert: curly and straight apostrophes,
    // "&" and "and", "St." and "St", and runs of spaces.
    //
    // A line break (TfL's escaped "\n" included) ends a clause, as a full stop does: TfL often
    // separates clauses with one and no punctuation ("Buses towards London Bridge\nLondon Bridge
    // Station is closed"). It is kept as "; " rather than a space, so every check that stops at a
    // clause end — the "towards" list behind a name, the list of lines after it — stops there too.
    private val LINE_BREAK = Regex("""\s*\n\s*""")
    private val AMPERSAND = Regex("""\s+&\s+""")
    private val SLASH = Regex("""\s*/\s*""")
    private val SAINT = Regex("""\b(St)\.""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")

    private fun normalize(s: String): String = s
        .replace("\\n", "\n")
        .replace(LINE_BREAK, "; ")
        .replace('’', '\'')
        .replace(AMPERSAND, " and ")
        // A stop and its cross street, however spaced ("Bank Station/King William Street"), read as
        // TfL lists the stop ("Bank Station / King William Street").
        .replace(SLASH, " / ")
        // Any case, keeping the letters' own: an all-caps alert writes "ST. PAUL'S".
        .replace(SAINT, "$1")
        .replace(SPACES, " ")
        .trim()

    // Whether the clause around [at] is written all in capitals. That loses the signal that tells a
    // place name from an ordinary word, so the checks that lean on it (a "towards" list, a list of
    // lines) are skipped there and every name in it is marked: a destination marked too costs a
    // glance, where a missed affected stop is the failure this exists to avoid. Judged per clause, so
    // lowercase prose elsewhere in the alert doesn't switch it off, on the [capped] text, so neither
    // do station names quoted in their own case, and without a bare "and", which [normalize] writes
    // for "&".
    private fun shouting(capped: String, at: Int): Boolean {
        val start = capped.lastIndexOfAny(CLAUSE_ENDS, at - 1) + 1
        val end = capped.indexOfAny(CLAUSE_ENDS, at).let { if (it < 0) capped.length else it }
        return capped.substring(start, end).replace(AND, "").none(Char::isLowerCase)
    }

    // Whether the name at [at] is one of the destinations in a "towards Lewisham and London Bridge"
    // list: that says which way the affected buses run, not where the disruption is. The list is the
    // run after the nearest "towards" in the same sentence made only of capitalized words, commas,
    // "and" and "or" — anything else (a verb, "will miss stops") means the name is past the list.
    // Read on the capped text, so a station's own lowercase word ("Prince of Wales Road") doesn't
    // end the list early.
    private fun inTowardsList(haystack: String, at: Int): Boolean {
        val sentence = haystack.lastIndexOfAny(CLAUSE_ENDS, at - 1) + 1
        val towards = haystack.lastIndexOf("towards ", at, ignoreCase = true)
        if (towards < sentence) return false
        val between = haystack.substring(towards + "towards ".length, at)
        return between.split(' ', ',').filter(String::isNotEmpty)
            .all { it == "and" || it == "or" || it.first().isUpperCase() }
    }

    private val CLAUSE_ENDS = charArrayOf('.', '?', '!', ':', ';')

    // [text]'s clauses, as the ranges between its [CLAUSE_ENDS].
    private fun clauses(text: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var start = 0
        for ((i, c) in text.withIndex()) {
            if (c in CLAUSE_ENDS) {
                ranges += start until i
                start = i + 1
            }
        }
        ranges += start until text.length
        return ranges
    }

    // A bus stop's letter after its name, bracketed or quoted ("'Example Road' (E)", "Example Road 'E'"),
    // or both poles' letters ("Example Road (BN and BP)").
    private const val LETTER =
        """(?:\s*(?:\([A-Z0-9]{1,3}(?:\s*(?:,|and|/)\s*[A-Z0-9]{1,3})*\)|'[A-Z0-9]{1,3}'))"""

    // A word "Station" an alert adds to a stop's name ("Moorgate Station" for a route's "Moorgate").
    private const val STATION = """(?:\s+station)?"""

    // The cross street an alert may put before a stop's name, with its slash: "King William Street / ".
    private const val CROSSED = """(?:[\p{L}\p{N}][\p{L}\p{N}'&.\-]*(?:\s+[\p{L}\p{N}&][\p{L}\p{N}'&.\-]*){0,5}\s+/\s+)?"""

    // [LETTER], where a stop may carry one or not.
    private const val STOP_LETTER = "$LETTER?"

    // The words TfL's bus alerts use for a stretch of stops not served, as seen in the wild
    // (maintainer, 2026-10-01): "Buses are not serving stops between 'A' and 'B'", and "the stops
    // from 'A' (E) to 'B' will not be served", "Stops between 'A' (H) and 'B' (CL) will not be
    // served", "Bus stops from 'A' to 'B' (PR) will be missed", and "Buses are missing stops from A 'F'
    // to B 'T'" (or "missing A 'E' to B (T)", "missing stops between A (BN and BP) and B (M and P)", or
    // several segments), and "The 'Hail & Ride' section from A
    // to B are not being served". Only these place an alert, so wording nobody has seen
    // TfL use places nothing and the alert stays on: reading free prose for where service is affected
    // kept misreading it ("good service between", "will not be diverted between", "diverted except
    // between"; Codex, PR #455). FROM and TO stand for the ends.
    private const val NOT_SERVED = """(?:(?:will\s+not\s+be|are\s+not\s+being)\s+served|(?:will\s+be|are\s+being)\s+missed)\b"""

    private val SKIPPED = listOf(
        """\b(?:(?:are\s+)?not\s+serving|will\s+not\s+serve)\s+(?:the\s+)?stops\s+between\s+FROM\s+and\s+TO""",
        """\b(?:the\s+)?(?:(?:bus\s+)?stops|'?hail\s+and\s+ride'?\s+section)\s+from\s+FROM\s+to\s+TO\s+$NOT_SERVED""",
        """\b(?:the\s+)?(?:bus\s+)?stops\s+between\s+FROM\s+and\s+TO\s+$NOT_SERVED""",
        """\bmissing\s+(?:the\s+)?(?:stops\s+from\s+)?FROM\s+to\s+TO""",
        """\bmissing\s+(?:the\s+)?stops\s+between\s+FROM\s+and\s+TO""",
        // A later segment of the same list: "missing the stops from A 'CA' to B 'T' northbound, and from
        // C 'U' to D 'CB' southbound", "… and stops from C to D towards South End".
        """\bmissing\s+(?:the\s+)?stops\s+from\s+[^.;:?!]*?\band\s+(?:the\s+)?(?:stops\s+)?from\s+FROM\s+to\s+TO""",
    )

    // TfL's words for stops a bus won't serve, named one by one, as seen in the wild (maintainer,
    // 2026-10-01): "Buses will divert via …, missing the stop 'St Paul's Station' (SP)", "Buses
    // towards … will miss stop Millbank", "Buses will not serve stops 'A' (F) and 'B' (W)", "Bus
    // stop 'A' (R) will not be served", and "the stops 'A' (U) and 'B' (H) will not be served". STOP stands for one of them, anywhere in the list the words
    // open, up to the clause's end; "stops between" is a stretch ([SKIPPED]), not a list.
    private val MISSED = listOf(
        """\b(?:(?:missing|will\s+miss)(?:\s+the)?|will\s+not\s+serve(?:\s+the)?|(?:are\s+)?not\s+serving(?:\s+the)?)\s+stops?(?!\s+(?:between|from)\b)\s+""" +
            """(?:[^.;:?!]*?(?:,|\band\b)\s+)?STOP""",
        """\b(?:the\s+)?(?:bus\s+)?stops?(?!\s+(?:between|from)\b)\s+(?:[^.;:?!]*?(?:,|\band\b)\s+)?STOP[^.;:?!]*?\s+$NOT_SERVED""",
    )

    // TfL's words for a curtailment, as seen in the wild (maintainer, 2026-10-01), each with whether
    // the stops left out are after STOP (where buses terminate) or before it (where they start):
    // "Route 99 is curtailed to Alpha Road 'J'", "Buses towards North End are terminating at the stop
    // Beta Road 'V'", "will terminate at 'Beta Road' (D)", "starting services towards South End from
    // stop Alpha Road 'G'", and "will start from stop at 'Gamma Road' (C)". Not where negated ("will not
    // terminate at …", see [affirmed]), which says where buses don't stop short (Codex, PR #455).
    private val CUT_SHORT = listOf(
        """(?<!\bnot\s)(?<!n't\s)\b(?:terminat(?:e|es|ing)\s+at|curtailed\s+to)\s+(?:the\s+)?(?:stop\s+)?(?:at\s+)?STOP""" to true,
        """(?<!\bnot\s)(?<!n't\s)\bstart(?:s|ing)?\s+(?:services\s+)?(?:towards\s+[^,.;]*?\s+)?from\s+(?:the\s+)?(?:stop\s+)?(?:at\s+)?STOP""" to false,
    )

    // Whether [match] is said outright: nothing earlier in its clause negates it ("buses are not
    // expected to terminate at …"). Its own words may ("will not be served"). The whole clause rather
    // than the word before, so no length of phrasing gets a negation past it (Codex, PR #455).
    private fun affirmed(haystack: String, match: MatchResult): Boolean {
        val start = haystack.lastIndexOfAny(CLAUSE_ENDS, match.range.first - 1) + 1
        return !NEGATED.containsMatchIn(haystack.substring(start, match.range.first))
    }

    private val NEGATED = Regex(NEGATION, RegexOption.IGNORE_CASE)

    // Words for stops missed, left over once the wordings read above are taken out: a clause negating
    // any serving, stopping or calling ("not serving", "won't stop", "cannot serve", "are not expected
    // to serve", "unable to call"; [NEGATION] anywhere in it, however far from the verb), or "miss",
    // "skip", "bypass" — but not "serving" ("via Example Road (serving Bus Stop J)"), nor a negated
    // miss ("Routes 98 and 99 also follow the diversion but do not miss any stops").
    private fun unreadSkip(clause: String): Boolean =
        (NEGATED.containsMatchIn(clause) && SERVICE_VERB.containsMatchIn(clause)) || MISSED_VERB.containsMatchIn(clause)

    // Serving, stopping or calling as a verb ("the stops" is a noun, and "do not miss any stops" says
    // nothing is missed).
    private val SERVICE_VERB = Regex("""\b(?:serv\w*|stop(?:s?\s+at|ping|ped)?|call(?:s|ing|ed)?)\b(?!\s+stops?\b)""", RegexOption.IGNORE_CASE)

    // A curtailment said of the route as a whole: "Route 99 is cutting short", "Routes 98 and 99 are curtailed".
    private val ROUTE_SUMMARY = Regex("""\broutes?\s+[\w\s,&]*?\b(?:is|are)\s+(?:being\s+)?(?:cut|curtail)""", RegexOption.IGNORE_CASE)

    // Words narrowing a curtailment to some of a route's buses: a direction, some of its journeys.
    private val NARROWER = Regex("""\b(?:towards|some|certain|selected|a\s+few|most|journeys|trips|buses)\b""", RegexOption.IGNORE_CASE)

    // A curtailment in any words, read or not ([CUT_SHORT] places one).
    private val CURTAILMENT = Regex(
        """\b(?:cut(?:ting|s)?\s+short|curtail\w*|terminat\w*|$START_FROM_STOP)""",
        RegexOption.IGNORE_CASE,
    )

    private val MISSED_VERB = Regex(
        """(?<!\bnot\s)(?<!n't\s)\b(?:miss(?:es|ed|ing)?|skip(?:s|ped|ping)?|bypass(?:es|ed|ing)?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val AND = Regex("""\band\b""")

    // A word straight after a name that makes it a line, a branch or a holiday rather than a place.
    private val NOT_A_PLACE = Regex("""^\s+(lines?|branch(es)?|holidays?)\b""", RegexOption.IGNORE_CASE)

    // The Hammersmith & City and Waterloo & City lines, as [normalize] spells them — but not where
    // another name follows ("Waterloo and City Thameslink" is two stations), "line" aside.
    private val CITY_LINES =
        Regex("""(?i:\b(Hammersmith|Waterloo) and City\b)(?!\s+(?!(?i:lines?)\b)\p{Lu})""")

    // A name that leads a list of lines or branches — "Victoria and Piccadilly lines", "Bank,
    // Charing Cross and Kennington branches" — is a line too: more capitalized names joined by
    // commas, "and" or "or", then "lines" or "branches". Case-sensitive, so a lowercase word ends
    // the list.
    private val LIST_OF_LINES =
        Regex("""^(?:,?\s+(?:(?:and|or)\s+)?\p{Lu}[\p{L}'.-]*)+\s+(?:[Ll]ines|[Bb]ranch(?:es)?)\b""")

    // Shorter than this, a name is more likely a fragment of another word or phrase than a station.
    private const val MIN_NAME = 3
}
