package com.omama.stationalarm.repository

import com.omama.stationalarm.util.StationIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extra edge cases for [StationIds.sanitize]; complements the existing util/StationIdsTest. */
class StationIdsEdgeCasesTest {

    @Test
    fun hyphensDigitsAndCaseAreKept() {
        assertEquals("Ab-12-Z", StationIds.sanitize("Ab-12-Z"))
    }

    @Test
    fun everyNonAsciiCodePointBecomesOneHyphen() {
        assertEquals("---", StationIds.sanitize("मुं"))
        // Regex works on code points, so a surrogate pair (an emoji) is a single replacement.
        assertEquals("a-b", StationIds.sanitize("a🚆b"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmedButInnerWhitespaceIsNot() {
        assertEquals("a-b", StationIds.sanitize("\t a b \n"))
    }

    @Test
    fun emptyAndWhitespaceOnlyGetAGeneratedIdThatIsItselfSafe() {
        for (raw in listOf("", " ", "\n\t")) {
            val id = StationIds.sanitize(raw)
            assertTrue(id, id.matches(Regex("place-\\d+")))
        }
    }

    @Test
    fun sanitizingIsIdempotent() {
        for (raw in listOf("liq_rev_9", "a b/c", "NDLS", "ÅÄÖ", "x_y-z")) {
            val once = StationIds.sanitize(raw)
            assertEquals(once, StationIds.sanitize(once))
        }
    }

    @Test
    fun resultNeverContainsAnUnderscore() {
        for (raw in listOf("_", "__x__", "liq_1_2_3", "geofence_x_alert")) {
            assertFalse(raw, StationIds.sanitize(raw).contains('_'))
        }
    }

    @Test
    fun allPunctuationIdIsNotTreatedAsBlank() {
        // Only whitespace is "blank"; "///" keeps its shape as hyphens rather than being regenerated.
        assertEquals("---", StationIds.sanitize("///"))
    }

    @Test
    fun veryLongIdsAreNotTruncated() {
        // Documents current behaviour: no length cap (GMS rejects request ids over 100 chars; see report).
        val raw = "x".repeat(500)
        assertEquals(raw, StationIds.sanitize(raw))
    }

    @Test
    fun distinctRawIdsCanCollideAfterSanitising() {
        // Documents current behaviour: "liq_1" and "liq-1" map to the same alarm id.
        assertEquals(StationIds.sanitize("liq_1"), StationIds.sanitize("liq-1"))
    }
}
