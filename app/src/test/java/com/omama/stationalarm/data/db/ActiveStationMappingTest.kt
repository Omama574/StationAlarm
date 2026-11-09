package com.omama.stationalarm.data.db

import com.omama.stationalarm.data.ActiveStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Plain-JVM tests for the entity <-> domain mapping, default values and radius tiers. */
class ActiveStationMappingTest {

    @Test
    fun entityDefaults_matchTheDocumentedSchemaDefaults() {
        val e = ActiveStationEntity("A", 3.0, true, false, true, null, false)
        assertEquals("MONITORING", e.status)
        assertEquals(0.0, e.lat, 0.0)
        assertEquals(0.0, e.lon, 0.0)
        assertEquals("", e.stationName)
        assertNull(e.lastTriggeredAt)
    }

    @Test
    fun domainDefaults_matchTheEntityDefaults() {
        val a = ActiveStation("A", 3.0, true, false, true)
        assertEquals("MONITORING", a.status)
        assertEquals(0.0, a.lat, 0.0)
        assertEquals(0.0, a.lon, 0.0)
        assertEquals("", a.stationName)
        assertNull(a.customReminder)
        assertEquals(false, a.sendReminder)
        assertNull(a.currentDistanceKm)
        assertNull(a.lastTriggeredAt)
    }

    @Test
    fun roundTrip_preservesEveryPersistedField() {
        val original = ActiveStationEntity(
            stationId = "liq-1", alertDistanceKm = 12.5, notify = false, vibrate = true, sound = false,
            customReminder = "Get off 🚆", sendReminder = true, status = "ALERTING",
            lat = -33.8688, lon = 151.2093, stationName = "सिडनी", lastTriggeredAt = 1_716_240_000_000L,
        )
        assertEquals(original, original.toDomainModel().toEntity())
    }

    @Test
    fun currentDistance_isRuntimeOnlyAndNeverPersisted() {
        val domain = ActiveStation("A", 3.0, true, true, true, currentDistanceKm = 42.0)
        assertEquals(42.0, domain.currentDistanceKm!!, 0.0)
        assertNull(domain.toEntity().toDomainModel().currentDistanceKm)
    }

    @Test
    fun radiusTiers_areFixedOffsetsFromTheAlertDistance() {
        for (r in listOf(3.0, 7.5, 20.0)) {
            val a = ActiveStation("A", r, true, true, true)
            assertEquals(r, a.radiusLevel1Km, 0.0)
            assertEquals(r + 5, a.radiusLevel2Km, 0.0)
            assertEquals(r + 30, a.radiusLevel3Km, 0.0)
            assertEquals(r + 40, a.radiusLevel4Km, 0.0)
            assertEquals(r + 60, a.radiusLevel5Km, 0.0)
            assertEquals(r + 100, a.radiusLevel6Km, 0.0)
            assertEquals(r + 150, a.radiusLevel7Km, 0.0)
            assertEquals(r + 200, a.radiusLevel8Km, 0.0)
        }
    }

    @Test
    fun getStation_customAlarmWithBlankNameFallsBackToCustomLocation() {
        val s = ActiveStation("manual-1", 5.0, true, true, true, lat = 12.0, lon = 77.0).getStation()
        assertNotNull(s)
        assertEquals("manual-1", s!!.id)
        assertEquals("Custom Location", s.name)
        assertEquals(12.0, s.lat, 0.0)
        assertEquals(77.0, s.lon, 0.0)
    }

    @Test
    fun getStation_usesStoredNameWhenPresent() {
        val s = ActiveStation("manual-1", 5.0, true, true, true, lat = 12.0, lon = 77.0, stationName = "Home").getStation()
        assertEquals("Home", s!!.name)
    }

    @Test
    fun getStation_isNullWhenBothCoordinatesAreZero_butNotWhenOnlyOneIs() {
        assertNull(ActiveStation("manual-1", 5.0, true, true, true).getStation())
        assertNotNull(ActiveStation("manual-1", 5.0, true, true, true, lat = 0.0, lon = 5.0).getStation())
        assertNotNull(ActiveStation("manual-1", 5.0, true, true, true, lat = -5.0, lon = 0.0).getStation())
    }
}
