package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationAbbreviationsTest {

    private fun abbrev(name: String) = DestinationAbbreviations.abbreviate(name)

    private fun floor(name: String) = DestinationAbbreviations.floor(name)

    @Test
    fun `single-letter forms take a period`() {
        assertEquals("N. Greenwich", abbrev("North Greenwich"))
        assertEquals("S. Woodford", abbrev("South Woodford"))
        assertEquals("E. Finchley", abbrev("East Finchley"))
        assertEquals("W. Ruislip", abbrev("West Ruislip"))
        assertEquals("H. Barnet", abbrev("High Barnet"))
        assertEquals("U. Holloway", abbrev("Upper Holloway"))
        assertEquals("L. Sydenham", abbrev("Lower Sydenham"))
        assertEquals("Heathrow T. 5", abbrev("Heathrow Terminal 5"))
        assertEquals("Heathrow T. 2 & 3", abbrev("Heathrow Terminals 2 & 3"))
    }

    @Test
    fun `multi-letter forms take no period`() {
        assertEquals("Gt Portland St", abbrev("Great Portland Street"))
        assertEquals("Clapham Jct", abbrev("Clapham Junction"))
        assertEquals("Finsbury Pk", abbrev("Finsbury Park"))
        assertEquals("Tottenham Court Rd", abbrev("Tottenham Court Road"))
        assertEquals("Baker St", abbrev("Baker Street"))
        assertEquals("Blackwall Pt", abbrev("Blackwall Point"))
    }

    @Test
    fun `street, place and landmark words take their address forms`() {
        assertEquals("Ealing Bwy", abbrev("Ealing Broadway"))
        assertEquals("Sloane Sq", abbrev("Sloane Square"))
        assertEquals("Kensington Gdns", abbrev("Kensington Gardens"))
        assertEquals("Bethnal Gn", abbrev("Bethnal Green"))
        assertEquals("Shaftesbury Ave", abbrev("Shaftesbury Avenue"))
        assertEquals("Ladbroke Grove / Westbourne Pl", abbrev("Ladbroke Grove / Westbourne Place"))
        assertEquals("Mornington Cres", abbrev("Mornington Crescent"))
        assertEquals("London Br", abbrev("London Bridge"))
        assertEquals("Hampstead Hth", abbrev("Hampstead Heath"))
        assertEquals("St Thomas' Hosp", abbrev("St Thomas' Hospital"))
    }

    @Test
    fun `a branch takes the new forms too`() {
        assertEquals("Ealing Bwy", abbreviateBranch("Ealing Broadway"))
    }

    @Test
    fun `several words in one name are all abbreviated`() {
        assertEquals("H. St Kensington", abbrev("High Street Kensington"))
        assertEquals("N. Pk", abbrev("North Park"))
    }

    @Test
    fun `only whole words are abbreviated, never substrings`() {
        // The trap: a word that starts with a key must not be touched.
        assertEquals("Highgate", abbrev("Highgate"))
        assertEquals("Eastcote", abbrev("Eastcote"))
        assertEquals("Upminster", abbrev("Upminster"))
        assertEquals("Streatham", abbrev("Streatham"))
        assertEquals("Greenford", abbrev("Greenford"))
        assertEquals("Bridgewater", abbrev("Bridgewater"))
        assertEquals("Northwood", abbrev("Northwood"))
        assertEquals("Southgate", abbrev("Southgate"))
    }

    @Test
    fun `a saint prefix is left alone`() {
        // "St" for Saint is a different token from "Street"; only the whole word "Street" maps.
        assertEquals("St Pancras", abbrev("St Pancras"))
        assertEquals("St John's Wood", abbrev("St John's Wood"))
    }

    @Test
    fun `names with nothing to shorten are unchanged`() {
        assertEquals("Morden", abbrev("Morden"))
        assertEquals("Brixton", abbrev("Brixton"))
        assertEquals("Elephant & Castle", abbrev("Elephant & Castle"))
        assertEquals("Cockfosters", abbrev("Cockfosters"))
    }

    @Test
    fun `abbreviating is idempotent`() {
        val once = abbrev("East Finchley")
        assertEquals(once, abbrev(once))
        val gt = abbrev("Great Portland Street")
        assertEquals(gt, abbrev(gt))
        val bdwy = abbrev("Ealing Broadway")
        assertEquals(bdwy, abbrev(bdwy))
    }

    @Test
    fun `international shortens as a station's name does, for every name that shortens`() {
        assertEquals("Stratford Intl", abbrev("Stratford International"))
        assertEquals("Stratford Intl", floor("Stratford International"))
        assertEquals(
            listOf("Stratford International", "Stratford Intl", "Stratford Intl"),
            DestinationAbbreviations.forms("Stratford International"),
        )
        // In full, common words shortened, then initials: the one list every shortened name steps through.
        assertEquals(listOf("Battersea Power", "Battersea Power", "Battersea P."), DestinationAbbreviations.forms("Battersea Power"))
    }

    @Test
    fun `cross takes TfL's own X, which is also the floor`() {
        // TfL's and the riders' own form, so no period; and as a mapped word it is the floor, never
        // initials ("King's C. S. P.") that lose which station it is.
        assertEquals("Charing X", abbrev("Charing Cross"))
        assertEquals("King's X St. Pancras", abbrev("King's Cross St. Pancras"))
        assertEquals("King's X St. Pancras", floor("King's Cross St. Pancras"))
        assertEquals("New X Gate", abbrev("New Cross Gate"))
        // A whole word only: Crossharbour keeps its name.
        assertEquals("Crossharbour", abbrev("Crossharbour"))
    }

    @Test
    fun `a mapped word is itself the floor, keeping the identity word`() {
        // When [abbreviate] shortens a word (the throwaway compass/qualifier), that IS the floor —
        // it keeps the identity ("Finchley", "Barnet"), better than a blind first-word form would.
        assertEquals("N. Finchley", floor("North Finchley"))
        assertEquals("H. Barnet", floor("High Barnet"))
        assertEquals("Mill Hill E.", floor("Mill Hill East"))
        assertEquals("Tottenham Court Rd", floor("Tottenham Court Road"))
    }

    @Test
    fun `an un-mapped name floors to the first word plus initials`() {
        // No mapped word, so the first word carries the identity: keep it whole, initial the rest.
        // Our worked case — "Battersea Power Station" reaches here as "Battersea Power" (suffix
        // stripped) and has no mapped word, so the general rule floors it with no special-casing.
        assertEquals("Battersea P.", floor("Battersea Power"))
        // A non-letter token is left whole, so it never reads "Elephant &. C.".
        assertEquals("Elephant & C.", floor("Elephant & Castle"))
    }

    @Test
    fun `an un-mapped name ending in a qualified place initials the words before it`() {
        // "Harrow" and "Sutton" are places a compass qualifies elsewhere (West Harrow, North Sutton),
        // so they carry the identity and the words before them are the throwaway part.
        assertEquals("S. H. Harrow", floor("Sudbury Hill Harrow"))
        assertEquals("K. Sutton", floor("Kings Sutton"))
        // "New" initials like any other qualifier (maintainer, 2026-10-18).
        assertEquals("N. Barnet", floor("New Barnet"))
        // A last word that isn't such a place keeps the first-word rule.
        assertEquals("Kentish T.", floor("Kentish Town"))
        assertEquals("Battersea P.", floor("Battersea Power"))
    }

    @Test
    fun `a one-word or blank name has no floor to take`() {
        assertEquals("Morden", floor("Morden"))
        assertEquals("Brixton", floor("Brixton"))
        assertEquals("", floor(""))
    }

    @Test
    fun `lane shortens to ln`() {
        assertEquals("Wood Ln", abbrev("Wood Lane"))
        assertEquals("Turnpike Ln", floor("Turnpike Lane"))
    }

    @Test
    fun `market shortens to mkt`() {
        assertEquals("Bush Mkt", abbrev("Bush Market"))
    }

    // A named word keeps the rest whole: "Canada Water Bus Stn" before it floors to initials.
    @Test
    fun `station shortens to stn`() {
        assertEquals("Canada Water Bus Stn", abbrev("Canada Water Bus Station"))
        assertEquals("Canada Water Bus Stn", floor("Canada Water Bus Station"))
    }

    // A station TfL names for two places ("Shepherd's Bush Market / Wood Lane") shortens each alike,
    // the slash and its spacing kept.
    @Test
    fun `each part of a slash-separated name shortens on its own`() {
        assertEquals("Wood Ln / White City Rd", abbrev("Wood Lane / White City Road"))
        assertEquals("Wood Ln/N. Acton", abbrev("Wood Lane/North Acton"))
        // Each part floors to its own identity: a mapped word where one maps, else first word + initials.
        assertEquals("Shepherd's Bush Mkt / Wood Ln", floor("Shepherd's Bush Market / Wood Lane"))
        assertEquals("Battersea P. / Elephant & C.", floor("Battersea Power / Elephant & Castle"))
        // Idempotent, and a bare slash is left alone.
        assertEquals("Wood Ln / White City Rd", abbrev(abbrev("Wood Lane / White City Road")))
        assertEquals("/", floor("/"))
    }
}
