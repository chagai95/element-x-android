/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.api

import io.element.android.libraries.matrix.api.MatrixClient
import kotlinx.coroutines.CoroutineScope

/**
 * Keeps the phone's notes and the notes room in step, in both directions.
 *
 * Notes are Matrix messages in a room of their own, so they sync across devices, survive a
 * reinstall, can be read in any Matrix client and can be shared simply by inviting someone to the
 * room. This observer is the only thing that talks to the server about them: the notes screen
 * reads and writes a local store and is therefore instant and works offline, and the observer
 * drains that store's outbox and folds incoming messages back into it.
 *
 * The api/impl split exists so app navigation can start the observer without depending on the
 * implementation, matching the rest of this project — and matching the call-reminder observer
 * next to it.
 */
interface NotesSessionObserver {
    /** Starts observing. Safe to call more than once; later calls are ignored. */
    fun start()

    interface Factory {
        fun create(matrixClient: MatrixClient, sessionCoroutineScope: CoroutineScope): NotesSessionObserver
    }
}
