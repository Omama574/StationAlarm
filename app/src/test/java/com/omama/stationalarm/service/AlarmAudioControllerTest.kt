package com.omama.stationalarm.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [AlarmAudioController] routing, focus, fallback and timeout behaviour, driven through its public API with
 * Robolectric's AudioManager / MediaPlayer shadows.
 *
 * Fixture note: the stock `AudioDeviceInfoBuilder` creates a port without handle/role, which makes
 * `AudioDeviceInfo.getId()` NPE and `isSink()` false (the controller reads `id`, and MediaPlayer.setPreferredDevice
 * rejects non-sinks). [device] patches both so a fake device behaves like a real output.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class, shadows = [ShadowAudioManagerAsyncDeviceCallbacks::class])
class AlarmAudioControllerTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var controller: AlarmAudioController
    private lateinit var defaultUri: Uri

    private val created = CopyOnWriteArrayList<MediaPlayer>()
    private val scopes = CopyOnWriteArrayList<Job>()

    private lateinit var speaker: AudioDeviceInfo
    private lateinit var bluetooth: AudioDeviceInfo
    private lateinit var wired: AudioDeviceInfo
    private lateinit var usb: AudioDeviceInfo

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, 1)
        bluetooth = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 2)
        wired = device(AudioDeviceInfo.TYPE_WIRED_HEADSET, 3)
        usb = device(AudioDeviceInfo.TYPE_USB_HEADSET, 4)
        controller = AlarmAudioController(context)
        defaultUri = requireNotNull(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        ShadowMediaPlayer.setCreateListener { player, _ -> created += player }
        setOutputs(speaker)
        registerPlayable(defaultUri)
    }

    @After
    fun tearDown() {
        controller.stopAll()
        scopes.forEach { it.cancel() }
        ShadowMediaPlayer.setCreateListener(null)
    }

    // ------------------------------------------------------------------ fixtures / helpers

    private fun device(type: Int, id: Int): AudioDeviceInfo {
        val info = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val port = fieldOf(info, "mPort")
        val handle = Class.forName("android.media.AudioHandle")
            .getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(id)
        setField(port, "mHandle", handle)
        setField(port, "mRole", 2) // AudioPort.ROLE_SINK
        return info
    }

    private fun fieldOf(target: Any, name: String): Any = findField(target.javaClass, name).get(target)

    private fun setField(target: Any, name: String, value: Any) = findField(target.javaClass, name).set(target, value)

    private fun findField(start: Class<*>, name: String): java.lang.reflect.Field {
        var c: Class<*>? = start
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun registerPlayable(uri: Uri) =
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(60_000, 0))

    private fun setOutputs(vararg devices: AudioDeviceInfo) = shadowOf(audioManager).setOutputDevices(devices.toList())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun advanceSeconds(s: Long) = shadowOf(Looper.getMainLooper()).idleFor(s, TimeUnit.SECONDS)

    private fun connect(d: AudioDeviceInfo) {
        shadowOf(audioManager).addOutputDevice(d, true)
        idle()
    }

    private fun disconnect(d: AudioDeviceInfo) {
        shadowOf(audioManager).removeOutputDevice(d, true)
        idle()
    }

    private fun start(
        ringSpeakerWithHeadphones: Boolean = true,
        uri: Uri? = null,
        escalating: Boolean = false,
        rampSecs: Int = 0,
        onTerminalFailure: (() -> Unit)? = null
    ) {
        controller.playAlarmSound(uri, ringSpeakerWithHeadphones, escalating, rampSecs, onTerminalFailure)
        idle()
    }

    private fun diag(): String {
        val logs = org.robolectric.shadows.ShadowLog.getLogs()
            .filter { it.tag == "AlarmAudioController" }
            .joinToString(" | ") { it.msg + (it.throwable?.let { t -> " <$t>" } ?: "") }
        val states = created.map { shadowOf(it).state.toString() + ":" + shadowOf(it).sourceUri }
        return " [logs=$logs; players=$states]"
    }

    /** Players that are actually playing. */
    private fun playing(): List<MediaPlayer> =
        created.filter { shadowOf(it).state == ShadowMediaPlayer.State.STARTED }

    /** Players that were created and not yet released. */
    private fun alive(): List<MediaPlayer> =
        created.filter { shadowOf(it).state != ShadowMediaPlayer.State.END }

    private fun preferredTypes(): Set<Int> = alive().mapNotNull { it.preferredDevice?.type }.toSet()

    private fun playerOn(type: Int): MediaPlayer? = alive().firstOrNull { it.preferredDevice?.type == type }

    // ------------------------------------------------------------------ basic playback

    @Test
    fun playAlarmSound_withOnlyTheSpeaker_startsOneLoopingPlayerOnTheDefaultAlarmUri() {
        start()

        val players = alive()
        assertEquals(1, players.size)
        val shadow = shadowOf(players[0])
        assertEquals(ShadowMediaPlayer.State.STARTED, shadow.state)
        assertTrue(players[0].isLooping)
        assertEquals(defaultUri, shadow.sourceUri)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, players[0].preferredDevice?.type)
        assertTrue(controller.isAlarmRinging)
    }

    @Test
    fun playAlarmSound_requestsExclusiveTransientAlarmAudioFocus() {
        start()

        val request: AudioFocusRequest = requireNotNull(shadowOf(audioManager).lastAudioFocusRequest).audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, request.focusGain)
        assertEquals(AudioAttributes.USAGE_ALARM, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, request.audioAttributes.contentType)
    }

    @Test
    fun playAlarmSound_forcesTheAlarmStreamToMaximumVolume() {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)

        start()

        assertEquals(
            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        )
    }

    @Test
    fun playAlarmSound_whileAlreadyRinging_isIgnored() {
        start()
        val firstFocus = shadowOf(audioManager).lastAudioFocusRequest
        val firstPlayers = alive()

        start()

        assertEquals(firstPlayers, alive())
        assertSame(firstFocus, shadowOf(audioManager).lastAudioFocusRequest)
        assertEquals(1, created.size)
    }

    // ------------------------------------------------------------------ routing: dual routing and preference

    @Test
    fun withBluetoothConnected_andRingSpeakerOn_alarmPlaysOnSpeakerAndBluetoothAtTheSameTime() {
        setOutputs(speaker, bluetooth)

        start(ringSpeakerWithHeadphones = true)

        assertEquals(2, alive().size)
        assertEquals(
            setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            preferredTypes()
        )
        alive().forEach { assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(it).state) }
    }

    @Test
    fun withBluetoothConnected_andRingSpeakerOff_alarmPlaysOnBluetoothOnly() {
        setOutputs(speaker, bluetooth)

        start(ringSpeakerWithHeadphones = false)

        assertEquals(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), preferredTypes())
        assertEquals(1, alive().size)
    }

    @Test
    fun withWiredHeadset_andRingSpeakerOff_alarmPlaysOnTheHeadsetOnly() {
        setOutputs(speaker, wired)

        start(ringSpeakerWithHeadphones = false)

        assertEquals(setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET), preferredTypes())
    }

    @Test
    fun withoutAnyExternalDevice_alarmUsesTheSpeakerEvenWhenRingSpeakerIsOff() {
        setOutputs(speaker)

        start(ringSpeakerWithHeadphones = false)

        assertEquals(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), preferredTypes())
        assertTrue("an alarm must never be silent", controller.isAlarmRinging)
    }

    @Test
    fun whenSeveralExternalDevicesAreConnected_bluetoothBeatsWiredBeatsUsb() {
        setOutputs(speaker, usb, wired, bluetooth)
        start(ringSpeakerWithHeadphones = false)
        assertEquals(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), preferredTypes())
        controller.stopAlarmSound()

        setOutputs(speaker, usb, wired)
        start(ringSpeakerWithHeadphones = false)
        assertEquals(setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET), preferredTypes())
        controller.stopAlarmSound()

        setOutputs(speaker, usb)
        start(ringSpeakerWithHeadphones = false)
        assertEquals(setOf(AudioDeviceInfo.TYPE_USB_HEADSET), preferredTypes())
    }

    @Test
    fun bluetoothScoAndTelephonyOutputs_areNotTreatedAsExternalAlarmRoutes() {
        setOutputs(speaker, device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 9), device(AudioDeviceInfo.TYPE_TELEPHONY, 10))

        start(ringSpeakerWithHeadphones = false)

        assertEquals(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), preferredTypes())
        assertEquals(1, alive().size)
    }

    // ------------------------------------------------------------------ routing: devices changing mid-alarm

    @Test
    fun bluetoothDisconnectsMidAlarm_withRingSpeakerOn_speakerKeepsRinging_andBluetoothPlayerIsReleased() {
        setOutputs(speaker, bluetooth)
        start(ringSpeakerWithHeadphones = true)
        val speakerPlayer = requireNotNull(playerOn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        val bluetoothPlayer = requireNotNull(playerOn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))

        disconnect(bluetooth)

        assertEquals(listOf(speakerPlayer), alive())
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(speakerPlayer).state)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(bluetoothPlayer).state)
        assertTrue(controller.isAlarmRinging)
    }

    @Test
    fun bluetoothDisconnectsMidAlarm_withRingSpeakerOff_alarmMovesToTheSpeakerInsteadOfGoingSilent() {
        setOutputs(speaker, bluetooth)
        start(ringSpeakerWithHeadphones = false)
        assertEquals(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), preferredTypes())

        disconnect(bluetooth)

        assertEquals(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), preferredTypes())
        assertEquals(1, alive().size)
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(alive()[0]).state)
        assertTrue(controller.isAlarmRinging)
    }

    @Test
    fun bluetoothConnectsMidAlarm_withRingSpeakerOn_addsAnExternalRoute_andKeepsTheSpeakerPlayer() {
        start(ringSpeakerWithHeadphones = true)
        val speakerPlayer = alive().single()

        connect(bluetooth)

        assertEquals(2, alive().size)
        assertSame("speaker player must not be restarted", speakerPlayer, playerOn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertNotNull(playerOn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
    }

    @Test
    fun bluetoothConnectsMidAlarm_withRingSpeakerOff_movesTheAlarmToBluetoothOnly() {
        start(ringSpeakerWithHeadphones = false)
        val speakerPlayer = alive().single()

        connect(bluetooth)

        assertEquals(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), preferredTypes())
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(speakerPlayer).state)
    }

    @Test
    fun externalDeviceReplacedByAnotherOne_rebuildsTheExternalRoute() {
        val earbuds = device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 20)
        setOutputs(speaker, bluetooth)
        start(ringSpeakerWithHeadphones = true)
        val firstExternal = requireNotNull(playerOn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))

        // one device-change event that both removes and adds an output
        shadowOf(audioManager).removeOutputDevice(bluetooth, false)
        connect(earbuds)

        val secondExternal = requireNotNull(playerOn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(20, secondExternal.preferredDevice?.id)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(firstExternal).state)
    }

    @Test
    fun irrelevantDeviceChangeMidAlarm_doesNotRestartRunningPlayers() {
        setOutputs(speaker, bluetooth)
        start(ringSpeakerWithHeadphones = true)
        val before = alive()

        connect(device(AudioDeviceInfo.TYPE_TELEPHONY, 99))

        assertEquals(before, alive())
        assertEquals(before.size, created.size)
    }

    // ------------------------------------------------------------------ stop / focus / callback lifecycle

    @Test
    fun stopAlarmSound_releasesEveryPlayer_abandonsFocus_andStopsReactingToDevices() {
        setOutputs(speaker, bluetooth)
        start(ringSpeakerWithHeadphones = true)
        val focusRequest = requireNotNull(shadowOf(audioManager).lastAudioFocusRequest).audioFocusRequest

        controller.stopAlarmSound()

        assertFalse(controller.isAlarmRinging)
        assertEquals(emptyList<MediaPlayer>(), alive())
        assertSame(focusRequest, shadowOf(audioManager).lastAbandonedAudioFocusRequest)

        val createdBefore = created.size
        connect(device(AudioDeviceInfo.TYPE_WIRED_HEADSET, 77))
        disconnect(bluetooth)
        assertEquals("device callback must be unregistered after stop", createdBefore, created.size)
    }

    @Test
    fun stopAlarmSound_whenNothingIsRinging_isANoOp() {
        controller.stopAlarmSound()

        assertNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        assertFalse(controller.isAlarmRinging)
    }

    @Test
    fun alarmCanBeRestartedAfterStop() {
        start()
        controller.stopAlarmSound()

        start()

        assertEquals(1, alive().size)
        assertTrue(controller.isAlarmRinging)
    }

    @Test
    fun audioFocusLoss_neverStopsTheAlarm() {
        start()
        val listener = focusListener()

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertTrue(controller.isAlarmRinging)
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(alive().single()).state)
    }

    @Test
    fun audioFocusGain_resumesAPausedPlayer() {
        start()
        val player = alive().single()
        shadowOf(player).state = ShadowMediaPlayer.State.PAUSED

        focusListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(player).state)
    }

    private fun focusListener(): AudioManager.OnAudioFocusChangeListener {
        val request = requireNotNull(shadowOf(audioManager).lastAudioFocusRequest).audioFocusRequest
        val getter = AudioFocusRequest::class.java.getDeclaredMethod("getOnAudioFocusChangeListener")
            .apply { isAccessible = true }
        return getter.invoke(request) as AudioManager.OnAudioFocusChangeListener
    }

    // ------------------------------------------------------------------ fallback to default sound

    @Test
    fun unplayableCustomUri_fallsBackToTheDefaultAlarmSound_withoutReportingFailure() {
        val failures = AtomicInteger()
        val custom = Uri.parse("content://revoked.provider/alarm.mp3") // never registered => setDataSource throws

        start(uri = custom, onTerminalFailure = { failures.incrementAndGet() })

        assertEquals(diag(), 1, playing().size)
        assertEquals(defaultUri, shadowOf(playing().single()).sourceUri)
        assertTrue(controller.isAlarmRinging)
        assertEquals(0, failures.get())
    }

    @Ignore(
        "BUG: AlarmAudioController.kt:266-295 createRoutePlayer() does `val mp = MediaPlayer()` and then " +
            "mp.setDataSource(...); when setDataSource (or setAudioAttributes) throws, the catch block at 292-295 " +
            "only logs and returns null - the half-built MediaPlayer is never released. This is the path taken when " +
            "the user's custom ringtone URI is revoked or deleted (the very case the fallback exists for), so every " +
            "failed attempt strands one native MediaPlayer until GC. Expected: only the playing player is left " +
            "un-released. Actual: the failed attempt's player stays in IDLE state, un-released."
    )
    @Test
    fun afterAFailedRouteCreation_noHalfBuiltMediaPlayerIsLeft() {
        val custom = Uri.parse("content://revoked.provider/alarm.mp3")

        start(uri = custom)

        assertEquals(diag(), 1, playing().size)
        assertEquals("the failed custom attempt must release its player" + diag(), 1, alive().size)
    }

    @Test
    fun playableCustomUri_isUsed() {
        val custom = Uri.parse("content://media/external/audio/media/42")
        registerPlayable(custom)

        start(uri = custom)

        assertEquals(diag(), custom, shadowOf(alive().single()).sourceUri)
    }

    @Test
    fun customUriThatFailsWhilePlaying_switchesToTheDefaultSound() {
        val custom = Uri.parse("content://media/external/audio/media/42")
        registerPlayable(custom)
        start(uri = custom)
        assertEquals(diag(), 1, alive().size)
        val customPlayer = alive().single()

        shadowOf(customPlayer).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        idle()

        assertEquals(ShadowMediaPlayer.State.END, shadowOf(customPlayer).state)
        assertEquals(defaultUri, shadowOf(alive().single()).sourceUri)
        assertTrue(controller.isAlarmRinging)
    }

    @Test
    fun whenCustomAndDefaultBothFail_terminalFailureCallbackFiresOnce_andAlarmStateIsReset() {
        val failures = AtomicInteger()
        ShadowMediaPlayer.addException(
            DataSource.toDataSource(context, defaultUri),
            java.io.IOException("default alarm sound missing")
        )

        start(uri = Uri.parse("content://revoked.provider/alarm.mp3"), onTerminalFailure = { failures.incrementAndGet() })

        assertEquals(1, failures.get())
        assertFalse(controller.isAlarmRinging)
        assertEquals(diag(), emptyList<MediaPlayer>(), playing())
        assertNotNull("focus must be abandoned after a terminal failure", shadowOf(audioManager).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun defaultSoundErrorWhilePlaying_isTerminal_andReportsOnce() {
        val failures = AtomicInteger()
        start(onTerminalFailure = { failures.incrementAndGet() })
        val player = alive().single()

        shadowOf(player).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        idle()

        assertEquals(1, failures.get())
        assertFalse(controller.isAlarmRinging)
        assertEquals(emptyList<MediaPlayer>(), alive())
    }

    @Test
    fun errorOnOneOfTwoRoutes_doesNotKillTheOtherRoute() {
        setOutputs(speaker, bluetooth)
        val failures = AtomicInteger()
        start(ringSpeakerWithHeadphones = true, onTerminalFailure = { failures.incrementAndGet() })
        val btPlayer = requireNotNull(playerOn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        val speakerPlayer = requireNotNull(playerOn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))

        shadowOf(btPlayer).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        idle()

        assertEquals(listOf(speakerPlayer), alive())
        assertEquals(0, failures.get())
        assertTrue(controller.isAlarmRinging)
    }

    // ------------------------------------------------------------------ escalating volume

    @Test
    fun nonEscalatingAlarm_playsAtFullVolumeImmediately() {
        start(escalating = false)

        assertEquals(1.0f, shadowOf(alive().single()).leftVolume, 0.0001f)
    }

    @Test
    fun escalatingAlarm_startsQuietAndRampsToFullVolumeOverTheRampDuration() {
        setOutputs(speaker, bluetooth)
        start(ringSpeakerWithHeadphones = true, escalating = true, rampSecs = 10)
        val players = alive()
        assertEquals(2, players.size)
        players.forEach { assertEquals(0.05f, shadowOf(it).leftVolume, 0.0001f) }

        advanceSeconds(5)
        players.forEach { assertEquals(0.05f + 5 * (0.95f / 10), shadowOf(it).leftVolume, 0.001f) }

        advanceSeconds(5)
        players.forEach { assertEquals(1.0f, shadowOf(it).leftVolume, 0.0001f) }

        advanceSeconds(10)
        players.forEach { assertEquals("volume must stay capped", 1.0f, shadowOf(it).leftVolume, 0.0001f) }
    }

    @Test
    fun stopAlarmSound_cancelsTheVolumeRamp_andNextAlarmStartsFresh() {
        start(escalating = true, rampSecs = 10)
        advanceSeconds(3)
        controller.stopAlarmSound()

        start(escalating = true, rampSecs = 10)
        assertEquals(0.05f, shadowOf(alive().single()).leftVolume, 0.0001f)

        // A leftover tick from the first alarm would add a second step within this one second.
        advanceSeconds(1)

        assertEquals(0.05f + 0.095f, shadowOf(alive().single()).leftVolume, 0.001f)
    }

    // ------------------------------------------------------------------ timeout

    @Test
    fun startTimeout_stopsSoundAndVibration_thenRunsTheCallback() {
        val scope = scope()
        val order = CopyOnWriteArrayList<String>()
        start()
        controller.startTimeout(scope, 60_000L) {
            order += "callback(ringing=${controller.isAlarmRinging},vibrating=${controller.isVibrating})"
        }

        advanceSeconds(59)
        assertTrue(controller.isAlarmRinging)
        assertEquals(emptyList<String>(), order.toList())

        advanceSeconds(2)
        assertEquals(listOf("callback(ringing=false,vibrating=false)"), order.toList())
        assertEquals(emptyList<MediaPlayer>(), alive())
    }

    @Test
    fun stopAll_cancelsThePendingTimeout() {
        val scope = scope()
        val fired = AtomicInteger()
        start()
        controller.startTimeout(scope, 30_000L) { fired.incrementAndGet() }

        controller.stopAll()
        advanceSeconds(120)

        assertEquals(0, fired.get())
        assertFalse(controller.isAlarmRinging)
    }

    @Test
    fun startTimeout_calledAgain_replacesThePreviousTimeout() {
        val scope = scope()
        val first = AtomicInteger()
        val second = AtomicInteger()
        start()
        controller.startTimeout(scope, 30_000L) { first.incrementAndGet() }
        controller.startTimeout(scope, 90_000L) { second.incrementAndGet() }

        advanceSeconds(60)
        assertEquals(0, first.get())
        assertEquals(0, second.get())

        advanceSeconds(40)
        assertEquals(0, first.get())
        assertEquals(1, second.get())
    }

    private fun scope(): CoroutineScope {
        val job = Job()
        scopes += job
        return CoroutineScope(Dispatchers.Main + job)
    }

    // ------------------------------------------------------------------ vibration

    @Test
    fun startVibrator_isIdempotent_andStopVibratorCancelsIt() {
        assertFalse(controller.isVibrating)

        controller.startVibrator()
        controller.startVibrator()
        assertTrue(controller.isVibrating)

        controller.stopVibrator()
        assertFalse(controller.isVibrating)
        controller.stopVibrator() // second stop is harmless
    }
}
