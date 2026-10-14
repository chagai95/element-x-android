/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.session

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.store.CallReminderSettings
import io.element.android.features.callreminders.impl.store.CallReminderStore
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tells the room what the user did about a reminder.
 *
 * The outcome is posted as an ordinary message, so it also reaches WhatsApp through the bridge
 * and the server can read it back into its own records. The text is written to be read by a
 * person *and* parsed by a script — `matrix-os`' `reminder-ack-watch.py` matches it line for
 * line, so changing the wording here means changing it there.
 *
 * ### Why this is not simply done in the session observer
 *
 * It used to be: a loop every thirty seconds, inside a live session. But the tap that resolves a
 * reminder happens in a broadcast receiver, and the commonest case by far is a phone that has
 * been ringing in a pocket with the app long since evicted from memory. Nothing was alive to run
 * that loop, so "Done" sat on the phone until the user next opened the app — sometimes for days,
 * and from the chat's point of view the reminder had simply been ignored.
 *
 * So the receiver calls [flushSoon] directly and the session is *restored* if it is not already
 * in memory, exactly as the push notification layer does to send a reply from the shade. The
 * observer's slow loop stays as the retry net for anything that failed while offline.
 */
@Inject
@SingleIn(AppScope::class)
class CallReminderAcknowledger(
    @ApplicationContext private val context: Context,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val matrixClientProvider: MatrixClientProvider,
) {
    // One flush at a time: the receiver and the observer's loop can easily collide, and sending
    // the same acknowledgement twice is worse than sending it a second later.
    private val mutex = Mutex()

    /** Fire-and-forget, for callers with milliseconds to live. */
    fun flushSoon() {
        appCoroutineScope.launch { flush() }
    }

    suspend fun flush() = mutex.withLock {
        val store = CallReminderStore.get(context)
        if (!CallReminderSettings.get(context).sendAcknowledgements) return@withLock
        val pending = store.all().filter { it.needsAcknowledgement }
        for (reminder in pending) {
            val text = acknowledgementText(reminder) ?: continue
            val client = matrixClientProvider.getOrRestore(SessionId(reminder.sessionId)).getOrNull() ?: run {
                Timber.tag(TAG).w("No session to acknowledge %s with; will retry", reminder.id)
                continue
            }
            val room = client.getJoinedRoom(RoomId(reminder.roomId)) ?: run {
                Timber.tag(TAG).w("Not joined to the room reminder %s came from", reminder.id)
                continue
            }
            room.liveTimeline
                .sendMessage(body = text, htmlBody = null, intentionalMentions = emptyList())
                .onSuccess {
                    store.update(reminder.id) { it.copy(acknowledged = true) }
                    Timber.tag(TAG).d("Acknowledged reminder %s", reminder.id)
                }
                .onFailure { Timber.tag(TAG).w(it, "Could not acknowledge a reminder; will retry") }
        }
    }

    companion object {
        private const val TAG = "CallReminders"

        /**
         * The one place the wire format lives.
         *
         *     ✅ Done 15:02 — Call reminder #42
         *     📞 Called 15:02 — Call reminder #42
         *     ⏰ Snoozed to 15:20 — Call reminder #42
         *
         * The "#id" is the server's own reminder id, present only for reminders that came from
         * the server; one created on the phone has nothing to refer to.
         */
        fun acknowledgementText(reminder: CallReminder): String? {
            val reference = if (reminder.id.startsWith(SERVER_ID_PREFIX)) " #${reminder.id.removePrefix(SERVER_ID_PREFIX)}" else ""
            return when (reminder.state) {
                CallReminder.State.Done -> "✅ Done ${at(reminder.resolvedAtMillis ?: return null)} — Call reminder$reference"
                CallReminder.State.Called -> "📞 Called ${at(reminder.resolvedAtMillis ?: return null)} — Call reminder$reference"
                // Only a snooze the user chose, never the app's own "nobody answered, try again"
                // re-arming — that is the app nagging, not a thing the user did.
                CallReminder.State.Snoozed -> reminder.snoozedAtMillis?.let {
                    "⏰ Snoozed to ${at(reminder.dueAtMillis)} — Call reminder$reference"
                }
                else -> null
            }
        }

        private fun at(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

        const val SERVER_ID_PREFIX = "server-"
    }
}
