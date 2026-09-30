package app.stopdash.domain

/**
 * Why a [TflClient] call failed, in the terms a surface must tell apart to be honest
 * (SPEC principles 1–2: a failure is shown as what it is, never an empty or stale
 * list). The client maps transport and HTTP failures onto these, so the caller — the
 * ViewModel now, the widget later — decides what the user sees without depending on
 * the HTTP engine. Messages stay sanitized (a coarse reason or an HTTP status, never a
 * stop id, coordinate, or `app_key`; SPEC *Privacy* applies to logs and errors too).
 */
sealed class TflException(message: String, cause: Throwable?) : Exception(message, cause) {
    /** No usable network — offline, or TfL's host didn't resolve or connect. */
    class Offline(cause: Throwable?) : TflException("offline", cause)

    /** TfL returned 429 — a user-supplied `app_key` raises the limit (SPEC D7). */
    class RateLimited(cause: Throwable?) : TflException("rate limited", cause)

    /**
     * TfL answered 404: it doesn't know what was asked for ("The following line id is not
     * recognised" — a National Rail service it has no line for). Asking again won't change it, so a
     * surface says the thing isn't available rather than offer a retry.
     */
    class NotFound(cause: Throwable?) : TflException("HTTP 404", cause)

    /**
     * TfL refused the user's own `app_key` (401 or 403): mistyped, expired or revoked (SPEC D7).
     * Asking again with it fails the same way, so a surface says so and offers to clear it, and the
     * app goes on keyless. Only raised when a key was sent; a keyless refusal is [Unreachable].
     */
    class KeyRejected(cause: Throwable?) : TflException("app_key rejected", cause)

    /** Reached TfL but the request still failed — a non-2xx, or a decode failure. */
    class Unreachable(reason: String, cause: Throwable?) : TflException(reason, cause)

    /**
     * Online, but the request didn't complete — a timeout, a refused or reset connection, a TLS
     * failure. Told apart from [Unreachable] so a surface says "network error" rather than blame
     * TfL for what may be the phone's own connection.
     */
    class Network(reason: String, cause: Throwable?) : TflException(reason, cause)
}
