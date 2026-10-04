package app.stopdash.domain

/**
 * The [DismissedAlertsStore.mark] each of a check's verdicts was asked at: by place or line key
 * ([DismissedAlert.alertKey]) in [byKey], [rest] for any other. A dismissal counted after its own
 * place's or line's mark is newer than the verdict on it, so it's never let go of on it; one reused
 * answer holds back only the dismissals of its own place, never those of places answered fresh.
 */
data class DismissalMarks(val rest: Long, val byKey: Map<String, Long> = emptyMap()) {
    /** The mark [alert]'s verdict was asked at. */
    fun of(alert: DismissedAlert): Long = byKey[alert.alertKey] ?: rest
}
