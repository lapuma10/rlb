#!/usr/bin/env bash
# Sync state between host's ~/.runelite/ and the Docker named volumes that
# rlb-bot containers mount. Three things get synced (each can go either
# direction):
#
#   accounts      ~/.runelite/rlb/accounts        rlb-shared-accounts
#   trails        ~/.runelite/recorder/trails     rlb-shared-trails
#   worldmap      ~/.runelite/recorder/worldmap   rlb-shared-worldmap
#
# Usage:
#   scripts/sync-runelite.sh from-host    push host → docker, restart containers
#   scripts/sync-runelite.sh to-host      pull docker → host
#   scripts/sync-runelite.sh both         from-host then to-host (host always wins on conflict)
#   scripts/sync-runelite.sh capture      run host AccountLauncher, sync result, restart
#
# Default action with no args: from-host.
#
# Conflict policy: cp -r overwrites — whichever side is the "source" wins.
# To preserve a captured-in-container trail, run `to-host` BEFORE you next
# run `from-host`. (We don't merge by mtime to keep this simple.)

set -euo pipefail

HOST_RL="$HOME/.runelite"
JBIN="${JBIN:-/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java}"
JAR_GLOB="$(dirname "$0")/../runelite-client/build/libs/client-*-shaded.jar"

# ----- shared volume names (must match what containers mount) -----
VOL_ACCOUNTS=rlb-shared-accounts
VOL_TRAILS=rlb-shared-trails
VOL_WORLDMAP=rlb-shared-worldmap

ensure_volumes() {
  for v in "$VOL_ACCOUNTS" "$VOL_TRAILS" "$VOL_WORLDMAP"; do
    docker volume inspect "$v" >/dev/null 2>&1 || docker volume create "$v" >/dev/null
  done
}

restart_running_bots() {
  # Restart every running rlb-* container EXCEPT capture/wine (those are
  # ephemeral and shouldn't be restarted out from under the user).
  local names
  names=$(docker ps --format '{{.Names}}' | grep '^rlb-' | grep -v -E '^rlb-(capture|wine|wine-install)' || true)
  if [ -z "$names" ]; then
    echo "[sync] no running rlb-* containers to restart"
    return
  fi
  for c in $names; do
    docker restart "$c" >/dev/null && echo "[sync] restarted: $c"
  done
}

case "${1:-from-host}" in
  from-host)
    ensure_volumes
    docker run --rm \
      -v "$VOL_ACCOUNTS:/dest-accounts" \
      -v "$VOL_TRAILS:/dest-trails" \
      -v "$VOL_WORLDMAP:/dest-worldmap" \
      -v "$HOST_RL:/src:ro" \
      alpine sh -c '
        [ -d /src/rlb/accounts ]      && cp -r /src/rlb/accounts/.      /dest-accounts/  || true
        [ -d /src/recorder/trails ]   && cp -r /src/recorder/trails/.   /dest-trails/    || true
        [ -d /src/recorder/worldmap ] && cp -r /src/recorder/worldmap/. /dest-worldmap/  || true
        echo "[sync] from-host:" \
             "accounts=$(ls /dest-accounts 2>/dev/null | wc -l | tr -d " ")" \
             "trails=$(ls /dest-trails 2>/dev/null | wc -l | tr -d " ")" \
             "worldmap=$(ls /dest-worldmap 2>/dev/null | wc -l | tr -d " ")"
      '
    restart_running_bots
    ;;
  to-host)
    ensure_volumes
    mkdir -p "$HOST_RL/rlb/accounts" "$HOST_RL/recorder/trails" "$HOST_RL/recorder/worldmap"
    docker run --rm \
      -v "$VOL_ACCOUNTS:/src-accounts:ro" \
      -v "$VOL_TRAILS:/src-trails:ro" \
      -v "$VOL_WORLDMAP:/src-worldmap:ro" \
      -v "$HOST_RL:/dest" \
      alpine sh -c '
        cp -r /src-accounts/.  /dest/rlb/accounts/      2>/dev/null || true
        cp -r /src-trails/.    /dest/recorder/trails/   2>/dev/null || true
        cp -r /src-worldmap/.  /dest/recorder/worldmap/ 2>/dev/null || true
        echo "[sync] to-host:" \
             "accounts=$(ls /dest/rlb/accounts 2>/dev/null | wc -l | tr -d " ")" \
             "trails=$(ls /dest/recorder/trails 2>/dev/null | wc -l | tr -d " ")" \
             "worldmap=$(ls /dest/recorder/worldmap 2>/dev/null | wc -l | tr -d " ")"
      '
    ;;
  both)
    "$0" from-host
    "$0" to-host
    ;;
  capture)
    # Run host AccountLauncher in foreground; when it exits (user closes
    # the launcher window after capture), sync host → docker and restart.
    JAR=$(ls $JAR_GLOB 2>/dev/null | head -1)
    if [ -z "$JAR" ]; then
      echo "[sync] no shaded jar found; run ./gradlew :client:shadowJar first" >&2
      exit 1
    fi
    echo "[sync] launching host AccountLauncher — Add Jagex / Add Regular as needed,"
    echo "[sync] then close the launcher window to continue."
    "$JBIN" -cp "$JAR" net.runelite.client.launcher.AccountLauncher
    "$0" from-host
    ;;
  *)
    echo "Usage: $0 [from-host|to-host|both|capture]" >&2
    exit 1
    ;;
esac
