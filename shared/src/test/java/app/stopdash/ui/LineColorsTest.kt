package app.stopdash.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line-pill color rules, as pure functions: a tube line resolves to its official
 * TfL color by id, a named Overground line to its own accent (a hollow pill), any bus to
 * London-bus red by mode, everything else to no color (a neutral pill). The filled pill's
 * text flips black/white to stay legible on the fill; the hollow pill's accent is nudged to
 * stay legible on the surface.
 */
class LineColorsTest {
    @Test
    fun `tube lines map to their official TfL color`() {
        assertEquals(Color(0xFFE32017), lineFillColor("central", "tube"))
        assertEquals(Color(0xFF000000), lineFillColor("northern", "tube"))
        assertEquals(Color(0xFF003688), lineFillColor("piccadilly", "tube"))
        assertEquals(Color(0xFFF3A9BB), lineFillColor("hammersmith-city", "tube"))
    }

    @Test
    fun `any bus maps to London bus red, whatever its route id`() {
        assertEquals(Color(0xFFDC241F), lineFillColor("24", "bus"))
        assertEquals(Color(0xFFDC241F), lineFillColor("N73", "bus"))
    }

    @Test
    fun `single-color modes resolve by mode, whatever the line id`() {
        assertEquals(Color(0xFF00A4A7), lineFillColor("dlr", "dlr")) // DLR turquoise
        assertEquals(Color(0xFF6950A1), lineFillColor("elizabeth", "elizabeth-line")) // Elizabeth purple
        assertEquals(Color(0xFF84B817), lineFillColor("tram", "tram")) // Trams green
        // An Overground service whose id isn't one of the six named lines (legacy
        // "london-overground") falls back to the single mode orange.
        assertEquals(Color(0xFFEE7C0E), lineFillColor("london-overground", "overground"))
    }

    @Test
    fun `named Overground lines resolve to their own accent and have no solid fill`() {
        // Each named line has its own accent (for the hollow pill) and NO fill, so LinePill
        // renders it hollow rather than as a solid orange.
        assertEquals(Color(0xFFEF9600), overgroundAccentColor("lioness"))
        assertEquals(Color(0xFF2774AE), overgroundAccentColor("mildmay"))
        assertEquals(Color(0xFFD22730), overgroundAccentColor("windrush"))
        assertEquals(Color(0xFF893B67), overgroundAccentColor("weaver"))
        assertEquals(Color(0xFF5BA763), overgroundAccentColor("suffragette"))
        assertEquals(Color(0xFF606667), overgroundAccentColor("liberty"))
        assertNull(lineFillColor("mildmay", "overground"))
        assertNull(lineFillColor("lioness", "overground"))
        // Tube lines and unknown ids are not Overground accents.
        assertNull(overgroundAccentColor("central"))
        assertNull(overgroundAccentColor("london-overground"))
    }

    @Test
    fun `a hollow accent stays legible on both the light and dark surface`() {
        val light = Color.White
        val dark = Color(0xFF141218) // a typical Material 3 dark surface
        // Lioness yellow is the stress case (low contrast on white as-is); Mildmay blue and
        // Liberty gray round it out. The label clears the text floor and the border the
        // (lower) border floor on both surfaces.
        listOf(Color(0xFFEF9600), Color(0xFF2774AE), Color(0xFF606667)).forEach { accent ->
            listOf(light, dark).forEach { surface ->
                val bg = apcaLuminance(surface)
                assertTrue(apcaLc(apcaLuminance(accentInkOn(accent, surface)), bg) >= 60.0)
                assertTrue(apcaLc(apcaLuminance(accentEdgeOn(accent, surface)), bg) >= 30.0)
            }
        }
    }

    @Test
    fun `an unmapped line or mode has no color, so the caller falls back to neutral`() {
        // lineFillColor covers tube/mode only; national-rail resolves by operator via
        // railOperatorColor (below), so lineFillColor stays null for it.
        assertNull(lineFillColor("thameslink", "national-rail"))
        assertNull(lineFillColor("", ""))
    }

    @Test
    fun `national rail operators resolve to their confirmed brand color`() {
        assertEquals(Color(0xFFB7007C), railOperatorColor("national-rail", "c2c"))
        assertEquals(Color(0xFF8CC63E), railOperatorColor("national-rail", "Southern"))
        assertEquals(Color(0xFF389CFF), railOperatorColor("national-rail", "Southeastern"))
        assertEquals(Color(0xFFFF5AA4), railOperatorColor("national-rail", "Thameslink"))
        assertEquals(Color(0xFF43165C), railOperatorColor("national-rail", "Great Northern"))
        assertEquals(Color(0xFFD70428), railOperatorColor("national-rail", "Greater Anglia"))
        assertEquals(Color(0xFF0A493E), railOperatorColor("national-rail", "Great Western Railway"))
        assertEquals(Color(0xFF24398C), railOperatorColor("national-rail", "South Western Railway"))
        assertEquals(Color(0xFFCE0E2D), railOperatorColor("national-rail", "London North Eastern Railway"))
        assertEquals(Color(0xFF004354), railOperatorColor("national-rail", "Avanti West Coast"))
        assertEquals(Color(0xFF713563), railOperatorColor("national-rail", "East Midlands Railway"))
        assertEquals(Color(0xFF660F21), railOperatorColor("national-rail", "CrossCountry"))
        assertEquals(Color(0xFF00BFFF), railOperatorColor("national-rail", "Chiltern Railways"))
        assertEquals(Color(0xFFEB1E2D), railOperatorColor("national-rail", "Gatwick Express"))
        assertEquals(Color(0xFF532E63), railOperatorColor("national-rail", "Heathrow Express"))
    }

    @Test
    fun `the rest of the rail operators wear their template color`() {
        assertEquals(Color(0xFF086BFE), railOperatorColor("national-rail", "Eurostar"))
        assertEquals(Color(0xFF1D2E35), railOperatorColor("national-rail", "Caledonian Sleeper"))
        assertEquals(Color(0xFF2B6EF5), railOperatorColor("national-rail", "Lumo"))
        assertEquals(Color(0xFF1D1D1B), railOperatorColor("national-rail", "Grand Central"))
        assertEquals(Color(0xFFDE005C), railOperatorColor("national-rail", "Hull Trains"))
        assertEquals(Color(0xFF09A4EC), railOperatorColor("national-rail", "TransPennine Express"))
        assertEquals(Color(0xFFFF0000), railOperatorColor("national-rail", "Transport for Wales"))
        assertEquals(Color(0xFF1E467D), railOperatorColor("national-rail", "ScotRail"))
        assertEquals(Color(0xFFFFF200), railOperatorColor("national-rail", "Merseyrail"))
        assertEquals(Color(0xFF1E90FF), railOperatorColor("national-rail", "Island Line"))
        assertEquals(Color(0xFFF27B15), railOperatorColor("national-rail", "West Midlands Railway"))
    }

    @Test
    fun `Northern Rail is indigo by either name, and the tube's Northern line stays black`() {
        val indigo = Color(0xFF262262)
        assertEquals(indigo, railOperatorColor("national-rail", "Northern"))
        assertEquals(indigo, railOperatorColor("national-rail", "Northern Rail"))
        val tube = pillColors("Northern", "northern", "tube", Color.White)
        assertEquals(Color(0xFF000000), (tube as PillColors.Solid).fill)
    }

    @Test
    fun `an operator the rail feed names its own way matches by name or by its line id`() {
        val lner = Color(0xFFCE0E2D)
        // A rail board names LNER by its brand alone.
        assertEquals(lner, railOperatorColor("national-rail", "LNER", "london-north-eastern-railway"))
        assertEquals(lner, railOperatorColor("national-rail", "LNER"))
        // A spelling nobody has listed still matches through the line id its operator code maps to.
        assertEquals(lner, railOperatorColor("national-rail", "L.N.E.R. Trains", "london-north-eastern-railway"))
        assertEquals(Color(0xFF8CC63E), railOperatorColor("national-rail", "", "southern"))
        // Still rail only.
        assertNull(railOperatorColor("tube", "LNER", "london-north-eastern-railway"))
    }

    @Test
    fun `West Midlands Trains' line is London Northwestern green under every name but WMR's own`() {
        val green = Color(0xFF27B67A)
        // The rail feed's name for both brands, TfL's name for the line, and the line id.
        assertEquals(green, railOperatorColor("national-rail", "LNR & WMR", "west-midlands-trains"))
        assertEquals(green, railOperatorColor("national-rail", "West Midlands Trains", "west-midlands-trains"))
        assertEquals(green, railOperatorColor("national-rail", "", "west-midlands-trains"))
        // A service named for West Midlands Railway alone keeps its brand's orange.
        assertEquals(Color(0xFFF27B15), railOperatorColor("national-rail", "West Midlands Railway", "west-midlands-trains"))
        val pill = pillColors("West Midlands Trains", "west-midlands-trains", "national-rail", Color.White)
        assertEquals(green, (pill as PillColors.Solid).fill)
    }

    @Test
    fun `Lumo Stirling wears Lumo's blue`() {
        assertEquals(Color(0xFF2B6EF5), railOperatorColor("national-rail", "Lumo Stirling", "lumo-stirling"))
    }

    @Test
    fun `London Northwestern is green however the feed spells it`() {
        val green = Color(0xFF27B67A)
        assertEquals(green, railOperatorColor("national-rail", "London Northwestern Railway"))
        assertEquals(green, railOperatorColor("national-rail", "London North Western Railway"))
        assertEquals(green, railOperatorColor("national-rail", "London Northwestern Railway (LNR)"))
        // Rail only, like every operator color.
        assertNull(railOperatorColor("bus", "London Northwestern Railway"))
        // A white label, like most rail pills.
        assertEquals(Color.White, textColorOn(green))
    }

    @Test
    fun `operator color is case- and punctuation-insensitive`() {
        val emr = Color(0xFF713563)
        assertEquals(emr, railOperatorColor("national-rail", "east midlands railway"))
        assertEquals(emr, railOperatorColor("national-rail", "East  Midlands  Railway"))
        assertEquals(emr, railOperatorColor("national-rail", "East-Midlands Railway"))
    }

    @Test
    fun `operator color is gated on the national-rail mode`() {
        // A non-rail line that happens to share an operator's name must not pick up a rail
        // brand color — the color is meaningful only for a national-rail service.
        assertNull(railOperatorColor("tube", "Southern"))
        assertNull(railOperatorColor("bus", "Southern"))
    }

    @Test
    fun `a rail operator without a confirmed brand hex falls back to neutral`() {
        // Not in the confirmed set → null, so LinePill shows a neutral pill rather than an
        // invented shade (SPEC: never an invented color).
        assertNull(railOperatorColor("national-rail", "Example Rail"))
        // Nor does a line id that is only that unknown operator's name.
        assertNull(railOperatorColor("national-rail", "Example Rail", "example-rail"))
    }

    @Test
    fun `the halo is the opposite tone of the text, at partial alpha`() {
        // Black text → a translucent white halo; white text → a translucent black halo,
        // so the glow always lifts the label off the fill rather than blending into it.
        val onBlackText = haloFor(Color.Black)
        assertEquals(1f, onBlackText.red, 0f)
        assertEquals(1f, onBlackText.green, 0f)
        assertEquals(1f, onBlackText.blue, 0f)

        val onWhiteText = haloFor(Color.White)
        assertEquals(0f, onWhiteText.red, 0f)
        assertEquals(0f, onWhiteText.green, 0f)
        assertEquals(0f, onWhiteText.blue, 0f)

        // Partial alpha keeps it a lift, not an opaque second outline.
        assertTrue(onBlackText.alpha > 0f && onBlackText.alpha < 1f)
        assertEquals(onBlackText.alpha, onWhiteText.alpha, 0f)
    }

    @Test
    fun `text picks the higher-contrast black or white by APCA`() {
        // Dark fills → white.
        assertEquals(Color.White, textColorOn(Color(0xFF000000))) // Northern black
        assertEquals(Color.White, textColorOn(Color(0xFFE32017))) // Central red
        assertEquals(Color.White, textColorOn(Color(0xFF6950A1))) // Elizabeth purple
        assertEquals(Color.White, textColorOn(Color(0xFF003688))) // Piccadilly navy
        // Pale fills → black.
        assertEquals(Color.Black, textColorOn(Color(0xFFFFD300))) // Circle yellow
        assertEquals(Color.Black, textColorOn(Color(0xFFA0A5A9))) // Jubilee silver
        assertEquals(Color.Black, textColorOn(Color(0xFF84B817))) // Trams green
        assertEquals(Color.Black, textColorOn(Color(0xFFF3A9BB))) // Hammersmith & City pink
        // Saturated mid-tones: WCAG-2's ratio wrongly picks black on these, APCA picks
        // white — the case this switch exists for (Victoria, DLR, Bakerloo, Overground).
        assertEquals(Color.White, textColorOn(Color(0xFF0098D4))) // Victoria blue
        assertEquals(Color.White, textColorOn(Color(0xFF00A4A7))) // DLR turquoise
        assertEquals(Color.White, textColorOn(Color(0xFFB36305))) // Bakerloo brown
        assertEquals(Color.White, textColorOn(Color(0xFFEE7C0E))) // Overground orange
    }

    @Test
    fun `a named Overground line is hollow, its accent nudged for the surface`() {
        val dark = Color(0xFF121212)
        val accent = overgroundAccentColor("mildmay")!!
        assertEquals(
            PillColors.Hollow(label = accentInkOn(accent, dark), border = accentEdgeOn(accent, dark)),
            pillColors("Mildmay", "mildmay", "overground", dark),
        )
    }

    @Test
    fun `a tube line is solid, with its APCA label, outline and halo`() {
        val fill = Color(0xFF0098D4)
        val label = textColorOn(fill)
        assertEquals(
            PillColors.Solid(fill = fill, label = label, border = borderColorOn(fill), halo = haloFor(label)),
            pillColors("Victoria", "victoria", "tube", Color.White),
        )
    }

    @Test
    fun `a rail operator's brand wins over its mode, and an unknown operator is neutral`() {
        val southern = pillColors("Southern", "southern", "national-rail", Color.White)
        assertEquals(Color(0xFF8CC63E), (southern as PillColors.Solid).fill)
        assertEquals(PillColors.Neutral, pillColors("Unknown Trains", "unknown", "national-rail", Color.White))
    }

    @Test
    fun `a solid pill's colors don't depend on the surface`() {
        assertEquals(
            pillColors("Central", "central", "tube", Color.White),
            pillColors("Central", "central", "tube", Color.Black),
        )
    }
}
