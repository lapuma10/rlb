package net.runelite.client.sequence.activities.ge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;

/**
 * Persistent ledger of GE buy events for buy-limit accounting. The OSRS
 * Grand Exchange enforces a per-item rolling 4-hour buy limit; this
 * ledger tracks {@code (itemId, qty, timestamp)} tuples so the bot can
 * cap or skip new offers that would exceed the limit.
 *
 * <p>Recording: hook {@link #record(int, int, Instant)} into the offer
 * lifecycle (e.g. on {@code GrandExchangeOfferChanged} terminal state
 * BOUGHT / CANCELLED_BUY, where {@code completedQuantity} is the
 * authoritative filled amount).
 *
 * <p>Querying: {@link #quotaUsed(int, Instant)} sums entries for an
 * itemId within the rolling window; {@link #quotaRemaining(int, int,
 * Instant)} is the convenience caller that bakes in a limit and floors
 * at zero.
 *
 * <p>Persistence: one JSON file per account at
 * {@code ~/.runelite/recorder/buy-limits/<sha256-of-username>.json}.
 * Account hashing mirrors {@code TrainingPlanStore.hashKey(...)}. The
 * file is pruned of expired entries on every save.
 *
 * <p>Concurrency: the in-memory map is a {@link ConcurrentHashMap} of
 * synchronized lists. Recording fires on the RuneLite client thread
 * (offer-changed events run there); pre-flight queries fire from the
 * GrandExchangeScript at script-start time on whichever thread the
 * panel runs on. Writes are serialized via {@code synchronized}
 * helpers below; reads are best-effort consistent.
 */
@Slf4j
public final class BuyLimitLedger {

    /** Rolling window for the OSRS GE buy limit. */
    public static final Duration WINDOW = Duration.ofHours(4);

    private static final String DIR_NAME = "buy-limits";
    private static final String EXT = ".json";
    private static final String DEFAULT_KEY = "default";
    private static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        // Gson can't reflect into java.time.Instant on JDK 17+ without
        // --add-opens — register an explicit ISO-8601 string adapter.
        .registerTypeAdapter(Instant.class,
            (com.google.gson.JsonSerializer<Instant>) (src, type, ctx) ->
                src == null ? com.google.gson.JsonNull.INSTANCE
                            : new com.google.gson.JsonPrimitive(src.toString()))
        .registerTypeAdapter(Instant.class,
            (com.google.gson.JsonDeserializer<Instant>) (json, type, ctx) ->
                json == null || json.isJsonNull()
                    ? null
                    : Instant.parse(json.getAsString()))
        .create();

    /** itemId → list of buy entries. Lists are synchronized via {@code this}. */
    private final Map<Integer, List<Entry>> entries = new ConcurrentHashMap<>();
    private final Path baseDir;

    public BuyLimitLedger() {
        this(RuneLite.RUNELITE_DIR.toPath().resolve("recorder").resolve(DIR_NAME));
    }

    /** Test-only constructor. */
    public BuyLimitLedger(Path baseDir) {
        this.baseDir = baseDir;
    }

    /** Append a buy record. {@code qty} is the actual filled quantity
     *  (NOT the requested quantity) — only filled buys count against the
     *  GE limit. {@code at} is the moment the buy completed.
     *
     *  <p>{@code qty <= 0} is silently ignored. Zero-fill aborts and
     *  no-op partials shouldn't pollute the ledger. */
    public synchronized void record(int itemId, int qty, Instant at) {
        if (itemId <= 0 || qty <= 0 || at == null) return;
        entries.computeIfAbsent(itemId, k -> new ArrayList<>())
            .add(new Entry(qty, at));
    }

    /** Sum of filled qtys for {@code itemId} whose timestamp is within
     *  the rolling 4-hour window relative to {@code now}. */
    public synchronized int quotaUsed(int itemId, Instant now) {
        List<Entry> list = entries.get(itemId);
        if (list == null || list.isEmpty()) return 0;
        Instant cutoff = now.minus(WINDOW);
        int sum = 0;
        for (Entry e : list) {
            if (e.at != null && e.at.isAfter(cutoff)) {
                sum += e.qty;
            }
        }
        return sum;
    }

    /** Convenience: {@code max(0, limit - quotaUsed(itemId, now))}. */
    public int quotaRemaining(int itemId, int limit, Instant now) {
        return Math.max(0, limit - quotaUsed(itemId, now));
    }

    /** Cap a requested quantity to the remaining quota. Returns the
     *  smaller of {@code requestedQty} and {@code quotaRemaining(...)}.
     *  Result is 0 when the limit has been reached — caller should
     *  treat as "skip this trade, retry after the window resets". */
    public int capToQuota(int itemId, int requestedQty, int limit, Instant now) {
        if (requestedQty <= 0) return 0;
        return Math.min(requestedQty, quotaRemaining(itemId, limit, now));
    }

    /** Drop entries older than {@link #WINDOW} from the in-memory map.
     *  Called on every {@link #save(Client)} to keep the on-disk file
     *  small. Idempotent. */
    public synchronized void prune(Instant now) {
        Instant cutoff = now.minus(WINDOW);
        for (List<Entry> list : entries.values()) {
            list.removeIf(e -> e.at == null || !e.at.isAfter(cutoff));
        }
    }

    /** Snapshot of the ledger's current state, for diagnostics / UI. The
     *  returned map and lists are immutable copies — caller-modifications
     *  do not leak into the ledger. */
    public synchronized Map<Integer, List<Entry>> snapshot() {
        Map<Integer, List<Entry>> out = new HashMap<>();
        for (Map.Entry<Integer, List<Entry>> e : entries.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    // ─── Persistence ──────────────────────────────────────────────────

    /** Load the per-account ledger from disk. Missing file is silently
     *  treated as an empty ledger (first-run case). Malformed JSON is
     *  logged and discarded — better to lose history than crash the
     *  bot at startup. */
    public synchronized void load(@Nullable Client client) {
        Path file = pathFor(usernameOf(client));
        entries.clear();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<PersistedShape>(){}.getType();
            PersistedShape shape = GSON.fromJson(r, type);
            if (shape == null || shape.entries == null) return;
            for (Map.Entry<String, List<Entry>> e : shape.entries.entrySet()) {
                int itemId;
                try { itemId = Integer.parseInt(e.getKey()); }
                catch (NumberFormatException nfe) { continue; }
                List<Entry> list = e.getValue();
                if (list == null) continue;
                List<Entry> sane = new ArrayList<>(list.size());
                for (Entry x : list) if (x != null && x.qty > 0 && x.at != null) sane.add(x);
                if (!sane.isEmpty()) entries.put(itemId, sane);
            }
            prune(Instant.now());
        } catch (IOException | JsonSyntaxException ex) {
            log.warn("buy-limits: failed to read {} — starting empty", file, ex);
            entries.clear();
        }
    }

    /** Persist the ledger to disk. Prunes expired entries first. */
    public synchronized void save(@Nullable Client client) {
        prune(Instant.now());
        Path file = pathFor(usernameOf(client));
        try {
            Files.createDirectories(file.getParent());
            // Convert int-keyed map to string-keyed for stable JSON output.
            PersistedShape shape = new PersistedShape();
            shape.schemaVersion = SCHEMA_VERSION;
            shape.entries = new HashMap<>();
            for (Map.Entry<Integer, List<Entry>> e : entries.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    shape.entries.put(Integer.toString(e.getKey()), List.copyOf(e.getValue()));
                }
            }
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(shape, w);
            }
        } catch (IOException ex) {
            log.warn("buy-limits: failed to write {}", file, ex);
            throw new UncheckedIOException(ex);
        }
    }

    private Path pathFor(String username) {
        return baseDir.resolve(hashKey(username) + EXT);
    }

    private static String usernameOf(@Nullable Client client) {
        if (client == null) return DEFAULT_KEY;
        try {
            String u = client.getUsername();
            return (u == null || u.isBlank()) ? DEFAULT_KEY : u.trim();
        } catch (Throwable th) {
            return DEFAULT_KEY;
        }
    }

    static String hashKey(String username) {
        if (username == null || username.equals(DEFAULT_KEY)) return DEFAULT_KEY;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(username.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            return DEFAULT_KEY;
        }
    }

    // ─── On-disk shape ────────────────────────────────────────────────

    public static final class Entry {
        @SerializedName("q") public final int qty;
        @SerializedName("t") public final Instant at;

        public Entry(int qty, Instant at) {
            this.qty = qty;
            this.at = at;
        }

        public int qty()     { return qty; }
        public Instant at()  { return at; }
    }

    private static final class PersistedShape {
        @SerializedName("schemaVersion") int schemaVersion;
        /** Item id (string-keyed for clean JSON) → list of entries. */
        @SerializedName("entries") Map<String, List<Entry>> entries;
    }
}
