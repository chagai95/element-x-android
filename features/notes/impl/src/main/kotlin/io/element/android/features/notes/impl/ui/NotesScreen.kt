/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.notes.impl.R
import io.element.android.features.notes.impl.model.Note
import io.element.android.features.notes.impl.model.NoteColour
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.theme.components.FloatingActionButton
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.TopAppBar

/**
 * The wall of notes.
 *
 * A grid by default, because colours and pinning are only worth having if you can see several
 * notes at once — which is the one thing Supernote's list could not do. A single tap toggles back
 * to a list for long notes.
 *
 * Search filters **in memory on every keystroke** rather than issuing a query. That is how
 * Supernote did it and it was the right call: a person's notes fit in memory, and instant is
 * worth more than clever. The magic query `missed reminders` is kept too — it lists notes whose
 * due time has passed.
 */
@Composable
fun NotesScreen(
    notes: List<Note>,
    showingTrash: Boolean,
    onBack: () -> Unit,
    onOpen: (Note) -> Unit,
    onNew: () -> Unit,
    onToggleChecklistItem: (Note, Int) -> Unit,
    onTogglePin: (Note) -> Unit,
    onTrash: (Note) -> Unit,
    onRestore: (Note) -> Unit,
    onDeleteForever: (Note) -> Unit,
    onToggleTrashView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(true) }

    val visible = remember(notes, query, showingTrash) { filterNotes(notes, query, showingTrash) }
    val pinned = remember(visible) { visible.filter { it.pinned } }
    val rest = remember(visible) { visible.filterNot { it.pinned } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                titleStr = stringResource(if (showingTrash) R.string.notes_title_trash else R.string.notes_title),
                navigationIcon = { BackButton(onClick = onBack) },
                actions = {
                    IconButton(onClick = { grid = !grid }) {
                        Icon(
                            imageVector = if (grid) CompoundIcons.ListBulleted() else CompoundIcons.Grid(),
                            contentDescription = stringResource(
                                if (grid) R.string.notes_action_list_view else R.string.notes_action_grid_view
                            ),
                        )
                    }
                    IconButton(onClick = onToggleTrashView) {
                        Icon(
                            imageVector = CompoundIcons.Delete(),
                            contentDescription = stringResource(R.string.notes_action_trash),
                            tint = if (showingTrash) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.iconSecondary,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!showingTrash) {
                FloatingActionButton(onClick = onNew) {
                    Icon(imageVector = CompoundIcons.Plus(), contentDescription = stringResource(R.string.notes_action_new))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.notes_search_placeholder),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (visible.isEmpty()) {
                EmptyState(query.isNotBlank(), showingTrash)
            } else {
                LazyVerticalGrid(
                    columns = if (grid) GridCells.Fixed(2) else GridCells.Fixed(1),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 96.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (pinned.isNotEmpty()) {
                        items(pinned, key = { it.id }) { note ->
                            NoteCard(note, showingTrash, onOpen, onToggleChecklistItem, onTogglePin, onTrash, onRestore, onDeleteForever)
                        }
                    }
                    items(rest, key = { it.id }) { note ->
                        NoteCard(note, showingTrash, onOpen, onToggleChecklistItem, onTogglePin, onTrash, onRestore, onDeleteForever)
                    }
                }
            }
        }
    }
}

/**
 * Supernote's search, kept: an in-memory filter on title and body, run on every keystroke, plus
 * its one magic query.
 */
internal fun filterNotes(notes: List<Note>, query: String, showingTrash: Boolean): List<Note> {
    val pool = notes.filter { it.trashed == showingTrash && it.pending != Note.PendingAction.Delete }
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return pool
    if (trimmed.equals(MISSED_REMINDERS_QUERY, ignoreCase = true)) {
        val now = System.currentTimeMillis()
        return pool.filter { it.remindAtMillis != null && it.remindAtMillis < now }
    }
    return pool.filter { note ->
        note.title.contains(trimmed, ignoreCase = true) ||
            note.body.contains(trimmed, ignoreCase = true) ||
            note.checklist.any { it.text.contains(trimmed, ignoreCase = true) }
    }
}

internal const val MISSED_REMINDERS_QUERY = "missed reminders"

@Composable
private fun NoteCard(
    note: Note,
    showingTrash: Boolean,
    onOpen: (Note) -> Unit,
    onToggleChecklistItem: (Note, Int) -> Unit,
    onTogglePin: (Note) -> Unit,
    onTrash: (Note) -> Unit,
    onRestore: (Note) -> Unit,
    onDeleteForever: (Note) -> Unit,
) {
    val background = note.colour.background()
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, ElementTheme.colors.borderInteractiveSecondary, RoundedCornerShape(14.dp))
            .clickable { onOpen(note) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = note.title.ifBlank { stringResource(R.string.notes_untitled) },
                style = ElementTheme.typography.fontBodyLgMedium,
                color = if (note.isUnconfirmed) ElementTheme.colors.textActionAccent else ElementTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!showingTrash) {
                IconButton(onClick = { onTogglePin(note) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (note.pinned) CompoundIcons.PinSolid() else CompoundIcons.Pin(),
                        contentDescription = stringResource(R.string.notes_action_pin),
                        tint = if (note.pinned) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.iconTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (note.body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = note.body,
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
        note.checklist.take(CHECKLIST_PREVIEW).forEachIndexed { index, item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !showingTrash) { onToggleChecklistItem(note, index) }
                    .padding(vertical = 2.dp),
            ) {
                Icon(
                    imageVector = if (item.done) CompoundIcons.CheckCircleSolid() else CompoundIcons.Circle(),
                    contentDescription = null,
                    tint = if (item.done) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.iconTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.text,
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = if (item.done) ElementTheme.colors.textSecondary else ElementTheme.colors.textPrimary,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (note.checklist.size > CHECKLIST_PREVIEW) {
            Text(
                text = stringResource(R.string.notes_checklist_more, note.checklist.size - CHECKLIST_PREVIEW),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (note.remindAtMillis != null) {
                Icon(
                    imageVector = CompoundIcons.Time(),
                    contentDescription = null,
                    tint = ElementTheme.colors.iconAccentPrimary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = note.footer(),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (showingTrash) {
                IconButton(onClick = { onRestore(note) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = CompoundIcons.Restart(),
                        contentDescription = stringResource(R.string.notes_action_restore),
                        tint = ElementTheme.colors.iconSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = { onDeleteForever(note) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = CompoundIcons.Delete(),
                        contentDescription = stringResource(R.string.notes_action_delete_forever),
                        tint = ElementTheme.colors.iconCriticalPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                IconButton(onClick = { onTrash(note) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = CompoundIcons.Delete(),
                        contentDescription = stringResource(R.string.notes_action_trash),
                        tint = ElementTheme.colors.iconTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** The line under a note: how many ticks are done, or when it is due, or nothing. */
@Composable
private fun Note.footer(): String = when {
    remindAtMillis != null -> io.element.android.features.notes.impl.parser.NoteWireFormat.formatTime(remindAtMillis)
    hasChecklist -> stringResource(R.string.notes_checklist_progress, checkedCount, checklist.size)
    isUnconfirmed -> stringResource(R.string.notes_not_synced)
    else -> ""
}

@Composable
private fun EmptyState(searching: Boolean, showingTrash: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = stringResource(
                    when {
                        searching -> R.string.notes_empty_search
                        showingTrash -> R.string.notes_empty_trash
                        else -> R.string.notes_empty_title
                    }
                ),
                style = ElementTheme.typography.fontBodyLgMedium,
                color = ElementTheme.colors.textPrimary,
            )
            if (!searching && !showingTrash) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.notes_empty_subtitle),
                    style = ElementTheme.typography.fontBodyMdRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
        }
    }
}

/**
 * A note's colour, resolved against the current theme.
 *
 * Deliberately a tint of the Compound canvas rather than a fixed hex: a sticky-note yellow that
 * looks right on white is a headache torch in dark mode. Each colour is the same hue at a
 * lightness the theme can live with.
 */
@Composable
internal fun NoteColour.background() = when (this) {
    NoteColour.None -> ElementTheme.colors.bgSubtleSecondary
    NoteColour.Butter -> ElementTheme.colors.bgCriticalSubtle.blendWith(0xFFFFF3C4)
    NoteColour.Sky -> ElementTheme.colors.bgSubtlePrimary.blendWith(0xFFD6ECFF)
    NoteColour.Mint -> ElementTheme.colors.bgSuccessSubtle.blendWith(0xFFD6F5E4)
    NoteColour.Rose -> ElementTheme.colors.bgCriticalSubtle.blendWith(0xFFFFDCE4)
    NoteColour.Lilac -> ElementTheme.colors.bgSubtlePrimary.blendWith(0xFFE8DEFF)
    NoteColour.Sand -> ElementTheme.colors.bgSubtleSecondary.blendWith(0xFFF2E5D0)
}

/**
 * Mixes a pastel over the theme's own surface.
 *
 * In light mode the pastel dominates and the note looks like paper; in dark mode the theme's dark
 * surface dominates and the pastel is only a hint of hue, which is what keeps a dark-mode notes
 * wall readable instead of glowing.
 */
@Composable
private fun androidx.compose.ui.graphics.Color.blendWith(pastel: Long): androidx.compose.ui.graphics.Color {
    val tint = androidx.compose.ui.graphics.Color(pastel)
    val isLight = ElementTheme.isLightTheme
    val weight = if (isLight) 0.85f else 0.12f
    return androidx.compose.ui.graphics.lerp(this, tint, weight)
}

private const val CHECKLIST_PREVIEW = 4
