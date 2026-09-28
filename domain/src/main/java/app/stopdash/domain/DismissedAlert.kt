package app.stopdash.domain

/**
 * A **dismissed alert**: the user tapped "dismiss" on a service alert — a stop-closure card or a
 * line's status — and it should stay hidden — but only until the alert's *content* changes, so a dismiss clears the notice you've
 * read without ever burying a new or escalated one (maintainer, 2026-09-22).
 *
 * [alertKey] identifies **which** alert, stably across refreshes: the stop-status row's place
 * identity ([stopPlaceKey] — hub, else a real StopArea, else the stop), the same key the near-me
 * fold collapses on, so the dismissal survives the nearest-member changing as the user moves and
 * doesn't leak to a same-text notice at another place. [contentSignature] is **what must stay
 * unchanged** to remain dismissed — the normalized (pre-name-strip) notice text; when TfL rewords
 * or replaces the notice the signature no longer matches and the card returns. It also carries the
 * notice's TfL window, so a dismiss lasts only until the stated end: an extended or moved window
 * is a new notice and shows again at once (maintainer, 2026-09-23).
 *
 * A **line-status** alert keys on the line instead ([lineAlertKey] — a line's status is line-wide, so
 * dismissing it at one stop clears it at every stop the line serves) and its signature folds in TfL's
 * **severity** alongside the label and prose, so a dismissed status re-surfaces the moment it
 * escalates or TfL rewords it (maintainer, 2026-09-23: every service alert is dismissible).
 *
 * Persisted so a dismiss survives restart; it rides Android backup/transfer with the rest of the
 * user's config (SPEC *Privacy*), never an app-initiated send — the notice text is TfL's own public
 * wording and the key is a public place id, no coordinate.
 */
data class DismissedAlert(
    val alertKey: String,
    val contentSignature: String,
) {
    companion object {
        /**
         * The dismissal identity of a **stop-closure row** — its place key and the normalized
         * notice text. Only meaningful for a stop-status row (a non-null [DepartureRow.stopDisruption]);
         * a caller filters to those first.
         */
        fun ofStopClosure(row: DepartureRow): DismissedAlert =
            DismissedAlert(
                alertKey = stopPlaceKey(row),
                contentSignature = row.stopDisruption.orEmpty() +
                    row.stopDisruptionWindows.takeIf { it.isNotEmpty() }?.let { "$WINDOW_SEPARATOR$it" }.orEmpty(),
            )

        /**
         * The dismissal identity of a **line's status** — the line and its severity, label and prose.
         * Any change to the three (an escalation from minor to severe delays, a new reason) is a new
         * alert, so a dismiss never buries a worse one.
         */
        fun ofLineStatus(status: LineStatus): DismissedAlert =
            DismissedAlert(
                alertKey = lineAlertKey(status.lineId),
                contentSignature = listOf(status.severity.toString(), status.description, status.fullText.orEmpty())
                    .joinToString(WINDOW_SEPARATOR),
            )

        /**
         * The dismissal identity of whatever alert [row] carries — its stop closure, else its line's
         * status — or null when the row carries none (nothing to dismiss).
         */
        fun of(row: DepartureRow): DismissedAlert? = when {
            row.stopDisruption != null -> ofStopClosure(row)
            row.status != null -> ofLineStatus(row.status)
            else -> null
        }

        // Appended only for a dated notice, so an undated one keeps its bare-text signature (and a
        // dismissal stored before windows were keyed still matches). A unit separator never occurs
        // in notice text.
        private const val WINDOW_SEPARATOR = "\u001F"
    }
}

/**
 * Pure rules for the dismissed set, kept out of the store so the membership logic is JVM-testable
 * without DataStore or Android (mirrors [Starred]/[WatchedStops]).
 */
object Dismissed {
    /**
     * [current] with [alert] recorded. A dismiss only **adds**, so dismissing one of several cards
     * shown at a place (the fold keeps a card per distinct notice — [DepartureRows.nearbyDeduped])
     * never un-dismisses the others; obsolete entries are pruned by [reconcile] after a refresh.
     */
    fun dismiss(current: Set<DismissedAlert>, alert: DismissedAlert): Set<DismissedAlert> =
        current + alert

    /**
     * [current] pruned to the notices still shown, but **only for places actually checked this
     * cycle** ([checkedPlaces] — the place keys whose stops were all queried and whose disruption
     * lookup succeeded, plus the [lineAlertKey]s of the lines whose status TfL returned). An entry
     * for a checked place whose signature is no longer in [live] — its notice resolved or was
     * replaced — is dropped; an entry for a place **not** checked (a different
     * nearby set not queried this cycle, or one whose disruption lookup failed) is **kept**, so a
     * still-valid dismissal never lapses just because its place wasn't looked at. This stops a
     * resolved incident's stale `(place, text)` from later suppressing a genuinely new same-text
     * closure (SPEC principle 2 — never hide a warning) without breaking persist-until-change. A
     * concurrent notice still shown keeps its dismissal, since its signature is in [live].
     */
    fun reconcile(
        current: Set<DismissedAlert>,
        live: Set<DismissedAlert>,
        checkedPlaces: Set<String>,
    ): Set<DismissedAlert> =
        current.filterTo(mutableSetOf()) { it in live || it.alertKey !in checkedPlaces }
}

/**
 * The dismissal key of a line-status alert: the line id under a `line:` prefix, which no stop place
 * key (a hub, StopArea or stop id — naptan-style codes) ever carries, so a line and a place can't
 * collide in the one dismissed set. Also what [Dismissed.reconcile] scopes a checked line by.
 */
fun lineAlertKey(lineId: String): String = "line:$lineId"

/**
 * The stable place identity a stop-status row folds and dismisses on: the interchange
 * ([DepartureRow.hubId]) when there is one, else a **real** StopArea cluster
 * ([DepartureRow.clusterId] when it is TfL's `stationNaptan`, not the display-name fallback — a
 * naptan code never equals a display name, so `clusterId != stopName` marks a real one), else the
 * stop's own id. Shared by the near-me fold ([DepartureRows.nearbyDeduped]) and the dismissal key
 * so the two agree on what "one place" means.
 */
fun stopPlaceKey(row: DepartureRow): String =
    stopPlaceKey(row.hubId, row.clusterId, row.stopName, row.stopId)

/** The place identity of a [StopArrivals] — the same rule as the [DepartureRow] overload, so a stop
 *  and the rows derived from it agree on their place (used to scope the dismissal [Dismissed.reconcile]). */
fun stopPlaceKey(stop: StopArrivals): String =
    stopPlaceKey(stop.hubId, stop.clusterId, stop.stopName, stop.stopId)

/**
 * The place identity from the raw stop-identity fields — the interchange ([hubId]) when there is
 * one, else a real StopArea cluster ([clusterId] when it is a `stationNaptan`, not the display-name
 * fallback), else the stop's own id. The one source of truth the row / [StopArrivals] overloads and
 * the ViewModel's queried-stop scope all call, so fold, dismiss, and reconcile agree on "one place".
 */
fun stopPlaceKey(hubId: String, clusterId: String, stopName: String, stopId: String): String {
    val realCluster = clusterId.takeUnless { it.isBlank() || it == stopName }
    return hubId.ifBlank { realCluster ?: stopId }
}

/**
 * A short, stable fingerprint of a line-status alert's full dismissal identity
 * ([DismissedAlert.ofLineStatus]'s signature: severity, label and TfL's full reason), so a stored
 * check that drops the full reason can still tell a reworded alert from the one the user
 * dismissed. Not a secret: it only has to differ when the alert does.
 */
fun lineAlertFingerprint(status: LineStatus): String = fingerprint(DismissedAlert.ofLineStatus(status).contentSignature)

/** [alert]'s fingerprint, as [lineAlertFingerprint] gives it for the status it was made from. */
fun alertFingerprint(alert: DismissedAlert): String = fingerprint(alert.contentSignature)

/** Whether [alerts] holds a dismissal of the line alert whose fingerprint is [fingerprint]. */
fun dismissedLine(alerts: Set<DismissedAlert>, lineId: String, fingerprint: String): Boolean =
    alerts.any { it.alertKey == lineAlertKey(lineId) && fingerprint(it.contentSignature) == fingerprint }

private fun fingerprint(signature: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }
