/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.api

import io.element.android.libraries.matrix.api.MatrixClient
import kotlinx.coroutines.CoroutineScope

/**
 * Watches a signed-in session for call reminders and keeps the phone's alarms in step with them.
 *
 * A call reminder arrives as an ordinary message carrying a structured payload (see the fork's
 * docs/chagai/CALL-REMINDERS.md). This observer notices those messages, stores them locally and
 * schedules an exact alarm for each, which is what lets the phone ring like a clock alarm at the
 * due time rather than relying on a push landing at exactly the right moment.
 *
 * It also flushes acknowledgements ("done", "snoozed", "called") back into the room the reminder
 * came from, so the same information reaches WhatsApp through the bridge.
 *
 * The api/impl split exists so app navigation can start the observer without depending on the
 * implementation, matching how the rest of this project is wired.
 */
interface CallReminderSessionObserver {
    /** Starts observing. Safe to call more than once; later calls are ignored. */
    fun start()

    interface Factory {
        fun create(matrixClient: MatrixClient, sessionCoroutineScope: CoroutineScope): CallReminderSessionObserver
    }
}
