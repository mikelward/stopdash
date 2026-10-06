package app.stopdash.data

import android.content.Context
import java.io.File

/**
 * The app's cache and no-backup directories, named from the data directory Android already holds
 * in memory. `Context.cacheDir` and `noBackupFilesDir` check (and make) the directory on disk on
 * every call, so a store built on the main thread from them read the disk there (maintainer bug
 * report, 2026-10-05). A store given a file under these makes its directory when it first writes,
 * off the main thread.
 */
internal object AppDirs {
    fun cache(context: Context): File = File(context.applicationContext.applicationInfo.dataDir, "cache")

    fun noBackup(context: Context): File = File(context.applicationContext.applicationInfo.dataDir, "no_backup")
}
