/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.features.callreminders.impl.di.CallRemindersBindings
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.notifications.CallReminderNotifications
import io.element.android.features.callreminders.impl.receivers.CallReminderActionReceiver
import io.element.android.features.callreminders.impl.ring.AlarmRingService
import io.element.android.features.callreminders.impl.ring.TonePreview
import io.element.android.features.callreminders.impl.ui.CallReminderAlarmActivity
import io.element.android.features.callreminders.impl.scheduler.CallReminderScheduler
import io.element.android.features.callreminders.impl.store.CallReminderSettings
import io.element.android.features.callreminders.impl.store.CallReminderStore
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.designsystem.theme.ElementThemeApp
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import timber.log.Timber

/**
 * The reminders list: what is coming, what is snoozed, what was done, plus the settings.
 *
 * Reachable from the alarm screen, from the "next call reminder" notification, and from the
 * launcher icon's long-press shortcut.
 */
class CallRemindersActivity : AppCompatActivity() {
    @Inject lateinit var appPreferencesStore: AppPreferencesStore

    @Inject lateinit var featureFlagService: FeatureFlagService

    @Inject lateinit var buildMeta: BuildMeta

    /** Set once the screen is composed, so onResume can push a freshly granted permission in. */
    private var dndRefresh: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindings<CallRemindersBindings>().inject(this)

        val store = CallReminderStore.get(this)
        val settings = CallReminderSettings.get(this)
        val scheduler = CallReminderScheduler(this)

        // Opening the list is a good moment to make sure the system's alarms still match what we
        // believe, e.g. after the app was force-stopped.
        store.prune()
        scheduler.rescheduleAll(store)

        setContent {
            ElementThemeApp(
                appPreferencesStore = appPreferencesStore,
                featureFlagService = featureFlagService,
                compoundLight = SemanticColorsLightDark.default.light,
                compoundDark = SemanticColorsLightDark.default.dark,
                buildMeta = buildMeta,
            ) {
                val reminders by store.reminders.collectAsState()
                // The Do Not Disturb flag is read from the system, not stored, so it is folded in
                // here and refreshed in onResume — the user may have just granted it.
                var settingsState by remember {
                    mutableStateOf(settings.toState(CallReminderNotifications.hasDoNotDisturbAccess(this@CallRemindersActivity)))
                }
                dndRefresh = { settingsState = settingsState.copy(hasDoNotDisturbAccess = it) }

                CallRemindersScreen(
                    reminders = reminders,
                    settings = settingsState,
                    onBack = { finish() },
                    onDone = { act(it, CallReminderActionReceiver.ACTION_DONE) },
                    onSnooze = { reminder, minutes -> act(reminder, CallReminderActionReceiver.ACTION_SNOOZE, minutes = minutes) },
                    onCall = { act(it, CallReminderActionReceiver.ACTION_CALL, phone = it.phone) },
                    onDelete = { reminder ->
                        scheduler.cancel(reminder.id)
                        CallReminderNotifications.cancelAlarm(this, reminder)
                        store.remove(reminder.id)
                        CallReminderNotifications.showNext(this, store.open().firstOrNull())
                    },
                    onPreviewTone = { tone -> TonePreview.play(this, tone) },
                    onOpenDoNotDisturbSettings = ::openDoNotDisturbSettings,
                    onSettingChange = { changed ->
                        settings.apply(changed)
                        settingsState = changed
                        // Channels remember their Do Not Disturb bypass from creation time, so a
                        // freshly granted permission has to be pushed into them.
                        CallReminderNotifications.ensureChannels(this)
                        CallReminderNotifications.showNext(
                            this,
                            store.open().firstOrNull().takeIf { changed.showNextReminderNotification },
                        )
                    },
                    onTestRing = {
                        // Rings the real alarm, through the real path, with a throwaway reminder,
                        // so "will this actually wake me?" is answerable without waiting for one.
                        // It is stored while it rings so the full-screen alarm screen works too,
                        // and removed again the moment the user acts on it.
                        val test = testReminder(settingsState)
                        store.remove(test.id)
                        store.upsert(test)
                        TonePreview.stop()
                        if (!AlarmRingService.ring(this, test, settingsState.gentleByDefault)) {
                            CallReminderNotifications.showAlarm(
                                context = this,
                                reminder = test,
                                gentle = settingsState.gentleByDefault,
                            )
                        }
                        // And bring the alarm screen up, which is the half a test was missing:
                        // on an unlocked phone the system shows the notification as a heads-up
                        // rather than firing its full-screen intent, so testing the alarm never
                        // showed the screen a real alarm actually shows. The alarm receiver
                        // already does this for a real reminder; now the test does the same
                        // thing through the same path.
                        startActivity(CallReminderAlarmActivity.intent(this, test.id))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dndRefresh?.invoke(CallReminderNotifications.hasDoNotDisturbAccess(this))
    }

    override fun onPause() {
        // A tone preview must not follow the user out of the screen.
        TonePreview.stop()
        super.onPause()
    }

    /**
     * Opens the system's Do Not Disturb access screen.
     *
     * Android will not let an app grant itself the right to ignore Do Not Disturb; only the user
     * can, on this screen. Without it the alarm stream still cuts through a silenced ringer,
     * which is the common case — this is for a full Do Not Disturb.
     */
    private fun openDoNotDisturbSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Timber.w(it, "No Do Not Disturb access screen on this device") }
    }

    private fun act(reminder: CallReminder, action: String, phone: String? = null, minutes: Int? = null) {
        sendBroadcast(CallReminderActionReceiver.intent(this, reminder.id, action, phone, minutes))
    }

    private fun testReminder(settings: CallRemindersSettingsState) = CallReminder(
        id = CallReminderActionReceiver.TEST_REMINDER_ID,
        roomId = "",
        sessionId = "",
        title = getString(io.element.android.features.callreminders.impl.R.string.call_reminders_test_title),
        contact = null,
        phone = null,
        notes = getString(io.element.android.features.callreminders.impl.R.string.call_reminders_test_notes),
        dueAtMillis = System.currentTimeMillis(),
        snoozeOptionsMinutes = settings.snoozeOptionsMinutes,
        state = CallReminder.State.Ringing,
    )

}

private fun CallReminderSettings.toState(hasDoNotDisturbAccess: Boolean) = CallRemindersSettingsState(
    ringInSilentMode = ringInSilentMode,
    escalationEnabled = escalationEnabled,
    gentleByDefault = gentleByDefault,
    sendAcknowledgements = sendAcknowledgements,
    showNextReminderNotification = showNextReminderNotification,
    snoozeOptionsMinutes = snoozeOptionsMinutes,
    toneId = toneId,
    maxOutVolume = maxOutVolume,
    rampUpSeconds = rampUpSeconds,
    rampStartPercent = rampStartPercent,
    vibrate = vibrate,
    torchBlink = torchBlink,
    hasDoNotDisturbAccess = hasDoNotDisturbAccess,
)

private fun CallReminderSettings.apply(state: CallRemindersSettingsState) {
    ringInSilentMode = state.ringInSilentMode
    escalationEnabled = state.escalationEnabled
    gentleByDefault = state.gentleByDefault
    sendAcknowledgements = state.sendAcknowledgements
    showNextReminderNotification = state.showNextReminderNotification
    snoozeOptionsMinutes = state.snoozeOptionsMinutes
    toneId = state.toneId
    maxOutVolume = state.maxOutVolume
    rampUpSeconds = state.rampUpSeconds
    rampStartPercent = state.rampStartPercent
    vibrate = state.vibrate
    torchBlink = state.torchBlink
}
