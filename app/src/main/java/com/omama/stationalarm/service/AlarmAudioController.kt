package com.omama.stationalarm.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the alarm sound + vibrator + audio-focus + 5-minute timeout.
 *
 * Keeps sound, vibration, focus, routing, and timeout state here instead of
 * scattering alarm-specific behaviour across the Service.
 */
internal class AlarmAudioController(private val context: Context) {

    private val tag = "AlarmAudioController"

    private enum class AlarmRoute { SPEAKER, EXTERNAL }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var speakerPlayer: MediaPlayer? = null
    private var externalPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var alarmTimeoutJob: Job? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var externalOutputDevice: AudioDeviceInfo? = null
    private var activeAlarmUri: Uri? = null
    private var activeUriIsDefaultAttempt: Boolean = false
    private var defaultAlarmUri: Uri? = null
    private var ringSpeakerWithHeadphones: Boolean = true
    private var routedAudioAttributes: AudioAttributes? = null
    private var terminalFailureCallback: (() -> Unit)? = null

    var isAlarmRinging: Boolean = false
        private set
    var isVibrating: Boolean = false
        private set

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Don't stop alarm on focus loss — user needs this alarm
                Log.d(tag, "Audio focus lost, keeping alarm alive")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(tag, "Audio focus lost transiently")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(tag, "Audio focus gained")
                if (isAlarmRinging) {
                    resumePlayer(speakerPlayer)
                    resumePlayer(externalPlayer)
                }
            }
        }
    }

    fun playAlarmSound() = playAlarmSound(null, true, null)
    fun playAlarmSound(customUri: Uri?) = playAlarmSound(customUri, true, null)
    fun playAlarmSound(customUri: Uri?, onAudioTerminalFailure: (() -> Unit)?) =
        playAlarmSound(customUri, true, onAudioTerminalFailure)

    /**
     * Plays the alarm using [customUri] if provided, otherwise falls back to
     * the system default alarm/notification ringtone. If [customUri] fails to
     * open (e.g., revoked SAF permission, deleted file), falls back to the
     * system default so the alarm never silently fails.
     *
     * [onAudioTerminalFailure] is invoked when BOTH the custom URI AND the
     * default URI fail (the only path that ends in true silence). The Service
     * uses this to escalate to vibration + a user-visible notification.
     *
     * When [ringSpeakerWithHeadphones] is true, the alarm starts a speaker route
     * plus the best connected external route. If that route disappears while
     * ringing, the remaining route keeps going and routing is rebuilt.
     */
    fun playAlarmSound(
        customUri: Uri?,
        ringSpeakerWithHeadphones: Boolean,
        onAudioTerminalFailure: (() -> Unit)?
    ) {
        if (isAlarmRinging) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }

        isAlarmRinging = true

        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        this.ringSpeakerWithHeadphones = ringSpeakerWithHeadphones
        terminalFailureCallback = onAudioTerminalFailure
        routedAudioAttributes = audioAttributes
        defaultAlarmUri = defaultUri
        registerAudioDeviceCallback(audioManager)

        // Start whichever routes are safe for the current device state.
        fun startPlayer(uri: Uri, isDefaultAttempt: Boolean) {
            activeAlarmUri = uri
            activeUriIsDefaultAttempt = isDefaultAttempt
            rebuildRoutes(audioManager)
            if (!hasAnyRoutePlayer()) {
                switchToDefaultFallback(audioManager, "No alarm route could be started")
            }
        }

        // Try custom URI first, then fall back to default on any failure
        val preferredUri = customUri ?: defaultUri
        if (preferredUri == null) {
            finishTerminalFailure(audioManager, "No alarm URI available")
            return
        }
        val startingWithDefault = (preferredUri == defaultUri)
        try {
            startPlayer(preferredUri, isDefaultAttempt = startingWithDefault)
        } catch (e: Exception) {
            Log.w(tag, "Failed to play custom alarm URI ($preferredUri), falling back to default", e)
            releaseRoutePlayers()
            if (defaultUri != null && defaultUri != preferredUri) {
                try {
                    startPlayer(defaultUri, isDefaultAttempt = true)
                } catch (e2: Exception) {
                    Log.e(tag, "Error playing default alarm after fallback", e2)
                    finishTerminalFailure(audioManager, "Default alarm fallback failed")
                }
            } else {
                finishTerminalFailure(audioManager, "Default alarm route failed")
            }
        }
    }

    fun stopAlarmSound() {
        if (isAlarmRinging) {
            releaseRoutePlayers()
            isAlarmRinging = false

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            unregisterAudioDeviceCallback(audioManager)
            abandonAudioFocus(audioManager)
            activeAlarmUri = null
            defaultAlarmUri = null
            routedAudioAttributes = null
            externalOutputDevice = null
            terminalFailureCallback = null
        }
    }

    private fun rebuildRoutes(audioManager: AudioManager) {
        val uri = activeAlarmUri ?: return
        val attributes = routedAudioAttributes ?: return
        val externalDevice = findExternalOutputDevice(audioManager)
        val shouldUseSpeaker = ringSpeakerWithHeadphones || externalDevice == null

        if (shouldUseSpeaker) {
            if (speakerPlayer == null) {
                speakerPlayer = createRoutePlayer(
                    audioManager = audioManager,
                    audioAttributes = attributes,
                    uri = uri,
                    isDefaultAttempt = activeUriIsDefaultAttempt,
                    preferredDevice = findBuiltInSpeaker(audioManager),
                    route = AlarmRoute.SPEAKER
                )
            }
        } else {
            releaseRoutePlayer(AlarmRoute.SPEAKER)
        }

        if (externalDevice != null) {
            val externalChanged = externalOutputDevice?.id != externalDevice.id
            if (externalChanged) {
                releaseRoutePlayer(AlarmRoute.EXTERNAL)
            }
            externalOutputDevice = externalDevice
            if (externalPlayer == null) {
                externalPlayer = createRoutePlayer(
                    audioManager = audioManager,
                    audioAttributes = attributes,
                    uri = uri,
                    isDefaultAttempt = activeUriIsDefaultAttempt,
                    preferredDevice = externalDevice,
                    route = AlarmRoute.EXTERNAL
                )
            }
        } else {
            externalOutputDevice = null
            releaseRoutePlayer(AlarmRoute.EXTERNAL)
        }
    }

    private fun createRoutePlayer(
        audioManager: AudioManager,
        audioAttributes: AudioAttributes,
        uri: Uri,
        isDefaultAttempt: Boolean,
        preferredDevice: AudioDeviceInfo?,
        route: AlarmRoute
    ): MediaPlayer? {
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(context, uri)
            mp.setAudioAttributes(audioAttributes)
            if (preferredDevice != null) {
                if (!mp.setPreferredDevice(preferredDevice)) {
                    Log.w(tag, "Preferred audio route was rejected: route=$route type=${preferredDevice.type}")
                    mp.release()
                    return null
                }
            }
            mp.isLooping = true
            mp.setOnPreparedListener {
                mp.setVolume(1.0f, 1.0f)
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                mp.start()
            }
            mp.setOnErrorListener { _, what, extra ->
                mainHandler.post {
                    handleRoutePlayerError(route, mp, uri, isDefaultAttempt, what, extra)
                }
                true
            }
            mp.prepareAsync()
            mp
        } catch (e: Exception) {
            Log.w(tag, "Failed to start $route alarm route for uri=$uri", e)
            null
        }
    }

    private fun handleRoutePlayerError(
        route: AlarmRoute,
        player: MediaPlayer,
        uri: Uri,
        isDefaultAttempt: Boolean,
        what: Int,
        extra: Int
    ) {
        if (!isAlarmRinging) {
            try { player.release() } catch (_: Exception) {}
            return
        }

        val currentPlayer = when (route) {
            AlarmRoute.SPEAKER -> speakerPlayer
            AlarmRoute.EXTERNAL -> externalPlayer
        }
        if (currentPlayer !== player) {
            try { player.release() } catch (_: Exception) {}
            return
        }

        Log.w(tag, "MediaPlayer error (route=$route, what=$what, extra=$extra) for uri=$uri")
        releaseRoutePlayer(route)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!isDefaultAttempt && defaultAlarmUri != null && defaultAlarmUri != uri) {
            switchToDefaultFallback(audioManager, "$route route failed on custom URI")
        } else if (!hasAnyRoutePlayer()) {
            finishTerminalFailure(audioManager, "All alarm audio routes failed")
        }
    }

    private fun switchToDefaultFallback(audioManager: AudioManager, reason: String) {
        val fallbackUri = defaultAlarmUri
        if (fallbackUri == null || (activeUriIsDefaultAttempt && activeAlarmUri == fallbackUri)) {
            finishTerminalFailure(audioManager, reason)
            return
        }

        Log.w(tag, "$reason; falling back to default alarm sound")
        releaseRoutePlayers()
        activeAlarmUri = fallbackUri
        activeUriIsDefaultAttempt = true
        rebuildRoutes(audioManager)
        if (!hasAnyRoutePlayer()) {
            finishTerminalFailure(audioManager, "Default alarm fallback failed")
        }
    }

    private fun finishTerminalFailure(audioManager: AudioManager, reason: String) {
        if (!isAlarmRinging) return
        Log.e(tag, reason)
        releaseRoutePlayers()
        unregisterAudioDeviceCallback(audioManager)
        abandonAudioFocus(audioManager)
        isAlarmRinging = false
        activeAlarmUri = null
        defaultAlarmUri = null
        routedAudioAttributes = null
        externalOutputDevice = null
        val callback = terminalFailureCallback
        terminalFailureCallback = null
        callback?.invoke()
    }

    private fun registerAudioDeviceCallback(audioManager: AudioManager) {
        if (audioDeviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                handleAudioDevicesChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                handleAudioDevicesChanged()
            }
        }
        audioDeviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, mainHandler)
    }

    private fun unregisterAudioDeviceCallback(audioManager: AudioManager) {
        audioDeviceCallback?.let { callback ->
            try {
                audioManager.unregisterAudioDeviceCallback(callback)
            } catch (e: Exception) {
                Log.w(tag, "Failed to unregister audio device callback", e)
            }
        }
        audioDeviceCallback = null
    }

    private fun handleAudioDevicesChanged() {
        if (!isAlarmRinging) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        rebuildRoutes(audioManager)
        if (!hasAnyRoutePlayer()) {
            switchToDefaultFallback(audioManager, "Audio devices changed and no alarm route is active")
        }
    }

    private fun abandonAudioFocus(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun releaseRoutePlayers() {
        releaseRoutePlayer(AlarmRoute.SPEAKER)
        releaseRoutePlayer(AlarmRoute.EXTERNAL)
    }

    private fun releaseRoutePlayer(route: AlarmRoute) {
        val player = when (route) {
            AlarmRoute.SPEAKER -> speakerPlayer.also { speakerPlayer = null }
            AlarmRoute.EXTERNAL -> externalPlayer.also { externalPlayer = null }
        }
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
    }

    private fun resumePlayer(player: MediaPlayer?) {
        player ?: return
        try {
            if (!player.isPlaying) player.start()
        } catch (e: Exception) {
            Log.w(tag, "Failed to resume alarm player after focus gain", e)
        }
    }

    private fun hasAnyRoutePlayer(): Boolean = speakerPlayer != null || externalPlayer != null

    private fun findBuiltInSpeaker(audioManager: AudioManager): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private fun findExternalOutputDevice(audioManager: AudioManager): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { isExternalAlarmOutputType(it.type) }
            .minByOrNull { externalOutputPriority(it.type) }

    private fun isExternalAlarmOutputType(type: Int): Boolean =
        when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> isModernExternalAlarmOutputType(type)
        }

    private fun isModernExternalAlarmOutputType(type: Int): Boolean =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    type == AudioDeviceInfo.TYPE_BLE_BROADCAST))

    private fun externalOutputPriority(type: Int): Int =
        when {
            isBluetoothOutputType(type) -> 0
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> 1
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET -> 2
            else -> 3
        }

    private fun isBluetoothOutputType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    type == AudioDeviceInfo.TYPE_BLE_BROADCAST))

    fun startVibrator() {
        if (isVibrating) return
        isVibrating = true
        val vibrator = vibrator()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 1000, 1000), 1)
        }
    }

    fun stopVibrator() {
        if (isVibrating) {
            vibrator().cancel()
            isVibrating = false
        }
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /**
     * Schedules the 5-minute auto-dismiss timeout. Cancels any previously
     * scheduled timeout. The caller's [onTimeout] runs after sound + vibration
     * have already been stopped.
     */
    fun startTimeout(scope: CoroutineScope, onTimeout: () -> Unit) {
        alarmTimeoutJob?.cancel()
        alarmTimeoutJob = scope.launch {
            delay(5 * 60 * 1000L) // 5 minutes max duration
            stopAlarmSound()
            stopVibrator()
            onTimeout()
        }
    }

    /** Stops sound + vibration + cancels any pending timeout. */
    fun stopAll() {
        stopAlarmSound()
        stopVibrator()
        alarmTimeoutJob?.cancel()
    }
}
