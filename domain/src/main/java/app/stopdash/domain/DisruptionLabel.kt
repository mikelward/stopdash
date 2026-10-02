package app.stopdash.domain

/**
 * A line disruption resolved to what the surfaces need: the short chip [label] and a
 * [severity] on TfL's scale (lower is more disruptive) for choosing between several
 * coexisting statuses.
 *
 * [isFallback] marks the one case that carries no real information — a catch-all status
 * ("Special Service") whose reason named nothing, so all we can say is "something is
 * wrong". That is the *only* thing ranked below every named status regardless of severity
 * (see [mostSevereDisruption]). A status that merely lacks wording — a blank description at
 * a real TfL severity — is **not** a fallback: it keeps its severity and the generic label,
 * so a severe-but-unworded closure is never demoted behind a milder named status.
 */
data class ResolvedDisruption(
    val label: String,
    val severity: Int,
    val isFallback: Boolean = false,
    // The entry's own free-text reason (trimmed), carried through so the chosen disruption's
    // prose reaches [LineStatus.fullText] for the route detail view — the compact label is
    // [label], the prose is this. Blank when TfL gave no reason for the entry.
    val fullText: String = "",
    // The label was read out of a catch-all's reason ("Special Service" → "Diversion"), not worded
    // by TfL: its severity is ours, borrowed from the closure it ranks beside, so it's never taken
    // for a part closure TfL places on a stretch (Codex, PR #446).
    val inferred: Boolean = false,
)

/**
 * Resolves one TfL line-status entry to its chip label and a comparable severity.
 *
 * A status TfL already words clearly ("Part Closure", "Suspended", "Severe Delays") is
 * taken as-is at TfL's own [statusSeverity]. Its vague catch-all — "Special Service" on a
 * bus (severity 0) — names nothing: the real state (usually a diversion) lives only in the
 * free-text [reason], which is scanned for a concise label. That inferred label carries its
 * own severity on TfL's scale, so a diversion read out of a severity-0 catch-all is compared
 * honestly against a graded status rather than either always winning (severity 0 sorts as
 * most severe) or always losing to it. When the text names nothing, the entry resolves to a
 * low-priority "Service Alert" — never the meaningless "Special Service" — and the line
 * still counts as disrupted.
 *
 * Choosing between coexisting entries is the caller's job: resolve each, then take the one
 * with the lowest [severity]. Because every entry — graded or catch-all — is reduced to the
 * same (label, severity) shape, there is no special case for the severity-0 anomaly and no
 * "which entry sorted first" hazard.
 *
 * TfL exposes no structured field for the catch-all (its `closureText` is null, `category`
 * only "PlannedWork"/"RealTime"), so the prose is the only source. Whether an alert is
 * current is not judged here — TfL's `isNow` reads false even for closures in effect, so
 * that needs the dates in the text (`TODO.md` Phase 3).
 */
fun resolveDisruption(
    statusSeverityDescription: String,
    statusSeverity: Int,
    reason: String,
): ResolvedDisruption {
    val description = statusSeverityDescription.trim()
    // Anything TfL words itself keeps its own label and severity. A blank description is a
    // real disruption missing its wording, not the vague catch-all, so it keeps its severity
    // and only borrows the generic label — it is NOT a fallback, so a severe-but-unworded
    // status still ranks by that severity and is never demoted behind a milder named one.
    if (description.lowercase() !in CATCH_ALL_DESCRIPTIONS) {
        return ResolvedDisruption(description.ifBlank { SERVICE_ALERT_LABEL }, statusSeverity, fullText = reason.trim())
    }
    // The "Special Service" catch-all: infer the label from the reason, taking the most
    // severe when several are named, and let that label supply the severity. When nothing is
    // named it is a true fallback — flagged so it sorts below every informative status.
    val text = reason.lowercase()
    return DISRUPTION_KEYWORDS
        .filter { keyword ->
            keyword.needles.any { saidOutright(text, Regex.escape(it)) } ||
                // In the reason as written: a pattern's place is told from a time by its capital (Codex, PR #455).
                keyword.patterns.any { saidOutright(text, it, cased = reason) }
        }
        .minByOrNull { it.severity }
        ?.let { ResolvedDisruption(it.label, it.severity, fullText = reason.trim(), inferred = true) }
        ?: ResolvedDisruption(SERVICE_ALERT_LABEL, SERVICE_ALERT_SEVERITY, isFallback = true, fullText = reason.trim(), inferred = true)
}

// Whether [needle] appears in [text] (lowercased) without a negation governing it: one earlier in the
// same run of words, with no punctuation, "and" or "but" between ("no" included: "no buses are
// diverted between …"; Codex, PR #455). An "or" carries the negation on: "not diverted or curtailed"
// denies both (Codex, PR #455). "Buses aren't terminating at …"
// and "the service is not expected to be cut short" name no curtailment, however many words lie
// between (Codex, PR #455). A negation of something else doesn't reach it: missing a real diversion
// downgrades it to a generic alert that never sounds, so "buses are not serving stops A and B and are
// diverted via X" still names one (Codex, PR #455).
// Nor one said after it, in its own clause, to have ended: "the diversion is no longer required", "…
// has ended", "… has been lifted" (Codex, PR #455).
// [pattern] is a regex, matched in [cased] where given: [text] before it was lowercased, the same
// length for every alphabet TfL writes in, so a match there is negated or not as in [text].
private fun saidOutright(text: String, pattern: String, cased: String = text): Boolean =
    Patterns.of(pattern).findAll(cased).any { match ->
        !NEGATED_JUST_BEFORE.containsMatchIn(text.substring(0, minOf(match.range.first, text.length))) &&
            !ENDED.containsMatchIn(clauseAfter(text, match.range.last + 1))
    }

private val NEGATED_JUST_BEFORE =
    Regex("""$NEGATION(?:\s+(?!(?:and|but)\b)[\w'’]+)*\s*$""")

// [text] from [start] to the end of its clause: punctuation, or a word that starts another clause, so
// "diverted because Beta Road is no longer open" keeps the diversion.
private fun clauseAfter(text: String, start: Int): String =
    text.substring(minOf(start, text.length)).split(CLAUSE_END, limit = 2).first()

private val CLAUSE_END =
    Regex("""[.;:,!?]|\b(?:and|but|or|because|as|since|while|whilst|due|where|when|which|until|after|before|so|owing|if|though|although)\b""")

// A clause saying what it follows has ended: "is no longer …", "is not required", "has (now) ended",
// "has been lifted".
private val ENDED =
    Regex("""\b(?:is|are|was|were|has|have|had|will\s+be)\s+(?:now\s+)?(?:no\s+longer\b|not\s+(?:required|needed|necessary|in\s+(?:place|operation|effect|force))\b|(?:been\s+)?(?:lifted|removed|withdrawn|cancell?ed|stood\s+down|ended|finished)\b|over\b)""")

/**
 * Picks the disruption to show from several coexisting ones. A true fallback
 * ([ResolvedDisruption.isFallback] — "something is wrong but nothing named it") sorts *after*
 * every informative status, whatever its severity, since a named status is always more useful
 * than the placeholder. Everything else — including a severe status that merely lacks wording
 * — is ordered by [severity] (lowest is most disruptive). Returns null when there are none.
 *
 * The fallback-last key comes first, and keys on the flag rather than the label, for a
 * reason: TfL's scale is not monotonic in usefulness — an informative status can carry a
 * *higher* (milder) number than the fallback's synthetic severity (e.g. "Diverted" is 15,
 * the fallback 9), so ordering by severity alone would hide it behind the placeholder; and a
 * blank-description status borrows the "Service Alert" label without being a fallback, so
 * keying on the label would wrongly demote a severe closure behind a milder named delay.
 */
fun mostSevereDisruption(disruptions: List<ResolvedDisruption>): ResolvedDisruption? =
    disruptions.minWithOrNull(compareBy({ it.isFallback }, { it.severity }))

/**
 * A concise label a keyword in the reason maps to, with a [severity] on TfL's scale (lower
 * is more disruptive) so an inferred label sorts against TfL's own graded statuses. A
 * diversion sits with a part closure (5).
 *
 * The scan covers only what the "Special Service" catch-all actually hides — a diversion or
 * a curtailment. It deliberately does **not** try to read suspensions, closures or delays
 * out of the prose: TfL words those clearly in [statusSeverityDescription] itself
 * ("Suspended", "Part Suspended", "Severe Delays"), which is taken as-is on the graded path,
 * so inferring them from free text only invited mislabeling a section outage as a whole-route
 * one. A reason that names none of these resolves to "Service Alert".
 */
// [needles] are literal words; [patterns] are regexes, for wording a literal can't pin down.
private data class DisruptionKeyword(val label: String, val severity: Int, val needles: List<String>, val patterns: List<String> = emptyList())

private val DISRUPTION_KEYWORDS = listOf(
    DisruptionKeyword("Diversion", 5, listOf("diverted", "diversion")),
    // TfL's own words for one, as seen in the wild (maintainer, 2026-10-01).
    DisruptionKeyword(
        "Curtailed",
        5,
        listOf("curtailed", "curtailment", "cutting short", "cut short"),
        // Buses turning back at a place, or starting partway along from a stop: the stops past it are
        // missed as surely. Only with a place, since "will terminate at 22:00" and "will start from
        // Monday" are times (Codex, PR #455).
        patterns = listOf(TERMINATE_AT_PLACE, START_FROM_STOP),
    ),
)

// A place's start: a quote, or a capital, as TfL writes a stop's name; a time ("10:00", "midnight")
// has neither (Codex, PR #455). Case-sensitive, so matched in the reason as written. An all-caps alert
// capitalizes its times too, so a time word is ruled out by name ("AT MIDNIGHT"; Codex, PR #455).
private const val PLACE = """(?!(?i:midnight|midday|noon|the\s+end|end\s+of)\b)['"‘’“”]?\p{Lu}"""

/**
 * Buses starting from a stop partway along: "will start from stop at B", "starting services towards X
 * from stop A". A place must follow, not a time ("will start from the stop at 10:00"; Codex, PR #455).
 */
internal const val START_FROM_STOP =
    """(?i:\bstart(?:s|ing)?\s+(?:services\s+)?(?:towards\s+[^,.;]*?\s+)?from\s+(?:the\s+)?stop\s+(?:at\s+)?)$PLACE"""

/**
 * Buses turning back at a place partway along: "will terminate at 'Beta Road' (D)", "terminating at
 * the stop Beta Road 'V'". A place must follow, not a time ("will terminate at 22:00"; Codex, PR #455).
 */
internal const val TERMINATE_AT_PLACE =
    """(?i:\bterminat(?:e|es|ing)\s+at\s+(?:the\s+)?(?:stop\s+)?(?:at\s+)?)$PLACE"""


/**
 * The fallback for a status with no informative wording — clearer than TfL's "Special
 * Service", ranked as one of the mildest disruptions so a graded status of any real kind is
 * shown in preference when both are present, while a lone one still marks the line.
 */
private const val SERVICE_ALERT_LABEL = "Service Alert"
private const val SERVICE_ALERT_SEVERITY = 9

/** TfL descriptions that name nothing on their own, so the reason is scanned instead. */
private val CATCH_ALL_DESCRIPTIONS = setOf(
    "special service",
    "bus service disruption",
    "service disruption",
    "information",
    "issues reported",
)
