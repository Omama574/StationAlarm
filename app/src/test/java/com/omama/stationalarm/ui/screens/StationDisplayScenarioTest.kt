package com.omama.stationalarm.ui.screens

import com.omama.stationalarm.util.StationIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationDisplayScenarioTest {

    @Test
    fun railwayCodesPassThroughUnchanged() {
        for (code in listOf("NDLS", "BCT", "PUNE", "LNL", "R", "sc", "A1B2C3", "123456")) {
            assertEquals(code, displayableStationCode(code))
        }
    }

    @Test
    fun geocoderAndManualPinIdsAreHidden() {
        assertNull(displayableStationCode("liq-481312177"))
        assertNull(displayableStationCode("liq_481312177"))
        assertNull(displayableStationCode("manual-1718000000000"))
        assertNull(displayableStationCode("place-1718000000000"))
    }

    @Test
    fun lengthAndCharacterBoundaries() {
        assertEquals("ABCDEF", displayableStationCode("ABCDEF"))   // 6 is the maximum
        assertNull(displayableStationCode("ABCDEFG"))              // 7
        assertNull(displayableStationCode(""))
        assertNull(displayableStationCode(" "))
        assertNull(displayableStationCode(" NDLS"))
        assertNull(displayableStationCode("NDLS\n"))               // matches() must be whole-string
        assertNull(displayableStationCode("ND-S"))
        assertNull(displayableStationCode("ND_S"))
        assertNull(displayableStationCode("दिल्ली"))
        assertNull(displayableStationCode("ＮＤＬＳ"))              // full-width letters are not [A-Za-z]
    }

    @Test
    fun sanitizedGeocoderIdsStayHidden() {
        // StationIds.sanitize turns "liq_123456789" into "liq-123456789": must still not show as a station code.
        assertNull(displayableStationCode(StationIds.sanitize("liq_481312177")))
        assertNull(displayableStationCode(StationIds.sanitize("liq_rev_99")))
    }
}
