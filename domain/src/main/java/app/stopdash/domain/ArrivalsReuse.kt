package app.stopdash.domain

import java.time.Duration

/**
 * How long a stop's arrivals are carried over without a request, for the app's refresh and the
 * widget's own alike: [near] for any refresh, and on the timer ([automatic]) the longer [far] for a
 * stop past the walking reach, since its departures matter once the rider is closer and the shared
 * TfL rate budget is better spent on the near ones.
 */
object ArrivalsReuse {
    fun window(near: Duration, far: Duration, automatic: Boolean, isFar: Boolean): Duration =
        if (automatic && isFar) maxOf(near, far) else near
}
