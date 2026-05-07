# Running RLB in Docker

Two-image architecture, one shared volume for accounts:

```
┌──────────────────────────┐     ┌──────────────────────────┐
│  rlb-bot (production)    │     │  rlb-capture (onboarding)│
│  ~600 MB · always-on     │     │  ~1.3 GB · ephemeral     │
│  RuneLite + AccountLauncher    │  Wine + JagexLauncher.exe│
│  + Xvfb/x11vnc + proxychains   │  + AccountLauncher       │
└────────────┬─────────────┘     └────────────┬─────────────┘
             │                                 │
             │   shared Docker named volume    │
             │     `rlb-shared-accounts`       │
             ▼                                 ▼
        /root/.runelite/rlb/accounts/  ←─ both containers see the same
                                          captured Jagex accounts here
```

The container's PID 1 is `AccountLauncher` (Swing UI), not RuneLite.
You VNC in, see the account list, click Launch on whichever account
you want. RuneLite spawns as a child JVM; close its window or click
Stop to return to the launcher.

**Host disk never touches a Jagex token** when the capture image is in
use. Onboarding happens inside `rlb-capture`; production uses the
shared volume. The legacy host-capture path still works (and is now
non-destructive — see `AccountLauncher` snapshot/restore below) but
isn't required.

## What's in the box

| File | Role |
|---|---|
| `Dockerfile` | `rlb-bot` runtime: `eclipse-temurin:17-jre` + Xvfb + x11vnc + proxychains4 + the host-built shaded jar + the seeded public game caches. |
| `Dockerfile.capture` | `rlb-capture`: extends `rlb-bot` with Wine + Firefox + the Jagex Launcher Windows installer. |
| `entrypoint.sh` | Per-launch startup: seed caches, optional proxy config, Xvfb + x11vnc, exec AccountLauncher. Special `wine-install` mode for one-time Wine setup. |
| `.dockerignore` | Keeps the build context tiny — only the jar, `seed/`, and `entrypoint.sh`. |
| `seed/` | **Gitignored.** `cache/` + `jagexcache/` from `~/.runelite/` (210MB, public game data). For `rlb-capture`: also `JagexLauncher.exe` (the Windows installer, ~150MB, downloaded from Jagex). |

## One-time setup

### 1. Seed the public game caches

```bash
mkdir -p seed
cp -r ~/.runelite/cache       seed/
cp -r ~/.runelite/jagexcache  seed/
```

These are public game data (XTEA keys + the OSRS file cache). Same
seed works for every account. Refresh whenever Jagex pushes a major
game update on your host's RuneLite (so the in-container cache
isn't days-stale and triggering a full re-download).

### 2. Build the production image

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:shadowJar
docker build -t rlb-bot .
```

Image size: ~600MB (JRE base + apt + jagexcache + jar).

### 3. (Optional) Build the capture image

Skip this if you're fine doing Add-Jagex on your Mac. Do it if you
want zero host-disk involvement in account onboarding.

```bash
# Download "Jagex Launcher" Windows installer (.exe) from
#   https://www.jagex.com/en-GB/launcher
# Save as: seed/JagexLauncher.exe   (~150MB, gitignored)

docker build -f Dockerfile.capture -t rlb-capture .
```

Image size: ~1.3GB (rlb-bot + Wine + Firefox + the installer).

### 4. (Once per `rlb-capture` build) Install Jagex Launcher under Wine

The install drops binaries into the Wine prefix. We persist that
prefix as a named volume `rlb-wine`, so this step runs exactly once
per `rlb-capture` rebuild.

```bash
docker run --rm -it -p 5901:5900 \
  -v rlb-wine:/root/.wine \
  rlb-capture \
  /entrypoint.sh wine-install

open vnc://localhost:5901   # password: rlb
# Click through the installer GUI. When done, close the installer
# window — the container exits cleanly.
```

## Capturing a Jagex account (no host involvement)

```bash
docker run --rm -it -p 5901:5900 \
  -v rlb-wine:/root/.wine \
  -v rlb-shared-accounts:/root/.runelite/rlb/accounts \
  rlb-capture

open vnc://localhost:5901   # password: rlb
# 1. AccountLauncher opens. Click "Add Jagex".
# 2. Type a display name, click OK.
# 3. Wine launches the installed Jagex Launcher.
# 4. In Jagex Launcher, log in as the new alt, click Play.
# 5. Credentials get written into rlb-shared-accounts volume.
# 6. Close the launcher window — container exits.
```

Production `rlb-bot` containers that mount `rlb-shared-accounts` see
the new account on next launch.

## Capturing a Jagex account (host fallback)

If you don't want to bother with Wine, the legacy host-capture path
still works and is now non-destructive (see "Snapshot/restore" below).

```bash
# On your Mac, outside Docker:
JBIN=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java
$JBIN -cp runelite-client/build/libs/client-*-shaded.jar \
  net.runelite.client.launcher.AccountLauncher
```

Click Add Jagex → click Play in Jagex Launcher → captured to
`~/.runelite/rlb/accounts/<id>/`. Then either:

- bind-mount that path into containers (`-v $HOME/.runelite/rlb/accounts:/root/.runelite/rlb/accounts`), or
- one-shot copy into the `rlb-shared-accounts` named volume:

```bash
docker run --rm \
  -v rlb-shared-accounts:/dest \
  -v $HOME/.runelite/rlb/accounts:/src:ro \
  alpine sh -c 'cp -r /src/. /dest/'
```

### Snapshot/restore (the bug fix)

Previously, host's `~/.runelite/credentials.properties` got
overwritten by every Add Jagex (Jagex Launcher dumps tokens there).
That meant adding alt-2 changed which account host's RuneLite would
auto-launch into. Now `AccountLauncher.onAddAccount()` snapshots the
file before launching Jagex Launcher, captures the fresh tokens to
the per-account dir, and **restores host's pre-capture state**.

If host had no `credentials.properties` before the capture, it has
none after. If it had account A's tokens, it has account A's tokens
afterwards. Adding accounts is now isolated from "active session"
state on host.

## Running production containers

```bash
docker run -d --name rlb-acct-jack \
  -p 5901:5900 \
  -v rlb-acct-jack:/root/.runelite \
  -v rlb-shared-accounts:/root/.runelite/rlb/accounts \
  rlb-bot

open vnc://localhost:5901   # password: rlb
# In the launcher: select 'jack', click Launch.
```

| Volume | Scope | Purpose |
|---|---|---|
| `rlb-acct-<name>` (per-container, named) | one container | logs, trails, cache, profiles2, settings.json |
| `rlb-shared-accounts` (cross-container, named) | all rlb-bot containers | captured Jagex/Regular accounts (the `accounts/` dir) |

Multi-account is just naming + ports:

```bash
docker run -d --name rlb-acct-A -p 5901:5900 \
  -v rlb-acct-A:/root/.runelite \
  -v rlb-shared-accounts:/root/.runelite/rlb/accounts \
  rlb-bot

docker run -d --name rlb-acct-B -p 5902:5900 \
  -v rlb-acct-B:/root/.runelite \
  -v rlb-shared-accounts:/root/.runelite/rlb/accounts \
  rlb-bot
```

Both containers see all captured accounts in their launcher; each
launches whichever one you click.

## Proxy (per account, with auth)

Pass proxy config via env vars. The entrypoint generates
`/etc/proxychains4.conf` and wraps `java` in `proxychains4 -q`. Every
TCP connection (game protocol AND HTTP fetches AND DNS) goes through
the proxy.

```bash
docker run -d --name rlb-acct-A -p 5901:5900 \
  -e PROXY_HOST=residential.proxy.example \
  -e PROXY_PORT=1080 \
  -e PROXY_USER=user12345 \
  -e PROXY_PASS=secret \
  -v rlb-acct-A:/root/.runelite \
  -v rlb-shared-accounts:/root/.runelite/rlb/accounts \
  rlb-bot
```

| Env var | Default | Notes |
|---|---|---|
| `PROXY_HOST` | unset = no proxy | Hostname or IP of the proxy. |
| `PROXY_PORT` | required if `PROXY_HOST` set | TCP port. |
| `PROXY_TYPE` | `socks5` | Or `http`. SOCKS5 is the conventional choice for OSRS. |
| `PROXY_USER` | unset | Skip both USER and PASS for unauthenticated proxies. |
| `PROXY_PASS` | unset | |
| `VNC_PASSWORD` | `rlb` | Default fine for Docker Desktop (localhost-only). |
| `VNC_PORT` | `5900` | Container port; map with `-p HOST:5900`. |
| `JAGEX_LAUNCHER_EXE` | set in `rlb-capture` | Path inside the container to the installed Jagex Launcher .exe. Override only if your installer drops it elsewhere. |

To verify the egress IP after launch:

```bash
docker exec rlb-acct-A proxychains4 -q curl -s https://ifconfig.me
```

## See and control via VNC

Each container exposes a VNC server on container port 5900. Connect
from Mac Screen Sharing:

```bash
open vnc://localhost:5901   # password: rlb
```

What works:

- **Watch** the canvas in real time
- **Click panels** (RecorderPanel sidebar, plugin config) — go to
  Swing, don't fight the bot
- **Click the canvas** — works, but if a script is mid-dispatch the
  manual click will fight `HumanizedInputDispatcher` (CLAUDE.md §8).
  Fine for "stop the bot, click around manually."
- **Type chat, resize, etc.**

Mac's Screen Sharing app requires password auth (rejects "no auth"
VNC), so we always set one. Default `rlb`. Override with
`-e VNC_PASSWORD=...`. Fine to keep the default when ports are
localhost-only on Docker Desktop; pick a real password if you ever
expose 5900 to a LAN/VPS.

## State and persistence

Each container's `/root/.runelite` is a named Docker volume. Survives
container restart, removed on `docker volume rm`. Lives in Docker's
storage area inside the Docker Desktop VM on Mac, not in your project
tree or under `~/.runelite/`.

Inside a per-container volume:

```
/root/.runelite/
  cache/                     # seeded from /seed on first run, mutated at runtime
  jagexcache/                # ditto — incremental updates persist here
  recorder/trails/*.json     # any new trails recorded in this container
  logs/client.log            # bot's own log
  profiles2/                 # plugin configs (persist across restarts)
  settings.json              # RuneLite launcher settings
  credentials.properties     # CURRENT active account's tokens
                             # (overwritten each time you Launch in the launcher)
```

The `rlb/accounts/` subdirectory is overlaid by the
`rlb-shared-accounts` named volume, so it's NOT inside the
per-container volume — it's the cross-container store of every
captured account.

To grab a trail out of a container:

```bash
docker cp rlb-acct-jack:/root/.runelite/recorder/trails/my-trail.json .
```

Wipe a single account's per-container state and start over (without
losing the captured accounts):

```bash
docker stop rlb-acct-jack
docker rm rlb-acct-jack
docker volume rm rlb-acct-jack
# rlb-shared-accounts is untouched
```

Wipe everything (nuke from orbit):

```bash
docker stop $(docker ps -aq --filter name=rlb-)
docker rm $(docker ps -aq --filter name=rlb-)
docker volume rm $(docker volume ls -q --filter name=rlb-)
```

## Troubleshooting

**Empty launcher window** — `rlb-shared-accounts` volume is fresh.
Either capture an account via `rlb-capture`, or seed the volume from
host (see "host fallback" above). Or click "Add Regular" if you don't
need Jagex auth.

**`JAGEX_LAUNCHER_EXE env var not set` when clicking Add Jagex** —
you're running `rlb-bot`, not `rlb-capture`. Add Jagex needs Wine + the
installed Jagex Launcher, which only the capture image has. Use
`rlb-capture` for onboarding, `rlb-bot` for production runs.

**Wine launch fails / Jagex Launcher window doesn't appear** — the
`rlb-wine` named volume probably wasn't initialized. Re-run the
`/entrypoint.sh wine-install` step. If the install itself fails, check
`docker logs` for the Wine error; common fix is bumping Wine to a
newer version in `Dockerfile.capture` (`apt-get install winehq-staging`
from the WineHQ repo instead of Debian's stock Wine).

**RuneLite window is black through VNC** — Xvfb is up but the JVM
crashed. Check `docker logs rlb-acct-<name>` for the stack trace. Most
common cause is a missing native lib for AWT — add it to the apt-get
list in the Dockerfile.

**Bot acts but you can't see anything via VNC** — the VNC port
mapping is missing. Restart the container with `-p HOST:5900`.

**Egress IP isn't the proxy's** — env vars weren't set, or the proxy
itself rejected auth. `docker logs` shows
`[entrypoint] proxy enabled: ...` if config was applied; if you see
`no proxy configured`, the env vars didn't reach the container.

**Image rebuild is slow every time** — the jar layer is at the bottom
of the Dockerfile but the apt + seed layers above it should be cached.
Check you're not running `docker build --no-cache`.

**Docker daemon socket missing on Mac** — Docker Desktop isn't
running. `open -a Docker`, wait ~30s.

## Full VPN per account (alternative to SOCKS5)

If you want a real VPN tunnel instead of a SOCKS5 proxy, use
`qmcgaw/gluetun` as a sidecar and share its network namespace:

```bash
docker run -d --name vpn-acct-A --cap-add=NET_ADMIN \
  -e VPN_SERVICE_PROVIDER=mullvad \
  -e VPN_TYPE=wireguard \
  -e WIREGUARD_PRIVATE_KEY=... \
  -e WIREGUARD_ADDRESSES=... \
  -p 5901:5900 \
  qmcgaw/gluetun

docker run -d --name rlb-acct-A \
  --network=container:vpn-acct-A \
  -v rlb-acct-A:/root/.runelite \
  -v rlb-shared-accounts:/root/.runelite/rlb/accounts \
  rlb-bot
```

The **VPN container** owns the `-p` port mapping — the bot container
can't have its own when sharing network namespace. All bot traffic,
including the VNC connection from your Mac, routes through the VPN
container's port forwarding.

Use SOCKS5+proxychains for "different proxy per account, simple"; use
gluetun sidecars for "real VPN tunnel per account, more setup."
