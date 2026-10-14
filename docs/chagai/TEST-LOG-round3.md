# Round 3 test log — 2026-10-14

Everything below was exercised on the **emulator clone** `android-clone-clone1`
(x86_64 / Android 13), starting from the shipped **26.09.4-chagai.8** APK, plus a full
line-by-line review of both fork feature modules.

The reminders were driven two ways:

- the **ring test page** — <http://chagai.website:8777/> — which creates a real call reminder
  through `reminder.py add --kind call` and shows, live, whether the phone's alarm event posted,
  whether the WhatsApp text went, and what the phone answered back;
- and by writing a reminder straight into the phone's own store and re-broadcasting
  `BOOT_COMPLETED`, which is the same path a reboot takes — so the alarm had to be rebuilt from
  the store and rung by `AlarmManager`, exactly as it would after a restart.

The emulator has no Matrix session signed in, so the Matrix-dependent halves (notes syncing to
the room, acknowledgements actually reaching the room) are covered by unit tests and by the
server-side record rather than by the device.

---

## What was verified working

| | |
|---|---|
| Cold start | 760 ms to first frame |
| Reminders list, empty state, settings screen | correct |
| Tone picker, previews, loud/gentle, ring-in-silent, torch toggle, DND row | correct |
| "Test the alarm" → alarm screen with Done / 5 / 15 / 60 / Tomorrow 9:00 | correct |
| Alarm raises `STREAM_ALARM` 2/7 → **7/7** while ringing | verified via `dumpsys audio` |
| Volume key silences the ring, keeps the screen, restores 7/7 → **2/7** | correct |
| Ring runs as a `mediaPlayback` foreground service, `category=alarm` | correct |
| **Reboot survival** — alarm rebuilt from the store and re-armed as an exact alarm clock | verified in `dumpsys alarm` |
| Done from the notification resolves the reminder and stops the ring | correct |
| Notes grid, colours, pinning, checklist counts, search box, trash, widget registered | correct |
| No crash, no ANR, no `FATAL` in `logcat` across the whole pass | clean |

---

## Defects found

Severity: **blocker** = can lose a reminder or a note · **high** = wrong behaviour a user will
hit · **medium** = wrong but survivable · **polish**.

### R3-1 · A real reminder rang with nothing on screen — **blocker** — fixed in chagai.9

**Reproduce (on .8):** schedule a reminder, let it fire. **Expected:** the alarm screen, as the
test button shows. **Actual:** a loud alarm and *nothing at all* on screen — no full-screen
alarm, not even a heads-up banner. The only way to stop it was to pull down the notification
shade and find the buttons. With the screen off the phone did not even wake.

**Cause.** The ring notification was built with `NotificationCompat.setSilent(true)`, because the
ring service plays the tone itself and must not have the channel play a second one. But
`setSilent` does more than mute: it puts the notification in the "silent" group with
`GROUP_ALERT_SUMMARY`, and the system suppresses *all* alerting for a group child whose summary
is absent — the heads-up and **the full-screen intent** included. The alarm screen was being
thrown away by the system before it ever reached the screen.

This hid for two rounds because **the test button is the one path that does not depend on it**:
it starts the alarm screen itself, from a visible activity, so the test always looked right. A
real alarm has only the full-screen intent, because an app may not start an activity from the
background — confirmed in `logcat`: `Abort background activity starts from 10192`.

**Fix.** Keep the ring channel silent (it already carries no sound and no vibration) by setting
sound/vibration/defaults to none directly, and never via `setSilent`. Alerting is now a separate
decision from silence: the first post may interrupt and carries the full-screen intent; a re-post
of an alarm the user has already silenced may not, so silencing stays silenced.
Also `FOREGROUND_SERVICE_IMMEDIATE`, so the notification is not held back for ten seconds —
ten seconds of a ringing phone showing nothing.

### R3-2 · "Done" sat on the phone until the app was next opened — **high** — fixed in chagai.9

The acknowledgement was only sent by a 30-second loop inside a live session. The tap that
resolves a reminder happens in a broadcast receiver, usually with the app long since evicted from
memory, so nothing was alive to run it. Observed on the real reminder of 2026-10-13: tapped Done
at 00:19, nothing in the chat until the app was opened hours later.

**Fix.** The receiver now asks for the acknowledgement immediately and the session is restored if
it is not already in memory — the same mechanism the app already uses to send a reply from the
notification shade. The 30-second loop stays as the retry net for when there is no network.

### R3-3 · "Snooze" was never reported, though the settings screen promises it — **high** — fixed in chagai.9

Settings says *"Posts 'done' or 'snoozed' back, so it also shows on WhatsApp"*. Only Done was
ever posted; the snooze branch did not exist. A snoozed reminder therefore looked identical to an
ignored one from the chat's side.

**Fix.** A snooze the user chose now posts `⏰ Snoozed to 15:20 — Call reminder #42`. The app's
own "nobody answered, ring again" re-arming deliberately posts nothing — that is the app nagging,
not the user doing something. The server watcher reads the new line and keeps watching, so the
later Done is recorded over the top.

### R3-4 · A reminder you once ignored re-rang every time you opened the app — **blocker** — fixed in chagai.9

Two ordinary states leave a reminder open with a due time in the past: escalation running out of
tries, and ringing with escalation turned off. `rescheduleAll` rang *anything* open and overdue,
and it runs on every session start, every time the reminders screen opens, and on every reboot.
So last Tuesday's ignored reminder fires a full-volume klaxon, for ever.

**Fix.** Only a reminder that has never rung, and is less than an hour late, is rung on the way
back from a reboot. Everything else stays open and visible, and silent.

### R3-5 · Walking away from a test ring left a permanent klaxon — **high** — fixed in chagai.9

The throwaway "test-ring" reminder was only cleaned up if the user *answered* it. Press Home
instead and it stayed in the store, open and overdue — R3-4's trigger, armed by the very button
meant to reassure you the feature works. Now cleaned up when the screen is left.

### R3-6 · "Call now" in the notification never opened the dialer — **high** — fixed in chagai.9

It was a broadcast that then started an activity, which Android 12 drops as a "notification
trampoline". The reminder was marked as called and the dialer never appeared — so the reminder
quietly disappeared without a call. It now opens the alarm screen directly and dials from there,
which also means the screen is waiting behind the dialer to ask "done, or remind you again?" when
the call ends.

### R3-7 · "Called" was never reported to the chat — **medium** — fixed in chagai.9

`ACTION_CALL` never recorded *when* it resolved, and the acknowledgement only fires for a
reminder that has. The `📞 Called` line was dead code.

### R3-8 · Anyone who could message you could hijack a reminder — **high** — fixed in chagai.9

Reminders are recognised by their text in **any** room, and the id is taken from the message
(`#42`) without being tied to the room. A stranger's `⏰ Call reminder #42` would merge over the
real #42 — moving its time, title and phone number, so the call you actually had to make never
rang. An id is now only honoured within the room it first appeared in.

### R3-9 · A second reminder on the same note silently did nothing — **high** — fixed in chagai.9

Note reminders reuse one id per note, and the store deliberately refuses to move a reminder the
user has already dealt with. So: set a reminder on a note, let it ring, tap Done, set a new one →
the editor showed the new time and no alarm existed.

### R3-10 · Notes could be lost to a sync — **blocker** — fixed in chagai.9

Three separate ways:

- **Typing during a send.** Sending is a network round-trip; anything typed while it was in
  flight was marked as sent when it had not been, and the echo of the older message later
  overwrote it. The user watches their own sentence revert. The send is now only "done" if the
  note still says what was actually sent.
- **A second notes room.** If the notes room could not be resolved — which also happens while the
  room list is still syncing, exactly the state a cold start is in — the app created a *new* room
  and repointed the account data at it, orphaning every existing note on every device. It now
  waits and retries instead.
- **Duplicate notes.** A note's event id only arrives with the timeline echo, so every keystroke
  in that window queued another *create*, putting several copies of the same note in the room.

### R3-11 · Ordinary syncs invented note versions and evicted real ones — **medium** — fixed in chagai.9

The stored note keeps a trailing newline and the empty checklist row the editor needs; the wire
format drops both. So the echo of the app's *own* message compared unequal, looked like somebody
else's edit, pushed a version nobody created and shoved a real one off the end of the 20-slot
history. Comparison is now done on the normalised form, which is the honest question — "does this
say anything different?".

### R3-12 · A version stopped being captured after switching apps — **medium** — fixed in chagai.9

An editing session ends at `onPause` and was never restarted, so leaving the note to check
something else and coming back meant the rest of that sitting was never recorded in the history.

### R3-13 · Typing in a note did far more work than it should — **high** — fixed in chagai.9

Every keystroke wrote the whole notes file *and* cancelled two alarms *and* wrote the reminders
file — even for a note with no reminder at all, where there was nothing to cancel. With version
history added, the notes file is up to 21× bigger per note, which turns a tolerable write into a
real one. Both paths now return early when nothing has changed.

---

## Known, deliberately not fixed yet

| | |
|---|---|
| **Alarm volume could stay at maximum if the ring service is killed mid-ring** (OOM, force-stop). The previous level is remembered in memory only. Needs to be written to disk and restored at next start. | medium |
| **A reminder time does not follow you across time zones.** "15:00" is resolved against the phone's zone when the message is read and frozen. Fly a zone west and it rings an hour early. Fixing it properly means carrying the wall-clock time and the zone, which is a design decision, not a patch. | medium |
| **In a shared notes room, any member could overwrite a note by id.** Inherent to "a note is a message"; the fix (only honour notes from yourself) changes what sharing means, so it is Chagai's call. | medium |
| Alarm-notification ids are truncated to 16 bits, so a collision would let one reminder's dismissal clear another's notification. Negligible odds. | polish |
| `NoteWireFormat.parseTime` is lenient, so a malformed due time becomes a *wrong* time rather than none. | polish |
| Neither parser caps input length, so a very large message body is read in full. | polish |

---

## How to re-run this pass

```bash
# the device
sudo docker exec android-clone-clone1 adb install -r /tmp/mc.apk      # x86_64 split
sudo docker exec android-clone-clone1 adb shell am start-activity \
    -n website.chagai.matrixchat/io.element.android.features.callreminders.impl.ui.CallRemindersActivity

# a real alarm, without a Matrix session: write the store, then do what a reboot does
sudo docker exec android-clone-clone1 adb shell 'su 0 sh -c "cat > \
    /data/data/website.chagai.matrixchat/files/call-reminders.json"'   # see the JSON shape below
sudo docker exec android-clone-clone1 adb shell \
    'su 0 am broadcast -a android.intent.action.BOOT_COMPLETED -p website.chagai.matrixchat -f 0x20'

# what to watch
sudo docker exec android-clone-clone1 adb shell 'dumpsys alarm | grep -A4 matrixchat'
sudo docker exec android-clone-clone1 adb shell 'dumpsys audio | grep -A6 "^- STREAM_ALARM"'
sudo docker exec android-clone-clone1 adb shell 'dumpsys activity services website.chagai.matrixchat'
```

The `-f 0x20` matters: a force-stopped package receives no broadcast without
`FLAG_INCLUDE_STOPPED_PACKAGES`.

A reminder row is one object of
`{id, roomId, sessionId, title, contact, phone, notes, dueAtMillis, originalDueAtMillis,
snoozeOptionsMinutes, state, escalationCount, resolvedAtMillis, snoozedAtMillis, acknowledged,
gentle}`; `id` must start with `server-` for the acknowledgement to carry a reference.
