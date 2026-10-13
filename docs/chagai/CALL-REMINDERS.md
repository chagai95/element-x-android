# Call reminders

Ask for a reminder to phone someone, and at the moment it is due the phone **rings like a clock
alarm** — full screen, over the lock screen, on the alarm audio stream so it cuts through a
silenced ringer — with **Call now / Done / Snooze** on it.

```
you:    remind me to call Elisheva at 15:00 about Disney+
server: reminder.py add --kind call --at "15:00" --contact Elisheva \
          --phone +4179… --notes "about Disney+" --text "Call Elisheva"
phone:  [m]  ⏰  at 15:00 the phone rings
```

---

## Why it is built this way

The obvious design is "the server sends a push at 15:00 and the app rings". That design is wrong,
and it is wrong in a way that is invisible until the one time it matters: pushes are delayed by
Doze, by a flaky network, by the phone being off, by the bridge being behind. A reminder that
rings at 15:07 is not an alarm.

So the phone is told about the reminder **when it is created**, stores it locally, and sets a
real system alarm (`AlarmManager.setAlarmClock`, the API clock apps use — exact, Doze-proof, and
shown in the status bar as a genuine next alarm). From that moment the ring does not depend on
the network, the server, or the app running.

Everything else follows from that one decision.

## The event

A reminder is an ordinary `m.room.message` in the room. That matters: it still reaches WhatsApp
through the bridge, exactly as reminders do today, and any client that does not know about call
reminders simply shows a sensible message.

**The visible body** is a fixed shape:

```
⏰ Call reminder #42: Call Elisheva
🕒 Wed 14 Oct 2026, 15:00
📞 +41791234567
📝 about Disney+
```

**And the same event carries a structured field:**

```json
"website.chagai.reminder": {
  "v": 1,
  "id": 42,
  "kind": "call",
  "title": "Call Elisheva",
  "contact": "Elisheva",
  "phone": "+41791234567",
  "notes": "about Disney+",
  "due": "2026-10-14T15:00:00+0200",
  "snooze_options": [5, 15, 60]
}
```

### Why both

Because the app can only see one of them on most of its code paths. Element X's Kotlin API hands
the UI *typed* content — the raw event JSON is reachable from a timeline, but not from the room
list or from a push notification, which are the two places the app actually notices a message
arriving. The structured field is therefore the exact, machine-first form, used where it is
available; the body is the form that always works.

That is why the body's shape is fixed and dull, with English day and month names and a
literal `#id`. It is a wire format wearing the clothes of a sentence. `CallReminderParser` reads
it and `reminder.py`'s `friendly_time()` writes it — **change one and you must change the other**,
which is why both carry a comment saying so and why `CallReminderParserTest` pins every case.

## Server side

`matrix-os/bin/reminder.py` gained call reminders **additively**: new columns, new optional
flags, same table, same runner, same firing path. Every pre-existing reminder has
`payload_kind='text'` and behaves exactly as before.

```bash
reminder.py add --kind call --at "tomorrow 15:00" \
    --contact "Elisheva" --phone "+41791234567" --notes "about Disney+" \
    --text "Call Elisheva"

reminder.py ack 42 --state done           # record what the phone did
reminder.py ack 42 --state snoozed --until "15 minutes"
reminder.py announce 42                   # re-tell the phone (e.g. after reinstalling)
```

`--kind call` is still scheduled as a *time* reminder — it is the payload that differs, not the
scheduling — so the existing due-query and the runner were not touched at all. On top of storing
the row, `add` posts the structured event immediately via `bin/send-reminder-event.py`, because
the phone needs the alarm before the time arrives. If that post fails the reminder still fires
normally through WhatsApp; only the alarm is missing, and `announce` can fix it later.

New columns: `payload_kind`, `call_phone`, `call_contact`, `call_notes`, `snooze_opts`,
`app_event_id`, `ack_state`, `ack_at`, `ack_detail`.

## App side

`features/callreminders/{api,impl}`.

| Piece | What it does |
|---|---|
| `CallReminderParser` | message → reminder, from the structured field or the body. Unit tested. |
| `CallReminderStore` | a JSON file, read once and held in memory, written atomically. The reason a reminder survives a reboot, a force-stop or being offline. |
| `CallReminderScheduler` | `setAlarmClock` per reminder; falls back to `setExactAndAllowWhileIdle` on an Android 12 where exact alarms were revoked. `rescheduleAll` rebuilds everything from the store. |
| `CallReminderAlarmReceiver` | the moment it goes off: post the ringing notification, bring up the screen, arm auto-silence. |
| `CallReminderNotifications` | the ring itself (see below) and the quiet "next call reminder" line. |
| `CallReminderAlarmActivity` | the full-screen alarm, over the lock screen, not dismissible by the back gesture. |
| `CallRemindersActivity` / `CallRemindersScreen` | upcoming / snoozed / done, with per-row actions, plus the settings. |
| `CallReminderActionReceiver` | Done / Snooze / Call now — one code path for the notification buttons and the on-screen buttons, so they cannot drift apart. |
| `BootCompletedReceiver` | rebuilds alarms after a reboot, an app update, or a clock/time-zone change. |
| `DefaultCallReminderSessionObserver` | watches the room list for reminders and posts outcomes back. Started from `MatrixSessionCache` alongside the sync orchestrator. |

### How it rings without a service

The ringing is done by the **notification**, not by a foreground service: a channel whose sound
is the alarm ringtone with `AudioAttributes.USAGE_ALARM`, and a notification carrying
`Notification.FLAG_INSISTENT` so the sound **loops** until it is dealt with, plus a full-screen
intent so the alarm screen comes up over the lock screen.

That is alarm-clock behaviour with nothing to keep alive: no foreground-service type to argue
with on Android 14, nothing to leak if the process is killed, and the user can retune the sound
in Android's own notification settings because it is a real channel. Two channels exist so
**loud** and **gentle** are genuine system-level choices.

`USAGE_ALARM` is what gets through a silenced ringer. Do Not Disturb is a separate permission
(`setBypassDnd` only takes effect once the user grants DND access in system settings); the code
asks for it and carries on without it.

### "Call now" never places a call

It fires `ACTION_DIAL`, which opens the dialer with the number filled in and leaves the button to
the user. The app therefore never needs — and never requests — `CALL_PHONE`.

### What else it does

- **Escalation.** Unanswered, it goes quiet after a few minutes and tries again, up to three
  times (both configurable). A missed reminder nags; it does not vanish.
- **Snooze** 5 / 15 / 60 (presets configurable), or **tomorrow at 9:00** in one tap.
- **Volume keys silence the ring and keep the screen** — what every alarm clock does, and what
  stops a half-awake press from losing the reminder.
- **After "Call now"**, returning to the screen asks whether it is done or should come back.
- **Acknowledgements** are posted back into the room — `✅ Done 15:02 — Call reminder #42` — so
  the outcome also shows up on WhatsApp and `reminder.py ack` can record it. Sent on a slow loop
  rather than at the moment of the tap, because the tap happens in a broadcast receiver that may
  outlive the app process; this way an outcome is reported as soon as a session is alive again
  and is never lost.
- **A quiet "next call reminder"** line in the shade, and a launcher long-press shortcut to the
  list.
- **Settings**: ring in silent mode, escalation, gentle by default, send acknowledgements, show
  the next reminder, snooze presets, and a **test-ring** button that rings the real alarm with a
  throwaway reminder — so "will this actually wake me?" is answerable without waiting.

## Permissions this adds

`USE_EXACT_ALARM` (granted on install), `SCHEDULE_EXACT_ALARM` (Android 12 fallback only),
`USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`.
No `CALL_PHONE`, no `READ_CONTACTS`, nothing that reads anything.

## Known limits

- **A reminder created while the phone is offline** is only picked up when the app next syncs. If
  that happens after the due time it still rings (anything up to an hour late is treated as "ring
  now"), but not at the second.
- **The room list carries only the latest message per room.** If another message lands in the same
  room in the same instant, the reminder can be missed; opening the app or the reminders list
  re-syncs and re-schedules. In the self-chat, where reminders live, this is close to impossible.
- **Do Not Disturb** needs its system permission granted once, by hand, for a reminder to pierce
  it. Silent mode needs nothing.
- **Not yet built**: creating a reminder *from* the app (long-press a message → "remind me to call
  about this", or a `/call` composer command). Those touch upstream's message code, so they are a
  separate change; the loop for adding them is in `RELEASE.md`.
