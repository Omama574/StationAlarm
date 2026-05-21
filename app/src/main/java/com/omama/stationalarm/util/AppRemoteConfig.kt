package com.omama.stationalarm.util

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin wrapper around Firebase Remote Config for app-controlled emergency
 * knobs. The geocoding backend URL is handled separately in [com.omama.stationalarm.network.GeocodingClient]
 * because it must apply at Retrofit-build time; everything user-facing lives here.
 *
 * Keys:
 *   - `min_supported_app_version` (long) — if BuildConfig.VERSION_CODE is
 *     below this, the app shows a blocking "Please update" screen.
 *   - `maintenance_mode` (boolean) — when true, a sticky banner appears at
 *     the top of the main screen with [maintenanceMessageFlow] as body.
 *   - `maintenance_message` (string) — body text for the banner. Defaults
 *     to a generic line if empty.
 *   - `feature_flags` (string JSON) — map of feature-flag-name -> Boolean.
 *     Malformed JSON parses to an empty map (never crashes a caller).
 *
 * All flows hold the *last fetched* value. Initial value is the baked-in
 * default; production fetch happens at ~12h cadence (debug: 30s).
 *
 * Every call is swallow-on-throw so a Firebase outage or bad init can never
 * propagate into UI code.
 */
object AppRemoteConfig {

    // ── Default values mirrored from StationAlarmApplication.setDefaultsAsync.
    // Keep them in sync so a cache miss has predictable behaviour. ──────────
    const val KEY_MIN_SUPPORTED_VERSION = "min_supported_app_version"
    const val KEY_MAINTENANCE_MODE      = "maintenance_mode"
    const val KEY_MAINTENANCE_MESSAGE   = "maintenance_message"
    const val KEY_FEATURE_FLAGS         = "feature_flags"

    const val DEFAULT_MIN_SUPPORTED_VERSION: Long = 0L
    const val DEFAULT_MAINTENANCE_MODE: Boolean = false
    const val DEFAULT_MAINTENANCE_MESSAGE: String = ""
    const val DEFAULT_FEATURE_FLAGS: String = "{}"

    /** Fallback shown when maintenance_mode is true but maintenance_message is empty. */
    const val FALLBACK_MAINTENANCE_BODY = "Service temporarily unavailable. We're working on it."

    private val _minSupportedAppVersion = MutableStateFlow(DEFAULT_MIN_SUPPORTED_VERSION)
    val minSupportedAppVersionFlow: StateFlow<Long> = _minSupportedAppVersion.asStateFlow()

    private val _maintenanceMode = MutableStateFlow(DEFAULT_MAINTENANCE_MODE)
    val maintenanceModeFlow: StateFlow<Boolean> = _maintenanceMode.asStateFlow()

    private val _maintenanceMessage = MutableStateFlow(DEFAULT_MAINTENANCE_MESSAGE)
    val maintenanceMessageFlow: StateFlow<String> = _maintenanceMessage.asStateFlow()

    private val _featureFlags = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val featureFlagsFlow: StateFlow<Map<String, Boolean>> = _featureFlags.asStateFlow()

    /**
     * Pulls the latest RC values into the local flows. Safe to call multiple
     * times — typically once after `fetchAndActivate()` completes in
     * [com.omama.stationalarm.StationAlarmApplication].
     */
    fun refresh(rc: FirebaseRemoteConfig) {
        try {
            _minSupportedAppVersion.value = rc.getLong(KEY_MIN_SUPPORTED_VERSION)
            _maintenanceMode.value = rc.getBoolean(KEY_MAINTENANCE_MODE)
            _maintenanceMessage.value = rc.getString(KEY_MAINTENANCE_MESSAGE)
            _featureFlags.value = parseFeatureFlags(rc.getString(KEY_FEATURE_FLAGS))
        } catch (_: Exception) {
            // Leave the existing values intact — better stale than crashing.
        }
    }

    /**
     * Compares the running build against the Remote Config floor.
     * In debug builds, force-update is suppressed so a misconfigured RC
     * value can't lock developers out of their own dev installs.
     */
    fun isForceUpdateRequired(currentVersionCode: Long, isDebug: Boolean): Boolean {
        if (isDebug) return false
        return currentVersionCode < _minSupportedAppVersion.value
    }

    /**
     * Parses the `feature_flags` JSON blob. Accepts a flat object whose
     * values are booleans; ignores entries with non-boolean values. Returns
     * an empty map for any parse failure rather than throwing — feature
     * flags should never crash the app, only fail closed.
     *
     * Uses Gson rather than [org.json.JSONObject] because the latter is a
     * stub on the pure-JVM unit-test classpath and silently returns empty.
     */
    internal fun parseFeatureFlags(json: String): Map<String, Boolean> {
        if (json.isBlank()) return emptyMap()
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return emptyMap()
            val out = mutableMapOf<String, Boolean>()
            for ((key, value) in root.asJsonObject.entrySet()) {
                if (value.isJsonPrimitive) {
                    val prim = value.asJsonPrimitive
                    if (prim.isBoolean) out[key] = prim.asBoolean
                }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
