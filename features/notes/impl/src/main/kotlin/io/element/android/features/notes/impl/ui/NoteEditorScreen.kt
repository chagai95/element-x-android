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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.notes.impl.R
import io.element.android.features.notes.impl.model.ChecklistItem
import io.element.android.features.notes.impl.model.Note
import io.element.android.features.notes.impl.model.NoteColour
import io.element.android.features.notes.impl.model.NoteVersion
import io.element.android.features.notes.impl.parser.NoteWireFormat
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.TopAppBar

/**
 * Writing a note.
 *
 * Everything on this screen saves **as you type**, straight into the local store — there is no
 * save button, and leaving by any route keeps the note. That is only possible because the store
 * is local and synchronous; the room catches up afterwards.
 *
 * The title may be left empty: it is derived from the first line when the note is saved, trimmed
 * back to a word boundary, which is a trick worth keeping from Supernote — it means you never
 * have to name a thought before writing it down.
 */
@Composable
fun NoteEditorScreen(
    note: Note,
    onBack: () -> Unit,
    onChange: (Note) -> Unit,
    onTrash: () -> Unit,
    onPickReminder: () -> Unit,
    onClearReminder: () -> Unit,
    onRestoreVersion: (NoteVersion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                titleStr = stringResource(R.string.notes_editor_title),
                navigationIcon = { BackButton(onClick = onBack) },
                actions = {
                    IconButton(onClick = { onChange(note.copy(pinned = !note.pinned)) }) {
                        Icon(
                            imageVector = if (note.pinned) CompoundIcons.PinSolid() else CompoundIcons.Pin(),
                            contentDescription = stringResource(R.string.notes_action_pin),
                            tint = if (note.pinned) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.iconSecondary,
                        )
                    }
                    IconButton(onClick = onTrash) {
                        Icon(
                            imageVector = CompoundIcons.Delete(),
                            contentDescription = stringResource(R.string.notes_action_trash),
                            tint = ElementTheme.colors.iconSecondary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            TextField(
                value = note.title,
                onValueChange = { onChange(note.copy(title = it)) },
                placeholder = stringResource(R.string.notes_editor_title_placeholder),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = note.body,
                onValueChange = { onChange(note.copy(body = it)) },
                placeholder = stringResource(R.string.notes_editor_body_placeholder),
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            ColourRow(note.colour) { onChange(note.copy(colour = it)) }

            Spacer(Modifier.height(16.dp))
            ChecklistEditor(
                items = note.checklist,
                onItemsChange = { onChange(note.copy(checklist = it)) },
            )

            Spacer(Modifier.height(16.dp))
            ReminderRow(note, onPickReminder, onClearReminder)

            if (note.versions.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HistorySection(note.versions, onRestoreVersion)
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(
                    if (note.isUnconfirmed) R.string.notes_editor_not_synced else R.string.notes_editor_synced
                ),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = if (note.isUnconfirmed) ElementTheme.colors.textActionAccent else ElementTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun ColourRow(selected: NoteColour, onPick: (NoteColour) -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.notes_editor_colour),
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NoteColour.entries.forEach { colour ->
                val isSelected = colour == selected
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(colour.background(), CircleShape)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) ElementTheme.colors.borderInteractivePrimary else ElementTheme.colors.borderInteractiveSecondary,
                            shape = CircleShape,
                        )
                        .clickable { onPick(colour) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = CompoundIcons.Check(),
                            contentDescription = null,
                            tint = ElementTheme.colors.iconPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The checklist.
 *
 * Supernote never had one — this is new — but it is the thing a note app is used for more than
 * anything else. Each row edits in place; an empty row at the bottom is how a new item is added,
 * so there is no "add item" button to find.
 */
@Composable
private fun ChecklistEditor(
    items: List<ChecklistItem>,
    onItemsChange: (List<ChecklistItem>) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.notes_editor_checklist),
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textSecondary,
        )
        items.forEachIndexed { index, item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(
                    onClick = {
                        onItemsChange(items.toMutableList().also { it[index] = item.copy(done = !item.done) })
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (item.done) CompoundIcons.CheckCircleSolid() else CompoundIcons.Circle(),
                        contentDescription = null,
                        tint = if (item.done) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.iconTertiary,
                    )
                }
                TextField(
                    value = item.text,
                    onValueChange = { text ->
                        onItemsChange(items.toMutableList().also { it[index] = item.copy(text = text) })
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onItemsChange(items.filterIndexed { i, _ -> i != index }) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = CompoundIcons.Close(),
                        contentDescription = stringResource(R.string.notes_action_remove_item),
                        tint = ElementTheme.colors.iconTertiary,
                    )
                }
            }
            if (item.done) {
                // A ticked item reads as done even while it is being edited.
                Text(
                    text = item.text,
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary,
                    textDecoration = TextDecoration.LineThrough,
                    modifier = Modifier.padding(start = 44.dp),
                )
            }
        }
        TextButton(
            text = stringResource(R.string.notes_editor_add_item),
            onClick = { onItemsChange(items + ChecklistItem("")) },
        )
    }
}

/**
 * Earlier versions of the note, collapsed to one line until asked for.
 *
 * The long-press-to-restore Supernote had, grown up a little: every version shows when it was
 * saved and what it said, and restoring keeps a copy of the current content, so nothing here can
 * lose anything. Versions are captured when an editing session ends — not per keystroke — and
 * live only on this phone; the room's own edit history is the cross-device copy.
 */
@Composable
private fun HistorySection(versions: List<NoteVersion>, onRestore: (NoteVersion) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = CompoundIcons.History(),
                contentDescription = null,
                tint = ElementTheme.colors.iconSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.notes_editor_history, versions.size),
                style = ElementTheme.typography.fontBodySmMedium,
                color = ElementTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(
                    if (expanded) R.string.notes_editor_history_hide else R.string.notes_editor_history_show
                ),
                onClick = { expanded = !expanded },
            )
        }
        if (expanded) {
            Text(
                text = stringResource(R.string.notes_editor_history_subtitle),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
            versions.forEach { version ->
                Spacer(Modifier.height(8.dp))
                VersionRow(version, onRestore)
            }
        }
    }
}

@Composable
private fun VersionRow(version: NoteVersion, onRestore: (NoteVersion) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = NoteWireFormat.formatTime(version.savedAtMillis),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
            Text(
                text = version.title.ifBlank { stringResource(R.string.notes_untitled) },
                style = ElementTheme.typography.fontBodySmMedium,
                color = ElementTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (version.body.isNotBlank()) {
                Text(
                    text = version.body,
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (version.checklist.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.notes_checklist_progress,
                        version.checklist.count { it.done },
                        version.checklist.size,
                    ),
                    style = ElementTheme.typography.fontBodyXsRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
        }
        TextButton(
            text = stringResource(R.string.notes_action_restore_version),
            onClick = { onRestore(version) },
        )
    }
}

@Composable
private fun ReminderRow(note: Note, onPick: () -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = CompoundIcons.Time(),
            contentDescription = null,
            tint = if (note.remindAtMillis != null) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.iconSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = note.remindAtMillis
                    ?.let { NoteWireFormat.formatTime(it) }
                    ?: stringResource(R.string.notes_editor_no_reminder),
                style = ElementTheme.typography.fontBodyMdRegular,
                color = ElementTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.notes_editor_reminder_subtitle),
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
        }
        TextButton(
            text = stringResource(if (note.remindAtMillis == null) R.string.notes_editor_set_reminder else R.string.notes_action_change),
            onClick = onPick,
        )
        if (note.remindAtMillis != null) {
            TextButton(text = stringResource(R.string.notes_action_clear), onClick = onClear)
        }
    }
}
