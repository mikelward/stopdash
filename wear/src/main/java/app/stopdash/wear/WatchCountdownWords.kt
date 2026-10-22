package app.stopdash.wear

import android.content.res.Resources
import app.stopdash.domain.CountdownWords

/** A countdown's words ([CountdownWords]) in the watch's language, from its own string resources: "3 · 7 min". */
internal fun countdownWords(resources: Resources): CountdownWords =
    CountdownWords(resources.getString(R.string.countdown_between)) { resources.getString(R.string.countdown_minutes, it) }
