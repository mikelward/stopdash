package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stations a line's alert names (SPEC *Disruptions*). Stock station names, no user data. */
class AlertStopsTest {
    private fun stops(vararg names: String) = names.mapIndexed { i, name -> RouteStop("S$i", name) }

    private fun mentioned(text: String?, vararg names: String) =
        AlertStops.mentioned(text, stops(*names)).map { id -> names[id.removePrefix("S").toInt()] }.toSet()

    @Test
    fun `marks every station the alert names`() {
        assertEquals(
            setOf("Moorgate", "Monument"),
            mentioned("Diverted between Moorgate and Monument due to roadworks.", "Old Street", "Moorgate", "Bank", "Monument"),
        )
    }

    @Test
    fun `matches a station listed under its full TfL name`() {
        assertEquals(
            setOf("Charing Cross Underground Station"),
            mentioned("No service between Charing Cross and Kennington.", "Charing Cross Underground Station", "Embankment Underground Station"),
        )
    }

    @Test
    fun `ignores case in the name but not a lowercase word`() {
        assertEquals(setOf("Bank"), mentioned("Trains are not stopping at BANK.", "Bank"))
        assertEquals(emptySet<String>(), mentioned("Flooding by the river bank.", "Bank"))
    }

    @Test
    fun `a line, branch or holiday is not the station of the same name`() {
        assertEquals(
            emptySet<String>(),
            mentioned(
                "Minor delays on the Victoria line and the Bank branch. Hammersmith & City: no service on Bank Holiday.",
                "Victoria", "Bank", "Hammersmith",
            ),
        )
    }

    @Test
    fun `a name leading a list of lines is a line`() {
        assertEquals(
            emptySet<String>(),
            mentioned(
                "Victoria and Piccadilly lines: minor delays. Bank, Charing Cross and Kennington branches: good service.",
                "Victoria", "Bank", "Charing Cross",
            ),
        )
    }

    @Test
    fun `a station followed by other stations is still a station`() {
        assertEquals(
            setOf("Victoria", "Green Park"),
            mentioned("No service between Victoria and Green Park stations.", "Victoria", "Green Park"),
        )
    }

    @Test
    fun `the station is still marked where the alert also names its line`() {
        assertEquals(setOf("Victoria"), mentioned("Victoria line: no service, trains terminate at Victoria.", "Victoria"))
    }

    @Test
    fun `a name inside a longer word is not a match`() {
        assertEquals(emptySet<String>(), mentioned("Bankside works continue.", "Bank"))
    }

    @Test
    fun `spelling variants within the text still match`() {
        assertEquals(
            setOf("Elephant & Castle", "St. Paul's", "King's Cross St. Pancras"),
            mentioned(
                "Closed between Elephant and Castle and St Paul’s.\\nKing's Cross St Pancras is open.",
                "Elephant & Castle", "St. Paul's", "King's Cross St. Pancras",
            ),
        )
    }

    @Test
    fun `a bus stop listed with its cross street matches on its own name`() {
        assertEquals(
            setOf("Camomile Street / Bishopsgate", "Fenchurch Street"),
            mentioned(
                "Buses will be diverted and will miss stops Camomile Street and Fenchurch Street.",
                "Liverpool Street / Bishopsgate", "Camomile Street / Bishopsgate", "Fenchurch Street",
            ),
        )
    }

    @Test
    fun `the cross street alone is not a match`() {
        assertEquals(emptySet<String>(), mentioned("Bishopsgate is closed to traffic.", "Camomile Street / Bishopsgate"))
    }

    @Test
    fun `destinations in a towards list are not where the disruption is`() {
        assertEquals(
            setOf("Camomile Street"),
            mentioned(
                "Buses towards Lewisham and London Bridge will miss stops Camomile Street.",
                "Camomile Street", "London Bridge",
            ),
        )
    }

    @Test
    fun `a name after a towards list's sentence ends still counts`() {
        assertEquals(
            setOf("London Bridge"),
            mentioned("Buses run towards Lewisham. London Bridge is closed.", "London Bridge"),
        )
    }

    @Test
    fun `a line break ends a towards list as a full stop does`() {
        // Real and escaped line breaks alike: TfL's prose arrives with either.
        assertEquals(
            setOf("London Bridge"),
            mentioned("Buses towards London Bridge\nLondon Bridge Station is closed", "London Bridge"),
        )
        assertEquals(
            setOf("London Bridge"),
            mentioned("Buses towards London Bridge\\nLondon Bridge Station is closed", "London Bridge"),
        )
    }

    @Test
    fun `a line break ends a list of lines too`() {
        assertEquals(
            setOf("Victoria"),
            mentioned("Trains terminate at Victoria\nCircle and District lines: good service", "Victoria"),
        )
    }

    @Test
    fun `an all-caps alert still matches, full stops and all`() {
        assertEquals(
            setOf("St. Paul's"),
            mentioned("ST. PAUL'S STATION CLOSED", "St. Paul's", "Bank"),
        )
    }

    @Test
    fun `an all-caps alert marks every station it names, destinations and lines' namesakes too`() {
        // Capitals are the signal that ends a "towards" list or a list of lines; all-caps text has
        // none, so the matcher marks generously rather than miss the stop the buses skip.
        assertEquals(
            setOf("Lewisham", "Camomile Street / Bishopsgate"),
            mentioned("BUSES TOWARDS LEWISHAM WILL MISS STOPS CAMOMILE STREET", "Lewisham", "Camomile Street / Bishopsgate", "Aldgate"),
        )
        assertEquals(setOf("Victoria"), mentioned("VICTORIA AND PICCADILLY LINES: MINOR DELAYS", "Victoria", "Green Park"))
        // A single line or branch is still named by the word after it, whatever the case.
        assertEquals(emptySet<String>(), mentioned("VICTORIA LINE: MINOR DELAYS", "Victoria"))
    }

    @Test
    fun `matches a station without the place TfL qualifies it with, but not a longer name`() {
        assertEquals(
            setOf("Stratford (London)"),
            mentioned("No service at Stratford.", "Stratford (London)", "Stratford International"),
        )
        assertEquals(
            setOf("Stratford International"),
            mentioned("No service at Stratford International.", "Stratford (London)", "Stratford International"),
        )
    }

    @Test
    fun `matches a station whether or not the alert writes its apostrophe`() {
        assertEquals(setOf("Earl's Court"), mentioned("Minor delays between Earls Court and Wimbledon.", "Earl's Court"))
        assertEquals(setOf("Earls Court"), mentioned("No service at Earl’s Court.", "Earls Court"))
        // A possessive after the name still ends it.
        assertEquals(setOf("Victoria"), mentioned("Victoria's platforms are closed.", "Victoria"))
        assertEquals(setOf("Earl's Court"), mentioned("Earl's Court's lifts are unavailable.", "Earl's Court"))
    }

    @Test
    fun `destinations joined by or are still destinations`() {
        assertEquals(
            setOf("Camomile Street"),
            mentioned("Buses towards Lewisham or London Bridge will miss stops Camomile Street", "Lewisham", "London Bridge", "Camomile Street"),
        )
        // A destination's own lowercase word doesn't end the list.
        assertEquals(
            setOf("Camomile Street"),
            mentioned(
                "Buses towards Prince of Wales Road or South End Green will miss stops Camomile Street",
                "Prince of Wales Road", "South End Green", "Camomile Street",
            ),
        )
        // A question or exclamation mark ends the sentence, and the list with it.
        assertEquals(
            setOf("London Bridge"),
            mentioned("Buses towards Lewisham! London Bridge station is closed", "Lewisham", "London Bridge"),
        )
    }

    @Test
    fun `an all-caps alert with an ampersand is still all caps`() {
        assertEquals(
            setOf("Elephant & Castle", "Camomile Street"),
            mentioned("BUSES TOWARDS ELEPHANT & CASTLE WILL MISS STOPS CAMOMILE STREET", "Elephant & Castle", "Camomile Street"),
        )
        // All caps is judged per clause, so lowercase prose after it doesn't switch it off.
        assertEquals(
            setOf("Lewisham", "Camomile Street"),
            mentioned("BUSES TOWARDS LEWISHAM WILL MISS STOPS CAMOMILE STREET. For more information, check the website.", "Lewisham", "Camomile Street"),
        )
        // An all-caps template quoting stops in their own case is still all caps.
        assertEquals(
            setOf("Lewisham", "Camomile Street"),
            mentioned("BUSES TOWARDS LEWISHAM WILL MISS STOPS Camomile Street", "Lewisham", "Camomile Street"),
        )
        assertEquals(
            setOf("Lewisham", "Camomile Street", "Fenchurch Street"),
            mentioned(
                "BUSES TOWARDS LEWISHAM WILL MISS STOPS Camomile Street AND Fenchurch Street",
                "Lewisham", "Camomile Street", "Fenchurch Street",
            ),
        )
    }

    @Test
    fun `Waterloo and City Thameslink are two stations, but the Waterloo and City line is not one`() {
        assertEquals(
            setOf("Waterloo", "City Thameslink"),
            mentioned("No service between Waterloo and City Thameslink.", "Waterloo", "City Thameslink"),
        )
        assertEquals(emptySet<String>(), mentioned("WATERLOO & CITY LINE: GOOD SERVICE", "Waterloo"))
        assertEquals(emptySet<String>(), mentioned("Waterloo & City: part suspended.", "Waterloo"))
        assertEquals(emptySet<String>(), mentioned("Victoria and Hammersmith & City lines: minor delays", "Victoria", "Hammersmith"))
        assertEquals(emptySet<String>(), mentioned("Use the Victoria or Piccadilly lines instead.", "Victoria"))
    }

    @Test
    fun `a station before another starting City is still a station`() {
        assertEquals(
            setOf("Bank", "City Thameslink"),
            mentioned("No service between Bank and City Thameslink.", "Bank", "City Thameslink", "Hammersmith"),
        )
    }

    @Test
    fun `the Waterloo and City line doesn't mark Waterloo`() {
        assertEquals(emptySet<String>(), mentioned("Waterloo & City line: part suspended.", "Waterloo"))
    }

    @Test
    fun `no text or no stops marks nothing`() {
        assertEquals(emptySet<String>(), mentioned(null, "Bank"))
        assertEquals(emptySet<String>(), mentioned("  ", "Bank"))
        assertEquals(emptySet<String>(), AlertStops.mentioned("Bank", emptyList()))
    }
}
