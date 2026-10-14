/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.ring

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.getSystemService
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.notifications.CallReminderNotifications
import io.element.android.features.callreminders.impl.store.CallReminderSettings
import io.element.android.features.callreminders.impl.store.CallReminderStore
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

/**
 * The thing that actually makes the noise.
 *
 * ### Why this exists at all
 *
 * The first version of call reminders rang by posting a notification with `FLAG_INSISTENT` on a
 * channel whose sound was the alarm ringtone. That is the textbook approach and it is genuinely
 * reliable — but it is only ever *as loud as the alarm stream currently is*. If the alarm volume
 * is down a few notches (which is the normal state of a phone that is not used as an alarm
 * clock) the reminder is a polite chime, which is exactly the complaint: "it's not ringing
 * loudly". A notification channel also cannot ramp, cannot vibrate in a pattern the user chose,
 * and cannot be made to stop making noise without taking the whole notification down.
 *
 * So the ring moved into a foreground service that owns the audio properly:
 *
 * 1. **It raises `STREAM_ALARM` to maximum for the duration** and puts it back afterwards. This
 *    is the single change that makes the difference between "I didn't hear it" and "that woke me
 *    up". The previous level is remembered in the service and restored on every exit path.
 * 2. **It plays the tone itself** through [MediaPlayer] with `USAGE_ALARM` /
 *    `CONTENT_TYPE_SONIFICATION` and `isLooping = true`, so the sound is continuous rather than
 *    a notification's one-shot-with-a-repeat-flag, and so the app chooses the tone.
 * 3. **It takes transient-exclusive audio focus**, which ducks or pauses music and podcasts for
 *    the duration instead of ringing underneath them.
 * 4. **It ramps**: the first seconds are at ~40 % and it reaches full within ten seconds
 *    (configurable, and skippable). Loud enough to wake you, not loud enough to hurt if the
 *    phone is next to your ear.
 * 5. **It vibrates on a repeating waveform** with `USAGE_ALARM` vibration attributes, so the
 *    buzz also survives a silenced ringer, and optionally **blinks the camera torch**.
 * 6. **It holds a partial wake lock** so the ring cannot be cut short by the CPU idling, with a
 *    hard timeout so a bug can never leave the lock held.
 *
 * ### Why a foreground service and not just a louder notification
 *
 * Audio that must keep playing while the screen is off, outlive the broadcast receiver that
 * started it, and be stoppable from a notification button is precisely the shape a foreground
 * service is for. The alternative — doing it from the alarm `BroadcastReceiver` — gets the
 * process killed mid-tone. The service is started from an exact-alarm broadcast, which is one of
 * the documented exemptions from Android 12+'s background foreground-service-start restriction,
 * and it declares `mediaPlayback` as its type.
 *
 * If the start is nonetheless refused (an OEM policy, a start from the wrong state), the caller
 * falls back to the old insistent-notification path — quieter, but it still rings. Never silent.
 *
 * The *notification* is still what shows the buttons and carries the full-screen intent; this
 * service adopts that same notification as its foreground notification, so there is one thing on
 * screen, not two.
 */
class AlarmRingService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var torchCameraId: String? = null

    /** The alarm volume we found, so the user's phone is handed back exactly as it was. */
    private var previousAlarmVolume: Int? = null
    private var ringingReminderId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var rampStep: Runnable? = null
    private var torchStep: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> {
                stopEverything()
                stopForegroundCompat(removeNotification = true)
                stopSelf()
            }
            ACTION_SILENCE -> {
                // Stop the noise, keep the alarm on screen and in the shade. The notification is
                // detached from the service first so taking the service down does not take the
                // buttons with it — a silenced alarm still has to be dealt with.
                stopEverything()
                silenceButKeepNotification()
                stopSelf()
            }
            else -> {
                Timber.w("AlarmRingService started with no action; stopping")
                stopSelf()
            }
        }
        // Not sticky: if the system kills us, the alarm has already been rescheduled or resolved;
        // restarting with a null intent and no reminder would just be noise.
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return stopSelf()
        val store = CallReminderStore.get(this)
        val settings = CallReminderSettings.get(this)
        val reminder = store.get(reminderId) ?: run {
            Timber.w("Ring requested for an unknown reminder")
            return stopSelf()
        }
        val gentle = intent.getBooleanExtra(EXTRA_GENTLE, false)

        // Already ringing this one (e.g. a re-delivered broadcast): do not stack two players.
        if (ringingReminderId == reminderId && player != null) return
        if (ringingReminderId != null) stopEverything()
        ringingReminderId = reminderId

        // The notification must be up *before* anything else: startForeground has a few seconds
        // to be called or the system kills the service with a crash.
        val notification = CallReminderNotifications.buildAlarmNotification(
            context = this,
            reminder = reminder,
            gentle = gentle,
            // This service owns the sound now, so the notification itself must be silent —
            // otherwise the channel's tone plays on top of ours.
            silent = true,
            ongoing = true,
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    CallReminderNotifications.notificationId(reminder),
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(CallReminderNotifications.notificationId(reminder), notification)
            }
        }.onFailure {
            Timber.e(it, "Could not go foreground; falling back to the notification's own sound")
            CallReminderNotifications.showAlarm(this, reminder, gentle = gentle)
            ringingReminderId = null
            stopSelf()
            return
        }

        acquireWakeLock(settings)
        startAudio(settings, gentle)
        startVibration(settings, gentle)
        if (settings.torchBlink && !gentle) startTorch()

        // A hard ceiling on how long this service may live, independent of the auto-silence
        // alarm. Belt and braces: whatever else goes wrong, the phone stops ringing.
        handler.postDelayed(
            {
                Timber.w("Ring hit its hard time limit; stopping")
                stopEverything()
                silenceButKeepNotification()
                stopSelf()
            },
            min(MAX_RING_MILLIS, max(settings.autoSilenceMinutes, 1) * 60_000L + GRACE_MILLIS),
        )
    }

    // ----------------------------------------------------------------------------------------
    // audio
    // ----------------------------------------------------------------------------------------
    private fun startAudio(settings: CallReminderSettings, gentle: Boolean) {
        val manager = getSystemService<AudioManager>() ?: return
        audioManager = manager
        val tone = AlarmTone.fromId(settings.toneId)
        val uri = tone.uri(this) ?: run {
            Timber.w("No tone to play")
            return
        }

        // Ring on the alarm stream unless the user has deliberately asked for a reminder to be
        // mutable by the ringer switch. The alarm stream is the one that ignores silent mode.
        val usage = if (settings.ringInSilentMode) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION
        val stream = if (settings.ringInSilentMode) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION

        if (settings.maxOutVolume && !gentle) raiseVolume(manager, stream)
        requestFocus(manager, usage)

        val startFraction = if (settings.rampUpSeconds > 0) settings.rampStartPercent / 100f else 1f
        val target = if (gentle) GENTLE_VOLUME else 1f

        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                setVolume(startFraction * target, startFraction * target)
                setOnErrorListener { _, what, extra ->
                    Timber.e("Alarm player error what=%d extra=%d", what, extra)
                    true
                }
                prepare()
                start()
            }
        }.onFailure { Timber.e(it, "Could not play the alarm tone") }.getOrNull()

        if (settings.rampUpSeconds > 0) rampUp(settings.rampUpSeconds, startFraction, target)
        Timber.d(
            "Ringing: tone=%s stream=%d ramp=%ds gentle=%b",
            tone.id,
            stream,
            settings.rampUpSeconds,
            gentle,
        )
    }

    /**
     * Takes the alarm stream to maximum, remembering what it was.
     *
     * This is the part that makes the reminder loud, and the part that has to be undone on every
     * exit path — [restoreVolume] is called from [stopEverything] and from [onDestroy], so a
     * crash, a kill or a normal stop all put the volume back.
     *
     * `setStreamVolume` throws if Do Not Disturb is on and the app has no notification-policy
     * access, hence the `runCatching`: the tone still plays at whatever the current level is.
     */
    private fun raiseVolume(manager: AudioManager, stream: Int) {
        runCatching {
            val maximum = manager.getStreamMaxVolume(stream)
            val current = manager.getStreamVolume(stream)
            if (current < maximum) {
                previousAlarmVolume = current
                manager.setStreamVolume(stream, maximum, 0)
                Timber.d("Raised stream %d from %d to %d for the alarm", stream, current, maximum)
            }
        }.onFailure { Timber.w(it, "Could not raise the alarm volume (Do Not Disturb without policy access?)") }
    }

    private fun restoreVolume() {
        val manager = audioManager ?: return
        val previous = previousAlarmVolume ?: return
        previousAlarmVolume = null
        val settings = CallReminderSettings.get(this)
        val stream = if (settings.ringInSilentMode) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
        runCatching { manager.setStreamVolume(stream, previous, 0) }
            .onFailure { Timber.w(it, "Could not put the alarm volume back") }
    }

    /**
     * Transient-exclusive focus: whatever is playing stops for the alarm rather than competing
     * with it, and resumes when the alarm ends.
     */
    private fun requestFocus(manager: AudioManager, usage: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            // An alarm is not something to duck for; it keeps its own volume whatever else wants
            // the output.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        runCatching { manager.requestAudioFocus(request) }
            .onFailure { Timber.w(it, "Could not take audio focus") }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }

    /**
     * Fades the tone in over [seconds].
     *
     * Deliberately done in software on the player rather than by walking the stream volume: the
     * stream is already at maximum (that is the point), so the ramp has to happen *inside* the
     * alarm, and the player's own gain is the only place that does not touch the user's settings.
     */
    private fun rampUp(seconds: Int, from: Float, target: Float) {
        val steps = max(1, seconds * 1_000 / RAMP_INTERVAL_MILLIS.toInt())
        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                step++
                val fraction = (from + (1f - from) * step / steps).coerceIn(0f, 1f)
                runCatching { player?.setVolume(fraction * target, fraction * target) }
                if (step < steps) handler.postDelayed(this, RAMP_INTERVAL_MILLIS)
            }
        }
        rampStep = runnable
        handler.postDelayed(runnable, RAMP_INTERVAL_MILLIS)
    }

    // ----------------------------------------------------------------------------------------
    // vibration and light
    // ----------------------------------------------------------------------------------------
    private fun startVibration(settings: CallReminderSettings, gentle: Boolean) {
        if (!settings.vibrate) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService<Vibrator>()
        } ?: return
        vibrator = device
        if (!device.hasVibrator()) return

        val pattern = if (gentle) GENTLE_PATTERN else LOUD_PATTERN
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = if (gentle) GENTLE_AMPLITUDES else LOUD_AMPLITUDES
                // repeat = 0: start the waveform again from the beginning, forever, until
                // cancelled. A one-shot pattern is easy to miss; a repeating one is an alarm.
                val effect = VibrationEffect.createWaveform(pattern, amplitudes, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device.vibrate(
                        effect,
                        android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    device.vibrate(
                        effect,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(pattern, 0)
            }
        }.onFailure { Timber.w(it, "Could not vibrate") }
    }

    /** Blinks the torch, for when the phone is face down or across the room. Opt-in. */
    private fun startTorch() {
        val manager = getSystemService<CameraManager>() ?: return
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return
        torchCameraId = cameraId
        var on = false
        val runnable = object : Runnable {
            override fun run() {
                on = !on
                runCatching { manager.setTorchMode(cameraId, on) }
                    .onFailure { Timber.d(it, "Torch unavailable; stopping the blink") }
                handler.postDelayed(this, TORCH_INTERVAL_MILLIS)
            }
        }
        torchStep = runnable
        handler.post(runnable)
    }

    private fun stopTorch() {
        torchStep?.let(handler::removeCallbacks)
        torchStep = null
        val cameraId = torchCameraId ?: return
        torchCameraId = null
        runCatching { getSystemService<CameraManager>()?.setTorchMode(cameraId, false) }
    }

    // ----------------------------------------------------------------------------------------
    // lifecycle
    // ----------------------------------------------------------------------------------------
    private fun acquireWakeLock(settings: CallReminderSettings) {
        val manager = getSystemService<PowerManager>() ?: return
        // PARTIAL only: the screen is the full-screen activity's job, this just keeps the CPU
        // alive so the tone does not stutter or stop.
        wakeLock = runCatching {
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                // A timeout, always: a wake lock leaked by a crash is a flat battery.
                acquire(min(MAX_RING_MILLIS, max(settings.autoSilenceMinutes, 1) * 60_000L + GRACE_MILLIS))
            }
        }.onFailure { Timber.w(it, "Could not take a wake lock") }.getOrNull()
    }

    private fun stopEverything() {
        handler.removeCallbacksAndMessages(null)
        rampStep = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        stopTorch()
        restoreVolume()
        abandonFocus()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        ringingReminderId = null
    }

    /**
     * Hands the notification over to the notification manager and re-posts it without sound, so
     * stopping the service leaves the alarm visible and actionable.
     */
    private fun silenceButKeepNotification() {
        val id = ringingReminderId
        stopForegroundCompat(removeNotification = false)
        val reminder = id?.let { CallReminderStore.get(this).get(it) } ?: return
        CallReminderNotifications.showAlarm(
            context = this,
            reminder = reminder,
            gentle = CallReminderSettings.get(this).gentleByDefault,
            silent = true,
            // Already silenced by the user: keep the buttons, never pull the screen back up.
            alert = false,
        )
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "io.element.android.features.callreminders.ring.START"
        private const val ACTION_STOP = "io.element.android.features.callreminders.ring.STOP"
        private const val ACTION_SILENCE = "io.element.android.features.callreminders.ring.SILENCE"
        private const val EXTRA_REMINDER_ID = "reminder_id"
        private const val EXTRA_GENTLE = "gentle"
        private const val WAKE_LOCK_TAG = "MatrixChat:callReminderRing"

        /** How often the fade-in nudges the volume. 250 ms is inaudibly smooth and cheap. */
        private const val RAMP_INTERVAL_MILLIS = 250L
        private const val TORCH_INTERVAL_MILLIS = 400L
        private const val GRACE_MILLIS = 15_000L

        /** Nothing may ring for longer than this, whatever the settings say. */
        private const val MAX_RING_MILLIS = 15 * 60_000L
        private const val GENTLE_VOLUME = 0.45f

        /**
         * Long buzz, short gap, long buzz — a pattern the body reads as urgent. Amplitudes are
         * near-maximum: a reminder that only buzzes politely is one that gets slept through.
         */
        private val LOUD_PATTERN = longArrayOf(0, 800, 350, 800, 350, 1_200, 500)
        private val LOUD_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255, 0)
        private val GENTLE_PATTERN = longArrayOf(0, 180, 140, 180, 1_600)
        private val GENTLE_AMPLITUDES = intArrayOf(0, 140, 0, 140, 0)

        /** Start ringing [reminder]. Returns false if the service could not be started. */
        fun ring(context: Context, reminder: CallReminder, gentle: Boolean): Boolean = runCatching {
            val intent = Intent(context, AlarmRingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_REMINDER_ID, reminder.id)
                putExtra(EXTRA_GENTLE, gentle)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        }.getOrElse { error ->
            // Android 12+ can refuse a background foreground-service start. An exact alarm is a
            // documented exemption, so this should not happen — but if an OEM disagrees, the
            // caller has to know so it can fall back to the notification's own sound.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException) {
                Timber.w(error, "The system refused to start the ring service")
            } else {
                Timber.e(error, "Could not start the ring service")
            }
            false
        }

        /** Stop ringing and take the alarm down. */
        fun stop(context: Context) = send(context, ACTION_STOP)

        /** Stop the noise but leave the alarm on screen, as a volume key does on a clock alarm. */
        fun silence(context: Context) = send(context, ACTION_SILENCE)

        private fun send(context: Context, action: String) {
            runCatching {
                context.startService(Intent(context, AlarmRingService::class.java).setAction(action))
            }.onFailure {
                // Nothing is ringing, so there is nothing to stop. Not worth a log line above debug.
                Timber.d("Ring service was not running")
            }
        }
    }
}
