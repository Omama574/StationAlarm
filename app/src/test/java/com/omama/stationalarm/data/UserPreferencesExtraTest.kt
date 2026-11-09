package com.omama.stationalarm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Defaults and persistence for every UserPreferences setting. Complements UserPreferencesTest (alarm duration
 * clamp) and UserPreferencesLocaleTest (locale). The DataStore instance is cached for the whole sandbox, so
 * each test wipes it through the private field instead of trusting a fresh file.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class UserPreferencesExtraTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        UserPreferences.initialize(context)
        runBlocking { store().edit { it.clear() } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun store(): DataStore<Preferences> =
        UserPreferences::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
            .get(UserPreferences) as DataStore<Preferences>

    private fun <T> now(flow: Flow<T>): T = runBlocking { flow.first() }

    // ── defaults ───────────────────────────────────────────────────────────────

    @Test
    fun everySettingHasItsDocumentedDefault() {
        assertEquals("system", now(UserPreferences.themeModeFlow))
        assertEquals("km", now(UserPreferences.distanceUnitFlow))
        assertEquals("", now(UserPreferences.alarmSoundUriFlow))
        assertTrue("speaker + headphones is the default routing", now(UserPreferences.ringSpeakerWithHeadphonesFlow))
        assertTrue(now(UserPreferences.escalatingAlarmFlow))
        assertEquals(30, now(UserPreferences.escalatingAlarmRampSecsFlow))
        assertEquals(180, now(UserPreferences.alarmDurationSecsFlow))
        assertEquals("system", now(UserPreferences.appLocaleFlow))
        assertFalse(now(UserPreferences.batteryOptDismissedFlow))
    }

    @Test
    fun constantsMatchTheirDocumentation() {
        assertEquals(UserPreferences.THEME_SYSTEM, "system")
        assertEquals(UserPreferences.THEME_LIGHT, "light")
        assertEquals(UserPreferences.THEME_DARK, "dark")
        assertEquals(UserPreferences.UNIT_KM, "km")
        assertEquals(UserPreferences.UNIT_MILES, "miles")
        assertEquals(UserPreferences.LOCALE_SYSTEM, "system")
        assertEquals(30, UserPreferences.DEFAULT_RAMP_SECS)
        assertEquals(60, UserPreferences.MIN_ALARM_DURATION_SECS)
        assertEquals(900, UserPreferences.MAX_ALARM_DURATION_SECS)
        assertTrue(UserPreferences.DEFAULT_ALARM_DURATION_SECS in 60..900)
    }

    // ── persistence, one setting at a time ───────────────────────────────────

    @Test
    fun themeMode_roundTripsEveryValue() = runBlocking {
        for (v in listOf(UserPreferences.THEME_LIGHT, UserPreferences.THEME_DARK, UserPreferences.THEME_SYSTEM)) {
            UserPreferences.setThemeMode(v)
            assertEquals(v, UserPreferences.themeModeFlow.first())
        }
    }

    @Test
    fun unknownThemeValue_isStoredVerbatim_consumersTreatItAsSystem() = runBlocking {
        UserPreferences.setThemeMode("neon")
        assertEquals("neon", UserPreferences.themeModeFlow.first())
    }

    @Test
    fun distanceUnit_roundTrips() = runBlocking {
        UserPreferences.setDistanceUnit(UserPreferences.UNIT_MILES)
        assertEquals("miles", UserPreferences.distanceUnitFlow.first())
        UserPreferences.setDistanceUnit(UserPreferences.UNIT_KM)
        assertEquals("km", UserPreferences.distanceUnitFlow.first())
    }

    @Test
    fun alarmSoundUri_roundTrips_andEmptyStringRestoresTheSystemDefault() = runBlocking {
        val uri = "content://media/external/audio/media/42?title=Beep&x=%E0%A4%A6"
        UserPreferences.setAlarmSoundUri(uri)
        assertEquals(uri, UserPreferences.alarmSoundUriFlow.first())
        UserPreferences.setAlarmSoundUri("")
        assertEquals("", UserPreferences.alarmSoundUriFlow.first())
    }

    @Test
    fun ringSpeakerWithHeadphones_toggles_andFalseSurvivesAsAnExplicitValue() = runBlocking {
        UserPreferences.setRingSpeakerWithHeadphones(false)
        assertFalse(UserPreferences.ringSpeakerWithHeadphonesFlow.first())
        UserPreferences.setRingSpeakerWithHeadphones(true)
        assertTrue(UserPreferences.ringSpeakerWithHeadphonesFlow.first())
    }

    @Test
    fun escalatingAlarm_toggles() = runBlocking {
        UserPreferences.setEscalatingAlarm(false)
        assertFalse(UserPreferences.escalatingAlarmFlow.first())
        UserPreferences.setEscalatingAlarm(true)
        assertTrue(UserPreferences.escalatingAlarmFlow.first())
    }

    @Test
    fun rampSecs_roundTripsTheValuesTheSliderCanProduce() = runBlocking {
        for (secs in listOf(15, 20, 30, 45, 60)) {
            UserPreferences.setEscalatingAlarmRampSecs(secs)
            assertEquals(secs, UserPreferences.escalatingAlarmRampSecsFlow.first())
        }
    }

    @Test
    fun alarmDuration_roundTripsEveryWholeMinuteTheSliderCanProduce() = runBlocking {
        for (secs in 60..900 step 60) {
            UserPreferences.setAlarmDurationSecs(secs)
            assertEquals(secs, UserPreferences.alarmDurationSecsFlow.first())
        }
    }

    @Test
    fun alarmDuration_clampEdges() = runBlocking {
        UserPreferences.setAlarmDurationSecs(59); assertEquals(60, UserPreferences.alarmDurationSecsFlow.first())
        UserPreferences.setAlarmDurationSecs(901); assertEquals(900, UserPreferences.alarmDurationSecsFlow.first())
        UserPreferences.setAlarmDurationSecs(Int.MAX_VALUE); assertEquals(900, UserPreferences.alarmDurationSecsFlow.first())
        UserPreferences.setAlarmDurationSecs(Int.MIN_VALUE); assertEquals(60, UserPreferences.alarmDurationSecsFlow.first())
    }

    @Test
    fun batteryOptDismissed_roundTrips() = runBlocking {
        UserPreferences.setBatteryOptDismissed(true)
        assertTrue(UserPreferences.batteryOptDismissedFlow.first())
        UserPreferences.setBatteryOptDismissed(false)
        assertFalse(UserPreferences.batteryOptDismissedFlow.first())
    }

    // ── interaction between settings ─────────────────────────────────────────

    @Test
    fun writingOneSetting_neverDisturbsTheOthers() = runBlocking {
        UserPreferences.setThemeMode("dark")
        UserPreferences.setDistanceUnit("miles")
        UserPreferences.setAlarmSoundUri("content://x/1")
        UserPreferences.setRingSpeakerWithHeadphones(false)
        UserPreferences.setEscalatingAlarm(false)
        UserPreferences.setEscalatingAlarmRampSecs(45)
        UserPreferences.setAlarmDurationSecs(300)
        UserPreferences.setAppLocale("hi")
        UserPreferences.setBatteryOptDismissed(true)

        UserPreferences.setThemeMode("light")           // change one, check the other eight
        assertEquals("light", UserPreferences.themeModeFlow.first())
        assertEquals("miles", UserPreferences.distanceUnitFlow.first())
        assertEquals("content://x/1", UserPreferences.alarmSoundUriFlow.first())
        assertFalse(UserPreferences.ringSpeakerWithHeadphonesFlow.first())
        assertFalse(UserPreferences.escalatingAlarmFlow.first())
        assertEquals(45, UserPreferences.escalatingAlarmRampSecsFlow.first())
        assertEquals(300, UserPreferences.alarmDurationSecsFlow.first())
        assertEquals("hi", UserPreferences.appLocaleFlow.first())
        assertTrue(UserPreferences.batteryOptDismissedFlow.first())
    }

    @Test
    fun concurrentWritesToDifferentKeys_areAllPersisted() = runBlocking {
        listOf(
            async(Dispatchers.Default) { UserPreferences.setThemeMode("dark") },
            async(Dispatchers.Default) { UserPreferences.setDistanceUnit("miles") },
            async(Dispatchers.Default) { UserPreferences.setRingSpeakerWithHeadphones(false) },
            async(Dispatchers.Default) { UserPreferences.setEscalatingAlarmRampSecs(60) },
            async(Dispatchers.Default) { UserPreferences.setAlarmDurationSecs(600) },
            async(Dispatchers.Default) { UserPreferences.setAppLocale("fr") },
        ).awaitAll()
        assertEquals("dark", UserPreferences.themeModeFlow.first())
        assertEquals("miles", UserPreferences.distanceUnitFlow.first())
        assertFalse(UserPreferences.ringSpeakerWithHeadphonesFlow.first())
        assertEquals(60, UserPreferences.escalatingAlarmRampSecsFlow.first())
        assertEquals(600, UserPreferences.alarmDurationSecsFlow.first())
        assertEquals("fr", UserPreferences.appLocaleFlow.first())
    }

    @Test
    fun manyRapidWritesToOneKey_endOnTheLastValue() = runBlocking {
        for (i in 1..50) UserPreferences.setEscalatingAlarmRampSecs(i)
        assertEquals(50, UserPreferences.escalatingAlarmRampSecsFlow.first())
    }

    @Test
    fun aFlowKeepsEmittingAfterLaterWrites() = runBlocking {
        val before = UserPreferences.distanceUnitFlow.first()
        UserPreferences.setDistanceUnit("miles")
        val after = UserPreferences.distanceUnitFlow.first()
        assertEquals("km", before)
        assertEquals("miles", after)
    }

    // ── failure handling ──────────────────────────────────────────────────────

    @Test
    fun aFailingDataStore_makesEverySetterSwallowTheErrorInsteadOfCrashingTheCaller() = runBlocking {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = kotlinx.coroutines.flow.flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        try {
            UserPreferences::class.java.getDeclaredField("dataStore").apply { isAccessible = true }.set(UserPreferences, broken)
            // None of these may throw: they run from rememberCoroutineScope in the UI.
            UserPreferences.setThemeMode("dark")
            UserPreferences.setDistanceUnit("miles")
            UserPreferences.setAlarmSoundUri("content://x")
            UserPreferences.setRingSpeakerWithHeadphones(false)
            UserPreferences.setEscalatingAlarm(false)
            UserPreferences.setEscalatingAlarmRampSecs(20)
            UserPreferences.setAlarmDurationSecs(120)
            UserPreferences.setAppLocale("hi")
            UserPreferences.setBatteryOptDismissed(true)
            // and reads from the same broken store still yield defaults
            assertEquals("system", UserPreferences.themeModeFlow.first())
            assertTrue(UserPreferences.ringSpeakerWithHeadphonesFlow.first())
        } finally {
            UserPreferences.initialize(context) // put the real DataStore back
        }
    }
}
