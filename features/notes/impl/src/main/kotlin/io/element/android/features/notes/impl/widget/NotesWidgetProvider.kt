/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.element.android.features.notes.impl.R
import io.element.android.features.notes.impl.model.Note
import io.element.android.features.notes.impl.store.NotesStore
import io.element.android.features.notes.impl.ui.NotesActivity
import timber.log.Timber

/**
 * The notes widget: pinned notes on the home screen, and a + that opens a blank one.
 *
 * Supernote had no widget at all. This is the shortest possible path from "I need to remember
 * this" to it being written down, which is the only thing that decides whether a note app gets
 * used.
 *
 * Two deliberate choices:
 *
 * * **No `RemoteViewsService`.** A widget showing a handful of notes does not need a collection
 *   adapter, a bound service and an IPC round trip per row. The rows are built here and added to
 *   a `LinearLayout`, which cannot get stuck showing stale content when a service is killed.
 * * **`updatePeriodMillis = 0`.** The system's periodic update is capped at 30 minutes and wakes
 *   the device to run it — a poor trade for content that only changes when the user changes a
 *   note. [requestUpdate] pushes an update from the app the moment it knows instead.
 *
 * The widget reads [NotesStore] directly. That is safe and cheap because the store is a small
 * JSON file read once per process, and it means the widget is correct even when no session is
 * alive and nothing has synced.
 */
class NotesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_notes)

        // Tapping anywhere that is not a note opens the notes screen; the + opens a new note.
        views.setOnClickPendingIntent(R.id.widget_title, openNotes(context))
        views.setOnClickPendingIntent(R.id.widget_new, openNewNote(context))

        views.removeAllViews(R.id.widget_rows)
        val notes = runCatching { pickNotes(context) }
            .onFailure { Timber.w(it, "Could not read notes for the widget") }
            .getOrDefault(emptyList())

        if (notes.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_empty, android.view.View.GONE)
            notes.forEach { note ->
                val row = RemoteViews(context.packageName, R.layout.widget_note_row)
                row.setTextViewText(R.id.widget_row_text, note.widgetLine(context))
                row.setOnClickPendingIntent(R.id.widget_row_text, openNote(context, note.id))
                views.addView(R.id.widget_rows, row)
            }
        }
        manager.updateAppWidget(widgetId, views)
    }

    /**
     * What to show: pinned notes first, then the most recently touched, capped.
     *
     * The cap is a number of *rows*, not a height — a widget the user has made taller simply has
     * empty space below, which is better than guessing a row count from a size the provider only
     * learns about through `onAppWidgetOptionsChanged`.
     */
    private fun pickNotes(context: Context): List<Note> = NotesStore.get(context)
        .live()
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAtMillis })
        .take(MAX_ROWS)

    /** One line per note: a tick count for a checklist, otherwise the title. */
    private fun Note.widgetLine(context: Context): String = when {
        hasChecklist -> "• " + title.ifBlank { context.getString(R.string.notes_untitled) } +
            "  " + context.getString(R.string.notes_checklist_progress, checkedCount, checklist.size)
        else -> "• " + title.ifBlank { context.getString(R.string.notes_untitled) }
    }

    private fun openNotes(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        NotesActivity.intent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openNewNote(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_NEW,
        NotesActivity.intent(context, newNote = true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openNote(context: Context, noteId: String): PendingIntent = PendingIntent.getActivity(
        context,
        // A distinct request code per note, so the PendingIntents do not collapse into one.
        REQUEST_NOTE_BASE + (noteId.hashCode() and 0xFFFF),
        NotesActivity.intent(context, noteId = noteId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val MAX_ROWS = 5
        private const val REQUEST_OPEN = 1
        private const val REQUEST_NEW = 2
        private const val REQUEST_NOTE_BASE = 1_000

        /**
         * Redraws every placed widget.
         *
         * Called when the app has changed a note. Cheap and safe when no widget is placed: the
         * manager simply returns an empty id list.
         */
        fun requestUpdate(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, NotesWidgetProvider::class.java))
                if (ids.isEmpty()) return
                context.sendBroadcast(
                    Intent(context, NotesWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                )
            }.onFailure { Timber.d(it, "Could not refresh the notes widget") }
        }
    }
}
