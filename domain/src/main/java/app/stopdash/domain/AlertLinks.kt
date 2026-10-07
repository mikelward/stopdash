package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Finds the web links in a service alert's text, so the alert can make them tappable (maintainer,
 * 2026-09-23). TfL prose names a page both with and without a scheme ("https://tfl.gov.uk/strikes",
 * "visit tfl.gov.uk/status-updates"), so both are found. Each hit carries its [range] in the text and
 * the [url] to open — always `https`/`http`, with `https://` added to a bare domain, so a tap can
 * never open another scheme.
 *
 * Trailing sentence punctuation (".", ",", ")" …) is not part of the link: "see tfl.gov.uk." links
 * "tfl.gov.uk". Pure, so it's unit-tested off a device.
 */
object AlertLinks {
    data class Link(val range: IntRange, val url: String)

    // A URL with a scheme, a "www." host, or a bare host ending in a common UK/generic TLD, each with
    // an optional path. The bare-host arm needs a known TLD so ordinary prose ("e.g.") never links,
    // and nothing starts mid-word or after an "@", so an email address isn't linked as a website.
    private val LINK = Regex(
        """(?i)(?<![@\w.-])(?:https?://[^\s<>"]+|www\.[^\s<>"]+|""" +
            """[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:gov\.uk|co\.uk|org\.uk|london|com|org|net|uk)\b(?:/[^\s<>"]*)?)""",
    )
    private const val TRAILING = ".,;:!?)]}'\""

    @WorkerThread
    fun find(text: String): List<Link> =
        LINK.findAll(text).mapNotNull { match ->
            val raw = match.value.trimEnd { it in TRAILING }
            if (raw.isEmpty()) return@mapNotNull null
            val url = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
                raw
            } else {
                "https://$raw"
            }
            Link(match.range.first until match.range.first + raw.length, url)
        }.toList()
}
