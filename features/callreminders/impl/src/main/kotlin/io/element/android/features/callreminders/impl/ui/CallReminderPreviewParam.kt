/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.ui

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.callreminders.impl.model.CallReminder

/** The states of a call reminder worth looking at in a preview or a screenshot test. */
class CallReminderPreviewParam : PreviewParameterProvider<CallReminder> {
    override val values: Sequence<CallReminder>
        get() = sequenceOf(
            aCallReminder(),
            aCallReminder(notes = null, phone = null),
            aCallReminder(
                title = "Call the Medgate line back",
                contact = null,
                phone = "+41 844 844 911",
                notes = "Ask about the lab order and the klaro referral",
            ),
        )
}

internal fun aCallReminder(
    id: String = "server-42",
    title: String = "Call Elisheva",
    contact: String? = "Elisheva",
    phone: String? = "+41 79 123 45 67",
    notes: String? = "About the Disney+ account",
    dueAtMillis: Long = PREVIEW_DUE_MILLIS,
    state: CallReminder.State = CallReminder.State.Ringing,
) = CallReminder(
    id = id,
    roomId = "!self:chagai.website",
    sessionId = "@me:chagai.website",
    title = title,
    contact = contact,
    phone = phone,
    notes = notes,
    dueAtMillis = dueAtMillis,
    originalDueAtMillis = dueAtMillis,
    state = state,
)

/** Fixed so previews and screenshot tests never change from one run to the next. */
private const val PREVIEW_DUE_MILLIS = 1_791_385_200_000L
