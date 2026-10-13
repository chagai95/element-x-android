/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.notifications.CallReminderNotifications
import io.element.android.features.callreminders.impl.scheduler.CallReminderScheduler
import io.element.android.features.callreminders.impl.store.CallReminderSettings
import io.element.android.features.callreminders.impl.store.CallReminderStore
import io.element.android.features.callreminders.impl.ui.CallReminderAlarmActivity
import timber.log.Timber

/**
 * The moment the alarm goes off.
 *
 * Deliberately short: post the ringing notification (which carries the full-screen intent, so the
 * alarm screen comes up even over a lock screen), try to bring the screen up directly as well,
 * and arm the "stop ringing and come back later" alarm. No service, nothing long-running — a
 * broadcast receiver has milliseconds, and everything that needs to last is a notification or an
 * alarm, both of which outlive the process.
 */
class CallReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val store = CallReminderStore.get(context)
        val settings = CallReminderSettings.get(context)
        val reminder = store.get(id) ?: run {
            Timber.w("Alarm fired for an unknown reminder %s", id)
            return
        }

        when (intent.action) {
            ACTION_RING -> ring(context, store, settings, reminder)
            ACTION_AUTO_SILENCE -> autoSilence(context, store, settings, reminder)
            else -> Timber.w("Unexpected alarm action %s", intent.action)
        }
    }

    private fun ring(
        context: Context,
        store: CallReminderStore,
        settings: CallReminderSettings,
        reminder: CallReminder,
    ) {
        if (!reminder.isOpen) return
        val ringing = store.update(reminder.id) { it.copy(state = CallReminder.State.Ringing) } ?: return
        val gentle = ringing.gentle || settings.gentleByDefault
        CallReminderNotifications.showAlarm(context, ringing, gentle = gentle)

        // Belt and braces: the full-screen intent is the reliable path on a locked screen, this
        // is the one that works when the phone is unlocked and in use.
        runCatching {
            context.startActivity(CallReminderAlarmActivity.intent(context, ringing.id))
        }.onFailure { Timber.d(it, "Could not launch the alarm screen directly; the notification will") }

        if (settings.escalationEnabled) {
            CallReminderScheduler(context).scheduleAutoSilence(
                ringing.id,
                System.currentTimeMillis() + settings.autoSilenceMinutes * 60_000L,
            )
        }
    }

    /**
     * Nobody dealt with it. Stop making noise, then come back in a few minutes — up to the
     * configured number of tries — so a missed reminder nags instead of disappearing.
     */
    private fun autoSilence(
        context: Context,
        store: CallReminderStore,
        settings: CallReminderSettings,
        reminder: CallReminder,
    ) {
        if (reminder.state != CallReminder.State.Ringing) return
        CallReminderNotifications.cancelAlarm(context, reminder)

        val scheduler = CallReminderScheduler(context)
        if (reminder.escalationCount >= settings.maxEscalations) {
            // Out of tries. Leave it open and visible rather than silently resolved — an
            // unanswered reminder to call someone is still a thing the user has to do.
            val left = store.update(reminder.id) { it.copy(state = CallReminder.State.Snoozed) }
            left?.let { CallReminderNotifications.showNext(context, it) }
            return
        }
        val next = System.currentTimeMillis() + ESCALATION_GAP_MINUTES * 60_000L
        val updated = store.update(reminder.id) {
            it.copy(
                state = CallReminder.State.Snoozed,
                dueAtMillis = next,
                escalationCount = it.escalationCount + 1,
            )
        } ?: return
        scheduler.schedule(updated)
        Timber.d("Re-arming reminder %s (attempt %d)", updated.id, updated.escalationCount)
    }

    companion object {
        const val ACTION_RING = "io.element.android.features.callreminders.RING"
        const val ACTION_AUTO_SILENCE = "io.element.android.features.callreminders.AUTO_SILENCE"
        const val EXTRA_REMINDER_ID = "reminder_id"

        /** Gap between an unanswered alarm and the next attempt. */
        private const val ESCALATION_GAP_MINUTES = 5
    }
}
