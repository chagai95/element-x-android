/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.receivers.CallReminderAlarmReceiver
import io.element.android.features.callreminders.impl.store.CallReminderStore
import io.element.android.features.callreminders.impl.ui.CallRemindersActivity
import timber.log.Timber

/**
 * Keeps the system alarm clock in step with the stored reminders.
 *
 * It uses [AlarmManager.setAlarmClock], the same API a clock app uses: it is exact, it survives
 * Doze, and it shows the next reminder in the status bar as a real alarm. That is the difference
 * between "a notification arrives roughly then" and "the phone rings at 15:00".
 */
class CallReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService<AlarmManager>()

    fun schedule(reminder: CallReminder) {
        val manager = alarmManager ?: return
        if (!reminder.isOpen) {
            cancel(reminder.id)
            return
        }
        val fireIntent = alarmPendingIntent(reminder.id, CallReminderAlarmReceiver.ACTION_RING)
        val triggerAt = reminder.dueAtMillis

        runCatching {
            if (canScheduleExact()) {
                // setAlarmClock also publishes the next alarm to the status bar and lock screen,
                // and is never deferred by Doze or battery optimisation.
                manager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, showAlarmPendingIntent()),
                    fireIntent,
                )
            } else {
                // Android 12 with exact alarms revoked. Still allowed while idle, just not
                // guaranteed to the second — better than silently not ringing at all.
                Timber.w("Exact alarms are not permitted; falling back to an inexact-while-idle alarm")
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, fireIntent)
            }
        }.onFailure { Timber.e(it, "Could not schedule the alarm for %s", reminder.id) }
    }

    fun cancel(reminderId: String) {
        val manager = alarmManager ?: return
        manager.cancel(alarmPendingIntent(reminderId, CallReminderAlarmReceiver.ACTION_RING))
        manager.cancel(alarmPendingIntent(reminderId, CallReminderAlarmReceiver.ACTION_AUTO_SILENCE))
    }

    /**
     * Arms the "stop ringing and try again later" alarm, so an unanswered reminder does not ring
     * forever and does not simply vanish either.
     */
    fun scheduleAutoSilence(reminderId: String, atMillis: Long) {
        val manager = alarmManager ?: return
        val intent = alarmPendingIntent(reminderId, CallReminderAlarmReceiver.ACTION_AUTO_SILENCE)
        runCatching {
            if (canScheduleExact()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, atMillis, intent)
            }
        }.onFailure { Timber.e(it, "Could not schedule auto-silence for %s", reminderId) }
    }

    /**
     * Rebuilds every alarm from the store. Called after a reboot, after the app is updated, after
     * the clock or time zone changes, and whenever the reminders screen is opened — all the
     * moments where the system may have thrown our alarms away.
     */
    fun rescheduleAll(store: CallReminderStore) {
        val now = System.currentTimeMillis()
        store.open().forEach { reminder ->
            when {
                reminder.dueAtMillis > now -> schedule(reminder)
                // Its moment passed while the phone was off, and recently enough that ringing now
                // is still useful. Ring rather than never.
                reminder.state == CallReminder.State.Scheduled &&
                    now - reminder.dueAtMillis <= CATCH_UP_WINDOW_MILLIS -> {
                    context.sendBroadcast(
                        Intent(context, CallReminderAlarmReceiver::class.java).apply {
                            action = CallReminderAlarmReceiver.ACTION_RING
                            putExtra(CallReminderAlarmReceiver.EXTRA_REMINDER_ID, reminder.id)
                        }
                    )
                }
                // Overdue but already rung, or long past: leave it open and visible, and do not
                // ring. Anything else turns a reminder the user once ignored into a klaxon that
                // goes off every time they open the app, for ever — an alarm nobody can escape is
                // worse than a missed one, and it is how people stop trusting a reminder app.
                else -> Timber.d("Not re-ringing overdue reminder %s (%s)", reminder.id, reminder.state)
            }
        }
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true

    private fun alarmPendingIntent(reminderId: String, action: String): PendingIntent {
        val intent = Intent(context, CallReminderAlarmReceiver::class.java).apply {
            this.action = action
            // The data uri makes each (reminder, action) pair a distinct PendingIntent, which is
            // what lets one reminder be cancelled without touching any other.
            data = android.net.Uri.parse("matrixchat://reminder/$reminderId/$action")
            putExtra(CallReminderAlarmReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Where the system sends you when you tap the alarm icon in the status bar. */
    private fun showAlarmPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, CallRemindersActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        /**
         * How late a reminder may be and still be worth ringing about on the way back from a
         * reboot. An hour matches the tolerance the message parser already applies to a reminder
         * that arrives late.
         */
        const val CATCH_UP_WINDOW_MILLIS = 60 * 60 * 1000L
    }
}
