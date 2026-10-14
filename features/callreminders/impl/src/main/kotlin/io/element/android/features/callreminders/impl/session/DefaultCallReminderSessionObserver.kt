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
import io.element.android.features.callreminders.impl.notifications.CallReminderNotifications
import io.element.android.features.callreminders.impl.parser.CallReminderParser
import io.element.android.features.callreminders.impl.scheduler.CallReminderScheduler
import io.element.android.features.callreminders.impl.store.CallReminderSettings
import io.element.android.features.callreminders.impl.store.CallReminderStore
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClient
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
    private val acknowledger: CallReminderAcknowledger,
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

        // The slow loop is the safety net, not the mechanism: the receiver that handles the tap
        // asks for a flush straight away. This catches whatever was offline at that moment.
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

    /** See [CallReminderAcknowledger]: it owns both the wire format and the sending. */
    private suspend fun flushAcknowledgements() {
        // The receiver that handles the tap already asks for a flush; this is the retry net for
        // anything that was offline or had no session at that moment.
        runCatching { acknowledger.flush() }
            .onFailure { Timber.tag(TAG).w(it, "An acknowledgement pass failed; will retry") }
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
