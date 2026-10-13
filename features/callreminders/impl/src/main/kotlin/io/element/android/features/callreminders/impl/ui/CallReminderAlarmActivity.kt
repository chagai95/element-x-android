/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metro.Inject
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.features.callreminders.impl.di.CallRemindersBindings
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.notifications.CallReminderNotifications
import io.element.android.features.callreminders.impl.receivers.CallReminderActionReceiver
import io.element.android.features.callreminders.impl.scheduler.CallReminderScheduler
import io.element.android.features.callreminders.impl.store.CallReminderSettings
import io.element.android.features.callreminders.impl.store.CallReminderStore
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.designsystem.theme.ElementThemeApp
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import timber.log.Timber
import java.util.Calendar

/**
 * The full-screen alarm.
 *
 * Shown over the lock screen, with the screen turned on, and not dismissible by swiping — the
 * only ways out are the buttons, exactly like a clock alarm. The ringing itself belongs to the
 * notification (see [CallReminderNotifications]); this activity owns the picture and the choices,
 * which is why killing it does not silence anything and why acting on it always does.
 */
class CallReminderAlarmActivity : AppCompatActivity() {
    @Inject lateinit var appPreferencesStore: AppPreferencesStore

    @Inject lateinit var featureFlagService: FeatureFlagService

    @Inject lateinit var buildMeta: BuildMeta

    private lateinit var store: CallReminderStore
    private lateinit var settings: CallReminderSettings
    private lateinit var scheduler: CallReminderScheduler
    private var reminderId: String? = null

    /** Set when the user taps "Call now", so we can ask how it went when they come back. */
    private var leftToDial = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindings<CallRemindersBindings>().inject(this)

        store = CallReminderStore.get(this)
        settings = CallReminderSettings.get(this)
        scheduler = CallReminderScheduler(this)

        showOverLockScreen()

        reminderId = intent?.getStringExtra(EXTRA_REMINDER_ID)
        if (reminderId == null || store.get(reminderId!!) == null) {
            Timber.w("Alarm screen opened without a known reminder; closing")
            finish()
            return
        }

        setContent {
            ElementThemeApp(
                appPreferencesStore = appPreferencesStore,
                featureFlagService = featureFlagService,
                compoundLight = SemanticColorsLightDark.default.light,
                compoundDark = SemanticColorsLightDark.default.dark,
                buildMeta = buildMeta,
            ) {
                val reminders by store.reminders.collectAsState()
                val reminder = reminders.firstOrNull { it.id == reminderId }
                if (reminder == null) {
                    finish()
                } else {
                    CallReminderAlarmScreen(
                        reminder = reminder,
                        isRinging = reminder.state == CallReminder.State.Ringing,
                        justCalled = leftToDial && reminder.state == CallReminder.State.Called,
                        onCallNow = { act(CallReminderActionReceiver.ACTION_CALL, phone = reminder.phone) },
                        onDone = { act(CallReminderActionReceiver.ACTION_DONE); finish() },
                        onSnooze = { minutes -> act(CallReminderActionReceiver.ACTION_SNOOZE, minutes = minutes); finish() },
                        onSnoozeTomorrowMorning = { snoozeUntilTomorrowMorning(reminder) },
                        onOpenChat = { openChat(reminder) },
                        onSilence = { silence(reminder) },
                        onOpenAllReminders = {
                            startActivity(Intent(this, CallRemindersActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_REMINDER_ID)?.let { reminderId = it }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the dialer: the reminder is in "called" state and the screen now asks
        // whether it is done or should come back later, rather than leaving it in limbo.
        if (leftToDial) {
            reminderId?.let { id -> store.get(id) }
        }
    }

    /**
     * Volume keys silence the ring but leave the screen up, which is what every alarm clock does
     * and what stops a half-awake press from losing the reminder entirely.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA
        ) {
            reminderId?.let { id -> store.get(id)?.let(::silence) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Swiping back must not dismiss an alarm; the user has to choose what happens to it. */
    @Deprecated("Deliberate: an alarm is not dismissible by the back gesture")
    @Suppress("MissingSuperCall", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = Unit

    private fun act(action: String, phone: String? = null, minutes: Int? = null) {
        val id = reminderId ?: return
        if (action == CallReminderActionReceiver.ACTION_CALL) leftToDial = true
        sendBroadcast(CallReminderActionReceiver.intent(this, id, action, phone, minutes))
    }

    private fun silence(reminder: CallReminder) {
        CallReminderNotifications.showAlarm(
            context = this,
            reminder = reminder,
            gentle = reminder.gentle || settings.gentleByDefault,
            silent = true,
        )
    }

    /** One tap for the commonest real answer: not now, first thing tomorrow. */
    private fun snoozeUntilTomorrowMorning(reminder: CallReminder) {
        val target = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, TOMORROW_MORNING_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        CallReminderNotifications.cancelAlarm(this, reminder)
        val snoozed = store.update(reminder.id) {
            it.copy(state = CallReminder.State.Snoozed, dueAtMillis = target, escalationCount = 0, acknowledged = false)
        }
        snoozed?.let {
            scheduler.schedule(it)
            CallReminderNotifications.showNext(this, store.open().firstOrNull())
        }
        finish()
    }

    private fun openChat(reminder: CallReminder) {
        // The app's own deep link: matrixchat://open/<sessionId>/<roomId>.
        val uri = Uri.parse("matrixchat://open/${Uri.encode(reminder.sessionId)}/${Uri.encode(reminder.roomId)}")
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Timber.e(it, "Could not open the reminder's room") }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        private const val TOMORROW_MORNING_HOUR = 9

        fun intent(context: Context, reminderId: String): Intent =
            Intent(context, CallReminderAlarmActivity::class.java).apply {
                putExtra(EXTRA_REMINDER_ID, reminderId)
                data = Uri.parse("matrixchat://reminder-alarm/$reminderId")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
            }
    }
}
