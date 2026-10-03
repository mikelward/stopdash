package app.stopdash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ByIdentityTest {
    @Test
    fun aKey_isEqualOnlyToTheVerySameValue() {
        val pending = setOf("E", "F")
        assertEquals(ByIdentity(pending), ByIdentity(pending))
        // An equal copy is a new key, so its contents are never compared.
        assertNotEquals(ByIdentity(pending), ByIdentity(HashSet(pending)))
    }
}
