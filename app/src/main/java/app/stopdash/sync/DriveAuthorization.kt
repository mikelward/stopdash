package app.stopdash.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.stopdash.StopdashDebugLog
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * The rider's grant for sync to use their Drive app data folder (dev-docs/drive-sync.md *What Drive
 * offers*), through Play services' `AuthorizationClient`: the `drive.appdata` scope, which reaches that
 * folder and nothing else of their Drive. StopDash never learns who the rider is, only that it may use
 * the folder. Failures are logged by their kind alone: never a token.
 */
internal object DriveAuthorization {
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    /** What asking for the grant came to. */
    sealed interface Answer {
        /** Granted: the access token for this sync's requests. */
        data class Granted(val token: String) : Answer

        /** Only Google's own screen can grant it: launched by the caller, its result read by [granted]. */
        data class NeedsScreen(val intent: PendingIntent) : Answer

        /** Couldn't be asked: no Play services, or Google refused. */
        data object Failed : Answer
    }

    private val request: AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Asks for the grant: at once where the rider already gave it, else by Google's screen. */
    suspend fun ask(context: Context): Answer = try {
        answerOf(Identity.getAuthorizationClient(context.applicationContext).authorize(request).await())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        // By its type, status and first frames alone: its message is Google's, and never needed.
        StopdashDebugLog.warning("sync: grant not asked: %s", describe(e))
        Answer.Failed
    }

    /** A token for a sync running without the screen; null when only the screen can grant one. */
    suspend fun token(context: Context): String? = (ask(context) as? Answer.Granted)?.token

    /** The grant as Google's screen returned it ([data], its result), or [Answer.Failed] where refused. */
    fun granted(context: Context, data: Intent?): Answer = try {
        answerOf(Identity.getAuthorizationClient(context.applicationContext).getAuthorizationResultFromIntent(data))
    } catch (e: ApiException) {
        StopdashDebugLog.warning("sync: grant refused: %s", describe(e))
        Answer.Failed
    }

    private fun describe(e: ApiException): String = "${e.javaClass.simpleName} status ${e.statusCode}${framesOf(e)}"

    private fun answerOf(result: AuthorizationResult): Answer {
        val intent = result.pendingIntent
        if (result.hasResolution() && intent != null) return Answer.NeedsScreen(intent)
        val token = result.accessToken ?: return Answer.Failed.also { StopdashDebugLog.warning("sync: granted without a token") }
        return Answer.Granted(token)
    }
}
