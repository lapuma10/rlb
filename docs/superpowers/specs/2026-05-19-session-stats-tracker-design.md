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

Each login session is stored as a JSON file: `~/.runelite/recorder/sessions/<accountname>/<YYYY-MM-DD>.json`

```json
{
  "date": "2026-05-19",
  "loginTime": 1716144123000,
  "logoutTime": 1716161234000,
  "scripts": {
    "ChickenFarmV3": {
      "name": "Chicken Farm V3",
      "totalMs": 3300000,
      "runs": [
        {
          "startMs": 1716144123000,
          "endMs": 1716147423000,
          "count": 42
        }
      ]
    },
    "CookingV3": {
      "name": "Cooking V3",
      "totalMs": 1920000,
      "runs": [
        {
          "startMs": 1716147500000,
          "endMs": 1716149420000,
          "count": 180
        }
      ]
    },
    "MiningLoop": {
      "name": "Mining",
      "totalMs": 1020000,
      "runs": [
        {
          "startMs": 1716149500000,
          "endMs": 1716150520000,
          "count": 84
        }
      ]
    }
  }
}
```

**Fields:**
- `date`: ISO date string (YYYY-MM-DD) — used for file organization and daily boundary detection
- `loginTime`, `logoutTime`: milliseconds since epoch
- `scripts.<name>.totalMs`: cumulative milliseconds across all runs of this script today
- `scripts.<name>.runs`: array of individual run segments (start, end, optional count like kills/ores/items cooked)
- `count`: script-specific metric (kills for combat, ores for mining, items cooked for cooking, etc.) — populated when the script stops/reports completion

### In-Memory Session State

While logged in, `SessionTracker` maintains:
```java
class SessionState {
  long loginTimeMs;
  long currentSessionStartMs;
  Map<String, ScriptRuntime> scripts = new HashMap<>();  // script name → current run state
}

class ScriptRuntime {
  String name;
  long totalMs;
  List<RunSegment> runs;  // all runs in this session
  long currentRunStartMs;  // null if not running
  int currentCount;  // accumulating count (kills, ores, etc.)
}

class RunSegment {
  long startMs, endMs;
  int count;
}
```

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
  long totalLoginMs,
  Map<String, ScriptStats> scripts
) {}

record ScriptStats(
  String name,
  long totalMs,
  int totalCount  // aggregate across all runs in the period
) {}
```

#### 3. StatsPanel (new JPanel)

Responsibility: display current session + aggregated stats; handle radio-button period switching.

**Location:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java` — add as a new tab

**UI structure:**
```
┌─────────────────────────────────────────────────┐
│  [Daily] [Weekly] [Monthly] [All-Time]  [Reset] │
├─────────────────────────────────────────────────┤
│ CURRENT SESSION (only when logged in)           │
│ Logged in: 2h 34m (started 2:15 PM)            │
├─────────────────────────────────────────────────┤
│ Chicken Farm V3    1h 45m    42 kills           │
│ Cooking V3         32m       180 cooked         │
│ Mining             17m       84 ores            │
│ ─────────────────────────────────────────────── │
│ Total              2h 34m                       │
├─────────────────────────────────────────────────┤
│ [scroll area for many scripts]                  │
└─────────────────────────────────────────────────┘
```

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
   - Creates new `SessionState`
   - Notifies StatsPanel via callback
4. StatsPanel displays "Logged in: 0h 0m (started HH:MM AM/PM)"
5. 1-second refresh timer starts

### Script Activation

1. Script starts (e.g., ChickenFarmV3 begins)
2. Script event fires (via RecorderManager or script state transition)
3. SessionTracker records:
   - `sessionState.scripts.get("ChickenFarmV3").currentRunStartMs = now`
4. StatsPanel shows "Chicken Farm V3: 0m" (0 duration so far)

### Script Deactivation

1. Script stops (e.g., ChickenFarmV3 ends or is manually stopped)
2. Script event fires with completion count (e.g., 42 kills)
3. SessionTracker:
   - Records `endMs` for the current run
   - Adds RunSegment to the `runs` list
   - Updates `totalMs`
   - Clears `currentRunStartMs` (script not running)
   - Stores the count (42 kills)
4. StatsPanel updates label: "Chicken Farm V3: 45m 42 kills"

### Logout → Session End

1. Player logs out → `GameStateChanged(LOGGED_OUT)` fires
2. SessionTracker:
   - Records `logoutTime = System.currentTimeMillis()`
   - Calls `SessionStore.saveSession(accountName, sessionState)` on a background worker
   - Clears in-memory session
   - Notifies StatsPanel via callback
3. StatsPanel:
   - Clears "Logged in: ..." display
   - Calls `SessionStore.aggregateDaily(accountName, today)` to refresh stats
   - Displays cumulative totals for the day

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

Existing scripts already emit state changes (e.g., ChickenFarmV3 transitions IDLE → RUNNING → IDLE). SessionTracker listens by:
- Observing script status labels (e.g., chickenStatusLabel, miningStatusLabel)
- Or scripts emit events directly via a callback interface

**Approach:** Wrap script status updates so SessionTracker can observe them. Add a `ScriptLifecycleListener` interface that scripts call on start/stop.

---

## File Organization

```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/
  session/
    SessionTracker.java       (login/logout detection, event forwarding)
    SessionStore.java         (JSON persistence, aggregation queries)
    SessionState.java         (in-memory session record)
    SessionStats.java         (aggregated result record)
    TimePeriod.java           (enum: DAILY, WEEKLY, MONTHLY, ALL_TIME)

runelite-client/src/test/java/net/runelite/client/plugins/recorder/
  session/
    SessionStoreTest.java     (JSON read/write, aggregation logic)
```

StatsPanel lives in RecorderPanel.java (new inner class or method).

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
