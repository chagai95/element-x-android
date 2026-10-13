/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl.session

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.notes.api.NotesSessionObserver
import io.element.android.features.notes.impl.model.Note
import io.element.android.features.notes.impl.parser.NoteWireFormat
import io.element.android.features.notes.impl.reminders.NoteReminders
import io.element.android.features.notes.impl.store.NotesStore
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.childScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.createroom.CreateRoomParameters
import io.element.android.libraries.matrix.api.createroom.RoomPreset
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.roomdirectory.RoomVisibility
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The only thing that talks to the server about notes.
 *
 * It does three jobs, and nothing else:
 *
 * 1. **Finds (or makes) the notes room.** Its id is kept in the account's own global account
 *    data under [NOTES_ACCOUNT_DATA]. Account data syncs, so a second device finds the same room
 *    rather than creating a second one, and a reinstall picks the existing notes straight back
 *    up. The room itself is an ordinary private, encrypted room called "📝 Notes" — which means
 *    sharing a notebook is just inviting someone to it.
 * 2. **Reads the room into the store.** Every message that looks like a note is parsed and
 *    folded in. Local unsent edits always win, so syncing can never overwrite something the user
 *    can see on screen.
 * 3. **Drains the store's outbox.** New notes are sent, changed notes are edited in place
 *    (`m.replace`, so the history is the room's own), deleted notes are redacted.
 *
 * Everything the user does happens in the store first and reaches here afterwards. That ordering
 * is the feature: notes open instantly and work with no connection.
 */
@AssistedInject
class DefaultNotesSessionObserver(
    @Assisted private val matrixClient: MatrixClient,
    @Assisted sessionCoroutineScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    dispatchers: CoroutineDispatchers,
) : NotesSessionObserver {
    @AssistedFactory
    @ContributesBinding(AppScope::class)
    interface Factory : NotesSessionObserver.Factory {
        override fun create(matrixClient: MatrixClient, sessionCoroutineScope: CoroutineScope): DefaultNotesSessionObserver
    }

    private val coroutineScope = sessionCoroutineScope.childScope(dispatchers.io, TAG)
    private val started = AtomicBoolean(false)
    private val store by lazy { NotesStore.get(context) }

    /** One flush at a time: two overlapping drains would send the same note twice. */
    private val flushMutex = Mutex()

    override fun start() {
        if (!started.compareAndSet(false, true)) return

        coroutineScope.launch {
            // Alarms first and without waiting for the network: a note reminder due in ten
            // minutes must be armed whether or not the room resolves.
            NoteReminders.rescheduleAll(context, store)

            val room = resolveNotesRoom() ?: run {
                Timber.tag(TAG).w("No notes room yet; notes stay on this device until there is one")
                return@launch
            }
            observe(room)
        }

        // The outbox is drained on a slow loop rather than the instant the user types, because
        // the typing happens in an activity that may well be gone before a send completes. This
        // way a change is sent as soon as a session is alive, and is never lost if one is not.
        coroutineScope.launch {
            while (true) {
                runCatching { flush() }.onFailure { Timber.tag(TAG).w(it, "Could not flush notes") }
                delay(FLUSH_INTERVAL_MILLIS)
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // the room
    // ----------------------------------------------------------------------------------------
    /**
     * The notes room, found from account data or created once.
     *
     * Account data rather than a room tag or a name match: a name is not unique and can be
     * changed by anyone in the room, whereas account data is per-account, syncs, and is exactly
     * the "where do I keep this" pointer the spec intends.
     */
    private suspend fun resolveNotesRoom(): JoinedRoom? {
        val stored = matrixClient.getAccountData(NOTES_ACCOUNT_DATA).getOrNull()
            ?.let { ROOM_ID_REGEX.find(it)?.groupValues?.getOrNull(1) }
        if (stored != null) {
            matrixClient.getJoinedRoom(RoomId(stored))?.let { return it }
            Timber.tag(TAG).w("The stored notes room is not joined any more; making a new one")
        }

        val created = matrixClient.createRoom(
            CreateRoomParameters(
                name = ROOM_NAME,
                topic = ROOM_TOPIC,
                // Notes are private writing. Encrypted, invite-only, not in the directory.
                isEncrypted = true,
                isDirect = false,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.PRIVATE_CHAT,
            )
        ).getOrElse {
            Timber.tag(TAG).e(it, "Could not create the notes room")
            return null
        }

        matrixClient.setAccountData(NOTES_ACCOUNT_DATA, """{"room_id":"${created.value}"}""")
            .onFailure { Timber.tag(TAG).w(it, "Created the notes room but could not record it") }
        Timber.tag(TAG).d("Created the notes room %s", created.value)
        return matrixClient.getJoinedRoom(created)
    }

    private suspend fun observe(room: JoinedRoom) {
        room.liveTimeline.timelineItems
            .onEach { items -> items.forEach { consider(it) } }
            .launchIn(coroutineScope)
    }

    private fun consider(item: MatrixTimelineItem) {
        val event = (item as? MatrixTimelineItem.Event)?.event ?: return
        val body = ((event.content as? MessageContent)?.type as? TextMessageType)?.body ?: return
        if (!NoteWireFormat.looksLikeNote(body)) return
        val eventId = event.eventId?.value ?: return

        val note = NoteWireFormat.decode(
            body = body,
            // A note written by hand in another client carries no id of its own; deriving it
            // from the event id keeps it stable, so it does not become a new note every sync.
            fallbackId = "evt-${eventId.hashCode().toUInt().toString(16)}",
            eventId = eventId,
            timestampMillis = event.timestamp,
        ) ?: return

        val stored = store.applyRemote(note) ?: return
        NoteReminders.schedule(context, stored)
    }

    // ----------------------------------------------------------------------------------------
    // the outbox
    // ----------------------------------------------------------------------------------------
    private suspend fun flush() = flushMutex.withLock {
        val pending = store.pending()
        if (pending.isEmpty()) return@withLock
        val room = resolveNotesRoom() ?: return@withLock
        val timeline = room.liveTimeline

        for (note in pending) {
            when (note.pending) {
                Note.PendingAction.Create -> {
                    timeline.sendMessage(
                        body = NoteWireFormat.encode(note),
                        htmlBody = null,
                        intentionalMentions = emptyList(),
                    ).onSuccess {
                        // The event id arrives with the echo rather than from the send, so the
                        // note is marked sent now and picks its id up from the timeline. Until
                        // then it simply shows as not yet confirmed.
                        store.markSent(note.id, eventId = null)
                        Timber.tag(TAG).d("Sent a new note")
                    }.onFailure { Timber.tag(TAG).w(it, "Could not send a note; will retry") }
                }
                Note.PendingAction.Update -> {
                    val eventId = note.eventId ?: continue
                    timeline.editMessage(
                        eventOrTransactionId = EventOrTransactionId.Event(EventId(eventId)),
                        body = NoteWireFormat.encode(note),
                        htmlBody = null,
                        intentionalMentions = emptyList(),
                    ).onSuccess {
                        store.markSent(note.id, eventId)
                        Timber.tag(TAG).d("Updated a note")
                    }.onFailure { Timber.tag(TAG).w(it, "Could not update a note; will retry") }
                }
                Note.PendingAction.Delete -> {
                    val eventId = note.eventId ?: continue
                    timeline.redactEvent(
                        eventOrTransactionId = EventOrTransactionId.Event(EventId(eventId)),
                        reason = null,
                    ).onSuccess {
                        store.markSent(note.id, eventId)
                        Timber.tag(TAG).d("Deleted a note")
                    }.onFailure { Timber.tag(TAG).w(it, "Could not delete a note; will retry") }
                }
                null -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "Notes"

        /**
         * Where the notes room id is kept. Namespaced to the fork so it can never collide with
         * anything the spec or another client defines.
         */
        const val NOTES_ACCOUNT_DATA = "website.chagai.notes"
        const val ROOM_NAME = "📝 Notes"
        const val ROOM_TOPIC = "Notes from Matrix Chat. Each message is a note — invite someone to share them."
        const val FLUSH_INTERVAL_MILLIS = 15_000L

        val ROOM_ID_REGEX = """"room_id"\s*:\s*"([^"]+)"""".toRegex()
    }
}
