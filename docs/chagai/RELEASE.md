# The release loop

The whole point of this fork is that asking for a feature and having it on the phone is one
short loop:

```
ask for a feature  ->  ./feature.sh "the thing"  ->  implement it  ->  ./feature.sh --finish
                   ->  ./release.sh --notes "the thing"  ->  link arrives on WhatsApp
```

Nothing else needs to be remembered.

---

## `./release.sh` — one command, end to end

```bash
cd ~/android-build/element-x-android
./release.sh --notes "Call reminders ring like an alarm"
```

In order, it:

1. **Refuses to run** if the 1-minute load average is above 12 — the box runs live services.
2. **Bumps the version.** `versionChagai` in `plugins/src/main/kotlin/Versions.kt` goes up by
   one, giving e.g. `26.09.4-chagai.2` and version code `20260906`.
3. **Builds** `:app:assembleFdroidRelease`, signed with the personal keystore, under
   `nice`/`ionice` with the capped Gradle (see `BUILD.md`), with the memory watchdog running
   alongside.
4. **Verifies** with `apksigner verify --print-certs` and `aapt2 dump badging`, and *fails the
   release* unless the package id is `website.chagai.matrixchat`, the label is `Matrix Chat`
   and the version name matches what it just set. A silently mis-branded APK can't get out.
5. **Copies** the arm64 APK to `~/android-build/artifacts/matrix-chat-<version>-arm64.apk` and
   records its sha256.
6. **Writes the changelog** entry into `CHANGELOG-chagai.md`.
7. **Publishes** to `/matrix/static-files/public/matrix-chat/` → **https://chagai.website/matrix-chat/**,
   together with a generated `index.html` (download button, version, sha256, what's new,
   licence notice) and a machine-readable `latest.json`. It then curls the published APK and
   aborts if it is not a 200.
8. **Commits and tags** `v<version>` on `chagai/main`, and pushes the branch and tags to the
   local bare mirror at `~/android-build/element-x-android.git`. It never pushes anywhere public.
9. **Sends the install link** to Chagai's WhatsApp self-chat via `matrix-os/bin/send.sh`.

Flags: `--no-bump`, `--no-publish`, `--no-send`, `--universal` (also build/publish the all-ABI
APK), `--notes "…"`.

### Why `/matrix-chat/` and not a port

His phone refuses plain-HTTP downloads on odd ports. The path is served by the same static-file
mount as `/jitsi/` and `/boggle/`, over the site's real TLS certificate, which is the only
arrangement that reliably installs from the phone's browser.

## `./feature.sh` — start a feature

```bash
./feature.sh "call reminders"      # branch chagai/feat/call-reminders + a changelog stub
./feature.sh --fix "login crash"   # branch chagai/fix/login-crash
./feature.sh --finish              # merge the branch back into chagai/main
```

It refuses to run on a dirty tree, so a half-finished change can never be carried into a new
branch by accident.

## Signing

| | |
|---|---|
| Keystore | `~/android-build/keystore/chagai-release.keystore` (mode 0600) |
| Credentials | `~/android-build/keystore/chagai-release.creds` (mode 0600) |
| Alias | `chagai` |
| Certificate | SHA-256 `BF:AE:EC:D2:79:D6:20:B9:49:84:9E:C8:00:E7:73:67:40:5E:3A:CC:B3:81:CF:A2:CC:F2:DF:4A:CF:EE:71:B1` |
| Valid until | 2053-11-21 |

This is the **same key** that signs his Jitsi and legacy Element builds — reuse it forever.
Android identifies an app by `(package name, signing certificate)`: sign an update with a
different key and it will not install over the previous one, and there is no recovery short of
uninstalling and losing the local data.

The keystore and its password are **never** committed and never appear on the dashboard. The
Gradle signing config reads them from `-P` properties that `release.sh` sources from the 0600
creds file. PKCS12 gotcha, already handled: the key password must equal the store password, or
AGP's signer fails with "final block not properly padded".

## Installing on the phone

Open the link, tap the APK, allow installing from the browser once. It installs **next to** the
official Element X — different application id — so nothing existing is touched or overwritten.
Updates install over the previous Matrix Chat because the certificate and package id stay the
same.

## When something goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| `release.sh` exits "load average is N" | the box is busy | wait; the live services come first |
| build dies with no error, watchdog line in the log | memory floor hit | wait for the box to quieten, then re-run; the Gradle build cache makes the retry much faster |
| `apksigner verify` fails | wrong/missing `-P` properties | check the creds file is readable and `storepass` equals `keypass` |
| published URL is not 200 | file ownership | the publish step uses `sudo install -o chagai -g chagai -m 644`; check the directory exists and is 755 |
| "App not installed" on the phone | an older build signed with a different key | uninstall the old Matrix Chat first (this loses its local data) |
