/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import io.element.android.features.callreminders.impl.R
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.receivers.CallReminderActionReceiver
import io.element.android.features.callreminders.impl.ui.CallReminderAlarmActivity
import io.element.android.features.callreminders.impl.ui.CallRemindersActivity
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The alarm's face: the thing on screen and in the shade, with the buttons and the full-screen
 * intent that brings the alarm up over the lock screen.
 *
 * ### Who makes the noise
 *
 * Since the "make it ring loud" pass, the *sound* belongs to
 * [io.element.android.features.callreminders.impl.ring.AlarmRingService], not to a notification
 * channel. A channel's sound can only ever be as loud as the stream currently is, cannot ramp,
 * and cannot be stopped without taking the notification down with it. The service plays the tone
 * itself, takes the alarm stream to maximum and puts it back afterwards.
 *
 * So there are two kinds of alarm notification here:
 *
 * * **[CHANNEL_RING]** — deliberately **silent and vibration-free**, high importance, Do Not
 *   Disturb bypassing. This is the one the ring service posts as its foreground notification:
 *   the service is already making the noise, and a channel sound on top of it would be two
 *   tones at once.
 * * **[CHANNEL_LOUD] / [CHANNEL_GENTLE]** — the original channels, which *do* carry a sound and
 *   `FLAG_INSISTENT`. These are now the **fallback**, used only when the ring service cannot be
 *   started. Quieter, but the alarm still rings; it is never silent.
 *
 * [CHANNEL_UPCOMING] is the unrelated quiet "next reminder" line.
 */
object CallReminderNotifications {
    const val CHANNEL_LOUD = "call_reminders_loud"
    const val CHANNEL_GENTLE = "call_reminders_gentle"
    const val CHANNEL_UPCOMING = "call_reminders_upcoming"

    /**
     * The channel the ring service posts on. Silent by design — see the class comment.
     *
     * A channel's importance and sound are fixed at creation and cannot be changed by the app
     * afterwards, which is why this is a new id rather than a reconfigured [CHANNEL_LOUD].
     */
    const val CHANNEL_RING = "call_reminders_ring"

    private const val ALARM_NOTIFICATION_ID_BASE = 920_000
    const val UPCOMING_NOTIFICATION_ID = 919_999

    /** A long, urgent pattern for the loud mode; a short double-tap for the gentle one. */
    private val LOUD_VIBRATION = longArrayOf(0, 700, 500, 700, 500, 700, 1200)
    private val GENTLE_VIBRATION = longArrayOf(0, 180, 120, 180)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarmTone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val ring = NotificationChannel(
            CHANNEL_RING,
            context.getString(R.string.call_reminders_channel_ring),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.call_reminders_channel_ring_description)
            // Silent *on purpose*: the ring service is playing the tone.
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            bypassDndIfAllowed(context)
        }
        val loud = NotificationChannel(
            CHANNEL_LOUD,
            context.getString(R.string.call_reminders_channel_loud),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.call_reminders_channel_loud_description)
            setSound(alarmTone, alarmAttributes)
            enableVibration(true)
            vibrationPattern = LOUD_VIBRATION
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            bypassDndIfAllowed(context)
        }
        val gentle = NotificationChannel(
            CHANNEL_GENTLE,
            context.getString(R.string.call_reminders_channel_gentle),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.call_reminders_channel_gentle_description)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = GENTLE_VIBRATION
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val upcoming = NotificationChannel(
            CHANNEL_UPCOMING,
            context.getString(R.string.call_reminders_channel_upcoming),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.call_reminders_channel_upcoming_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannels(listOf(ring, loud, gentle, upcoming))
    }

    /**
     * Ask to ignore Do Not Disturb.
     *
     * This only sticks once the user has granted notification-policy access in system settings
     * (the reminders screen offers the button for it). Without the grant the alarm *stream* still
     * cuts through a silenced ringer, which covers the common case; the grant is what gets it
     * through a full Do Not Disturb.
     */
    private fun NotificationChannel.bypassDndIfAllowed(context: Context) {
        val manager = context.getSystemService<NotificationManager>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && manager?.isNotificationPolicyAccessGranted != true) return
        runCatching { setBypassDnd(true) }.onFailure { Timber.d("No Do Not Disturb access") }
    }

    /** True when the app may set a channel to bypass Do Not Disturb — i.e. the grant is in place. */
    fun hasDoNotDisturbAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return context.getSystemService<NotificationManager>()?.isNotificationPolicyAccessGranted == true
    }

    fun notificationId(reminder: CallReminder): Int = ALARM_NOTIFICATION_ID_BASE + (reminder.id.hashCode() and 0xFFFF)

    /**
     * Builds the alarm notification.
     *
     * Shared by the ring service (which adopts it as its foreground notification, `silent = true`
     * because it is playing the tone itself) and by the fallback path (`silent = false`, where the
     * channel's own sound plus `FLAG_INSISTENT` is what rings).
     *
     * [alert] is a separate question from [silent]: a *first* post must be allowed to interrupt —
     * that is what raises the alarm screen and the heads-up — while re-posting an alarm the user
     * has already silenced must not drag the screen back up.
     */
    fun buildAlarmNotification(
        context: Context,
        reminder: CallReminder,
        gentle: Boolean,
        silent: Boolean,
        ongoing: Boolean = true,
        alert: Boolean = true,
    ): Notification {
        ensureChannels(context)
        val channel = when {
            silent -> CHANNEL_RING
            gentle -> CHANNEL_GENTLE
            else -> CHANNEL_LOUD
        }
        val fullScreen = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            CallReminderAlarmActivity.intent(context, reminder.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val subtitle = alarmSubtitle(context, reminder)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(reminder.title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setOngoing(ongoing)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(reminder.originalDueAtMillis)
            .setShowWhen(true)
            .setContentIntent(fullScreen)
            // Re-posting a silenced alarm must not pop the heads-up again; a ringing one should
            // re-alert on every update.
            .setOnlyAlertOnce(!alert)
            // A foreground-service notification is held back for ten seconds by default, which is
            // ten seconds of a phone ringing with nothing on screen. An alarm is exactly the case
            // the "immediate" behaviour exists for.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (alert) {
            // The full-screen intent is what brings the alarm screen up over the lock screen.
            // Only on a post that is allowed to interrupt: the system ignores it otherwise, and
            // a silenced alarm that re-opened its own screen would be unsilenceable.
            builder.setFullScreenIntent(fullScreen, true)
        }

        if (silent) {
            // NOT NotificationCompat.setSilent(): that quietly moves the notification into the
            // "silent" group with GROUP_ALERT_SUMMARY, and a group child whose summary is absent
            // has *all* of its alerting suppressed — the heads-up and the full-screen intent
            // included. That is why a real reminder used to ring loudly with nothing on screen
            // while the test button, which starts the screen itself, looked fine.
            // The ring channel already carries no sound and no vibration, so there is nothing
            // here to suppress.
            builder.setSound(null).setVibrate(null).setDefaults(0)
        } else {
            // Pre-O there are no channels, so the stream and the vibration are set here. From O
            // on these are ignored and the channel's own sound/attributes apply, which is why
            // the loud channel is built with USAGE_ALARM.
            builder.setSound(
                RingtoneManager.getDefaultUri(if (gentle) RingtoneManager.TYPE_NOTIFICATION else RingtoneManager.TYPE_ALARM),
                if (gentle) AudioManager.STREAM_NOTIFICATION else AudioManager.STREAM_ALARM,
            )
            builder.setVibrate(if (gentle) GENTLE_VIBRATION else LOUD_VIBRATION)
        }

        if (reminder.phone != null) {
            builder.addAction(
                0,
                context.getString(R.string.call_reminders_action_call_now),
                // An activity, not a broadcast: Android 12 drops an activity start that a
                // notification tap routes through a receiver, which left this button marking the
                // reminder "called" and never opening the dialer.
                PendingIntent.getActivity(
                    context,
                    reminder.id.hashCode(),
                    CallReminderAlarmActivity.intent(context, reminder.id, autoCall = true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        builder.addAction(
            0,
            context.getString(R.string.call_reminders_action_done),
            CallReminderActionReceiver.pendingIntent(context, reminder.id, CallReminderActionReceiver.ACTION_DONE),
        )
        val firstSnooze = reminder.snoozeOptionsMinutes.firstOrNull() ?: 5
        builder.addAction(
            0,
            context.getString(R.string.call_reminders_action_snooze_minutes, firstSnooze),
            CallReminderActionReceiver.pendingIntent(
                context,
                reminder.id,
                CallReminderActionReceiver.ACTION_SNOOZE,
                minutes = firstSnooze,
            ),
        )

        val notification = builder.build()
        if (!silent) {
            // FLAG_INSISTENT loops the sound until the notification is cancelled. There is no
            // NotificationCompat setter for it, so it goes on the built notification. Only
            // relevant on the fallback path, where the channel is the thing making the noise.
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
        }
        return notification
    }

    /**
     * Posts the alarm notification directly.
     *
     * Used on the fallback path (the ring service could not start) and to re-post a *silenced*
     * alarm so the screen and the buttons survive the noise stopping.
     */
    fun showAlarm(context: Context, reminder: CallReminder, gentle: Boolean, silent: Boolean = false, alert: Boolean = true) {
        val notification = buildAlarmNotification(context, reminder, gentle = gentle, silent = silent, alert = alert)
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(reminder), notification)
        }.onFailure { Timber.e(it, "Could not post the alarm notification (notifications permission?)") }
    }

    fun cancelAlarm(context: Context, reminder: CallReminder) {
        NotificationManagerCompat.from(context).cancel(notificationId(reminder))
    }

    /** A quiet, permanent "next call reminder" line in the shade. */
    fun showNext(context: Context, reminder: CallReminder?) {
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (reminder == null) {
            manager.cancel(UPCOMING_NOTIFICATION_ID)
            return
        }
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, CallRemindersActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPCOMING)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.call_reminders_next_title))
            .setContentText("${reminder.title} · ${formatTime(reminder.dueAtMillis)}")
            .setOngoing(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(UPCOMING_NOTIFICATION_ID, notification) }
            .onFailure { Timber.d(it, "Could not post the upcoming-reminder notification") }
    }

    private fun alarmSubtitle(context: Context, reminder: CallReminder): String = buildList {
        reminder.phone?.let(::add)
        reminder.notes?.let(::add)
        add(context.getString(R.string.call_reminders_due_at, formatTime(reminder.originalDueAtMillis)))
    }.joinToString("\n")

    fun formatTime(millis: Long): String =
        SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
}
