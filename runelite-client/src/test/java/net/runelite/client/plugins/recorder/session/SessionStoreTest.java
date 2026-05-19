package net.runelite.client.plugins.recorder.session;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SessionStoreTest {
    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    private SessionStore store;

    @Before
    public void setUp() {
        store = new SessionStore(tempDir.getRoot().toPath());
    }

    @Test
    public void jsonRoundTrip() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        List<ScriptRun> runs = List.of(
            new ScriptRun("chicken_farm_v3", "Chicken Farm V3", 1000, 2000L, 42, "kills"),
            new ScriptRun("cooking_v3", "Cooking V3", 2100, 3100L, 180, "cooked")
        );

        LoginSession session = new LoginSession("1000", 1000, 2200L, 2200, runs);

        store.upsertSession(account, date, session);
        List<LoginSession> loaded = store.loadDay(account, date);

        assertEquals(1, loaded.size());
        LoginSession reloaded = loaded.get(0);
        assertEquals(session.sessionId(), reloaded.sessionId());
        assertEquals(session.loginTime(), reloaded.loginTime());
        assertEquals(session.logoutTime(), reloaded.logoutTime());
        assertEquals(2, reloaded.runs().size());
        assertEquals("chicken_farm_v3", reloaded.runs().get(0).scriptId());
        assertEquals(42, (int) reloaded.runs().get(0).count());
    }

    @Test
    public void multipleSessionsPerDay() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        LoginSession s1 = new LoginSession("1000", 1000, 2000L, 2000,
            List.of(new ScriptRun("mining", "Mining", 1000, 2000L, 50, "ores")));
        LoginSession s2 = new LoginSession("3000", 3000, 4000L, 4000,
            List.of(new ScriptRun("mining", "Mining", 3000, 4000L, 60, "ores")));

        store.upsertSession(account, date, s1);
        store.upsertSession(account, date, s2);

        List<LoginSession> loaded = store.loadDay(account, date);
        assertEquals(2, loaded.size());
    }

    @Test
    public void aggregateDaily_basic() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        LoginSession session = new LoginSession("1000", 1000, 3000L, 3000, List.of(
            new ScriptRun("chicken_farm_v3", "Chicken Farm V3", 1000, 2000L, 42, "kills"),
            new ScriptRun("mining", "Mining", 2000, 3000L, 50, "ores")
        ));
        store.upsertSession(account, date, session);

        SessionStats stats = store.aggregateDaily(account, date);
        assertEquals(2000, stats.totalLoginMs());
        assertEquals(2000, stats.totalScriptActiveMs());
        assertEquals(0, stats.idleMs());
        assertEquals(2, stats.scripts().size());
    }

    @Test
    public void aggregateDaily_overlappingScripts_unionTime() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        LoginSession session = new LoginSession("1000", 1000, 3000L, 3000, List.of(
            new ScriptRun("script_a", "Script A", 1000, 2000L, null, null),
            new ScriptRun("script_b", "Script B", 1500, 2500L, null, null)
        ));
        store.upsertSession(account, date, session);

        SessionStats stats = store.aggregateDaily(account, date);
        // total login = 2000; union of [1000,2000] + [1500,2500] = [1000,2500] = 1500
        assertEquals(2000, stats.totalLoginMs());
        assertEquals(1500, stats.totalScriptActiveMs());
        assertEquals(500, stats.idleMs());
    }

    @Test
    public void upsertReplacesBySessionId() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        LoginSession v1 = new LoginSession("1000", 1000, 2000L, 2000,
            List.of(new ScriptRun("mining", "Mining", 1000, 2000L, 10, "ores")));
        store.upsertSession(account, date, v1);

        LoginSession v2 = new LoginSession("1000", 1000, 2000L, 2000,
            List.of(new ScriptRun("mining", "Mining", 1000, 2000L, 20, "ores")));
        store.upsertSession(account, date, v2);

        List<LoginSession> loaded = store.loadDay(account, date);
        assertEquals(1, loaded.size());
        assertEquals(20, (int) loaded.get(0).runs().get(0).count());
    }

    @Test
    public void openRun_usesLastSavedMsAsEnd() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        // Run started at 1000, never ended; session never logged out, last saved at 2500.
        LoginSession session = new LoginSession("1000", 1000, null, 2500, List.of(
            new ScriptRun("mining", "Mining", 1000, null, null, null)
        ));
        store.upsertSession(account, date, session);

        SessionStats stats = store.aggregateDaily(account, date);
        // Open run treated as [1000, lastSavedMs=2500] = 1500ms.
        assertEquals(1500, stats.totalScriptActiveMs());
    }

    @Test
    public void deleteDay_removesFile() {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";
        LoginSession session = new LoginSession("1000", 1000, 2000L, 2000, List.of());
        store.upsertSession(account, date, session);
        assertEquals(1, store.loadDay(account, date).size());

        store.deleteDay(account, date);
        assertEquals(0, store.loadDay(account, date).size());
    }

    @Test
    public void loadDay_corruptJson_returnsEmpty() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        // First write a valid file so the account dir exists.
        LoginSession s = new LoginSession("1000", 1000, 2000L, 2000, List.of());
        store.upsertSession(account, date, s);

        // Now corrupt the file. loadDay must NOT throw; aggregateAllTime depends on this.
        Path dayFile = tempDir.getRoot().toPath()
            .resolve(account).resolve(date.toString() + ".json");
        Files.writeString(dayFile, "{not valid json at all");

        List<LoginSession> loaded = store.loadDay(account, date);
        assertEquals(0, loaded.size());

        // aggregateAllTime should also survive a corrupt file in the directory.
        SessionStats stats = store.aggregateAllTime(account);
        assertEquals(0, stats.totalLoginMs());
    }

    @Test
    public void sameScriptTwiceInSession_doesNotDoubleCount() {
        // Regression: a session with two runs of the SAME scriptId used to be counted
        // twice in per-script totals because the old loop called accumulate once per run
        // and each call scanned all runs in the session. Should be ONE entry of 30 min.
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        LoginSession session = new LoginSession("1000", 0, 1_800_000L, 1_800_000, List.of(
            new ScriptRun("mining", "Mining", 0, 600_000L, 10, "ores"),
            new ScriptRun("mining", "Mining", 600_000, 1_800_000L, 20, "ores")
        ));
        store.upsertSession(account, date, session);

        SessionStats stats = store.aggregateDaily(account, date);
        assertEquals(1, stats.scripts().size());
        ScriptStats mining = stats.scripts().get("mining");
        // 10 min + 20 min = 30 min, NOT 60 min.
        assertEquals(1_800_000L, mining.totalMs());
        assertEquals(30, (int) mining.totalCount());
    }

    @Test
    public void openRunContributesToPerScriptTotalMs() {
        // Regression: open runs (endMs=null) were skipped by per-script accumulation,
        // showing 0m in the breakdown even though global active time was correct.
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        LoginSession session = new LoginSession("1000", 1000, null, 2500, List.of(
            new ScriptRun("mining", "Mining", 1000, null, null, null)
        ));
        store.upsertSession(account, date, session);

        SessionStats stats = store.aggregateDaily(account, date);
        ScriptStats mining = stats.scripts().get("mining");
        // Open run treated as [1000, lastSavedMs=2500] = 1500ms per script.
        assertEquals(1500, mining.totalMs());
    }

    @Test
    public void periodicOpenRunThenFinalClosed_noDuplicate() {
        // Real lifecycle: tracker writes open run via periodic save, then later upserts
        // the same session with the run closed. upsert-by-sessionId must replace, not append.
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        LoginSession openSnapshot = new LoginSession("s1", 1000, null, 2000, List.of(
            new ScriptRun("mining", "Mining", 1000, null, null, null)
        ));
        store.upsertSession(account, date, openSnapshot);

        LoginSession closed = new LoginSession("s1", 1000, 3000L, 3000, List.of(
            new ScriptRun("mining", "Mining", 1000, 3000L, 42, "ores")
        ));
        store.upsertSession(account, date, closed);

        List<LoginSession> loaded = store.loadDay(account, date);
        assertEquals(1, loaded.size());
        assertEquals(1, loaded.get(0).runs().size());
        assertEquals(42, (int) loaded.get(0).runs().get(0).count());

        SessionStats stats = store.aggregateDaily(account, date);
        assertEquals(2000, stats.totalScriptActiveMs());
        assertEquals(1, stats.scripts().size());
    }

    @Test
    public void countAggregation_mixedNullAndNonNull() {
        // If any run for a scriptId lacks a count, the merged count is null
        // (we can't claim a total when partial data is missing).
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        // Session A: counted (10).
        store.upsertSession(account, date, new LoginSession("a", 0, 1000L, 1000, List.of(
            new ScriptRun("mining", "Mining", 0, 1000L, 10, "ores")
        )));
        // Session B: uncounted (null count).
        store.upsertSession(account, date, new LoginSession("b", 2000, 3000L, 3000, List.of(
            new ScriptRun("mining", "Mining", 2000, 3000L, null, null)
        )));

        SessionStats stats = store.aggregateDaily(account, date);
        ScriptStats mining = stats.scripts().get("mining");
        // Time still sums (1000 + 1000 = 2000), but count is null because partial.
        assertEquals(2000, mining.totalMs());
        org.junit.Assert.assertNull(mining.totalCount());
    }

    @Test
    public void aggregateDailyWithLiveOverride_appendsAndReplaces() {
        // When the panel passes a live in-memory snapshot, aggregateDaily must replace
        // (by sessionId) the stale disk version — not double-count it.
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        // Disk: an old periodic snapshot of session s1 with lastSavedMs=2000.
        store.upsertSession(account, date, new LoginSession("s1", 1000, null, 2000, List.of(
            new ScriptRun("mining", "Mining", 1000, null, null, null)
        )));

        // Live snapshot of the SAME session, now at lastSavedMs=5000 (3s later).
        LoginSession live = new LoginSession("s1", 1000, null, 5000, List.of(
            new ScriptRun("mining", "Mining", 1000, null, null, null)
        ));

        SessionStats stats = store.aggregateDaily(account, date, live, date);
        // Active = [1000, 5000] = 4000, NOT 4000+1000=5000 (which would happen if duplicated).
        assertEquals(4000, stats.totalScriptActiveMs());
        assertEquals(4000, stats.totalLoginMs());
    }

    @Test
    public void aggregateAllTimeWithLiveOverride_brandNewSessionAppended() {
        // Brand-new session with no disk file yet still appears in all-time via the live override.
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        LoginSession live = new LoginSession("brand_new", 1000, null, 4000, List.of(
            new ScriptRun("mining", "Mining", 1000, null, null, null)
        ));

        SessionStats stats = store.aggregateAllTime(account, live, date);
        assertEquals(3000, stats.totalScriptActiveMs());
        assertEquals(1, stats.scripts().size());
    }

    @Test
    public void concurrentUpsert_doesNotLoseWrites() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 19);
        String account = "testaccount";

        int threads = 8;
        int writesPerThread = 25;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            Thread w = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        String sid = "t" + threadId + "_" + i;
                        long start_ = i * 1000L;
                        long end_ = start_ + 500;
                        LoginSession ls = new LoginSession(sid, start_, end_, end_, List.of(
                            new ScriptRun("mining", "Mining", start_, end_, 1, "ores")
                        ));
                        store.upsertSession(account, date, ls);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
            workers.add(w);
            w.start();
        }
        start.countDown();
        assertTrue("threads did not finish in time", done.await(30, TimeUnit.SECONDS));

        List<LoginSession> loaded = store.loadDay(account, date);
        // Every distinct sessionId written by every thread must be present (no read-modify-write loss).
        assertEquals(threads * writesPerThread, loaded.size());
    }
}
