package com.omama.stationalarm.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Gaps left by StationIndexTest: the full 7-tier ordering, tie-breakers, name separators, hostile
 * input (regex metacharacters, unicode, huge strings) and locale safety. Pure JVM.
 */
class StationIndexExtraTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun s(id: String, name: String) = Station(id, name, 1.0, 1.0)
    private fun ids(index: StationIndex, q: String, limit: Int = 50) = index.search(q, limit).map { it.id }

    // One station per tier for the query "ab"; every other field is chosen so it can match no other tier.
    private val tiers = listOf(
        s("AB", "ZZZ"),            // 0 code equals
        s("Q1", "AB"),             // 1 name equals
        s("Q2", "RAM AB TOWN"),    // 2 a whole word equals
        s("Q3", "ABAD ROAD"),      // 3 a word starts with
        s("ABX", "QQQ"),           // 4 code starts with
        s("Q4", "KABUL"),          // 5 name contains
        s("XAB", "QQQQ"),          // 6 code contains
    )

    @Test
    fun allSevenTiersComeOutInDocumentedOrder_whateverTheInputOrder() {
        val expected = listOf("AB", "Q1", "Q2", "Q3", "ABX", "Q4", "XAB")
        assertEquals(expected, ids(StationIndex(tiers), "ab"))
        assertEquals(expected, ids(StationIndex(tiers.reversed()), "ab"))
        assertEquals(expected, ids(StationIndex(tiers.shuffled(java.util.Random(7))), "AB"))
    }

    @Test
    fun withinATier_shorterNameWins_thenAlphabetical_thenInputOrder() {
        val index = StationIndex(
            listOf(
                s("L3", "ZZ GOA TOWN"),   // tier 2, 11 chars
                s("L1", "GOA ZZ"),        // tier 2, 6 chars
                s("L2", "AA GOA"),        // tier 2, 6 chars, alphabetically before "goa zz"
                s("D1", "GOA ZZ"),        // identical to L1 -> keeps input order (stable sort)
            )
        )
        assertEquals(listOf("L2", "L1", "D1", "L3"), ids(index, "goa"))
    }

    @Test
    fun identicalDuplicateRows_areBothReturned() {
        val index = StationIndex(listOf(s("DR", "DADAR"), s("DDR", "DADAR")))
        assertEquals(listOf("DR", "DDR"), ids(index, "dadar"))
    }

    @Test
    fun nameSeparators_dotParenHyphenSlashCommaAndSpace_splitWords() {
        val index = StationIndex(
            listOf(
                s("A", "B.G.NAGAR"),
                s("B", "KOLKATA (CHITPUR)"),
                s("C", "ABC-DEF"),
                s("D", "UP/DOWN YARD"),
                s("E", "PORT, TOWN"),
            )
        )
        assertEquals(listOf("A"), ids(index, "g"))          // whole word "g"
        assertEquals(listOf("B"), ids(index, "chitpur"))
        assertEquals(listOf("C"), ids(index, "def"))
        assertEquals(listOf("D"), ids(index, "down"))
        assertEquals(listOf("E"), ids(index, "town"))
    }

    @Test
    fun regexAndPunctuationInTheQuery_isTreatedLiterally_andNeverThrows() {
        val index = StationIndex(listOf(s("B", "KOLKATA (CHITPUR)"), s("X", "PLAIN")))
        assertEquals(listOf("B"), ids(index, "(chitpur)"))
        for (q in listOf("(", ")", "[", "]", "*", "+", "?", "\\", "$", "^", ".*", "a|b", "{1}", "%", "_", "'", "\"", "--", ";DROP TABLE")) {
            index.search(q) // must not throw PatternSyntaxException & co.
        }
        assertTrue(index.search(".*").isEmpty())
    }

    @Test
    fun unicode_neverCrashes_andNonLatinTextFindsNothingInAnEnglishList() {
        val index = StationIndex(listOf(s("NDLS", "NEW DELHI"), s("PUNE", "PUNE JN")))
        for (q in listOf("दिल्ली", "نيودلهي", "新德里", "😀😀😀", "é", "​delhi", "ＮＤＬＳ", "İSTANBUL", "ß")) {
            index.search(q)
        }
        assertTrue(index.search("दिल्ली").isEmpty())
        assertTrue(index.search("😀😀😀").isEmpty())
    }

    @Test
    fun nonBreakingSpaceAroundTheQuery_isTrimmed() {
        val index = StationIndex(listOf(s("NDLS", "NEW DELHI")))
        assertEquals(listOf("NDLS"), ids(index, " delhi "))
        assertEquals(listOf("NDLS"), ids(index, "\tdelhi\n"))
    }

    @Test
    fun multiWordQuery_matchesTheWholeNameAsATierOneHit() {
        val index = StationIndex(listOf(s("NDLS", "NEW DELHI"), s("DLI", "DELHI JN."), s("X", "NEW DELHI CANTT")))
        assertEquals("NDLS", ids(index, "new delhi").first())
        assertEquals("NDLS", ids(index, "NEW DELHI").first())
    }

    @Test
    fun turkishDefaultLocale_doesNotBreakCaseInsensitiveMatching() {
        // String.lowercase() is locale independent; toLowerCase() under "tr" would turn "I" into dotless "ı"
        // and "INDORE" would stop matching "indore".
        Locale.setDefault(Locale("tr", "TR"))
        val index = StationIndex(listOf(s("INDB", "INDORE JN"), s("ISL", "ISLAMPUR")))
        assertEquals(listOf("INDB"), ids(index, "indore"))
        assertEquals(listOf("INDB"), ids(index, "INDORE"))
        assertEquals(listOf("ISL"), ids(index, "islam"))
        assertEquals(listOf("ISL"), ids(index, "ISL"))
    }

    @Test
    fun mixedCaseStationNames_matchAnyCaseQuery() {
        val index = StationIndex(listOf(s("BH", "BHOnra"), s("BB", "Banshbari Halt")))
        assertEquals(listOf("BH"), ids(index, "bhonra"))
        assertEquals(listOf("BH"), ids(index, "BHONRA"))
        assertEquals(listOf("BB"), ids(index, "HALT"))
    }

    @Test
    fun veryLongQuery_returnsEmptyQuicklyWithoutThrowing() {
        val index = StationIndex((1..2000).map { s("S$it", "STATION $it") })
        val huge = "x".repeat(200_000)
        val start = System.nanoTime()
        assertTrue(index.search(huge).isEmpty())
        assertTrue(index.search("station ".repeat(5000)).isEmpty())
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", (System.nanoTime() - start) < 5_000_000_000L)
    }

    @Test
    fun defaultLimitIsTwentyAndNegativeLimitIsEmpty() {
        val index = StationIndex((1..30).map { s("S$it", "MATCH $it") })
        assertEquals(StationIndex.DEFAULT_LIMIT, 20)
        assertEquals(20, index.search("match").size)
        assertEquals(30, index.search("match", limit = 1000).size)
        assertTrue(index.search("match", limit = -5).isEmpty())
    }

    @Test
    fun emptyAndDegenerateStationLists_areSafe() {
        assertTrue(StationIndex(emptyList()).search("delhi").isEmpty())
        val odd = StationIndex(listOf(s("", ""), s("  ", "   "), s("X", "...")))
        assertTrue(odd.search("delhi").isEmpty())
        assertNotNull(odd.search("x"))
        assertEquals(listOf("X"), odd.search("x").map { it.id })
    }

    @Test
    fun resultsAreTheOriginalStationObjects() {
        val st = s("NDLS", "NEW DELHI")
        assertTrue(StationIndex(listOf(st)).search("ndls").single() === st)
    }

    @Test
    fun codeSearchIsExactBeforePrefix() {
        val index = StationIndex(listOf(s("NDLS", "NEW DELHI"), s("NDL", "NANDALUR"), s("NDLSX", "OTHER")))
        assertEquals(listOf("NDLS", "NDLSX"), ids(index, "ndls"))
    }
}
