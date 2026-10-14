# In progress: push notifications set themselves up

Branch: `chagai/fix/push-notifications-set-themselves-up`
Started: 2026-10-14
Finished: 2026-10-14

## What was wrong

The phone showed "Current push provider: UnifiedPush, but no distributors have been configured"
while "Check UnifiedPush" happily found ntfy. The homeserver had **no pusher at all** for this
app, so there was nowhere to send a notification.

Two independent causes, both now fixed — the full write-up is `docs/chagai/PUSH.md`.

1. Upstream refuses to register a pusher until the session is verified. A password-only session
   that skipped verification therefore never registered, silently and forever.
2. The gateway Element X derives from the distributor's endpoint (`ntfy.sh`) is unreachable from
   this homeserver: the box only reaches ntfy.sh over IPv6 and the Synapse container has no IPv6.

## Changelog entry (paste into ./release.sh --notes)

Notifications now set themselves up: the app registers for push even before the session is
verified, and it uses a push gateway the server can actually reach. Settings -> Notifications
always shows which app is delivering them.
