# Session & Script Runtime Tracker — Design Spec

**Date:** 2026-05-19  
**Feature:** Per-account login session tracking + script runtime aggregation  
**Audience:** RecorderPlugin users (tracking daily/weekly/monthly/all-time bot activity)

---

## Overview

Add a "Stats" tab to RecorderPanel that displays:
- **Current login session:** time logged in, total duration
- **Script runtimes:** per-script duration breakdown (daily/weekly/monthly/all-time totals)
- **Easy period switching:** radio buttons to flip between Daily | Weekly | Monthly | All-Time views
- **Per-account persistence:** session data stored to `~/.runelite/recorder/sessions/<accountname>/` as JSON files, surviving client restarts

---

## Data Model

### Session File Format

Each day is stored as a JSON file: `~/.runelite/recorder/sessions/<accountname>/<YYYY-MM-DD>.json`

Multiple login sessions can occur in a single day. Raw data is stored as events (script runs), not pre-aggregated. Aggregation happens at read time.

```json
{
  "date": "2026-05-19",
  "sessions": [
    {
      "sessionId": "1716144123000",
      "loginTime": 1716144123000,
      "logoutTime": 1716161234000,
      "runs": [
        {
          "scriptId": "chicken_farm_v3",
          "displayName": "Chicken Farm V3",
          "startMs": 1716144123000,
          "endMs": 1716147423000,
          "count": 42,
          "countLabel": "kills"
        },
        {
          "scriptId": "cooking_v3",
          "displayName": "Cooking V3",
          "startMs": 1716147500000,
          "endMs": 1716149420000,
          "count": 180,
          "countLabel": "cooked"
        },
        {
          "scriptId": "mining",
          "displayName": "Mining",
          "startMs": 1716149500000,
          "endMs": 1716150520000,
          "count": 84,
          "countLabel": "ores"
        }
      ]
    }
  ]
}
```

**Fields:**
- `date`: ISO date string (YYYY-MM-DD) — file organization and boundary detection
- `sessions`: array of login sessions for this day (supports multiple logins per day)
- `sessionId`: unique ID for this session (use `loginTimeMs` as the session ID)
- `loginTime`, `logoutTime`: milliseconds since epoch
- `runs`: flat array of all script run segments in this session
  - `scriptId`: stable identifier (e.g., `chicken_farm_v3`) — does not change if display name changes
  - `displayName`: human-readable label for UI
  - `startMs`, `endMs`: milliseconds since epoch (always in same session, no midnight crossing)
  - `count`: script-specific metric (kills, ores, items cooked, etc.) — null if not reported
  - `countLabel`: name of the metric (`"kills"`, `"ores"`, `"cooked"`, etc.) — helps UI label the number

### In-Memory Session State

While logged in, `SessionTracker` maintains:
```java
record LoginSession(
  String sessionId,
  long loginTimeMs,
  List<ScriptRun> runs
) {}

record ScriptRun(
  String scriptId,
  String displayName,
  long startMs,
  Long endMs,           // null while running
  Integer count,        // null until script stops
  String countLabel
) {}

// Per-script current state (not persisted; used to track active runs)
class ActiveScriptState {
  String scriptId;
  String displayName;
  long startMs;
  int currentCount;     // accumulating while script is running
  String countLabel;    // e.g., "kills", "ores"
}
```

Tracker maintains:
- `currentLoginSession: LoginSession` (null if logged out)
- `activeScripts: Map<String, ActiveScriptState>` (script ID → current state)

When a script stops, the tracker:
1. Finalizes the run: `ScriptRun(scriptId, displayName, startMs, endMs=now, count, countLabel)`
2. Adds to `currentLoginSession.runs`
3. Removes from `activeScripts`

On logout:
1. Writes `currentLoginSession` to disk
2. Clears `currentLoginSession` and `activeScripts`

---

## Architecture

### Core Components

#### 1. SessionTracker (new class)

Responsibility: detect login/logout; track script start/stop; manage in-memory session state; serialize to disk.

**Location:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionTracker.java`

**Key methods:**
- `onGameStateChanged(GameState)` — detect LOGGED_IN / LOGGED_OUT transitions; start/end sessions
- `onScriptStarted(ScriptName)` — record script start time
- `onScriptStopped(ScriptName, count)` — record script end time + optional completion count
- `getCurrentSession()` → SessionState (null if logged out)
- `saveSessionToDisk(SessionState)` → writes to JSON file

**Threading:** Runs on the client thread (reads `client.getGameState()`); JSON writes delegated to a background worker to avoid blocking.

#### 2. SessionStore (new class)

Responsibility: read/write session files; aggregate across multiple days.

**Location:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionStore.java`

**Key methods:**
- `saveSession(String accountName, SessionState)` → writes `<accountName>/<YYYY-MM-DD>.json`
- `loadSession(String accountName, LocalDate)` → reads and parses `<accountName>/<YYYY-MM-DD>.json`
- `aggregateDaily(String accountName, LocalDate)` → total duration + per-script breakdown for one day
- `aggregateWeekly(String accountName, LocalDate)` → sum across Mon–Sun (or chosen week)
- `aggregateMonthly(String accountName, YearMonth)` → sum across all days in the month
- `aggregateAllTime(String accountName)` → sum all files in the account's session directory

**Return type:** `SessionStats` (immutable record)
```java
record SessionStats(
  long totalLoginMs,           // sum of all (logoutTime - loginTime) in the period
  long totalScriptActiveMs,    // union of all script run intervals (no double-counting overlaps)
  long idleMs,                 // totalLoginMs - totalScriptActiveMs
  Map<String, ScriptStats> scripts
) {}

record ScriptStats(
  String scriptId,
  String displayName,
  long totalMs,                // sum of individual run durations
  Integer totalCount,          // sum of counts across all runs (null if script never reported counts)
  String countLabel            // e.g., "kills", "ores" (from last run of this script)
) {}
```

**Note on overlapping scripts:** If two scripts run simultaneously (e.g., Combat + Banking), `totalScriptActiveMs` counts the union of their intervals, not the sum. This prevents double-counting idle time.

**Algorithm:**
1. Collect all script run intervals: `[(s1.start, s1.end), (s2.start, s2.end), ...]`
2. Merge overlapping intervals
3. Sum the merged intervals to get `totalScriptActiveMs`
4. `idleMs = totalLoginMs - totalScriptActiveMs`

#### 3. StatsPanel (new JPanel)

Responsibility: display current session + aggregated stats; handle radio-button period switching.

**Location:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java` — add as a new tab

**UI structure:**
```
┌──────────────────────────────────────────────────────┐
│  [Daily] [Weekly] [Monthly] [All-Time]      [Reset]  │
├──────────────────────────────────────────────────────┤
│ CURRENT SESSION (only when logged in)                │
│ Logged in: 2h 34m (started 2:15 PM)                 │
│ Script active: 1h 45m    Idle: 49m                  │
├──────────────────────────────────────────────────────┤
│ Script Breakdown:                                    │
│ ─────────────────────────────────────────────────── │
│ Chicken Farm V3    1h 12m    42 kills               │
│ Cooking V3         33m       180 cooked              │
│ Mining             —          —                      │
│ ─────────────────────────────────────────────────── │
│ [scroll area for many scripts]                       │
└──────────────────────────────────────────────────────┘
```

**On logout,** display updates to show the finalized session (login time, script active, idle, script breakdown).

**Per-period display** (Daily/Weekly/Monthly/All-Time):
- Shows totals for the selected period (sum of all login sessions, all script runs, etc.)
- If multiple login sessions in the period, shows aggregated time across all of them

**Key methods:**
- `setPeriod(TimePeriod)` — switch between Daily/Weekly/Monthly/AllTime; re-render stats
- `onSessionStateChanged()` — called when SessionTracker emits session updates; refreshes current-session display
- `refreshStats()` — queries SessionStore for aggregated data; updates labels/tables

**Timer:** Uses a Swing `Timer` to refresh every 1 second while logged in (updates "Logged in: Xh Ym" in real-time).

---

## Data Flow

### Login → Session Start

1. Player logs in → `GameStateChanged(LOGGED_IN)` fires
2. RecorderPlugin forwards to SessionTracker
3. SessionTracker:
   - Records `loginTime = System.currentTimeMillis()`
   - Creates new `LoginSession` with `sessionId = loginTime`
   - Initializes empty `runs` list
   - Notifies StatsPanel via callback
4. StatsPanel displays "Logged in: 0h 0m (started HH:MM AM/PM)" and "Script active: 0m"
5. 1-second refresh timer starts (updates login/idle times)

### Script Activation

1. Script starts (e.g., ChickenFarmV3 begins) and calls:
   ```java
   sessionTracker.onScriptStarted("chicken_farm_v3", "Chicken Farm V3");
   ```
2. SessionTracker:
   - Creates `ActiveScriptState` for this script
   - Records `startMs = now`
   - Adds to `activeScripts` map
3. StatsPanel updates to show "Chicken Farm V3: 0m" (0 duration so far)

### Script Deactivation

1. Script stops (e.g., ChickenFarmV3 ends or is manually stopped) and calls:
   ```java
   sessionTracker.onScriptStopped("chicken_farm_v3", 42, "kills");
   ```
2. SessionTracker:
   - Finalizes the run: `ScriptRun(scriptId, displayName, startMs, endMs=now, count, countLabel)`
   - Appends to `currentLoginSession.runs`
   - Removes from `activeScripts`
   - **Saves session to disk** (atomic write to `<date>.json.tmp`, then move to `<date>.json`)
3. StatsPanel updates label: "Chicken Farm V3: 45m 42 kills"

### Logout → Session End

1. Player logs out → `GameStateChanged(LOGGED_OUT)` fires
2. SessionTracker:
   - Records `logoutTime = System.currentTimeMillis()`
   - Finalizes any still-running scripts (treat as stopped with null count)
   - Writes `currentLoginSession` to disk (atomic write)
   - Clears `currentLoginSession` and `activeScripts`
   - Notifies StatsPanel via callback
3. StatsPanel:
   - Clears "Logged in: ..." display
   - Calls `SessionStore.aggregateDaily(accountName, today)` to refresh stats
   - Displays cumulative totals for the day

### Periodic Saving

In addition to on-logout, SessionTracker saves every 60 seconds while logged in. This ensures data survives client crashes.

### UI Period Switching

1. User clicks "Weekly" radio button
2. StatsPanel:
   - Calls `SessionStore.aggregateWeekly(accountName, selectedDate)`
   - Refreshes script breakdown display
   - Title changes: "THIS WEEK — [Reset button]" (reset disabled for non-Daily)

### Reset Button

1. User clicks "Reset Today" (only enabled in Daily view)
2. StatsPanel:
   - Calls `SessionStore.deleteSession(accountName, today)`
   - Clears in-memory current session (if logged in)
   - Refreshes display

---

## Integration Points

### RecorderPlugin

Add to startup:
```java
sessionTracker = new SessionTracker(client, clientThread);
eventBus.register(sessionTracker);  // subscribes to GameStateChanged

// Wire into panel
recorderPanel.setSessionTracker(sessionTracker, sessionStore);
```

### RecorderPanel

Add new tab:
```java
private StatsPanel statsPanel;

// In constructor or build method:
statsPanel = new StatsPanel(sessionTracker, sessionStore, client, configManager);
tabs.addTab("Stats", statsPanel);
```

### Script Event Detection

Existing scripts already manage their own state transitions (e.g., ChickenFarmV3 → IDLE → RUNNING → IDLE). SessionTracker subscribes to script lifecycle events via a callback interface.

**Add to SessionTracker:**
```java
interface ScriptLifecycleListener {
  void onScriptStarted(String scriptId, String displayName);
  void onScriptStopped(String scriptId, Integer count, String countLabel);
}
```

**Each script calls:**
- `sessionTracker.onScriptStarted("chicken_farm_v3", "Chicken Farm V3")` on start
- `sessionTracker.onScriptStopped("chicken_farm_v3", 42, "kills")` on stop/death (count can be null)

Scripts are responsible for calling these methods at the right times. Do NOT observe UI labels; labels are presentation only.

---

## File Organization

```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/
  session/
    SessionTracker.java           (GameStateChanged listener, script lifecycle callbacks)
    SessionStore.java             (JSON read/write, aggregation queries)
    SessionStats.java             (aggregated result record)
    LoginSession.java             (in-memory session + runs)
    ScriptRun.java                (single script run record)
    ScriptStats.java              (aggregated per-script stats)
    TimePeriod.java               (enum: DAILY, WEEKLY, MONTHLY, ALL_TIME)
    ScriptLifecycleListener.java  (interface for script callbacks)

runelite-client/src/test/java/net/runelite/client/plugins/recorder/
  session/
    SessionStoreTest.java         (JSON round-trip, aggregation, midnight crossing)
```

**StatsPanel:** Add to `RecorderPanel.java` (new inner class or separate file in the same directory).

---

## Disk I/O & Durability

### Atomic Writes

All writes to session JSON files use atomic writes to prevent corruption if the client exits mid-write:

```java
Path tmpPath = dateFile.resolveSibling(dateFile.getFileName() + ".tmp");
Files.write(tmpPath, json);
Files.move(tmpPath, dateFile, ATOMIC_MOVE, REPLACE_EXISTING);
```

This ensures the file is either the old version or the new version, never a partial JSON.

### Write Occasions

SessionTracker writes to disk on:
1. **Script stop** — record the completed run
2. **Periodic tick** — every 60 seconds while logged in (survives crashes)
3. **Logout** — finalize the session
4. **Plugin shutdown** — finalize current session if still open

### Midnight Crossing

If a player logs in at 23:30:00 and is still online at 00:30:00 (next day):

- A single `LoginSession` spans both dates (23:30:00 – 00:30:00)
- On save, the session is written to the **day it started** (2026-05-19.json in this example)
- Script runs that cross midnight are NOT split; they live in the file of the day the session started
- Aggregation (weekly, monthly, all-time) includes runs even if they cross midnight

**Rationale:** Sessions are atomic units. A player who logs in once and plays for 2 hours across midnight should not have their run split into two files. Midnight boundaries are only used for file organization, not for splitting runs.

---

## Error Handling

- **Missing account name:** Use "default" if `client.getUsername()` is empty at login time
- **Malformed JSON:** Log warning; treat file as corrupted; skip that day's data
- **File write failure:** Log error; data lost for that session (acceptable — rare scenario)
- **Directory doesn't exist:** Create `~/.runelite/recorder/sessions/<accountName>/` on first save

---

## Testing

### Unit Tests (SessionStoreTest)

1. **JSON round-trip:** Create SessionState → save → load → verify fields match
2. **Aggregation logic:**
   - Two sessions on same day → dailyTotal = sum of both
   - Sessions across a week (Mon–Fri) → weeklyTotal = sum of all five days
   - Empty month → returns zero totals
3. **Script deduplication:** Multiple runs of same script → `totalMs` and `totalCount` aggregate correctly
4. **File cleanup:** saveSession overwrites existing file without duplication

### Integration Tests (manual, in-game)

1. **Login tracking:** Log in → verify login time recorded; log out → verify JSON file created
2. **Script tracking:** Start ChickenFarmV3 → verify "0m" updates every tick → stop with 42 kills → verify "45m 42 kills"
3. **Period switching:** Accumulate 2 days of data → Daily view shows today only; Weekly shows both days
4. **Reset button:** Click Reset → today's file deleted; UI clears

---

## Future Extensions

- Charts (duration per script over time, kill/ore trends)
- Export to CSV
- Per-script filtering (show only scripts run this month)
- Comparison (this week vs. last week)

---

## Open Questions / Decisions

**None.** Design is fully specified.
