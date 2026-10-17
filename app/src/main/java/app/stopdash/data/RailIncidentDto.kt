package app.stopdash.data

import androidx.annotation.WorkerThread
import app.stopdash.domain.RailIncident
import app.stopdash.domain.railMessageText
import kotlinx.serialization.Serializable

/**
 * One incident as National Rail's Disruptions API gives it (`/disruptions/incidents/search`), passed
 * through unchanged by stopdash's relay (`dev-docs/national-rail-proxy.md`): the fields stopdash reads,
 * the rest ignored.
 */
@Serializable
data class RailIncidentDto(
    val summary: String? = null,
    // HTML, as Darwin's station messages are.
    val description: String? = null,
    // "Active" or "Cleared".
    val status: String? = null,
    val disruptionLinks: List<RailIncidentLinkDto> = emptyList(),
) {
    /** This incident as the app shows it; null with no page to match it to a TfL status by. */
    @WorkerThread
    fun toIncident(): RailIncident? {
        val links = disruptionLinks.mapNotNull { it.uri?.trim()?.ifEmpty { null } }
        if (links.isEmpty()) return null
        return RailIncident(
            summary = summary?.trim().orEmpty(),
            text = description?.let(::railMessageText),
            links = links,
            active = !status.equals("Cleared", ignoreCase = true),
        )
    }
}

@Serializable
data class RailIncidentLinkDto(val uri: String? = null)
