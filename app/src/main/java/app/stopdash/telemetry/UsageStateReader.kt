package app.stopdash.telemetry

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.stopdash.StopdashDebugLog
import app.stopdash.data.AvoidedLinesSetting
import app.stopdash.data.DataStoreAppSettings
import app.stopdash.data.DataStoreFavoriteJourneysStore
import app.stopdash.data.DataStoreFavoritePlacesStore
import app.stopdash.data.DataStoreStarredRowsStore
import app.stopdash.data.DisruptionsRowSetting
import app.stopdash.data.DistanceUnitsSetting
import app.stopdash.data.HiddenModesSetting
import app.stopdash.data.MaxWalkSetting
import app.stopdash.data.RailApiKeySetting
import app.stopdash.data.StepFreeSetting
import app.stopdash.data.TripModesSetting
import app.stopdash.data.UserApiKeySetting
import app.stopdash.data.WalkingSpeedSetting
import app.stopdash.data.logAppSettingsWarning
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.StarredRowSet
import app.stopdash.domain.UsageEvent
import app.stopdash.domain.UsageState
import app.stopdash.ui.FontSizeSetting
import app.stopdash.watch.DataLayerWatchNodes
import app.stopdash.watch.WatchNodes
import app.stopdash.widget.StopDashWidgetReceiver
import app.stopdash.widget.liveWidgetRefreshNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads the rider's [UsageState] for [UsageProperties]. Disk and Play services, so it's only ever run
 * on the publisher's worker ([UsagePropertiesPublisher]). The settings are read as the app holds them
 * now (a change applied at once, ahead of its save), once each has been read from storage; one that
 * hasn't in time fails the read, so a default is never sent for the rider's own choice. The rest are
 * each bounded, and one that can't be read in time is "unknown".
 */
internal class UsageStateReader(
    context: Context,
    private val watches: WatchNodes = DataLayerWatchNodes(context),
) {
    private val context = context.applicationContext

    suspend fun read(): UsageState {
        val held = listOf(
            WalkingSpeedSetting.isLoaded, MaxWalkSetting.isLoaded, StepFreeSetting.isLoaded, TripModesSetting.isLoaded,
            AvoidedLinesSetting.isLoaded, HiddenModesSetting.isLoaded, DistanceUnitsSetting.isLoaded,
            DisruptionsRowSetting.isLoaded, UserApiKeySetting.isLoaded, RailApiKeySetting.isLoaded,
        )
        check(allLoaded(held)) { "settings not read" }
        val settings = DataStoreAppSettings.from(context, warn = ::logAppSettingsWarning)
        // The size the app is drawn at, a change that didn't save included; else the stored one.
        val fontSize = FontSizeSetting.inForce()
            ?: checkNotNull(bounded("text size") { settings.fontSize().first() }) { "text size not read" }
        val liveWidget = checkNotNull(settings.liveWidgetRefreshNow()) { "live widget not read" }
        return UsageState(
            walkingSpeed = WalkingSpeedSetting.changes.value,
            maxWalk = MaxWalkSetting.changes.value,
            stepFree = StepFreeSetting.changes.value,
            tripModes = TripModesSetting.changes.value,
            avoidedLines = AvoidedLinesSetting.changes.value.size,
            // The rider's own hides: National Rail's keyless default follows from [railKey].
            hiddenModes = HiddenModesSetting.chosenNow,
            distanceUnits = DistanceUnitsSetting.changes.value,
            disruptionsRow = DisruptionsRowSetting.changes.value,
            liveWidget = liveWidget,
            textSize = fontSize.scale,
            pinchToResize = fontSize.pinchEnabled,
            // Whether there's a key, never the key.
            tflKey = UserApiKeySetting.current != null,
            railKey = RailApiKeySetting.current != null,
            widgets = orUnknown("widgets") {
                AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, StopDashWidgetReceiver::class.java)).size
            },
            watch = orUnknown("watch") { bounded("watch") { watch() } },
            starredRows = orUnknown("starred rows") {
                bounded("starred rows") { (DataStoreStarredRowsStore.from(context, warn = ::warn).starred().first() as? StarredRowSet.Loaded)?.starred?.size }
            },
            favoritePlaces = orUnknown("favorite places") {
                bounded("favorite places") { (DataStoreFavoritePlacesStore.from(context, warn = ::warn).places().first() as? FavoritePlacesSet.Loaded)?.places?.size }
            },
            favoriteJourneys = orUnknown("favorite journeys") {
                bounded("favorite journeys") {
                    // Both lists from one read, so a save swapping a grayed copy for the journey isn't miscounted.
                    val saved = DataStoreFavoriteJourneysStore.from(context, warn = ::warn).savedJourneys().first()
                    favoriteJourneyCount(saved.journeys, saved.pending)
                }
            },
            notifications = orUnknown("notifications") { NotificationManagerCompat.from(context).areNotificationsEnabled() },
            location = orUnknown("location") {
                UsageEvent.Grant.of(
                    fine = granted(Manifest.permission.ACCESS_FINE_LOCATION),
                    coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            },
        )
    }

    // A watch with StopDash wins over one connected without it; one without it is seen only while connected.
    private suspend fun watch(): UsageState.Watch = when {
        watches.withApp().isNotEmpty() -> UsageState.Watch.WITH_APP
        watches.connected().isNotEmpty() -> UsageState.Watch.WITHOUT_APP
        else -> UsageState.Watch.NONE
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun allLoaded(loaded: List<StateFlow<Boolean>>): Boolean =
        withTimeoutOrNull(READ_TIMEOUT_MILLIS) { loaded.forEach { flow -> flow.first { it } } } != null

    // A store's flow retries a failed read for good, so each read is bounded: null once it's taken too
    // long, said by name in the log.
    private suspend fun <T> bounded(what: String, read: suspend () -> T?): T? {
        var answered = false
        val value = withTimeoutOrNull(READ_TIMEOUT_MILLIS) { read().also { answered = true } }
        if (!answered) warn("$what not read in ${READ_TIMEOUT_MILLIS / 1000} s")
        return value
    }

    // Null ("unknown") for what couldn't be read, said by name in the log, never what was read.
    private suspend fun <T> orUnknown(what: String, read: suspend () -> T?): T? =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("$what not read: ${e::class.simpleName}")
            null
        }

    private fun warn(message: String) = StopdashDebugLog.warning("telemetry: usage properties: %s", message)

    private companion object {
        const val READ_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * The favorite journeys a rider has saved, the grayed ones included (maintainer, 2026-10-10: saved from
 * the same Add, so a rider with only those has favorites too). Null when either list couldn't be read,
 * so the property says unknown rather than undercount.
 */
internal fun favoriteJourneyCount(journeys: List<*>?, pending: List<*>?): Int? =
    if (journeys == null || pending == null) null else journeys.size + pending.size
