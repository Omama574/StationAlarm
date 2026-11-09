package com.omama.stationalarm.util

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fills the gap AppRemoteConfigTest names ("the interesting cross-floor case is exercised via integration"):
 * the private StateFlows are driven through reflection, so the version floor can be crossed on the JVM.
 * AppRemoteConfig.refresh(FirebaseRemoteConfig) is not covered: FirebaseRemoteConfig cannot be constructed
 * or subclassed from a test without Firebase initialisation.
 */
class AppRemoteConfigExtraTest {

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(name: String): MutableStateFlow<T> =
        AppRemoteConfig::class.java.getDeclaredField(name).apply { isAccessible = true }.get(AppRemoteConfig) as MutableStateFlow<T>

    private fun setFloor(v: Long) { flow<Long>("_minSupportedAppVersion").value = v }

    @After
    fun resetToDefaults() {
        setFloor(AppRemoteConfig.DEFAULT_MIN_SUPPORTED_VERSION)
        flow<Boolean>("_maintenanceMode").value = AppRemoteConfig.DEFAULT_MAINTENANCE_MODE
        flow<String>("_maintenanceMessage").value = AppRemoteConfig.DEFAULT_MAINTENANCE_MESSAGE
        flow<Map<String, Boolean>>("_featureFlags").value = emptyMap()
    }

    // ── contract with StationAlarmApplication.setDefaultsAsync ─────────────────

    @Test
    fun keysAndDefaults_matchWhatTheApplicationRegisters() {
        assertEquals("min_supported_app_version", AppRemoteConfig.KEY_MIN_SUPPORTED_VERSION)
        assertEquals("maintenance_mode", AppRemoteConfig.KEY_MAINTENANCE_MODE)
        assertEquals("maintenance_message", AppRemoteConfig.KEY_MAINTENANCE_MESSAGE)
        assertEquals("feature_flags", AppRemoteConfig.KEY_FEATURE_FLAGS)
        assertEquals(0L, AppRemoteConfig.DEFAULT_MIN_SUPPORTED_VERSION)
        assertFalse(AppRemoteConfig.DEFAULT_MAINTENANCE_MODE)
        assertEquals("", AppRemoteConfig.DEFAULT_MAINTENANCE_MESSAGE)
        assertEquals("{}", AppRemoteConfig.DEFAULT_FEATURE_FLAGS)
        // the default flags blob must itself parse to "no flags"
        assertTrue(AppRemoteConfig.parseFeatureFlags(AppRemoteConfig.DEFAULT_FEATURE_FLAGS).isEmpty())
    }

    @Test
    fun flowsStartAtTheBakedInDefaults_andAreReadOnlyToCallers() {
        assertEquals(0L, AppRemoteConfig.minSupportedAppVersionFlow.value)
        assertFalse(AppRemoteConfig.maintenanceModeFlow.value)
        assertEquals("", AppRemoteConfig.maintenanceMessageFlow.value)
        assertTrue(AppRemoteConfig.featureFlagsFlow.value.isEmpty())
        assertFalse(AppRemoteConfig.minSupportedAppVersionFlow is MutableStateFlow<*>)
        assertFalse(AppRemoteConfig.featureFlagsFlow is MutableStateFlow<*>)
    }

    // ── isForceUpdateRequired across the floor ────────────────────────────────

    @Test
    fun releaseBuild_blocksOnlyVersionsBelowTheFloor() {
        setFloor(100)
        assertTrue(AppRemoteConfig.isForceUpdateRequired(99, isDebug = false))
        assertFalse("equal to the floor is allowed", AppRemoteConfig.isForceUpdateRequired(100, isDebug = false))
        assertFalse(AppRemoteConfig.isForceUpdateRequired(101, isDebug = false))
        assertTrue(AppRemoteConfig.isForceUpdateRequired(0, isDebug = false))
        assertTrue(AppRemoteConfig.isForceUpdateRequired(Long.MIN_VALUE, isDebug = false))
        assertFalse(AppRemoteConfig.isForceUpdateRequired(Long.MAX_VALUE, isDebug = false))
    }

    @Test
    fun debugBuild_isNeverBlocked_evenWithAnImpossibleFloor() {
        setFloor(Long.MAX_VALUE)
        assertFalse(AppRemoteConfig.isForceUpdateRequired(1, isDebug = true))
        assertFalse(AppRemoteConfig.isForceUpdateRequired(Long.MIN_VALUE, isDebug = true))
        assertTrue(AppRemoteConfig.isForceUpdateRequired(Long.MAX_VALUE - 1, isDebug = false))
    }

    @Test
    fun floorOfZeroOrLess_blocksNoRealBuild() {
        for (floor in listOf(0L, -1L, Long.MIN_VALUE)) {
            setFloor(floor)
            assertFalse("floor $floor", AppRemoteConfig.isForceUpdateRequired(1, isDebug = false))
        }
    }

    @Test
    fun floorChangeIsSeenByTheNextCall() {
        assertFalse(AppRemoteConfig.isForceUpdateRequired(50, isDebug = false))
        setFloor(51)
        assertTrue(AppRemoteConfig.isForceUpdateRequired(50, isDebug = false))
        setFloor(50)
        assertFalse(AppRemoteConfig.isForceUpdateRequired(50, isDebug = false))
    }

    // ── parseFeatureFlags: more hostile inputs ────────────────────────────────

    @Test
    fun onlyRealBooleansSurvive_everythingElseIsDropped() {
        val out = AppRemoteConfig.parseFeatureFlags(
            """{"a":true,"b":"false","c":0,"d":null,"e":[true],"f":false,"g":"true","h":1,"i":{"x":true},"j":1.5}"""
        )
        assertEquals(mapOf("a" to true, "f" to false), out)
    }

    @Test
    fun duplicateKeys_lastWins_keysAreCaseSensitive() {
        val out = AppRemoteConfig.parseFeatureFlags("""{"x":true,"x":false,"X":true}""")
        assertEquals(mapOf("x" to false, "X" to true), out)
    }

    @Test
    fun nonObjectDocuments_failClosed() {
        for (doc in listOf("null", "true", "42", "\"flags\"", "[]", "[{\"a\":true}]")) {
            assertTrue("<$doc>", AppRemoteConfig.parseFeatureFlags(doc).isEmpty())
        }
    }

    @Test
    fun trailingGarbageOrTruncation_failsClosedToEmpty() {
        assertTrue(AppRemoteConfig.parseFeatureFlags("""{"a":true} trailing""").isEmpty())
        assertTrue(AppRemoteConfig.parseFeatureFlags("""{"a":true""").isEmpty())
        assertTrue(AppRemoteConfig.parseFeatureFlags("""{"a":tru}""").let { it.isEmpty() || it["a"] != true })
    }

    @Test
    fun prettyPrintedUnicodeAndLargeDocuments_parse() {
        val pretty = "{\n  \"новая_функция\": true,\n  \"日本語\": false\n}\n"
        assertEquals(mapOf("новая_функция" to true, "日本語" to false), AppRemoteConfig.parseFeatureFlags(pretty))
        val big = (1..5000).joinToString(",", "{", "}") { "\"flag_$it\":${it % 2 == 0}" }
        val parsed = AppRemoteConfig.parseFeatureFlags(big)
        assertEquals(5000, parsed.size)
        assertEquals(true, parsed["flag_2"])
        assertEquals(false, parsed["flag_3"])
    }
}
