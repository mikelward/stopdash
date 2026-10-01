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
        .filter { keyword -> keyword.needles.any { it in text } }
        .minByOrNull { it.severity }
        ?.let { ResolvedDisruption(it.label, it.severity, fullText = reason.trim(), inferred = true) }
        ?: ResolvedDisruption(SERVICE_ALERT_LABEL, SERVICE_ALERT_SEVERITY, isFallback = true, fullText = reason.trim(), inferred = true)
}

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
private data class DisruptionKeyword(val label: String, val severity: Int, val needles: List<String>)

private val DISRUPTION_KEYWORDS = listOf(
    DisruptionKeyword("Diversion", 5, listOf("diverted", "diversion")),
    DisruptionKeyword("Curtailed", 5, listOf("curtailed", "curtailment")),
)

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
