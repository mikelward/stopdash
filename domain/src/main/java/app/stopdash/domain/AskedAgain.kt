package app.stopdash.domain

import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * What [source] emits, asked for again [every] after it ends, until the collector stops. Location
 * updates end at once without precise location or an accurate provider (or when one goes away), and
 * a caller still on screen wants them back the moment either returns, with nothing else on screen
 * changing to ask for them (Codex, #458 and #485).
 */
fun <T> askedAgainWhenEnded(every: Duration, source: () -> Flow<T>): Flow<T> = flow {
    while (true) {
        emitAll(source())
        delay(every.toMillis())
    }
}
