#!/usr/bin/env bash
# Kills the Matrix Chat Gradle build if the box runs low on memory, so a build can never
# push the live services into swap-death or reach the OOM killer. Started by build-release.sh.
FLOOR_MB="${1:-2200}"
while :; do
  sleep 20
  pgrep -f gradle-home-elementx >/dev/null || exit 0
  AVAIL="$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)"
  SWAPFREE="$(awk '/SwapFree/{print int($2/1024)}' /proc/meminfo)"
  if [ "$AVAIL" -lt "$FLOOR_MB" ] && [ "$SWAPFREE" -lt 2048 ]; then
    echo "WATCHDOG: MemAvailable=${AVAIL}MB SwapFree=${SWAPFREE}MB -> killing the build to protect the live services" >&2
    pkill -f gradle-home-elementx
    exit 2
  fi
done
