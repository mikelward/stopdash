package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SharedPlaceTest {

    @Test
    fun `a Google Maps share searches the place's name, not its link`() {
        assertEquals("Tate Modern", SharedPlace.queryOf("Tate Modern\nhttps://maps.app.goo.gl/AbCdEf123"))
    }

    @Test
    fun `the name is the first line, ahead of the address`() {
        assertEquals(
            "British Museum",
            SharedPlace.queryOf("British Museum\nGreat Russell St, London WC1B 3DG\nhttps://maps.app.goo.gl/XyZ"),
        )
    }

    @Test
    fun `a link run on to the name's line is dropped`() {
        assertEquals("Tate Modern", SharedPlace.queryOf("Tate Modern https://maps.app.goo.gl/AbCdEf123"))
        assertEquals("Tate Modern", SharedPlace.queryOf("Tate Modern www.example.com/tate"))
    }

    @Test
    fun `blank lines, quotes and extra spaces go`() {
        assertEquals("Somerset House", SharedPlace.queryOf("\n  “Somerset   House”  \n"))
    }

    @Test
    fun `a link alone has nothing to search, unless a subject names the place`() {
        assertNull(SharedPlace.queryOf("https://maps.app.goo.gl/AbCdEf123"))
        assertEquals("Tate Modern", SharedPlace.queryOf("https://maps.app.goo.gl/AbCdEf123", subject = "Tate Modern"))
    }

    @Test
    fun `nothing shared, or only punctuation, has nothing to search`() {
        assertNull(SharedPlace.queryOf(null))
        assertNull(SharedPlace.queryOf("  \n"))
        assertNull(SharedPlace.queryOf("--- !!!"))
    }

    @Test
    fun `a long message is cut at a word`() {
        val long = "Meet me at the main entrance of the Natural History Museum on Cromwell Road at half past ten tomorrow"
        val query = SharedPlace.queryOf(long)!!
        assertEquals(true, query.length <= SharedPlace.MAX_LENGTH)
        assertEquals(true, long.startsWith(query))
        assertEquals(false, query.endsWith(" "))
    }

    @Test
    fun `a geo link with a point routes to it`() {
        assertEquals(SharedPlace.Ask.Point(Coordinates(51.5, -0.12), null), SharedPlace.fromGeo("geo:51.5,-0.12"))
        assertEquals(SharedPlace.Ask.Point(Coordinates(51.5, -0.12), null), SharedPlace.fromGeo("geo:51.5,-0.12,30;u=10"))
        assertEquals(SharedPlace.Ask.Point(Coordinates(51.5, -0.12), null), SharedPlace.fromGeo("GEO:51.5,-0.12?z=15"))
    }

    @Test
    fun `a labeled point in the query routes to it by its label`() {
        assertEquals(
            SharedPlace.Ask.Point(Coordinates(51.5, -0.12), "Example Gallery"),
            SharedPlace.fromGeo("geo:0,0?q=51.5,-0.12(Example%20Gallery)"),
        )
        assertEquals(SharedPlace.Ask.Point(Coordinates(51.5, -0.12), null), SharedPlace.fromGeo("geo:0,0?q=51.5,-0.12"))
    }

    @Test
    fun `an address or a name in the query is searched`() {
        assertEquals(SharedPlace.Ask.Search("Tate Modern"), SharedPlace.fromGeo("geo:0,0?q=Tate+Modern"))
        assertEquals(SharedPlace.Ask.Search("Bankside, London SE1 9TG"), SharedPlace.fromGeo("geo:0,0?q=Bankside%2C%20London%20SE1%209TG"))
        assertEquals(SharedPlace.Ask.Search("cafe"), SharedPlace.fromGeo("geo:51.5,-0.12?q=cafe"))
        assertEquals(SharedPlace.Ask.Search("100% Cafe"), SharedPlace.fromGeo("geo:0,0?q=100%+Cafe"))
        assertEquals(SharedPlace.Ask.Search("100% Cafe"), SharedPlace.fromGeo("geo:0,0?q=100%25+Cafe"))
        assertEquals(SharedPlace.Ask.Search("Café 🚲"), SharedPlace.fromGeo("geo:0,0?q=Caf%C3%A9+🚲"))
    }

    @Test
    fun `no point and no query, or not a geo link, asks for nothing`() {
        assertNull(SharedPlace.fromGeo("geo:0,0"))
        assertNull(SharedPlace.fromGeo("geo:0,0?q="))
        assertNull(SharedPlace.fromGeo("geo:95,-0.12"))
        assertNull(SharedPlace.fromGeo("geo:abc"))
        assertNull(SharedPlace.fromGeo("https://maps.app.goo.gl/AbCdEf123"))
        assertNull(SharedPlace.fromGeo(null))
    }

    @Test
    fun `a point's label is cut to a name's length`() {
        val long = "Example " + "Gallery ".repeat(40)
        val label = (SharedPlace.fromGeo("geo:0,0?q=51.5,-0.12($long)") as SharedPlace.Ask.Point).label
        assertTrue(label!!.length <= SharedPlace.MAX_LENGTH)
        assertTrue(label.startsWith("Example Gallery"))
    }

    @Test
    fun `what is taken off a share is bounded, and a short one is kept whole`() {
        assertEquals("Tate Modern", SharedPlace.bounded("Tate Modern"))
        assertEquals(SharedPlace.MAX_SHARED, SharedPlace.bounded("x".repeat(SharedPlace.MAX_SHARED * 3))?.length)
        assertEquals(null, SharedPlace.bounded(null))
    }
    @Test
    fun `a share's Google Maps link is found, short or full, and nothing else is`() {
        assertEquals(
            "https://maps.app.goo.gl/AbCdEf123",
            SharedPlace.mapsLinkOf("Tate Modern\nBankside, London SE1 9TG\nhttps://maps.app.goo.gl/AbCdEf123"),
        )
        assertEquals("https://goo.gl/maps/XyZ", SharedPlace.mapsLinkOf("See https://goo.gl/maps/XyZ."))
        assertEquals(
            "https://www.google.com/maps/place/Tate+Modern/@51.5076,-0.0994,17z",
            SharedPlace.mapsLinkOf("https://www.google.com/maps/place/Tate+Modern/@51.5076,-0.0994,17z"),
        )
        assertNull(SharedPlace.mapsLinkOf("Tate Modern https://www.example.com/maps/x"))
        assertNull(SharedPlace.mapsLinkOf("http://maps.app.goo.gl/AbCdEf123"))
        assertNull(SharedPlace.mapsLinkOf("https://goo.gl/AbC"))
        assertNull(SharedPlace.mapsLinkOf("https://www.google.com/search?q=tate"))
        assertNull(SharedPlace.mapsLinkOf("Tate Modern"))
        assertEquals("https://maps.app.goo.gl/X", SharedPlace.mapsLinkOf("Tate Modern", subject = "https://maps.app.goo.gl/X"))
    }

    @Test
    fun `only a short link needs following, and only Google's Maps pages are hops`() {
        assertTrue(SharedPlace.isShortMapsLink("https://maps.app.goo.gl/AbC"))
        assertTrue(SharedPlace.isShortMapsLink("https://goo.gl/maps/AbC"))
        assertEquals(false, SharedPlace.isShortMapsLink("https://www.google.com/maps/@51.5,-0.12,15z"))
        assertTrue(SharedPlace.isGoogleMapsHop("https://www.google.co.uk/maps/place/X"))
        assertTrue(SharedPlace.isGoogleMapsHop("https://consent.google.com/m?continue=x"))
        assertEquals(false, SharedPlace.isGoogleMapsHop("https://evil.example/maps/place/X"))
        assertEquals(false, SharedPlace.isGoogleMapsHop("https://www.google.com.evil.example/maps/"))
        assertEquals(false, SharedPlace.isGoogleMapsHop("https://user@evil.example/maps"))
        assertEquals(false, SharedPlace.isGoogleMapsHop("http://www.google.com/maps/place/X"))
    }

    @Test
    fun `a place's page routes to its pin, not the map's center`() {
        val url = "https://www.google.com/maps/place/Tate+Modern/@51.5080,-0.1010,17z/" +
            "data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d51.5076!4d-0.0994!16s%2Fm%2F0abc"
        assertEquals(SharedPlace.Ask.Point(Coordinates(51.5076, -0.0994), "Tate Modern"), SharedPlace.fromMapsUrl(url, "Tate Modern"))
    }

    @Test
    fun `a point in the link routes to it`() {
        val point = SharedPlace.Ask.Point(Coordinates(51.5, -0.12), null)
        assertEquals(point, SharedPlace.fromMapsUrl("https://maps.google.com/?q=51.5,-0.12"))
        assertEquals(point, SharedPlace.fromMapsUrl("https://www.google.com/maps/search/?api=1&query=51.5%2C-0.12"))
        assertEquals(point, SharedPlace.fromMapsUrl("https://www.google.com/maps/search/51.5,+-0.12?entry=tts"))
        assertEquals(point, SharedPlace.fromMapsUrl("https://www.google.com/maps/@51.5,-0.12,15z"))
        assertEquals(point, SharedPlace.fromMapsUrl("https://www.google.com/maps/dir//51.5,-0.12/@51.4,-0.2,12z"))
    }

    @Test
    fun `a link naming its place by text searches it, and directions' center is never the place`() {
        assertEquals(
            SharedPlace.Ask.Search("Tate Modern, Bankside, London SE1 9TG"),
            SharedPlace.fromMapsUrl("https://maps.google.com?q=Tate+Modern,+Bankside,+London+SE1+9TG&ftid=0x0:0x1&entry=gps"),
        )
        assertNull(SharedPlace.fromMapsUrl("https://www.google.com/maps/dir/A/B/@51.4,-0.2,12z"))
    }

    @Test
    fun `a consent page is read for the Maps link it carries, and nothing else is read`() {
        assertEquals(
            SharedPlace.Ask.Point(Coordinates(51.5, -0.12), "Somewhere"),
            SharedPlace.fromMapsUrl(
                "https://consent.google.com/m?continue=https://maps.google.com/maps?q%3D51.5,-0.12&gl=GB",
                "Somewhere",
            ),
        )
        assertNull(SharedPlace.fromMapsUrl("https://consent.google.com/m?continue=https://evil.example/?q%3D51.5,-0.12"))
        assertNull(SharedPlace.fromMapsUrl("https://evil.example/maps?q=51.5,-0.12"))
        assertNull(SharedPlace.fromMapsUrl("https://maps.google.com/?q=0,0"))
    }

    @Test
    fun `an address with a postcode is read for the postcode and what comes first`() {
        // As Calendar and Contacts share an event's or a contact's address: one line, postcode near its end.
        assertEquals(
            listOf(SharedPlace.Address("XX1 1XX", "10 Example Street")),
            SharedPlace.addressesOf("10 Example Street, London XX1 1XX, UK"),
        )
        assertEquals(listOf(SharedPlace.Address("XX1 1XX", "10 Example Street")), SharedPlace.addressesOf("10 Example Street, London XX11XX"))
        assertEquals(listOf(SharedPlace.Address("XX1 1XX", "XX1 1XX")), SharedPlace.addressesOf("XX1 1XX"))
        assertEquals(listOf(SharedPlace.Address("XX1 1XX", "10 Example Street", capitals = false)), SharedPlace.addressesOf("10 Example Street, xx1 1xx"))
        assertEquals(emptyList<SharedPlace.Address>(), SharedPlace.addressesOf("10 Example Street, London, UK"))
        // Only a postcode standing alone, so a code inside a longer run of letters and digits isn't one.
        assertEquals(emptyList<SharedPlace.Address>(), SharedPlace.addressesOf("Order ABXX1 1XXZ"))
        assertEquals(emptyList<SharedPlace.Address>(), SharedPlace.addressesOf(null))
        // Its halves apart by any spaces: a non-breaking one, a tab, a run of them.
        for (gap in listOf("\u00A0", "\t", "   ", "\u2003", "\u2009")) {
            assertEquals(listOf("XX1 1XX"), SharedPlace.addressesOf("10 Example Street, XX1${gap}1XX").map { it.postcode })
        }
        // Every one, in order: which is real is for the bundle to say.
        assertEquals(
            listOf("B12 3RD", "XX1 1XX"),
            SharedPlace.addressesOf("Room b12 3rd floor, 10 Example Street, London XX1 1XX").map { it.postcode },
        )
    }

    @Test
    fun `the line a share names is read whole, a geo link's text too`() {
        val long = "10 Example Street, " + "Somewhere ".repeat(10) + "XX1 1XX"
        assertEquals(long, SharedPlace.lineOf("$long\nhttps://maps.app.goo.gl/AbCdEf123"))
        assertEquals("10 Example Street, London XX1 1XX", SharedPlace.geoText("geo:0,0?q=10+Example+Street%2C+London+XX1+1XX"))
        assertNull(SharedPlace.geoText("geo:0,0?q=51.5,-0.12(Example)"))
        assertNull(SharedPlace.geoText("geo:51.5,-0.12"))
    }

    @Test
    fun `an address without a postcode searches its street`() {
        assertEquals("10 Example Street", SharedPlace.queryOf("10 Example Street, London, UK"))
        assertEquals(SharedPlace.Ask.Search("10 Example Street"), SharedPlace.fromGeo("geo:0,0?q=10+Example+Street,+London"))
        // A name with a comma in it isn't an address.
        assertEquals("Smith, Jones & Co", SharedPlace.queryOf("Smith, Jones & Co"))
    }
}
