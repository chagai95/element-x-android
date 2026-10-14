# What this fork adds

Element X Android, as "Matrix Chat", plus the features below. Everything here is additive —
no upstream feature is removed or disabled.

---

## 1. Pre-configured for chagai.website

The sign-in screen offers and pre-fills **chagai.website** instead of matrix.org. "Change
account provider" still works, matrix.org is still in the suggestion list, and the provider last
actually signed in to still wins over the default — so this only changes the very first run.

### Server compatibility — checked, and it passes

Element X does **not** speak the classic `/sync` API. It requires **Simplified Sliding Sync**
(MSC4186, the successor to MSC3575), implemented natively in Synapse 1.114+. A server without it
cannot be logged into at all, no matter how the app is configured, so this was checked first
(read-only, against the live server):

```
GET https://chagai.website/.well-known/matrix/client
  → m.homeserver.base_url = https://mx.chagai.website

GET https://mx.chagai.website/_matrix/client/versions
  → unstable_features."org.matrix.simplified_msc3575": true     ← the decisive line
  → versions up to v1.12

GET https://mx.chagai.website/_matrix/client/v3/login
  → flows: m.login.password, com.devture.shared_secret_auth, m.login.application_service
```

So: **sliding sync is supported — no Synapse upgrade is needed**, and the server authenticates
with a **password**, not OIDC/MAS. Element X supports both; typing `chagai.website` takes the
app through well-known discovery to `mx.chagai.website` and lands on the password screen.

The OAuth client metadata is still configured correctly (client/logo/ToS/policy URIs all under
`chagai.website`, redirect scheme `website.chagai.matrixchat`) so that the day the homeserver
moves to MAS/OIDC, nothing has to change in the app.

## 2. Installs beside the real Element X

Application id `website.chagai.matrixchat`. Both apps can be installed at once, with separate
data, separate notifications and separate sessions. Nothing of his existing setup is at risk
from trying a build.

## 3. Branding

Name, launcher icon, themed icon, splash and store metadata — see `BRANDING.md` for the file
list. The About screen links to the fork's source, as the AGPL requires.

## 4. Call reminders (alarm-style)

A reminder to **phone someone** that rings like a clock alarm instead of arriving as a quiet
notification: full screen, over the lock screen, on the alarm audio stream so it cuts through
silent and Do Not Disturb, with **Call now / Done / Snooze**.

Since round 2 it is **properly loud**: a foreground service takes the alarm stream to maximum
for the duration (and puts it back), plays one of five tones generated from scratch by
`tools/chagai/make_tones.py`, ramps from 40 % to full over ten seconds, and vibrates on a
repeating waveform — because a notification channel can only ever be as loud as the alarm volume
already is, which is what "it's not ringing loudly" meant.

Implemented in `features/callreminders/{api,impl}`; the full design, the event schema, the
loudness work and the server side live in `CALL-REMINDERS.md`.

## 5. Notes

Notes built into the app, where **each note is a Matrix message in a private "📝 Notes" room** —
so they sync across devices, survive a reinstall, are end-to-end encrypted, keep their own edit
history, can be read in any Matrix client, and are shared by inviting someone to the room.

A grid of coloured, pinnable notes with checklists, instant in-memory search, trash and restore,
a due time that rings through the full call-reminder alarm, share-sheet capture from any app, and
launcher shortcuts. Each note also keeps a **version history**: finishing an edit session that
changed the note keeps what it said before, a History section in the editor shows the trail, and
**Restore** brings an old version back without losing the current one. Versions live on the
phone; the room's `m.replace` chain stays the authoritative cross-device history.

Inspired feature-by-feature by Chagai's own 2019 note app, **Supernote** — `SUPERNOTE-INVENTORY.md`
records what it had and what came across; `NOTES.md` is the design. Implemented in
`features/notes/{api,impl}`.

---

## Deliberate non-goals

- **No Google Play flavour.** The `gplay` flavour's Firebase config is bound to Element's own
  package name and Firebase project and cannot work under a different application id. The
  `fdroid` flavour (UnifiedPush) is what ships. See `BUILD.md`.
- **No analytics, no crash reporting.** The PostHog and Sentry keys are left empty, so those
  providers are compiled in but inert, and the opt-in prompt has nothing to send anywhere.
- **No renaming of internal packages or on-disk stores.** It would orphan existing app data and
  make every upstream merge a conflict. See `BRANDING.md` §7.
