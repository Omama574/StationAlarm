package com.omama.stationalarm.network

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * GeocodingModels.kt no longer has provider-specific toSearchResult() mappers: the Worker normalises, and the
 * app's "mapping" is Gson parsing + [GeoSearchResult.hasValidCoords]. These tests cover that surface.
 */
class GeocodingModelsExtraTest {

    private val gson = Gson()
    private fun r(lat: Double, lon: Double) = GeoSearchResult("id", "n", "a", lat, lon)

    // ── hasValidCoords ────────────────────────────────────────────────────────

    @Test
    fun validCoords_boundaryMatrix() {
        val valid = listOf(
            90.0 to 0.0, -90.0 to 0.0, 0.0 to 180.0, 0.0 to -180.0, 90.0 to 180.0, -90.0 to -180.0,
            18.7492 to 73.408, 0.0 to 0.0000001, 0.0000001 to 0.0, -0.5 to 0.5, 89.9999999 to 179.9999999,
        )
        valid.forEach { (la, lo) -> assertTrue("$la,$lo", r(la, lo).hasValidCoords) }

        val invalid = listOf(
            0.0 to 0.0, -0.0 to -0.0, -0.0 to 0.0,
            90.0000001 to 0.0, -90.0000001 to 0.0, 0.0 to 180.0000001, 0.0 to -180.0000001,
            91.0 to 10.0, 10.0 to 181.0, 1e9 to 1e9, -1e9 to 5.0,
            Double.NaN to 10.0, 10.0 to Double.NaN, Double.NaN to Double.NaN,
            Double.POSITIVE_INFINITY to 10.0, 10.0 to Double.NEGATIVE_INFINITY,
            Double.MAX_VALUE to Double.MIN_VALUE,
        )
        invalid.forEach { (la, lo) -> assertFalse("$la,$lo", r(la, lo).hasValidCoords) }
    }

    @Test
    fun swappedLatLon_ofAnIndianStation_isStillInRange_soTheFilterCannotCatchIt() {
        // Documents a limit of the filter: (73.4, 18.7) is a legal Earth point, only the Worker can get this right.
        assertTrue(r(73.408, 18.7492).hasValidCoords)
    }

    // ── parsing the Worker envelope ───────────────────────────────────────────

    @Test
    fun missingLatLon_defaultToZero_andAreFilteredAsPlaceholders() {
        val resp = gson.fromJson("""{"results":[{"id":"a","name":"n","formattedAddress":"f"}]}""", GeocodingResponse::class.java)
        val row = resp.results.single()
        assertEquals(0.0, row.lat, 0.0)
        assertEquals(0.0, row.lon, 0.0)
        assertFalse(row.hasValidCoords)
    }

    @Test
    fun nullLatLon_leaveTheZeroDefault() {
        val resp = gson.fromJson("""{"results":[{"id":"a","name":"n","formattedAddress":"f","lat":null,"lon":null}]}""", GeocodingResponse::class.java)
        assertFalse(resp.results.single().hasValidCoords)
    }

    @Test
    fun numericStringCoordinates_areAccepted_nonNumericOnesThrow() {
        val ok = gson.fromJson("""{"results":[{"id":"a","name":"n","formattedAddress":"f","lat":"18.5","lon":"73.8"}]}""", GeocodingResponse::class.java)
        assertEquals(18.5, ok.results.single().lat, 0.0)
        val failure = runCatching {
            gson.fromJson("""{"results":[{"id":"a","name":"n","formattedAddress":"f","lat":"north","lon":1}]}""", GeocodingResponse::class.java)
        }.exceptionOrNull()
        assertTrue("gave $failure", failure is JsonSyntaxException || failure is NumberFormatException)
    }

    @Test
    fun extremeCoordinates_parseExactly_andOutOfRangeOnesAreNotValid() {
        val json = """{"results":[
            {"id":"n","name":"pole","formattedAddress":"","lat":90,"lon":-180},
            {"id":"o","name":"over","formattedAddress":"","lat":90.00001,"lon":0.5},
            {"id":"p","name":"tiny","formattedAddress":"","lat":4.9E-324,"lon":0},
            {"id":"q","name":"huge","formattedAddress":"","lat":1.7976931348623157E308,"lon":1}
        ]}"""
        val rows = gson.fromJson(json, GeocodingResponse::class.java).results.associateBy { it.id }
        assertTrue(rows.getValue("n").hasValidCoords)
        assertFalse(rows.getValue("o").hasValidCoords)
        assertTrue(rows.getValue("p").hasValidCoords)   // denormal but non-zero, inside the bounds
        assertFalse(rows.getValue("q").hasValidCoords)
    }

    @Test
    fun unicodeAndEmoji_inTextFields_roundTrip() {
        val json = """{"results":[{"id":"u","name":"दिल्ली 😀 São Paulo","formattedAddress":"北京市, é","lat":1.5,"lon":2.5}]}"""
        val row = gson.fromJson(json, GeocodingResponse::class.java).results.single()
        assertEquals("दिल्ली 😀 São Paulo", row.name)
        assertEquals("北京市, é", row.formattedAddress)
    }

    @Test
    fun dataClassCopyAndEquality_includeEveryField() {
        val a = GeoSearchResult("1", "n", "a", 1.0, 2.0)
        assertEquals(a, a.copy())
        assertFalse(a == a.copy(id = "2"))
        assertFalse(a == a.copy(lon = 2.5))
        assertEquals(GeocodingResponse(listOf(a)), GeocodingResponse(listOf(a.copy())))
    }

    // ── the Retrofit contract (what may go on the wire) ───────────────────────

    private val methods = GeocodingService::class.java.declaredMethods.filter { !it.isSynthetic && '$' !in it.name }.associateBy { it.name }

    private fun queryNames(name: String): Set<String> =
        methods.getValue(name).parameterAnnotations.flatMap { it.filterIsInstance<Query>() }.map { it.value }.toSet()

    @Test
    fun service_exposesExactlySearchAndReverse() {
        assertEquals(setOf("search", "reverse"), methods.keys)
        assertEquals("search", methods.getValue("search").getAnnotation(GET::class.java)!!.value)
        assertEquals("reverse", methods.getValue("reverse").getAnnotation(GET::class.java)!!.value)
    }

    @Test
    fun service_queryParameters_areExactlyTheDocumentedOnes() {
        assertEquals(setOf("q", "limit", "bias_lat", "bias_lon", "lang"), queryNames("search"))
        assertEquals(setOf("lat", "lon", "lang"), queryNames("reverse"))
    }

    @Test
    fun service_hasNoCountryRestrictionOrCredentialParameter() {
        val all = queryNames("search") + queryNames("reverse")
        val forbidden = listOf("country", "key", "token", "secret", "auth")
        assertTrue("$all", all.none { n -> forbidden.any { n.lowercase().contains(it) } })
    }

    @Test
    fun service_declaresNoHeaderOrUrlOverrides() {
        val ann = methods.values.flatMap { it.parameterAnnotations.flatMap { a -> a.toList() } }.map { it.annotationClass.java.simpleName }
        assertTrue("$ann", ann.all { it == "Query" })
    }

    @Test
    fun buildConfig_carriesNoKeyLikeFields() {
        val fields = Class.forName("com.omama.stationalarm.BuildConfig").declaredFields
        val forbidden = listOf("key", "token", "secret", "password", "liq", "mapbox")
        val bad = fields.filter { f -> forbidden.any { f.name.lowercase().contains(it) } }.map { it.name }
        assertTrue("suspicious BuildConfig fields $bad (all: ${fields.map { it.name }})", bad.isEmpty())
        fields.filter { it.type == String::class.java }.forEach { f ->
            val v = f.get(null) as String
            assertFalse("${f.name}=$v", Regex("""(?i)(pk\.[0-9a-f]{16,}|AIza[0-9A-Za-z_\-]{35}|[0-9a-f]{32,})""").containsMatchIn(v))
        }
    }
}
