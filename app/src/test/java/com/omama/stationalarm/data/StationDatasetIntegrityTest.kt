package com.omama.stationalarm.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Guards the shipped app/src/main/assets/stations.json (parsed straight from the file, no Android needed).
 * Station codes end up inside geofence request IDs (`geofence_<id>_<layer>`), so a stray '_' or blank code
 * would silently disable alarms; coordinates of (0,0) would put an alarm in the Atlantic.
 */
class StationDatasetIntegrityTest {

    companion object {
        private lateinit var stations: List<Station>
        private lateinit var index: StationIndex

        @JvmStatic
        @BeforeClass
        fun load() {
            val file = listOf("src/main/assets/stations.json", "app/src/main/assets/stations.json")
                .map(::File).firstOrNull { it.isFile }
            assertNotNull("stations.json not found from ${File(".").absolutePath}", file)
            stations = file!!.reader(Charsets.UTF_8).use {
                Gson().fromJson(it, object : TypeToken<List<Station>>() {}.type)
            }
            index = StationIndex(stations)
        }
    }

    @Test
    fun hasThousandsOfStations() = assertTrue("only ${stations.size}", stations.size > 8000)

    @Test
    fun everyCodeIsUniqueNonBlankAlphanumericAndGeofenceSafe() {
        val bad = stations.filter { !Regex("[A-Za-z0-9]{1,6}").matches(it.id) }
        assertTrue("bad codes: ${bad.take(10)}", bad.isEmpty())
        val dup = stations.groupBy { it.id.lowercase() }.filterValues { it.size > 1 }.keys
        assertTrue("case-insensitive duplicate codes: ${dup.take(10)}", dup.isEmpty())
    }

    @Test
    fun everyNameIsPresentAndTrimmed() {
        val bad = stations.filter { it.name.isBlank() || it.name != it.name.trim() }
        assertTrue("bad names: ${bad.take(10)}", bad.isEmpty())
    }

    @Test
    fun coordinatesAreFiniteInsideIndiaAndNeverNullIsland() {
        val bad = stations.filter {
            it.lat.isNaN() || it.lon.isNaN() || (it.lat == 0.0 && it.lon == 0.0) ||
                it.lat !in 6.0..37.5 || it.lon !in 68.0..98.0
        }
        assertTrue("suspicious coordinates: ${bad.take(10)}", bad.isEmpty())
    }

    @Test
    fun searchingAStationsOwnCodeReturnsThatStationFirst() {
        for (st in stations.filterIndexed { i, _ -> i % 5 == 0 }) {
            val first = index.search(st.id).firstOrNull()
            assertEquals("code ${st.id}", st.id, first?.id)
            assertEquals("lower-case code ${st.id}", st.id, index.search(st.id.lowercase()).firstOrNull()?.id)
        }
    }

    @Test
    fun searchingAnExactNameFindsThatStationWithinTheDefaultLimit() {
        for (st in stations.filterIndexed { i, _ -> i % 11 == 0 }) {
            assertTrue("name ${st.name}", index.search(st.name).any { it.id == st.id })
        }
    }

    @Test
    fun landmarkStationsResolveByCodeAndName() {
        fun first(q: String) = index.search(q).first()
        assertEquals("NEW DELHI", first("ndls").name)
        assertEquals("NEW DELHI", first("new delhi").name)
        assertEquals("MUMBAI CENTRAL", first("mmct").name)
        assertEquals("HOWRAH JN", first("hwh").name)
        assertEquals("PUNE JN", first("pune").name)
        // Verified on a device: the offline lookup resolves LONAVALA to LNL at 18.7492 / 73.408.
        val lnl = first("lonavala")
        assertEquals("LNL", lnl.id)
        assertEquals(18.7492, lnl.lat, 1e-6)
        assertEquals(73.408, lnl.lon, 1e-6)
    }

    @Test
    fun delhiQueryKeepsNewDelhiInsideTheEightRowsTheMapScreenShows() {
        val top8 = index.search("delhi").take(8).map { it.id }
        assertTrue("NDLS missing from $top8", "NDLS" in top8)
    }
}
