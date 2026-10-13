/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.features.notes.impl.di.NotesBindings
import io.element.android.features.notes.impl.model.Note
import io.element.android.features.notes.impl.reminders.NoteReminders
import io.element.android.features.notes.impl.store.NotesStore
import io.element.android.features.notes.impl.widget.NotesWidgetProvider
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.designsystem.theme.ElementThemeApp
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import java.util.Calendar

/**
 * The notes screen, and the editor behind it.
 *
 * One activity with two states rather than a navigation graph, for the same reason the call
 * reminders screen is one: the feature then needs no hook into upstream's Appyx navigation at
 * all, which keeps every future merge with Element X clean. It is reached from the launcher
 * long-press shortcut, from the share sheet and from a note's alarm.
 *
 * Every edit is written to [NotesStore] immediately — there is no save button and no way to lose
 * a note by leaving. Sending it to the room is the session observer's job and happens later.
 */
class NotesActivity : AppCompatActivity() {
    @Inject lateinit var appPreferencesStore: AppPreferencesStore

    @Inject lateinit var featureFlagService: FeatureFlagService

    @Inject lateinit var buildMeta: BuildMeta

    private lateinit var store: NotesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindings<NotesBindings>().inject(this)
        store = NotesStore.get(this)

        val openNoteId = intent?.getStringExtra(EXTRA_NOTE_ID)
        // A launcher shortcut's <extra> is always a String, however it is written in the XML,
        // while a programmatic intent carries a real Boolean. Accept both rather than silently
        // ignoring the shortcut.
        val startNew = intent?.getBooleanExtra(EXTRA_NEW_NOTE, false) == true ||
            intent?.getStringExtra(EXTRA_NEW_NOTE)?.toBooleanStrictOrNull() == true
        // Resolved here rather than inside the composition: creating a note writes to disk, and
        // a side effect in a composable initialiser is a trap waiting for the next person.
        val initialNoteId = openNoteId ?: if (startNew) createNote().id else null

        setContent {
            ElementThemeApp(
                appPreferencesStore = appPreferencesStore,
                featureFlagService = featureFlagService,
                compoundLight = SemanticColorsLightDark.default.light,
                compoundDark = SemanticColorsLightDark.default.dark,
                buildMeta = buildMeta,
            ) {
                val notes by store.notes.collectAsState()
                var editingId by rememberSaveable { mutableStateOf(initialNoteId) }
                var showingTrash by rememberSaveable { mutableStateOf(false) }
                val editing = remember(notes, editingId) { notes.firstOrNull { it.id == editingId } }

                if (editing != null) {
                    NoteEditorScreen(
                        note = editing,
                        onBack = {
                            // A note nobody typed anything into is not worth keeping; one that
                            // was never given a title gets named from its first line here, which
                            // is the moment the user has finished with it.
                            if (editing.isBlank) store.delete(editing.id) else store.finishEditing(editing.id)
                            editingId = null
                        },
                        onChange = { changed ->
                            val saved = store.save(changed)
                            NoteReminders.schedule(this, saved)
                        },
                        onTrash = {
                            store.setTrashed(editing.id, true)
                            NoteReminders.schedule(this, editing.copy(trashed = true))
                            editingId = null
                        },
                        onPickReminder = { pickReminder(editing) },
                        onClearReminder = {
                            val cleared = store.edit(editing.id) { it.copy(remindAtMillis = null) }
                            cleared?.let { NoteReminders.schedule(this, it) }
                        },
                    )
                } else {
                    NotesScreen(
                        notes = notes,
                        showingTrash = showingTrash,
                        onBack = { finish() },
                        onOpen = { editingId = it.id },
                        onNew = { editingId = createNote().id },
                        onToggleChecklistItem = { note, index ->
                            store.edit(note.id) { current ->
                                val items = current.checklist.toMutableList()
                                items.getOrNull(index)?.let { items[index] = it.copy(done = !it.done) }
                                current.copy(checklist = items)
                            }
                        },
                        onTogglePin = { note -> store.edit(note.id) { it.copy(pinned = !it.pinned) } },
                        onTrash = { note ->
                            store.setTrashed(note.id, true)
                            NoteReminders.schedule(this, note.copy(trashed = true))
                        },
                        onRestore = { note ->
                            val restored = store.setTrashed(note.id, false)
                            restored?.let { NoteReminders.schedule(this, it) }
                        },
                        onDeleteForever = { note ->
                            NoteReminders.schedule(this, note.copy(trashed = true))
                            store.delete(note.id)
                        },
                        onToggleTrashView = { showingTrash = !showingTrash },
                    )
                }
            }
        }
    }

    override fun onPause() {
        // Leaving by the home button or the recents switcher is also "finished with it", so an
        // untitled note is named here too, not only when Back is pressed.
        store.all().filter { it.title.isBlank() && !it.isBlank }.forEach { store.finishEditing(it.id) }
        // The home-screen widget reads the same store, so it only has to be told to redraw. Done
        // on pause rather than on every keystroke: nothing on the home screen is visible while
        // the editor is open, and a broadcast per character would be absurd.
        NotesWidgetProvider.requestUpdate(this)
        super.onPause()
    }

    private fun createNote(): Note {
        val note = Note(id = NotesStore.newNoteId())
        return store.save(note)
    }

    /**
     * Picks a due time with the system's own date and time dialogs.
     *
     * Two stock dialogs rather than a bespoke Compose picker: they are what the user already
     * knows, they handle 12/24-hour and locale correctly, and a note reminder is not the place to
     * spend a day of UI work.
     */
    private fun pickReminder(note: Note) {
        val now = Calendar.getInstance()
        val start = note.remindAtMillis?.let { Calendar.getInstance().apply { timeInMillis = it } } ?: now
        DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        val target = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val saved = store.edit(note.id) { it.copy(remindAtMillis = target.timeInMillis) }
                        saved?.let { NoteReminders.schedule(this, it) }
                    },
                    start.get(Calendar.HOUR_OF_DAY),
                    start.get(Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(this),
                ).show()
            },
            start.get(Calendar.YEAR),
            start.get(Calendar.MONTH),
            start.get(Calendar.DAY_OF_MONTH),
        ).apply {
            // A reminder in the past would ring immediately, which is never what was meant.
            datePicker.minDate = now.timeInMillis - DAY_MILLIS
        }.show()
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_NEW_NOTE = "new_note"
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        fun intent(context: Context, noteId: String? = null, newNote: Boolean = false): Intent =
            Intent(context, NotesActivity::class.java).apply {
                noteId?.let { putExtra(EXTRA_NOTE_ID, it) }
                if (newNote) putExtra(EXTRA_NEW_NOTE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
