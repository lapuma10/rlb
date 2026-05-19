package net.runelite.client.plugins.recorder.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class SessionStore {
    private static final String SESSION_DIR = ".runelite/recorder/sessions";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private final Gson gson;
    private final Path baseDir;

    public SessionStore() {
        this(Paths.get(System.getProperty("user.home")).resolve(SESSION_DIR));
    }

    public SessionStore(Path baseDir) {
        this.baseDir = baseDir;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    private Path getSessionDirectory(String accountName) {
        return baseDir.resolve(accountName);
    }

    private Path getDayFile(String accountName, LocalDate date) {
        return getSessionDirectory(accountName).resolve(date.format(DATE_FORMAT) + ".json");
    }

    public record DaySessionFile(String date, List<LoginSession> sessions) {}

    public List<LoginSession> loadDay(String accountName, LocalDate date) {
        Path dayFile = getDayFile(accountName, date);
        if (!Files.exists(dayFile)) {
            return new ArrayList<>();
        }
        try {
            String content = Files.readString(dayFile);
            DaySessionFile dayData = gson.fromJson(content, DaySessionFile.class);
            return dayData != null && dayData.sessions != null ? dayData.sessions : new ArrayList<>();
        } catch (IOException e) {
            log.warn("Failed to load session file {}: {}", dayFile, e.getMessage());
            return new ArrayList<>();
        }
    }

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
            String json = gson.toJson(dayData);
            Files.writeString(tmpFile, json);
            Files.move(tmpFile, dayFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            log.debug("Upserted session {} for {}", session.sessionId(), date);
        } catch (IOException e) {
            log.error("Failed to upsert session for {}: {}", date, e.getMessage(), e);
        }
    }

    public void deleteDay(String accountName, LocalDate date) {
        try {
            Path dayFile = getDayFile(accountName, date);
            Files.deleteIfExists(dayFile);
            log.debug("Deleted session file for {}", date);
        } catch (IOException e) {
            log.error("Failed to delete session file for {}: {}", date, e.getMessage());
        }
    }

    private static class Interval {
        final long start;
        final long end;
        Interval(long start, long end) { this.start = start; this.end = end; }
    }

    private long mergeAndSumIntervals(List<Interval> intervals) {
        if (intervals.isEmpty()) return 0;
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

    private ScriptStats accumulateScriptStats(String scriptId, List<ScriptRun> runs, long sessionLastSavedMs) {
        String displayName = "";
        long totalMs = 0;
        int totalCount = 0;
        String countLabel = null;
        boolean hasCount = false;

        for (ScriptRun run : runs) {
            if (!run.scriptId().equals(scriptId)) continue;
            displayName = run.displayName();
            long endMs = run.endMs() != null ? run.endMs() : sessionLastSavedMs;
            totalMs += endMs - run.startMs();
            if (run.count() != null) {
                totalCount += run.count();
                countLabel = run.countLabel();
                hasCount = true;
            }
        }
        return new ScriptStats(scriptId, displayName, totalMs, hasCount ? totalCount : null, countLabel);
    }

    private SessionStats aggregateFromSessions(List<LoginSession> sessions) {
        if (sessions.isEmpty()) {
            return new SessionStats(0, 0, 0, Map.of());
        }

        long totalLoginMs = 0;
        List<Interval> allRunIntervals = new ArrayList<>();
        Map<String, ScriptStats> scriptStatsMap = new HashMap<>();

        for (LoginSession session : sessions) {
            totalLoginMs += session.loginDurationMs();
            for (ScriptRun run : session.runs()) {
                long endMs = run.endMs() != null ? run.endMs() : session.lastSavedMs();
                allRunIntervals.add(new Interval(run.startMs(), endMs));
            }
        }

        // Rebuild scriptStatsMap to ensure per-session contribution counted exactly once per scriptId.
        for (LoginSession session : sessions) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (ScriptRun run : session.runs()) {
                if (!seen.add(run.scriptId())) continue;
                ScriptStats sessionScriptStats = accumulateScriptStats(run.scriptId(), session.runs(), session.lastSavedMs());
                ScriptStats existing = scriptStatsMap.get(run.scriptId());
                if (existing == null) {
                    scriptStatsMap.put(run.scriptId(), sessionScriptStats);
                } else {
                    Integer mergedCount = (existing.totalCount() != null && sessionScriptStats.totalCount() != null)
                        ? existing.totalCount() + sessionScriptStats.totalCount() : null;
                    scriptStatsMap.put(run.scriptId(), new ScriptStats(
                        existing.scriptId(),
                        existing.displayName(),
                        existing.totalMs() + sessionScriptStats.totalMs(),
                        mergedCount,
                        existing.countLabel()
                    ));
                }
            }
        }

        long totalScriptActiveMs = mergeAndSumIntervals(allRunIntervals);
        long idleMs = Math.max(0, totalLoginMs - totalScriptActiveMs);

        return new SessionStats(totalLoginMs, totalScriptActiveMs, idleMs, scriptStatsMap);
    }

    public SessionStats aggregateDaily(String accountName, LocalDate date) {
        return aggregateFromSessions(loadDay(accountName, date));
    }

    public SessionStats aggregateWeekly(String accountName, LocalDate midweekDate) {
        LocalDate monday = midweekDate.minusDays(midweekDate.getDayOfWeek().getValue() - 1);
        List<LoginSession> allSessions = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            allSessions.addAll(loadDay(accountName, monday.plusDays(i)));
        }
        return aggregateFromSessions(allSessions);
    }

    public SessionStats aggregateMonthly(String accountName, YearMonth month) {
        LocalDate startDate = month.atDay(1);
        LocalDate endDate = month.atEndOfMonth();
        List<LoginSession> allSessions = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            allSessions.addAll(loadDay(accountName, date));
        }
        return aggregateFromSessions(allSessions);
    }

    public SessionStats aggregateAllTime(String accountName) {
        Path sessionDir = getSessionDirectory(accountName);
        if (!Files.exists(sessionDir)) {
            return new SessionStats(0, 0, 0, Map.of());
        }
        List<LoginSession> allSessions = new ArrayList<>();
        try (var stream = Files.list(sessionDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        String dateStr = p.getFileName().toString().replace(".json", "");
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
}
