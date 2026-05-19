# Session & Script Runtime Tracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a per-account session tracker that logs login duration, script runtimes (daily/weekly/monthly/all-time), persists to JSON files, and displays in a new Stats tab in RecorderPanel with crash recovery via open-run persistence.

**Architecture:** Three core components — SessionTracker (detects login/logout, receives script lifecycle callbacks, saves periodically); SessionStore (reads/writes JSON files, aggregates across days, handles open runs); StatsPanel (UI tab with radio-button period switching, displays current session and aggregated stats).

**Tech Stack:** Java 17, Gradle (existing build), Swing (UI), JSON (Jackson for serialization), JUnit 5 (tests).

---

## File Structure

**New files (runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/):**
- `SessionTracker.java` — login/logout detection, script lifecycle, periodic saves
- `SessionStore.java` — JSON I/O, aggregation queries
- `LoginSession.java` — record: session identity + runs (immutable)
- `ScriptRun.java` — record: single script run (immutable)
- `ScriptStats.java` — record: aggregated per-script stats (immutable)
- `SessionStats.java` — record: aggregated session stats (immutable)
- `TimePeriod.java` — enum: DAILY, WEEKLY, MONTHLY, ALL_TIME
- `ScriptLifecycleListener.java` — interface for script callbacks

**New test file:**
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/session/SessionStoreTest.java`

**Modified files:**
- `RecorderPanel.java` — add StatsPanel as new tab, wire SessionTracker callbacks
- `RecorderPlugin.java` — instantiate SessionTracker, register for events, inject into panel

---

## Tasks

### Task 1: Create Data Model Records

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/ScriptRun.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/LoginSession.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/ScriptStats.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionStats.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/TimePeriod.java`

- [ ] **Step 1: Create ScriptRun record**

```java
package net.runelite.client.plugins.recorder.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record ScriptRun(
    @JsonProperty("scriptId") String scriptId,
    @JsonProperty("displayName") String displayName,
    @JsonProperty("startMs") long startMs,
    @JsonProperty("endMs") @Nullable Long endMs,
    @JsonProperty("count") @Nullable Integer count,
    @JsonProperty("countLabel") @Nullable String countLabel
)
{
}
```

- [ ] **Step 2: Create LoginSession record**

```java
package net.runelite.client.plugins.recorder.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;
import java.util.List;

public record LoginSession(
    @JsonProperty("sessionId") String sessionId,
    @JsonProperty("loginTime") long loginTime,
    @JsonProperty("logoutTime") @Nullable Long logoutTime,
    @JsonProperty("lastSavedMs") long lastSavedMs,
    @JsonProperty("runs") List<ScriptRun> runs
)
{
    public long loginDurationMs() {
        if (logoutTime != null) {
            return logoutTime - loginTime;
        }
        return lastSavedMs - loginTime;
    }
}
```

- [ ] **Step 3: Create ScriptStats record**

```java
package net.runelite.client.plugins.recorder.session;

import javax.annotation.Nullable;

public record ScriptStats(
    String scriptId,
    String displayName,
    long totalMs,
    @Nullable Integer totalCount,
    @Nullable String countLabel
)
{
}
```

- [ ] **Step 4: Create SessionStats record**

```java
package net.runelite.client.plugins.recorder.session;

import java.util.Map;

public record SessionStats(
    long totalLoginMs,
    long totalScriptActiveMs,
    long idleMs,
    Map<String, ScriptStats> scripts
)
{
}
```

- [ ] **Step 5: Create TimePeriod enum**

```java
package net.runelite.client.plugins.recorder.session;

public enum TimePeriod {
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    ALL_TIME("All-Time");

    private final String label;

    TimePeriod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
```

- [ ] **Step 6: Commit data model**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/
git commit -m "feat: add session tracker data model records"
```

---

### Task 2: Create ScriptLifecycleListener Interface

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/ScriptLifecycleListener.java`

- [ ] **Step 1: Create interface**

```java
package net.runelite.client.plugins.recorder.session;

import javax.annotation.Nullable;

public interface ScriptLifecycleListener {
    void onScriptStarted(String scriptId, String displayName);

    void onScriptStopped(String scriptId, @Nullable Integer count, @Nullable String countLabel);
}
```

- [ ] **Step 2: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/ScriptLifecycleListener.java
git commit -m "feat: add script lifecycle listener interface"
```

---

### Task 3: Implement SessionStore — JSON I/O

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionStore.java`

- [ ] **Step 1: Create SessionStore skeleton with JSON mapper**

```java
package net.runelite.client.plugins.recorder.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class SessionStore {
    private static final String SESSION_DIR = ".runelite/recorder/sessions";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private final ObjectMapper mapper;
    private final Path baseDir;

    public SessionStore() {
        this(Paths.get(System.getProperty("user.home")).resolve(SESSION_DIR));
    }

    public SessionStore(Path baseDir) {
        this.baseDir = baseDir;
        this.mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    private Path getSessionDirectory(String accountName) {
        return baseDir.resolve(accountName);
    }

    private Path getDayFile(String accountName, LocalDate date) {
        return getSessionDirectory(accountName)
            .resolve(date.format(DATE_FORMAT) + ".json");
    }

    public record DaySessionFile(
        String date,
        List<LoginSession> sessions
    ) {
    }
}
```

- [ ] **Step 2: Implement loadDay()**

Add to SessionStore.java:

```java
    public List<LoginSession> loadDay(String accountName, LocalDate date) {
        Path dayFile = getDayFile(accountName, date);
        if (!Files.exists(dayFile)) {
            return new ArrayList<>();
        }
        try {
            String content = Files.readString(dayFile);
            DaySessionFile dayData = mapper.readValue(content, DaySessionFile.class);
            return dayData.sessions != null ? dayData.sessions : new ArrayList<>();
        } catch (IOException e) {
            log.warn("Failed to load session file {}: {}", dayFile, e.getMessage());
            return new ArrayList<>();
        }
    }
```

- [ ] **Step 3: Implement upsertSession()**

Add to SessionStore.java:

```java
    public void upsertSession(String accountName, LocalDate date, LoginSession session) {
        try {
            Path sessionDir = getSessionDirectory(accountName);
            Files.createDirectories(sessionDir);

            List<LoginSession> sessions = loadDay(accountName, date);

            boolean found = false;
            for (int i = 0; i < sessions.size(); i++) {
                if (sessions.get(i).sessionId().equals(session.sessionId())) {
                    sessions.set(i, session);
                    found = true;
                    break;
                }
            }
            if (!found) {
                sessions.add(session);
            }

            Path dayFile = getDayFile(accountName, date);
            Path tmpFile = dayFile.resolveSibling(dayFile.getFileName() + ".tmp");

            DaySessionFile dayData = new DaySessionFile(date.format(DATE_FORMAT), sessions);
            String json = mapper.writeValueAsString(dayData);
            Files.writeString(tmpFile, json);
            Files.move(tmpFile, dayFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            log.debug("Upserted session {} for {}", session.sessionId(), date);
        } catch (IOException e) {
            log.error("Failed to upsert session for {}: {}", date, e.getMessage(), e);
        }
    }
```

- [ ] **Step 4: Implement deleteDay()**

Add to SessionStore.java:

```java
    public void deleteDay(String accountName, LocalDate date) {
        try {
            Path dayFile = getDayFile(accountName, date);
            Files.deleteIfExists(dayFile);
            log.debug("Deleted session file for {}", date);
        } catch (IOException e) {
            log.error("Failed to delete session file for {}: {}", date, e.getMessage());
        }
    }
```

- [ ] **Step 5: Commit JSON I/O**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionStore.java
git commit -m "feat: implement SessionStore JSON I/O (load/upsert/delete)"
```

---

### Task 4: Implement SessionStore — Aggregation Logic

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionStore.java`

- [ ] **Step 1: Add helper method to merge overlapping intervals**

Add to SessionStore.java:

```java
    private static class Interval {
        final long start;
        final long end;

        Interval(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private long mergeAndSumIntervals(List<Interval> intervals) {
        if (intervals.isEmpty()) {
            return 0;
        }
        intervals.sort((a, b) -> Long.compare(a.start, b.start));

        long totalMs = 0;
        long currentStart = intervals.get(0).start;
        long currentEnd = intervals.get(0).end;

        for (int i = 1; i < intervals.size(); i++) {
            Interval iv = intervals.get(i);
            if (iv.start <= currentEnd) {
                currentEnd = Math.max(currentEnd, iv.end);
            } else {
                totalMs += currentEnd - currentStart;
                currentStart = iv.start;
                currentEnd = iv.end;
            }
        }
        totalMs += currentEnd - currentStart;
        return totalMs;
    }
```

- [ ] **Step 2: Add method to accumulate script stats**

Add to SessionStore.java:

```java
    private ScriptStats accumulateScriptStats(String scriptId, List<ScriptRun> runs) {
        String displayName = "";
        long totalMs = 0;
        int totalCount = 0;
        String countLabel = null;
        boolean hasCount = false;

        for (ScriptRun run : runs) {
            if (!run.scriptId().equals(scriptId)) {
                continue;
            }
            displayName = run.displayName();
            
            if (run.endMs() != null) {
                totalMs += run.endMs() - run.startMs();
            }
            
            if (run.count() != null) {
                totalCount += run.count();
                countLabel = run.countLabel();
                hasCount = true;
            }
        }

        return new ScriptStats(
            scriptId,
            displayName,
            totalMs,
            hasCount ? totalCount : null,
            countLabel
        );
    }
```

- [ ] **Step 3: Add method to aggregate from list of sessions**

Add to SessionStore.java:

```java
    private SessionStats aggregateFromSessions(List<LoginSession> sessions) {
        if (sessions.isEmpty()) {
            return new SessionStats(0, 0, 0, java.util.Map.of());
        }

        long totalLoginMs = 0;
        List<Interval> allRunIntervals = new ArrayList<>();
        java.util.Map<String, ScriptStats> scriptStatsMap = new java.util.HashMap<>();

        for (LoginSession session : sessions) {
            totalLoginMs += session.loginDurationMs();

            for (ScriptRun run : session.runs()) {
                long endMs = run.endMs() != null ? run.endMs() : session.lastSavedMs();
                allRunIntervals.add(new Interval(run.startMs(), endMs));
            }

            for (ScriptRun run : session.runs()) {
                if (!scriptStatsMap.containsKey(run.scriptId())) {
                    scriptStatsMap.put(run.scriptId(), 
                        accumulateScriptStats(run.scriptId(), session.runs()));
                } else {
                    ScriptStats existing = scriptStatsMap.get(run.scriptId());
                    ScriptStats newStats = accumulateScriptStats(run.scriptId(), session.runs());
                    scriptStatsMap.put(run.scriptId(), new ScriptStats(
                        existing.scriptId(),
                        existing.displayName(),
                        existing.totalMs() + newStats.totalMs(),
                        (existing.totalCount() != null && newStats.totalCount() != null) 
                            ? existing.totalCount() + newStats.totalCount() 
                            : null,
                        existing.countLabel()
                    ));
                }
            }
        }

        long totalScriptActiveMs = mergeAndSumIntervals(allRunIntervals);
        long idleMs = totalLoginMs - totalScriptActiveMs;

        return new SessionStats(totalLoginMs, totalScriptActiveMs, idleMs, scriptStatsMap);
    }
```

- [ ] **Step 4: Implement aggregateDaily()**

Add to SessionStore.java:

```java
    public SessionStats aggregateDaily(String accountName, LocalDate date) {
        List<LoginSession> sessions = loadDay(accountName, date);
        return aggregateFromSessions(sessions);
    }
```

- [ ] **Step 5: Implement aggregateWeekly()**

Add to SessionStore.java:

```java
    public SessionStats aggregateWeekly(String accountName, LocalDate midweekDate) {
        LocalDate monday = midweekDate.minusDays(midweekDate.getDayOfWeek().getValue() - 1);

        List<LoginSession> allSessions = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            allSessions.addAll(loadDay(accountName, day));
        }

        return aggregateFromSessions(allSessions);
    }
```

- [ ] **Step 6: Implement aggregateMonthly()**

Add to SessionStore.java:

```java
    public SessionStats aggregateMonthly(String accountName, java.time.YearMonth month) {
        LocalDate startDate = month.atDay(1);
        LocalDate endDate = month.atEndOfMonth();

        List<LoginSession> allSessions = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            allSessions.addAll(loadDay(accountName, date));
        }

        return aggregateFromSessions(allSessions);
    }
```

- [ ] **Step 7: Implement aggregateAllTime()**

Add to SessionStore.java:

```java
    public SessionStats aggregateAllTime(String accountName) {
        Path sessionDir = getSessionDirectory(accountName);
        if (!Files.exists(sessionDir)) {
            return new SessionStats(0, 0, 0, java.util.Map.of());
        }

        List<LoginSession> allSessions = new ArrayList<>();
        try {
            Files.list(sessionDir)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        String dateStr = p.getFileName().toString()
                            .replace(".json", "");
                        LocalDate date = LocalDate.parse(dateStr, DATE_FORMAT);
                        allSessions.addAll(loadDay(accountName, date));
                    } catch (Exception e) {
                        log.warn("Failed to load session file {}", p, e);
                    }
                });
        } catch (IOException e) {
            log.error("Failed to list session directory: {}", e.getMessage());
        }

        return aggregateFromSessions(allSessions);
    }
```

- [ ] **Step 8: Commit aggregation logic**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionStore.java
git commit -m "feat: implement SessionStore aggregation (daily/weekly/monthly/all-time)"
```

---

### Task 5: Implement SessionTracker — Login/Logout Detection

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionTracker.java`

- [ ] **Step 1: Create SessionTracker class skeleton**

```java
package net.runelite.client.plugins.recorder.session;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import javax.annotation.Nullable;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

@Slf4j
public class SessionTracker implements ScriptLifecycleListener {
    private final Client client;
    private final ClientThread clientThread;
    private final SessionStore sessionStore;

    @Nullable private LoginSession currentLoginSession;
    private final Map<String, ScriptRun> activeRuns = new HashMap<>();
    private Timer periodicSaveTimer;
    private final List<Runnable> sessionStateCallbacks = new ArrayList<>();

    public SessionTracker(Client client, ClientThread clientThread, SessionStore sessionStore) {
        this.client = client;
        this.clientThread = clientThread;
        this.sessionStore = sessionStore;
    }

    public void registerSessionStateCallback(Runnable callback) {
        sessionStateCallbacks.add(callback);
    }

    private void notifyStateChanged() {
        sessionStateCallbacks.forEach(Runnable::run);
    }

    public @Nullable LoginSession getCurrentSession() {
        return currentLoginSession;
    }
}
```

- [ ] **Step 2: Implement login/logout detection**

Add to SessionTracker.java:

```java
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            onLogin();
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            onLogout();
        }
    }

    private void onLogin() {
        String accountName = client.getUsername();
        if (accountName == null || accountName.isEmpty()) {
            accountName = "default";
        }

        long loginTime = System.currentTimeMillis();
        String sessionId = String.valueOf(loginTime);

        currentLoginSession = new LoginSession(
            sessionId,
            loginTime,
            null,
            loginTime,
            new ArrayList<>()
        );

        log.debug("Session started: {} at {}", accountName, loginTime);
        notifyStateChanged();
        startPeriodicSaves(accountName);
    }

    private void onLogout() {
        if (currentLoginSession == null) {
            return;
        }

        long logoutTime = System.currentTimeMillis();
        String accountName = client.getUsername();
        if (accountName == null || accountName.isEmpty()) {
            accountName = "default";
        }

        finalizeAllActiveRuns(logoutTime);

        currentLoginSession = new LoginSession(
            currentLoginSession.sessionId(),
            currentLoginSession.loginTime(),
            logoutTime,
            logoutTime,
            currentLoginSession.runs()
        );

        LocalDate sessionDate = LocalDate.now(ZoneId.systemDefault());
        sessionStore.upsertSession(accountName, sessionDate, currentLoginSession);

        log.debug("Session ended: {} at {}", accountName, logoutTime);

        currentLoginSession = null;
        activeRuns.clear();
        stopPeriodicSaves();
        notifyStateChanged();
    }

    private void finalizeAllActiveRuns(long endTime) {
        if (currentLoginSession == null) {
            return;
        }

        List<ScriptRun> finalizedRuns = new ArrayList<>(currentLoginSession.runs());

        for (ScriptRun activeRun : activeRuns.values()) {
            ScriptRun finalizedRun = new ScriptRun(
                activeRun.scriptId(),
                activeRun.displayName(),
                activeRun.startMs(),
                endTime,
                activeRun.count(),
                activeRun.countLabel()
            );
            finalizedRuns.add(finalizedRun);
        }

        currentLoginSession = new LoginSession(
            currentLoginSession.sessionId(),
            currentLoginSession.loginTime(),
            currentLoginSession.logoutTime(),
            endTime,
            finalizedRuns
        );

        activeRuns.clear();
    }
```

- [ ] **Step 3: Commit login/logout detection**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionTracker.java
git commit -m "feat: implement SessionTracker login/logout detection"
```

---

### Task 6: Implement SessionTracker — Script Lifecycle Callbacks

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionTracker.java`

- [ ] **Step 1: Implement onScriptStarted()**

Add to SessionTracker.java:

```java
    @Override
    public void onScriptStarted(String scriptId, String displayName) {
        if (currentLoginSession == null) {
            log.warn("Script started but no login session active: {}", scriptId);
            return;
        }

        if (activeRuns.containsKey(scriptId)) {
            log.warn("Script {} already active; ignoring duplicate start", scriptId);
            return;
        }

        long startMs = System.currentTimeMillis();
        ScriptRun activeRun = new ScriptRun(
            scriptId,
            displayName,
            startMs,
            null,
            null,
            null
        );

        activeRuns.put(scriptId, activeRun);
        log.debug("Script started: {} ({})", displayName, scriptId);
        notifyStateChanged();
    }
```

- [ ] **Step 2: Implement onScriptStopped()**

Add to SessionTracker.java:

```java
    @Override
    public void onScriptStopped(String scriptId, @Nullable Integer count, @Nullable String countLabel) {
        if (currentLoginSession == null) {
            log.warn("Script stopped but no login session active: {}", scriptId);
            return;
        }

        ScriptRun activeRun = activeRuns.get(scriptId);
        if (activeRun == null) {
            log.warn("Script {} stopped but was not active", scriptId);
            return;
        }

        long endMs = System.currentTimeMillis();

        ScriptRun finalizedRun = new ScriptRun(
            activeRun.scriptId(),
            activeRun.displayName(),
            activeRun.startMs(),
            endMs,
            count,
            countLabel
        );

        List<ScriptRun> runs = new ArrayList<>(currentLoginSession.runs());
        runs.add(finalizedRun);
        activeRuns.remove(scriptId);

        currentLoginSession = new LoginSession(
            currentLoginSession.sessionId(),
            currentLoginSession.loginTime(),
            currentLoginSession.logoutTime(),
            endMs,
            runs
        );

        long durationMs = endMs - activeRun.startMs();
        log.debug("Script stopped: {} ({}), duration: {}ms, count: {}", 
            activeRun.displayName(), scriptId, durationMs, count);

        String accountName = client.getUsername();
        if (accountName == null || accountName.isEmpty()) {
            accountName = "default";
        }
        LocalDate sessionDate = LocalDate.now(ZoneId.systemDefault());
        sessionStore.upsertSession(accountName, sessionDate, currentLoginSession);

        notifyStateChanged();
    }
```

- [ ] **Step 3: Commit script lifecycle**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionTracker.java
git commit -m "feat: implement SessionTracker script lifecycle callbacks"
```

---

### Task 7: Implement SessionTracker — Periodic Saving & Plugin Shutdown

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionTracker.java`

- [ ] **Step 1: Implement periodic save scheduler**

Add to SessionTracker.java:

```java
    private void startPeriodicSaves(String accountName) {
        if (periodicSaveTimer != null) {
            periodicSaveTimer.cancel();
        }

        periodicSaveTimer = new Timer("SessionTracker-PeriodicSave", true);
        periodicSaveTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                periodicSave(accountName);
            }
        }, 60_000, 60_000);
    }

    private void stopPeriodicSaves() {
        if (periodicSaveTimer != null) {
            periodicSaveTimer.cancel();
            periodicSaveTimer = null;
        }
    }

    private void periodicSave(String accountName) {
        if (currentLoginSession == null) {
            return;
        }

        long now = System.currentTimeMillis();
        List<ScriptRun> runs = new ArrayList<>(currentLoginSession.runs());
        for (ScriptRun activeRun : activeRuns.values()) {
            runs.add(activeRun);
        }

        LoginSession snapshotSession = new LoginSession(
            currentLoginSession.sessionId(),
            currentLoginSession.loginTime(),
            currentLoginSession.logoutTime(),
            now,
            runs
        );

        LocalDate sessionDate = LocalDate.now(ZoneId.systemDefault());
        sessionStore.upsertSession(accountName, sessionDate, snapshotSession);

        log.debug("Periodic save: session {}", currentLoginSession.sessionId());
    }
```

- [ ] **Step 2: Add shutdown method**

Add to SessionTracker.java:

```java
    public void onShutdown(String accountName) {
        stopPeriodicSaves();

        if (currentLoginSession == null) {
            return;
        }

        if (accountName == null || accountName.isEmpty()) {
            accountName = "default";
        }

        long shutdownTime = System.currentTimeMillis();
        finalizeAllActiveRuns(shutdownTime);

        LoginSession finalSession = new LoginSession(
            currentLoginSession.sessionId(),
            currentLoginSession.loginTime(),
            shutdownTime,
            shutdownTime,
            currentLoginSession.runs()
        );

        LocalDate sessionDate = LocalDate.now(ZoneId.systemDefault());
        sessionStore.upsertSession(accountName, sessionDate, finalSession);

        currentLoginSession = null;
        activeRuns.clear();

        log.debug("Session finalized on plugin shutdown");
    }
```

- [ ] **Step 3: Commit periodic saves**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/session/SessionTracker.java
git commit -m "feat: implement SessionTracker periodic saves and shutdown handling"
```

---

### Task 8: Implement StatsPanel UI

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`

- [ ] **Step 1: Add StatsPanel inner class (full implementation)**

Add to RecorderPanel.java (inside the class, after field declarations):

```java
    private static class StatsPanel extends JPanel {
        private final SessionTracker sessionTracker;
        private final SessionStore sessionStore;
        private final Client client;
        private TimePeriod currentPeriod = TimePeriod.DAILY;
        private final JLabel loginStatusLabel = new JLabel("Not logged in");
        private final JLabel scriptActiveLabel = new JLabel("Script active: —");
        private final JLabel idleLabel = new JLabel("Idle: —");
        private final JTextArea statsTextArea = new JTextArea(15, 40);
        private final JButton resetBtn = new JButton("Reset Today");
        private Timer refreshTimer;

        StatsPanel(SessionTracker sessionTracker, SessionStore sessionStore, Client client) {
            this.sessionTracker = sessionTracker;
            this.sessionStore = sessionStore;
            this.client = client;
            initUI();
            startRefreshTimer();
        }

        private void initUI() {
            setLayout(new BorderLayout(4, 4));
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

            JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
            ButtonGroup periodGroup = new ButtonGroup();
            for (TimePeriod period : TimePeriod.values()) {
                JRadioButton rb = new JRadioButton(period.getLabel());
                rb.addActionListener(e -> {
                    currentPeriod = period;
                    refreshStats();
                });
                periodGroup.add(rb);
                topPanel.add(rb);
                if (period == TimePeriod.DAILY) {
                    rb.setSelected(true);
                }
            }

            resetBtn.addActionListener(e -> resetToday());
            topPanel.add(Box.createHorizontalStrut(20));
            topPanel.add(resetBtn);

            add(topPanel, BorderLayout.NORTH);

            JPanel sessionPanel = new JPanel(new BoxLayout(new JPanel(), BoxLayout.Y_AXIS));
            sessionPanel.setBorder(BorderFactory.createTitledBorder("Current Session"));
            sessionPanel.add(loginStatusLabel);
            sessionPanel.add(scriptActiveLabel);
            sessionPanel.add(idleLabel);

            statsTextArea.setEditable(false);
            statsTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane statsScroll = new JScrollPane(statsTextArea);

            JPanel centerPanel = new JPanel(new BorderLayout(0, 4));
            centerPanel.add(sessionPanel, BorderLayout.NORTH);
            centerPanel.add(statsScroll, BorderLayout.CENTER);

            add(centerPanel, BorderLayout.CENTER);
        }

        private void startRefreshTimer() {
            refreshTimer = new Timer(1000, e -> refreshStats());
            refreshTimer.start();
        }

        private void stopRefreshTimer() {
            if (refreshTimer != null) {
                refreshTimer.stop();
            }
        }

        private void refreshStats() {
            LoginSession currentSession = sessionTracker.getCurrentSession();
            String accountName = client.getUsername();
            if (accountName == null || accountName.isEmpty()) {
                accountName = "default";
            }

            resetBtn.setEnabled(currentSession == null);

            if (currentSession != null) {
                long loginDurationMs = System.currentTimeMillis() - currentSession.loginTime();
                long hours = loginDurationMs / (1000 * 60 * 60);
                long mins = (loginDurationMs % (1000 * 60 * 60)) / (1000 * 60);
                loginStatusLabel.setText(String.format("Logged in: %dh %dm", hours, mins));
            } else {
                loginStatusLabel.setText("Not logged in");
                scriptActiveLabel.setText("Script active: —");
                idleLabel.setText("Idle: —");
            }

            SessionStats stats = null;
            LocalDate today = LocalDate.now();
            switch (currentPeriod) {
                case DAILY:
                    stats = sessionStore.aggregateDaily(accountName, today);
                    break;
                case WEEKLY:
                    stats = sessionStore.aggregateWeekly(accountName, today);
                    break;
                case MONTHLY:
                    stats = sessionStore.aggregateMonthly(accountName, java.time.YearMonth.now());
                    break;
                case ALL_TIME:
                    stats = sessionStore.aggregateAllTime(accountName);
                    break;
            }

            if (stats != null) {
                updateStatsDisplay(stats);
            }
        }

        private void updateStatsDisplay(SessionStats stats) {
            long hours = stats.totalLoginMs() / (1000 * 60 * 60);
            long mins = (stats.totalLoginMs() % (1000 * 60 * 60)) / (1000 * 60);

            long activeHours = stats.totalScriptActiveMs() / (1000 * 60 * 60);
            long activeMins = (stats.totalScriptActiveMs() % (1000 * 60 * 60)) / (1000 * 60);

            long idleHours = stats.idleMs() / (1000 * 60 * 60);
            long idleMins = (stats.idleMs() % (1000 * 60 * 60)) / (1000 * 60);

            scriptActiveLabel.setText(String.format("Script active: %dh %dm", activeHours, activeMins));
            idleLabel.setText(String.format("Idle: %dh %dm", idleHours, idleMins));

            StringBuilder sb = new StringBuilder();
            sb.append("SCRIPT BREAKDOWN\n");
            sb.append("─────────────────────────────────────\n");

            for (ScriptStats scriptStat : stats.scripts().values()) {
                long scriptHours = scriptStat.totalMs() / (1000 * 60 * 60);
                long scriptMins = (scriptStat.totalMs() % (1000 * 60 * 60)) / (1000 * 60);

                sb.append(String.format("%-25s %3dh %2dm", scriptStat.displayName(), scriptHours, scriptMins));
                if (scriptStat.totalCount() != null) {
                    sb.append(String.format("  %d %s", scriptStat.totalCount(), scriptStat.countLabel()));
                }
                sb.append("\n");
            }

            sb.append("─────────────────────────────────────\n");
            sb.append(String.format("TOTAL              %3dh %2dm\n", hours, mins));

            statsTextArea.setText(sb.toString());
        }

        private void resetToday() {
            LoginSession current = sessionTracker.getCurrentSession();
            if (current != null) {
                JOptionPane.showMessageDialog(this, 
                    "Cannot reset while logged in.",
                    "Reset Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            String accountName = client.getUsername();
            if (accountName == null || accountName.isEmpty()) {
                accountName = "default";
            }

            int confirm = JOptionPane.showConfirmDialog(this,
                "Delete today's session data?",
                "Confirm Reset",
                JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                sessionStore.deleteDay(accountName, LocalDate.now());
                refreshStats();
            }
        }
    }
```

- [ ] **Step 2: Add fields and initialization to RecorderPanel**

Add to RecorderPanel class fields:

```java
    private SessionTracker sessionTracker;
    private SessionStore sessionStore;
    private StatsPanel statsPanel;
```

In RecorderPanel constructor, after tabs are created, add:

```java
    statsPanel = new StatsPanel(null, null, client); // Will be set via setSessionTracker
    tabs.addTab("Stats", statsPanel);
```

- [ ] **Step 3: Add setter for SessionTracker**

Add to RecorderPanel class:

```java
    public void setSessionTracker(SessionTracker sessionTracker, SessionStore sessionStore) {
        this.sessionTracker = sessionTracker;
        this.sessionStore = sessionStore;
        
        // Recreate StatsPanel with actual instances
        int statsTabIndex = tabs.indexOfTab("Stats");
        statsPanel = new StatsPanel(sessionTracker, sessionStore, client);
        tabs.setComponentAt(statsTabIndex, statsPanel);
        
        sessionTracker.registerSessionStateCallback(() -> {
            if (statsPanel != null) {
                statsPanel.refreshStats();
            }
        });
    }
```

- [ ] **Step 4: Commit StatsPanel**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java
git commit -m "feat: implement StatsPanel UI with period switching and reset"
```

---

### Task 9: Wire SessionTracker into RecorderPlugin

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java`

- [ ] **Step 1: Add fields to RecorderPlugin**

Add to RecorderPlugin class fields:

```java
    private SessionTracker sessionTracker;
    private SessionStore sessionStore;
```

- [ ] **Step 2: Initialize in onStartup()**

In RecorderPlugin.onStartup() method, add after eventBus is registered:

```java
    sessionStore = new SessionStore();
    sessionTracker = new SessionTracker(client, clientThread, sessionStore);
    eventBus.register(sessionTracker);
    panel.setSessionTracker(sessionTracker, sessionStore);
```

- [ ] **Step 3: Call shutdown in onStop()**

In RecorderPlugin.onStop() method, add before any return:

```java
    if (sessionTracker != null) {
        String accountName = client.getUsername();
        if (accountName == null) {
            accountName = "default";
        }
        sessionTracker.onShutdown(accountName);
    }
```

- [ ] **Step 4: Commit wiring**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java
git commit -m "feat: wire SessionTracker into RecorderPlugin lifecycle"
```

---

### Task 10: Write Unit Tests for SessionStore

**Files:**
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/session/SessionStoreTest.java`

- [ ] **Step 1: Create test class**

```java
package net.runelite.client.plugins.recorder.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    @TempDir
    Path tempDir;

    private SessionStore store;

    @BeforeEach
    void setUp() {
        store = new SessionStore(tempDir);
    }

    @Test
    void testJsonRoundTrip() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String accountName = "testaccount";

        List<ScriptRun> runs = List.of(
            new ScriptRun("chicken_farm_v3", "Chicken Farm V3", 1000, 2000L, 42, "kills"),
            new ScriptRun("cooking_v3", "Cooking V3", 2100, 3100L, 180, "cooked")
        );

        LoginSession session = new LoginSession(
            "1000",
            1000,
            2200L,
            2200,
            runs
        );

        store.upsertSession(accountName, date, session);
        List<LoginSession> loaded = store.loadDay(accountName, date);

        assertEquals(1, loaded.size());
        LoginSession loadedSession = loaded.get(0);
        assertEquals(session.sessionId(), loadedSession.sessionId());
        assertEquals(2, loadedSession.runs().size());
    }

    @Test
    void testMultipleSessionsPerDay() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String accountName = "testaccount";

        LoginSession session1 = new LoginSession(
            "1000",
            1000,
            2000L,
            2000,
            List.of(new ScriptRun("mining", "Mining", 1000, 2000L, 50, "ores"))
        );

        LoginSession session2 = new LoginSession(
            "3000",
            3000,
            4000L,
            4000,
            List.of(new ScriptRun("mining", "Mining", 3000, 4000L, 60, "ores"))
        );

        store.upsertSession(accountName, date, session1);
        store.upsertSession(accountName, date, session2);

        List<LoginSession> loaded = store.loadDay(accountName, date);
        assertEquals(2, loaded.size());
    }

    @Test
    void testAggregationDaily() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String accountName = "testaccount";

        LoginSession session = new LoginSession(
            "1000",
            1000,
            3000L,
            3000,
            List.of(
                new ScriptRun("chicken_farm_v3", "Chicken Farm V3", 1000, 2000L, 42, "kills"),
                new ScriptRun("mining", "Mining", 2000, 3000L, 50, "ores")
            )
        );

        store.upsertSession(accountName, date, session);
        SessionStats stats = store.aggregateDaily(accountName, date);

        assertEquals(2000, stats.totalLoginMs());
        assertEquals(2000, stats.totalScriptActiveMs());
        assertEquals(0, stats.idleMs());
        assertEquals(2, stats.scripts().size());
    }

    @Test
    void testOverlappingScripts() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String accountName = "testaccount";

        LoginSession session = new LoginSession(
            "1000",
            1000,
            3000L,
            3000,
            List.of(
                new ScriptRun("script_a", "Script A", 1000, 2000L, null, null),
                new ScriptRun("script_b", "Script B", 1500, 2500L, null, null)
            )
        );

        store.upsertSession(accountName, date, session);
        SessionStats stats = store.aggregateDaily(accountName, date);

        assertEquals(1500, stats.totalScriptActiveMs());
        assertEquals(500, stats.idleMs());
    }

    @Test
    void testUpsertReplaces() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String accountName = "testaccount";

        LoginSession session1 = new LoginSession(
            "1000",
            1000,
            2000L,
            2000,
            List.of(new ScriptRun("mining", "Mining", 1000, 2000L, 10, "ores"))
        );

        store.upsertSession(accountName, date, session1);

        LoginSession session1Updated = new LoginSession(
            "1000",
            1000,
            2000L,
            2000,
            List.of(new ScriptRun("mining", "Mining", 1000, 2000L, 20, "ores"))
        );

        store.upsertSession(accountName, date, session1Updated);

        List<LoginSession> loaded = store.loadDay(accountName, date);
        assertEquals(1, loaded.size());
        assertEquals(20, loaded.get(0).runs().get(0).count());
    }

    @Test
    void testOpenRuns() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String accountName = "testaccount";

        LoginSession session = new LoginSession(
            "1000",
            1000,
            null,
            2500,
            List.of(
                new ScriptRun("mining", "Mining", 1000, null, null, null)
            )
        );

        store.upsertSession(accountName, date, session);
        SessionStats stats = store.aggregateDaily(accountName, date);

        assertEquals(1500, stats.totalScriptActiveMs());
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew :client:test --tests SessionStoreTest -v
```

Expected: All tests PASS

- [ ] **Step 3: Commit tests**

```bash
git add runelite-client/src/test/java/net/runelite/client/plugins/recorder/session/SessionStoreTest.java
git commit -m "feat: add SessionStore unit tests (JSON I/O, aggregation, crash recovery)"
```

---

### Task 11: Manual Integration Test

**Files:**
- None (manual test)

- [ ] **Step 1: Run the plugin and test login tracking**

1. Build: `JAVA_HOME=$(dirname /opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java)/.. ./gradlew :client:shadowJar`
2. Run RuneLite via CLAUDE.md instructions
3. Log in to an account
4. Open the Stats tab in RecorderPanel
5. Verify "Logged in: 0h 0m" updates every second

- [ ] **Step 2: Test script tracking**

1. Start a script (e.g., ChickenFarmV3 or Mining)
2. Verify script appears in the breakdown with increasing duration
3. Wait 1-2 minutes, stop the script
4. Verify final duration and count (kills/ores) are recorded
5. Start a second script; verify both are tracked

- [ ] **Step 3: Test session persistence**

1. Log out
2. Check `~/.runelite/recorder/sessions/<accountname>/YYYY-MM-DD.json` exists
3. Verify JSON contains all runs, durations, counts
4. Log back in; verify new session is appended (multiple sessions in same file)

- [ ] **Step 4: Test period switching**

1. Click "Weekly" radio button; verify stats update (aggregates 7 days)
2. Click "Monthly"; verify stats update
3. Click "All-Time"; verify stats update
4. Return to "Daily"

- [ ] **Step 5: Test reset button**

1. Log out (reset button should be enabled)
2. Click reset; confirm deletion dialog
3. Verify session file deleted
4. Stats reset to 0h 0m

- [ ] **Step 6: Test crash recovery**

1. Log in, start a script for 30 seconds
2. Force-quit client (Ctrl+C)
3. Reopen; check stats from before crash
4. Crashed script should show duration up to last periodic save (within 60s)

- [ ] **Step 7: Commit integration test notes**

```bash
git commit --allow-empty -m "docs: manual integration tests pass — all features verified"
```

---

## Summary

**11 tasks** → **~60 minutes** of implementation

- Tasks 1–2: Data model + interfaces (5 min)
- Tasks 3–4: SessionStore I/O + aggregation (15 min)
- Tasks 5–7: SessionTracker login/lifecycle/saves (15 min)
- Task 8: StatsPanel UI (10 min)
- Task 9: Wire plugin (5 min)
- Task 10: Unit tests (5 min)
- Task 11: Manual integration test (5 min)

Each task has full code; no placeholders. Ready to execute.
