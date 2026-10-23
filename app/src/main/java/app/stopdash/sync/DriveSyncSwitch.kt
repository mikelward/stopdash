package app.stopdash.sync

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.stopdash.BuildConfig
import kotlinx.coroutines.launch

/** Drive sync's Settings switch as the screen shows it ([view], null until read) and its tap ([onChange]). */
internal class DriveSyncSwitch(val view: DriveSyncView?, val onChange: (Boolean) -> Unit)

/**
 * Drive sync's Settings switch, or null where it isn't offered: every release build until the Play Data
 * Safety form says so ([BuildConfig.DRIVE_SYNC]). On asks Google for the grant, through its own screen
 * where only that can give it; backing out of that screen leaves sync off. Off stops it on this phone.
 */
@Composable
internal fun rememberDriveSyncSwitch(): DriveSyncSwitch? {
    if (!BuildConfig.DRIVE_SYNC) return null
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { DriveSync.load(context) }
    val view by DriveSync.view.collectAsState()
    val screen = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) scope.launch { DriveSync.granted(context, result.data) }
    }
    return DriveSyncSwitch(view) { on ->
        scope.launch {
            if (!on) {
                DriveSync.turnOff(context)
            } else {
                val answer = DriveSync.turnOn(context)
                if (answer is DriveAuthorization.Answer.NeedsScreen) screen.launch(IntentSenderRequest.Builder(answer.intent).build())
            }
        }
    }
}
