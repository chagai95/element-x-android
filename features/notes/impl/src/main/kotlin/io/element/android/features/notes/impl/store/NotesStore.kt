/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl.store

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.element.android.features.notes.impl.model.Note
import io.element.android.features.notes.impl.model.NoteVersion
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
class NotesStore @VisibleForTesting internal constructor(private val file: File) {
    private val lock = Any()

    /**
     * What each note said when its current editing session began, by note id.
     *
     * In memory on purpose: a version should mark "as it was before I sat down to edit", and a
     * process death *is* the end of a sitting. Persisting these would turn a crash into a
     * half-captured session with a misleading timestamp.
     */
    private val editingSnapshots = mutableMapOf<String, Note>()
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
        val sentAlready = note.sentAtLeastOnce || existing?.sentAtLeastOnce == true
        val action = when {
            // A note whose create is still in flight must not queue a *second* create, or every
            // keystroke before the echo lands puts another copy of the note in the room.
            existing == null -> Note.PendingAction.Create
            existing.eventId == null && !sentAlready -> Note.PendingAction.Create
            else -> Note.PendingAction.Update
        }
        val saved = note.copy(
            eventId = note.eventId ?: existing?.eventId,
            updatedAtMillis = System.currentTimeMillis(),
            pending = action,
            sentAtLeastOnce = sentAlready,
        )
        write(if (existing == null) current + saved else current.map { if (it.id == saved.id) saved else it })
        saved
    }

    /**
     * The note as the wire format would write it, used only for comparing.
     *
     * The stored note deliberately keeps things the wire format drops — a trailing newline, and
     * the empty checklist row the editor keeps so there is always somewhere to type the next
     * item. Comparing the raw forms therefore made every ordinary echo of our *own* message look
     * like somebody else's edit: it pushed a version nobody created and shoved a real one off the
     * end of the twenty-slot history. Comparing the normalised forms is the honest question —
     * "does this say anything different?".
     */
    private fun Note.normalisedForComparison(): Note = copy(
        title = title.ifBlank { Note.deriveTitle(body, checklist) },
        body = body.trimEnd(),
        checklist = checklist.filter { it.text.isNotBlank() },
    )

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
        // The end of an editing session that changed the note is the moment its previous content
        // becomes a version — the same "when the user is finished" reasoning as the title above.
        // The snapshot is normalised exactly the way the finished note is (derived title, blank
        // checklist rows dropped), so mechanical naming alone never reads as a user change.
        val snapshot = synchronized(lock) { editingSnapshots.remove(id) }
        val cleaned = snapshot?.takeUnless { it.isBlank }?.let {
            val cleanedChecklist = it.checklist.filter { item -> item.text.isNotBlank() }
            it.copy(
                title = it.title.ifBlank { Note.deriveTitle(it.body, cleanedChecklist) },
                checklist = cleanedChecklist,
            )
        }
        val versions = when {
            cleaned == null -> existing.versions
            cleaned.title == title && cleaned.body == existing.body && cleaned.checklist == checklist -> existing.versions
            else -> pushVersion(existing.versions, cleaned)
        }
        if (title == existing.title && checklist == existing.checklist && versions == existing.versions) return existing
        return save(existing.copy(title = title, checklist = checklist, versions = versions))
    }

    /** Remembers what [id] says right now, so [finishEditing] can tell whether — and from what — it changed. */
    fun beginEditing(id: String) {
        synchronized(lock) { get(id)?.let { editingSnapshots[id] = it } }
    }

    /** Ends every open editing session; leaving the app by any route is also "finished with it". */
    fun finishAllEditing() {
        synchronized(lock) { editingSnapshots.keys.toList() }.forEach { finishEditing(it) }
    }

    /**
     * Puts an earlier version's content back, going through the normal edit path so it also
     * syncs to the room as an ordinary edit. What the note says *now* is pushed onto the history
     * first, so a restore is itself restorable and never destroys anything.
     */
    fun restoreVersion(id: String, savedAtMillis: Long): Note? {
        val existing = get(id) ?: return null
        val version = existing.versions.firstOrNull { it.savedAtMillis == savedAtMillis } ?: return null
        return save(
            existing.copy(
                title = version.title,
                body = version.body,
                checklist = version.checklist,
                versions = pushVersion(existing.versions, existing),
            )
        )
    }

    /** [note]'s content onto the front of [versions] — unless it is blank or already the newest entry. */
    private fun pushVersion(versions: List<NoteVersion>, note: Note): List<NoteVersion> = when {
        note.isBlank -> versions
        versions.firstOrNull()?.matches(note) == true -> versions
        else -> (listOf(
            NoteVersion(
                title = note.title,
                body = note.body,
                checklist = note.checklist,
                savedAtMillis = note.updatedAtMillis,
            )
        ) + versions).take(VERSIONS_MAX)
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
            else -> {
                val here = existing.normalisedForComparison()
                val there = note.normalisedForComparison()
                // A remote edit about to overwrite different local content is the other moment
                // worth a version: "my phone said something else before that sync" is exactly the
                // question history answers. The incoming note never carries versions (the wire
                // format has none), so the local trail is carried over either way.
                val contentChanged = here.title != there.title ||
                    here.body != there.body ||
                    here.checklist != there.checklist
                val anythingChanged = contentChanged ||
                    existing.eventId != note.eventId ||
                    existing.colour != note.colour ||
                    existing.pinned != note.pinned ||
                    existing.trashed != note.trashed ||
                    existing.remindAtMillis != note.remindAtMillis
                if (!anythingChanged) return@synchronized null
                val merged = note.copy(
                    // Keep the local creation time: the room only knows when the message arrived,
                    // which for an edited note is not when the note was written.
                    createdAtMillis = minOf(existing.createdAtMillis, note.createdAtMillis),
                    sentAtLeastOnce = true,
                    versions = if (contentChanged) pushVersion(existing.versions, existing) else existing.versions,
                )
                write(current.map { if (it.id == merged.id) merged else it })
                merged
            }
        }
    }

    /**
     * Records that a pending change reached the room.
     *
     * [sentContentAtMillis] is the [Note.updatedAtMillis] of the content that was actually sent.
     * Sending is a network round-trip, and anything typed during it has already bumped that stamp
     * — so clearing [Note.pending] unconditionally would mark newer text as sent when it never
     * was, and the echo of the older message would later overwrite it. The user would watch their
     * own sentence revert. If the note has moved on, the send is recorded but the change stays
     * pending for the next pass.
     */
    fun markSent(id: String, eventId: String?, sentContentAtMillis: Long? = null) = synchronized(lock) {
        val current = _notes.value
        val existing = current.firstOrNull { it.id == id } ?: return
        val stillTheSameContent = sentContentAtMillis == null || existing.updatedAtMillis == sentContentAtMillis
        if (existing.pending == Note.PendingAction.Delete && stillTheSameContent) {
            write(current.filterNot { it.id == id })
        } else {
            write(
                current.map {
                    if (it.id != id) {
                        it
                    } else {
                        it.copy(
                            eventId = eventId ?: it.eventId,
                            pending = if (stillTheSameContent) null else it.pending,
                            // The create *did* happen, whatever has been typed since. Recording
                            // that stops the next pass sending a second copy of the same note.
                            sentAtLeastOnce = true,
                        )
                    }
                }
            )
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

        /**
         * Enough to cover weeks of real editing; small enough that the store file stays trivial.
         * The room's own history is unlimited — this is only the instant-and-offline copy.
         */
        private const val VERSIONS_MAX = 20

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
