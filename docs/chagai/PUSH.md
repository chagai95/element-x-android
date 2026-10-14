# Push notifications in Matrix Chat

How a message on `chagai.website` ends up waking this app, what can break, and what to tap.

---

## The short version

Matrix Chat is built from the **fdroid flavour** of Element X, which has **no Firebase**
(Element's Firebase project is bound to *their* package id and cannot work under
`website.chagai.matrixchat`). So the app uses **UnifiedPush**: an open standard where a small
separate app on the phone — a **distributor** — holds the one permanent connection, and every
app that wants push borrows it.

```
  someone posts a message
        │
        ▼
  Synapse (mx.chagai.website)
        │   POST /_matrix/push/v1/notify   {event_id, room_id}      ← no message content
        ▼
  push gateway        (matrix.gateway.unifiedpush.org — see "the wrong machine" below)
        │
        ▼
  distributor app on the phone   (ntfy)
        │   Android broadcast
        ▼
  Matrix Chat  ──── fetches the event from the homeserver ────▶ shows the notification
```

Three things must all be true:

1. a **distributor app** is installed (ntfy, NextPush, Conversations, Sunup — any of them);
2. Matrix Chat has **chosen** that distributor and got an *endpoint* URL back from it;
3. Matrix Chat has **registered a pusher** on the homeserver pointing at the matching gateway.

If any one is missing, the phone stays silent and — in stock Element X — nothing tells you.

---

## What the app does automatically

On every app start, `DefaultPushService.ensurePusherIsRegistered()` runs:

- no push provider chosen yet → take the first one that has a distributor;
- provider chosen but no distributor → take the first available distributor;
- already set up → re-register, so a pusher deleted server-side comes back.

Registering with a distributor means: `UnifiedPush.saveDistributor()` + `UnifiedPush.register()`,
then waiting (up to 30 s) for the distributor to hand back an **endpoint** such as
`https://ntfy.sh/upAbCdEf?up=1`.

### How the gateway is derived from the endpoint

Upstream takes the endpoint's host and asks `https://<host>/_matrix/push/v1/notify` for a
discovery document. A UnifiedPush-aware server answers:

```json
{"unifiedpush": {"gateway": "matrix"}}
```

…and that URL becomes the pusher's gateway; otherwise it falls back to the public
`https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify`, which relays to the endpoint
for you.

**That derivation asks the wrong machine.** The gateway has to be reachable by the
**homeserver**, and the phone has no idea what the homeserver can reach. On `chagai.website`
the two disagree badly:

| From | → `ntfy.sh` | → `matrix.gateway.unifiedpush.org` |
|---|---|---|
| the phone | fine | fine |
| the host (IPv6) | fine | fine |
| the host (IPv4) | **unreachable** — TCP 80 and 443 both black-hole | fine |
| inside the `matrix-synapse` container | **impossible** — Docker here has no IPv6 at all | **200 OK** |

So a pusher pointing at `https://ntfy.sh/_matrix/push/v1/notify` can never be delivered by this
homeserver. Other accounts on the server that use ntfy.sh prove it: their pushers carry a
`failing_since` timestamp.

The fork therefore **forces** the gateway to the public UnifiedPush one
(`UnifiedPushConfig.FORCED_PUSH_GATEWAY_HTTP_URL`). It is reachable from Synapse, and it
relays to whatever endpoint the distributor handed out — ntfy.sh included. The payload is
identical either way.

### What actually travels over the gateway

The pusher is registered with `format = EVENT_ID_ONLY`. The homeserver sends only the
**event id, room id and unread counts** — never the message text, never the sender's name. The
app then fetches the event itself over its normal authenticated connection. So neither ntfy.sh
nor any other gateway ever sees message content.

---

## Three fork fixes (26.09.4-chagai.11)

Found while debugging "no distributors have been configured" on a phone that clearly had ntfy
installed, with **zero** pushers registered on the homeserver for this app.

1. **A session that is not verified no longer blocks push registration.**
   Upstream refuses to register a pusher until `sessionVerifiedStatus == Verified`. Signing in
   with a password and skipping verification therefore leaves the homeserver with *nowhere to
   send a notification*, permanently and silently. A pusher carries no secrets (event-id-only)
   and an unverified session can still display notifications, so the fork only waits while the
   verification status is still `Unknown`.
   *`DefaultPushService.ensurePusherIsRegistered`, `LoggedInPresenter`.*

2. **The push gateway is one the homeserver can actually reach.**
   See the table above. `UnifiedPushConfig.FORCED_PUSH_GATEWAY_HTTP_URL` pins it to the public
   UnifiedPush matrix gateway; set it back to `null` once a distributor server the homeserver
   can reach directly is in use.
   *`UnifiedPushConfig`, `DefaultUnifiedPushGatewayUrlResolver`.*

3. **The pusher is filed under this app's own id.**
   `PUSHER_APP_ID` defaulted to upstream's `im.vector.app.android`, so Matrix Chat and a real
   Element X on the same account landed in the same pusher slot. It is now
   `website.chagai.matrixchat`.
   *`BuildTimeConfig.PUSHER_APP_ID_RELEASE`.*

Plus: **Settings → Notifications → Push notification method** is now always visible when a
distributor exists (upstream hid it whenever there was exactly one and it was already selected),
so you can always see *which* app is carrying your notifications.

---

## On the phone — the setup, once

1. Install a UnifiedPush distributor. **ntfy** (F-Droid or Play) is the one in use here.
2. Open ntfy once and let it run. Leave its own server setting on the default `ntfy.sh` unless
   you have a self-hosted one (see below). It needs **no account and no subscription** to act as
   a distributor.
3. Battery: Android must not kill ntfy. Settings → Apps → ntfy → Battery → **Unrestricted**.
   (On Xiaomi/HyperOS also pin it in Recents and allow Autostart.)
4. Open Matrix Chat. Within a few seconds it registers by itself.
5. Check: **Settings → Notifications → Troubleshoot notifications** → *Run tests*. Everything
   should be green, including *Test push gateway* and *Test Push loop back*.

If step 4 did not happen (older build, or ntfy was asleep):
**Settings → Notifications → Push notification method → ntfy**.

---

## Reading the Troubleshoot screen

| Check | What it really means |
|---|---|
| **Check permissions** | Android's POST_NOTIFICATIONS grant. |
| **Display notification** | Local only — proves the channel works, nothing about push. |
| **Push provider support** | Which transports this *build* contains. `UnifiedPush` for the fdroid flavour. |
| **Current push provider** | A provider **and** a distributor are stored. "…but no distributors have been configured" = registration with the distributor never completed. |
| **Check UnifiedPush** | How many distributor apps are installed. Green here while the line above is red means: it found ntfy but never managed to register with it. |
| **Test push gateway** | Sends a test notify to the stored gateway URL. "No current push provider" = nothing stored yet. |
| **Test Push loop back** | Full round trip: homeserver → gateway → distributor → app, within 10 s. |

Server-side confirmation (read-only, on the box):

```sql
select app_id, app_display_name, left(pushkey, 50), to_timestamp(ts/1000), failing_since
from pushers where user_name = '@me:chagai.website';
```

A working Matrix Chat has a row with `app_id = website.chagai.matrixchat` and a `pushkey`
that is the ntfy endpoint URL. No row = the server has nowhere to push, and no amount of
phone-side fiddling will help.

---

## The clean long-term answer: a self-hosted ntfy

The matrix-docker-ansible-deploy playbook on the box ships an `ntfy` role, currently disabled.
Turning it on (`ntfy_enabled: true` + a DNS record for `ntfy.chagai.website` + a playbook run)
would give a self-hosted distributor server **and** the loud-alarm channel the reminder system
wants. Nothing in the app needs to change: point the ntfy app at the new server, and the
endpoint host changes, so `UnifiedPushGatewayResolver` derives the new gateway on its own —
provided the server has `base-url` set and answers the discovery request on
`/_matrix/push/v1/notify`.

Two things to get right on a self-hosted ntfy:

- `base-url` **must** be set to the public HTTPS URL, otherwise ntfy hands out endpoints with
  the wrong host and the gateway lookup goes nowhere.
- Synapse must be able to reach it. If it ends up on the same host, check that the gateway URL
  is not caught by Synapse's `ip_range_blacklist`.

Until then the public UnifiedPush gateway + ntfy.sh is fine: neither ever sees message content.

One caveat worth knowing: when a gateway answers `{"rejected": [...]}` — which ntfy does with
`507 cannot publish to UnifiedPush topic without previously active subscriber` if the ntfy app
has been off the network for a long time — Synapse **deletes** that pusher. The app re-creates
it the next time the app starts, so this heals itself — but it is why a phone that was off the
network for days can need the app force-stopped and reopened once.
