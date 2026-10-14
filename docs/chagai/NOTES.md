# Notes

Notes, built into Matrix Chat, inspired by Chagai's own 2019 note app **Supernote**
(`chagai95/supernote.org`, Apache-2.0). What Supernote had, what came across and what did not is
in `SUPERNOTE-INVENTORY.md`, feature by feature with a reason for each. This file is the design.

Implemented in `features/notes/{api,impl}`.

---

## The one decision everything follows from

**A note is a Matrix message, in a room of its own.**

Supernote's notes lived in Firestore, and almost everything hard about it followed from that:
sharing meant maintaining a `shared[]` array of user ids on every document; multi-device meant a
per-device subcollection and a "Server data vs Local data" conflict dialog; history meant two
parallel string arrays grown with `arrayUnion`; offline meant a cache-warming routine and a
`keepOffline` flag per note.

Putting notes in a Matrix room deletes all of that work:

| What it needed | What the room gives |
|---|---|
| Sync across devices | the homeserver already does it |
| Sharing with a person | invite them to the room |
| Edit history | `m.replace` — every client already shows edits |
| Offline edits | the Rust SDK's send queue |
| Encryption | the room is encrypted |
| Surviving a reinstall | the notes are on the server; a new login re-reads them |
| Reading notes elsewhere | they are messages — Element, a web client, anything |

The cost is one constraint, which shapes the rest of this document: the Kotlin API this fork
builds on can only send a message **body**, not a custom event field. So the body *is* the
format.

---

## Where a note lives

A room called **📝 Notes** — private, encrypted, invite-only, not in the directory. Created once,
on the first run that has a session.

Its room id is stored in the account's **global account data** under `website.chagai.notes`:

```json
{"room_id": "!abcdef:chagai.website"}
```

Account data rather than a room tag or a name match, because:

- it **syncs**, so a second device finds the same room instead of creating a second one;
- it survives a reinstall, so logging back in picks the existing notes straight up;
- a room *name* is not unique and anyone in the room can change it, which would silently orphan
  every note.

If the stored room is no longer joined, a new one is created and recorded. Notes already in the
old room are not migrated — leaving the notes room is treated as deliberate.

## The wire format

```
📝 Groceries
Milk for the week, and ask about the bread.
☐ milk
☑ bread
☐ eggs
🔖 3f9c2a · butter · pinned · due Wed 14 Oct 2026, 09:00
```

| Part | Meaning |
|---|---|
| **Line 1**, `📝 ` + text | the title. Its presence is what makes a message a note. |
| lines beginning `☐ ` / `☑ ` | checklist items, unticked and ticked, in order |
| everything else in between | the body |
| **last line**, `🔖 ` + tokens | metadata: the note id first, then any of a colour, `pinned`, and `due <time>`, separated by ` · ` |

Three properties this buys, all deliberate:

1. **It is readable everywhere.** In Element, in a web client, through the WhatsApp bridge, it
   looks like a note. A custom event type would read as "unsupported event" in every client but
   this one.
2. **It degrades.** An unknown token from a newer build is ignored rather than losing the note; a
   malformed due time is dropped rather than throwing; a note typed by hand with no `🔖 ` line
   still parses and gets a stable id derived from its event id.
3. **Editing is free.** A change is an `m.replace` on the message, which every client understands
   as an edit — so the version history Supernote maintained by hand is the room's own, with no
   second data structure.

The due time uses **the same format the call-reminder wire format uses** —
`EEE d MMM yyyy, HH:mm`, `Locale.UK` — so there is one time format across the fork rather than
two that can drift. `NoteWireFormatTest` pins every case, including the round trip, the
hand-typed note, the unknown token, the malformed time and the empty note.

> **The body is a wire format.** Changing `NoteWireFormat` changes how every note already in the
> room reads. The tests are the contract.

## How the screen and the room are connected

```
  NotesScreen / NoteEditorScreen
        │  every keystroke
        ▼
     NotesStore           ← the only thing the UI talks to
        │  pending: Create | Update | Delete
        ▼
 DefaultNotesSessionObserver   ← the only thing the server talks to
        │                          ▲
        └── send / edit / redact ──┘ read the timeline back in
```

- **The UI never touches the network.** That is why notes open instantly, why editing works on
  the Underground, and why there is no save button — every change is written to a local JSON file
  synchronously.
- **The observer drains the outbox** on a 15-second loop rather than at the moment of the tap,
  because the tap happens in an activity that may be gone before a send completes. The same
  reasoning as the call-reminder acknowledgements next door.
- **A local edit always wins.** `NotesStore.applyRemote` ignores an incoming version while the
  phone has unsent changes for that note, so syncing can never overwrite something the user can
  see on screen.
- The store is written **whole-file and atomically** (temp file, then rename), so a kill
  mid-write cannot leave a truncated store.

### The "orange text" trick, kept

Supernote's loveliest detail: a note being opened was painted `#DF3B0D` and filled instantly from
the in-memory cache, *before* any fetch — so the screen was never blank — and flipped to black
when the real value arrived. It was not a stored state; the colour was a side effect of which
method had run last.

The idea is right: **show the value you already have, do not show a spinner.** Here it is an
explicit flag (`Note.isUnconfirmed`) rather than an accident of control flow, and a note the
server has not confirmed yet is drawn in the accent colour with "Not synced yet" underneath. Same
instinct, legible cause.

## What the screens do

**The wall** (`NotesScreen`) — a two-column grid by default, one column on a tap. The grid is
what makes colours and pinning worth having, and it is the one thing Supernote's list could not
do. Pinned notes come first.

- **Search** filters **in memory on every keystroke** rather than querying. That is how Supernote
  did it and it was right: a person's notes fit in memory, and instant beats clever. Title, body
  and checklist text are all searched.
- **`missed reminders`** is kept as a magic query — type it and the list becomes notes whose due
  time has passed. It was in Supernote and it is genuinely useful.
- **Checklist items tick from the card**, without opening the note. The tick edits the message,
  so it syncs.
- **Trash** rather than delete, with a separate trash view, restore, and delete-for-good — which
  redacts the message.

**The editor** (`NoteEditorScreen`) — title, body, six colours, a checklist, a due time.

- The **title may be left empty**: it is derived from the first line when saved, cut at 25
  characters and trimmed back to the last space so it never breaks a word. Straight from
  Supernote, and the reason you never have to name a thought before writing it down.
- Colours are **an explicit pair of values per colour**, one per theme. The first version mixed
  a pastel over a semantic Compound colour and dark mode exposed why that is wrong: "Butter" was
  built on `bgCriticalSubtle`, which is a *red* in dark mode, so a yellow note rendered maroon.
  Semantic colours mean "this is an error", not "this is warm", and their hue is free to change
  between themes — which is exactly what a note colour must not do. Light gets paper pastels;
  dark gets the same hue at a lightness that reads as a tinted dark surface, because a
  sticky-note yellow that looks right on white is a torch at night.
- A note with **no content at all is discarded** when you leave it, so opening the editor by
  accident costs nothing.

## Note reminders

A note with a due time **rings through the call-reminder alarm** — it is turned into a
`CallReminder` with the id `note-<noteId>` and handed to the machinery that already exists.

That is a deliberate choice rather than a shortcut. It means:

- the same **loud** ring: alarm stream at maximum, looped tone, ramp, vibration, full-screen over
  the lock screen (see `CALL-REMINDERS.md`);
- the same Done / Snooze buttons and the same escalation;
- **one list** showing everything that is going to ring, not two;
- rebuilt after a reboot, an app update or a clock change by the receiver that already does that;
- one place where ringing is implemented and tested.

A note reminder carries no room id, so the call-reminder observer skips it when posting
acknowledgements — there is nothing to acknowledge to.

## Getting a note in

| Way in | How |
|---|---|
| **Share sheet** | share text from any app → "New note in Matrix Chat". An invisible activity saves it and closes; a shared subject becomes the title. Supernote had **no** `ACTION_SEND` filter at all — it could not receive anything. |
| **Launcher long-press** | **New note** and **Notes** shortcuts, alongside **Call reminders**. Supernote tried exactly these and its last commit says "tried adding shortcuts to homescreen with no success"; here they work, and each goes somewhere different. |
| **The app** | the notes screen's + button |

## Permissions this adds

**None.** Notes need nothing the app does not already have.

## Known limits, honestly

- **The notes room is created on first sync.** Before that, notes are saved on the phone and
  marked "not synced yet"; they go up as soon as there is a session. Nothing is lost, but a note
  written before the first successful login lives only on that phone until then.
- **The event id arrives with the echo, not the send.** A brand-new note is marked sent
  immediately and picks up its event id when the timeline catches up. In the gap it shows as
  unconfirmed, which is accurate.
- **Version history and restore** (round 2, second pass). The editor keeps a local trail of what
  a note said before — a version is captured when an editing session that *changed* the note
  ends (the same "when the user is finished" moment that names an untitled note; capturing per
  keystroke would make the history a keylogger), and when a remote edit overwrites different
  local content. A collapsed **History (n)** section in the editor lists them with their times;
  **Restore** puts an old version's content back through the normal edit path (so it syncs as an
  ordinary `m.replace`) and pushes the current content onto the trail first, so a restore can
  never lose anything — including restoring a restore. Colour, pin and reminder are deliberately
  not part of a version: restoring what a note *said* should not un-pin it or resurrect a
  dismissed alarm. The trail is capped at 20 and lives **only on the phone** (the wire format
  never carries it); the room's `m.replace` chain remains the authoritative, unlimited,
  cross-device history. `NotesStoreVersionsTest` pins every capture-or-not rule. This is
  Supernote's long-press-to-restore, grown up a little.
- **The live timeline is a window.** Very old notes are only read once the timeline has been
  paginated back far enough. In practice the store already has them from when they were written;
  this matters on a fresh install with a long history.
- **No images or attachments.** The room could hold them — it is a room — but Supernote's image
  support was half-finished and nothing was asked for. It is the natural next thing.

## Not built yet: location reminders

Supernote's one real feature with no counterpart here, deferred deliberately rather than
forgotten. Its version did not work in the background on any modern Android — it never declared
`ACCESS_BACKGROUND_LOCATION`, so its location listener stopped the moment the app was
backgrounded, which is exactly when "remind me when I get to the shop" needs to be running.

Doing it properly needs a decision, not a guess:

- `ACCESS_BACKGROUND_LOCATION`, which Android deliberately makes hard to get and which has a real
  privacy cost;
- **no Play Services Geofencing API** — this is the `fdroid` flavour, which has no Google Play
  dependency by design — so either a foreground service holding location updates (a persistent
  notification, a battery cost) or nothing.

The data model to use when it is built is Supernote's, which was a good one: a centre point, a
radius in metres, `arrive` / `leave`, days of the week, and a start and end hour. Its one trick
worth copying is the in-memory `isInRadius` latch that turns "am I inside now?" into enter/exit
edges by hand.

## Files

| File | What it is |
|---|---|
| `model/Note.kt` | the note, its colours, its checklist, and the title-derivation rule |
| `parser/NoteWireFormat.kt` | encode / decode, and the format's documentation |
| `store/NotesStore.kt` | the local store and its outbox |
| `session/DefaultNotesSessionObserver.kt` | the only thing that talks to the server |
| `reminders/NoteReminders.kt` | bridges a note's due time into the call-reminder alarm |
| `ui/NotesScreen.kt` | the wall: grid/list, search, pin, colours, tick, trash |
| `ui/NoteEditorScreen.kt` | writing a note |
| `ui/NotesActivity.kt` | hosts both, and the date/time picker |
| `ui/QuickCaptureActivity.kt` | the share sheet |
| `src/test/.../NoteWireFormatTest.kt` | the format's contract |


---

## Round 3 (2026-10-14) — a note that cannot be lost

`TEST-LOG-round3.md` has the detail. Four fixes, all in the "never lose what someone wrote" class:

- **Typing while a note is being sent.** Sending is a network round-trip, and `markSent` cleared
  `pending` unconditionally — so anything typed during it was marked as sent when it had not
  been, and the echo of the older message later overwrote it. You would watch your own sentence
  revert. `markSent` now takes the `updatedAtMillis` of the content that was actually sent and
  only clears `pending` if the note still says that.
- **Never make a second notes room.** `getJoinedRoom` returns null while the room list is still
  syncing, which is exactly the state a cold start with pending notes is in. The old code read
  that as "the user left" and created a fresh room, repointed the account data at it — which
  propagates to every device — and orphaned every existing note. It now waits and retries.
- **One create, not N.** A note's event id only arrives with the timeline echo, so for a few
  seconds `eventId == null` means two different things: "never sent" and "sent, waiting".
  `sentAtLeastOnce` tells them apart; without it every keystroke in that window queued another
  create and the room collected copies.
- **Versions that mean something.** The stored note keeps what the wire format drops — a trailing
  newline, and the empty checklist row the editor needs so there is always somewhere to type the
  next item. Comparing raw forms made the echo of the app's *own* message look like somebody
  else's edit: it pushed a version nobody created and shoved a real one off the end of the
  twenty-slot history. The comparison is now made on the normalised form.

Plus two that are about the feel of it:

- An editing session ends at `onPause` and was never restarted, so leaving the note to check
  something else and coming back meant the rest of that sitting was never captured as a version.
- Every keystroke wrote the whole notes file **and** cancelled two alarms **and** wrote the
  reminders file — even for a note with no reminder, where there was nothing to cancel. With
  version history the notes file is up to 21× larger per note, which turned a tolerable write
  into a real one. Both paths return early when nothing has changed.
