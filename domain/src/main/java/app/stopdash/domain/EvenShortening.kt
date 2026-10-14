package app.stopdash.domain

/**
 * Two parts of one line shortened **evenly** (maintainer, 2026-10-14): a favorite ride's two ends, or
 * a stop it boards from and where it goes. Each part lists its forms longest first (a name's are
 * [DestinationAbbreviations.forms], the steps every shortened name takes), and the line takes the
 * longest level at which both fit, so neither keeps its full name while the other is cut. Below the
 * last level each part takes an even share of the width, the shorter keeping all it needs, and elides
 * on its own.
 */
object EvenShortening {
    /**
     * A stop's platform or pole label, longest first, kept whole at each level: "Platform 6", then
     * "Plat 6" (maintainer, 2026-10-14). A pole's letter or "➔ Archway" has no shorter form.
     */
    fun qualifierForms(label: String): List<String> =
        listOf(label, label.split(" ").joinToString(" ") { if (it == "Platform") "Plat" else it })

    /**
     * What to draw: [first] and [second], the [level] they were taken at (so whatever sits between them
     * can take its form at the same level), and, when even their last forms don't fit, the most of the
     * width each may take ([firstMax], [secondMax]), each eliding within it; both null when they fit.
     * [extra] when the line has room for the optional extra too ([choose]'s `extra`).
     */
    data class Choice(
        val first: String,
        val second: String,
        val firstMax: Int? = null,
        val secondMax: Int? = null,
        val level: Int = 0,
        val extra: Boolean = false,
    )

    /**
     * [choose], with something optional [extra] px wide beside the parts (a stop's distance) shown only
     * while both parts fit in full with it: the first thing to go, before either part shortens.
     */
    fun choose(
        first: List<String>,
        second: List<String>,
        width: Int,
        gaps: List<Int>,
        extra: Int,
        widthOf: (String) -> Int,
    ): Choice {
        require(first.isNotEmpty() && second.isNotEmpty() && gaps.isNotEmpty()) { "each part needs a form" }
        val a = first.first()
        val b = second.first()
        if (widthOf(a) + widthOf(b) <= width - gaps.first() - extra) return Choice(a, b, extra = true)
        return choose(first, second, width, gaps, widthOf)
    }

    /** [choose] with the same [gap] at every level. */
    fun choose(first: List<String>, second: List<String>, width: Int, gap: Int, widthOf: (String) -> Int): Choice =
        choose(first, second, width, listOf(gap), widthOf)

    /**
     * The pair for a line [width] wide with [gaps] between the parts, one a level (what sits between
     * them may shorten too, a platform's "Plat"), [widthOf] measuring a form as drawn. The lists are
     * walked a level at a time together, a shorter list's last entry standing for its later levels. A
     * constant few measurements, whatever the names.
     */
    fun choose(first: List<String>, second: List<String>, width: Int, gaps: List<Int>, widthOf: (String) -> Int): Choice {
        require(first.isNotEmpty() && second.isNotEmpty() && gaps.isNotEmpty()) { "each part needs a form" }
        val levels = maxOf(first.size, second.size, gaps.size)
        fun <T> List<T>.at(level: Int) = this[minOf(level, lastIndex)]
        for (level in 0 until levels) {
            val a = first.at(level)
            val b = second.at(level)
            if (widthOf(a) + widthOf(b) <= (width - gaps.at(level)).coerceAtLeast(0)) return Choice(a, b, level = level)
        }
        val last = levels - 1
        val a = first.at(last)
        val b = second.at(last)
        val available = (width - gaps.at(last)).coerceAtLeast(0)
        val aWidth = widthOf(a)
        val bWidth = widthOf(b)
        val half = available / 2
        return when {
            aWidth <= half -> Choice(a, b, aWidth, available - aWidth, last)
            bWidth <= half -> Choice(a, b, available - bWidth, bWidth, last)
            else -> Choice(a, b, half, available - half, last)
        }
    }
}
