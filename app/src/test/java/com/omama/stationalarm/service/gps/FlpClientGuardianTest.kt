package com.omama.stationalarm.service.gps

import android.Manifest
import android.app.Application
import android.location.Location
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.omama.stationalarm.service.Reflect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * FLP health guardian: tracks consecutive failures of one-shot recovery fixes and performs a "nuclear
 * reset" (brand-new client + fresh callback) after 3 failures, or immediately after a single 15 s timeout,
 * with a 60 s cooldown between resets.
 *
 * The FLP client inside the guardian is replaced by a [FakeFlpClient]. Real wall-clock time is NOT
 * simulated by this Robolectric setup (System.currentTimeMillis() keeps running), so cooldown tests move
 * the guardian's private `lastNukeTimestamp` instead; looper timers (the 15 s timeout) use idleFor().
 *
 * After a reset the guardian holds a REAL (GMS-less) client; tests that need to go on use [swapInFake].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FlpClientGuardianTest {

    private lateinit var app: Application
    private lateinit var fake: FakeFlpClient
    private lateinit var guardian: FlpClientGuardian
    private lateinit var request: LocationRequest
    private lateinit var initialCallback: LocationCallback

    private val recreatedClients = mutableListOf<FusedLocationProviderClient>()
    private val recoveryFixes = mutableListOf<Location>()
    private val recoveryFailures = mutableListOf<String>()
    private var callbackFactoryCalls = 0

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        fake = FakeFlpClient("original")
        guardian = FlpClientGuardian(
            context = app,
            onClientRecreated = { recreatedClients += it },
            onRecoveryFix = { recoveryFixes += it },
            onRecoveryFailed = { recoveryFailures += it }
        )
        swapInFake(fake)
        request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build()
        initialCallback = object : LocationCallback() {}
        guardian.configure(request, { callbackFactoryCalls++; object : LocationCallback() {} }, initialCallback)
    }

    @After
    fun tearDown() {
        guardian.destroy()
    }

    // ------------------------------------------------------------------------------------------ helpers

    private fun swapInFake(f: FakeFlpClient) {
        Reflect.field(FlpClientGuardian::class.java, "client").set(guardian, f.client)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advanceMs(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun location(accuracy: Float = 12f) = Location("fused").apply {
        latitude = 18.5
        longitude = 73.8
        this.accuracy = accuracy
    }

    /** Runs one recovery attempt that answers immediately with [result] (null = "no location"). */
    private fun recoverWith(f: FakeFlpClient, result: Location?) {
        f.onGetCurrentLocation = { it.setResult(result) }
        guardian.attemptRecovery()
        idle()
    }

    private fun recoverWithFailure(f: FakeFlpClient, error: Exception) {
        f.onGetCurrentLocation = { it.setException(error) }
        guardian.attemptRecovery()
        idle()
    }

    private fun rewindLastNuke(byMs: Long) {
        val f = Reflect.field(FlpClientGuardian::class.java, "lastNukeTimestamp")
        f.setLong(guardian, System.currentTimeMillis() - byMs)
    }

    // ------------------------------------------------------------------------------------------ plumbing

    @Test
    fun startLocationUpdates_beforeConfigure_doesNothing() {
        val fresh = FlpClientGuardian(app, {}, {}, {})
        Reflect.field(FlpClientGuardian::class.java, "client").set(fresh, fake.client)
        fresh.startLocationUpdates()
        assertTrue(fake.registrations.isEmpty())
        fresh.stopLocationUpdates()
        assertTrue(fake.removedCallbacks.isEmpty())
    }

    @Test
    fun startLocationUpdates_withoutPermission_doesNothing() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        guardian.startLocationUpdates()
        assertTrue(fake.registrations.isEmpty())
    }

    @Test
    fun startLocationUpdates_registersTheSavedRequestAndCallbackOnTheMainLooper() {
        guardian.startLocationUpdates()
        val reg = fake.registrations.single()
        assertSame(request, reg.request)
        assertSame(initialCallback, reg.callback)
        assertSame(Looper.getMainLooper(), reg.looper)
    }

    @Test
    fun stopLocationUpdates_removesTheCurrentCallback() {
        guardian.stopLocationUpdates()
        assertEquals(listOf(initialCallback), fake.removedCallbacks)
    }

    // ------------------------------------------------------------------------------------------ recovery bookkeeping

    @Test
    fun attemptRecovery_withoutPermission_isCompletelyInert() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        guardian.attemptRecovery()
        advanceMs(5 * 60_000L)
        assertTrue(fake.currentLocationCalls.isEmpty())
        assertEquals(0, guardian.getConsecutiveFailures())
        assertTrue(recoveryFailures.isEmpty())
        assertTrue("no timeout may have been armed", recreatedClients.isEmpty())
    }

    @Test
    fun recoveryFix_isForwardedAndResetsTheFailureStreak() {
        recoverWith(fake, null)
        recoverWith(fake, null)
        assertEquals(2, guardian.getConsecutiveFailures())

        val good = location()
        recoverWith(fake, good)

        assertEquals(0, guardian.getConsecutiveFailures())
        assertSame(good, recoveryFixes.single())
        assertSame("a healthy pipe must never be recreated", fake.client, guardian.client)
        assertTrue(recreatedClients.isEmpty())
    }

    @Test
    fun nullLocation_countsAsFailure_andIsReported() {
        recoverWith(fake, null)
        assertEquals(1, guardian.getConsecutiveFailures())
        assertEquals(listOf("null location"), recoveryFailures)
        assertTrue(recoveryFixes.isEmpty())
    }

    @Test
    fun gmsFailure_countsAsFailure_andReportsTheMessage() {
        recoverWithFailure(fake, RuntimeException("boom"))
        assertEquals(1, guardian.getConsecutiveFailures())
        assertEquals(listOf("GMS failure: boom"), recoveryFailures)
    }

    @Test
    fun twoFailures_doNotRecreateTheClient() {
        recoverWith(fake, null)
        recoverWith(fake, null)
        assertEquals(2, guardian.getConsecutiveFailures())
        assertSame(fake.client, guardian.client)
        assertTrue(recreatedClients.isEmpty())
        assertEquals(0, callbackFactoryCalls)
    }

    // ------------------------------------------------------------------------------------------ nuclear reset

    @Test
    fun threeConsecutiveNullRecoveries_recreateTheClient() {
        recoverWith(fake, null)
        recoverWith(fake, null)
        recoverWith(fake, null)

        assertNotSame("a fresh FLP client handle is the whole point of the reset", fake.client, guardian.client)
        assertEquals(1, recreatedClients.size)
        assertSame(guardian.client, recreatedClients.single())
        assertEquals("counter restarts after a reset", 0, guardian.getConsecutiveFailures())
        assertEquals(listOf("null location", "null location", "null location"), recoveryFailures)
    }

    @Test
    fun nuke_tearsDownTheOldRegistration_andBuildsAFreshCallback() {
        repeat(3) { recoverWith(fake, null) }

        assertEquals("old callback removed from the old client", listOf(initialCallback), fake.removedCallbacks)
        assertEquals("callback factory consulted exactly once", 1, callbackFactoryCalls)
        val current = Reflect.get<LocationCallback?>(guardian, "currentCallback")
        assertNotNull(current)
        assertNotSame("GMS needs a fresh callback object, not the old instance", initialCallback, current)
    }

    @Test
    fun mixedFailureKinds_accumulateTowardsTheReset() {
        recoverWith(fake, null)
        recoverWithFailure(fake, IllegalStateException("ipc"))
        assertSame(fake.client, guardian.client)
        recoverWith(fake, null)
        assertNotSame(fake.client, guardian.client)
        assertEquals(1, recreatedClients.size)
    }

    @Test
    fun aSuccessInTheMiddle_resetsTheStreak_soNoNukeHappens() {
        recoverWith(fake, null)
        recoverWith(fake, null)
        recoverWith(fake, location())
        recoverWith(fake, null)
        recoverWith(fake, null)
        assertEquals(2, guardian.getConsecutiveFailures())
        assertSame(fake.client, guardian.client)
        assertTrue(recreatedClients.isEmpty())
    }

    @Test
    fun singleTimeout_forcesAnImmediateReset_withoutWaitingForThreeFailures() {
        fake.onGetCurrentLocation = null // GMS never answers: the IPC pipe is dead
        guardian.attemptRecovery()
        idle()

        advanceMs(14_999)
        assertTrue("1 ms before the 15 s deadline nothing has happened", recoveryFailures.isEmpty())
        assertSame(fake.client, guardian.client)

        advanceMs(1)
        assertEquals(listOf("15s timeout"), recoveryFailures)
        assertNotSame(fake.client, guardian.client)
        assertEquals(1, recreatedClients.size)
        assertEquals(0, guardian.getConsecutiveFailures())
    }

    @Test
    fun answerBeforeTheDeadline_cancelsTheTimeout() {
        fake.onGetCurrentLocation = null
        guardian.attemptRecovery()
        advanceMs(10_000)

        fake.currentLocationCalls.single().source.setResult(location())
        idle()
        assertEquals(1, recoveryFixes.size)

        advanceMs(60_000)
        assertTrue(recoveryFailures.isEmpty())
        assertTrue(recreatedClients.isEmpty())
        assertSame(fake.client, guardian.client)
    }

    @Test
    fun anOrdinaryFixArriving_cancelsThePendingTimeoutAndClearsFailures() {
        recoverWith(fake, null) // 1 failure on the books
        fake.onGetCurrentLocation = null
        guardian.attemptRecovery()
        advanceMs(5_000)

        guardian.onFixReceived() // the regular FLP callback delivered a fix

        assertEquals(0, guardian.getConsecutiveFailures())
        advanceMs(60_000)
        assertEquals("only the earlier null-location failure was reported", listOf("null location"), recoveryFailures)
        assertTrue(recreatedClients.isEmpty())
    }

    @Test
    fun aNewAttempt_replacesThePreviousTimeoutAndCancelsItsToken() {
        fake.onGetCurrentLocation = null
        guardian.attemptRecovery()
        advanceMs(10_000)
        guardian.attemptRecovery() // t = 10 s: the first deadline (t = 15 s) must be gone

        assertEquals(2, fake.currentLocationCalls.size)
        assertTrue("first token cancelled", fake.currentLocationCalls[0].token!!.isCancellationRequested)
        assertFalse("second token live", fake.currentLocationCalls[1].token!!.isCancellationRequested)

        advanceMs(7_000) // t = 17 s: past the first deadline
        assertTrue(recoveryFailures.isEmpty())
        assertSame(fake.client, guardian.client)

        advanceMs(8_000) // t = 25 s: the second deadline
        assertEquals(listOf("15s timeout"), recoveryFailures)
        assertNotSame(fake.client, guardian.client)
    }

    @Test
    fun recoveryRequestsHighAccuracy() {
        fake.onGetCurrentLocation = null
        guardian.attemptRecovery()
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, fake.currentLocationCalls.single().priority)
    }

    @Test
    fun securityExceptionFromTheClient_isSwallowed_withoutCountingAFailureOrNuking() {
        fake.getCurrentLocationThrows = SecurityException("permission revoked mid-flight")
        guardian.attemptRecovery()
        advanceMs(5 * 60_000L)
        assertEquals(0, guardian.getConsecutiveFailures())
        assertTrue("its 15 s timer must have been cancelled", recoveryFailures.isEmpty())
        assertTrue(recreatedClients.isEmpty())
    }

    // ------------------------------------------------------------------------------------------ cooldown

    @Test
    fun secondTimeoutInsideTheCooldown_isReportedButDoesNotRecreateTheClientAgain() {
        fake.onGetCurrentLocation = null
        guardian.attemptRecovery()
        advanceMs(15_000)
        assertEquals(1, recreatedClients.size)

        val second = FakeFlpClient("second")
        swapInFake(second)
        guardian.attemptRecovery()
        advanceMs(15_000)

        assertEquals("the timeout is still reported", listOf("15s timeout", "15s timeout"), recoveryFailures)
        assertEquals("but GMS must not be hammered with another new client", 1, recreatedClients.size)
        assertSame(second.client, guardian.client)
        assertEquals("failure count survives a suppressed reset", 1, guardian.getConsecutiveFailures())
    }

    @Test
    fun threeFailuresInsideTheCooldown_areSuppressedToo() {
        fake.onGetCurrentLocation = null
        guardian.attemptRecovery()
        advanceMs(15_000)
        assertEquals(1, recreatedClients.size)

        val second = FakeFlpClient("second")
        swapInFake(second)
        repeat(3) { recoverWith(second, null) }
        assertEquals(1, recreatedClients.size)
        assertSame(second.client, guardian.client)
        assertEquals(3, guardian.getConsecutiveFailures())
    }

    @Test
    fun afterTheCooldownExpires_theNextTimeoutRecreatesTheClientAgain() {
        fake.onGetCurrentLocation = null
        guardian.attemptRecovery()
        advanceMs(15_000)
        assertEquals(1, recreatedClients.size)

        val second = FakeFlpClient("second")
        swapInFake(second)
        rewindLastNuke(61_000)
        guardian.attemptRecovery()
        advanceMs(15_000)

        assertEquals(2, recreatedClients.size)
        assertNotSame(second.client, guardian.client)
    }

    @Test
    fun justInsideTheCooldown_stillSuppressed() {
        fake.onGetCurrentLocation = null
        guardian.attemptRecovery()
        advanceMs(15_000)
        val second = FakeFlpClient("second")
        swapInFake(second)

        rewindLastNuke(55_000) // 5 s of margin: real time keeps running during the test
        guardian.attemptRecovery()
        advanceMs(15_000)
        assertEquals(1, recreatedClients.size)
    }

    @Test
    fun failuresAccumulatedDuringTheCooldown_triggerTheResetAsSoonAsItExpires() {
        fake.onGetCurrentLocation = null
        guardian.attemptRecovery()
        advanceMs(15_000)
        val second = FakeFlpClient("second")
        swapInFake(second)

        repeat(3) { recoverWith(second, null) } // suppressed, counter stays at 3
        assertEquals(1, recreatedClients.size)

        rewindLastNuke(61_000)
        recoverWith(second, null) // the 4th failure, cooldown over -> reset
        assertEquals(2, recreatedClients.size)
        assertEquals(0, guardian.getConsecutiveFailures())
    }

    // ------------------------------------------------------------------------------------------ nuke without wiring

    @Test
    fun resetWithoutConfigure_stillSwapsTheClient_butRegistersNothing() {
        val bare = FlpClientGuardian(app, { recreatedClients += it }, {}, { recoveryFailures += it })
        val bareFake = FakeFlpClient("bare")
        Reflect.field(FlpClientGuardian::class.java, "client").set(bare, bareFake.client)

        bareFake.onGetCurrentLocation = null
        bare.attemptRecovery()
        advanceMs(15_000)

        assertEquals(1, recreatedClients.size)
        assertNotSame(bareFake.client, bare.client)
        assertTrue("nothing to tear down: no callback was ever configured", bareFake.removedCallbacks.isEmpty())
        bare.destroy()
    }

    // ------------------------------------------------------------------------------------------ telemetry + lifecycle

    @Test
    fun clientAge_isZeroUntilAResetHappened_thenCountsSeconds() {
        assertEquals(0L, guardian.getClientAgeSeconds())

        fake.onGetCurrentLocation = null
        guardian.attemptRecovery()
        advanceMs(15_000)
        assertEquals(1, recreatedClients.size)
        assertTrue(guardian.getClientAgeSeconds() in 0L..2L)

        rewindLastNuke(125_000)
        assertTrue("age=${guardian.getClientAgeSeconds()}", guardian.getClientAgeSeconds() in 125L..130L)
    }

    @Test
    fun noRecoveryAttempt_noReset_nomatterHowMuchTimePasses() {
        // The guardian is reactive: with nobody calling attemptRecovery() (no stall detected) it must stay quiet.
        advanceMs(30 * 60_000L)
        assertTrue(recreatedClients.isEmpty())
        assertTrue(recoveryFailures.isEmpty())
        assertSame(fake.client, guardian.client)
        assertTrue(fake.currentLocationCalls.isEmpty())
    }

    @Test
    fun destroy_cancelsTheDeadline_theTokenAndTheRegistration() {
        fake.onGetCurrentLocation = null
        guardian.attemptRecovery()
        guardian.destroy()

        assertTrue(fake.currentLocationCalls.single().token!!.isCancellationRequested)
        assertEquals(listOf(initialCallback), fake.removedCallbacks)

        advanceMs(5 * 60_000L)
        assertTrue("a destroyed guardian must not nuke from beyond the grave", recreatedClients.isEmpty())
        assertTrue(recoveryFailures.isEmpty())
    }
}
