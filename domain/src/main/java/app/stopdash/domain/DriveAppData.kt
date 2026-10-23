package app.stopdash.domain

/**
 * A file in the rider's Google Drive app data folder (dev-docs/drive-sync.md), as Drive lists it:
 * [modifiedTime] is Drive's own stamp, compared only for a change since the file was last read.
 */
data class DriveFile(val id: String, val name: String, val modifiedTime: String)

/** The folder's files, and Drive's clock as of the answer that listed them, for this sync's stamps. */
data class DriveListing(val files: List<DriveFile>, val clock: DriveClock)

/** Why a request to Drive came to nothing, as the sync's status says it (dev-docs/drive-sync.md *Failure modes*). */
sealed interface DriveFailure {
    /** No answer: offline, unreachable, or timed out. The next sync asks again. */
    data object Offline : DriveFailure

    /** No grant to use the folder, or one Drive no longer takes: "Sign in again". */
    data object SignInNeeded : DriveFailure

    /** The rider's Drive storage is full: "Drive is full". */
    data object Full : DriveFailure

    /** Drive asked for fewer requests. The next sync asks again. */
    data object RateLimited : DriveFailure

    /** The file isn't there any more (another phone can't delete it, but the rider can, from Drive). */
    data object NotFound : DriveFailure

    /** An answer that couldn't be read: not the JSON asked for, or without Drive's clock. */
    data object Unreadable : DriveFailure

    /** Any other refusal, by its HTTP status. */
    data class Refused(val status: Int) : DriveFailure
}

/** A request to Drive's answer: its [Ok] value, or why there's none. */
sealed interface DriveResult<out T> {
    data class Ok<out T>(val value: T) : DriveResult<T>

    data class Failed(val failure: DriveFailure) : DriveResult<Nothing>
}

/**
 * The rider's Drive app data folder, the one place sync writes (dev-docs/drive-sync.md): only the files
 * StopDash made there, never the rest of their Drive. A phone writes only its own file and never deletes
 * another's; [delete] is the rider's own "delete StopDash's data from Drive".
 */
interface DriveAppData {
    /** Every file in the folder, with Drive's clock as of the answer. */
    suspend fun list(): DriveResult<DriveListing>

    /** File [id]'s text. */
    suspend fun download(id: String): DriveResult<String>

    /** A new file [name] holding [text]. */
    suspend fun create(name: String, text: String): DriveResult<DriveFile>

    /** File [id] now holding [text]. */
    suspend fun update(id: String, text: String): DriveResult<DriveFile>

    /** File [id] gone; one already gone counts as done. */
    suspend fun delete(id: String): DriveResult<Unit>
}
