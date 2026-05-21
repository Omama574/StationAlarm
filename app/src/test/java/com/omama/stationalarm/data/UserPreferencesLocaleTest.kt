package com.omama.stationalarm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Locale preference round-trip. The picker in Settings depends on this exact
 * default behavior (returns LOCALE_SYSTEM on fresh install so the app follows
 * device locale by default).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class UserPreferencesLocaleTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Wipe DataStore between tests so a previous run's writes don't leak.
        context.filesDir.resolve("datastore").deleteRecursively()
        UserPreferences.initialize(context)
    }

    @Test
    fun appLocale_defaultsToSystem_onFreshInstall() = runTest {
        val first = UserPreferences.appLocaleFlow.first()
        assertEquals(UserPreferences.LOCALE_SYSTEM, first)
    }

    @Test
    fun setAppLocale_writesAndReadsBack() = runTest {
        UserPreferences.setAppLocale("hi")
        assertEquals("hi", UserPreferences.appLocaleFlow.first())

        UserPreferences.setAppLocale("fr")
        assertEquals("fr", UserPreferences.appLocaleFlow.first())
    }

    @Test
    fun setAppLocale_systemSentinelClearsOverride() = runTest {
        UserPreferences.setAppLocale("hi")
        assertEquals("hi", UserPreferences.appLocaleFlow.first())

        UserPreferences.setAppLocale(UserPreferences.LOCALE_SYSTEM)
        assertEquals(UserPreferences.LOCALE_SYSTEM, UserPreferences.appLocaleFlow.first())
    }

    @Test
    fun setAppLocale_bcp47TagsRoundTrip() = runTest {
        // Region tags (en-US, pt-BR) are valid BCP 47 and must survive the round trip.
        UserPreferences.setAppLocale("en-US")
        assertEquals("en-US", UserPreferences.appLocaleFlow.first())

        UserPreferences.setAppLocale("pt-BR")
        assertEquals("pt-BR", UserPreferences.appLocaleFlow.first())
    }
}
