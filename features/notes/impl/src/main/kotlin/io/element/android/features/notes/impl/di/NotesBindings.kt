/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import io.element.android.features.notes.impl.ui.NotesActivity

/**
 * The notes screen is started by the system — a launcher shortcut, a share, a note's alarm —
 * rather than by app navigation, so it asks the application graph for what it needs instead of
 * being constructed with it. Same pattern as the call-reminder activities.
 */
@ContributesTo(AppScope::class)
interface NotesBindings {
    fun inject(activity: NotesActivity)
}
