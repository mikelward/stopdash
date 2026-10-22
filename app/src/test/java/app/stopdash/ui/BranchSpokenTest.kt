package app.stopdash.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a screen reader hears for a shortened or lone branch: the words are the app's string resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class BranchSpokenTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `a shortened branch beside its terminus is heard as the branch in full`() {
        assertEquals("via Newbury Park", BranchSpoken("Newbury Park").spokenText(resources))
    }

    @Test
    fun `a branch with its terminus named is heard as the whole route`() {
        assertEquals("Hainault via Newbury Park", BranchSpoken("Newbury Park", terminus = "Hainault").spokenText(resources))
    }
}
