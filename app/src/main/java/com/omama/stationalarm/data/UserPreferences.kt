package com.omama.stationalarm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
 */
object UserPreferences {

    private lateinit var dataStore: DataStore<Preferences>

    private val Context.prefsDataStore by preferencesDataStore(name = "user_prefs")

    // ── Keys ────────────────────────────────────────────────────────────────
    private val KEY_THEME = stringPreferencesKey("theme_mode")
    private val KEY_DISTANCE_UNIT = stringPreferencesKey("distance_unit")
    private val KEY_ALARM_SOUND_URI = stringPreferencesKey("custom_alarm_sound_uri")
    private val KEY_RING_SPEAKER_WITH_HEADPHONES = booleanPreferencesKey("ring_speaker_with_headphones")

    // ── Defaults ────────────────────────────────────────────────────────────
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val UNIT_KM = "km"
    const val UNIT_MILES = "miles"

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

    private suspend inline fun runSafely(op: String, crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            android.util.Log.e("UserPreferences", "$op failed", e)
        }
    }
}
