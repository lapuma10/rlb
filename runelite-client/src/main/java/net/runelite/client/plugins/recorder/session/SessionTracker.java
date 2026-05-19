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
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class SessionTracker implements ScriptLifecycleListener {
    private final Client client;
    private final ClientThread clientThread;
    private final SessionStore sessionStore;

    private final Object lock = new Object();
    @Nullable private LoginSession currentLoginSession;
    @Nullable private String currentAccountName;
    private final Map<String, ScriptRun> activeRuns = new HashMap<>();
    @Nullable private Timer periodicSaveTimer;
    private final CopyOnWriteArrayList<Runnable> sessionStateCallbacks = new CopyOnWriteArrayList<>();

    public SessionTracker(Client client, ClientThread clientThread, SessionStore sessionStore) {
        this.client = client;
        this.clientThread = clientThread;
        this.sessionStore = sessionStore;
    }

    public void registerSessionStateCallback(Runnable callback) {
        sessionStateCallbacks.add(callback);
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

    private String accountNameOrDefault() {
        String name = client.getUsername();
        return (name == null || name.isEmpty()) ? "default" : name;
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
            isNew = true;
        }
        // startPeriodicSaves and notifyStateChanged must run outside the lock
        if (isNew) {
            log.debug("Session started: {} at {}", resolvedName, System.currentTimeMillis());
            startPeriodicSaves();
            notifyStateChanged();
        }
    }

    private void onLogout() {
        String accountName;
        LoginSession sessionToSave;
        synchronized (lock) {
            if (currentLoginSession == null) {
                return;
            }
            // Use the name captured at login; client.getUsername() returns "" at this point.
            accountName = currentAccountName != null ? currentAccountName : "default";
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
        LocalDate sessionDate = LocalDate.now(ZoneId.systemDefault());
        sessionStore.upsertSession(accountName, sessionDate, sessionToSave);
        log.debug("Session ended: {} at {}", accountName, sessionToSave.logoutTime());

        synchronized (lock) {
            currentLoginSession = null;
            activeRuns.clear();
            currentAccountName = null;
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
        }
        notifyStateChanged();
    }

    @Override
    public void onScriptStopped(String scriptId, @Nullable Integer count, @Nullable String countLabel) {
        String accountName;
        LoginSession updatedSession;
        ScriptRun activeRun;
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
            accountName = currentAccountName != null ? currentAccountName : "default";
            log.debug("Script stopped: {} ({}), duration: {}ms, count: {}",
                activeRun.displayName(), scriptId, endMs - activeRun.startMs(), count);
        }
        // I/O outside the lock
        LocalDate sessionDate = LocalDate.now(ZoneId.systemDefault());
        sessionStore.upsertSession(accountName, sessionDate, updatedSession);
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
            accountName = currentAccountName != null ? currentAccountName : "default";
            sessionId = currentLoginSession.sessionId();
        }
        // I/O outside the lock
        LocalDate sessionDate = LocalDate.now(ZoneId.systemDefault());
        sessionStore.upsertSession(accountName, sessionDate, snapshot);
        log.debug("Periodic save: session {}", sessionId);
    }

    // ---- Shutdown (plugin stop) ----

    public void onShutdown() {
        stopPeriodicSaves();
        String accountName;
        LoginSession finalSession;
        synchronized (lock) {
            if (currentLoginSession == null) return;
            accountName = currentAccountName != null ? currentAccountName : "default";
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
        LocalDate sessionDate = LocalDate.now(ZoneId.systemDefault());
        sessionStore.upsertSession(accountName, sessionDate, finalSession);

        synchronized (lock) {
            currentLoginSession = null;
            activeRuns.clear();
            currentAccountName = null;
        }
        log.debug("Session finalized on plugin shutdown");
    }
}
