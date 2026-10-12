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
}
