package net.runelite.client.plugins.recorder.session;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

@Slf4j
public class SessionTracker implements ScriptLifecycleListener {
    /** Callback for downstream consumers (e.g. RecorderManager) that need to
     *  know when the user's input source flips between "operator at keyboard"
     *  and "registered script driving the dispatcher". Called on the same
     *  thread as the underlying transition (event-bus for login/logout,
     *  poller thread for registered-script transitions, caller thread for
     *  direct {@link #onScriptStarted}/{@link #onScriptStopped} invocations).
     *  Implementations MUST NOT block — the tracker swallows any thrown
     *  exception so a listener fault never propagates back into script
     *  lifecycle handling. */
    public interface ScriptModeListener {
        /** {@code mode} is one of {@code "live"} or {@code "bot_watch"}.
         *  {@code scriptId} is the script driving inputs, or null if
         *  {@code mode == "live"}. */
        void onModeChanged(String mode, @Nullable String scriptId);
    }

    private final Client client;
    private final ClientThread clientThread;
    private final SessionStore sessionStore;

    private static final String DEFAULT_ACCOUNT = "default";

    private final Object lock = new Object();
    @Nullable private LoginSession currentLoginSession;
    @Nullable private String currentAccountName;
    @Nullable private LocalDate currentSessionDate;
    private final Map<String, ScriptRun> activeRuns = new HashMap<>();
    @Nullable private Timer periodicSaveTimer;
    @Nullable private Timer scriptPollTimer;
    private final CopyOnWriteArrayList<Runnable> sessionStateCallbacks = new CopyOnWriteArrayList<>();
    @Nullable private volatile ScriptModeListener scriptModeListener;

    /** Auto-detected scripts: poll each supplier every second and translate false→true / true→false
     *  into onScriptStarted / onScriptStopped. Lets callers register a script once instead of
     *  threading callbacks through every Start/Stop button. */
    private record RegisteredScript(String scriptId, String displayName, BooleanSupplier isRunning) {}
    private final CopyOnWriteArrayList<RegisteredScript> scriptRegistry = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Boolean> lastScriptState = new ConcurrentHashMap<>();

    public SessionTracker(Client client, ClientThread clientThread, SessionStore sessionStore) {
        this.client = client;
        this.clientThread = clientThread;
        this.sessionStore = sessionStore;
    }

    public void registerSessionStateCallback(Runnable callback) {
        sessionStateCallbacks.add(callback);
    }

    /** Wire a {@link ScriptModeListener}. Passing null clears the existing
     *  listener. Only one listener is supported (the recorder); add a
     *  list-based dispatch if that ever changes. */
    public void setScriptModeListener(@Nullable ScriptModeListener listener) {
        this.scriptModeListener = listener;
    }

    /** Returns the id of any currently-running registered script, or null if
     *  none are active. When multiple scripts run concurrently (rare), an
     *  arbitrary one is returned — callers treating this as "the mode driver"
     *  accept that ambiguity. Safe to call from any thread. */
    @Nullable
    public String activeScriptId() {
        synchronized (lock) {
            return activeRuns.isEmpty() ? null : activeRuns.keySet().iterator().next();
        }
    }

    private void notifyScriptMode(String mode, @Nullable String scriptId) {
        ScriptModeListener l = scriptModeListener;
        if (l == null) return;
        try {
            l.onModeChanged(mode, scriptId);
        } catch (Throwable t) {
            log.warn("ScriptModeListener threw on mode={} script={}: {}", mode, scriptId, t.toString());
        }
    }

    /** Register a bot script for auto-tracking. The tracker polls {@code isRunning} every second;
     *  a false→true transition fires {@link #onScriptStarted}, true→false fires {@link #onScriptStopped}.
     *  Safe to call before login — pre-session transitions are silently ignored. */
    public void registerScript(String scriptId, String displayName, BooleanSupplier isRunning) {
        scriptRegistry.add(new RegisteredScript(scriptId, displayName, isRunning));
        lastScriptState.put(scriptId, safeIsRunning(isRunning));
        startScriptPollTimerIfNeeded();
    }

    private static boolean safeIsRunning(BooleanSupplier s) {
        try { return s.getAsBoolean(); } catch (Exception e) { return false; }
    }

    private synchronized void startScriptPollTimerIfNeeded() {
        if (scriptPollTimer != null) return;
        scriptPollTimer = new Timer("SessionTracker-ScriptPoll", true);
        scriptPollTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                try { pollScripts(); } catch (Exception e) { log.warn("Script poll failed", e); }
            }
        }, 1000L, 1000L);
    }

    private void pollScripts() {
        // Only fire transitions while logged in — otherwise the tracker silently swallows them
        // (and would clutter the log with "no session active" warnings).
        if (getCurrentSession() == null) {
            // Still refresh baseline so a script started pre-login but stopped post-login doesn't fire a spurious stop.
            for (RegisteredScript rs : scriptRegistry) {
                lastScriptState.put(rs.scriptId, safeIsRunning(rs.isRunning));
            }
            return;
        }
        for (RegisteredScript rs : scriptRegistry) {
            boolean now = safeIsRunning(rs.isRunning);
            Boolean prev = lastScriptState.put(rs.scriptId, now);
            if (prev == null || prev == now) continue;
            if (now) onScriptStarted(rs.scriptId, rs.displayName);
            else onScriptStopped(rs.scriptId, null, null);
        }
    }

    private void notifyStateChanged() {
        for (Runnable cb : sessionStateCallbacks) {
            try {
                cb.run();
            } catch (Exception e) {
                log.warn("Session state callback threw", e);
            }
        }
    }

    @Nullable
    public LoginSession getCurrentSession() {
        synchronized (lock) {
            return currentLoginSession;
        }
    }

    @Nullable
    public String getCurrentAccountName() {
        synchronized (lock) {
            return currentAccountName;
        }
    }

    @Nullable
    public LocalDate getCurrentSessionDate() {
        synchronized (lock) {
            return currentSessionDate;
        }
    }

    /** Live snapshot of the current session including any active runs (open, endMs=null, lastSavedMs=now).
     *  Used by the UI to show live "Script active" time without waiting for the 60s periodic flush.
     *  Returns null when no session is active. */
    @Nullable
    public LoginSession getCurrentSnapshot() {
        synchronized (lock) {
            if (currentLoginSession == null) return null;
            long now = System.currentTimeMillis();
            List<ScriptRun> runs = new ArrayList<>(currentLoginSession.runs());
            for (ScriptRun activeRun : activeRuns.values()) {
                runs.add(activeRun);
            }
            return new LoginSession(
                currentLoginSession.sessionId(),
                currentLoginSession.loginTime(),
                currentLoginSession.logoutTime(),
                now,
                runs
            );
        }
    }

    private String accountNameOrDefault() {
        // Prefer the in-game character display name (set after the game world finishes loading,
        // a couple of ticks past LOGGED_IN). Fall back to the login credential, then default.
        // Reading client.getLocalPlayer() requires the client thread — callers are either
        // an @Subscribe handler (on the eventbus → client thread) or the periodic Timer
        // (off-thread, where this returns the cached login name or default).
        try {
            Player local = client.getLocalPlayer();
            if (local != null && local.getName() != null && !local.getName().isEmpty()) {
                return local.getName();
            }
        } catch (Exception ignored) {
            // off-thread read can throw; fall through to the safer accessor below
        }
        String login = client.getUsername();
        return (login == null || login.isEmpty()) ? DEFAULT_ACCOUNT : login;
    }

    // ---- Login / logout detection ----

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();
        if (state == GameState.LOGGED_IN) {
            onLogin();
        } else if (state == GameState.LOGIN_SCREEN) {
            onLogout();
        }
    }

    /** Late-resolve the account name. {@code client.getUsername()} is empty when LOGGED_IN
     *  first fires, so {@link #onLogin} initially caches "default". GameTick runs on the client
     *  thread after the game world loads, by which time {@code getLocalPlayer().getName()} is
     *  populated. Once we've nailed it down, this is a no-op. */
    @Subscribe
    public void onGameTick(GameTick event) {
        synchronized (lock) {
            if (currentLoginSession == null) return;
            if (currentAccountName != null && !DEFAULT_ACCOUNT.equals(currentAccountName)) return;
            String resolved = accountNameOrDefault();
            if (resolved != null && !DEFAULT_ACCOUNT.equals(resolved)
                && !resolved.equals(currentAccountName))
            {
                log.info("Session account name late-resolved: {} → {}", currentAccountName, resolved);
                currentAccountName = resolved;
            }
        }
    }

    private void onLogin() {
        boolean isNew;
        String resolvedName;
        synchronized (lock) {
            if (currentLoginSession != null) {
                // Already tracking; LOGGED_IN can fire repeatedly during a session (region transitions etc.)
                return;
            }
            resolvedName = accountNameOrDefault();
            currentAccountName = resolvedName;
            long loginTime = System.currentTimeMillis();
            String sessionId = String.valueOf(loginTime);
            currentLoginSession = new LoginSession(sessionId, loginTime, null, loginTime, new ArrayList<>());
            currentSessionDate = LocalDate.now(ZoneId.systemDefault());
            isNew = true;
        }
        // startPeriodicSaves and notifyStateChanged must run outside the lock
        if (isNew) {
            log.debug("Session started: {} at {}", resolvedName, System.currentTimeMillis());
            startPeriodicSaves();
            // Catch scripts that were running BEFORE login — open a run for each so this
            // session's tracking starts now (we don't know when they actually started).
            for (RegisteredScript rs : scriptRegistry) {
                boolean now = safeIsRunning(rs.isRunning);
                lastScriptState.put(rs.scriptId, now);
                if (now) onScriptStarted(rs.scriptId, rs.displayName);
            }
            notifyStateChanged();
        }
    }

    private void onLogout() {
        String accountName;
        LocalDate sessionDate;
        LoginSession sessionToSave;
        synchronized (lock) {
            if (currentLoginSession == null) {
                return;
            }
            // Use the name + date captured at login; client.getUsername() returns "" at this point,
            // and using "now" as the date would split sessions crossing midnight across two files.
            accountName = currentAccountName != null ? currentAccountName : DEFAULT_ACCOUNT;
            sessionDate = currentSessionDate != null ? currentSessionDate : LocalDate.now(ZoneId.systemDefault());
            long logoutTime = System.currentTimeMillis();

            // finalizeAllActiveRuns mutates currentLoginSession and clears activeRuns; caller holds lock.
            finalizeAllActiveRuns(logoutTime);

            sessionToSave = new LoginSession(
                currentLoginSession.sessionId(),
                currentLoginSession.loginTime(),
                logoutTime,
                logoutTime,
                currentLoginSession.runs()
            );
        }

        // I/O outside the lock
        sessionStore.upsertSession(accountName, sessionDate, sessionToSave);
        log.debug("Session ended: {} at {}", accountName, sessionToSave.logoutTime());

        synchronized (lock) {
            currentLoginSession = null;
            activeRuns.clear();
            // Intentionally keep currentAccountName + currentSessionDate cached so the panel
            // can still display the just-logged-out account's stats. Next login overwrites
            // them (or leaves them if the same account logs back in).
        }
        stopPeriodicSaves();
        notifyStateChanged();
    }

    /** Must be called with {@code lock} already held. Mutates {@code currentLoginSession} and clears {@code activeRuns}. */
    private void finalizeAllActiveRuns(long endTime) {
        if (currentLoginSession == null) return;
        List<ScriptRun> finalizedRuns = new ArrayList<>(currentLoginSession.runs());
        for (ScriptRun activeRun : activeRuns.values()) {
            finalizedRuns.add(new ScriptRun(
                activeRun.scriptId(),
                activeRun.displayName(),
                activeRun.startMs(),
                endTime,
                activeRun.count(),
                activeRun.countLabel()
            ));
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

    // ---- Script lifecycle ----

    @Override
    public void onScriptStarted(String scriptId, String displayName) {
        boolean fired;
        synchronized (lock) {
            if (currentLoginSession == null) {
                log.warn("Script started but no login session active: {}", scriptId);
                return;
            }
            if (activeRuns.containsKey(scriptId)) {
                log.warn("Script {} already active; ignoring duplicate start", scriptId);
                return;
            }
            long startMs = System.currentTimeMillis();
            ScriptRun activeRun = new ScriptRun(scriptId, displayName, startMs, null, null, null);
            activeRuns.put(scriptId, activeRun);
            log.debug("Script started: {} ({})", displayName, scriptId);
            fired = true;
        }
        if (fired) notifyScriptMode("bot_watch", scriptId);
        notifyStateChanged();
    }

    @Override
    public void onScriptStopped(String scriptId, @Nullable Integer count, @Nullable String countLabel) {
        String accountName;
        LocalDate sessionDate;
        LoginSession updatedSession;
        ScriptRun activeRun;
        String nextMode;
        String nextScriptId;
        synchronized (lock) {
            if (currentLoginSession == null) {
                log.warn("Script stopped but no login session active: {}", scriptId);
                return;
            }
            activeRun = activeRuns.remove(scriptId);
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
            updatedSession = new LoginSession(
                currentLoginSession.sessionId(),
                currentLoginSession.loginTime(),
                currentLoginSession.logoutTime(),
                endMs,
                runs
            );
            currentLoginSession = updatedSession;
            accountName = currentAccountName != null ? currentAccountName : DEFAULT_ACCOUNT;
            sessionDate = currentSessionDate != null ? currentSessionDate : LocalDate.now(ZoneId.systemDefault());
            log.debug("Script stopped: {} ({}), duration: {}ms, count: {}",
                activeRun.displayName(), scriptId, endMs - activeRun.startMs(), count);
            // Pick the next mode while holding the lock so concurrent
            // start/stop calls can't observe an inconsistent snapshot.
            if (activeRuns.isEmpty()) {
                nextMode = "live";
                nextScriptId = null;
            } else {
                nextMode = "bot_watch";
                nextScriptId = activeRuns.keySet().iterator().next();
            }
        }
        // I/O outside the lock
        sessionStore.upsertSession(accountName, sessionDate, updatedSession);
        notifyScriptMode(nextMode, nextScriptId);
        notifyStateChanged();
    }

    // ---- Periodic save (crash recovery) ----

    private void startPeriodicSaves() {
        if (periodicSaveTimer != null) {
            periodicSaveTimer.cancel();
        }
        periodicSaveTimer = new Timer("SessionTracker-PeriodicSave", true);
        periodicSaveTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                try {
                    periodicSave();
                } catch (Exception e) {
                    log.warn("Periodic save failed", e);
                }
            }
        }, 60_000L, 60_000L);
    }

    private void stopPeriodicSaves() {
        if (periodicSaveTimer != null) {
            periodicSaveTimer.cancel();
            periodicSaveTimer = null;
        }
    }

    private void periodicSave() {
        LoginSession snapshot;
        String accountName;
        LocalDate sessionDate;
        String sessionId;
        synchronized (lock) {
            if (currentLoginSession == null) return;
            long now = System.currentTimeMillis();
            List<ScriptRun> runs = new ArrayList<>(currentLoginSession.runs());
            for (ScriptRun activeRun : activeRuns.values()) {
                runs.add(activeRun);
            }
            snapshot = new LoginSession(
                currentLoginSession.sessionId(),
                currentLoginSession.loginTime(),
                currentLoginSession.logoutTime(),
                now,
                runs
            );
            accountName = currentAccountName != null ? currentAccountName : DEFAULT_ACCOUNT;
            sessionDate = currentSessionDate != null ? currentSessionDate : LocalDate.now(ZoneId.systemDefault());
            sessionId = currentLoginSession.sessionId();
        }
        // I/O outside the lock
        sessionStore.upsertSession(accountName, sessionDate, snapshot);
        log.debug("Periodic save: session {}", sessionId);
    }

    // ---- Shutdown (plugin stop) ----

    public void onShutdown() {
        stopPeriodicSaves();
        if (scriptPollTimer != null) {
            scriptPollTimer.cancel();
            scriptPollTimer = null;
        }
        String accountName;
        LocalDate sessionDate;
        LoginSession finalSession;
        synchronized (lock) {
            if (currentLoginSession == null) return;
            accountName = currentAccountName != null ? currentAccountName : DEFAULT_ACCOUNT;
            sessionDate = currentSessionDate != null ? currentSessionDate : LocalDate.now(ZoneId.systemDefault());
            long shutdownTime = System.currentTimeMillis();

            // finalizeAllActiveRuns mutates currentLoginSession; caller holds lock.
            finalizeAllActiveRuns(shutdownTime);

            finalSession = new LoginSession(
                currentLoginSession.sessionId(),
                currentLoginSession.loginTime(),
                shutdownTime,
                shutdownTime,
                currentLoginSession.runs()
            );
        }
        // I/O outside the lock
        sessionStore.upsertSession(accountName, sessionDate, finalSession);

        synchronized (lock) {
            currentLoginSession = null;
            activeRuns.clear();
            currentAccountName = null;
            currentSessionDate = null;
        }
        log.debug("Session finalized on plugin shutdown");
    }
}
