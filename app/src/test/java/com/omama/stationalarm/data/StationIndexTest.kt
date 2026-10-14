package com.omama.stationalarm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationIndexTest {

    private fun s(id: String, name: String) = Station(id, name, 0.0, 0.0)

    // A slice shaped like the real list, including the case that motivated ranking: the file order puts
    // minor halts before New Delhi.
    private val index = StationIndex(
        listOf(
            s("ANDI", "ADARSH NAGAR DELHI"),
            s("DAZ", "DELHI AZADPUR"),
            s("DEC", "DELHI CANTT"),
            s("DLI", "DELHI JN."),
            s("NDLS", "NEW DELHI"),
            s("SC", "SECUNDERABAD JN"),
            s("SCH", "SACHIN"),
            s("AGAE", "AGRADWIP"),
            s("AGA", "AGRA CITY"),
            s("NGE", "NAGAR"),
            s("BGNR", "B.G.NAGAR"),
            s("NGI", "NAGARI"),
        )
    )

    @Test
    fun stationCodeMatchBeatsEverythingElse() {
        assertEquals("SC", index.search("sc").first().id)
        assertEquals("NDLS", index.search("ndls").first().id)
    }

    @Test
    fun newDelhiIsNotBuriedBehindOtherDelhiStations() {
        val ids = index.search("delhi").map { it.id }
        assertTrue("NDLS missing in $ids", "NDLS" in ids)
        assertTrue("NDLS too low in $ids", ids.indexOf("NDLS") <= 1)
    }

    @Test
    fun wholeWordMatchBeatsALongerWordThatMerelyStartsWithTheQuery() {
        val ids = index.search("agra").map { it.id }
        assertTrue(ids.indexOf("AGA") < ids.indexOf("AGAE"))
    }

    @Test
    fun exactNameBeatsWordMatchesAndWordMatchesBeatPrefixes() {
        val ids = index.search("nagar").map { it.id }
        assertEquals("NGE", ids[0])                          // name == query
        assertTrue(ids.indexOf("BGNR") < ids.indexOf("NGI")) // whole word before a longer word prefix
    }

    @Test
    fun matchingIsCaseInsensitiveAndTrimsTheQuery() {
        assertEquals(index.search("delhi"), index.search("  DeLhI "))
    }

    @Test
    fun blankQueryReturnsNothing() {
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun limitIsApplied() {
        assertEquals(2, index.search("delhi", limit = 2).size)
        assertTrue(index.search("delhi", limit = 0).isEmpty())
    }

    @Test
    fun unknownQueryReturnsNothing() {
        assertTrue(index.search("zzzz").isEmpty())
    }
}
