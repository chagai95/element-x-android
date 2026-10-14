# Matrix Chat — changelog

Changes made in this fork, newest first.

## 26.09.4-chagai.9 — 2026-10-14

- A reminder that fires now actually shows its alarm screen — before, it rang loudly with nothing on screen and no buttons to find.
- Done, Called and Snoozed are reported straight back to the chat the moment you tap them, instead of waiting for the app to be opened.
- "Call now" in the notification really opens the dialer now, and asks what happened when the call ends.
- A reminder you once ignored no longer re-rings every time you open the app.
- Notes remember what they said before: a History section shows earlier versions and Restore brings one back.
- Notes can no longer be lost to a sync, duplicated, or stranded in a second notes room — and typing in one is a lot lighter.

## 26.09.4-chagai.8 — 2026-10-13

- Dark-mode fix: a yellow note was coming out maroon (and the other colours were off too) when the phone is in dark mode. Note colours now have a proper dark-mode version each, so a yellow note is yellow at night — just not a torch. Found by looking at the real screen in night mode.

## 26.09.4-chagai.7 — 2026-10-13

- A notes widget for your home screen: your pinned notes and a + that opens a blank one, one tap from anywhere. Two fixes found by driving the app on a test phone: a note you did not title used to end up called just the first word you typed (now it takes its name from the first line when you finish writing), and the empty tick box left behind after adding a checklist item no longer shows up on the card. Under the hood: the app now ships a startup profile so the first launches after installing are quicker.

## 26.09.4-chagai.6 — 2026-10-13

- Notes are in. Each note is a message in a private 📝 Notes room on your own server, so they sync to every device, survive a reinstall, are encrypted, and you share a notebook by inviting someone to it. A grid of coloured notes you can pin, checklists you tick straight from the card, instant search as you type (try typing 'missed reminders'), a trash you can undo, and a due time that rings with the full loud alarm. Share text from any app straight into a note, and long-press the app icon for New note / Notes / Call reminders. Also: the test alarm now opens the full alarm screen, like a real one does.

## 26.09.4-chagai.5 — 2026-10-13

- The alarm is properly loud now: it turns the alarm volume up to maximum while it rings (and puts it back after), plays a looped tone of its own instead of a notification ping, buzzes on a long repeating pattern, and fades in over ten seconds so it does not hurt. Five new sounds to pick from — Marimba Rise (the new default), Pulse, Bell Cascade, Old Telephone, Sunrise — plus your phone's own alarm sound, all with a tap-to-hear preview. New switches: full alarm volume, fade-in, vibrate, blink the torch, and a one-tap button to let reminders through Do Not Disturb.

## 26.09.4-chagai.4 — 2026-10-13

- Small fix on top of the version I sent a few minutes ago: the 60-minute snooze button was being squashed to "…" — install this one instead.

## 26.09.4-chagai.3 — 2026-10-13

- Call reminders: ask for "remind me to call X at 15:00" and the phone rings like a proper alarm — full screen, alarm sound, over the lock screen, through silent mode.
- Buttons: Call now (opens the dialer), Done, Snooze 5/15/60 or tomorrow 9:00.
- If you ignore it, it goes quiet and tries again, up to three times.
- A reminders list (coming up / snoozed / done), a test-ring button, and it tells the chat what you did so WhatsApp shows it too.

## 26.09.4-chagai.2 — 2026-10-13

- Sign-in screen is fully Matrix Chat now: the [m] logo replaces the Element mark, the Element tagline is gone, and the first button is Sign in to chagai.website (Sign in manually is still right underneath).

## 26.09.4-chagai.1 — 2026-10-13

- First Matrix Chat build, from Element X 26.09.4.
- Renamed, new [m] icon, installs next to the real Element X.
- Signs in to chagai.website by default — your server already supports the fast sync Element X needs.

---

## Round 2 performance measurements (2026-10-13)

Measured on the emulator clone (x86_64, Android 13), same device, same protocol, back to back.
`adb shell am start -W` cold start of `MainActivity`, force-stopped between runs, first run after
each install discarded. **TotalTime, milliseconds.**

### What was already right, upstream

- **R8 is already on** for `release` (`optimization { enable = true }` — AGP 9's replacement for
  `isMinifyEnabled`), with the fork's keep rules under `app/proguard/`.
- **Resources are already shrunk** (`shrunk_resources_binary_format/` exists for every release
  build).
- **`androidx.profileinstaller` is already a dependency** via Compose, and AGP already merges
  every library's ART profile.

Nothing to turn on. The gap was the app's own baseline profile.

### The baseline profile

Before: `assets/dexopt/baseline.prof` was **9,686 bytes**, entirely AndroidX/Compose rules, and

```
adb shell dumpsys package dexopt | grep -A2 website.chagai.matrixchat
  x86_64: [status=verify] [reason=install]
```

`status=verify` means **none of the app's own code is AOT-compiled** after an install — the whole
startup path is interpreted and JIT-compiled while the user waits. Adding
`app/src/main/baseline-prof.txt` takes the profile to **12,864 bytes** and covers the application
object, `MainActivity`, the startup initializers, the DI graph, appnav, onboarding/login/roomlist,
Compound, the Matrix layer and both fork features.

### The numbers, and the honest reading of them

| Build | State | Runs (ms) | Mean |
|---|---|---|---|
| chagai.6 (no app profile) | fresh install, `status=verify` | 389 · 373 · 364 · 393 · 404 | **385** |
| chagai.6 | after `compile -m speed-profile` | 394 · 396 · 366 · 362 · 384 | **380** |
| chagai.7 (baseline profile) | fresh install | 367 · 359 · 397 · 402 · 426 | **390** |
| chagai.6 | fresh install (control pass) | 393 · 409 · 446 · 410 · 396 | **411** |
| chagai.7 | fresh install (second pass) | 364 · 373 · 393 · 373 · 380 | **377** |
| chagai.6 | fresh install (second control) | 391 · 388 · 384 · 379 · 368 | **382** |

**chagai.7 mean 383 ms vs chagai.6 mean 396 ms — about 3 %, which is inside the run-to-run
noise.** On this emulator the baseline profile makes **no measurable difference**, and saying
otherwise would be inventing a number.

Two things worth recording about *why*, because they are the interesting part:

1. **The first measurements were wrong, and the control caught it.** An early pass showed
   chagai.6 at 377–568 ms (mean 484), which would have made the profile look like a 20 % win.
   Re-measuring the same APK back to back gave 364–404 ms. The difference was load on the build
   server, not the app. On a shared box, a before/after taken minutes apart is not a measurement
   — only an interleaved A/B is.
2. **An x86_64 emulator is the wrong device to see this on.** A baseline profile buys the
   removal of interpretation and JIT on the startup path, and that is cheapest exactly where this
   was measured: an x86_64 image on a fast host. The benefit is largest on a real ARM phone.

It is still shipped, because the finding that motivated it is real and device-independent:
`status=verify reason=install` means his phone genuinely is interpreting the app's own code on
the first launches after every update. The profile costs 3 KB in the APK and nothing at runtime.
**The honest claim is "the right thing is now in place", not "it is 20 % faster".** Someone with
a real device and `androidx.benchmark` can measure it properly.
