package app.stopdash.domain

/**
 * Parses a rail `platformName` into the two cues the platform sub-header shows (SPEC D8: "Platform 2
 * (Eastbound)"): the **platform number** a rail place splits on ([platformNumber]) and the **compass
 * direction** that platform faces ([of]). TfL prints a rail platform as `"<Direction> - Platform N"` —
 * "Northbound - Platform 1", "Eastbound - Platform 2" — so both are read off the one string.
 *
 * The **platform** is the grouping key, not the compass: the compass alone conflates physically
 * distinct platforms — at King's Cross the Eastbound Circle/H&C/Met share one sub-surface platform
 * while the Eastbound Piccadilly is a different deep-tube platform — so keying on the platform number
 * keeps a header naming one physical platform. The compass rides along only as the direction cue in
 * parens, and it is read from the platform name rather than TfL's `inbound`/`outbound` `direction`
 * because the latter is **inconsistent across lines at one platform**: the Circle runs an Eastbound
 * platform as `inbound` while the Hammersmith & City runs the same platform as `outbound`, and some
 * predictions carry no `direction` at all (confirmed against live TfL data, 2026-09-22).
 *
 * [of] returns the direction label (`Northbound`, `Eastbound`, `Inner Rail`, …), or **null** when the
 * platform names no direction: a bare "Platform 4", an empty platform, or a **bus** pole's stop-local
 * `platform` ("Stop A", "Stop A / 1"). Only a recognized rail-direction form is accepted — a
 * **compass** bearing (a compass point + "bound", never TfL's `Inbound`/`Outbound` jargon) or a
 * sub-surface loop label ("Inner Rail" / "Outer Rail") — so neither a bus value nor the
 * inbound/outbound words are ever mistaken for a direction (which for a bus value would mislabel a
 * header and split one bus cluster's poles into separate groups — Codex P2, PR #109).
 */
object PlatformDirection {
    // The compass prefixes a genuine rail bearing takes before "bound" — the four **cardinals**
    // TfL uses on rail platforms. Deliberately EXCLUDES `in`/`out` (TfL's "Inbound"/"Outbound" also
    // end in "bound" but are the jargon SPEC rejects for the header, not a compass), AND the
    // intercardinals (`northeast`…), which don't appear on TfL rail platforms.
    private val COMPASS_PREFIXES = setOf("north", "south", "east", "west")
    // Recognized non-compass rail platform direction labels (the Circle/District sub-surface loop),
    // keyed by their lowercase form and mapped to the one canonical spelling to return.
    private val LOOP_LABELS = mapOf("inner rail" to "Inner Rail", "outer rail" to "Outer Rail")

    // The "Platform N" suffix TfL prints on a rail platform name — the pole a rail place splits on
    // (SPEC D8). A bus stop-letter form ("Stop A") never says "Platform", so this stays rail-only.
    private val PLATFORM_NUMBER = Regex("""Platform\s+(\S+)""", RegexOption.IGNORE_CASE)

    /** The platform number in [platform] ("Eastbound - Platform 2" → "2", "Platform 4" → "4"), else
     *  null — a bus stop-letter form ("Stop A"), a bare compass ("Northbound"), or an empty value. */
    fun platformNumber(platform: String?): String? {
        if (platform.isNullOrBlank()) return null
        return PLATFORM_NUMBER.find(platform)?.groupValues?.get(1)
    }

    /**
     * [platformNumber] where it names one physical platform: not TfL's "Platform Unknown", a placeholder
     * for trains in either direction, so two trains listed there can't be said to share a platform.
     */
    fun knownPlatformNumber(platform: String?): String? =
        platformNumber(platform)?.takeUnless { it.equals("Unknown", ignoreCase = true) }

    /** The direction label in [platform] ("Eastbound - Platform 2" → "Eastbound"), else null. */
    fun of(platform: String?): String? {
        // The label is everything before the " - Platform N" suffix (or the whole string when TfL
        // gives no platform number, e.g. a bare "Northbound").
        val label = platform?.substringBefore(" - ")?.trim().orEmpty()
        if (label.isEmpty()) return null
        // Accept only a genuine rail direction: a **compass** bearing (a compass prefix + "bound")
        // or a loop label. Anything else — a bare "Platform 4", a bus stop letter ("Stop A"), or
        // TfL's `Inbound`/`Outbound` jargon (which SPEC rejects for the header) — names no
        // direction. Return a **canonical casing** so the same compass in different casings TfL
        // might supply ("NORTHBOUND" / "Northbound") yields one grouping key and one direction
        // block, not two identical-looking sections — the UI uppercases for display, but the group
        // key uses this value (Codex P2, PR #109). `lowercase()`/`uppercaseChar()` are
        // locale-invariant.
        val normalized = label.lowercase()
        val compassPrefix = normalized.removeSuffix("bound")
        if (compassPrefix != normalized && compassPrefix in COMPASS_PREFIXES) {
            return normalized.replaceFirstChar(Char::uppercaseChar)
        }
        return LOOP_LABELS[normalized]
    }
}
