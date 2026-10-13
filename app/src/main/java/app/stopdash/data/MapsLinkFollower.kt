package app.stopdash.data

import app.stopdash.StopdashDebugLog
import app.stopdash.domain.SharedPlace
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.URLParserException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Follows a Google Maps short link (`maps.app.goo.gl/…`) to the full link it stands for, which names the
 * place's exact spot (SPEC *Sharing a place in*). One request to Google: its redirect is read, never
 * fetched, and only a redirect to Google's own Maps pages is taken. Null when the link can't be followed
 * (offline, Google down, a reply that isn't such a redirect), so the share falls back to its name.
 *
 * The request carries only the link, the device's IP address and the client's user agent: no cookie, no
 * account. Cost £0; the short-link service is undocumented, so a change there makes shares fall back to
 * searching their name, never fail.
 */
internal class MapsLinkFollower(private val http: HttpClient = defaultClient) {

    suspend fun follow(link: String): String? {
        if (!SharedPlace.isShortMapsLink(link)) return link
        // One request only, as disclosed (SPEC, docs/PRIVACY.md): a short link that leads to another
        // short link, or anywhere but Google's Maps pages, isn't followed further.
        val next = try {
            val response = http.get(link) { timeout { requestTimeoutMillis = TIMEOUT_MILLIS } }
            response.headers[HttpHeaders.Location] ?: run {
                // The status only, never the link: it names the place shared.
                StopdashDebugLog.warning("shared place: Maps link not followed: HTTP %s, no redirect", response.status.value)
                return null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Offline, unreachable or timed out (Ktor's timeout is an IOException).
            StopdashDebugLog.warning("shared place: Maps link not followed: %s", e::class.simpleName)
            return null
        } catch (e: URLParserException) {
            StopdashDebugLog.warning("shared place: Maps link not followed: not a URL")
            return null
        }
        if (SharedPlace.isShortMapsLink(next) || !SharedPlace.isGoogleMapsHop(next)) {
            StopdashDebugLog.warning("shared place: Maps link not followed: led elsewhere")
            return null
        }
        return next
    }

    companion object {
        private const val TIMEOUT_MILLIS = 5_000L

        // Its own client: redirects are read here rather than followed, and a 3xx isn't a failure.
        private val defaultClient by lazy {
            HttpClient(OkHttp) {
                followRedirects = false
                expectSuccess = false
                engine {
                    config {
                        followRedirects(false)
                        followSslRedirects(false)
                    }
                }
                install(HttpTimeout)
            }
        }
    }
}

/**
 * One request to Google per shared link, however many times the share is read: a reading that starts
 * while another for the same link is in flight, or soon after it finished, as when the screen is
 * recreated mid-share, takes that attempt's answer rather than asking again (SPEC *Sharing a place in*).
 * The request runs in [scope], outliving the screen that started it. A followed link is kept for the last
 * few shares; one that couldn't be followed is kept for [RETRY_AFTER_MILLIS], so a recreated screen
 * reads the same failure, and a share of it a minute later tries again. [now] is a monotonic clock.
 * "Once" is per share, within the process (maintainer, 2026-10-13): a share restored after Android ends
 * the app mid-request, or a link shared again once [KEPT] others have pushed it out, may ask again, the
 * same request to the same recipient.
 */
internal class MapsLinkMemo(
    private val follow: suspend (String) -> String?,
    private val scope: CoroutineScope,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    // An attempt records its own failure, so it counts from when it failed, waited on or not.
    private class Attempt {
        lateinit var answer: Deferred<String?>
        @Volatile var failedAt: Long? = null
    }

    // Least recently read first. Kept to [KEPT] by [trim], which drops only finished attempts: one still in
    // flight is shared until it answers, and trimmed once it has.
    private val links = LinkedHashMap<String, Attempt>(KEPT, 0.75f, true)

    /** How many links are kept, for tests. */
    internal val size: Int get() = synchronized(links) { links.size }

    private fun trim() = synchronized(links) {
        val oldestFirst = links.values.iterator()
        while (links.size > KEPT && oldestFirst.hasNext()) {
            if (oldestFirst.next().answer.isCompleted) oldestFirst.remove()
        }
    }

    suspend fun follow(link: String): String? {
        val attempt = synchronized(links) {
            val kept = links[link]
            val failedAt = kept?.failedAt
            if (kept != null && (failedAt == null || now() - failedAt < RETRY_AFTER_MILLIS)) {
                kept
            } else {
                Attempt().also { fresh ->
                    fresh.answer = scope.async {
                        followed(link).also { if (it == null) fresh.failedAt = now() }
                    }
                    links[link] = fresh
                    fresh.answer.invokeOnCompletion { trim() }
                    trim()
                }
            }
        }
        return attempt.answer.await()
    }

    // [follow], with anything it throws read as a link that couldn't be followed, so a failure is kept as one.
    private suspend fun followed(link: String): String? =
        try {
            follow.invoke(link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StopdashDebugLog.warning("shared place: Maps link not followed: %s", e::class.simpleName)
            null
        }

    companion object {
        private const val KEPT = 8
        const val RETRY_AFTER_MILLIS = 60_000L

        /** The process's own: shares read by any screen go through it. */
        val shared: MapsLinkMemo by lazy {
            val follower = MapsLinkFollower()
            MapsLinkMemo(follower::follow, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        }
    }
}
