# Building Matrix Chat on the server

This fork is built headless on Chagai's server, which also runs his **live** production stack:
Matrix/Synapse, a large Postgres, the WhatsApp/Telegram/Teams bridges, the dashboard, Ollama,
Paperclip and the transcription pipelines. A build must never starve any of that. Every rule
below exists for that reason.

---

## The toolchain

| Thing | Where | Note |
|---|---|---|
| JDK | `~/android-build/jdk-21` (Temurin 21.0.11) | Element X requires Java 21; the system JDK is 17, and it is **not** upgraded |
| Android SDK | `~/android-build/sdk` | shared with the other Android projects on the box |
| Platform | `platforms;android-37.0` | `Versions.COMPILE_SDK = 37` |
| Build tools | `build-tools;37.0.0` | must match `COMPILE_SDK`; `apksigner` and `aapt2` come from here |
| Gradle | 9.7.1 via the wrapper | downloaded into the capped Gradle home |
| Gradle home | `~/android-build/gradle-home-elementx` | **separate** from the legacy Element and Jitsi ones, so their caches never collide |
| Rust SDK | Maven artifact `org.matrix.rustcomponents:sdk-android` | no Rust toolchain, no NDK, nothing to compile natively |

`env.sh` in the repository root exports all of it. Source it, never hand-set these.

```bash
cd ~/android-build/element-x-android
source ./env.sh
```

## The resource caps (not optional)

`~/android-build/gradle-home-elementx/gradle.properties` deliberately overrides the project's
own `gradle.properties`, which asks for `-Xmx8g` and parallel execution — far too much here.

```properties
org.gradle.jvmargs=-Xmx5g -XX:MaxMetaspaceSize=768m -XX:+UseG1GC \
                   -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 -XX:GCTimeRatio=4
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.workers.max=2
org.gradle.caching=true
org.gradle.configuration-cache=false
kotlin.daemon.jvmargs=-Xmx1800m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC -XX:MaxHeapFreeRatio=25
```

The `MinHeapFreeRatio`/`MaxHeapFreeRatio` pair matters as much as `-Xmx`: without them G1 holds
the full heap as resident memory for the whole build. With them the JVM hands pages back
between tasks, which is the difference between the box having ~16 GB free and ~2 GB free
during the R8 step.

Every invocation also runs under `nice -n19 ionice -c3`, and `release.sh` refuses to start at
all when the 1-minute load average is above 12.

**`tools/chagai/build-watchdog.sh`** runs alongside the build and kills it if `MemAvailable`
drops below ~2.2 GB *and* free swap below 2 GB. A dead build is recoverable; an OOM-killed
Synapse is not.

Lessons learned the hard way, worth keeping:

- The first attempt used `-Xmx6g` without the heap-free ratios and drove `MemAvailable` to
  2.4 GB with 3 GB of swap left. It was killed and retuned before it could reach R8.
- Postgres' ~15 GB of shared buffers show up in `shared`, not in reclaimable cache, so
  `free -g`'s **available** column is the only number worth watching. `used` lies.

## Flavours and build types

`gplay` and `fdroid` are the two store flavours. **This fork always builds `fdroid`**, because:

- the `gplay` flavour pulls in Firebase, whose checked-in config is bound to the package name
  `io.element.android.x` and Element's Firebase project — it cannot work under a different
  application id;
- `fdroid` uses UnifiedPush instead, which is self-hostable and needs no Google account;
- it also drops the Play-services location backend, which this build has no use for.

Release builds are signed with the personal keystore (see `RELEASE.md`), via the `chagai`
signing config added in `app/build.gradle.kts`. Without those `-P` properties the release build
type falls back to upstream's behaviour (the debug key), so a plain `assembleRelease` still
works for anyone cloning the fork.

## Commands

```bash
source ./env.sh

# the one you actually want
./release.sh --notes "What changed"

# just a build, no publish, no message
./release.sh --no-publish --no-send

# by hand (what release.sh runs underneath)
nice -n19 ionice -c3 ./gradlew --no-daemon --max-workers=2 --console=plain \
  :app:assembleFdroidRelease \
  -Pchagai.storeFile=$HOME/android-build/keystore/chagai-release.keystore \
  -Pchagai.storePassword=… -Pchagai.keyAlias=chagai -Pchagai.keyPassword=…

# unit tests for one module
nice -n19 ionice -c3 ./gradlew --no-daemon --max-workers=2 :features:callreminders:impl:testFdroidDebugUnitTest
```

Outputs land in `app/build/outputs/apk/fdroid/release/`:

- `app-fdroid-arm64-v8a-release.apk` — the one that ships (his phone is arm64)
- `app-fdroid-universal-release.apk` — all four ABIs, roughly twice the size
- plus `armeabi-v7a`, `x86`, `x86_64` splits

## Performance — what was already right, and what was missing

Measured on the emulator clone (x86_64, Android 13) in round 2. The same device before and after,
same protocol, so the comparison is the only thing being claimed — these are not phone numbers.

### Already right, upstream

- **R8 is on** for `release` and `nightly` (`optimization { enable = true }` in
  `app/build.gradle.kts` — the AGP 9 replacement for `isMinifyEnabled`), with the fork's keep
  rules under `app/proguard/`.
- **Resources are already shrunk** — `app/build/intermediates/shrunk_resources_binary_format/`
  exists for every release build.
- **`androidx.profileinstaller` is already a dependency** (transitively, via Compose), and AGP
  already merges every library's ART profile (`mergeFdroidReleaseArtProfile`).

So there was nothing to turn on. The gap was elsewhere.

### What was missing: the app's own baseline profile

```
$ adb shell dumpsys package dexopt | grep -A2 website.chagai.matrixchat
  x86_64: [status=verify] [reason=install]
```

`status=verify` means **none of the app's own code is compiled ahead of time** — every class on
the startup path is interpreted and JIT-compiled while the user waits. The APK's
`assets/dexopt/baseline.prof` was 9,686 bytes, all of it AndroidX and Compose rules merged in by
AGP; nothing covering `io.element.android.*`.

`app/src/main/baseline-prof.txt` now covers the application object, `MainActivity`, the
androidx.startup initializers, the generated DI graph, appnav, onboarding/login/roomlist,
Compound and the design system, the Matrix layer, and both fork features (call reminders are on
the cold path by definition — an alarm at 07:00 is the first thing the process does that day).

It is **hand-written rather than recorded**. Recording one properly needs the
`androidx.baselineprofile` Gradle plugin and a macrobenchmark module, which means new
dependencies resolved at build time — and these builds are capped and run `--offline`. A
hand-written profile is the documented alternative: less precise (it cannot know which *branches*
are hot), but it covers the right code, and being approximate only costs a few methods compiled
that need not have been.

### Measuring it yourself

```bash
E="sudo docker exec android-clone-clone1 adb"
# Put the package back to exactly its post-install compilation state.
$E shell cmd package compile --reset website.chagai.matrixchat
$E shell dumpsys package dexopt | grep -A2 website.chagai.matrixchat   # status=?
# Then, per run: force-stop, wait, start with -W, read TotalTime.
$E shell am force-stop website.chagai.matrixchat
$E shell am start -W -n website.chagai.matrixchat/io.element.android.x.MainActivity
```

Discard the first run after an install — that is the one where the filesystem cache is cold for
reasons that have nothing to do with the app.

Jank on a specific screen:

```bash
$E shell dumpsys gfxinfo website.chagai.matrixchat | head -40
```

### The numbers

See the round-2 table at the bottom of `CHANGELOG-chagai.md`.

## Keeping up with upstream

```bash
git fetch upstream --tags
git checkout chagai/main
git rebase v26.XX.Y          # or merge, if the fork has diverged a lot
```

Then walk `BRANDING.md` — it lists every file this fork touches, so the conflict set is
predictable. Reset `versionChagai` to 1 in `plugins/src/main/kotlin/Versions.kt` after moving
to a new upstream tag, and re-run `tools/chagai/make_icons.py` if upstream changed the icon
layout.

Never push to `upstream`, and never force-push `chagai/main`.
