/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.features.callreminders.impl.ui.CallReminderAlarmActivity
import io.element.android.features.callreminders.impl.ui.CallRemindersActivity

/**
 * The two activities this feature owns are started by the system (an alarm, a notification, a
 * launcher shortcut) rather than by app navigation, so they ask the application graph for what
 * they need instead of being constructed with it.
 */
@ContributesTo(AppScope::class)
interface CallRemindersBindings {
    fun inject(activity: CallReminderAlarmActivity)
    fun inject(activity: CallRemindersActivity)
}
