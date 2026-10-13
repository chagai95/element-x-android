/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl.store

import android.content.Context
import io.element.android.features.notes.impl.model.Note
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Where notes live on the phone.
 *
 * The notes screen talks to **this** and never to the network. That is the whole reason opening
 * notes is instant, editing works on the Underground, and a note typed with no connection is not
 * lost: it is written here with a [Note.PendingAction] and the session observer sends it when
 * there is a session to send it with.
 *
 * Deliberately the same shape as the call-reminder store next door — one JSON file, held in
 * memory, whole-file atomic writes (temp file, then rename, so a kill mid-write cannot truncate
 * it). A database would buy nothing for a few hundred rows read by activities and broadcast
 * receivers, and would cost a schema, a migration path and a coroutine boundary in the middle of
 * a text field's `onValueChange`.
 */
class NotesStore private constructor(private val file: File) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(Note.serializer())

    private val _notes = MutableStateFlow(load())

    /** Every note the phone knows about, pinned first then newest first. */
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    fun all(): List<Note> = _notes.value

    fun get(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    /** Notes to show on the main screen: not in the trash, not waiting to be deleted. */
    fun live(): List<Note> = _notes.value.filter { !it.trashed && it.pending != Note.PendingAction.Delete }

    fun trashed(): List<Note> = _notes.value.filter { it.trashed && it.pending != Note.PendingAction.Delete }

    /** Everything the observer still has to send. */
    fun pending(): List<Note> = _notes.value.filter { it.pending != null }

    /**
     * Writes a note the user has just changed.
     *
     * Marks it [Note.PendingAction.Update] — or keeps it [Note.PendingAction.Create] if it has
     * never been sent, because a note that does not exist on the server yet cannot be edited
     * there; it has to be created with the new content.
     */
    fun save(note: Note): Note = synchronized(lock) {
        val current = _notes.value
        val existing = current.firstOrNull { it.id == note.id }
        val action = when {
            existing == null || existing.eventId == null -> Note.PendingAction.Create
            else -> Note.PendingAction.Update
        }
        val saved = note.copy(
            eventId = note.eventId ?: existing?.eventId,
            updatedAtMillis = System.currentTimeMillis(),
            pending = action,
        )
        write(if (existing == null) current + saved else current.map { if (it.id == saved.id) saved else it })
        saved
    }

    /** Applies a change to one note by id, with the pending/sync bookkeeping handled. */
    fun edit(id: String, transform: (Note) -> Note): Note? {
        val existing = get(id) ?: return null
        return save(transform(existing))
    }

    /**
     * Called when the user has finished with a note: names it, if they did not.
     *
     * Deliberately **not** done in [save]. Saving happens on every keystroke, so deriving there
     * named the note after the first thing typed and then never changed it again — typing
     * "Ask the landlord about the heating" produced a note called "Ask", because by the second
     * keystroke the title was no longer blank and the derivation stopped. Supernote got this
     * right by doing it in `onStop()`; this is the same moment.
     *
     * [io.element.android.features.notes.impl.parser.NoteWireFormat.encode] derives too, as a
     * safety net for a note that reaches the room without passing through here.
     */
    fun finishEditing(id: String): Note? {
        val existing = get(id) ?: return null
        // An empty checklist row is how a new item is added, so there is usually one left over
        // when the user walks away. Dropping them here keeps an empty tickbox off the card.
        val checklist = existing.checklist.filter { it.text.isNotBlank() }
        val title = existing.title.ifBlank { Note.deriveTitle(existing.body, checklist) }
        if (title == existing.title && checklist == existing.checklist) return existing
        return save(existing.copy(title = title, checklist = checklist))
    }

    /**
     * Folds a note that arrived from the room into the store.
     *
     * A local edit always wins: if the phone has unsent changes for this note, the incoming
     * version is ignored rather than overwriting work the user can see on screen. Everything else
     * takes the room's version, which is what makes two devices converge.
     *
     * Returns the stored note when something changed, else null, so the caller can avoid
     * rescheduling an alarm that has not moved.
     */
    fun applyRemote(note: Note): Note? = synchronized(lock) {
        val current = _notes.value
        val existing = current.firstOrNull { it.id == note.id }
        when {
            existing == null -> {
                write(current + note)
                note
            }
            existing.pending != null -> null
            existing.eventId == note.eventId &&
                existing.title == note.title &&
                existing.body == note.body &&
                existing.checklist == note.checklist &&
                existing.colour == note.colour &&
                existing.pinned == note.pinned &&
                existing.trashed == note.trashed &&
                existing.remindAtMillis == note.remindAtMillis -> null
            else -> {
                // Keep the local creation time: the room only knows when the message arrived,
                // which for an edited note is not when the note was written.
                val merged = note.copy(createdAtMillis = minOf(existing.createdAtMillis, note.createdAtMillis))
                write(current.map { if (it.id == merged.id) merged else it })
                merged
            }
        }
    }

    /** Records that a pending change reached the room. */
    fun markSent(id: String, eventId: String?) = synchronized(lock) {
        val current = _notes.value
        val existing = current.firstOrNull { it.id == id } ?: return
        if (existing.pending == Note.PendingAction.Delete) {
            write(current.filterNot { it.id == id })
        } else {
            write(current.map { if (it.id == id) it.copy(eventId = eventId ?: it.eventId, pending = null) else it })
        }
    }

    /** Into the trash, or back out of it. Reversible, which is the point. */
    fun setTrashed(id: String, trashed: Boolean): Note? = edit(id) { it.copy(trashed = trashed) }

    /** Gone for good once the observer has redacted the message. */
    fun delete(id: String) = synchronized(lock) {
        val current = _notes.value
        val existing = current.firstOrNull { it.id == id } ?: return
        if (existing.eventId == null) {
            // Never reached the room, so there is nothing to redact: drop it here and now.
            write(current.filterNot { it.id == id })
        } else {
            write(current.map { if (it.id == id) it.copy(pending = Note.PendingAction.Delete) else it })
        }
    }

    private fun load(): List<Note> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString(serializer, file.readText())
    }.onFailure {
        Timber.e(it, "Could not read the notes store; starting empty")
    }.getOrDefault(emptyList())

    private fun write(value: List<Note>) {
        _notes.value = value.sortedWith(
            compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAtMillis }
        )
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, _notes.value))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Timber.e(it, "Could not write the notes store") }
    }

    companion object {
        private const val FILE_NAME = "notes.json"

        /** Short, readable, and unique enough: it only has to be unique within one person's notes. */
        fun newNoteId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

        @Volatile
        private var instance: NotesStore? = null

        /** One store per process, so every screen and receiver sees the same state. */
        fun get(context: Context): NotesStore =
            instance ?: synchronized(this) {
                instance ?: NotesStore(File(context.applicationContext.filesDir, FILE_NAME)).also { instance = it }
            }
    }
}
