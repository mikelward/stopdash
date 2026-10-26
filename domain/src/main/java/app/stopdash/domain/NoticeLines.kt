package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Which lines a stop notice is about (SPEC *Disruptions*), read from its text, since TfL files a
 * station's notices against the station as a whole with no line field: King's Cross St. Pancras's
 * "Circle Line: No service between …" comes back for every line calling there. A ride on the
 * Northern line that ends there isn't stopped by it, so its card leaves it out ([aboutOtherLines]).
 *
 * A notice is about lines only when it opens with them, as TfL writes a line's notice: the name with
 * "line" after it ("Circle Line: …"), alone or in a list ("Circle, District and Hammersmith & City
 * lines: …", "DLR and Jubilee line: …"), or "DLR", at the start, or after the station's own name ("King's Cross St. Pancras: Circle
 * line …", stripped as a card strips it, [cleanDisruptionBody]). A line mentioned further on ("Station closed. Use the Circle line from Euston Square")
 * doesn't scope the notice, which is about the whole station (Codex on #1233), and a station sharing a
 * line's name ("Victoria: the Wilton Road entrance is closed") is never read as its line.
 */
object NoticeLines {
    /**
     * The ids of the lines a notice at [stopName] reading [text] opens with, in TfL's spelling
     * ("hammersmith-city"); empty when it opens with none.
     */
    @WorkerThread
    fun scope(text: String, stopName: String = ""): Set<String> =
        leading(cleanDisruptionBody(text, stopName).trimStart()).orEmpty()

    /**
     * Whether a notice reading [text] is about other lines than [lineIds] only: it opens with at least one
     * line ([scope]), and none of them is in [lineIds]. A notice opening with no line (a closed station, a
     * lift out) is about every line there, and with no lines known nothing can be ruled out, so both are
     * kept (SPEC principle 1).
     */
    @WorkerThread
    fun aboutOtherLines(text: String, lineIds: Collection<String>, stopName: String = ""): Boolean {
        if (lineIds.isEmpty()) return false
        val scope = scope(text, stopName)
        return scope.isNotEmpty() && scope.none { it in lineIds }
    }

    // The lines [text] opens with, or null when it opens with none.
    private fun leading(text: String): Set<String>? {
        val run = LINE_RUN.find(text)?.takeIf { it.range.first == 0 } ?: return null
        // A proper noun: "the circle line" in passing prose isn't TfL naming a line.
        if (!run.value.first().isUpperCase()) return null
        // A heading not read to its end ("Jubilee line and DLR: …") might name the ride's line in the part
        // not read, so the notice is kept as about the whole station (Codex on #1233).
        if (!RUN_END.matches(run.value) || MORE.containsMatchIn(text.substring(run.range.last + 1))) return null
        val found = LinkedHashSet<String>()
        for (name in NAME.findAll(run.groupValues[1])) {
            LINES.firstOrNull { (_, pattern) -> pattern.matches(name.value) }?.let { found += it.first }
        }
        return found.takeIf { it.isNotEmpty() }
    }

    // Each line TfL names in a notice, by id: longer names first, so "Hammersmith & City" isn't read
    // as a list of two.
    private val LINES: List<Pair<String, Regex>> = listOf(
        "dlr" to "DLR",
        "hammersmith-city" to """Hammersmith\s*(?:&|and)\s*City|H\s*&\s*C""",
        "waterloo-city" to """Waterloo\s*(?:&|and)\s*City|W\s*&\s*C""",
        "bakerloo" to "Bakerloo",
        "central" to "Central",
        "circle" to "Circle",
        "district" to "District",
        "jubilee" to "Jubilee",
        "metropolitan" to "Metropolitan|Met",
        "northern" to "Northern",
        "piccadilly" to "Piccadilly",
        "victoria" to "Victoria",
        "elizabeth" to "Elizabeth",
        "liberty" to "Liberty",
        "lioness" to "Lioness",
        "mildmay" to "Mildmay",
        "suffragette" to "Suffragette",
        "weaver" to "Weaver",
        "windrush" to "Windrush",
    ).map { (id, pattern) -> id to Regex(pattern, RegexOption.IGNORE_CASE) }

    private val NAMES = LINES.joinToString("|") { "(?:${it.second.pattern})" }

    // One name, as found inside a run.
    private val NAME = Regex("""\b(?:$NAMES)\b""", RegexOption.IGNORE_CASE)

    // One name in a heading, "line" or "lines" after it or not.
    private val ITEM = """(?:$NAMES)(?:\s+lines?)?"""

    // A list of line names: "Circle Line", "Circle, District and H&C lines", "Circle line and District line".
    private val LINE_RUN = Regex(
        """\b((?:$ITEM)(?:\s*(?:,|&|/|\band\b)\s*(?:$ITEM))*)""",
        RegexOption.IGNORE_CASE,
    )

    // A heading names lines only when it ends "line", "lines" or "DLR" ("Victoria: …" is a station).
    private val RUN_END = Regex("""(?s).*\b(?:lines?|DLR)""", RegexOption.IGNORE_CASE)

    // More of a list after the part read.
    private val MORE = Regex("""^\s*(?:,|&|/|\band\b)""", RegexOption.IGNORE_CASE)
}
