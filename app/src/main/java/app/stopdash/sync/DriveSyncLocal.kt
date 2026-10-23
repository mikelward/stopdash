package app.stopdash.sync

import android.content.Context
import app.stopdash.StopdashDebugLog
import app.stopdash.domain.SyncState
import app.stopdash.domain.SyncStateFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Drive sync keeps on this phone (dev-docs/drive-sync.md *Android backup*): whether it's on, this
 * install's id, and its [SyncState], in [dir] under the app's no-backup folder, which neither Auto Backup
 * nor device-to-device transfer carries to another phone (a phone restored from this one would otherwise
 * write as it). Turning sync off keeps the state, so turning it back on resumes rather than joins again.
 *
 * Read and written on [io]. A write lands whole or not at all (a temporary file moved into place); a disk
 * failure throws its IOException to the caller, which says sync couldn't run.
 */
internal class DriveSyncLocal(
    private val dir: File,
    private val warn: (String) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Whether the rider turned sync on, on this phone. */
    suspend fun isOn(): Boolean = withContext(io) { File(dir, ON).exists() }

    /** Keeps the switch; an IOException where it couldn't be. */
    suspend fun setOn(on: Boolean): Unit = withContext(io) {
        if (on) {
            write(ON, "on")
        } else {
            val file = File(dir, ON)
            if (!file.delete() && file.exists()) throw IOException("sync switch not cleared")
        }
    }

    /** This install's id, made once and kept: its file in Drive is named for it. */
    suspend fun phone(): String = withContext(io) {
        read(PHONE)?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString().also { write(PHONE, it) }
    }

    /** What was kept from the last sync; nothing kept (or a file that can't be read) starts afresh. */
    suspend fun state(): SyncState = withContext(io) {
        read(STATE)?.let { SyncStateFile.decode(it, warn) } ?: SyncState.EMPTY
    }

    suspend fun saveState(state: SyncState): Unit = withContext(io) { write(STATE, SyncStateFile.encode(state)) }

    private fun read(name: String): String? = try {
        File(dir, name).readText()
    } catch (e: FileNotFoundException) {
        // Not made yet: the first sync. Expected, so noted in passing, by which file alone.
        StopdashDebugLog.info("sync: nothing kept yet (%s)", name)
        null
    }

    private fun write(name: String, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private const val ON = "on"
        private const val PHONE = "phone"
        private const val STATE = "state.json"

        fun of(context: Context): DriveSyncLocal =
            DriveSyncLocal(File(context.applicationContext.noBackupFilesDir, "drive-sync"), warn = { StopdashDebugLog.warning("sync: %s", it) })
    }
}
