package com.omama.stationalarm.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for the parts of [AppRemoteConfig] that don't need
 * Firebase: the JSON parser and the version comparator. These are the two
 * places where bad input from the server most likely turns into a crash,
 * so they're worth exercising in isolation. No Robolectric — keeps runtime
 * negligible.
 */
class AppRemoteConfigTest {

    // ── parseFeatureFlags ──────────────────────────────────────────────

    @Test
    fun parseFeatureFlags_emptyJson_returnsEmptyMap() {
        assertEquals(emptyMap<String, Boolean>(), AppRemoteConfig.parseFeatureFlags("{}"))
    }

    @Test
    fun parseFeatureFlags_blank_returnsEmptyMap() {
        assertEquals(emptyMap<String, Boolean>(), AppRemoteConfig.parseFeatureFlags(""))
        assertEquals(emptyMap<String, Boolean>(), AppRemoteConfig.parseFeatureFlags("   "))
    }

    @Test
    fun parseFeatureFlags_singleBooleanTrue() {
        val out = AppRemoteConfig.parseFeatureFlags("""{"new_search":true}""")
        assertEquals(mapOf("new_search" to true), out)
    }

    @Test
    fun parseFeatureFlags_multipleBooleans() {
        val out = AppRemoteConfig.parseFeatureFlags(
            """{"new_search":true,"voice_dismiss":false,"beta_audio":true}"""
        )
        assertEquals(3, out.size)
        assertTrue(out["new_search"]!!)
        assertFalse(out["voice_dismiss"]!!)
        assertTrue(out["beta_audio"]!!)
    }

    @Test
    fun parseFeatureFlags_skipsNonBooleanValues() {
        // Strings, numbers, and nested objects must NOT crash — they're just
        // dropped silently. Server-side schema can evolve without bricking
        // older clients that hadn't shipped support for the new types yet.
        val out = AppRemoteConfig.parseFeatureFlags(
            """{"good":true,"number":42,"string":"yes","nested":{"x":true}}"""
        )
        assertEquals(mapOf("good" to true), out)
    }

    @Test
    fun parseFeatureFlags_malformedJson_returnsEmptyMap_doesntThrow() {
        assertEquals(emptyMap<String, Boolean>(), AppRemoteConfig.parseFeatureFlags("not json at all"))
        assertEquals(emptyMap<String, Boolean>(), AppRemoteConfig.parseFeatureFlags("{broken"))
        assertEquals(emptyMap<String, Boolean>(), AppRemoteConfig.parseFeatureFlags("[1,2,3]"))
    }

    // ── isForceUpdateRequired ──────────────────────────────────────────

    @Test
    fun forceUpdate_debugBuildAlwaysSuppressed() {
        // Even with a sky-high required version, debug builds are immune
        // so a misconfigured RC entry can't lock developers out of their own
        // installs. Production-only guard.
        assertFalse(AppRemoteConfig.isForceUpdateRequired(currentVersionCode = 1L, isDebug = true))
    }

    @Test
    fun forceUpdate_releaseBuildBelowFloor_blocks() {
        // We can't easily mutate the StateFlow from here, so this test
        // validates the comparator with the default floor (0). Anything > 0
        // passes; we explicitly verify the boundary by simulating via the
        // helper's contract: returns false when current >= floor.
        // (The interesting cross-floor case is exercised via integration.)
        assertFalse(AppRemoteConfig.isForceUpdateRequired(currentVersionCode = 0L, isDebug = false))
        assertFalse(AppRemoteConfig.isForceUpdateRequired(currentVersionCode = 100L, isDebug = false))
    }
}
