package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.toJavaDuration

/**
 * One of National Rail's current incidents (its Disruptions API), as the app shows it: the one-line
 * [summary], the [text] behind it as plain text ([railMessageText]), and the [links] to its pages on
 * nationalrail.co.uk, by which a TfL status that is only one of those links finds it. [active] is false
 * for one National Rail has marked cleared.
 */
data class RailIncident(
    val summary: String,
    val text: String?,
    val links: List<String>,
    val active: Boolean = true,
)

/**
 * National Rail's own words for a National Rail line whose TfL status is only a link to a
 * nationalrail.co.uk disruption page (SPEC *National Rail*): the incident carrying that link,
 * matched on the address alone, puts its summary and text in the link's place ([LineStatus.detail]),
 * the link kept after them so its page can still be opened.
 */
object RailIncidents {
    // TfL's whole reason, nothing but one disruption page's address.
    private val BARE_LINK = Regex("""^https?://(?:www\.)?nationalrail\.co\.uk/service-disruptions/[^\s]+$""", RegexOption.IGNORE_CASE)

    /** [text]'s disruption page, keyed as [key] keys one, when [text] is nothing but that page's address; else null. */
    fun linkOf(text: String?): String? {
        val trimmed = text?.trim() ?: return null
        return if (BARE_LINK.matches(trimmed)) key(trimmed) else null
    }

    /**
     * [uri] reduced to what names the page: its host and path, lower case, without scheme, `www.`,
     * query, fragment or trailing slash, as TfL and National Rail write the same page both ways.
     */
    fun key(uri: String): String = uri.trim().lowercase()
        .substringBefore('#').substringBefore('?')
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .trimEnd('/')

    /** Whether any of [statuses] (each direction's included) has a link only an incident can put words to. */
    @WorkerThread
    fun wanted(statuses: Collection<LineStatus>): Boolean = statuses.any { status ->
        linkOf(status.fullText) != null || status.byDirection.values.any { linkOf(it.fullText) != null }
    }

    /**
     * [statuses] with National Rail's words put to each bare link [incidents] has a page for, each
     * direction's too. Only an incident still active counts: one National Rail has cleared doesn't
     * stand for the disruption TfL still links to (Codex, #943). A link two active incidents share
     * takes the first; a link none carries keeps TfL's text as it is.
     */
    @WorkerThread
    fun withIncidents(statuses: List<LineStatus>, incidents: List<RailIncident>): List<LineStatus> {
        if (incidents.isEmpty()) return statuses
        val byLink = HashMap<String, RailIncident>()
        for (incident in incidents) {
            if (!incident.active) continue
            for (link in incident.links) byLink.putIfAbsent(key(link), incident)
        }
        fun put(status: LineStatus): LineStatus {
            val text = status.fullText
            val incident = linkOf(text)?.let(byLink::get)
            val words = incident?.let { listOfNotNull(it.summary.trim().ifEmpty { null }, it.text, text?.trim()).distinct().joinToString("\n\n") }
            val directions = status.byDirection.mapValues { put(it.value) }
            return status.copy(
                detail = if (words != null && text != null) IncidentDetail(text, words) else status.detail,
                byDirection = directions,
            )
        }
        return statuses.map(::put)
    }
}

/** The relay's incidents and how old they already were when it answered ([age], from its `Age` header). */
data class FetchedIncidents(val incidents: List<RailIncident>, val age: Duration = Duration.ZERO)

/**
 * The process's last answer from the National Rail relay ([RailIncident]s), shared by every client so the
 * line page, the list, a trip and a journey's check ask at most once per [reuse] between them. A failed
 * ask is remembered as long, so a relay that's down isn't asked on every refresh. The words are dated
 * by when the relay fetched them, by the age it gives, not by when they were asked for, and none past
 * [keep] (the shared staleness bound, SPEC D4) is used, so words National Rail has since changed or
 * cleared aren't shown as current: the link is shown instead.
 */
class RailIncidentCache(
    private val clock: () -> Instant = Instant::now,
    private val reuse: Duration = REUSE,
    private val keep: Duration = KEEP,
) {
    private val lock = Mutex()
    private var held: List<RailIncident>? = null
    // Everything here is judged in the steady frame ([SteadyClock]), so the clock set back doesn't hold
    // a refresh off, or keep old words current, until the wall clock catches up (Codex, #943).
    // When the relay fetched [held] from National Rail, on this device's clock: when it was had, less
    // the age the relay gave, so a wrong wall clock on either side doesn't move it.
    private var heldFrom: Instant? = null
    private var askedAt: Instant? = null

    /**
     * The incidents: the last answer while it was asked for within [reuse], else [fetch]'s, asked by one
     * caller at a time so callers together ask once. Null when there's none current to use: [fetch]
     * failed (told to [onFailure]) or answered with words older than [keep], and nothing held is younger.
     */
    suspend fun incidents(onFailure: (Exception) -> Unit = {}, fetch: suspend () -> FetchedIncidents): List<RailIncident>? = lock.withLock {
        val asked = askedAt
        if (asked == null || Duration.between(asked, now()) >= reuse) {
            askedAt = now()
            try {
                val answer = fetch()
                held = answer.incidents
                heldFrom = now().minus(answer.age.coerceAtLeast(Duration.ZERO))
            } catch (e: CancellationException) {
                // A caller that went away didn't ask: the next one asks again rather than waiting it out.
                askedAt = asked
                throw e
            } catch (e: Exception) {
                onFailure(e)
            }
        }
        held?.takeIf { heldFrom?.let { Duration.between(it, now()) < keep } == true }
    }

    private fun now(): Instant = SteadyClock.stamp(clock())

    companion object {
        /** How long an answer is used before the relay is asked again: as often as it refreshes its own. */
        val REUSE: Duration = Duration.ofMinutes(2)

        /** The oldest words used: the shared staleness bound, past which a line's own status isn't shown either. */
        val KEEP: Duration = Staleness.THRESHOLD.toJavaDuration()

        /** The app's one cache, shared by every client. */
        val SHARED = RailIncidentCache()
    }
}
