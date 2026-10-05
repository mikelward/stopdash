package app.stopdash.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.stopdash.StopdashDebugLog
import app.stopdash.domain.AppSettings
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.DEFAULT_FONT_SCALE
import app.stopdash.domain.DistanceUnits
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
import app.stopdash.domain.WalkingSpeed
import app.stopdash.domain.FontSizeSettings
import app.stopdash.domain.clampFontScale
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The DataStore-backed [AppSettings] (mirrors [DataStoreStarredRowsStore]). DataStore serializes
 * reads and writes to one file and survives process death, and its [DataStore.data] flow re-emits
 * on every write — so a Settings screen collecting a setting reflects a toggle at once and a later
 * launch reads it back.
 *
 * A setting is not irreplaceable user work, so — unlike the starred rows — a corrupt or
 * newer-schema file is discarded and read as the defaults rather than preserved. The store adds
 * no off-device channel of its own: it is a private app file, riding Android backup /
 * device-to-device transfer like the rest of the app's data (SPEC §12 / *Privacy*).
 */
class DataStoreAppSettings internal constructor(
    private val dataStore: DataStore<PersistedSettings?>,
) : AppSettings {

    override fun liveWidgetRefresh(): Flow<Boolean> =
        persisted().map { it?.liveWidgetRefresh ?: DEFAULT_LIVE_WIDGET_REFRESH }

    override suspend fun setLiveWidgetRefresh(enabled: Boolean) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(liveWidgetRefresh = enabled) }
    }

    override fun fontSize(): Flow<FontSizeSettings> =
        persisted().map {
            // Clamped on read, so a value written by a build with a wider range can never size a
            // screen past what this one lays out.
            FontSizeSettings(
                scale = clampFontScale(it?.fontScale ?: DEFAULT_FONT_SCALE),
                pinchEnabled = it?.pinchEnabled ?: DEFAULT_PINCH_ENABLED,
            )
        }

    override suspend fun setFontScale(scale: Float) {
        val clamped = clampFontScale(scale)
        dataStore.updateData { (it ?: PersistedSettings()).copy(fontScale = clamped) }
    }

    override suspend fun setPinchEnabled(enabled: Boolean) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(pinchEnabled = enabled) }
    }

    // An opt-out counts only for the disclosure it was given against: one saved before the report
    // grew (an older version, or none) reads as "ask again", so the user sees what it now carries.
    override fun skipBugReportConsent(): Flow<Boolean> =
        persisted().map {
            it != null && it.skipBugReportConsent &&
                it.skipBugReportConsentVersion >= BUG_REPORT_CONSENT_VERSION
        }

    override suspend fun setSkipBugReportConsent(enabled: Boolean) {
        dataStore.updateData {
            (it ?: PersistedSettings()).copy(
                skipBugReportConsent = enabled,
                skipBugReportConsentVersion = BUG_REPORT_CONSENT_VERSION,
            )
        }
    }

    override fun journeyTipDismissed(): Flow<Boolean> =
        persisted().map { it?.journeyTipDismissed ?: false }

    override suspend fun setJourneyTipDismissed(dismissed: Boolean) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(journeyTipDismissed = dismissed) }
    }

    override fun watchInstallCardDismissed(): Flow<Boolean> =
        persisted().map { it?.watchInstallCardDismissed ?: false }

    override suspend fun setWatchInstallCardDismissed(dismissed: Boolean) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(watchInstallCardDismissed = dismissed) }
    }

    // Blank is normalized to null on read too, so a stored empty string (from an older build or a
    // hand-edited file) reads as keyless rather than sending an empty app_key that TfL rejects.
    override fun userApiKey(): Flow<String?> =
        persisted().map { it?.userApiKey?.takeIf(String::isNotBlank) }

    override suspend fun setUserApiKey(key: String?) {
        val normalized = key?.trim()?.takeIf(String::isNotEmpty)
        dataStore.updateData { (it ?: PersistedSettings()).copy(userApiKey = normalized) }
    }

    override fun railApiKey(): Flow<String?> =
        persisted().map { it?.railApiKey?.takeIf(String::isNotBlank) }

    override suspend fun setRailApiKey(key: String?) {
        val normalized = key?.trim()?.takeIf(String::isNotEmpty)
        dataStore.updateData { (it ?: PersistedSettings()).copy(railApiKey = normalized) }
    }

    override fun hiddenModes(): Flow<Set<String>> =
        persisted().map { it?.hiddenModes.orEmpty() }

    override suspend fun setHiddenModes(modes: Set<String>) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(hiddenModes = modes) }
    }

    override fun showDisruptionsRow(): Flow<Boolean> =
        persisted().map { it?.showDisruptionsRow ?: true }

    override suspend fun setShowDisruptionsRow(shown: Boolean) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(showDisruptionsRow = shown) }
    }

    override fun summaryNetworks(): Flow<Set<String>?> =
        persisted().map { it?.summaryNetworks }

    override suspend fun setSummaryNetworks(networks: Set<String>) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(summaryNetworks = networks) }
    }

    override fun distanceUnits(): Flow<DistanceUnits> =
        persisted().map { DistanceUnits.fromStored(it?.distanceUnits) }

    override suspend fun setDistanceUnits(units: DistanceUnits) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(distanceUnits = units.name) }
    }

    override fun walkingSpeed(): Flow<WalkingSpeed> =
        persisted().map { WalkingSpeed.fromStored(it?.walkingSpeed) }

    override suspend fun setWalkingSpeed(speed: WalkingSpeed) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(walkingSpeed = speed.name) }
    }

    override fun maxWalk(): Flow<MaxWalk> =
        persisted().map { MaxWalk.fromStored(it?.maxWalk) }

    override suspend fun setMaxWalk(maxWalk: MaxWalk) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(maxWalk = maxWalk.name) }
    }

    override fun stepFree(): Flow<StepFree> =
        persisted().map { StepFree.fromStored(it?.stepFree) }

    override suspend fun setStepFree(stepFree: StepFree) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(stepFree = stepFree.name) }
    }

    override fun tripModes(): Flow<TripModes> =
        persisted().map { TripModes.fromStored(it?.tripModesOff) }

    override suspend fun setTripModes(modes: TripModes) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(tripModesOff = modes.off) }
    }

    override fun avoidedLines(): Flow<Set<String>> =
        persisted().map { AvoidedLines.fromStored(it?.avoidedLines.orEmpty()) }

    override suspend fun setAvoidedLines(lines: Set<String>) {
        dataStore.updateData { (it ?: PersistedSettings()).copy(avoidedLines = lines) }
    }

    // The shared read flow: DataStore's `data`, with a transient I/O read failure retried rather
    // than collapsed to a terminal default. A `catch`-and-emit would end the flow, leaving a
    // long-lived collector stuck at the default after storage recovered (Codex P2 on #56).
    // retryWhen keeps the flow alive and recovers when the read succeeds; a non-IO cause rethrows.
    private fun persisted(): Flow<PersistedSettings?> =
        dataStore.data.retryWhen { cause, _ ->
            if (cause is IOException) {
                logAppSettingsWarning("settings read failed, retrying: ${cause::class.simpleName}")
                delay(SETTINGS_READ_RETRY_MILLIS)
                true
            } else {
                false
            }
        }

    companion object {
        /** The documented default, used before anything is saved and after a discard. */
        const val DEFAULT_LIVE_WIDGET_REFRESH = false

        /** Whether a pinch may resize text before the user has chosen otherwise. */
        const val DEFAULT_PINCH_ENABLED = true

        /** Consent is asked every time until the user opts out — the report carries the location. */
        const val DEFAULT_SKIP_BUG_REPORT_CONSENT = false

        /**
         * The version of what the bug report discloses. Bump it whenever the report carries more,
         * so a saved "don't ask again" is set aside and the consent screen shows the new payload.
         * 2: recent positions (the last 15 minutes) were added (2026-09-25).
         */
        const val BUG_REPORT_CONSENT_VERSION = 2

        /** Backoff between retries of a transient settings read, so [liveWidgetRefresh]'s
         *  retryWhen doesn't hot-loop while storage is briefly unavailable. */
        private const val SETTINGS_READ_RETRY_MILLIS = 1_000L

        /** The file name DataStore owns under the app's files dir. */
        private const val FILE_NAME = "app-settings.json"

        @Volatile
        private var instance: DataStoreAppSettings? = null

        /**
         * The process-wide store. DataStore permits only **one** active instance per file per
         * process (a second throws), so the [DataStore] is created once here and shared. Built
         * from the application context so it outlives any one Activity.
         *
         * [warn] is the sanitized log seam (no-op until the shared on-device logger lands): a
         * corrupt or truncated file is logged and then discarded rather than swallowed. Only the
         * first caller's [warn] is used (process singleton).
         */
        fun from(context: Context, warn: (String) -> Unit = {}): DataStoreAppSettings =
            instance ?: synchronized(this) {
                instance ?: DataStoreAppSettings(
                    DataStoreFactory.create(
                        serializer = SettingsSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            warn("app settings file was unreadable and has been discarded")
                            null
                        },
                    ) {
                        context.applicationContext.dataStoreFile(FILE_NAME)
                    },
                ).also { instance = it }
            }
    }
}

/**
 * Sanitized log sink for a discarded corrupt/unreadable settings file. A fixed reason only —
 * never a setting value (SPEC *Privacy* / *Error handling*). Wired into [DataStoreAppSettings.from]
 * by the production callers so a silent reset-to-defaults leaves a diagnostic (Codex P2 on #56).
 * A top-level function so every caller passes the same sink (the singleton keeps the first).
 */
internal fun logAppSettingsWarning(message: String) = StopdashDebugLog.warning("settings: %s", message)

/**
 * The persisted settings shape. Every field carries a default so a file written by an older
 * build (missing a field) reads back complete, and `ignoreUnknownKeys` lets a newer build's
 * extra fields parse rather than corrupt.
 */
@Serializable
data class PersistedSettings(
    val liveWidgetRefresh: Boolean = DataStoreAppSettings.DEFAULT_LIVE_WIDGET_REFRESH,
    val fontScale: Float = DEFAULT_FONT_SCALE,
    val pinchEnabled: Boolean = DataStoreAppSettings.DEFAULT_PINCH_ENABLED,
    val skipBugReportConsent: Boolean = DataStoreAppSettings.DEFAULT_SKIP_BUG_REPORT_CONSENT,
    // The consent version that opt-out was given against; absent (0) in a file from before versions.
    val skipBugReportConsentVersion: Int = 0,
    // Whether the route page's journey tip was dismissed. Defaulted, so an older file reads it unseen.
    val journeyTipDismissed: Boolean = false,
    // Whether the near-me "StopDash for your watch" card was dismissed. Defaulted, like the tip's.
    val watchInstallCardDismissed: Boolean = false,
    // The user's own TfL app_key (SPEC D7), or null when keyless. The one persisted setting that is
    // a credential; it is sent only with the user's own TfL requests (its purpose) and rides Android
    // backup like the rest of their settings, and is never logged or put in any other off-device
    // artifact (SPEC *Privacy*).
    val userApiKey: String? = null,
    // The user's own Rail Data Marketplace key for National Rail departures, or null. A credential,
    // handled like [userApiKey]: sent only with the user's own National Rail requests.
    val railApiKey: String? = null,
    // The transport modes hidden from the near-me list. Defaulted, so an older file hides none.
    val hiddenModes: Set<String> = emptySet(),
    // The distance-units choice by enum name, or null for the default (follow the locale). A string,
    // not the enum, so a value a newer build adds reads back as the default rather than corrupting.
    val distanceUnits: String? = null,
    // Whether the home screen shows its disruptions row; null (an older file) for the default, shown.
    val showDisruptionsRow: Boolean? = null,
    // The networks the disruptions row always covers, by key; null (never chosen) for the default.
    val summaryNetworks: Set<String>? = null,
    // The walking-speed choice by enum name, or null for the Planner's average; a string for the same
    // reason as [distanceUnits].
    val walkingSpeed: String? = null,
    // The max-walk choice by enum name, or null for the default; a string for the same reason as
    // [distanceUnits].
    val maxWalk: String? = null,
    // The step-free choice by enum name, or null for no requirement; a string for the same reason as
    // [distanceUnits].
    val stepFree: String? = null,
    // The mode groups a trip doesn't ride, by [ModeGroups.Group.key]; empty rides everything, so a
    // group added later rides until turned off.
    val tripModesOff: Set<String> = emptySet(),
    // The lines a trip avoids, each as [AvoidedLines.key]. Defaulted, so an older file avoids none.
    val avoidedLines: Set<String> = emptySet(),
)

/**
 * Reads and writes [PersistedSettings] as JSON (mirrors [StarredRowsSerializer]). An empty file
 * is "nothing saved yet" and reads back as null (→ the defaults); a **corrupt** one throws
 * [CorruptionException] so the corruption handler logs it and replaces the file with the
 * defaults — acceptable here because a setting, unlike the user's stars, is not irreplaceable.
 */
internal object SettingsSerializer : Serializer<PersistedSettings?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedSettings? = null

    override suspend fun readFrom(input: InputStream): PersistedSettings? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedSettings.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            throw CorruptionException("app settings could not be decoded")
        }
    }

    override suspend fun writeTo(t: PersistedSettings?, output: OutputStream) {
        if (t == null) return
        output.write(json.encodeToString(PersistedSettings.serializer(), t).encodeToByteArray())
    }
}
