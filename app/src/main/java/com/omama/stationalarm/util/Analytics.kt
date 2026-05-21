package com.omama.stationalarm.util

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Thin wrapper around [FirebaseAnalytics] for countable business events.
 * Every call is swallow-on-throw so Firebase outages, missing
 * `google-services.json`, or an uninitialised [FirebaseApp] can never crash
 * a caller. Debug builds still emit to Firebase DebugView for verification —
 * production filtering happens server-side at the Firebase project level.
 *
 * Conventions:
 *   - Event names: snake_case, past tense for actions ("alarm_fired"),
 *     present tense for setup ("permission_denied"). Keep stable: dashboards
 *     and Audiences key off them.
 *   - Param names: snake_case. Numeric values prefer banded strings
 *     ("0", "1", "2-5", "6+") over raw counts when used as Audience filters,
 *     since BigQuery aggregation handles raw integers but the in-product
 *     Audience builder treats every distinct integer as its own bucket.
 *   - User properties (set once or on state change) capture device class
 *     and permission state so dashboards can be sliced by them.
 */
object Analytics {

    @Volatile private var analytics: FirebaseAnalytics? = null

    fun initialize(context: Context) {
        try {
            analytics = FirebaseAnalytics.getInstance(context.applicationContext)
        } catch (e: Exception) {
            // Firebase failed to initialize earlier — log and continue.
            // Subsequent event()/setUserProperty() calls become no-ops.
            android.util.Log.w("Analytics", "FirebaseAnalytics unavailable", e)
        }
    }

    /** Logs a Firebase Analytics event. Safe to call before [initialize] (no-op). */
    fun event(name: String, params: (Bundle.() -> Unit)? = null) {
        val fa = analytics ?: return
        try {
            val bundle = if (params != null) Bundle().apply(params) else null
            fa.logEvent(name, bundle)
        } catch (_: Exception) {
            // Firebase calls should never propagate failures upward.
        }
    }

    /** Sets a Firebase Analytics user property (max 25 per app). Pass null to clear. */
    fun setUserProperty(name: String, value: String?) {
        val fa = analytics ?: return
        try {
            fa.setUserProperty(name, value)
        } catch (_: Exception) {
        }
    }
}
