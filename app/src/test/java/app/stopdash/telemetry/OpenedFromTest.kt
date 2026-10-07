package app.stopdash.telemetry

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stopdash.GetOffSoonAlert
import app.stopdash.PlaceShortcuts
import app.stopdash.domain.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenedFromTest {
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test
    fun `the app's icon, the widget, a notification and a shortcut each say so`() {
        assertEquals(UsageEvent.OpenedFrom.LAUNCHER, openedFrom(launcher()))
        assertEquals(UsageEvent.OpenedFrom.WIDGET, openedFrom(Intent().putExtra(EXTRA_OPENED_FROM, OPENED_FROM_WIDGET)))
        assertEquals(UsageEvent.OpenedFrom.NOTIFICATION, openedFrom(Intent().putExtra(GetOffSoonAlert.EXTRA_OPEN_ON_THE_WAY, true)))
        assertEquals(UsageEvent.OpenedFrom.SHORTCUT, openedFrom(Intent(PlaceShortcuts.ACTION_ROUTE_TO_PLACE)))
    }

    @Test
    fun `a start from Recents, or anything else, isn't counted as the icon`() {
        assertEquals(UsageEvent.OpenedFrom.OTHER, openedFrom(launcher().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        // Recents re-delivers the intent the task began with, whatever marked it.
        val fromHistory = listOf(
            Intent().putExtra(EXTRA_OPENED_FROM, OPENED_FROM_WIDGET),
            Intent().putExtra(GetOffSoonAlert.EXTRA_OPEN_ON_THE_WAY, true),
            Intent(PlaceShortcuts.ACTION_ROUTE_TO_PLACE),
        ).map { openedFrom(it.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)) }
        assertEquals(List(3) { UsageEvent.OpenedFrom.OTHER }, fromHistory)
        assertEquals(UsageEvent.OpenedFrom.OTHER, openedFrom(Intent(Intent.ACTION_VIEW)))
        assertEquals(UsageEvent.OpenedFrom.OTHER, openedFrom(null))
        assertEquals(UsageEvent.OpenedFrom.OTHER, openedFrom(Intent().putExtra(EXTRA_OPENED_FROM, "elsewhere")))
    }
}
