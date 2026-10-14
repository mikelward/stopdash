package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EvenShorteningTest {
    // Ten px a character: enough to reason about which level fits.
    private val width: (String) -> Int = { it.length * 10 }

    @Test
    fun `both ends in full when they fit`() {
        val choice = EvenShortening.choose(DestinationAbbreviations.forms("Euston"), DestinationAbbreviations.forms("Victoria"), 200, 10, width)
        assertEquals(EvenShortening.Choice("Euston", "Victoria"), choice)
    }

    @Test
    fun `both ends shorten together, never one kept whole while the other is cut`() {
        // "Clapham Junction" (16) + "High Street Kensington" (22) is 380 in full; abbreviated,
        // "Clapham Jct" (11) + "H. St Kensington" (16) is 270. With room for 330, both shorten,
        // though "Clapham Junction" in full beside the other's short form (320) would have fit.
        val choice = EvenShortening.choose(
            DestinationAbbreviations.forms("Clapham Junction"),
            DestinationAbbreviations.forms("High Street Kensington"),
            340,
            10,
            width,
        )
        assertEquals(EvenShortening.Choice("Clapham Jct", "H. St Kensington", level = 1), choice)
    }

    @Test
    fun `one end with no short form keeps its name while the other shortens`() {
        // "Euston" has no shorter form, so its level stays "Euston" while the other end steps down.
        val choice = EvenShortening.choose(
            DestinationAbbreviations.forms("Euston"),
            DestinationAbbreviations.forms("Shepherd's Bush Market"),
            260,
            10,
            width,
        )
        assertEquals(EvenShortening.Choice("Euston", "Shepherd's Bush Mkt", level = 1), choice)
    }

    @Test
    fun `a name shortens its long words, then to its floor, as a departure's destination does`() {
        assertEquals(
            listOf(
                "King's Cross & St Pancras International",
                "King's X & St Pancras Intl",
                "King's X & St Pancras Intl",
            ),
            DestinationAbbreviations.forms("King's Cross & St Pancras International"),
        )
        assertEquals(
            listOf("Heathrow Terminals 2 & 3", "Heathrow T. 2 & 3", "Heathrow T. 2 & 3"),
            DestinationAbbreviations.forms("Heathrow Terminals 2 & 3"),
        )
        // Nothing to abbreviate: the floor's initials are its short form.
        assertEquals(listOf("Battersea Power", "Battersea Power", "Battersea P."), DestinationAbbreviations.forms("Battersea Power"))
    }

    @Test
    fun `both ends drop to their initials together when abbreviating isn't enough`() {
        // "Battersea Power" (15) + "Elephant & Castle" (17) is 320 in full and abbreviated alike; at
        // their floors, "Battersea P." (12) + "Elephant & C." (13) is 250, which fits 260.
        val choice = EvenShortening.choose(
            DestinationAbbreviations.forms("Battersea Power"),
            DestinationAbbreviations.forms("Elephant & Castle"),
            260,
            10,
            width,
        )
        assertEquals(EvenShortening.Choice("Battersea P.", "Elephant & C.", level = 2), choice)
    }

    @Test
    fun `an optional extra shows while both names fit in full beside it`() {
        // "Euston" (6) + "Victoria" (8) is 140, with a gap of 10 and a 70 px distance 220: room at 220.
        val choice = EvenShortening.choose(
            DestinationAbbreviations.forms("Euston"),
            DestinationAbbreviations.forms("Victoria"),
            220,
            listOf(10),
            70,
            width,
        )
        assertEquals(EvenShortening.Choice("Euston", "Victoria", extra = true), choice)
    }

    @Test
    fun `an optional extra is the first thing to go, before either name shortens`() {
        // "Baker Street" (12) + "Victoria" (8) is 200 + 10: with the 70 px distance 280 doesn't fit 250,
        // so the distance goes and both names stay whole.
        val choice = EvenShortening.choose(
            DestinationAbbreviations.forms("Baker Street"),
            DestinationAbbreviations.forms("Victoria"),
            250,
            listOf(10),
            70,
            width,
        )
        assertEquals(EvenShortening.Choice("Baker Street", "Victoria"), choice)
    }

    @Test
    fun `what sits between the ends shortens at the same level`() {
        // "Baker Street" (12), "Platform 6" (10) and "Euston" (6) need 280; the abbreviated name beside
        // the whole platform still needs 240. Only with "Plat 6" too (200) does the line fit 230: the gap
        // is per level, so the platform shortens with the names.
        val platform = EvenShortening.qualifierForms("Platform 6").map { it.length * 10 }
        assertEquals(listOf(100, 60), platform)
        val choice = EvenShortening.choose(
            DestinationAbbreviations.forms("Baker Street"),
            DestinationAbbreviations.forms("Euston"),
            230,
            platform,
            width,
        )
        assertEquals(EvenShortening.Choice("Baker St", "Euston", level = 1), choice)
    }

    @Test
    fun `a platform shortens to Plat and a pole keeps its label`() {
        assertEquals(listOf("Platform 6", "Plat 6"), EvenShortening.qualifierForms("Platform 6"))
        assertEquals(listOf("Stop K", "Stop K"), EvenShortening.qualifierForms("Stop K"))
        assertEquals(listOf("Southbound", "Southbound"), EvenShortening.qualifierForms("Southbound"))
    }

    @Test
    fun `below the shortest forms a short end keeps all it needs and the long one takes the rest`() {
        val choice = EvenShortening.choose(listOf("Bank"), listOf("A very long name indeed"), 150, 10, width)
        assertEquals(EvenShortening.Choice("Bank", "A very long name indeed", 40, 100), choice)
    }

    @Test
    fun `below the shortest forms two long ends share the width evenly`() {
        val choice = EvenShortening.choose(listOf("A long first name"), listOf("A long second name"), 210, 10, width)
        assertEquals(EvenShortening.Choice("A long first name", "A long second name", 100, 100), choice)
    }

    @Test
    fun `a line too narrow for the gap gives each end nothing rather than a negative width`() {
        val choice = EvenShortening.choose(listOf("Bank"), listOf("Euston"), 5, 10, width)
        assertEquals(EvenShortening.Choice("Bank", "Euston", 0, 0), choice)
    }
}
