/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.store

import android.content.Context
import io.element.android.features.callreminders.impl.model.CallReminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Where reminders live on the phone.
 *
 * A plain JSON file, read once and then held in memory. It is deliberately simple and
 * synchronous: every caller is either a broadcast receiver with a few milliseconds to live or an
 * activity being created, and the file holds a handful of rows, so a database would buy nothing
 * and cost a lot of machinery. Writes are whole-file and atomic (write a temp file, then rename),
 * so a kill mid-write cannot leave a truncated store behind.
 *
 * This is the reason a reminder survives a reboot, a force-stop or being offline: the alarm is
 * rebuilt from here, not from the server.
 */
class CallReminderStore private constructor(private val file: File) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(CallReminder.serializer())

    private val _reminders = MutableStateFlow(load())

    /** Everything the phone knows about, newest due time last. Updated on every change. */
    val reminders: StateFlow<List<CallReminder>> = _reminders.asStateFlow()

    fun all(): List<CallReminder> = _reminders.value

    fun get(id: String): CallReminder? = _reminders.value.firstOrNull { it.id == id }

    /** Reminders still waiting to ring, soonest first. */
    fun open(): List<CallReminder> = _reminders.value.filter { it.isOpen }.sortedBy { it.dueAtMillis }

    /**
     * Adds a reminder, or refreshes the one already stored under the same id.
     *
     * An update never resurrects a reminder the user has already dealt with, and never moves one
     * the user has snoozed: the phone's copy is the truth once the user has touched it. Returns
     * the stored reminder when something actually changed, else null — the caller uses that to
     * decide whether to (re)schedule an alarm.
     */
    fun upsert(reminder: CallReminder): CallReminder? = synchronized(lock) {
        val current = _reminders.value
        val existing = current.firstOrNull { it.id == reminder.id }
        when {
            existing == null -> {
                save(current + reminder)
                reminder
            }
            !existing.isOpen -> null
            existing.state == CallReminder.State.Snoozed -> null
            existing.dueAtMillis == reminder.dueAtMillis &&
                existing.title == reminder.title &&
                existing.phone == reminder.phone &&
                existing.notes == reminder.notes -> null
            else -> {
                val merged = existing.copy(
                    title = reminder.title,
                    contact = reminder.contact,
                    phone = reminder.phone,
                    notes = reminder.notes,
                    dueAtMillis = reminder.dueAtMillis,
                    originalDueAtMillis = reminder.originalDueAtMillis,
                    snoozeOptionsMinutes = reminder.snoozeOptionsMinutes,
                )
                save(current.map { if (it.id == merged.id) merged else it })
                merged
            }
        }
    }

    fun update(id: String, transform: (CallReminder) -> CallReminder): CallReminder? = synchronized(lock) {
        val current = _reminders.value
        val existing = current.firstOrNull { it.id == id } ?: return null
        val updated = transform(existing)
        save(current.map { if (it.id == id) updated else it })
        updated
    }

    fun remove(id: String) = synchronized(lock) {
        save(_reminders.value.filterNot { it.id == id })
    }

    /**
     * Drops resolved reminders older than [keepForMillis] so the file cannot grow without bound,
     * while still leaving a readable recent history on the reminders screen.
     */
    fun prune(now: Long = System.currentTimeMillis(), keepForMillis: Long = DEFAULT_HISTORY_MILLIS) = synchronized(lock) {
        val kept = _reminders.value.filter { it.isOpen || (it.resolvedAtMillis ?: it.dueAtMillis) > now - keepForMillis }
        if (kept.size != _reminders.value.size) save(kept)
    }

    private fun load(): List<CallReminder> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString(serializer, file.readText())
    }.onFailure {
        Timber.e(it, "Could not read the call reminder store; starting empty")
    }.getOrDefault(emptyList())

    private fun save(value: List<CallReminder>) {
        _reminders.value = value.sortedBy { it.dueAtMillis }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, _reminders.value))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Timber.e(it, "Could not write the call reminder store") }
    }

    companion object {
        private const val FILE_NAME = "call-reminders.json"
        private const val DEFAULT_HISTORY_MILLIS = 30L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: CallReminderStore? = null

        /**
         * One store per process. Broadcast receivers, activities and the session observer all
         * reach it this way, so they are guaranteed to be looking at the same state.
         */
        fun get(context: Context): CallReminderStore =
            instance ?: synchronized(this) {
                instance ?: CallReminderStore(File(context.applicationContext.filesDir, FILE_NAME)).also { instance = it }
            }
    }
}
