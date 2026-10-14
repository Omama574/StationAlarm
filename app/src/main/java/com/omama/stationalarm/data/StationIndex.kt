package com.omama.stationalarm.data

private val WORD_SEPARATORS = Regex("[ .()\\-/,]+")

/**
 * Ranked search over the offline station list.
 *
 * The first implementation returned matches in file order and the UI showed the first 8, so for "delhi"
 * eight minor halts were listed and New Delhi (the 11th match) was cut off. Matches are now ordered by
 * how well they fit the query, then by shorter name, then alphabetically:
 *
 *   0  the station code equals the query            "ndls"  -> NEW DELHI
 *   1  the whole name equals the query
 *   2  a whole word of the name equals the query    "delhi" -> DELHI JN., NEW DELHI, DELHI CANTT …
 *   3  a word of the name starts with the query     "agra"  -> … AGRADWIP (after AGRA CITY)
 *   4  the station code starts with the query
 *   5  the name contains the query
 *   6  the station code contains the query
 *
 * Lower-casing and word splitting happen once, when the index is built, so a keystroke only does cheap
 * comparisons even though HomeScreen runs the search on the main thread.
 */
class StationIndex(stations: List<Station>) {

    private class Row(val station: Station) {
        val id: String = station.id.lowercase()
        val name: String = station.name.lowercase()
        val words: List<String> = name.split(WORD_SEPARATORS).filter { it.isNotEmpty() }
    }

    private class Scored(val score: Int, val row: Row)

    private val rows: List<Row> = stations.map { Row(it) }

    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<Station> {
        val q = query.trim().lowercase()
        if (q.isEmpty() || limit <= 0) return emptyList()
        return rows.asSequence()
            .mapNotNull { row -> score(row, q)?.let { Scored(it, row) } }
            .sortedWith(compareBy<Scored>({ it.score }, { it.row.name.length }, { it.row.name }))
            .take(limit)
            .map { it.row.station }
            .toList()
    }

    private fun score(row: Row, q: String): Int? = when {
        row.id == q -> 0
        row.name == q -> 1
        row.words.any { it == q } -> 2
        row.words.any { it.startsWith(q) } -> 3
        row.id.startsWith(q) -> 4
        row.name.contains(q) -> 5
        row.id.contains(q) -> 6
        else -> null
    }

    companion object {
        const val DEFAULT_LIMIT = 20
    }
}
