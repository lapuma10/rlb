package net.runelite.client.plugins.recorder.session;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.assertEquals;

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
}
