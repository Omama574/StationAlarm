package com.omama.stationalarm.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationIdsTest {

    @Test
    fun railwayCodesAreUnchanged() {
        assertEquals("NDLS", StationIds.sanitize("NDLS"))
        assertEquals("PUNE", StationIds.sanitize("PUNE"))
        assertEquals("SC", StationIds.sanitize("SC"))
    }

    @Test
    fun geocoderIdsLoseTheirUnderscores() {
        // Real shapes returned by the Cloudflare Worker.
        assertEquals("liq-481312177", StationIds.sanitize("liq_481312177"))
        assertEquals("liq-rev-481312177", StationIds.sanitize("liq_rev_481312177"))
    }

    @Test
    fun droppedPinIdsAreUnchanged() {
        assertEquals("manual-1718000000000", StationIds.sanitize("manual-1718000000000"))
    }

    @Test
    fun otherUnsafeCharactersAreReplacedToo() {
        assertEquals("a-b-c", StationIds.sanitize(" a b/c "))
    }

    @Test
    fun blankInputGetsAGeneratedId() {
        val id = StationIds.sanitize("   ")
        assertTrue(id.startsWith("place-"))
    }

    /**
     * Documents the bug this class exists to prevent. GeofenceBroadcastReceiver parses
     * "geofence_<stationId>_<layer>" with split("_", limit = 3): an underscore inside the station ID
     * moves the split, the receiver then looks up station "liq" instead of the real one, and the
     * hardware geofence event is dropped.
     */
    @Test
    fun rawUnderscoreIdBreaksTheReceiversSplit() {
        val parts = "geofence_liq_481312177_alert".split("_", limit = 3)
        assertEquals("liq", parts[1])                  // wrong station
        assertEquals("481312177_alert", parts[2])      // not a known layer, so the alert never triggers
    }

    @Test
    fun sanitizedIdsSurviveTheReceiversSplit() {
        val id = StationIds.sanitize("liq_481312177")
        val parts = "geofence_${id}_alert".split("_", limit = 3)
        assertEquals(3, parts.size)
        assertEquals(id, parts[1])
        assertEquals("alert", parts[2])
        assertNotEquals("liq", parts[1])
    }
}
