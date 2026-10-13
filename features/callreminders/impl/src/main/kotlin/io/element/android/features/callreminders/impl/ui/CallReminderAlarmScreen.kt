/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.callreminders.impl.R
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.notifications.CallReminderNotifications
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton

/**
 * The screen that comes up when a call reminder goes off.
 *
 * Shaped like a clock alarm rather than a notification: one thing to read, one obvious thing to
 * do. The number and the note are there because the point of the reminder is usually the context,
 * not the name — "call Elisheva" is useless at 15:00 if you have forgotten it was about Disney+.
 */
@Composable
fun CallReminderAlarmScreen(
    reminder: CallReminder,
    isRinging: Boolean,
    justCalled: Boolean,
    onCallNow: () -> Unit,
    onDone: () -> Unit,
    onSnooze: (minutes: Int) -> Unit,
    onSnoozeTomorrowMorning: () -> Unit,
    onOpenChat: () -> Unit,
    onSilence: () -> Unit,
    onOpenAllReminders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ElementTheme.colors.bgCanvasDefault),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(ElementTheme.colors.bgAccentRest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = CompoundIcons.VoiceCall(),
                    contentDescription = null,
                    tint = ElementTheme.colors.iconOnSolidPrimary,
                    modifier = Modifier.size(44.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.call_reminders_alarm_heading),
                style = ElementTheme.typography.fontBodyMdMedium,
                color = ElementTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = reminder.displayContact,
                style = ElementTheme.typography.fontHeadingLgBold,
                color = ElementTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            reminder.phone?.let { phone ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = phone,
                    style = ElementTheme.typography.fontBodyLgRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
            reminder.notes?.let { notes ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = notes,
                    style = ElementTheme.typography.fontBodyLgRegular,
                    color = ElementTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.call_reminders_due_at,
                    CallReminderNotifications.formatTime(reminder.originalDueAtMillis),
                ),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
            )

            if (justCalled) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.call_reminders_after_call_prompt),
                    style = ElementTheme.typography.fontBodyMdMedium,
                    color = ElementTheme.colors.textActionAccent,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(32.dp))

            if (reminder.phone != null) {
                Button(
                    text = stringResource(R.string.call_reminders_action_call_now),
                    onClick = onCallNow,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                text = stringResource(R.string.call_reminders_action_done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.call_reminders_snooze_heading),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            SnoozeRow(
                options = reminder.snoozeOptionsMinutes,
                onSnooze = onSnooze,
            )
            TextButton(
                text = stringResource(R.string.call_reminders_action_snooze_tomorrow),
                onClick = onSnoozeTomorrowMorning,
            )

            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = stringResource(R.string.call_reminders_action_open_chat),
                    onClick = onOpenChat,
                )
                TextButton(
                    text = stringResource(R.string.call_reminders_title),
                    onClick = onOpenAllReminders,
                )
            }

            if (isRinging) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    text = stringResource(R.string.call_reminders_action_silence),
                    onClick = onSilence,
                )
                Text(
                    text = stringResource(R.string.call_reminders_volume_hint),
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SnoozeRow(
    options: List<Int>,
    onSnooze: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        options.take(MAX_SNOOZE_BUTTONS).forEach { minutes ->
            OutlinedButton(
                text = snoozeLabel(minutes),
                onClick = { onSnooze(minutes) },
                // Low padding and an equal share of the row, so "1 hour" is not truncated to "…".
                size = ButtonSize.MediumLowPadding,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun snoozeLabel(minutes: Int): String = if (minutes % 60 == 0) {
    pluralStringResource(R.plurals.call_reminders_snooze_hours, minutes / 60, minutes / 60)
} else {
    pluralStringResource(R.plurals.call_reminders_snooze_minutes, minutes, minutes)
}

private const val MAX_SNOOZE_BUTTONS = 3

@PreviewsDayNight
@Composable
internal fun CallReminderAlarmScreenPreview(
    @PreviewParameter(CallReminderPreviewParam::class) reminder: CallReminder,
) = ElementPreview {
    CallReminderAlarmScreen(
        reminder = reminder,
        isRinging = true,
        justCalled = false,
        onCallNow = {},
        onDone = {},
        onSnooze = {},
        onSnoozeTomorrowMorning = {},
        onOpenChat = {},
        onSilence = {},
        onOpenAllReminders = {},
    )
}
