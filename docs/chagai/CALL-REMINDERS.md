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

### How it rings — and why that changed

**The first version rang with a notification.** A channel whose sound was the alarm ringtone
with `AudioAttributes.USAGE_ALARM`, plus `Notification.FLAG_INSISTENT` so the sound looped until
it was dealt with, plus a full-screen intent. Textbook, and reliable.

**It was also too quiet**, and the reason is structural, not a bug: a notification channel plays
*at whatever `STREAM_ALARM` is currently set to*. On a phone that is not used as an alarm clock
the alarm volume sits wherever it was last left, usually well below maximum, so a reminder
arrived as a polite chime. A channel also cannot fade in, cannot use a vibration pattern the user
picked, and cannot be made to stop making noise without taking the whole notification down.

So the sound moved into **`AlarmRingService`**, a foreground service of type `mediaPlayback`
whose only job is to be loud:

| What it does | Why |
|---|---|
| Raises `STREAM_ALARM` to **maximum** while it rings, and restores the previous level on **every** exit path (`stopEverything`, `onDestroy`, the hard timeout) | this is the single change that turns "I didn't hear it" into "that woke me up". Borrowing the volume is only acceptable because it is always given back. |
| Plays the tone itself: `MediaPlayer`, `USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION`, `isLooping = true` | continuous sound rather than a notification's one-shot-with-a-repeat-flag, and the app gets to choose the tone |
| Takes `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` | music and podcasts stop for the alarm instead of playing underneath it |
| **Ramps** from 40 % to full over 10 s (configurable, 0 = straight to full) | loud enough to wake you, not loud enough to hurt if the phone is on the pillow. Done on the *player's* gain, not the stream: the stream is already at maximum, which is the point. |
| Vibrates a repeating `VibrationEffect.createWaveform(..., repeat = 0)` with `VibrationAttributes.USAGE_ALARM` | a repeating waveform reads as an alarm; a one-shot pattern reads as a notification |
| Optionally blinks the camera torch (`CameraManager.setTorchMode`) | for a phone face down or across the room. Opt-in. |
| Holds a `PARTIAL_WAKE_LOCK` **with a timeout** | the tone cannot be cut short by the CPU idling, and a bug cannot leave the lock held |
| Stops itself after `autoSilenceMinutes` + 15 s, capped at 15 min | whatever else goes wrong, the phone stops ringing |

**The notification keeps its real job** — the buttons, and the full-screen intent that brings the
alarm screen up over the lock screen. While the service is playing, that notification is posted
on a new channel, **`call_reminders_ring`, which is deliberately silent and vibration-free**:
otherwise the channel's tone would play on top of the service's. The service adopts it as its own
foreground notification, so there is one thing on screen, not two.

**The old channels are now the fallback.** Android 12+ can refuse a foreground-service start from
the background. An exact alarm is one of the documented exemptions, so this should not happen —
but if an OEM disagrees, `AlarmRingService.ring()` returns false and the caller falls back to the
original insistent-notification path. Quieter, but it still rings. *A reminder that does not ring
at all is the one unacceptable outcome*, so there is always a second path.

#### Silent mode and Do Not Disturb

`USAGE_ALARM` is what gets through a **silenced ringer** — that works with no permission at all,
and it is the common case. A full **Do Not Disturb** is a separate grant: `setBypassDnd(true)`
only sticks once the user has given the app notification-policy access, which no app can grant
itself. The channels ask for it when the grant is present, and the reminders screen has a row
that opens the system screen and shows whether it is in place. Without the grant everything
still works, just not through DND.

`setStreamVolume` also throws while DND is active without that access, so the volume takeover is
wrapped — the tone then plays at the current level rather than not at all.

### The tones

Five tones ship in `features/callreminders/impl/src/main/res/raw/`, **all generated from
scratch** by `tools/chagai/make_tones.py` (numpy synthesis → 16-bit WAV → Ogg Vorbis q6 via
ffmpeg). Nothing sampled, nothing anybody else owns — which matters in an AGPL build, and means
each one is built to loop seamlessly at a known loudness instead of being whatever a stock file
happens to be.

| Tone | id | Length | What it is |
|---|---|---|---|
| **Marimba Rise** *(default)* | `marimba_rise` | 6.4 s | A pentatonic arpeggio climbing through four passes, each louder and higher, over a low octave on the beat. Bright enough to pull your eyes to the phone, warm enough to wake up to. |
| **Pulse** | `synth_pulse` | 4.8 s | Filtered saw stabs on every eighth with a rising siren glide and a tremolo. The one that will not be slept through. |
| **Bell Cascade** | `bell_cascade` | 6.0 s | Tubular-bell strikes (the inharmonic 2.76 / 5.40 / 8.93 partials) falling down a scale and overlapping into a shimmer. |
| **Old Telephone** | `retro_ring` | 4.0 s | Two close sine partials beating against each other, chopped at 22 Hz — the trill of a twin-bell handset. Deliberately not any network's standard cadence. |
| **Sunrise** | `sunrise` | 8.0 s | A swelling pad with 1, then 2, then 4, then 8 notes on it. The *tone* escalates, so it feels more urgent even at a fixed volume. |
| Phone's own alarm sound | `system_alarm` | — | `RingtoneManager.TYPE_ALARM`. For "the sound I already wake up to". |

Three details that matter more than they look:

- **Seamless loops.** The player loops, so a click at the seam would be audible every few
  seconds and would quickly sound broken. Each tone is a whole number of beats long and is
  cross-faded into its own head (`loop_seam`).
- **Peak-normalised to −1 dBFS, then soft-limited.** Peak normalisation alone is not enough: a
  struck marimba spends most of its loop decaying, so its *average* level — which is what
  loudness actually sounds like — sat ~14 dB below the peak and read as thin on a phone speaker.
  A `tanh` soft clipper brings the average up to −7…−10 dBFS with no audible distortion, because
  it rounds the peaks off instead of squaring them. The drive is per tone, so Sunrise stays
  genuinely quiet at the start and Pulse is pushed hard.
- **Attention through movement, not harshness.** Everything here changes as it plays. A steady
  tone is both easy to habituate to and unpleasant; a moving one is what the ear refuses to
  ignore.

Regenerate with:

```bash
python3 tools/chagai/make_tones.py           # all five
python3 tools/chagai/make_tones.py --only alarm_marimba_rise
```

The ids in `AlarmTone.kt` and the file names in `res/raw` must stay in step; an unknown id in
settings falls back to the default rather than ringing nothing.

The picker previews on tap — `TonePreview`, four and a half seconds, no volume takeover, no
vibration, stops itself. Choosing a tone in a quiet room should not blast the room. "Test the
alarm" is the button that does the real, loud thing, through the real service.

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
- **Settings**, in two groups. *How loud it rings*: the tone picker (with preview), use full
  alarm volume, fade-in, vibrate, blink the torch, ring in silent mode, a Do Not Disturb row that
  opens the system grant screen, and a **test-ring** button that rings the real alarm with a
  throwaway reminder through the real service — so "will this actually wake me?" is answerable
  without waiting. *How the alarm behaves*: escalation, gentle by default, send
  acknowledgements, show the next reminder, snooze presets.

## Permissions this adds

`USE_EXACT_ALARM` (granted on install), `SCHEDULE_EXACT_ALARM` (Android 12 fallback only),
`USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`,
and for the ring service `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
`MODIFY_AUDIO_SETTINGS` and `ACCESS_NOTIFICATION_POLICY`.

All of those are install-time or user-granted-in-settings. There is **no `CALL_PHONE`, no
`READ_CONTACTS`, no `CAMERA`** — the torch blink uses `CameraManager.setTorchMode`, which needs
no permission, and "Call now" uses `ACTION_DIAL`. Nothing here reads anything off the phone.

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
