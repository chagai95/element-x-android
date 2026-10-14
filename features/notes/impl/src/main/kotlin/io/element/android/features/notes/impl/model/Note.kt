/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl.model

import kotlinx.serialization.Serializable

/**
 * One note.
 *
 * A note is **a real Matrix message** in a room of its own (see
 * [io.element.android.features.notes.impl.session.DefaultNotesSessionObserver]). That single
 * decision is what gives notes everything a 2019 note app had to build by hand: they sync across
 * devices, survive a reinstall, can be read in any Matrix client, are end-to-end encrypted, keep
 * their own edit history, and are shared by inviting someone to the room rather than by
 * maintaining a list of user ids on every document.
 *
 * This class is the *phone's* copy of that. The screen reads and writes it and nothing else, so
 * opening notes is instant and editing works with no connection; [pending] is what the observer
 * drains back to the room.
 */
@Serializable
data class Note(
    /**
     * Stable identity, decided on the phone the moment the note is created, long before the
     * server has seen it. Also written into the message so every device agrees which note a
     * message is.
     */
    val id: String,
    /** The Matrix event, once it exists. Null for a note that has not been sent yet. */
    val eventId: String? = null,
    val title: String = "",
    /** Free text. Markdown is rendered, and links, phone numbers and emails are tappable. */
    val body: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    val colour: NoteColour = NoteColour.None,
    val pinned: Boolean = false,
    /**
     * In the trash. Kept rather than deleted, exactly as Supernote did it, because "I deleted the
     * wrong note" is the one note-app mistake that cannot be undone any other way.
     */
    val trashed: Boolean = false,
    /** When this note should ring, as epoch milliseconds. Rings through the call-reminder alarm. */
    val remindAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    /** What still has to be sent to the room, if anything. */
    val pending: PendingAction? = null,
    /**
     * True once this note's message has actually been sent, even if its event id has not come
     * back yet.
     *
     * The event id only arrives with the timeline echo, so for a few seconds after creating a
     * note `eventId` is null for two quite different reasons: "never sent" and "sent, waiting".
     * Without telling them apart, every keystroke in that window queued another *create* and the
     * room ended up with several copies of the same note.
     */
    val sentAtLeastOnce: Boolean = false,
    /**
     * Earlier contents of this note, newest first — what "restore an old version" restores.
     *
     * Kept **on this phone only**: the wire format never carries them, because the room already
     * has the authoritative history (every edit is an `m.replace` any client can show). This list
     * exists so restoring works instantly and offline, the two things a trip to the timeline
     * cannot promise. A version is captured when an editing session that changed the note ends,
     * and when a remote edit overwrites what this phone had — not on every keystroke.
     */
    val versions: List<NoteVersion> = emptyList(),
) {
    /**
     * True while the server has not confirmed this note.
     *
     * Supernote showed exactly this state by painting the text orange: the cached value appears
     * instantly, and turns black once it is confirmed. The idea is worth keeping — showing a
     * value you already have beats showing a spinner — so the notes grid tints an unconfirmed
     * note and marks it, rather than hiding it or blocking on the network.
     */
    val isUnconfirmed: Boolean get() = eventId == null || pending != null

    val checkedCount: Int get() = checklist.count { it.done }

    /** What the card shows under the title when there is no body: "2 of 5 done". */
    val hasChecklist: Boolean get() = checklist.isNotEmpty()

    /** Empty enough to be worth discarding rather than saving. */
    val isBlank: Boolean get() = title.isBlank() && body.isBlank() && checklist.none { it.text.isNotBlank() }

    enum class PendingAction {
        /** Never sent. The observer sends it and records the event id. */
        Create,

        /** Sent, then changed here. The observer replaces the message (an `m.replace` edit). */
        Update,

        /** Gone for good. The observer redacts the event and the row disappears. */
        Delete,
    }

    companion object {
        /**
         * Derives a title from the body, the way Supernote did: the first 20-25 characters,
         * **trimmed back to the last space** so a title never cuts a word in half.
         *
         * This runs when the note is saved with an empty title, so a note is never nameless and
         * the user never has to think of a name before writing anything down.
         */
        fun deriveTitle(body: String, checklist: List<ChecklistItem> = emptyList()): String {
            val source = body.ifBlank { checklist.firstOrNull { it.text.isNotBlank() }?.text.orEmpty() }
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
            if (source.isEmpty()) return ""
            if (source.length <= TITLE_MAX) return source
            val cut = source.take(TITLE_MAX)
            val lastSpace = cut.lastIndexOf(' ')
            // Any space will do. An earlier version insisted the title still be at least 20
            // characters, which meant "Remember to ask the landlord…" was cut to
            // "Remember to ask the landl" — the exact mid-word cut this is here to prevent.
            // A short-but-whole title beats a long-but-broken one.
            return if (lastSpace > 0) cut.take(lastSpace).trimEnd() else cut.trimEnd()
        }

        private const val TITLE_MAX = 25
    }
}

/** One line of a checklist. Ticking it edits the note's message, so the tick syncs. */
@Serializable
data class ChecklistItem(
    val text: String,
    val done: Boolean = false,
)

/**
 * One earlier state of a note: the content a restore brings back, and nothing else.
 *
 * Colour, pin and reminder are deliberately not here — restoring what a note *said* should not
 * also un-pin it or resurrect a long-dismissed alarm.
 */
@Serializable
data class NoteVersion(
    val title: String,
    val body: String,
    val checklist: List<ChecklistItem> = emptyList(),
    /** When this content was last saved, which is what the history row shows. */
    val savedAtMillis: Long,
) {
    fun matches(note: Note): Boolean =
        title == note.title && body == note.body && checklist == note.checklist
}

/**
 * The colours a note can be.
 *
 * Supernote hard-coded a single pale yellow for every card. Six is enough to sort a wall of notes
 * by eye and few enough that the grid never looks like a paint chart. The actual values are
 * resolved in the UI from Compound so they work in both themes; the enum only carries the id,
 * which is what goes into the message.
 */
@Serializable
enum class NoteColour(val id: String) {
    None("plain"),
    Butter("butter"),
    Sky("sky"),
    Mint("mint"),
    Rose("rose"),
    Lilac("lilac"),
    Sand("sand"),
    ;

    companion object {
        /** Tolerant: an unknown colour (a newer build wrote it) is simply a plain note. */
        fun fromId(id: String?): NoteColour = entries.firstOrNull { it.id == id } ?: None
    }
}
