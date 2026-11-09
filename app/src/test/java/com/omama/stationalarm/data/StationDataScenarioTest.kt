package com.omama.stationalarm.data

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * StationData against the real packaged stations.json (Robolectric serves the merged assets).
 * StationData is a process-wide singleton, so every test resets it through reflection and leaves it loaded.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class StationDataScenarioTest {

    private lateinit var context: Context

    private class NoAssetsContext(base: Context) : ContextWrapper(base) {
        override fun getAssets(): AssetManager = throw IOException("assets unavailable")
    }

    private fun resetSingleton() {
        for (name in listOf("stations", "index")) {
            StationData::class.java.getDeclaredField(name).apply { isAccessible = true }.set(StationData, null)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetSingleton()
    }

    @After
    fun tearDown() {
        resetSingleton()
        StationData.initialize(context) // leave real data loaded for any test that runs next
    }

    @Test
    fun beforeInitialize_searchAndLookupAreEmptyNotCrashing() {
        assertTrue(StationData.searchStations("ndls").isEmpty())
        assertNull(StationData.getStationById("NDLS"))
    }

    @Test
    fun initialize_loadsTheShippedList() {
        StationData.initialize(context)
        assertEquals("NEW DELHI", StationData.searchStations("ndls").first().name)
    }

    @Test
    fun lonavala_resolvesToLnlWithTheVerifiedCoordinates() {
        StationData.initialize(context)
        val hit = StationData.searchStations("lonavala").single()
        assertEquals("LNL", hit.id)
        assertEquals(18.7492, hit.lat, 1e-9)
        assertEquals(73.408, hit.lon, 1e-9)
    }

    @Test
    fun getStationById_isCaseInsensitive_andUnknownIdsAreNull() {
        StationData.initialize(context)
        for (id in listOf("LNL", "lnl", "Lnl")) assertEquals("LONAVALA", StationData.getStationById(id)?.name)
        assertNull(StationData.getStationById("NOPE9"))
        assertNull(StationData.getStationById(""))
        assertNull(StationData.getStationById("liq-481312177"))   // geocoder ids are never railway codes
    }

    @Test
    fun getStationById_agreesWithSearch_forASpreadOfStations() {
        StationData.initialize(context)
        val sample = StationData.searchStations("junction") + StationData.searchStations("jn") + StationData.searchStations("road")
        assertTrue(sample.size > 10)
        for (st in sample) assertEquals(st, StationData.getStationById(st.id.lowercase()))
    }

    @Test
    fun blankQueries_returnNothing_andPaddingIsTrimmed() {
        StationData.initialize(context)
        assertTrue(StationData.searchStations("").isEmpty())
        assertTrue(StationData.searchStations("   ").isEmpty())
        assertTrue(StationData.searchStations("\t\n").isEmpty())
        assertEquals("LNL", StationData.searchStations("  LonaVala ").single().id)
    }

    @Test
    fun searchIsBoundedByTheIndexLimit() {
        StationData.initialize(context)
        assertTrue(StationData.searchStations("a").size <= StationIndex.DEFAULT_LIMIT)
    }

    @Test
    fun secondInitialize_doesNotReloadOrWipeTheData() {
        StationData.initialize(context)
        StationData.initialize(NoAssetsContext(context))        // would blank the list if it reloaded
        assertNotNull(StationData.getStationById("NDLS"))
    }

    @Test
    fun missingAssets_degradeToAnEmptyListWithoutThrowing() {
        StationData.initialize(NoAssetsContext(context))
        assertTrue(StationData.searchStations("ndls").isEmpty())
        assertNull(StationData.getStationById("NDLS"))
    }
}
