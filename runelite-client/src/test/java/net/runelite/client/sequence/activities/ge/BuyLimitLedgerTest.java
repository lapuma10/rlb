package net.runelite.client.sequence.activities.ge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BuyLimitLedgerTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void quotaUsedSumsRecentEntries() {
        BuyLimitLedger ledger = new BuyLimitLedger(tmpPath());
        Instant t0 = Instant.parse("2026-04-30T20:00:00Z");
        ledger.record(2309, 100, t0);
        ledger.record(2309,  50, t0.plus(Duration.ofMinutes(30)));
        ledger.record(2309,  25, t0.plus(Duration.ofMinutes(60)));

        Instant now = t0.plus(Duration.ofMinutes(90));
        assertEquals(175, ledger.quotaUsed(2309, now));
    }

    @Test
    public void quotaUsedExpiresEntriesOlderThanWindow() {
        BuyLimitLedger ledger = new BuyLimitLedger(tmpPath());
        Instant t0 = Instant.parse("2026-04-30T20:00:00Z");
        ledger.record(2309, 1000, t0);                      // 4.5h ago vs `now` below
        ledger.record(2309,  100, t0.plus(Duration.ofMinutes(30))); // ~4h ago
        ledger.record(2309,   50, t0.plus(Duration.ofHours(1)));    // 3.5h ago
        ledger.record(2309,   25, t0.plus(Duration.ofHours(2)));    // 2.5h ago

        Instant now = t0.plus(Duration.ofHours(4)).plus(Duration.ofMinutes(30));
        // Window cutoff is now - 4h. Only entries strictly AFTER cutoff count.
        // Cutoff = t0 + 30m. Eligible: t0+1h (50), t0+2h (25). Total = 75.
        assertEquals(75, ledger.quotaUsed(2309, now));
    }

    @Test
    public void quotaRemainingFloorsAtZero() {
        BuyLimitLedger ledger = new BuyLimitLedger(tmpPath());
        Instant now = Instant.parse("2026-04-30T20:00:00Z");
        ledger.record(2309, 200, now.minus(Duration.ofMinutes(10)));
        // Limit 100, used 200 — remaining floors at 0.
        assertEquals(0, ledger.quotaRemaining(2309, 100, now));
    }

    @Test
    public void capToQuotaClips() {
        BuyLimitLedger ledger = new BuyLimitLedger(tmpPath());
        Instant now = Instant.parse("2026-04-30T20:00:00Z");
        ledger.record(2309, 80, now.minus(Duration.ofMinutes(10)));
        // Limit 100, used 80, remaining 20. Request 50 → capped to 20.
        assertEquals(20, ledger.capToQuota(2309, 50, 100, now));
        // Request 10 → fits, returned as-is.
        assertEquals(10, ledger.capToQuota(2309, 10, 100, now));
    }

    @Test
    public void recordIgnoresInvalidArgs() {
        BuyLimitLedger ledger = new BuyLimitLedger(tmpPath());
        Instant now = Instant.now();
        ledger.record(0,   100, now);
        ledger.record(2309,  0, now);
        ledger.record(2309, -5, now);
        ledger.record(2309, 100, null);
        assertEquals("no entries should have landed", 0, ledger.quotaUsed(2309, now));
    }

    @Test
    public void saveAndLoadRoundTrips() throws Exception {
        Path dir = tmpPath();
        Instant t0 = Instant.parse("2026-04-30T20:00:00Z");

        BuyLimitLedger a = new BuyLimitLedger(dir);
        a.record(2309, 100, t0);
        a.record(2313,  50, t0.plus(Duration.ofMinutes(30)));
        a.save(null);

        // New instance reading the same file should see the same entries.
        BuyLimitLedger b = new BuyLimitLedger(dir);
        b.load(null);
        Instant now = t0.plus(Duration.ofMinutes(45));
        assertEquals(100, b.quotaUsed(2309, now));
        assertEquals( 50, b.quotaUsed(2313, now));

        // File contents must include both item ids and the schema version.
        Path file = dir.resolve("default.json");
        assertTrue("ledger file must exist", Files.exists(file));
        String json = Files.readString(file);
        assertTrue(json.contains("\"2309\""));
        assertTrue(json.contains("\"2313\""));
        assertTrue(json.contains("\"schemaVersion\""));
    }

    @Test
    public void saveDropsExpiredEntries() throws Exception {
        Path dir = tmpPath();
        BuyLimitLedger a = new BuyLimitLedger(dir);
        // Old entry (>4h ago) should be pruned on save.
        a.record(2309, 999, Instant.now().minus(Duration.ofHours(5)));
        a.record(2309,  10, Instant.now().minus(Duration.ofMinutes(10)));
        a.save(null);

        BuyLimitLedger b = new BuyLimitLedger(dir);
        b.load(null);
        // Only the recent entry survives.
        assertEquals(10, b.quotaUsed(2309, Instant.now()));
    }

    @Test
    public void snapshotIsImmutableCopy() {
        BuyLimitLedger a = new BuyLimitLedger(tmpPath());
        a.record(2309, 5, Instant.now());
        var snap = a.snapshot();
        List<BuyLimitLedger.Entry> list = snap.get(2309);
        try {
            list.add(new BuyLimitLedger.Entry(999, Instant.now()));
            org.junit.Assert.fail("snapshot list should be immutable");
        } catch (UnsupportedOperationException expected) { /* ok */ }
    }

    private Path tmpPath() {
        try {
            return tmp.newFolder().toPath();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
