/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl.reminders

import android.content.Context
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.notifications.CallReminderNotifications
import io.element.android.features.callreminders.impl.scheduler.CallReminderScheduler
import io.element.android.features.callreminders.impl.store.CallReminderStore
import io.element.android.features.notes.impl.model.Note
import io.element.android.features.notes.impl.store.NotesStore
import timber.log.Timber

/**
 * Gives a note a due time, and makes it ring like everything else.
 *
 * A note with a reminder does **not** get its own alarm system. It is turned into a
 * [CallReminder] and handed to the machinery that already exists — the same exact alarm, the same
 * loud ring on the alarm stream, the same full-screen screen over the lock screen, the same
 * Done/Snooze buttons, the same rebuild after a reboot.
 *
 * That is worth doing deliberately rather than writing a second, weaker alarm path: there is one
 * place where ringing is implemented and tested, one list showing everything that is going to
 * ring, and a note reminder is exactly as hard to sleep through as a call reminder.
 *
 * The id is derived from the note id, so re-saving a note moves its alarm instead of adding one.
 */
object NoteReminders {
    private const val ID_PREFIX = "note-"

    fun reminderId(noteId: String): String = ID_PREFIX + noteId

    /** True for a reminder that came from a note, so the reminders list can label it. */
    fun isNoteReminder(reminderId: String): Boolean = reminderId.startsWith(ID_PREFIX)

    fun noteIdOf(reminderId: String): String = reminderId.removePrefix(ID_PREFIX)

    /**
     * Arms, moves or cancels the alarm for one note.
     *
     * Cancelling when there is no due time is as important as arming when there is: clearing a
     * reminder has to actually stop the phone ringing, not just stop showing the time.
     */
    fun schedule(context: Context, note: Note) {
        val reminderStore = CallReminderStore.get(context)
        val scheduler = CallReminderScheduler(context)
        val id = reminderId(note.id)
        val dueAt = note.remindAtMillis

        if (dueAt == null || note.trashed || note.pending == Note.PendingAction.Delete) {
            scheduler.cancel(id)
            reminderStore.get(id)?.let { CallReminderNotifications.cancelAlarm(context, it) }
            reminderStore.remove(id)
            return
        }

        val reminder = CallReminder(
            id = id,
            // No room: a note reminder has nothing to post an acknowledgement back to, and the
            // call-reminder observer skips reminders with no room for exactly that reason.
            roomId = "",
            sessionId = "",
            title = note.title.ifBlank { context.getString(io.element.android.features.notes.impl.R.string.notes_untitled) },
            contact = null,
            phone = null,
            notes = note.previewForAlarm(),
            dueAtMillis = dueAt,
            originalDueAtMillis = dueAt,
        )
        reminderStore.upsert(reminder)?.let { scheduler.schedule(it) }
        CallReminderNotifications.showNext(context, reminderStore.open().firstOrNull())
    }

    /**
     * Rebuilds every note alarm from the store.
     *
     * Called when a session starts, which covers the cases the system throws alarms away: a
     * reboot, an app update, a force-stop. The call-reminder boot receiver rebuilds its own
     * alarms from its own store, and because a note reminder *is* a stored call reminder, it is
     * rebuilt by the same code — this is the belt to that braces, for the case where a note's due
     * time was changed on another device while this one was off.
     */
    fun rescheduleAll(context: Context, store: NotesStore) {
        val live = store.all().filter { !it.trashed && it.remindAtMillis != null }
        live.forEach { schedule(context, it) }
        if (live.isNotEmpty()) Timber.tag("Notes").d("Armed %d note reminders", live.size)
    }

    /** What the alarm screen shows under the title: the body, or the first unticked item. */
    private fun Note.previewForAlarm(): String? = body.lineSequence().firstOrNull { it.isNotBlank() }
        ?: checklist.firstOrNull { !it.done && it.text.isNotBlank() }?.text
}
