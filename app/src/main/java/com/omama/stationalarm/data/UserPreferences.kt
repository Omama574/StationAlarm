package com.omama.stationalarm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * App-wide user preferences backed by DataStore. Initialized once in
 * [com.omama.stationalarm.StationAlarmApplication.onCreate].
 *
 * Keys:
 *   - theme_mode            : "system" | "light" | "dark"
 *   - distance_unit         : "km" | "miles"
 *   - custom_alarm_sound_uri: content:// URI of a user-picked ringtone or local audio file
 *                             (empty string = use system default alarm)
 *   - ring_speaker_with_headphones: true keeps the phone speaker active when external audio is connected
 *   - escalating_alarm_enabled   : true ramps MediaPlayer from 0.05→1.0 over ramp_secs (default true)
 *   - escalating_alarm_ramp_secs : ramp duration in seconds, 15–60 (default 30)
 */
object UserPreferences {

    private lateinit var dataStore: DataStore<Preferences>

    private val Context.prefsDataStore by preferencesDataStore(name = "user_prefs")

    // ── Keys ────────────────────────────────────────────────────────────────
    private val KEY_THEME = stringPreferencesKey("theme_mode")
    private val KEY_DISTANCE_UNIT = stringPreferencesKey("distance_unit")
    private val KEY_ALARM_SOUND_URI = stringPreferencesKey("custom_alarm_sound_uri")
    private val KEY_RING_SPEAKER_WITH_HEADPHONES = booleanPreferencesKey("ring_speaker_with_headphones")
    private val KEY_ESCALATING_ALARM = booleanPreferencesKey("escalating_alarm_enabled")
    private val KEY_RAMP_DURATION_SECS = intPreferencesKey("escalating_alarm_ramp_secs")
    private val KEY_BATTERY_OPT_DISMISSED = booleanPreferencesKey("battery_opt_permanently_dismissed")

    // ── Defaults ────────────────────────────────────────────────────────────
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val UNIT_KM = "km"
    const val UNIT_MILES = "miles"

    const val DEFAULT_RAMP_SECS = 30

    fun initialize(context: Context) {
        dataStore = context.applicationContext.prefsDataStore
    }

    // ── Flows ───────────────────────────────────────────────────────────────
    val themeModeFlow: Flow<String>
        get() = dataStore.data.map { it[KEY_THEME] ?: THEME_SYSTEM }

    val distanceUnitFlow: Flow<String>
        get() = dataStore.data.map { it[KEY_DISTANCE_UNIT] ?: UNIT_KM }

    /** Empty string means "use system default alarm sound". */
    val alarmSoundUriFlow: Flow<String>
        get() = dataStore.data.map { it[KEY_ALARM_SOUND_URI] ?: "" }

    val ringSpeakerWithHeadphonesFlow: Flow<Boolean>
        get() = dataStore.data.map { it[KEY_RING_SPEAKER_WITH_HEADPHONES] ?: true }

    val escalatingAlarmFlow: Flow<Boolean>
        get() = dataStore.data.map { it[KEY_ESCALATING_ALARM] ?: true }

    val escalatingAlarmRampSecsFlow: Flow<Int>
        get() = dataStore.data.map { it[KEY_RAMP_DURATION_SECS] ?: DEFAULT_RAMP_SECS }

    /** True if the user tapped "Don't ask again" on the battery sheet. Once
     *  set, the post-confirm sheet stays suppressed; the Settings > Background
     *  Running row remains the user-driven entry point to grant the exemption. */
    val batteryOptDismissedFlow: Flow<Boolean>
        get() = dataStore.data.map { it[KEY_BATTERY_OPT_DISMISSED] ?: false }

    // ── Setters ─────────────────────────────────────────────────────────────
    // DataStore writes can throw IOException on disk-full / corrupted preferences.
    // Callers launch these from rememberCoroutineScope, where an unhandled throw
    // would crash the recomposition that started it. Swallow + log instead — a
    // failed pref write is never worth a crash.
    suspend fun setThemeMode(mode: String) {
        runSafely("setThemeMode") { dataStore.edit { it[KEY_THEME] = mode } }
    }

    suspend fun setDistanceUnit(unit: String) {
        runSafely("setDistanceUnit") { dataStore.edit { it[KEY_DISTANCE_UNIT] = unit } }
    }

    suspend fun setAlarmSoundUri(uri: String) {
        runSafely("setAlarmSoundUri") { dataStore.edit { it[KEY_ALARM_SOUND_URI] = uri } }
    }

    suspend fun setRingSpeakerWithHeadphones(enabled: Boolean) {
        runSafely("setRingSpeakerWithHeadphones") {
            dataStore.edit { it[KEY_RING_SPEAKER_WITH_HEADPHONES] = enabled }
        }
    }

    suspend fun setEscalatingAlarm(enabled: Boolean) {
        runSafely("setEscalatingAlarm") { dataStore.edit { it[KEY_ESCALATING_ALARM] = enabled } }
    }

    suspend fun setEscalatingAlarmRampSecs(secs: Int) {
        runSafely("setEscalatingAlarmRampSecs") { dataStore.edit { it[KEY_RAMP_DURATION_SECS] = secs } }
    }

    suspend fun setBatteryOptDismissed(dismissed: Boolean) {
        runSafely("setBatteryOptDismissed") { dataStore.edit { it[KEY_BATTERY_OPT_DISMISSED] = dismissed } }
    }

    private suspend inline fun runSafely(op: String, crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            android.util.Log.e("UserPreferences", "$op failed", e)
        }
    }
}
