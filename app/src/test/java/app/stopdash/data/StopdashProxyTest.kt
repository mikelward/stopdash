package app.stopdash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopdashProxyTest {
    @Test
    fun `National Rail times come with the rider's key or through the proxy, and not with neither`() {
        assertTrue(StopdashProxy.timesWith("EXAMPLE", proxy = null))
        assertTrue(StopdashProxy.timesWith(null, proxy = "https://proxy.example"))
        assertFalse(StopdashProxy.timesWith(" ", proxy = null))
        assertFalse(StopdashProxy.timesWith(null, proxy = null))
    }
}
