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
 * The alarm itself.
 *
 * The ringing is done by the *notification*, not by a service: a notification on a channel whose
 * sound is the alarm ringtone with [AudioAttributes.USAGE_ALARM], marked insistent so the sound
 * loops until it is dealt with, and carrying a full-screen intent so the alarm screen comes up
 * over the lock screen. That gives alarm-clock behaviour — loops, cuts through a silenced ringer,
 * wakes the screen — with nothing to keep alive and nothing to leak if the process dies.
 *
 * Two channels exist so "loud" and "gentle" are real, system-level choices the user can also
 * retune in Android's own settings.
 */
object CallReminderNotifications {
    const val CHANNEL_LOUD = "call_reminders_loud"
    const val CHANNEL_GENTLE = "call_reminders_gentle"
    const val CHANNEL_UPCOMING = "call_reminders_upcoming"

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
            // Best effort: only takes effect once the user grants Do Not Disturb access. Without
            // it the alarm stream still cuts through a silenced ringer, which is the common case.
            runCatching { setBypassDnd(true) }.onFailure { Timber.d("No Do Not Disturb access") }
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
        manager.createNotificationChannels(listOf(loud, gentle, upcoming))
    }

    fun notificationId(reminder: CallReminder): Int = ALARM_NOTIFICATION_ID_BASE + (reminder.id.hashCode() and 0xFFFF)

    /**
     * Posts the ringing alarm.
     *
     * @param silent re-post without sound, used when the user hits a volume key: the screen and
     * the buttons stay, the noise stops. That is what a clock alarm does too.
     */
    fun showAlarm(context: Context, reminder: CallReminder, gentle: Boolean, silent: Boolean = false) {
        ensureChannels(context)
        val channel = when {
            silent -> CHANNEL_UPCOMING
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
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(reminder.originalDueAtMillis)
            .setShowWhen(true)
            .setContentIntent(fullScreen)
            // The full-screen intent is what brings the alarm screen up over the lock screen.
            .setFullScreenIntent(fullScreen, true)
            .setOnlyAlertOnce(false)

        if (silent) {
            builder.setSilent(true)
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

        reminder.phone?.let { phone ->
            builder.addAction(
                0,
                context.getString(R.string.call_reminders_action_call_now),
                CallReminderActionReceiver.pendingIntent(context, reminder.id, CallReminderActionReceiver.ACTION_CALL, phone),
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
            // NotificationCompat setter for it, so it goes on the built notification. This is
            // what makes the thing ring rather than ping once and be missed.
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
        }

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
