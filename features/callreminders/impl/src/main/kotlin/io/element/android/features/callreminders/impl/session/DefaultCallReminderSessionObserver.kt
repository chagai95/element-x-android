/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.session

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.callreminders.api.CallReminderSessionObserver
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.notifications.CallReminderNotifications
import io.element.android.features.callreminders.impl.parser.CallReminderParser
import io.element.android.features.callreminders.impl.scheduler.CallReminderScheduler
import io.element.android.features.callreminders.impl.store.CallReminderSettings
import io.element.android.features.callreminders.impl.store.CallReminderStore
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.roomlist.LatestEventValue
import io.element.android.libraries.matrix.api.roomlist.RoomList
import io.element.android.libraries.matrix.api.roomlist.RoomSummary
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.core.coroutine.childScope
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notices call reminders arriving in any room, and reports back what the user did with them.
 *
 * It listens to the room list rather than opening timelines, because the room list is already
 * being synced for every room and each summary carries the latest message's text. That is enough:
 * the server writes reminders in a fixed, parseable shape precisely so the visible text is all
 * the app needs (see [CallReminderParser]).
 *
 * Everything it finds is written to [CallReminderStore] and scheduled with
 * [CallReminderScheduler]; from that moment the alarm no longer depends on the network, the
 * server, or this observer still running.
 */
@AssistedInject
class DefaultCallReminderSessionObserver(
    @Assisted private val matrixClient: MatrixClient,
    @Assisted sessionCoroutineScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    dispatchers: CoroutineDispatchers,
) : CallReminderSessionObserver {
    @AssistedFactory
    @ContributesBinding(AppScope::class)
    interface Factory : CallReminderSessionObserver.Factory {
        override fun create(matrixClient: MatrixClient, sessionCoroutineScope: CoroutineScope): DefaultCallReminderSessionObserver
    }

    private val coroutineScope = sessionCoroutineScope.childScope(dispatchers.io, TAG)
    private val started = AtomicBoolean(false)
    private val store by lazy { CallReminderStore.get(context) }
    private val settings by lazy { CallReminderSettings.get(context) }
    private val scheduler by lazy { CallReminderScheduler(context) }

    override fun start() {
        if (!started.compareAndSet(false, true)) return

        coroutineScope.launch {
            CallReminderNotifications.ensureChannels(context)
            store.prune()
            // The system drops alarms on reboot and on update; this is the cheapest moment to
            // notice that and put them back.
            scheduler.rescheduleAll(store)
            updateNextNotification()
        }

        val roomList = matrixClient.roomListService.createRoomList(
            pageSize = ROOM_LIST_PAGE_SIZE,
            source = RoomList.Source.All,
            coroutineScope = coroutineScope,
        )
        roomList.summaries
            .onEach { summaries -> summaries.forEach(::consider) }
            .launchIn(coroutineScope)

        // Acknowledgements are sent on a slow loop rather than the instant the user taps, because
        // the tap happens in a broadcast receiver that may well outlive the app process; this way
        // the outcome is reported as soon as a session is alive again, and never lost.
        coroutineScope.launch {
            while (true) {
                flushAcknowledgements()
                delay(ACK_FLUSH_INTERVAL_MILLIS)
            }
        }
    }

    private fun consider(summary: RoomSummary) {
        val body = (summary.latestEvent as? LatestEventValue.Remote)
            ?.content
            ?.let { it as? MessageContent }
            ?.body
            ?: return
        if (!CallReminderParser.looksLikeReminder(body)) return

        val reminder = CallReminderParser.fromBody(
            body = body,
            roomId = summary.roomId.value,
            sessionId = matrixClient.sessionId.value,
        ) ?: return

        val stored = store.upsert(reminder) ?: return
        scheduler.schedule(stored)
        updateNextNotification()
        // Matrix ids are safe to log; the reminder's contents are not.
        Timber.tag(TAG).d("Scheduled a call reminder from %s", summary.roomId.value)
    }

    /**
     * Posts the outcome of each resolved reminder back into the room it came from, so the same
     * information reaches WhatsApp through the bridge and the server can record it.
     *
     * The text is written to be readable by a person and parseable by a script: the "#id" is the
     * server's own reminder id when there was one.
     */
    private suspend fun flushAcknowledgements() {
        if (!settings.sendAcknowledgements) return
        val pending = store.all().filter { !it.acknowledged && it.resolvedAtMillis != null && it.roomId.isNotEmpty() }
        for (reminder in pending) {
            val room = matrixClient.getJoinedRoom(RoomId(reminder.roomId)) ?: continue
            val text = acknowledgementText(reminder) ?: continue
            room.liveTimeline
                .sendMessage(body = text, htmlBody = null, intentionalMentions = emptyList())
                .onSuccess {
                    store.update(reminder.id) { it.copy(acknowledged = true) }
                    Timber.tag(TAG).d("Acknowledged reminder %s", reminder.id)
                }
                .onFailure { Timber.tag(TAG).w(it, "Could not acknowledge a reminder; will retry") }
        }
    }

    private fun acknowledgementText(reminder: CallReminder): String? {
        val reference = reminder.id.removePrefix("server-").let { if (reminder.id.startsWith("server-")) " #$it" else "" }
        val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reminder.resolvedAtMillis ?: return null))
        return when (reminder.state) {
            CallReminder.State.Done -> "✅ Done $at — Call reminder$reference"
            CallReminder.State.Called -> "📞 Called $at — Call reminder$reference"
            else -> null
        }
    }

    private fun updateNextNotification() {
        CallReminderNotifications.showNext(
            context,
            store.open().firstOrNull().takeIf { settings.showNextReminderNotification },
        )
    }

    private companion object {
        const val TAG = "CallReminders"
        const val ROOM_LIST_PAGE_SIZE = 30
        const val ACK_FLUSH_INTERVAL_MILLIS = 30_000L
    }
}
