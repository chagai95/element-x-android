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
