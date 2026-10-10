package app.stopdash

import app.stopdash.ui.NearbyStopsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure nearby-gate decision (the `LaunchedEffect` in `MainActivity` only supplies the three
 * booleans and carries out the result). The regression case is the coarse-only upgrade — an
 * install predating the FINE declaration keeps a live coarse grant that the manifest change does
 * not upgrade, so it must be offered precise exactly once rather than silently keeping the
 * inaccurate coarse fix.
 */
class NearbyPermissionActionTest {
    @Test
    fun `precise held locates`() {
        assertEquals(
            NearbyPermissionAction.LOCATE,
            nearbyPermissionAction(hasFine = true, hasAnyLocation = true, precisePrompted = false),
        )
    }

    @Test
    fun `coarse-only upgrade with precise never prompted requests precise once`() {
        // The reported bug: without this the coarse-only install locates immediately and never
        // sees the precise dialog, permanently keeping the ~1 km-off fix this change fixes.
        assertEquals(
            NearbyPermissionAction.REQUEST_PRECISE,
            nearbyPermissionAction(hasFine = false, hasAnyLocation = true, precisePrompted = false),
        )
    }

    @Test
    fun `coarse held after the precise prompt was shown locates without re-prompting`() {
        // The user's completed approximate choice: locate rather than nag on every open.
        assertEquals(
            NearbyPermissionAction.LOCATE,
            nearbyPermissionAction(hasFine = false, hasAnyLocation = true, precisePrompted = true),
        )
    }

    @Test
    fun `no location permission waits for the gate's Allow button`() {
        assertEquals(
            NearbyPermissionAction.WAIT,
            nearbyPermissionAction(hasFine = false, hasAnyLocation = false, precisePrompted = false),
        )
        // precisePrompted can't be true without a grant having been offered, but the decision is
        // still WAIT with no permission held — nothing auto-fires.
        assertEquals(
            NearbyPermissionAction.WAIT,
            nearbyPermissionAction(hasFine = false, hasAnyLocation = false, precisePrompted = true, rationaleAllowed = true),
        )
    }

    @Test
    fun `refused for good before this start goes straight to settings`() {
        // Asked before and Android won't explain again: it won't prompt either, so an Allow tap
        // would be refused unseen.
        assertEquals(
            NearbyPermissionAction.REFUSED,
            nearbyPermissionAction(
                hasFine = false, hasAnyLocation = false, precisePrompted = true, lastRefused = true, rationaleAllowed = false,
            ),
        )
        // A one-time grant that has expired withholds the rationale too, but Android prompts again.
        assertEquals(
            NearbyPermissionAction.WAIT,
            nearbyPermissionAction(
                hasFine = false, hasAnyLocation = false, precisePrompted = true, lastRefused = false, rationaleAllowed = false,
            ),
        )
        // Never asked: Android withholds the rationale on a first open too, which isn't a refusal.
        assertEquals(
            NearbyPermissionAction.WAIT,
            nearbyPermissionAction(hasFine = false, hasAnyLocation = false, precisePrompted = false, rationaleAllowed = false),
        )
        // A grant held is never a refusal, whatever the rationale says.
        assertEquals(
            NearbyPermissionAction.LOCATE,
            nearbyPermissionAction(hasFine = true, hasAnyLocation = true, precisePrompted = true, rationaleAllowed = false),
        )
    }

    @Test
    fun `the gate holds its placeholder until the start has decided`() {
        val required = NearbyStopsViewModel.State.PermissionRequired
        assertEquals(NearbyStopsViewModel.State.Locating, gateShownState(required, pending = false, permissionDecided = false))
        assertEquals(required, gateShownState(required, pending = false, permissionDecided = true))
        // Only the permission state waits on the decision; a pending chip row holds any state.
        val noFix = NearbyStopsViewModel.State.NoLocation
        assertEquals(noFix, gateShownState(noFix, pending = false, permissionDecided = false))
        assertEquals(NearbyStopsViewModel.State.Locating, gateShownState(noFix, pending = true, permissionDecided = true))
    }

    @Test
    fun `only a refusal for good is remembered, not a first refusal or a dismissed prompt`() {
        assertEquals(false, locationAnswer(granted = true, rationaleBefore = false, rationaleAfter = false))
        // The second refusal: Android explained before and won't again.
        assertEquals(true, locationAnswer(granted = false, rationaleBefore = true, rationaleAfter = false))
        // A first refusal: Android will explain, and prompt, next time.
        assertEquals(null, locationAnswer(granted = false, rationaleBefore = false, rationaleAfter = true))
        // Back on a first prompt, or on one after a refusal: nothing changed.
        assertEquals(null, locationAnswer(granted = false, rationaleBefore = false, rationaleAfter = false))
        assertEquals(null, locationAnswer(granted = false, rationaleBefore = true, rationaleAfter = true))
        // Answered at once with no prompt shown: Android had already stopped asking.
        assertEquals(true, locationAnswer(granted = false, rationaleBefore = false, rationaleAfter = false, atOnce = true))
    }

    @Test
    fun `a grant looks stops up again unless approximate is kept over a shown list`() {
        assertTrue(relocateAfterGrant(fine = false, showingStops = false))
        assertTrue(relocateAfterGrant(fine = true, showingStops = false))
        assertTrue(relocateAfterGrant(fine = true, showingStops = true))
        assertFalse(relocateAfterGrant(fine = false, showingStops = true))
    }
}
