package com.omama.stationalarm.service.gps

import android.location.Location
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omama.stationalarm.service.gps.FixQualityGate.Decision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Distance-banded accuracy gate. Needs Robolectric only because it takes an android.location.Location.
 *
 * Bands (accuracy threshold in metres, by distance to the nearest station):
 *   > 60 km: accept anything | (30, 60]: 2000 | (15, 30]: 1000 | (5, 15]: 500 | <= 5 km: 200
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FixQualityGateTest {

    private var savedLocale: Locale? = null

    @Before
    fun pinLocale() {
        // The demote reason string uses String.format("%.1f", ...), which is locale sensitive.
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        savedLocale?.let { Locale.setDefault(it) }
    }

    private fun fix(accuracyM: Float): Location = Location("fused").apply {
        latitude = 18.5292
        longitude = 73.8729
        accuracy = accuracyM
    }

    private fun evaluate(accuracyM: Float, distanceKm: Double): Decision =
        FixQualityGate.evaluate(fix(accuracyM), distanceKm)

    private fun assertAccept(decision: Decision, expectedEffective: Float, message: String = "") {
        assertTrue("$message expected Accept but was $decision", decision is Decision.Accept)
        assertEquals(message, expectedEffective, (decision as Decision.Accept).effectiveAccuracyMeters, 0f)
    }

    private fun assertDemote(decision: Decision, expectedEffective: Float, message: String = "") {
        assertTrue("$message expected DemoteToHint but was $decision", decision is Decision.DemoteToHint)
        assertEquals(message, expectedEffective, (decision as Decision.DemoteToHint).effectiveAccuracyMeters, 0f)
    }

    // ------------------------------------------------------------------------------------------
    //  Thresholds per band
    // ------------------------------------------------------------------------------------------

    @Test
    fun everyBand_acceptsAtTheThreshold_andDemotesOneUlpAbove() {
        // (a distance strictly inside the band, the band's accuracy threshold)
        val cases = listOf(
            0.0 to 200f, 0.001 to 200f, 2.5 to 200f, 4.999 to 200f,
            5.001 to 500f, 10.0 to 500f, 14.999 to 500f,
            15.001 to 1000f, 20.0 to 1000f, 29.999 to 1000f,
            30.001 to 2000f, 45.0 to 2000f, 59.999 to 2000f
        )
        for ((distanceKm, threshold) in cases) {
            assertAccept(evaluate(threshold, distanceKm), threshold, "dist=$distanceKm acc==threshold")
            val justAbove = Math.nextUp(threshold)
            assertDemote(evaluate(justAbove, distanceKm), justAbove, "dist=$distanceKm acc=nextUp(threshold)")
        }
    }

    @Test
    fun bandEdges_belongToTheNearerStricterBand() {
        // Exactly 5 / 15 / 30 / 60 km still use the nearer band's (smaller) threshold ...
        assertDemote(evaluate(201f, 5.0), 201f, "exactly 5 km uses the 200 m threshold")
        assertDemote(evaluate(501f, 15.0), 501f, "exactly 15 km uses the 500 m threshold")
        assertDemote(evaluate(1001f, 30.0), 1001f, "exactly 30 km uses the 1000 m threshold")
        assertDemote(evaluate(2001f, 60.0), 2001f, "exactly 60 km uses the 2000 m threshold")
        // ... and the first representable distance beyond the edge switches to the looser band.
        assertAccept(evaluate(201f, Math.nextUp(5.0)), 201f, "just beyond 5 km -> 500 m threshold")
        assertAccept(evaluate(501f, Math.nextUp(15.0)), 501f, "just beyond 15 km -> 1000 m threshold")
        assertAccept(evaluate(1001f, Math.nextUp(30.0)), 1001f, "just beyond 30 km -> 2000 m threshold")
        assertAccept(evaluate(2001f, Math.nextUp(60.0)), 2001f, "just beyond 60 km -> accept anything")
    }

    @Test
    fun beyond60Km_acceptsAnyFiniteAccuracy() {
        val distances = listOf(Math.nextUp(60.0), 100.0, 1_000.0, 20_000.0, Double.MAX_VALUE, Double.POSITIVE_INFINITY)
        val accuracies = listOf(0f, 10f, 2001f, 5_000f, 100_000f, 1e9f, Float.MAX_VALUE)
        for (d in distances) {
            for (a in accuracies) {
                assertAccept(evaluate(a, d), a, "dist=$d acc=$a")
            }
        }
    }

    @Test
    fun noStationsMonitored_sentinelDistance_acceptsEverything() {
        // The KDoc says to pass Double.MAX_VALUE when no stations are monitored.
        assertAccept(evaluate(9_999f, Double.MAX_VALUE), 9_999f)
    }

    // ------------------------------------------------------------------------------------------
    //  Degenerate inputs
    // ------------------------------------------------------------------------------------------

    @Test
    fun zeroAccuracy_isAcceptedInEveryBand() {
        // Location.getAccuracy() is 0.0 when the provider reports no accuracy at all.
        for (d in listOf(0.0, 4.0, 10.0, 20.0, 45.0, 500.0)) {
            assertAccept(evaluate(0f, d), 0f, "dist=$d")
        }
    }

    @Test
    fun negativeAccuracy_isTreatedAsGood_notDemoted() {
        for (d in listOf(0.0, 10.0, 100.0)) {
            val decision = evaluate(-5f, d)
            assertTrue("dist=$d: $decision", decision is Decision.Accept)
            assertFalse((decision as Decision.Accept).effectiveAccuracyMeters.isNaN())
        }
    }

    @Test
    fun zeroOrNegativeDistance_usesTheStrictestAlarmZoneThreshold() {
        assertAccept(evaluate(200f, 0.0), 200f)
        assertDemote(evaluate(201f, 0.0), 201f)
        assertAccept(evaluate(200f, -3.0), 200f)
        assertDemote(evaluate(201f, -3.0), 201f)
    }

    @Test
    fun nanDistance_fallsIntoTheStrictestBand() {
        // Every `>` comparison with NaN is false, so the `else` (<= 5 km, 200 m) branch wins.
        assertAccept(evaluate(150f, Double.NaN), 150f)
        assertDemote(evaluate(300f, Double.NaN), 300f)
    }

    @Test
    fun nanAccuracy_isNeverAccepted() {
        for (d in listOf(1.0, 10.0, 100.0)) {
            val decision = evaluate(Float.NaN, d)
            assertTrue("NaN accuracy at dist=$d must not be Accepted: $decision", decision is Decision.DemoteToHint)
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Contract of a demoted fix
    // ------------------------------------------------------------------------------------------

    @Test
    fun demotedFix_neverReportsBetterAccuracyThanTheFixItself() {
        val accuracies = listOf(0f, 50f, 200f, 201f, 500f, 501f, 1000f, 1001f, 2000f, 2001f, 5000f, 60_000f)
        val distances = listOf(0.5, 5.0, 5.5, 15.0, 16.0, 30.0, 31.0, 60.0, 61.0)
        for (d in distances) {
            for (a in accuracies) {
                when (val decision = evaluate(a, d)) {
                    is Decision.Accept -> assertEquals("dist=$d acc=$a", a, decision.effectiveAccuracyMeters, 0f)
                    is Decision.DemoteToHint ->
                        assertTrue(
                            "dist=$d acc=$a effective=${decision.effectiveAccuracyMeters}",
                            decision.effectiveAccuracyMeters >= a
                        )
                }
            }
        }
    }

    @Test
    fun characterisation_demoteKeepsTheReportedAccuracy_soBothDecisionsFeedTheSameAlarmArithmetic() {
        // The KDoc says a demoted fix has its accuracy "inflated to the demote threshold" and "cannot trigger the
        // alarm". In fact accuracy > threshold by definition, so maxOf(accuracy, threshold) == accuracy: Accept
        // and DemoteToHint hand LocationService exactly the same number, and the only difference is a log line /
        // CSV column. Pinned so a real "inflation" or "cannot trigger" implementation is a conscious change.
        for ((accuracy, distanceKm) in listOf(201f to 1.0, 501f to 10.0, 1001f to 20.0, 2001f to 40.0, 9_999f to 4.0)) {
            val decision = evaluate(accuracy, distanceKm) as Decision.DemoteToHint
            assertEquals("acc=$accuracy dist=$distanceKm", accuracy, decision.effectiveAccuracyMeters, 0f)
        }
    }

    @Test
    fun demoteReason_namesAccuracyThresholdAndDistance() {
        val decision = evaluate(750.5f, 12.345) as Decision.DemoteToHint
        assertTrue(decision.reason, decision.reason.contains("accuracy=750.5m"))
        assertTrue(decision.reason, decision.reason.contains("threshold=500.0m"))
        assertTrue(decision.reason, decision.reason.contains("dist=12.3km"))
    }

    @Test
    fun evaluate_doesNotMutateTheLocation() {
        val location = fix(1234f)
        FixQualityGate.evaluate(location, 3.0)
        assertEquals(1234f, location.accuracy, 0f)
        assertEquals(18.5292, location.latitude, 0.0)
    }

    // ------------------------------------------------------------------------------------------
    //  Non-finite accuracy must not leak into the alarm arithmetic
    // ------------------------------------------------------------------------------------------

    @Ignore("BUG: FixQualityGate.kt:62 maxOf(NaN, threshold) = NaN, so a NaN-accuracy fix returns DemoteToHint(NaN); LocationService.kt:526 then computes effectiveDistance = NaN and both `<= alertDistanceKm` (540) and `> alertDistanceKm` (536) are false, so that fix can never ring the alarm")
    @Test
    fun nanAccuracy_mustYieldAFiniteEffectiveAccuracy() {
        // LocationService computes effectiveDistance = distance - effectiveAccuracy/1000 and compares it
        // with `<= alertDistanceKm` / `> alertDistanceKm`. A NaN here makes BOTH comparisons false forever.
        for (d in listOf(1.0, 10.0, 100.0)) {
            val effective = when (val decision = evaluate(Float.NaN, d)) {
                is Decision.Accept -> decision.effectiveAccuracyMeters
                is Decision.DemoteToHint -> decision.effectiveAccuracyMeters
            }
            assertTrue("dist=$d effectiveAccuracyMeters=$effective", effective.isFinite())
        }
    }

    @Ignore("BUG: FixQualityGate.kt:62 maxOf(+Infinity, threshold) = +Infinity (and at >60 km `Inf <= Float.MAX_VALUE` is false), so LocationService.kt:526 computes effectiveDistance = -Infinity, which satisfies `<= alertDistanceKm` at any distance: a false alarm")
    @Test
    fun infiniteAccuracy_mustYieldAFiniteEffectiveAccuracy() {
        // +Infinity accuracy makes effectiveDistance = -Infinity, which satisfies `<= alertDistanceKm`
        // at ANY distance, i.e. an alarm at any distance.
        for (d in listOf(1.0, 10.0, 100.0)) {
            val effective = when (val decision = evaluate(Float.POSITIVE_INFINITY, d)) {
                is Decision.Accept -> decision.effectiveAccuracyMeters
                is Decision.DemoteToHint -> decision.effectiveAccuracyMeters
            }
            assertTrue("dist=$d effectiveAccuracyMeters=$effective", effective.isFinite())
        }
    }
}
