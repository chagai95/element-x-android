# Supernote — what it had, and what came across into Matrix Chat

**Supernote** (`chagai95/supernote.org`, Apache-2.0) is Chagai's 2019 Android note app. This is a
complete inventory of what it actually does, read from the source, with a verdict per feature:
**ported**, **adapted**, or **skipped (why)**.

Checked out at `~/android-build/supernote.org`. `git ls-remote` confirms GitHub has nothing newer
than the local checkout: one branch, `master`, at `5fe437b` (2019-12-11, a Jekyll theme commit).
The last commit that touched app code is `c3655c5`, 2019-11-20.

## The repo is five projects, and only two matter

| Project | What it is |
|---|---|
| **`FirebaseUI-FirestoreExample/`** | **The real app.** Firestore-backed, live sync, the working reminder/sharing/connectivity system. Everything worth porting is here. |
| **`CoMPuTeRNoTeS/`** | The earlier app. A much richer *data model* for note content — but most of it is scaffolding that throws `NotImplementedException` and is never instantiated. Design reference, not working code. |
| `MyApplication/` | The unmodified Android Studio "Google Maps Activity" template. A seed for "reminders on a map" that was never built. |
| `RunInBackround/` | An abandoned re-attempt at the background-service trick. The classes are empty placeholders that do not even extend `Service`. |
| `neverEndingAndroidService-master/` | Vendored third-party tutorial code (see Licensing). |

The newer app **abandoned** the older one's rich content model (`Paragraph` / `Picture` / `Audio`
/ `Video` / `Handwriting` / `Document` + seven `Hyperlink*` types) in favour of one plain-text
field per note. Worth knowing before being impressed by the class list: the taxonomy is real, the
behaviour mostly is not.

---

## ⚠️ "Copy all creatures" — the verdict

**There are no creatures.** No mascots, pets, monsters, avatars, characters, and no gamification
of any kind — no XP, levels, badges, streaks, achievements, coins or rewards. This was checked
exhaustively, not glanced at:

- `grep -ri` over the whole tree for `creature|monster|pet|mascot|avatar|character|dragon|animal|
  egg|hatch|level|xp|points|badge|achievement|streak|coin|reward|gamif|sprite`. Every hit was a
  false positive (`NETWORK_TYPE_…` constants containing "level", `android.graphics.Point`, build
  boilerplate about "sub-projects"). **Zero genuine hits.**
- Every file in every `res/drawable*`, `res/mipmap*`, `res/anim*` was listed. There is no
  `assets/` directory anywhere in the repo. The complete asset inventory is launcher icons plus,
  in the main app, `ic_search`, `ic_new_note`, `ic_new_reminder` and eight vector UI glyphs. No
  file name suggests an animal, a character or a mascot.
- `strings.xml` in both apps is utilitarian UI copy. No character names, no narrative text, no
  playful "meet your buddy" line anywhere.
- The full `git log --stat` (29 commits, 2019-05-28 → 2019-12-11) has no commit mentioning
  points, levels, achievements or characters. There was never a gamification layer to remove.
- `friends` in this codebase means **other human users you share notes with** (`CloudUser.friends`,
  `FriendsActivity`), not a pet or companion.

**Read of the request:** "creatures" is almost certainly **"features"** — the words are
phonetically close, this was dictated, and "copy all the features and build them nicely into the
app with Matrix" is exactly what the rest of the request asks for. So this document treats it as
features, and that is what was built.

If something creature-*like* is genuinely being remembered, the two most personality-laden,
non-obvious mechanics the app actually has are the **traffic light** (the whole app's chrome
changes colour with your connection — §9) and the **hyperlink zoo** (seven link-type classes,
even though only the taxonomy survives — §1.4). Both are in the table below. The third candidate
is the **orange text** trick (§1.6), which is genuinely charming and *has* been ported.

---

## The inventory

Status key: **Ported** — the behaviour now exists in Matrix Chat. **Adapted** — the idea came
across but the mechanism is different, because Matrix/modern Android give a better one.
**Skipped** — with the reason.

### Notes

| # | Supernote feature | How it worked there | Status in Matrix Chat |
|---|---|---|---|
| 1 | **A note** | `Note` Firestore POJO: `title`, `description` (the entire body, one plain-text blob), `created`, `creator`, `history[]`, `titleHistory[]`, `shared[]`, `keepOffline`, `loadToCache`, `trash` | **Adapted.** A note is a real Matrix message in a dedicated notes room, so it syncs through the homeserver instead of Firestore and is readable in any client. Title + body + checklist + colour + pin. See `NOTES.md`. |
| 2 | **Title auto-derived from the body** | Blank title on `onStop()` → first 20–25 characters of `description`, trimmed back to the last space so it never cuts mid-word | **Ported**, including the trim-to-last-space detail. |
| 3 | **"Orange text" while loading** | `setPreLoading()` paints title and body `#DF3B0D` and fills them synchronously from the in-memory cache *before* any fetch, so the screen is never blank; flips to black when the authoritative value arrives. Not a stored state — the colour is a side effect of which method ran last. | **Ported, as a real state.** A note that has not been confirmed by the server yet is drawn in the accent colour with a small "not synced yet" mark. Same idea — show the cached value instantly rather than a spinner — but it is an explicit flag on the note rather than an accident of control flow. |
| 4 | **Note history / versions** | `history[]` and `titleHistory[]` grown by `arrayUnion` on every `afterTextChanged`. Long-press an entry in `HistoryActivity` to restore. `TitleHistoryActivity` is fully built but its menu item is commented out, so it is unreachable. | **Adapted — Matrix already does this.** Editing a note sends an `m.replace`, so every version is in the room's event graph and every client can show the edit history. No parallel array to keep. The restore-an-old-version UI is a known gap, noted in `NOTES.md`. |
| 5 | **Per-device offline history** | Edits made offline go to a per-device subcollection `notes/{id}/{deviceUUID+userUid}`, merged on reconnect with a "Server data vs Local data" dialog, then the subcollection is drained | **Adapted.** The Rust SDK's send queue does this: an edit made offline is queued and sent when the connection returns, and the room's event ordering resolves it. The conflict dialog has no equivalent and is not missed — Matrix edits are last-writer-wins by event order. |
| 6 | **Rich text editing** | `net.dankito.richtexteditor` demo source sits orphaned in `CoMPuTeRNoTeS/app/src/main/kotlin/` — copied in, never added as a Gradle dependency, never referenced. An abandoned intent. | **Skipped for now.** Matrix messages already carry an HTML body and Element X renders it, so the plumbing exists — but a formatting toolbar is a big piece of UI for a note app and nothing in the request asks for it. Markdown in a note body is rendered. |
| 7 | **Note content types**: `Paragraph`, `Picture`, `Audio`, `Video`, `Handwriting`, `Document` | Only `Paragraph` is live. `Picture` has two paths, one of which (inline, local `content://`) would crash the save. `Audio`, `Video`, `Handwriting`, `Document` are zero-field stubs — there is no canvas, no `MediaRecorder`, no attachment code anywhere in the repo. | **Skipped — there is nothing to port.** The classes are empty. Images could be done properly in a Matrix room (it is a room; `m.image` just works), which is the natural follow-up, but this is not "copying" anything. |
| 8 | **The hyperlink zoo**: `HyperlinkWeb`, `HyperlinkContact`, `HyperlinkWhatsappNumber`, `HyperlinkTelegramNumber`, `HyperlinkMessenger`, `HyperlinkNote` | **None of them do anything.** No URL field on `HyperlinkWeb`, no number field on the WhatsApp/Telegram classes despite the names, no `ACTION_VIEW` anywhere. `HyperlinkNote` is the only one with plumbing (`LinkMovementMethod` is set) but nothing ever populates it. | **Adapted.** The *taxonomy* was the idea, and modern Android does it for free: Element X already linkifies URLs, phone numbers, emails and Matrix ids in a message body, so a note body gets tappable web links, `tel:` numbers and `wa.me` links with no code. Note-to-note linking → see #9. |
| 9 | **"Internal link" to another note** | A menu item that copies the literal string `"#<note title>"` to the clipboard. No id resolution, no tap target. | **Adapted.** A note's body may contain `[[another note title]]`; the notes screen resolves it and makes it tappable. The intent was a wiki-link; this is one. |
| 10 | **Tags** | `CoMPuTeRNoTeS` only: `Tag` is a bare `String`. `deleteTag`/`editTag` are stubs. The global pool is hard-coded to `"telegram"`, `"cool stuff"`, `"earn money"`. Also used as a sentinel: a note whose first tag is `"trash"` is "in the trash". | **Adapted into colours + pin + search.** A free-text tag system whose only working use was a trash sentinel is not worth reproducing; what it was *for* — finding a note again — is served by search, colours and pinning. |
| 11 | **Checklists** | **Did not exist.** Every `CheckBox` in the repo is a settings toggle. No `done`/`checked` field on any note model. | **New, not ported.** Asked for, and the obvious thing a note app should have. Tapping an item edits the note's event, so the tick syncs. |
| 12 | **Note colours** | **Did not exist.** `note_item.xml` hard-codes one pale yellow (`#FFFFE8`) for every card. | **New** (six Compound-palette colours), inspired by that one yellow card. |
| 13 | **Pinning** | **Did not exist** (zero hits for `pin`/`pinned`). | **New.** |
| 14 | **Grid / list modes** | **Did not exist** — every list is a `LinearLayoutManager`. | **New.** A grid is what makes colours and pinning worth having. |
| 15 | **Search** | Live in-memory filter over the cached notes on every keystroke (not a new query), with title-contains / body-contains toggles and a third "shared users" toggle that is declared but never applied. Plus a magic query, `"missed reminders"`, that lists overdue undone reminders. A sort dialog (title / date created) that applies **only to search results**. | **Ported**, including the live in-memory filtering (the right call — it is instant) and the `missed reminders` magic query. The never-applied third toggle is not reproduced. |
| 16 | **Trash, untrash, permanent delete** | A `trash` boolean on the note, cascading to its reminders. Three list modes (`all` / `trash` / `shared`) swapping the Firestore query behind one adapter, chosen by menu items that call `recreate()`. | **Ported.** Trash is a note flag; the screen filters. |
| 17 | **Swipe gestures** | Swipe **right** → trash, with an **undo Snackbar**. Swipe **left** → trash with undo, *or* permanent delete with no undo if the `swipe left to permanently delete` setting is on. In trash mode either direction restores. | **Ported**, with the setting and the undo. |
| 18 | **"Keep offline" per note** | `Note.keepOffline` / `loadToCache` attach a live snapshot listener to that note even when it is closed, and warm the Firestore disk cache | **Skipped — already true of everything.** Every note is in the local store; the screen reads the store, never the network. There is nothing to opt into. |

### Reminders

| # | Supernote feature | How it worked there | Status in Matrix Chat |
|---|---|---|---|
| 19 | **Time reminder** | `Timestamp` on a Firestore doc in `notes/{id}/Reminders`; `AlarmManager.set(RTC_WAKEUP, …)` — **not** `setExact`, so Doze can defer it. In `CoMPuTeRNoTeS` the `PendingIntent` request code is hard-coded to `0`, so every new alarm silently replaced the previous one. | **Ported and made reliable.** `setAlarmClock`, which is exact, survives Doze and publishes the next alarm to the status bar; a distinct `PendingIntent` per (reminder, action) so one can be cancelled without touching another. Rebuilt on boot, app update, and clock/time-zone change. |
| 20 | **A reminder on a note** | A reminder is a subcollection document under its note | **Ported.** A note can have a due time; it rings with the full loud alarm, and the alarm screen opens the note. |
| 21 | **`amountOfRepeats`** | An `int`, default 1, decremented by a "next time" notification button. Nothing ever re-schedules from it — it is a counter, not a recurrence rule. **There are no recurring time reminders in either app.** | **Adapted.** What it was reaching for is escalation ("ask me again"), which already exists properly: go quiet, re-ring, up to a configurable number of tries. |
| 22 | **Swipe-to-dismiss re-arms the alarm** | `setDeleteIntent` schedules a fresh alarm 20 s out (30 s in the older app, `swipedSnoozeTimeInSeconds`). An auto-snooze with no snooze button. | **Adapted.** Dismissing is not an outcome here either: volume keys silence the ring and keep the screen, and an unanswered alarm re-rings on the escalation schedule. Same instinct, explicit controls. |
| 23 | **Snooze** | Opens a date/time picker to pick a brand-new time. No fixed +N minutes. | **Ported and extended**: 5 / 15 / 60 presets (configurable) plus "tomorrow at 9:00" in one tap. |
| 24 | **WhatsApp reminder** | `WhatsappTimeReminder`, and `whatsappNumber`/`whatsappMessage` on *any* reminder, adding a "send whatsapp" notification action. The mechanism is a plain public click-to-chat link: `ACTION_VIEW` on `https://api.whatsapp.com/send?phone=…&text=…` — no Business API, no `setPackage`. | **Adapted.** Reminders already arrive *through* WhatsApp here — the server posts them into the bridged self-chat, so the WhatsApp side is the transport rather than a button. The alarm's "Call now" is the same idea in the same shape (`ACTION_DIAL`, no permission). The `wa.me` trick is kept on file in case a "message them" button is ever wanted. |
| 25 | **Location reminder** | `GeoPoint`, `radius` in metres, `arrive`, `leave`, `startHourOfDay`, `endHourOfDay`, `daysOfWeek` (lowercase day names in a list). **No Geofencing API, no `addProximityAlert`, no fused provider** — a plain `LocationManager` listener on the literal `"gps"` provider every 10 s / 45 m, recomputing `Location.distanceTo()` by hand, with an in-memory `isInRadius` latch to get enter/exit edges. No `ACCESS_BACKGROUND_LOCATION` was ever declared, so on Android 10+ it stopped the moment the app was backgrounded. | **Deliberately deferred — see "Not done, and why" below.** |
| 26 | **User reminder** — "tell me when this person is nearby" | Extends the location reminder; a Firestore listener on `users/{uid}` re-runs the same radius/day/hour logic against *another person's* live `geoPoint` | **Skipped.** It needs every participant to continuously publish their location to a shared store. That is a different product with different consent questions, not a note-app feature. Genuinely the most inventive thing in the app, and worth saying so. |
| 27 | **`MyDayOfWeek`** | An enum whose ordinal 0 is a dummy, so `Calendar.DAY_OF_WEEK` (1 = Sunday) indexes straight into it to produce a lowercase day name matching `daysOfWeek[]` | Noted as a neat trick. Not needed — `java.time.DayOfWeek` exists now. |

### Sharing and users

| # | Supernote feature | How it worked there | Status in Matrix Chat |
|---|---|---|---|
| 28 | **Share a note with another user** | A `shared[]` array of friend UIDs on the note document; "shared with me" is `whereArrayContains("shared", uid)` | **Adapted — this is what Matrix is.** Notes live in a room. Sharing a note means inviting someone to a room, which brings end-to-end encryption, permissions, history visibility and multi-device for free. The `shared[]` array has no counterpart because it has no job. |
| 29 | **Friends list** | Autocomplete over all `users` docs, `arrayUnion` onto `friends`, swipe either way to remove | **Skipped — the app already has it.** Element X's room and people lists are this, done properly. |
| 30 | **Username availability check** | Live-validated as typed: grey while checking, green and auto-saved if free, red if taken | **Skipped.** Matrix ids are allocated at registration on the homeserver; there is no rename to validate. |

### Connectivity, offline and the traffic light

| # | Supernote feature | How it worked there | Status in Matrix Chat |
|---|---|---|---|
| 31 | **The traffic light** | Not an icon — the **whole app's chrome changes colour**. Each activity overrides `getTheme()` to pick a style from a static state field, and `recreate()` is called when it changes. `enum TrafficLight { OFFLINE, ONLINE, INTERN_OFFLINE, MAYBE_CONNECTED, UNKNOWN }` → green `#36832B`, orange `#E77016` (slow mobile), red `#EB0000`, dark red `#500707` (network switched off on purpose). | **Adapted, and narrowed.** Repainting the entire app is a 2019 answer; Element X has a proper sync-state indicator in its room list. The *distinction* worth keeping — "offline" vs "deliberately offline" vs "connected but slow" — shows on the notes screen as a small sync chip, so an unsynced note is never a mystery. |
| 32 | **Slow-connection classification** | `NetworkUtil.fastConnection(subType)`, a switch over `TelephonyManager.NETWORK_TYPE_*`: EDGE/GPRS/1xRTT/CDMA/IDEN = slow, LTE/HSDPA/UMTS/… = fast | **Skipped.** `NetworkCapabilities` answers this directly now, and the Rust SDK handles its own retries. |
| 33 | **Turn the internet off on purpose** | Settings toggles: disable Firestore network when the connection is slow, or on app start (that one stored in a **global** Firestore document, shared across all users and devices). Plus dialogs offering to go offline for N minutes/hours/days, on a `Timer`. | **Skipped.** Element X has offline support that does not need to be managed, and a global cross-user flag for a per-device preference is a bug, not a feature. |
| 34 | **Real-internet probe** | `Online.java` pings google.com with a 1.5 s timeout — **and the result is commented out of the condition it was written for**, in both apps ("skip this for now because it does not work") | **Skipped.** Abandoned by its own author. |
| 35 | **`InternetThread`** | Times a download of a placeholder ISO URL via `TrafficStats` to measure bandwidth. The result, `MyApp.totalTime`, is never read anywhere. | **Skipped.** Dead experiment. |
| 36 | **`TelephonyManager`** | Only ever used for the speed classification above. Its permission check is against `ACCESS_FINE_LOCATION`, not `READ_PHONE_STATE`, because both requests happened to be bundled in one call. | **Skipped.** A red herring if you expect it to be a location fallback — it is not. |

### Reliability and background

| # | Supernote feature | How it worked there | Status in Matrix Chat |
|---|---|---|---|
| 37 | **The "never-ending service"** | Despite the name, no sensors: a `START_STICKY` service whose `onDestroy()` broadcasts to its own receiver, which immediately restarts it; `onTaskRemoved()` does the same; a `Timer` relaunched `MainActivity` every hour. **Disabled in commit `961716e` — "commented the SensorService out because it was cousing delays"** — and still disabled. | **Adapted into the thing it was trying to achieve.** Fighting the OS to stay alive is the wrong tool and it cost the app its responsiveness. What a reminder app actually needs is for the *alarm* to be unkillable, not the process: `setAlarmClock` (exact, Doze-proof, shown in the status bar), alarms rebuilt from the local store on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` / `TIME_SET` / `TIMEZONE_CHANGED`, the store written atomically so a kill mid-write cannot corrupt it, and the only long-lived service being the one that rings — which lives for the length of the ring and then stops. Same goal, no battery cost. |
| 38 | **Start on boot** | `StartMyServiceAtBootReceiver` starts `MyService`, whose one real side effect is commented out | **Ported properly** — see above. |
| 39 | **Crash recovery screen** | A global uncaught-exception handler schedules an alarm 15 s out to relaunch a crash-report activity, then `System.exit(2)` | **Skipped.** Element X has its own rageshake/crash reporting. |

### Notifications

| # | Supernote feature | How it worked there | Status in Matrix Chat |
|---|---|---|---|
| 40 | **The reminder notification** | One hard-coded channel `"CHANNEL_ID"`, `IMPORTANCE_HIGH`, `setDefaults(DEFAULT_ALL)`, default notification sound, `setAutoCancel(true)`. **No full-screen intent, no `FLAG_INSISTENT`, no custom vibration, no alarm stream.** | **Far exceeded.** A reminder here rings on the alarm stream at full volume with a looped generated tone, a repeating vibration waveform, a full-screen alarm over the lock screen, and escalation. See `CALL-REMINDERS.md`. |
| 41 | **Notification actions** | `trash` on every reminder; `done` + `snooze` on time reminders; `done` + `next time` on location reminders; `send whatsapp` when online and a number is set | **Ported in spirit**: Call now / Done / Snooze, plus Done / Snooze on a note reminder. |
| 42 | **One notification id per reminder** | Lazily assigned and cached, so a re-fire updates rather than stacks | **Ported** (derived from the reminder id, so it is stable without needing to be stored). |
| 43 | `BigTextIntentService` / `GlobalNotificationBuilder` | Vendored from Google's Wear notifications sample. **100 % commented out.** | **Skipped.** Dead reference code. |

### Entry points

| # | Supernote feature | How it worked there | Status in Matrix Chat |
|---|---|---|---|
| 44 | **Launcher shortcuts** | `shortcuts.xml` with `newNote`, `newReminder` and `search` — but `newNote` and `newReminder` target the identical activity with no distinguishing extras, and the last code commit says **"tried adding shortcuts to homescreen with no success"**. The feature was attempted and, by its author's account, never worked. | **Ported, and this time they work**: long-pressing the launcher icon gives **New note**, **Notes** and **Call reminders**, each going somewhere different. |
| 45 | **Share-sheet intake** | **Did not exist** — no `ACTION_SEND` filter in any of the five manifests. The app could not receive shared content. | **New.** Sharing text from any app offers "New note in Matrix Chat"; it captures and syncs without the app being opened. |
| 46 | **Home-screen widget** | **Did not exist** — no `AppWidgetProvider` anywhere. | **New.** A notes widget: pinned notes and a new-note button. |

### Things that were never there

For the record, so the gaps are not mistaken for missed ports: **no dark mode** (no `-night`
resources anywhere), **no custom fonts** (no `.ttf`/`.otf`, no `res/font`), **no animations** (no
`res/anim`, no `ObjectAnimator`/`overridePendingTransition` anywhere), **no voice input** (no
`SpeechRecognizer`/`RecognizerIntent`), **no OCR**, **no drawing canvas** (no `onDraw` subclass
anywhere — `Handwriting.java` is an empty stub), **no export or backup**, **no checklists, note
colours, pinning or grid view**. Matrix Chat has dark mode and animations from Element X and
Compound, and gains the checklists, colours, pinning and grid as new work.

---

## Not done, and why

**Location reminders (#25) are deferred, not forgotten.** They are the one real Supernote feature
with no counterpart yet, and the reason is honest rather than technical laziness:

- Supernote's version **did not work in the background** on any modern Android. It never declared
  `ACCESS_BACKGROUND_LOCATION`, so its location listener stopped the moment the app left the
  foreground — which is exactly when a "remind me when I get to the shop" reminder needs to be
  running. Porting it as written would ship something that looks like a feature and is not one.
- Doing it properly means `ACCESS_BACKGROUND_LOCATION` — a permission Android deliberately makes
  hard to get, with its own settings-screen flow and a visible privacy cost — plus either Play
  Services' Geofencing API (**not available**: this is the `fdroid` flavour, which has no Google
  Play dependencies at all, by design) or a foreground service holding location updates, which is
  a persistent notification and a real battery cost.
- So it needs a deliberate decision about that trade, not a guess. The design is written down in
  `NOTES.md` and the data model is the one Supernote used (radius, arrive/leave, day-of-week,
  start/end hour), which is a good model and worth keeping when it is built.

**User reminders (#26)** are skipped outright: continuously publishing everyone's location to a
shared store is a different product.

---

## Licensing

| Thing | Licence | Consequence |
|---|---|---|
| `chagai95/supernote.org` | **Apache-2.0** (`LICENSE`, full standard text) | Apache-2.0 is one-way compatible **into** AGPL-3.0, so ideas, structure or code taken from Supernote may live in this fork. Nothing was copied verbatim — everything here is a reimplementation in Kotlin/Compose against Matrix — so the practical obligation is attribution, which this document and the commit messages provide. |
| `neverEndingAndroidService-master/` | **Unclear.** Vendored unmodified from Fabio Ciravegna's (`github.com/fabcira`) `neverEndingAndroidService` tutorial — package `oak.shef.ac.uk.…`, "Created by fabio on 30/01/2016". **No LICENSE or NOTICE file is bundled in the vendored copy.** Sibling repos by the same author are reported as MIT, but that is not a statement about this one. | **Nothing was taken from it**, which settles the question. Its *idea* (keep a service alive by restarting it from its own `onDestroy`) was deliberately rejected on technical grounds (#37), so no code or structure crossed over. |
| `net.dankito.richtexteditor` demo source in `CoMPuTeRNoTeS/app/src/main/kotlin/` | **Apache-2.0** (dankito/RichTextEditor) | Orphaned — never a Gradle dependency, never referenced, does not compile. Nothing taken. |
| Firebase, FirebaseUI, Glide, `com.github.clans:fab`, AndroidX | Standard upstream, pulled by Gradle | Not vendored, not relevant to this fork. |

---

## The unfinished edges, for the record

Found in the source and worth knowing, because they are the places Supernote itself knew it was
incomplete:

- `MyDatePickerFragment.java:161` — **`// TODO add reminder to alarm manager!`** There is a path
  where a time the user picked is never actually scheduled. The most consequential unfinished
  thing in the app, in a reminder app.
- `NoteActivity.java:588` — "save and upload note in two different methods and then call save note
  in BOTH on back pressed methods". Two near-duplicate save paths still exist.
- `NoteActivity.java:143` — "for some reason gets skipped". Never resolved.
- `NoteMain.java:34` — "set an option in settings to allow or disable duplicate notes". Never built.
- `TitleHistoryActivity` is **fully implemented and unreachable** — its menu item and its
  `onOptionsItemSelected` case are both commented out.
- `arrayUnion` for history deduplicates *any* repeated value, not just consecutive ones, so
  retyping an old title loses the intermediate version.
- `LocationReminder`'s convenience constructor seeds `daysOfWeek` with all seven day names seven
  times each — 49 entries. Harmless for `.contains()`, but it is a bug.

## Where this came from

The whole repo was read for this: every `AndroidManifest.xml`, every Java source in the two real
apps, `res/layout`, `res/values/strings.xml`, `res/xml`, every drawable directory, and the full
`git log --stat`. Regenerating this means reading it again — there is no tool that produces it.
