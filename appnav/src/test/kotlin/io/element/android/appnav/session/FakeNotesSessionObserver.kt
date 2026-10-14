/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.appnav.session

import io.element.android.features.notes.api.NotesSessionObserver
import io.element.android.libraries.matrix.api.MatrixClient
import kotlinx.coroutines.CoroutineScope

/**
 * A notes observer that does nothing, so the session-cache tests stay about the session cache.
 * It records whether it was started, which is the only thing those tests could care about.
 */
class FakeNotesSessionObserver : NotesSessionObserver {
    var startCount = 0
        private set

    override fun start() {
        startCount++
    }

    class Factory : NotesSessionObserver.Factory {
        val created = mutableListOf<FakeNotesSessionObserver>()

        override fun create(matrixClient: MatrixClient, sessionCoroutineScope: CoroutineScope): NotesSessionObserver =
            FakeNotesSessionObserver().also { created += it }
    }
}
