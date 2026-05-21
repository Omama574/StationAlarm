package com.omama.stationalarm.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing contract for the Worker's neutral geocoding response shape.
 * These tests are the canary that the wire format hasn't drifted — if any
 * of them fail after a Worker change, the app will fail at runtime in the
 * same way. Pure Gson, no Android required.
 */
class GeocodingResponseParseTest {

    private val gson = Gson()

    @Test
    fun parses_search_response_with_full_fields() {
        val json = """
            {
              "results": [
                {
                  "id": "liq_293847",
                  "name": "Mumbai Central",
                  "formattedAddress": "Mumbai Central, Tardeo, Mumbai, Maharashtra, 400008, India",
                  "lat": 18.969,
                  "lon": 72.819
                },
                {
                  "id": "liq_999",
                  "name": "Bandra Terminus",
                  "formattedAddress": "Bandra, Mumbai, Maharashtra, India",
                  "lat": 19.060,
                  "lon": 72.840
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, GeocodingResponse::class.java)
        assertNotNull(response)
        assertEquals(2, response.results.size)
        assertEquals("liq_293847", response.results[0].id)
        assertEquals("Mumbai Central", response.results[0].name)
        assertTrue(response.results[0].formattedAddress.contains("Maharashtra"))
        assertEquals(18.969, response.results[0].lat, 0.0001)
        assertEquals(72.819, response.results[0].lon, 0.0001)
    }

    @Test
    fun parses_empty_results_array() {
        // Worker returns this for reverse-with-no-address (e.g., middle of ocean).
        // App's onMapTap fallback path expects this exact shape.
        val response = gson.fromJson("""{"results":[]}""", GeocodingResponse::class.java)
        assertNotNull(response)
        assertTrue(response.results.isEmpty())
    }

    @Test
    fun parses_blank_formattedAddress() {
        // Worker may legitimately ship "" when provider gave nothing usable.
        // App's UI hides the subtitle line when this happens.
        val json = """
            {
              "results": [
                { "id": "x", "name": "Some Place", "formattedAddress": "", "lat": 12.0, "lon": 77.0 }
              ]
            }
        """.trimIndent()
        val response = gson.fromJson(json, GeocodingResponse::class.java)
        assertEquals("", response.results[0].formattedAddress)
        // hasValidCoords must still pass for non-zero coords
        assertTrue(response.results[0].hasValidCoords)
    }

    @Test
    fun hasValidCoords_rejects_zero_zero_placeholder() {
        val result = GeoSearchResult(
            id = "x", name = "X", formattedAddress = "", lat = 0.0, lon = 0.0
        )
        assertFalse(result.hasValidCoords)
    }

    @Test
    fun hasValidCoords_rejects_NaN() {
        val result = GeoSearchResult(
            id = "x", name = "X", formattedAddress = "", lat = Double.NaN, lon = 0.0
        )
        assertFalse(result.hasValidCoords)
    }

    @Test
    fun hasValidCoords_rejects_out_of_range() {
        val result = GeoSearchResult(
            id = "x", name = "X", formattedAddress = "", lat = 91.0, lon = 0.0
        )
        assertFalse(result.hasValidCoords)
    }

    @Test
    fun hasValidCoords_accepts_real_coords() {
        // Both poles count, equator counts, antimeridian counts — all valid Earth points.
        val pole = GeoSearchResult(id = "x", name = "X", formattedAddress = "", lat = 90.0, lon = 0.0)
        val antimeridian = GeoSearchResult(id = "y", name = "Y", formattedAddress = "", lat = 0.0, lon = 180.0)
        assertTrue(pole.hasValidCoords)
        assertTrue(antimeridian.hasValidCoords)
    }

    @Test
    fun parses_id_as_string_even_when_provider_uses_number_like_value() {
        // OSM IDs are large integers — Worker stringifies before shipping.
        // Verify the type stays string on parse.
        val json = """
            {"results":[{"id":"liq_1234567890","name":"X","formattedAddress":"","lat":1.0,"lon":2.0}]}
        """.trimIndent()
        val response = gson.fromJson(json, GeocodingResponse::class.java)
        assertEquals("liq_1234567890", response.results[0].id)
    }
}
