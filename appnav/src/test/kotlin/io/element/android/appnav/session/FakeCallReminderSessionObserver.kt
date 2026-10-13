/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.appnav.session

import io.element.android.features.callreminders.api.CallReminderSessionObserver
import io.element.android.libraries.matrix.api.MatrixClient
import kotlinx.coroutines.CoroutineScope

/**
 * A call-reminder observer that does nothing, so the session-cache tests stay about the session
 * cache. It records whether it was started, which is the only thing those tests could care about.
 */
class FakeCallReminderSessionObserver : CallReminderSessionObserver {
    var startCount = 0
        private set

    override fun start() {
        startCount++
    }

    class Factory : CallReminderSessionObserver.Factory {
        val created = mutableListOf<FakeCallReminderSessionObserver>()

        override fun create(matrixClient: MatrixClient, sessionCoroutineScope: CoroutineScope): CallReminderSessionObserver =
            FakeCallReminderSessionObserver().also { created += it }
    }
}
