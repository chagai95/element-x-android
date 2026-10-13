/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.callreminders.impl.R
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.notifications.CallReminderNotifications
import io.element.android.features.callreminders.impl.ring.AlarmTone
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.ListSectionHeader
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.TopAppBar

/**
 * Everything the phone knows about call reminders: what is coming, what is snoozed, what was
 * done, and the few settings that change how the alarm behaves.
 *
 * Deliberately a plain list rather than a navigation destination inside the app's main graph —
 * the feature then needs no hook into upstream navigation at all, which keeps future merges with
 * Element X clean.
 */
@Composable
fun CallRemindersScreen(
    reminders: List<CallReminder>,
    settings: CallRemindersSettingsState,
    onBack: () -> Unit,
    onDone: (CallReminder) -> Unit,
    onSnooze: (CallReminder, Int) -> Unit,
    onCall: (CallReminder) -> Unit,
    onDelete: (CallReminder) -> Unit,
    onSettingChange: (CallRemindersSettingsState) -> Unit,
    onTestRing: () -> Unit,
    onPreviewTone: (AlarmTone) -> Unit,
    onOpenDoNotDisturbSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val upcoming = remember(reminders) {
        reminders.filter { it.state == CallReminder.State.Scheduled || it.state == CallReminder.State.Ringing }
    }
    val snoozed = remember(reminders) { reminders.filter { it.state == CallReminder.State.Snoozed } }
    val past = remember(reminders) {
        reminders.filter { it.state == CallReminder.State.Done || it.state == CallReminder.State.Called }
            .sortedByDescending { it.resolvedAtMillis ?: it.dueAtMillis }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                titleStr = stringResource(R.string.call_reminders_title),
                navigationIcon = { BackButton(onClick = onBack) },
                actions = {
                    TextButton(
                        text = stringResource(
                            if (showSettings) R.string.call_reminders_action_hide_settings else R.string.call_reminders_action_settings
                        ),
                        onClick = { showSettings = !showSettings },
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (showSettings) {
                item { SoundSection(settings, onSettingChange, onTestRing, onPreviewTone, onOpenDoNotDisturbSettings) }
                item { SettingsSection(settings, onSettingChange) }
                item { HorizontalDivider() }
            }
            if (reminders.isEmpty()) {
                item { EmptyState() }
            }
            if (upcoming.isNotEmpty()) {
                item { ListSectionHeader(title = stringResource(R.string.call_reminders_section_upcoming), hasDivider = false) }
                items(upcoming, key = { it.id }) { reminder ->
                    ReminderRow(reminder, onDone, onSnooze, onCall, onDelete)
                }
            }
            if (snoozed.isNotEmpty()) {
                item { ListSectionHeader(title = stringResource(R.string.call_reminders_section_snoozed), hasDivider = false) }
                items(snoozed, key = { it.id }) { reminder ->
                    ReminderRow(reminder, onDone, onSnooze, onCall, onDelete)
                }
            }
            if (past.isNotEmpty()) {
                item { ListSectionHeader(title = stringResource(R.string.call_reminders_section_done), hasDivider = false) }
                items(past, key = { it.id }) { reminder ->
                    ReminderRow(reminder, onDone, onSnooze, onCall, onDelete)
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.call_reminders_empty_title),
            style = ElementTheme.typography.fontBodyLgMedium,
            color = ElementTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.call_reminders_empty_subtitle),
            style = ElementTheme.typography.fontBodyMdRegular,
            color = ElementTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ReminderRow(
    reminder: CallReminder,
    onDone: (CallReminder) -> Unit,
    onSnooze: (CallReminder, Int) -> Unit,
    onCall: (CallReminder) -> Unit,
    onDelete: (CallReminder) -> Unit,
) {
    Column {
        ListItem(
            supportingContent = {
                Column {
                    Text(
                        text = CallReminderNotifications.formatTime(reminder.dueAtMillis),
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = ElementTheme.colors.textSecondary,
                    )
                    reminder.notes?.let {
                        Text(
                            text = it,
                            style = ElementTheme.typography.fontBodySmRegular,
                            color = ElementTheme.colors.textSecondary,
                        )
                    }
                }
            },
            content = { Text(reminder.title) },
        )
        Row(
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (reminder.isOpen) {
                reminder.phone?.let {
                    TextButton(text = stringResource(R.string.call_reminders_action_call_now), onClick = { onCall(reminder) })
                }
                TextButton(text = stringResource(R.string.call_reminders_action_done), onClick = { onDone(reminder) })
                TextButton(
                    text = stringResource(R.string.call_reminders_action_snooze_minutes, SNOOZE_SHORTCUT_MINUTES),
                    onClick = { onSnooze(reminder, SNOOZE_SHORTCUT_MINUTES) },
                )
            }
            TextButton(text = stringResource(R.string.call_reminders_action_delete), onClick = { onDelete(reminder) })
        }
        HorizontalDivider()
    }
}

/**
 * Everything about how loud it is and what it sounds like.
 *
 * Put first and kept together because this is the part that was wrong: the alarm was ringing at
 * whatever the phone's alarm volume happened to be. "Use full alarm volume" and the tone picker
 * are the two controls that matter, so they are the two at the top.
 */
@Composable
private fun SoundSection(
    settings: CallRemindersSettingsState,
    onChange: (CallRemindersSettingsState) -> Unit,
    onTestRing: () -> Unit,
    onPreviewTone: (AlarmTone) -> Unit,
    onOpenDoNotDisturbSettings: () -> Unit,
) {
    var expandedTones by rememberSaveable { mutableStateOf(false) }
    val selected = AlarmTone.fromId(settings.toneId)
    Column {
        ListSectionHeader(title = stringResource(R.string.call_reminders_sound_title), hasDivider = false)
        ListItem(
            supportingContent = { Text(stringResource(selected.labelRes)) },
            trailingContent = ListItemContent.Text(
                stringResource(if (expandedTones) R.string.call_reminders_action_hide_settings else R.string.call_reminders_action_change)
            ),
            onClick = { expandedTones = !expandedTones },
            content = { Text(stringResource(R.string.call_reminders_setting_tone)) },
        )
        if (expandedTones) {
            AlarmTone.entries.forEach { tone ->
                ListItem(
                    supportingContent = { Text(stringResource(tone.descriptionRes)) },
                    trailingContent = ListItemContent.RadioButton(selected = tone == selected),
                    onClick = {
                        onChange(settings.copy(toneId = tone.id))
                        onPreviewTone(tone)
                    },
                    content = { Text(stringResource(tone.labelRes)) },
                )
            }
        }
        SwitchRow(
            title = stringResource(R.string.call_reminders_setting_max_volume),
            subtitle = stringResource(R.string.call_reminders_setting_max_volume_subtitle),
            checked = settings.maxOutVolume,
            onChange = { onChange(settings.copy(maxOutVolume = it)) },
        )
        ListItem(
            supportingContent = {
                Text(
                    if (settings.rampUpSeconds == 0) {
                        stringResource(R.string.call_reminders_setting_ramp_off)
                    } else {
                        stringResource(R.string.call_reminders_setting_ramp_value, settings.rampStartPercent, settings.rampUpSeconds)
                    }
                )
            },
            trailingContent = ListItemContent.Text(stringResource(R.string.call_reminders_action_change)),
            onClick = { onChange(settings.copy(rampUpSeconds = settings.nextRampSeconds())) },
            content = { Text(stringResource(R.string.call_reminders_setting_ramp)) },
        )
        SwitchRow(
            title = stringResource(R.string.call_reminders_setting_vibrate),
            subtitle = stringResource(R.string.call_reminders_setting_vibrate_subtitle),
            checked = settings.vibrate,
            onChange = { onChange(settings.copy(vibrate = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.call_reminders_setting_torch),
            subtitle = stringResource(R.string.call_reminders_setting_torch_subtitle),
            checked = settings.torchBlink,
            onChange = { onChange(settings.copy(torchBlink = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.call_reminders_setting_silent_mode),
            subtitle = stringResource(R.string.call_reminders_setting_silent_mode_subtitle),
            checked = settings.ringInSilentMode,
            onChange = { onChange(settings.copy(ringInSilentMode = it)) },
        )
        ListItem(
            supportingContent = {
                Text(
                    stringResource(
                        if (settings.hasDoNotDisturbAccess) {
                            R.string.call_reminders_setting_dnd_granted
                        } else {
                            R.string.call_reminders_setting_dnd_missing
                        }
                    )
                )
            },
            trailingContent = ListItemContent.Text(
                stringResource(
                    if (settings.hasDoNotDisturbAccess) R.string.call_reminders_action_review else R.string.call_reminders_action_allow
                )
            ),
            onClick = onOpenDoNotDisturbSettings,
            content = { Text(stringResource(R.string.call_reminders_setting_dnd)) },
        )
        ListItem(
            supportingContent = { Text(stringResource(R.string.call_reminders_setting_test_ring_subtitle)) },
            onClick = onTestRing,
            content = { Text(stringResource(R.string.call_reminders_setting_test_ring)) },
        )
    }
}

@Composable
private fun SettingsSection(
    settings: CallRemindersSettingsState,
    onChange: (CallRemindersSettingsState) -> Unit,
) {
    Column {
        ListSectionHeader(title = stringResource(R.string.call_reminders_settings_title), hasDivider = false)
        SwitchRow(
            title = stringResource(R.string.call_reminders_setting_escalation),
            subtitle = stringResource(R.string.call_reminders_setting_escalation_subtitle),
            checked = settings.escalationEnabled,
            onChange = { onChange(settings.copy(escalationEnabled = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.call_reminders_setting_gentle),
            subtitle = stringResource(R.string.call_reminders_setting_gentle_subtitle),
            checked = settings.gentleByDefault,
            onChange = { onChange(settings.copy(gentleByDefault = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.call_reminders_setting_ack),
            subtitle = stringResource(R.string.call_reminders_setting_ack_subtitle),
            checked = settings.sendAcknowledgements,
            onChange = { onChange(settings.copy(sendAcknowledgements = it)) },
        )
        SwitchRow(
            title = stringResource(R.string.call_reminders_setting_next_notification),
            subtitle = stringResource(R.string.call_reminders_setting_next_notification_subtitle),
            checked = settings.showNextReminderNotification,
            onChange = { onChange(settings.copy(showNextReminderNotification = it)) },
        )
        ListItem(
            supportingContent = { Text(settings.snoozeOptionsMinutes.joinToString(" · ") { "$it min" }) },
            trailingContent = ListItemContent.Text(stringResource(R.string.call_reminders_action_change)),
            onClick = { onChange(settings.copy(snoozeOptionsMinutes = settings.nextSnoozePreset())) },
            content = { Text(stringResource(R.string.call_reminders_setting_default_snooze)) },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        supportingContent = { Text(subtitle) },
        trailingContent = ListItemContent.Switch(checked = checked),
        onClick = { onChange(!checked) },
        content = { Text(title) },
    )
}

private const val SNOOZE_SHORTCUT_MINUTES = 15

/** The settings the screen can change, as one immutable value so the screen stays stateless. */
data class CallRemindersSettingsState(
    val ringInSilentMode: Boolean = true,
    val escalationEnabled: Boolean = true,
    val gentleByDefault: Boolean = false,
    val sendAcknowledgements: Boolean = true,
    val showNextReminderNotification: Boolean = true,
    val snoozeOptionsMinutes: List<Int> = listOf(5, 15, 60),
    val toneId: String = AlarmTone.DEFAULT.id,
    val maxOutVolume: Boolean = true,
    val rampUpSeconds: Int = 10,
    val rampStartPercent: Int = 40,
    val vibrate: Boolean = true,
    val torchBlink: Boolean = false,
    /** Read from the system, not stored: whether the Do Not Disturb grant is in place. */
    val hasDoNotDisturbAccess: Boolean = false,
) {
    /** Cycles through the handful of snooze sets worth having, so changing it needs no dialog. */
    fun nextSnoozePreset(): List<Int> {
        val index = SNOOZE_PRESETS.indexOfFirst { it == snoozeOptionsMinutes }
        return SNOOZE_PRESETS[(index + 1).mod(SNOOZE_PRESETS.size)]
    }

    /** Same trick for the fade-in: four sensible values, cycled by tapping the row. */
    fun nextRampSeconds(): Int {
        val index = RAMP_PRESETS.indexOfFirst { it == rampUpSeconds }
        return RAMP_PRESETS[(index + 1).mod(RAMP_PRESETS.size)]
    }

    companion object {
        val SNOOZE_PRESETS = listOf(
            listOf(5, 15, 60),
            listOf(10, 30, 120),
            listOf(1, 5, 15),
            listOf(15, 60, 240),
        )

        /** 0 = straight to full volume. */
        val RAMP_PRESETS = listOf(10, 0, 5, 20, 30)
    }
}

@PreviewsDayNight
@Composable
internal fun CallRemindersScreenPreview(
    @PreviewParameter(CallReminderPreviewParam::class) reminder: CallReminder,
) = ElementPreview {
    CallRemindersScreen(
        reminders = listOf(
            reminder,
            aCallReminder(id = "server-43", title = "Call the garage", state = CallReminder.State.Snoozed),
            aCallReminder(id = "server-44", title = "Call Mum", state = CallReminder.State.Done),
        ),
        settings = CallRemindersSettingsState(),
        onBack = {},
        onDone = {},
        onSnooze = { _, _ -> },
        onCall = {},
        onDelete = {},
        onSettingChange = {},
        onTestRing = {},
        onPreviewTone = {},
        onOpenDoNotDisturbSettings = {},
    )
}
