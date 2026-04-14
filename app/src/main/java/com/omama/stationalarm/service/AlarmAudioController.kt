package com.omama.stationalarm.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
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
 * Behaviour preserved verbatim from the original LocationService implementation
 * (see git history pre-modularity refactor). The single non-cosmetic change is
 * that all of the relevant state — `mediaPlayer`, `alarmRinging`, `isVibrating`,
 * `audioFocusRequest`, the focus listener, and `alarmTimeoutJob` — now lives
 * here instead of being scattered across the Service.
 */
internal class AlarmAudioController(private val context: Context) {

    private val tag = "AlarmAudioController"

    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var alarmTimeoutJob: Job? = null

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
                if (isAlarmRinging && mediaPlayer?.isPlaying == false) {
                    mediaPlayer?.start()
                }
            }
        }
    }

    fun playAlarmSound() = playAlarmSound(null)

    /**
     * Plays the alarm using [customUri] if provided, otherwise falls back to
     * the system default alarm/notification ringtone. If [customUri] fails to
     * open (e.g., revoked SAF permission, deleted file), falls back to the
     * system default so the alarm never silently fails.
     */
    fun playAlarmSound(customUri: Uri?) {
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

        // Wrapped in a holder so the async error listener can tell whether it
        // fired while preparing the custom URI (fall back to default) or the
        // default URI itself (give up — sound will be silent).
        fun startPlayer(uri: Uri, isDefaultAttempt: Boolean) {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(audioAttributes)
                isLooping = true
                setOnPreparedListener {
                    setVolume(1.0f, 1.0f)
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                    start()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.w(tag, "MediaPlayer error (what=$what, extra=$extra) for uri=$uri")
                    try { mp.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    if (!isDefaultAttempt && defaultUri != null && defaultUri != uri) {
                        try {
                            startPlayer(defaultUri, isDefaultAttempt = true)
                        } catch (e: Exception) {
                            Log.e(tag, "Default alarm fallback failed after async error", e)
                            isAlarmRinging = false
                        }
                    } else {
                        // Default URI failed too — nothing we can do. Flip state off
                        // so callers stop thinking an alarm is playing.
                        isAlarmRinging = false
                    }
                    true // handled — MediaPlayer won't invoke onCompletion
                }
                prepareAsync()
            }
        }

        // Try custom URI first, then fall back to default on any failure
        val preferredUri = customUri ?: defaultUri
        if (preferredUri == null) {
            Log.e(tag, "No alarm URI available")
            isAlarmRinging = false
            return
        }
        val startingWithDefault = (preferredUri == defaultUri)
        try {
            startPlayer(preferredUri, isDefaultAttempt = startingWithDefault)
        } catch (e: Exception) {
            Log.w(tag, "Failed to play custom alarm URI ($preferredUri), falling back to default", e)
            mediaPlayer?.release()
            mediaPlayer = null
            if (defaultUri != null && defaultUri != preferredUri) {
                try {
                    startPlayer(defaultUri, isDefaultAttempt = true)
                } catch (e2: Exception) {
                    Log.e(tag, "Error playing default alarm after fallback", e2)
                    isAlarmRinging = false
                }
            } else {
                isAlarmRinging = false
            }
        }
    }

    fun stopAlarmSound() {
        if (isAlarmRinging) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            isAlarmRinging = false

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        }
    }

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
