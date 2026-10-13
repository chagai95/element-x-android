/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.element.android.features.callreminders.impl.notifications.CallReminderNotifications
import io.element.android.features.callreminders.impl.scheduler.CallReminderScheduler
import io.element.android.features.callreminders.impl.store.CallReminderStore
import timber.log.Timber

/**
 * Rebuilds every alarm after the system has thrown them away.
 *
 * Android drops all of an app's alarms on reboot, when the app is updated, and it invalidates
 * wall-clock alarms when the clock or the time zone changes. Without this, a reminder set on
 * Monday would silently never ring if the phone were restarted on Tuesday — the failure mode
 * that makes people stop trusting a reminder app.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                val store = CallReminderStore.get(context)
                store.prune()
                CallReminderNotifications.ensureChannels(context)
                CallReminderScheduler(context).rescheduleAll(store)
                CallReminderNotifications.showNext(context, store.open().firstOrNull())
                Timber.d("Rescheduled %d call reminders after %s", store.open().size, intent.action)
            }
            else -> Unit
        }
    }
}
