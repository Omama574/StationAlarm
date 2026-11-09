package com.omama.stationalarm.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Additional in-memory Room coverage for [ActiveStationDao]: REPLACE semantics, no-op updates, column
 * isolation, odd values, Flow emission ordering and concurrent writers. Complements ActiveStationDaoTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ActiveStationDaoExtendedTest {

    private lateinit var db: StationDatabase
    private lateinit var dao: ActiveStationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StationDatabase::class.java).allowMainThreadQueries().build()
        dao = db.activeStationDao()
    }

    @After
    fun tearDown() = db.close()

    private fun e(
        id: String,
        status: String = "MONITORING",
        radius: Double = 3.0,
        name: String = "Station $id",
        lat: Double = 12.97,
        lon: Double = 77.59,
        reminder: String? = null,
        last: Long? = null,
    ) = ActiveStationEntity(id, radius, true, true, true, reminder, false, status, lat, lon, name, last)

    // ── REPLACE / no-op semantics ────────────────────────────────────────────

    @Test
    fun insert_withSameId_replacesTheWholeRow() = runBlocking {
        dao.insert(e("A", radius = 3.0, name = "Old", last = 99L))
        dao.insert(e("A", radius = 9.0, name = "New", status = "PAUSED"))

        val all = dao.getAllActiveStationsList()
        assertEquals(1, all.size)
        assertEquals(9.0, all[0].alertDistanceKm, 0.0)
        assertEquals("New", all[0].stationName)
        assertEquals("PAUSED", all[0].status)
        assertNull("REPLACE rewrites every column, including lastTriggeredAt", all[0].lastTriggeredAt)
    }

    @Test
    fun operationsOnMissingRows_areSilentNoOps() = runBlocking {
        dao.delete("ghost")
        dao.updateStatus("ghost", "PAUSED")
        dao.markAlertingFromMonitoring("ghost")
        dao.updateStationName("ghost", "x")
        dao.setLastTriggeredAt("ghost", 1L)
        dao.updateSettings("ghost", 4.0, false, false, false, "r", true)

        assertNull(dao.getStatus("ghost"))
        assertNull(dao.getStationById("ghost"))
        assertTrue(dao.getAllActiveStationsList().isEmpty())
    }

    @Test
    fun resetAllAlertingToMonitoring_returnsZeroWhenNothingIsAlerting_andIsIdempotent() = runBlocking {
        dao.insert(e("A", status = "MONITORING"))
        dao.insert(e("B", status = "PAUSED"))
        assertEquals(0, dao.resetAllAlertingToMonitoring())

        dao.updateStatus("A", "ALERTING")
        assertEquals(1, dao.resetAllAlertingToMonitoring())
        assertEquals(0, dao.resetAllAlertingToMonitoring())
    }

    @Test
    fun updateStatus_acceptsAnyStringAndDoesNotValidateTheStateMachine() = runBlocking {
        // Documents that transitions are enforced by callers/queries, not by the column.
        dao.insert(e("A"))
        dao.updateStatus("A", "SOMETHING_ELSE")
        assertEquals("SOMETHING_ELSE", dao.getStatus("A"))
    }

    @Test
    fun markAlertingFromMonitoring_leavesAlertingAndPausedRowsAlone_andNeverTouchesLastTriggeredAt() = runBlocking {
        dao.insert(e("ALERT", status = "ALERTING", last = 5L))
        dao.insert(e("MON", status = "MONITORING", last = 6L))

        dao.markAlertingFromMonitoring("ALERT")
        dao.markAlertingFromMonitoring("MON")

        assertEquals(5L, dao.getStationById("ALERT")!!.lastTriggeredAt)
        assertEquals("ALERTING", dao.getStatus("MON"))
        assertEquals(6L, dao.getStationById("MON")!!.lastTriggeredAt)
    }

    @Test
    fun updateSettings_changesOnlyTheSettingsColumnsOfTheTargetRow() = runBlocking {
        dao.insert(e("A", status = "PAUSED", name = "Keep", lat = 1.5, lon = 2.5, last = 77L))
        dao.insert(e("B", radius = 3.0))

        dao.updateSettings("A", 11.0, notify = false, vibrate = false, sound = false, reminder = "wake", sendReminder = true)

        val a = dao.getStationById("A")!!
        assertEquals(11.0, a.alertDistanceKm, 0.0)
        assertEquals(false, a.notify)
        assertEquals(false, a.vibrate)
        assertEquals(false, a.sound)
        assertEquals("wake", a.customReminder)
        assertEquals(true, a.sendReminder)
        assertEquals("PAUSED", a.status)
        assertEquals("Keep", a.stationName)
        assertEquals(1.5, a.lat, 0.0)
        assertEquals(2.5, a.lon, 0.0)
        assertEquals(77L, a.lastTriggeredAt)
        assertEquals(3.0, dao.getStationById("B")!!.alertDistanceKm, 0.0)
    }

    @Test
    fun updateSettings_withNullReminderClearsAnExistingReminder() = runBlocking {
        dao.insert(e("A", reminder = "old"))
        dao.updateSettings("A", 3.0, true, true, true, null, false)
        assertNull(dao.getStationById("A")!!.customReminder)
    }

    // ── odd and boundary values ──────────────────────────────────────────────

    @Test
    fun names_roundTripExactly_forEmptyUnicodeSqlLookalikeAndHugeStrings() = runBlocking {
        val names = listOf(
            "", " ", "  padded  ", "मुंबई सेंट्रल", "🚆 train", "قطار", "O'Brien \"x\"; DROP TABLE active_stations;--",
            "line1\nline2\ttab", "100% _wild_ [card]", "x".repeat(100_000),
        )
        names.forEachIndexed { i, n -> dao.insert(e("id$i", name = n)) }

        names.forEachIndexed { i, n -> assertEquals("name #$i", n, dao.getStationById("id$i")!!.stationName) }
        assertEquals(names.size, dao.getAllActiveStationsList().size) // the "DROP TABLE" text was only data
    }

    @Test
    fun reminder_distinguishesNullFromEmptyString() = runBlocking {
        dao.insert(e("N", reminder = null))
        dao.insert(e("E", reminder = ""))
        assertNull(dao.getStationById("N")!!.customReminder)
        assertEquals("", dao.getStationById("E")!!.customReminder)
    }

    @Test
    fun coordinates_roundTripAtBoundariesAndExtremes() = runBlocking {
        val pairs = listOf(
            90.0 to 180.0, -90.0 to -180.0, 0.0 to 0.0, 1e-7 to -1e-7, 28.642464 to 77.220154,
            Double.MAX_VALUE to -Double.MAX_VALUE, Double.MIN_VALUE to 0.0,
            Double.POSITIVE_INFINITY to Double.NEGATIVE_INFINITY,
        )
        pairs.forEachIndexed { i, (lat, lon) -> dao.insert(e("c$i", lat = lat, lon = lon)) }
        pairs.forEachIndexed { i, (lat, lon) ->
            val r = dao.getStationById("c$i")!!
            assertEquals("lat #$i", lat, r.lat, 0.0)
            assertEquals("lon #$i", lon, r.lon, 0.0)
        }
    }

    @Test
    fun alertDistance_roundTripsFractionalAndSliderBoundaryValues() = runBlocking {
        for ((i, r) in listOf(0.05, 3.0, 20.0, 0.1 + 0.2).withIndex()) {
            dao.insert(e("r$i", radius = r))
            assertEquals(r, dao.getStationById("r$i")!!.alertDistanceKm, 0.0)
        }
    }

    @Test
    fun stationIds_areCaseSensitive_soCaseVariantsAreSeparateRows() = runBlocking {
        // StationData lookups ignore case but the primary key does not; unreachable from the UI (ids come from data).
        dao.insert(e("NDLS"))
        dao.insert(e("ndls"))
        assertEquals(2, dao.getAllActiveStationsList().size)
        assertNotNull(dao.getStationById("ndls"))
    }

    @Test
    fun unicodeAndSpacedIds_areAcceptedByTheDao() = runBlocking {
        for (id in listOf("a b", "ÅÄÖ", "मुंबई", "with'quote")) {
            dao.insert(e(id))
            assertEquals(id, dao.getStationById(id)!!.stationId)
        }
    }

    // ── Flow emission ordering ───────────────────────────────────────────────

    @Test
    fun getAllActiveStations_emitsEachCommittedStateInOrder() = runBlocking {
        val seen = CopyOnWriteArrayList<List<String>>()
        fun snapshot(list: List<ActiveStationEntity>) = list.map { "${it.stationId}:${it.status}" }.sorted()
        val collector = CoroutineScope(Dispatchers.Default).launch {
            dao.getAllActiveStations().collect { seen.add(snapshot(it)) }
        }
        fun await(expected: List<String>) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (seen.lastOrNull() != expected) {
                if (System.nanoTime() > deadline) fail("never saw $expected; saw $seen")
                Thread.sleep(10)
            }
        }
        try {
            await(emptyList())
            dao.insert(e("A")); await(listOf("A:MONITORING"))
            dao.updateStatus("A", "PAUSED"); await(listOf("A:PAUSED"))
            dao.insert(e("B")); await(listOf("A:PAUSED", "B:MONITORING"))
            dao.delete("A"); await(listOf("B:MONITORING"))
            dao.delete("B"); await(emptyList())
        } finally {
            collector.cancel()
        }

        // Collapse consecutive duplicates (Room may re-query), then the order must be exactly the write order.
        val distinct = seen.fold(emptyList<List<String>>()) { acc, s -> if (acc.lastOrNull() == s) acc else acc + listOf(s) }
        assertEquals(
            listOf(
                emptyList(), listOf("A:MONITORING"), listOf("A:PAUSED"),
                listOf("A:PAUSED", "B:MONITORING"), listOf("B:MONITORING"), emptyList(),
            ),
            distinct,
        )
    }

    // ── concurrent writers ───────────────────────────────────────────────────

    @Test
    fun concurrentInserts_ofDistinctRows_allLand() = runBlocking {
        coroutineScope { (1..50).forEach { i -> launch(Dispatchers.IO) { dao.insert(e("s$i")) } } }
        assertEquals(50, dao.getAllActiveStationsList().size)
    }

    @Test
    fun concurrentReplacesOfOneId_leaveOneInternallyConsistentRow() = runBlocking {
        coroutineScope {
            (1..30).forEach { k -> launch(Dispatchers.IO) { dao.insert(e("same", radius = k.toDouble(), name = "N$k")) } }
        }
        val all = dao.getAllActiveStationsList()
        assertEquals(1, all.size)
        // radius and name were written together, so they must still belong to the same writer.
        assertEquals("N${all[0].alertDistanceKm.toInt()}", all[0].stationName)
    }

    @Test
    fun concurrentMarkAlerting_isIdempotent() = runBlocking {
        dao.insert(e("A"))
        coroutineScope { (1..40).forEach { launch(Dispatchers.IO) { dao.markAlertingFromMonitoring("A") } } }
        assertEquals("ALERTING", dao.getStatus("A"))
    }

    @Test
    fun concurrentStatusAndSettingsWrites_doNotClobberEachOthersColumns() = runBlocking {
        dao.insert(e("A", name = "Keep", lat = 4.0, lon = 5.0))
        coroutineScope {
            (1..40).forEach { i ->
                launch(Dispatchers.IO) { dao.updateStatus("A", if (i % 2 == 0) "PAUSED" else "MONITORING") }
                launch(Dispatchers.IO) { dao.updateSettings("A", 7.0, true, false, true, "r", false) }
            }
        }
        val a = dao.getStationById("A")!!
        assertEquals(7.0, a.alertDistanceKm, 0.0)
        assertEquals("r", a.customReminder)
        assertTrue(a.status == "PAUSED" || a.status == "MONITORING")
        assertEquals("Keep", a.stationName)
        assertEquals(4.0, a.lat, 0.0)
        assertEquals(5.0, a.lon, 0.0)
    }
}
