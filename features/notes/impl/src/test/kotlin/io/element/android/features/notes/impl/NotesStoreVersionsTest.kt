/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.notes.impl.model.ChecklistItem
import io.element.android.features.notes.impl.model.Note
import io.element.android.features.notes.impl.store.NotesStore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * When a version is captured, and when one is deliberately not. The promise under test: a version
 * marks the end of an editing session that changed the note, a restore never destroys anything,
 * and the trail survives a remote overwrite — but nothing is captured per keystroke, for a blank
 * new note, or for a session that changed nothing.
 */
class NotesStoreVersionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): NotesStore = NotesStore(File(folder.root, "notes.json"))

    private fun NotesStore.newNote(body: String = ""): Note =
        save(Note(id = NotesStore.newNoteId(), body = body))

    @Test
    fun `a changed editing session captures what the note said before`() {
        val store = store()
        val note = store.newNote(body = "first thoughts")
        store.beginEditing(note.id)
        store.edit(note.id) { it.copy(body = "second thoughts") }
        val finished = store.finishEditing(note.id)!!

        assertThat(finished.versions).hasSize(1)
        assertThat(finished.versions.first().body).isEqualTo("first thoughts")
        assertThat(finished.body).isEqualTo("second thoughts")
    }

    @Test
    fun `a session that changed nothing captures nothing`() {
        val store = store()
        val note = store.newNote(body = "unchanged")
        store.beginEditing(note.id)
        val finished = store.finishEditing(note.id)!!
        assertThat(finished.versions).isEmpty()
    }

    @Test
    fun `typing into a brand-new note is its first content, not a version of blank`() {
        val store = store()
        val note = store.newNote()
        store.beginEditing(note.id)
        store.edit(note.id) { it.copy(body = "the first words") }
        val finished = store.finishEditing(note.id)!!
        assertThat(finished.versions).isEmpty()
    }

    @Test
    fun `keystroke saves without a session capture nothing`() {
        val store = store()
        val note = store.newNote(body = "a")
        store.edit(note.id) { it.copy(body = "ab") }
        store.edit(note.id) { it.copy(body = "abc") }
        assertThat(store.get(note.id)!!.versions).isEmpty()
    }

    @Test
    fun `restore brings the old content back and keeps the current content as a version`() {
        val store = store()
        val note = store.newNote(body = "first")
        store.beginEditing(note.id)
        store.edit(note.id) { it.copy(body = "second") }
        val finished = store.finishEditing(note.id)!!
        val firstVersion = finished.versions.first()

        val restored = store.restoreVersion(note.id, firstVersion.savedAtMillis)!!

        assertThat(restored.body).isEqualTo("first")
        assertThat(restored.versions.map { it.body }).contains("second")
        // The restored-from version is still there: restoring twice works.
        assertThat(restored.versions.map { it.body }).contains("first")
        // A never-synced note goes back out as Create, a synced one as Update.
        assertThat(restored.pending).isEqualTo(Note.PendingAction.Create)
    }

    @Test
    fun `a remote edit that overwrites local content keeps it as a version`() {
        val store = store()
        val note = store.newNote(body = "what my phone said")
        store.markSent(note.id, "\$event1")

        val remote = Note(id = note.id, eventId = "\$event2", title = "t", body = "what the room says")
        val merged = store.applyRemote(remote)!!

        assertThat(merged.body).isEqualTo("what the room says")
        assertThat(merged.versions.first().body).isEqualTo("what my phone said")
    }

    @Test
    fun `a remote echo of the same content captures nothing and keeps the trail`() {
        val store = store()
        val note = store.newNote(body = "first")
        store.beginEditing(note.id)
        store.edit(note.id) { it.copy(body = "second") }
        store.finishEditing(note.id)
        store.markSent(note.id, "\$event1")

        val current = store.get(note.id)!!
        val echo = Note(
            id = note.id,
            eventId = "\$event1",
            title = current.title,
            body = current.body,
            checklist = current.checklist,
            colour = current.colour,
            pinned = current.pinned,
        )
        // Same event, same content: nothing changed, so applyRemote reports null…
        assertThat(store.applyRemote(echo)).isNull()
        // …and the locally kept trail is untouched.
        assertThat(store.get(note.id)!!.versions.map { it.body }).containsExactly("first")
    }

    @Test
    fun `the trail is capped and drops the oldest`() {
        val store = store()
        var note = store.newNote(body = "v0")
        repeat(25) { i ->
            store.beginEditing(note.id)
            store.edit(note.id) { it.copy(body = "v${i + 1}") }
            note = store.finishEditing(note.id)!!
        }
        assertThat(note.versions).hasSize(20)
        assertThat(note.versions.first().body).isEqualTo("v24")
        assertThat(note.versions.last().body).isEqualTo("v5")
    }

    @Test
    fun `checklists travel with a version`() {
        val store = store()
        val note = store.newNote(body = "shopping")
        store.beginEditing(note.id)
        store.edit(note.id) { it.copy(checklist = listOf(ChecklistItem("milk", done = true))) }
        store.finishEditing(note.id)

        store.beginEditing(note.id)
        store.edit(note.id) { it.copy(checklist = emptyList()) }
        val finished = store.finishEditing(note.id)!!

        assertThat(finished.versions.first().checklist).containsExactly(ChecklistItem("milk", done = true))
    }

    @Test
    fun `versions survive a round trip through the store file`() {
        val file = File(folder.root, "notes.json")
        val first = NotesStore(file)
        val note = first.newNote(body = "first")
        first.beginEditing(note.id)
        first.edit(note.id) { it.copy(body = "second") }
        first.finishEditing(note.id)

        val reloaded = NotesStore(file)
        assertThat(reloaded.get(note.id)!!.versions.map { it.body }).containsExactly("first")
    }
}
