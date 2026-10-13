/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.element.android.features.notes.impl.R
import io.element.android.features.notes.impl.model.Note
import io.element.android.features.notes.impl.store.NotesStore

/**
 * "Share to notes" — the fastest way a thought gets written down.
 *
 * Share text from anywhere and it becomes a note, with no screen to wait for and nothing to tap:
 * this activity saves and finishes, and the session observer sends it to the room when it can.
 * A shared subject (an email's, a page title) becomes the title; otherwise the title is derived
 * from the first line, which is the behaviour Supernote used when a title was left blank.
 *
 * Supernote could not do this at all — it declared no `ACTION_SEND` filter — but it is the
 * feature that makes a note app actually get used, so it is here.
 *
 * No theme, no layout, no `setContent`: an invisible activity that does one thing and closes.
 */
class QuickCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.trim()
            .orEmpty()

        if (text.isEmpty()) {
            finish()
            return
        }

        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val store = NotesStore.get(this)
        store.save(
            Note(
                id = NotesStore.newNoteId(),
                title = subject.ifBlank { Note.deriveTitle(text) },
                body = text,
            )
        )
        Toast.makeText(this, R.string.notes_captured, Toast.LENGTH_SHORT).show()
        finish()
    }
}
