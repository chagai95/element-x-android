#!/usr/bin/env bash
# =============================================================================
#  Matrix Chat — one-command release
# =============================================================================
#  Bumps the fork build number, builds a signed release APK, verifies it,
#  publishes it to https://chagai.website/matrix-chat/, updates the changelog,
#  tags the commit and sends the install link to Chagai's WhatsApp self-chat.
#
#  Usage:
#      ./release.sh                      # bump, build, verify, publish, tag, send
#      ./release.sh --notes "What's new" # same, with an explicit changelog entry
#      ./release.sh --no-bump            # rebuild the current version
#      ./release.sh --no-send            # do not message WhatsApp
#      ./release.sh --no-publish         # build + verify only, keep it local
#      ./release.sh --universal          # also build/publish the all-ABI APK
#
#  Everything heavy runs under nice/ionice with a capped Gradle, because this
#  server also runs Chagai's live Matrix stack, bridges, dashboard and Ollama.
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
# shellcheck source=/dev/null
source "${HERE}/env.sh"

ARTIFACTS="${BUILD_ROOT}/artifacts"
PUBLIC_DIR="/matrix/static-files/public/matrix-chat"
PUBLIC_URL="https://chagai.website/matrix-chat"
CREDS="${BUILD_ROOT}/keystore/chagai-release.creds"
SELF_CHAT_ROOM='!eBmyiTBQmVtMLbhYyU:chagai.website'
SEND_SH="/home/claude/matrix-os/bin/send.sh"
VERSIONS_KT="plugins/src/main/kotlin/Versions.kt"
CHANGELOG="CHANGELOG-chagai.md"

BUMP=1 ; SEND=1 ; PUBLISH=1 ; UNIVERSAL=0 ; NOTES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-bump)    BUMP=0;      shift;;
    --no-send)    SEND=0;      shift;;
    --no-publish) PUBLISH=0;   shift;;
    --universal)  UNIVERSAL=1; shift;;
    --notes)      NOTES="$2";  shift 2;;
    -h|--help)    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

# --- 0. Guard: never start a heavy build while the box is already loaded ------
LOAD="$(awk '{print int($1)}' /proc/loadavg)"
if [ "$LOAD" -gt 12 ]; then
  echo "load average is ${LOAD} — refusing to start a build that would starve the live services." >&2
  echo "re-run when it drops below 12, or build by hand if you know it is fine." >&2
  exit 1
fi

# --- 1. Version ---------------------------------------------------------------
say "Version"
CUR="$(grep -oP '(?<=^private const val versionChagai = )\d+' "$VERSIONS_KT")"
if [ "$BUMP" -eq 1 ]; then
  NEXT=$((CUR + 1))
  sed -i "s/^private const val versionChagai = ${CUR}$/private const val versionChagai = ${NEXT}/" "$VERSIONS_KT"
else
  NEXT="$CUR"
fi
UP_Y="$(grep -oP '(?<=^private const val versionYear = )\d+' "$VERSIONS_KT")"
UP_M="$(grep -oP '(?<=^private const val versionMonth = )\d+' "$VERSIONS_KT")"
UP_R="$(grep -oP '(?<=^private const val versionReleaseNumber = )\d+' "$VERSIONS_KT")"
VERSION="$(printf '%d.%02d.%d-chagai.%d' "$UP_Y" "$UP_M" "$UP_R" "$NEXT")"
VERSION_CODE=$(( (2000 + UP_Y) * 10000 + UP_M * 100 + UP_R + NEXT ))
echo "  version      ${VERSION}"
echo "  version code ${VERSION_CODE}  (x10 + abi code in the APK)"

# --- 2. Build -----------------------------------------------------------------
say "Build (signed release APK, fdroid flavour, capped + niced)"
# shellcheck source=/dev/null
source <(grep -E '^(keystore|alias|storepass|keypass)=' "$CREDS")
GRADLE_ARGS=(
  --no-daemon --max-workers=2 --console=plain
  -Pchagai.storeFile="$keystore"
  -Pchagai.storePassword="$storepass"
  -Pchagai.keyAlias="$alias"
  -Pchagai.keyPassword="$keypass"
)
"${NICE[@]}" ./gradlew "${GRADLE_ARGS[@]}" :app:assembleFdroidRelease

OUT="app/build/outputs/apk/fdroid/release"
APK_ARM64="${OUT}/app-fdroid-arm64-v8a-release.apk"
APK_UNIV="${OUT}/app-fdroid-universal-release.apk"
[ -f "$APK_ARM64" ] || { echo "expected APK not found: $APK_ARM64" >&2; ls -la "$OUT" >&2; exit 1; }

# --- 3. Verify ----------------------------------------------------------------
say "Verify"
APKSIGNER="${ANDROID_SDK_ROOT}/build-tools/37.0.0/apksigner"
AAPT2="${ANDROID_SDK_ROOT}/build-tools/37.0.0/aapt2"
"$APKSIGNER" verify --print-certs "$APK_ARM64" | sed 's/^/  /'
"$AAPT2" dump badging "$APK_ARM64" | grep -E "^package|application-label:|sdkVersion|targetSdkVersion|native-code" | sed 's/^/  /'

BADGE_PKG="$(   "$AAPT2" dump badging "$APK_ARM64" | grep -oP "(?<=^package: name=')[^']+")"
BADGE_LABEL="$( "$AAPT2" dump badging "$APK_ARM64" | grep -oP "(?<=^application-label:')[^']+")"
BADGE_VNAME="$( "$AAPT2" dump badging "$APK_ARM64" | grep -oP "(?<=versionName=')[^']+")"
[ "$BADGE_PKG"   = "website.chagai.matrixchat" ] || { echo "unexpected package id: $BADGE_PKG" >&2; exit 1; }
[ "$BADGE_LABEL" = "Matrix Chat" ]               || { echo "unexpected app label: $BADGE_LABEL" >&2; exit 1; }
[ "$BADGE_VNAME" = "$VERSION" ]                  || { echo "APK says $BADGE_VNAME, expected $VERSION" >&2; exit 1; }
echo "  OK: ${BADGE_LABEL} / ${BADGE_PKG} / ${BADGE_VNAME}"

# --- 4. Collect artifacts -----------------------------------------------------
say "Artifacts"
mkdir -p "$ARTIFACTS"
ART_ARM64="${ARTIFACTS}/matrix-chat-${VERSION}-arm64.apk"
cp -f "$APK_ARM64" "$ART_ARM64"
SHA_ARM64="$(sha256sum "$ART_ARM64" | cut -d' ' -f1)"
SIZE_ARM64="$(stat -c%s "$ART_ARM64")"
echo "  ${ART_ARM64}  ($(numfmt --to=iec "$SIZE_ARM64"))"
echo "  sha256 ${SHA_ARM64}"

ART_UNIV="" ; SHA_UNIV=""
if [ "$UNIVERSAL" -eq 1 ] && [ -f "$APK_UNIV" ]; then
  ART_UNIV="${ARTIFACTS}/matrix-chat-${VERSION}-universal.apk"
  cp -f "$APK_UNIV" "$ART_UNIV"
  SHA_UNIV="$(sha256sum "$ART_UNIV" | cut -d' ' -f1)"
  echo "  ${ART_UNIV}"
fi

# --- 5. Changelog -------------------------------------------------------------
say "Changelog"
[ -n "$NOTES" ] || NOTES="Build ${VERSION}."
TODAY="$(date +%Y-%m-%d)"
python3 - "$CHANGELOG" "$VERSION" "$TODAY" "$NOTES" <<'PY'
import sys, pathlib
path, version, today, notes = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
p = pathlib.Path(path)
body = p.read_text() if p.exists() else "# Matrix Chat — changelog\n\nChanges made in this fork, newest first.\n"
marker = "newest first.\n"
entry = f"\n## {version} — {today}\n\n" + "\n".join(f"- {l.strip()}" for l in notes.splitlines() if l.strip()) + "\n"
if f"\n## {version} " in body:
    print("  changelog already has an entry for this version, leaving it alone")
else:
    i = body.index(marker) + len(marker)
    p.write_text(body[:i] + entry + body[i:])
    print(f"  added changelog entry for {version}")
PY

# --- 6. Publish ---------------------------------------------------------------
if [ "$PUBLISH" -eq 1 ]; then
  say "Publish to ${PUBLIC_URL}/"
  sudo install -d -o chagai -g chagai -m 755 "$PUBLIC_DIR"
  sudo install -o chagai -g chagai -m 644 "$ART_ARM64" "${PUBLIC_DIR}/matrix-chat-${VERSION}-arm64.apk"
  [ -n "$ART_UNIV" ] && sudo install -o chagai -g chagai -m 644 "$ART_UNIV" "${PUBLIC_DIR}/matrix-chat-${VERSION}-universal.apk"
  sudo install -o chagai -g chagai -m 644 "${BUILD_ROOT}/artifacts/matrix-chat-icon.png" "${PUBLIC_DIR}/icon.png" 2>/dev/null || true

  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  VERSION="$VERSION" VERSION_CODE="$VERSION_CODE" SHA_ARM64="$SHA_ARM64" SIZE_ARM64="$SIZE_ARM64" \
  SHA_UNIV="$SHA_UNIV" NOTES="$NOTES" PUBLIC_URL="$PUBLIC_URL" TODAY="$TODAY" CHANGELOG="$CHANGELOG" \
  python3 "${HERE}/tools/chagai/publish_page.py" "$TMP"
  sudo install -o chagai -g chagai -m 644 "${TMP}/latest.json" "${PUBLIC_DIR}/latest.json"
  sudo install -o chagai -g chagai -m 644 "${TMP}/index.html"  "${PUBLIC_DIR}/index.html"

  APK_URL="${PUBLIC_URL}/matrix-chat-${VERSION}-arm64.apk"
  CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$APK_URL")"
  echo "  ${APK_URL} -> HTTP ${CODE}"
  [ "$CODE" = "200" ] || { echo "published APK is not reachable" >&2; exit 1; }
else
  APK_URL="(not published)"
fi

# --- 7. Commit + tag ----------------------------------------------------------
say "Commit + tag"
git add -A
if git diff --cached --quiet; then
  echo "  nothing to commit"
else
  git commit -q -m "release: Matrix Chat ${VERSION}

${NOTES}"
fi
TAG="v${VERSION}"
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
  echo "  tag ${TAG} already exists, leaving it alone"
else
  git tag -a "$TAG" -m "Matrix Chat ${VERSION}"
  echo "  tagged ${TAG}"
fi
# Keep the local bare mirror in step (it never reaches anywhere public on its own).
#
# Note the direction: the mirror FETCHES from here rather than this repo pushing to it. This
# working copy is a partial (blobless) clone, so `git push` refuses — pack-objects will not
# lazily fetch the blobs it would have to walk. A fetch lets the mirror say what it already has,
# and since it holds all of upstream only the fork's own new objects move. A mirror that cannot
# be written is a warning, not a reason to stop: the APK is already built, verified, published
# and reachable by this point.
MIRROR="${BUILD_ROOT}/element-x-android.git"
if [ -d "$MIRROR" ]; then
  if git -C "$MIRROR" fetch -q "$HERE" \
      "refs/heads/chagai/main:refs/heads/chagai/main" "refs/tags/*:refs/tags/*" 2>/dev/null; then
    echo "  mirrored to the local bare repo"
    # …and on to GitHub. Pushed from the MIRROR, not from here: this working copy is a
    # blobless partial clone and pack-objects refuses to push from one. The mirror is
    # complete, so it can. `fork` is chagai95/element-x-android; `origin` in the mirror is
    # still upstream and is never pushed to. Never forced: only the fork's own branch and
    # this release's own tag move, fast-forward or not at all.
    #
    # This matters beyond convenience — Element X is AGPL-3.0 and the About screen offers
    # the source at that URL, so the repo has to carry whatever is installed.
    if git -C "$MIRROR" remote get-url fork >/dev/null 2>&1; then
      if git -C "$MIRROR" push -q fork \
          "refs/heads/chagai/main:refs/heads/chagai/main" "refs/tags/${TAG}" 2>/dev/null; then
        echo "  pushed chagai/main + ${TAG} to GitHub"
      else
        # The APK is already built, verified, published and reachable by this point; a
        # failed push is a warning, not a reason to fail the release.
        echo "  WARNING: could not push to GitHub (continuing)" >&2
      fi
    fi
  else
    echo "  WARNING: could not update the local bare mirror (continuing)" >&2
  fi
fi

# --- 8. Tell Chagai -----------------------------------------------------------
if [ "$SEND" -eq 1 ] && [ "$PUBLISH" -eq 1 ]; then
  say "Sending the link to the WhatsApp self-chat"
  MSG="📱 Matrix Chat ${VERSION} is ready to install:
${APK_URL}

${NOTES}

Signs in to chagai.website by default. Installs next to the real Element X (different app), so nothing is overwritten."
  bash "$SEND_SH" --room "$SELF_CHAT_ROOM" --text "$MSG"
fi

say "Done — Matrix Chat ${VERSION}"
echo "  APK  ${APK_URL}"
echo "  sha256 ${SHA_ARM64}"
