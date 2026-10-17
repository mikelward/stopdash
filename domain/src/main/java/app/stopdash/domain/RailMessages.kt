package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * A National Rail station message (Darwin's `nrccMessages`) as plain text for a station notice (SPEC
 * *National Rail*). Darwin writes them as HTML fragments: paragraphs and line breaks become lines, a
 * link keeps its words and its address beside them, "Latest Travel News (https://…)", which the notice
 * makes tappable as it does a TfL alert's ([AlertLinks]); a link whose words are its address is said
 * once. Other tags are dropped and entities decoded. Null when nothing is left to say.
 */
@WorkerThread
fun railMessageText(html: String): String? {
    val linked = ANCHOR.replace(html) { match ->
        val (double, single, unquoted, inner) = match.destructured
        // Left as written, decoded once below with the rest: decoding here too would decode twice, and turn
        // an escaped "&lt;…&gt;" into a tag the pass below strips.
        val href = double.ifEmpty { single }.ifEmpty { unquoted }.trim()
        val words = TAG.replace(inner, "").trim()
        val address = decodeEntities(href)
        when {
            !address.startsWith("http://", ignoreCase = true) && !address.startsWith("https://", ignoreCase = true) -> words
            words.isEmpty() -> href
            bare(decodeEntities(words)) == bare(address) -> href
            else -> "$words ($href)"
        }
    }
    val text = decodeEntities(TAG.replace(BREAK.replace(linked, "\n"), ""))
    return normalizeDisruptionText(text.replace('\u00A0', ' ').replace(SPACES, " ")).ifEmpty { null }
}

// An address with its scheme, "www." and trailing slash left off, as a link's words often write it.
private fun bare(url: String): String =
    url.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

// A link: its address (quoted either way, or bare) and its words.
private val ANCHOR = Regex(
    """<a\b[^>]*?\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))[^>]*>(.*?)</a\s*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val BREAK = Regex("""<br\s*/?>|</p\s*>|<p\b[^>]*>""", RegexOption.IGNORE_CASE)
private val TAG = Regex("""<[^>]*>""")
private val SPACES = Regex("""[ \t]+""")
private val ENTITY = Regex("""&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);""")
private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0", "pound" to "£", "ndash" to "–", "mdash" to "—", "rsquo" to "’", "lsquo" to "‘")

private fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
    val name = match.groupValues[1]
    when {
        name.startsWith("#x", ignoreCase = true) -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
        name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
        else -> NAMED[name]
    } ?: match.value
}

private fun codePoint(code: Int): String? = if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else null
