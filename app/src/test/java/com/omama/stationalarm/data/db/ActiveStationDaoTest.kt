package com.omama.stationalarm.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * In-memory Room tests covering the DAO contract that the reliability fixes
 * depend on. These are the operations that, if they ever silently broke,
 * would cause ghost alarms, lost renames, or wrong "Last triggered" stamps.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ActiveStationDaoTest {

    private lateinit var db: StationDatabase
    private lateinit var dao: ActiveStationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StationDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.activeStationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun station(
        id: String,
        status: String = "MONITORING",
        name: String = "Station $id",
        lastTriggeredAt: Long? = null
    ) = ActiveStationEntity(
        stationId = id,
        alertDistanceKm = 3.0,
        notify = true,
        vibrate = true,
        sound = true,
        customReminder = null,
        sendReminder = false,
        status = status,
        lat = 12.97,
        lon = 77.59,
        stationName = name,
        lastTriggeredAt = lastTriggeredAt
    )

    @Test
    fun resetAllAlertingToMonitoring_onlyTouchesAlertingRows() = runTest {
        dao.insert(station("A", status = "ALERTING"))
        dao.insert(station("B", status = "MONITORING"))
        dao.insert(station("C", status = "PAUSED"))

        val updated = dao.resetAllAlertingToMonitoring()

        assertEquals(1, updated)
        assertEquals("MONITORING", dao.getStatus("A"))
        assertEquals("MONITORING", dao.getStatus("B"))
        assertEquals("PAUSED", dao.getStatus("C"))
    }

    @Test
    fun markAlertingFromMonitoring_isNoOpOnPausedRow() = runTest {
        // PAUSED is the dismissed-after-ringing state; a late geofence event
        // arriving in that window must NOT resurrect the row to ALERTING.
        dao.insert(station("X", status = "PAUSED"))

        dao.markAlertingFromMonitoring("X")

        assertEquals("PAUSED", dao.getStatus("X"))
    }

    @Test
    fun markAlertingFromMonitoring_promotesMonitoringRow() = runTest {
        dao.insert(station("Y", status = "MONITORING"))

        dao.markAlertingFromMonitoring("Y")

        assertEquals("ALERTING", dao.getStatus("Y"))
    }

    @Test
    fun updateStationName_changesOnlyTargetRow() = runTest {
        dao.insert(station("A", name = "Old A"))
        dao.insert(station("B", name = "Old B"))

        dao.updateStationName("A", "New A")

        assertEquals("New A", dao.getStationById("A")?.stationName)
        assertEquals("Old B", dao.getStationById("B")?.stationName)
    }

    @Test
    fun setLastTriggeredAt_writesAndReadsBack() = runTest {
        dao.insert(station("A"))
        assertNull(dao.getStationById("A")?.lastTriggeredAt)

        val ts = 1_716_240_000_000L // arbitrary fixed epoch ms
        dao.setLastTriggeredAt("A", ts)

        assertEquals(ts, dao.getStationById("A")?.lastTriggeredAt)
    }

    @Test
    fun isActive_returnsFalseForMissingRow() = runTest {
        assertFalse(dao.isActive("nope"))
        dao.insert(station("A"))
        assertTrue(dao.isActive("A"))
    }

    @Test
    fun delete_removesRow() = runTest {
        dao.insert(station("A"))
        assertNotNull(dao.getStationById("A"))

        dao.delete("A")

        assertNull(dao.getStationById("A"))
    }
}
