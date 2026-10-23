package app.stopdash.sync

import app.stopdash.domain.DriveFailure
import app.stopdash.domain.SyncOutcome
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** How a sync's outcome reads under Drive sync's Settings switch. */
class DriveSyncStatusTest {
    private val now = Instant.parse("2026-10-23T08:00:00Z")

    @Test
    fun `a sync says when it synced, or that a newer phone's file was skipped`() {
        assertEquals(DriveSyncStatus.Synced(now), DriveSync.statusOf(SyncOutcome.Synced(emptyMap(), newerPhone = false), now))
        assertEquals(DriveSyncStatus.NewerPhone, DriveSync.statusOf(SyncOutcome.Synced(emptyMap(), newerPhone = true), now))
    }

    @Test
    fun `a failure says what the rider can do about it`() {
        assertEquals(DriveSyncStatus.Offline, DriveSync.statusOf(SyncOutcome.Failed(DriveFailure.Offline), now))
        assertEquals(DriveSyncStatus.Offline, DriveSync.statusOf(SyncOutcome.Failed(DriveFailure.RateLimited), now))
        assertEquals(DriveSyncStatus.SignIn, DriveSync.statusOf(SyncOutcome.Failed(DriveFailure.SignInNeeded), now))
        assertEquals(DriveSyncStatus.Full, DriveSync.statusOf(SyncOutcome.Failed(DriveFailure.Full), now))
        assertEquals(DriveSyncStatus.Failed, DriveSync.statusOf(SyncOutcome.Failed(DriveFailure.Unreadable), now))
        assertEquals(DriveSyncStatus.Failed, DriveSync.statusOf(SyncOutcome.StoreUnsynced, now))
    }

    @Test
    fun `overtaken by a change here, it waits for the next sync`() {
        assertEquals(DriveSyncStatus.Waiting, DriveSync.statusOf(SyncOutcome.Overtaken, now))
    }
}
