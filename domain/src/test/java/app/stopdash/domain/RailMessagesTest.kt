package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic messages in Darwin's HTML; no live text. */
class RailMessagesTest {
    @Test
    fun `a link keeps its words and its address`() {
        assertEquals(
            "Trains are delayed. See Latest Travel News (https://www.nationalrail.co.uk/example/).",
            railMessageText("""<p>Trains are delayed. See <a href="https://www.nationalrail.co.uk/example/" target="_blank">Latest Travel News</a>.</p>"""),
        )
        // Single-quoted, or unquoted.
        assertEquals("See news (https://example.com/a)", railMessageText("See <A HREF='https://example.com/a'>news</A>"))
        assertEquals("See news (https://example.com/a)", railMessageText("See <a href=https://example.com/a>news</a>"))
    }

    @Test
    fun `a link whose words are its address says it once`() {
        assertEquals(
            "Visit https://www.nationalrail.co.uk/example/",
            railMessageText("""Visit <a href="https://www.nationalrail.co.uk/example/">nationalrail.co.uk/example</a>"""),
        )
    }

    @Test
    fun `a link that isn't a web address keeps only its words`() {
        assertEquals("Email us", railMessageText("""<a href="mailto:help@example.com">Email us</a>"""))
        assertEquals("Call", railMessageText("""<a href="javascript:void(0)">Call</a>"""))
    }

    @Test
    fun `paragraphs and breaks become lines, tags go, entities decode`() {
        assertEquals(
            "Line one & more\nLine two’s <end>\nLast £5",
            railMessageText("<p>Line one &amp; more</p><p>Line&nbsp;two&rsquo;s &lt;end&gt;<br/>  <strong>Last</strong> &#163;5</p>"),
        )
        // An entity it doesn't know stays as written.
        assertEquals("A &bogus; B", railMessageText("A &bogus; B"))
    }

    @Test
    fun `nothing left to say is null`() {
        assertNull(railMessageText("  <p> </p><br>&nbsp;"))
    }

    @Test
    fun `a link's words and address are decoded once, an escaped tag in them kept`() {
        assertEquals(
            "Fares &pound; and <b> (https://example.com/a?x=1&y=2)",
            railMessageText("""<a href="https://example.com/a?x=1&amp;y=2">Fares &amp;pound; and &lt;b&gt;</a>"""),
        )
    }

    @Test
    fun `an address in capitals is still its own words`() {
        assertEquals("HTTPS://WWW.EXAMPLE.COM/A", railMessageText("""<a href="HTTPS://WWW.EXAMPLE.COM/A">example.com/a</a>"""))
    }
}
