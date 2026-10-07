package app.stopdash.widget

import android.content.Context
import app.stopdash.data.logAppSettingsWarning
import app.stopdash.domain.AppSettings
import app.stopdash.domain.UsageEvent
import app.stopdash.telemetry.UsageEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The outcome of applying the "refresh widget every minute" choice, so the caller can surface a
 * failure to the user instead of the coordinator swallowing it.
 */
enum class LiveWidgetRefreshResult { APPLIED, FAILED }

/**
 * Serializes every persist+schedule application (both entry points) so concurrent invocations can't
 * interleave. A rapid toggle launches an independent coroutine per callback; without this an OFF's
 * persist+cancel could interleave with a following ON's persist+schedule — the ON seeing the
 * not-yet-cancelled tick as pending and skipping the enqueue, then the OFF's cancel completing —
 * leaving the switch persisted on with no tick scheduled (Codex P2 on #56). Under the lock each
 * application runs to completion before the next starts, so the last one owns both the persisted
 * value and the schedule. Process-wide, so a startup sync and a toggle serialize too.
 */
private val liveWidgetRefreshMutex = Mutex()

/**
 * Coordinates the opt-in live-widget refresh setting: it owns persisting the choice, applying the
 * WorkManager schedule, and the **single error policy** for both — so the UI callback and the
 * startup sync don't each re-implement (and re-break) that handling. The per-call-site guards this
 * replaces drew six rounds of review findings; this is the one place the policy lives now.
 *
 * The policy, once, for both entry points:
 * - **The user's choice is kept on failure.** A transient DataStore or WorkManager error must not
 *   silently flip an explicit "on" back off — the setting self-heals (the next startup [syncLiveWidgetRefreshSchedule]
 *   and any widget render's `resumeWidgetRefreshIfEnabled` re-apply it), so a rollback would discard
 *   real intent for nothing.
 * - **The failure is reported, not swallowed** — logged sanitized (SPEC *Error handling* / *Privacy*)
 *   and returned as [LiveWidgetRefreshResult.FAILED] so the UI can show "couldn't start live refresh".
 * - **Cancellation propagates** (structured concurrency), and nothing here throws to the caller.
 */
suspend fun applyLiveWidgetRefresh(
    context: Context,
    settings: AppSettings,
    enabled: Boolean,
): LiveWidgetRefreshResult =
    liveWidgetRefreshMutex.withLock {
        try {
            settings.setLiveWidgetRefresh(enabled)
            UsageEvents.settingChanged(UsageEvent.SettingChanged.liveWidget(enabled))
            applyWidgetRefreshSetting(context, enabled)
            LiveWidgetRefreshResult.APPLIED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logAppSettingsWarning("live widget refresh apply failed: ${e::class.simpleName}")
            LiveWidgetRefreshResult.FAILED
        }
    }

/**
 * Re-applies the persisted setting to the scheduler — called once at startup so an enabled toggle
 * resumes the chain after process death. Same policy as [applyLiveWidgetRefresh]. Reads the value
 * through the bounded [liveWidgetRefreshNow]: the store flow retries a transient read forever (to
 * keep the long-lived collector alive), so a plain `.first()` here would never complete on a
 * persistent storage error — the sync would hang and never report FAILED, so the restore wouldn't
 * run and the error notice wouldn't show. A null read (couldn't read in time) is therefore FAILED,
 * which surfaces the notice and self-heals on the next startup/render (Codex P2 on #56).
 */
suspend fun syncLiveWidgetRefreshSchedule(
    context: Context,
    settings: AppSettings,
): LiveWidgetRefreshResult =
    liveWidgetRefreshMutex.withLock {
        try {
            when (val enabled = settings.liveWidgetRefreshNow()) {
                null -> LiveWidgetRefreshResult.FAILED
                else -> {
                    applyWidgetRefreshSetting(context, enabled)
                    LiveWidgetRefreshResult.APPLIED
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logAppSettingsWarning("live widget refresh startup sync failed: ${e::class.simpleName}")
            LiveWidgetRefreshResult.FAILED
        }
    }
