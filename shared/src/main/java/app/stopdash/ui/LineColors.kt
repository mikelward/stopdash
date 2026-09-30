package app.stopdash.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Official TfL line colors for the traditional Underground lines, keyed by TfL's
 * `lineId` — the long-stable colors (Northern black, Central red, Piccadilly dark blue,
 * …). Single-color *modes* (bus, DLR, Elizabeth, tram) resolve by mode instead (see
 * [modeColors]); the six named Overground lines resolve by id too but render as a hollow
 * pill (see [overgroundLineColors]).
 */
private val tubeLineColors: Map<String, Color> = mapOf(
    "bakerloo" to Color(0xFFB36305),
    "central" to Color(0xFFE32017),
    "circle" to Color(0xFFFFD300),
    "district" to Color(0xFF00782A),
    "hammersmith-city" to Color(0xFFF3A9BB),
    "jubilee" to Color(0xFFA0A5A9),
    "metropolitan" to Color(0xFF9B0056),
    "northern" to Color(0xFF000000),
    "piccadilly" to Color(0xFF003688),
    "victoria" to Color(0xFF0098D4),
    "waterloo-city" to Color(0xFF95CDBA),
)

/**
 * The six named Overground lines (TfL's 2024 renaming), keyed by `lineId`, each in its own
 * line color. Unlike the tube these are drawn as a **hollow** pill — the card surface shows
 * through, with the line color as the border and label (see [LinePill]) — both because
 * several of these colors sit close to a tube line's (Windrush red ≈ Central; Mildmay ≈ a
 * tube blue) and because TfL itself draws the Overground as hollow/parallel lines, so the
 * hollow shape reads as "Overground, not tube" even where the color collides. A named line
 * therefore has no *fill* ([lineFillColor] returns null for it); an Overground service whose
 * id isn't one of these (legacy `london-overground`) falls back to the single mode orange.
 *
 * Hex values are TfL's official 2024 Overground line colors, confirmed against TfL's own
 * published values (the "London Overground" reference, citing TfL's colour standard); the
 * colour-standard PDF for the named lines is not reachable from CI, so the confirmation was
 * done from that TfL-cited source when egress was opened for the check.
 */
private val overgroundLineColors: Map<String, Color> = mapOf(
    "lioness" to Color(0xFFEF9600),
    "mildmay" to Color(0xFF2774AE),
    "windrush" to Color(0xFFD22730),
    "weaver" to Color(0xFF893B67),
    "suffragette" to Color(0xFF5BA763),
    "liberty" to Color(0xFF606667),
)

/**
 * Single-color modes, keyed by TfL's `modeName` — every line of the mode shares the color,
 * so these resolve by mode rather than by line id. Buses are TfL's roundel red; DLR
 * turquoise, the Elizabeth line purple, and London Trams green are their TfL line colors.
 * `overground` is the legacy single orange, a fallback for an Overground service whose line
 * id isn't one of the six named ones (see [overgroundLineColors]). National rail resolves by
 * operator instead (see [railOperatorColors]), not by mode; anything else falls back to a
 * neutral pill.
 */
private val modeColors: Map<String, Color> = mapOf(
    "bus" to Color(0xFFDC241F),
    "dlr" to Color(0xFF00A4A7),
    "elizabeth-line" to Color(0xFF6950A1),
    "overground" to Color(0xFFEE7C0E),
    "tram" to Color(0xFF84B817),
    "trams" to Color(0xFF84B817),
)

/**
 * National Rail operator **brand** colors, keyed by a punctuation- and case-insensitive form
 * of the operator name (see [normalizeRailOperator]) — a service's `lineName`, the same field
 * [lineCode] reads for the operator code. A key is also looked up from the service's TfL line
 * id, which on a rail board comes from the operator's code, so a line matches by name or by code
 * (see [railOperatorColor]): TfL's ids are its operator names with hyphens, and normalize to these
 * same keys. Unlike the tube/mode colors
 * these are each operator's own brand hex, not a TfL palette color — the deliberate departure
 * from "add a line color only as a confirmed TfL hex" the maintainer authorized (SPEC / TODO),
 * so a rail departure wears its operator's identity (c2c magenta, Southern green, EMR aubergine)
 * instead of the neutral fallback, the way Google Maps shows them.
 *
 * Each value is the operator's **confirmed** brand hex — the color Wikipedia's UK-railways colour
 * templates carry (`Template:National Rail colour`, which for Eurostar reads its route module),
 * except Great Northern, whose Wikipedia value is a route-diagram blue rather than its brand, so
 * its purple is taken from the operator's own site (greatnorthernrail.com). Where a template
 * varies by route (Northern, ScotRail, Merseyrail, TransPennine Express), the value is its
 * default, the operator's own color rather than one route's.
 * A national-rail operator not listed here still falls back to a neutral pill rather than an
 * invented shade.
 *
 * Rendered as a **solid** pill (APCA black/white label) like the tube, not the Overground's
 * hollow treatment: the operator code (EMR, AWC, c2c) already reads distinct from any tube code,
 * so the rare collision with a tube color (LNER / Gatwick / Greater Anglia reds near Central,
 * Thameslink pink near Hammersmith & City) can't be mistaken for that line — and Google Maps,
 * the precedent here, fills the pill too.
 */
private val railOperatorColors: Map<String, Color> = mapOf(
    "c2c" to Color(0xFFB7007C),
    "southern" to Color(0xFF8CC63E),
    "southeastern" to Color(0xFF389CFF),
    "thameslink" to Color(0xFFFF5AA4),
    "greatnorthern" to Color(0xFF43165C),
    "greateranglia" to Color(0xFFD70428),
    "greatwesternrailway" to Color(0xFF0A493E),
    "southwesternrailway" to Color(0xFF24398C),
    "londonnortheasternrailway" to Color(0xFFCE0E2D),
    "avantiwestcoast" to Color(0xFF004354),
    "eastmidlandsrailway" to Color(0xFF713563),
    "crosscountry" to Color(0xFF660F21),
    "chilternrailways" to Color(0xFF00BFFF),
    "gatwickexpress" to Color(0xFFEB1E2D),
    "heathrowexpress" to Color(0xFF532E63),
    // The rail feed names it by its brand alone.
    "lner" to Color(0xFFCE0E2D),
    "eurostar" to Color(0xFF086BFE),
    "caledoniansleeper" to Color(0xFF1D2E35),
    "grandcentral" to Color(0xFF1D1D1B),
    "hulltrains" to Color(0xFFDE005C),
    "transpennineexpress" to Color(0xFF09A4EC),
    // "Northern" on a rail board, "Northern Rail" in TfL's own line list; the tube's Northern
    // line is mode `tube`, so the national-rail gate keeps it black.
    "northern" to Color(0xFF262262),
    "northernrail" to Color(0xFF262262),
    "transportforwales" to Color(0xFFFF0000),
    "scotrail" to Color(0xFF1E467D),
    "merseyrail" to Color(0xFFFFF200),
    "islandline" to Color(0xFF1E90FF),
    // West Midlands Trains runs two brands, London Northwestern and West Midlands Railway, under
    // one operator code (LM) and one TfL line. The rail feed doesn't tell them apart ("LNR &
    // WMR"), so the line wears London Northwestern's green (below) under that name, TfL's
    // "West Midlands Trains" and its line id: every train it runs from London is LNR
    // (maintainer, 2026-09-30). West Midlands Railway, named on its own, keeps its orange. Both
    // are from the route-map legend in Wikipedia's *West Midlands Trains* article (the
    // maintainer's pick, 2026-09-30, over the template's `#FF8300`, so the sister brands match).
    "westmidlandsrailway" to Color(0xFFF27B15),
    "lnrwmr" to Color(0xFF27B67A),
    "westmidlandstrains" to Color(0xFF27B67A),
)

/** An operator name reduced to lowercase letters and digits, so "Great Western Railway",
 *  "great western railway" and stray punctuation all key the same [railOperatorColors] entry
 *  (matching the domain's operator-code normalization). */
private fun normalizeRailOperator(name: String): String =
    name.lowercase().filter { it.isLetterOrDigit() }

/**
 * The **solid** pill fill color for a service, or `null` when its line/mode has no solid
 * fill (so the caller shows a neutral pill or, for a named Overground line, the hollow
 * treatment). A tube line resolves by [lineId]; a named Overground line has no fill (it is
 * hollow — see [overgroundAccentColor]); a single-color mode (bus, DLR, Elizabeth, legacy
 * Overground, tram) resolves by [mode]; everything else is unmapped for now.
 */
fun lineFillColor(lineId: String, mode: String): Color? {
    tubeLineColors[lineId]?.let { return it }
    if (overgroundLineColors.containsKey(lineId)) return null
    return modeColors[mode.lowercase()]
}

/**
 * The **solid** brand fill for a national-rail [operator] (the service's `lineName`), or `null`
 * for a non-rail mode or a rail operator without a confirmed brand hex (→ neutral pill). Gated on
 * [mode] so a tube/bus line that happens to share an operator's name can't pick up a rail
 * brand color. Resolved by operator, because every national-rail service shares the one
 * `national-rail` mode — its operator is its identity (see [railOperatorColors]).
 *
 * The name is tried first, so a brand the feed names (West Midlands Railway) keeps its own color;
 * then [lineId], which a rail board derives from the operator's code. The feed spells some names
 * its own way ("LNER", "LNR & WMR") and a name that matches nothing would otherwise leave the
 * pill neutral, so the code is the net that catches a new spelling.
 */
fun railOperatorColor(mode: String, operator: String, lineId: String = ""): Color? {
    if (!mode.equals("national-rail", ignoreCase = true)) return null
    return railColorFor(normalizeRailOperator(operator)) ?: railColorFor(normalizeRailOperator(lineId))
}

private fun railColorFor(key: String): Color? {
    if (key.isEmpty()) return null
    railOperatorColors[key]?.let { return it }
    return railOperatorColorPrefixes.firstOrNull { (prefix, _) -> key.startsWith(prefix) }?.second
}

/**
 * Brand colors matched by the start of the normalized operator name, for an operator the rail
 * feed spells more than one way (the same reason its pill code is prefix-pinned in
 * `LineCode.kt`). London Northwestern's green is the maintainer's pick (2026-09-24) of
 * `#27B67A`, from the route-map legend in Wikipedia's *West Midlands Trains* article, over
 * `#00BF6F` (its Wikipedia colour template): it takes a white label like most rail pills.
 */
private val railOperatorColorPrefixes: List<Pair<String, Color>> = listOf(
    "londonnorthwestern" to Color(0xFF27B67A),
    // Lumo, and the rail feed's "Lumo Stirling", which carries a code of its own.
    "lumo" to Color(0xFF2B6EF5),
)

/**
 * The accent color for a named Overground line, or `null` for anything else. A non-null
 * result means [LinePill] renders a **hollow** pill (surface fill, this color as border and
 * label) rather than a solid one.
 */
fun overgroundAccentColor(lineId: String): Color? = overgroundLineColors[lineId]

/**
 * Black or white text, whichever **APCA** rates as higher-contrast on [fill]. APCA (the
 * perceptual model headed into WCAG 3) is used instead of the WCAG-2 contrast ratio
 * because that ratio is luminance-only and misreads white on saturated mid-tones: it puts
 * black on Victoria blue, DLR turquoise and Bakerloo brown, where white is clearly the more
 * readable choice to the eye. APCA models polarity and lightness and agrees with the eye on
 * those. The chosen picks are recorded in `AGENTS.md` / `SPEC.md`; the bold weight and halo
 * add real margin neither model credits. Ties (near-neutral fills) fall to black.
 */
fun textColorOn(fill: Color): Color {
    val onBlack = apcaLc(textLuminance = BLACK_APCA_Y, backgroundLuminance = apcaLuminance(fill))
    val onWhite = apcaLc(textLuminance = WHITE_APCA_Y, backgroundLuminance = apcaLuminance(fill))
    return if (onBlack >= onWhite) Color.Black else Color.White
}

/**
 * APCA screen luminance for a color: a plain 2.4-power of each sRGB channel (APCA's own
 * transfer curve, not WCAG's piecewise one), weighted by the same coefficients.
 */
internal fun apcaLuminance(color: Color): Double =
    0.2126 * Math.pow(color.red.toDouble(), 2.4) +
        0.7152 * Math.pow(color.green.toDouble(), 2.4) +
        0.0722 * Math.pow(color.blue.toDouble(), 2.4)

private val BLACK_APCA_Y = apcaLuminance(Color.Black)
private val WHITE_APCA_Y = apcaLuminance(Color.White)

/**
 * Absolute APCA lightness contrast (Lc, 0–~108) of a text luminance on a background
 * luminance — the W3 APCA-0.1.9 constants: a soft black clamp, polarity-aware exponents,
 * and the low-contrast clip. Magnitude only; higher is more readable.
 */
internal fun apcaLc(textLuminance: Double, backgroundLuminance: Double): Double {
    fun clamp(y: Double) = if (y < 0.022) y + Math.pow(0.022 - y, 1.414) else y
    val txt = clamp(textLuminance)
    val bg = clamp(backgroundLuminance)
    if (Math.abs(bg - txt) < 0.0005) return 0.0
    val contrast = if (bg > txt) {
        val sapc = (Math.pow(bg, 0.56) - Math.pow(txt, 0.57)) * 1.14
        if (sapc < 0.1) 0.0 else sapc - 0.027
    } else {
        val sapc = (Math.pow(bg, 0.65) - Math.pow(txt, 0.62)) * 1.14
        if (sapc > -0.1) 0.0 else sapc + 0.027
    }
    return Math.abs(contrast * 100)
}

/**
 * A soft halo tone for label [text] over a colored fill — the opposite of the text, at
 * partial alpha. On the mid-luminance fills where black or white only just clears the
 * contrast floor (Bakerloo brown is ~4.7:1), a faint glow in the opposite tone lifts the
 * label off the fill without darkening or lightening the official color itself. The alpha
 * keeps it a lift, not a second visible outline.
 */
fun haloFor(text: Color): Color =
    (if (text == Color.Black) Color.White else Color.Black).copy(alpha = HALO_ALPHA)

private const val HALO_ALPHA = 0.72f

/**
 * A border tone for a colored pill: the fill nudged toward its own contrasting text color,
 * so every pill stays outlined against the card in both themes — a light-ish fill (Circle
 * yellow, most lines) darkens into a defined edge on a light card, while a near-black fill
 * (Northern) lightens into a gray edge that survives the dark theme's near-black surface. A
 * dark fill already separates from a light card by its own darkness, and a light fill from
 * a dark card, so nudging toward the text color always moves the edge the useful way. Tied
 * to the line's color rather than a flat neutral, so the outline reads as part of the line.
 */
fun borderColorOn(fill: Color): Color = lerp(fill, textColorOn(fill), BORDER_BLEND)

private const val BORDER_BLEND = 0.4f

/**
 * A line color adjusted to read on [surface] — for the hollow Overground pill, whose label
 * and border ARE the line color rather than black/white on a fill. The accent is blended
 * toward the surface's own contrasting pole (black on a light card, white on a dark one)
 * only as far as it takes to clear [minLc] APCA — so Mildmay blue reads as-is on white, a
 * dark accent brightens on the dark card, and Lioness yellow darkens into a legible amber on
 * white rather than washing out. A fully-blended accent (target pole) is the floor.
 */
private fun accentOnSurface(accent: Color, surface: Color, minLc: Double): Color {
    val target = textColorOn(surface)
    val bg = apcaLuminance(surface)
    var t = 0f
    while (t <= 1f) {
        val candidate = lerp(accent, target, t)
        if (apcaLc(textLuminance = apcaLuminance(candidate), backgroundLuminance = bg) >= minLc) {
            return candidate
        }
        t += ACCENT_BLEND_STEP
    }
    return target
}

/** The hollow pill's **label** color: the line accent, legible as text on the surface. */
fun accentInkOn(accent: Color, surface: Color): Color = accentOnSurface(accent, surface, ACCENT_TEXT_MIN_LC)

/** The hollow pill's **border** color: the line accent, at a lower floor than the label so
 *  it stays closer to the true line color while still defining the edge. */
fun accentEdgeOn(accent: Color, surface: Color): Color = accentOnSurface(accent, surface, ACCENT_BORDER_MIN_LC)

private const val ACCENT_BLEND_STEP = 0.1f
private const val ACCENT_TEXT_MIN_LC = 60.0
private const val ACCENT_BORDER_MIN_LC = 30.0


/**
 * How one line's pill is colored on a given surface: the whole line-pill rule (SPEC *Line pill
 * colors*) as one pure decision, so the phone's [LinePill] and the Wear OS surfaces
 * (dev-docs/wear-os.md) color a service identically, each on its own surface.
 */
sealed interface PillColors {
    /** An official line, mode or rail-operator color: an APCA black-or-white [label], an
     *  outline nudged toward it, and a [halo] that lifts the label off mid-tone fills. */
    data class Solid(val fill: Color, val label: Color, val border: Color, val halo: Color) : PillColors

    /** A named Overground line: the surface shows through, and the [label] and [border] are the
     *  line accent nudged to stay legible on that surface. */
    data class Hollow(val label: Color, val border: Color) : PillColors

    /** No confirmed color: the caller draws its own theme's neutral pill. */
    data object Neutral : PillColors
}

/**
 * The [PillColors] for a service on [surface]. A named Overground line is hollow, whatever else
 * matches; otherwise a national-rail service wears its operator's brand, and any other line or
 * mode its official TfL color; anything unmapped is [PillColors.Neutral].
 */
fun pillColors(lineName: String, lineId: String, mode: String, surface: Color): PillColors {
    overgroundAccentColor(lineId)?.let { accent ->
        return PillColors.Hollow(label = accentInkOn(accent, surface), border = accentEdgeOn(accent, surface))
    }
    val fill = railOperatorColor(mode, lineName, lineId) ?: lineFillColor(lineId, mode) ?: return PillColors.Neutral
    val label = textColorOn(fill)
    return PillColors.Solid(fill = fill, label = label, border = borderColorOn(fill), halo = haloFor(label))
}
