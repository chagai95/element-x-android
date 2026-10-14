/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.receivers

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.zacsweers.metro.Inject
import io.element.android.features.callreminders.impl.di.CallRemindersBindings
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.notifications.CallReminderNotifications
import io.element.android.features.callreminders.impl.ring.AlarmRingService
import io.element.android.features.callreminders.impl.scheduler.CallReminderScheduler
import io.element.android.features.callreminders.impl.session.CallReminderAcknowledger
import io.element.android.features.callreminders.impl.store.CallReminderStore
import io.element.android.libraries.architecture.bindings
import timber.log.Timber

/**
 * Everything the user can do about a ringing reminder, from the notification or the alarm screen.
 *
 * All of it goes through one receiver so the notification buttons and the on-screen buttons
 * cannot drift apart: tapping "Snooze 15" in the shade and tapping it on the alarm screen are
 * literally the same code path.
 */
class CallReminderActionReceiver : BroadcastReceiver() {
    @Inject lateinit var acknowledger: CallReminderAcknowledger

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val store = CallReminderStore.get(context)
        val scheduler = CallReminderScheduler(context)
        val reminder = store.get(id) ?: return

        when (intent.action) {
            ACTION_DONE -> {
                stopRinging(context, store, scheduler, reminder)
                store.update(id) {
                    it.copy(
                        state = CallReminder.State.Done,
                        resolvedAtMillis = System.currentTimeMillis(),
                        acknowledged = false,
                    )
                }
                Timber.d("Reminder %s marked done", id)
            }
            ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 15).coerceAtLeast(1)
                stopRinging(context, store, scheduler, reminder)
                val snoozed = store.update(id) {
                    it.copy(
                        state = CallReminder.State.Snoozed,
                        dueAtMillis = System.currentTimeMillis() + minutes * 60_000L,
                        // A deliberate snooze is the user taking charge, so the nagging counter
                        // starts again from zero.
                        escalationCount = 0,
                        // Stamped only here, never on the app's own re-arming, so the chat is
                        // told "pushed to 15:20" when the user chose it and stays quiet when the
                        // app is simply nagging.
                        snoozedAtMillis = System.currentTimeMillis(),
                        acknowledged = false,
                    )
                }
                snoozed?.let {
                    scheduler.schedule(it)
                    CallReminderNotifications.showNext(context, store.open().firstOrNull())
                }
                Timber.d("Reminder %s snoozed by %d minutes", id, minutes)
            }
            ACTION_CALL -> {
                val phone = intent.getStringExtra(EXTRA_PHONE) ?: reminder.phone
                stopRinging(context, store, scheduler, reminder)
                store.update(id) {
                    it.copy(
                        state = CallReminder.State.Called,
                        // Without this the acknowledgement never fires: "📞 Called" is only sent
                        // for a reminder that records when it was resolved.
                        resolvedAtMillis = System.currentTimeMillis(),
                        acknowledged = false,
                    )
                }
                if (phone != null) {
                    // ACTION_DIAL, never ACTION_CALL: it opens the dialer with the number filled
                    // in and lets the user press the button. The app never needs, and never asks
                    // for, permission to place a call.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { Timber.e(it, "No dialer available") }
                }
            }
            else -> Timber.w("Unknown reminder action %s", intent.action)
        }
        // The throwaway reminder behind the "test the alarm" button leaves no trace.
        if (id == TEST_REMINDER_ID) {
            scheduler.cancel(id)
            CallReminderNotifications.cancelAlarm(context, reminder)
            store.remove(id)
        } else {
            // Tell the room now, rather than whenever a session next happens to be alive. The tap
            // nearly always happens with the app long since evicted from memory, which used to
            // mean "Done" sat on the phone for hours or days while the chat showed nothing.
            runCatching {
                context.bindings<CallRemindersBindings>().inject(this)
                acknowledger.flushSoon()
            }.onFailure { Timber.w(it, "Could not acknowledge right away; the session loop will retry") }
        }
        // Collapse the shade so the user sees the result of what they just tapped.
        @Suppress("DEPRECATION")
        runCatching { context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) }
    }

    private fun stopRinging(
        context: Context,
        store: CallReminderStore,
        scheduler: CallReminderScheduler,
        reminder: CallReminder,
    ) {
        // First the noise, then the picture: stopping the service releases the tone, the
        // vibration, the torch and the borrowed alarm volume.
        AlarmRingService.stop(context)
        CallReminderNotifications.cancelAlarm(context, reminder)
        scheduler.cancel(reminder.id)
        CallReminderNotifications.showNext(context, store.open().firstOrNull { it.id != reminder.id })
    }

    companion object {
        const val ACTION_DONE = "io.element.android.features.callreminders.DONE"
        const val ACTION_SNOOZE = "io.element.android.features.callreminders.SNOOZE"
        const val ACTION_CALL = "io.element.android.features.callreminders.CALL"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_PHONE = "phone"

        /** Kept in step with CallRemindersActivity's test-ring button. */
        const val TEST_REMINDER_ID = "test-ring"

        fun intent(context: Context, reminderId: String, action: String, phone: String? = null, minutes: Int? = null): Intent =
            Intent(context, CallReminderActionReceiver::class.java).apply {
                this.action = action
                // Distinct data per (reminder, action, argument) so PendingIntents never collide.
                data = Uri.parse("matrixchat://reminder-action/$reminderId/$action/${minutes ?: 0}")
                putExtra(EXTRA_REMINDER_ID, reminderId)
                phone?.let { putExtra(EXTRA_PHONE, it) }
                minutes?.let { putExtra(EXTRA_MINUTES, it) }
            }

        fun pendingIntent(context: Context, reminderId: String, action: String, phone: String? = null, minutes: Int? = null): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                intent(context, reminderId, action, phone, minutes),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
