package net.runelite.client.plugins.recorder.scripts;

import java.awt.Rectangle;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.cook.CookingFood;
import net.runelite.client.plugins.recorder.cook.CookingInteraction;
import net.runelite.client.plugins.recorder.cook.CookingLocation;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.walker.UniversalWalker;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Cooking bot script.
 *
 * <p>Runs a configurable bank → cook → rebank loop at a
 * {@link CookingLocation}. Two heat-source kinds are supported:
 * fire-from-logs (Tinderbox + Logs ground spawn → light → cook) and
 * range (use food on a Range game object).
 *
 * <p><b>Original prompt (verbatim, for posterity):</b>
 * <pre>
 * Look into player animating. How would you write a cooking script?
 * brainstorm with the skill. see if you can figure out and find out how
 * cooking works. I want a cooking script that works in lumbridge castle
 * at p2 in the bank, there are logs that spawn there. I want you to use
 * tinderbox and take out selected food from the bank (select in the
 * script). then it should light a fire on the logs with a tinderbox(rightclick
 * logs ont he ground select light) or click tinderbox with use(left
 * click) then left click one of the logs. Then left click raw food
 * selected, then click on the fire thats burning and in the dialogue
 * select cook all(see cd .. starter script repo for hwo to dismiss
 * level up dialogue, how to know when youre done cooking, how to know
 * that your fire has died and u need to light a new one etc.) How to
 * use the dialogue to select cook all. How to bank, rebank. BUUUT we
 * need to use our runelite engines api for banking (we have none for
 * cooking yet, or lighting a fire) also dont hardcode it. we should add
 * more places to cook. But we start with lumby p2 bank(theres coords
 * for it in the repo were in rn). Build it in a worktree so we wouldnt
 * disturb the other stuff, do it based on main branch. us ebrainstorming
 * to improve and ensure that its propper.and working, send out a subagent
 * to look into rs wiki to see if we find something usefull. DO NOT
 * assume anything, ensure to use APi, check api, use correct calls,
 * animations, widget ids etc. the starter script should have correct
 * widget ids. Go plan it out, think through it, qc it, then go implement
 * it. DO NOT STOP until done. THEN Send out 2 qc suabgents to qc
 * independetnly. also note down my original prompt here in the script
 * file youll write this in. Keep it generic for us to be able to use
 * different foods, and fires/ranges, as there are different places to
 * cook, some places can be used with logs, others have ranges(range)
 * etc.
 *
 * Follow-up: a gotcah is that you have to have whats needed in your
 * inventory and in the bank, the scrolling, looking for items etc. and
 * if you dont find it, the bot shouldnt crash right? so ensur ebank is
 * open, were looking for it, then we close it etc.
 * </pre>
 *
 * <p><b>Threading:</b> mirrors {@code ChickenFarmV2Script} — one daemon
 * worker tick loop, every read of client state hopped through
 * {@link ClientThread#invokeLater}, clicks dispatched via the injected
 * {@link HumanizedInputDispatcher}.
 */
@Slf4j
public final class CookingScript
{
    /** Tick cadence — close to one OSRS engine tick (600ms). */
    private static final long TICK_MS = 600;
    /** Throttle between any two banking dispatches. */
    private static final long BANK_PACE_MS = 2000;
    /** Max wall-clock time waiting for a fire to spawn after we click
     *  logs. Low-level firemaking can take a long string of failed
     *  attempts (~3-7s each at lvl 1-15), so 30s covers a normal
     *  worst-case. The starter-script reference also waits for the
     *  fire object to materialise before continuing. */
    private static final long LIGHT_TIMEOUT_MS = 30_000;
    /** Min/max random pause after our fire dies before we re-light.
     *  Real players take a beat to notice the fire's gone, look for
     *  fresh logs, and decide to keep going. 2-10s feels human; the
     *  range covers both "I was paying attention" and "I was reading
     *  chat". */
    private static final long FIRE_DEATH_PAUSE_MIN_MS = 2_000;
    private static final long FIRE_DEATH_PAUSE_MAX_MS = 10_000;
    /** How long after the last raw-count decrease we keep treating the
     *  cook-all queue as "still running". One cook = ~1.8s; we wait
     *  ~5s to absorb pauses + animation gaps without re-dispatching.
     *  If no raw decrease within this window, the batch genuinely
     *  stalled and we re-issue use-raw-on-fire. */
    private static final long COOK_BATCH_SETTLE_MS = 5_000;
    /** Max time without inventory progress (raw count change) before we
     *  ABORT the cooking phase. Defensive: should not trip in practice. */
    private static final long COOK_STUCK_MS = 30_000;
    /** Throttle between cooking-action dispatches (use raw + click fire). */
    private static final long COOK_PACE_MS = 1500;

    public enum State
    {
        IDLE,
        BANKING,
        WALK_TO_COOK,
        LIGHTING_FIRE,
        COOKING,
        WALK_TO_BANK,
        ABORTED
    }

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final UniversalWalker walker;
    private final BankInteraction bank;
    private final CookingInteraction cook;

    private final AtomicReference<CookingLocation> location = new AtomicReference<>();
    private final AtomicInteger rawFoodId = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    private final AtomicInteger cookedCount = new AtomicInteger(0);
    private final AtomicInteger burntCount  = new AtomicInteger(0);

    private long lastBankActionAtMs;
    private long lastCookActionAtMs;
    private long lightingStartedAtMs;
    private long lastInventoryChangeAtMs;
    private int  lastRawCount = -1;
    /** Tile we just clicked logs on for lighting. Set when the
     *  use-tinderbox + click-logs sequence dispatches. Cleared when we
     *  observe a Fire object on this tile (or an adjacent tile) — at
     *  which point we treat it as "our fire" for the rest of the
     *  cooking trip. Re-cleared when the fire dies. */
    private WorldPoint litFireTile;
    /** When we kicked off the current lighting attempt — used to time
     *  out a no-fire-spawned attempt and back off. */
    private long lightDispatchedAtMs;
    /** Set true once tallyCookedBurnt has run for the current banking
     *  visit. Prevents re-tallying every tick we sit at the bank
     *  waiting for withdraws to finish. Reset on every state change. */
    private boolean tallyDoneThisVisit;
    /** Consecutive walker-stuck count. Reset on ARRIVED or any
     *  movement-based progress; capped via {@link #WALKER_MAX_STUCK}.
     *  Across that cap we abort — but most transient stucks self-recover
     *  on the next walker.tick. */
    private int walkerStuckCount;
    /** Consecutive cook-stuck cycles. Reset on inventory progress and
     *  on state transitions; capped via {@link #COOK_MAX_STUCK}. */
    private int cookStuckCount;
    /** Wall-clock time of the last raw-count DECREASE — i.e. an actual
     *  successful cook by the engine (raw → cooked or raw → burnt).
     *  Cooking is considered "actively running" while this is recent
     *  (within {@link #COOK_BATCH_SETTLE_MS}). Used as a second safety
     *  net beyond isCooking() so that an animation gap during a
     *  Cook-All queue can't trigger us to spuriously click an
     *  inventory slot that's already been converted to cooked food. */
    private long lastRawDecreaseAtMs;
    /** When the bank widget first opened this BANKING entry. Gates the
     *  "missing item" check until the engine has had a tick to populate
     *  {@code InventoryID.BANK}. */
    private long bankOpenedAtMs;
    /** Consecutive failures of withdraw / deposit / close dispatch — too
     *  many in a row means we're stuck and should abort. */
    private int  bankFailCount;
    /** Consecutive Skillmulti Space-presses without the dialog closing —
     *  switch to the explicit ALL widget click after this many. */
    private int  cookMenuConfirmAttempts;
    /** Snapshot of cooked / burnt-in-inventory at the start of the
     *  current cooking trip. Used so the next banking pass tallies only
     *  the delta produced this trip, not pre-existing inventory. */
    private int  tripStartCooked;
    private int  tripStartBurnt;
    /** Backoff timestamp for re-entering LIGHTING_FIRE after a failed
     *  attempt — avoids hammering the same flow if the engine swallows
     *  our use-mode click. */
    private long lightingBackoffUntilMs;

    /** Max retries on a banking sub-step (booth click / deposit /
     *  withdraw / close) before aborting. 3 tries covers transient
     *  widget races; beyond that something is genuinely wrong and we
     *  surface the failure rather than spam clicks forever. */
    private static final int BANK_MAX_FAIL = 3;
    /** Max consecutive walker STUCK / ERROR before aborting. Each
     *  STUCK represents ~15s of no movement, so 3 in a row is ~45s of
     *  failed walking — enough to know the player is genuinely
     *  blocked, not just one transient mis-click. */
    private static final int WALKER_MAX_STUCK = 3;
    /** Max consecutive cook-stuck cycles before aborting. The stuck
     *  timer fires after COOK_STUCK_MS (30s) of no inventory progress;
     *  we let the FSM re-issue use-on-fire that many times before
     *  surfacing the failure. */
    private static final int COOK_MAX_STUCK = 3;
    /** After this many Space-press attempts that don't close Skillmulti,
     *  fall back to clicking the ALL widget directly. */
    private static final int COOK_MENU_FALLBACK_AFTER = 2;
    /** The bank widget can be open for up to ~600ms before the
     *  ItemContainer is populated. We wait at least this long after
     *  detecting bank-open before treating "item not in bank" as a
     *  hard miss. */
    private static final long BANK_LOAD_GRACE_MS = 1500;

    public CookingScript(Client client, ClientThread clientThread,
                         HumanizedInputDispatcher dispatcher,
                         TransportResolver resolver)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.walker = new UniversalWalker(client, clientThread, dispatcher, resolver);
        this.bank = new BankInteraction(client, clientThread, dispatcher);
        this.cook = new CookingInteraction(client, clientThread, dispatcher);
    }

    public void setLocation(CookingLocation l) { location.set(l); }
    public void setRawFoodId(int id) { rawFoodId.set(id); }
    public CookingLocation location() { return location.get(); }
    public int rawFoodId() { return rawFoodId.get(); }
    public State state() { return state.get(); }
    public String status() { return status.get(); }
    public int cookedCount() { return cookedCount.get(); }
    public int burntCount() { return burntCount.get(); }

    public void start()
    {
        // A worker may still be unwinding from a previous stop() — refuse
        // to start a second one before the first is fully gone.
        Thread existing = worker.get();
        if (existing != null && existing.isAlive())
        {
            status.set("already running");
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        if (location.get() == null)
        {
            status.set("no location selected — abort");
            running.set(false);
            return;
        }
        if (rawFoodId.get() <= 0)
        {
            status.set("no food selected — abort");
            running.set(false);
            return;
        }
        State decided = decideResume();
        log.info("cook: resume → {} ({})", decided, status.get());
        setState(decided);
        if (decided == State.IDLE || decided == State.ABORTED)
        {
            running.set(false);
            return;
        }
        // Snapshot pre-trip cooked/burnt counts so we tally only the
        // delta this run produces (avoids over-counting on resume with
        // existing inventory).
        snapshotTripBaseline();
        Thread t = new Thread(this::tickLoop, "cooking-script");
        t.setDaemon(true);
        worker.set(t);
        t.start();
    }

    public void stop()
    {
        running.set(false);
        Thread t = worker.getAndSet(null);
        if (t != null)
        {
            t.interrupt();
            try { t.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        setState(State.IDLE);
        status.set("stopped");
    }

    private State decideResume()
    {
        CookingLocation l = location.get();
        WorldPoint here = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        if (here == null) { status.set("no player — abort"); return State.ABORTED; }
        boolean atBank = areaContains(l.bankArea(), here);
        boolean atCook = areaContains(l.cookArea(), here);
        int raw = inventoryAmountSafe(rawFoodId.get());
        if (atBank)
        {
            // Even if we have raw food, do a banking pass to ensure
            // tinderbox + raw food are sufficient before walking to
            // cook. The banking pass is idempotent.
            status.set("starting at bank");
            return State.BANKING;
        }
        if (atCook && raw > 0)
        {
            status.set("starting at cook spot — beginning to cook");
            return l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS
                ? State.LIGHTING_FIRE : State.COOKING;
        }
        // Mid-route — figure out which direction.
        if (raw > 0) { status.set("mid-route — heading to cook"); return State.WALK_TO_COOK; }
        status.set("mid-route — heading to bank");
        return State.WALK_TO_BANK;
    }

    private void tickLoop()
    {
        try
        {
            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                // Player gone (loading screen, hopped, disconnected) —
                // hold; don't tick the FSM into a null-state cascade.
                WorldPoint here = onClient(() -> {
                    Player p = client.getLocalPlayer();
                    return p == null ? null : p.getWorldLocation();
                });
                if (here == null)
                {
                    status.set("waiting for player (loading / disconnect?)");
                    Thread.sleep(TICK_MS);
                    continue;
                }

                // The level-up popup interrupts everything else — dismiss
                // first, then run state-specific logic on the next tick.
                if (safeDismissLevelUp())
                {
                    Thread.sleep(TICK_MS);
                    continue;
                }
                switch (state.get())
                {
                    case BANKING:        tickBanking();        break;
                    case WALK_TO_COOK:   tickWalk(true);       break;
                    case LIGHTING_FIRE:  tickLightingFire();   break;
                    case COOKING:        tickCooking();        break;
                    case WALK_TO_BANK:   tickWalk(false);      break;
                    case ABORTED:
                    case IDLE:
                    default:             running.set(false);   break;
                }
                Thread.sleep(TICK_MS);
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            running.set(false);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // BANKING
    // ────────────────────────────────────────────────────────────────

    /**
     * Banking flow:
     * <ol>
     *   <li>Walk to bank area if not there.</li>
     *   <li>If bank not open → click random booth.</li>
     *   <li>If bank open and inventory has cooked / burnt food → deposit.</li>
     *   <li>If bank open and inventory needs tinderbox / raw food → withdraw.
     *       Missing in bank ⇒ ABORT (close bank first).</li>
     *   <li>If bank open and inventory ready → close bank, transition to
     *       WALK_TO_COOK.</li>
     * </ol>
     * Paced at ≥ {@link #BANK_PACE_MS} between any two dispatches.
     */
    private void tickBanking() throws InterruptedException
    {
        CookingLocation l = location.get();
        if (!playerInArea(l.bankArea()))
        {
            UniversalWalker.Status s = walker.tick(l.cookToBank());
            status.set("bank: walking to bank (" + s + ")");
            if (s == UniversalWalker.Status.ARRIVED)
            {
                walker.reset();
                walkerStuckCount = 0;
            }
            else if (s == UniversalWalker.Status.STUCK
                  || s == UniversalWalker.Status.ERROR)
            {
                walkerStuckCount++;
                log.info("cook bank: walker {} (count={})", s, walkerStuckCount);
                walker.reset();
                if (walkerStuckCount > WALKER_MAX_STUCK)
                {
                    // Genuinely can't make progress to bank — only abort
                    // here, since this is a long-term unrecoverable
                    // location issue (player blocked behind a wall, etc).
                    abortWithStatus("walker repeatedly stuck heading to bank — aborting");
                }
            }
            return;
        }
        // Reset walker counter — we made it to the bank area.
        walkerStuckCount = 0;

        long now = System.currentTimeMillis();
        long since = lastBankActionAtMs == 0 ? Long.MAX_VALUE : now - lastBankActionAtMs;
        if (since < BANK_PACE_MS)
        {
            status.set("bank: pacing (" + since + "ms)");
            return;
        }

        // Bank PIN keypad up? We don't enter PINs — abort cleanly.
        // Re-clicking the booth during PIN entry can lock the account
        // out, which is much worse than just stopping.
        Boolean pinUp = onClient(bank::isBankPinUp);
        if (Boolean.TRUE.equals(pinUp))
        {
            abortWithStatus("bank PIN required — aborting (enter manually)");
            return;
        }

        boolean open = onClient(bank::isBankOpen);
        if (!open)
        {
            // Reset bank-loaded grace timer; we're about to (re)open.
            bankOpenedAtMs = 0L;
            status.set("bank: opening");
            dispatcher.lastErrorMessage();   // clear pre-existing error
            boolean clicked = bank.clickBankBoothRandom();
            if (clicked)
            {
                lastBankActionAtMs = now;
                bankFailCount = 0;
            }
            else
            {
                bankFailCount++;
                if (bankFailCount > BANK_MAX_FAIL)
                {
                    abortWithStatus("no bank booth in range after "
                        + BANK_MAX_FAIL + " attempts — aborting");
                }
            }
            return;
        }
        // Bank is open. Track when we first saw it open this entry so
        // the missing-item check can wait for the container to populate.
        if (bankOpenedAtMs == 0L) bankOpenedAtMs = now;

        // Surface any dispatch error from the previous bank action and
        // count consecutive failures.
        String dispErr = dispatcher.lastErrorMessage();
        if (dispErr != null)
        {
            log.info("cook bank: dispatcher error '{}'", dispErr);
            bankFailCount++;
            if (bankFailCount > BANK_MAX_FAIL)
            {
                abortWithBankClosed("bank dispatcher errors > " + BANK_MAX_FAIL
                    + " ('" + dispErr + "') — aborting");
                return;
            }
        }

        // Tally cooked/burnt for the trip we just finished — only once
        // per banking visit, on the FIRST bank-open tick. setState()
        // resets the flag so the next visit tallies fresh.
        if (!tallyDoneThisVisit)
        {
            tallyCookedBurnt();
            tallyDoneThisVisit = true;
        }

        boolean hasCooked = inventoryHasAnyCooked();
        boolean hasBurnt  = inventoryHasAnyBurnt();
        if (hasCooked || hasBurnt)
        {
            status.set("bank: depositing inventory");
            if (clickDepositInventoryThreadSafe())
            {
                lastBankActionAtMs = now;
                lastInventoryChangeAtMs = now;   // mark "we did something"
                bankFailCount = 0;
            }
            else
            {
                bankFailCount++;
            }
            return;
        }

        int rawId = rawFoodId.get();
        int rawInInv = cook.inventoryAmount(rawId);
        boolean needTinderbox = l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS
            && cook.inventoryAmount(ItemID.TINDERBOX) <= 0;

        // Before reading bank contents, ensure the container has loaded.
        // If not loaded yet AND we've been waiting < grace period, retry.
        boolean ready = bank.bankReady();
        if (!ready && (now - bankOpenedAtMs) < BANK_LOAD_GRACE_MS)
        {
            status.set("bank: waiting for container to populate");
            return;
        }
        if (!ready)
        {
            abortWithBankClosed("bank container did not populate in "
                + BANK_LOAD_GRACE_MS + "ms — aborting");
            return;
        }

        if (needTinderbox)
        {
            if (!bank.bankContainsItem(ItemID.TINDERBOX))
            {
                abortWithBankClosed("bank missing Tinderbox — aborting");
                return;
            }
            status.set("bank: withdrawing tinderbox");
            if (bank.withdrawOne(ItemID.TINDERBOX))
            {
                lastBankActionAtMs = now;
                bankFailCount = 0;
            }
            else bankFailCount++;
            return;
        }

        if (rawInInv == 0)
        {
            if (!bank.bankContainsItem(rawId))
            {
                CookingFood.Entry e = CookingFood.byRawId(rawId);
                String name = e == null ? ("id=" + rawId) : e.label;
                abortWithBankClosed("bank missing " + name + " — aborting");
                return;
            }
            status.set("bank: withdrawing raw food");
            if (bank.withdrawAll(rawId))
            {
                lastBankActionAtMs = now;
                bankFailCount = 0;
            }
            else bankFailCount++;
            return;
        }

        // Inventory is ready — close bank and head out.
        status.set("bank: closing");
        if (bank.closeBank())
        {
            lastBankActionAtMs = now;
            bankFailCount = 0;
        }
        else bankFailCount++;
        boolean stillOpen = onClient(bank::isBankOpen);
        if (!stillOpen)
        {
            // Snapshot a fresh trip baseline now that we leave the bank.
            snapshotTripBaseline();
            setState(State.WALK_TO_COOK);
        }
    }

    /** Read the deposit-inv widget on the client thread, dispatch the
     *  click off-thread. {@link BankInteraction#clickDepositInventory}
     *  reads the widget on the caller's thread, which trips the engine's
     *  client-thread assertion under -ea. */
    private boolean clickDepositInventoryThreadSafe() throws InterruptedException
    {
        Rectangle b = onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
            if (w == null || w.isHidden()) return null;
            Rectangle r = w.getBounds();
            return r == null || r.isEmpty() ? null : r;
        });
        if (b == null) return false;
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
        return true;
    }

    /** Close the bank (best effort) then transition to ABORTED with
     *  the given status. Used for "missing item in bank" paths so we
     *  don't leave the widget open. */
    private void abortWithBankClosed(String reason) throws InterruptedException
    {
        log.warn("cook: {}", reason);
        tryCloseBankBestEffort();
        abortWithStatus(reason);
    }

    /** Best-effort bank close — ignores errors. Used when recovering
     *  from transient errors (we want the bank closed before the next
     *  retry attempt, but we don't want a close failure to escalate). */
    private void tryCloseBankBestEffort()
    {
        try
        {
            Boolean isOpen = onClient(bank::isBankOpen);
            if (Boolean.TRUE.equals(isOpen)) bank.closeBank();
        }
        catch (Throwable th)
        {
            log.warn("cook: best-effort closeBank threw", th);
        }
    }

    private void abortWithStatus(String s)
    {
        status.set(s);
        setState(State.ABORTED);
    }

    // ────────────────────────────────────────────────────────────────
    // WALK_TO_COOK / WALK_TO_BANK
    // ────────────────────────────────────────────────────────────────

    private void tickWalk(boolean toCook) throws InterruptedException
    {
        CookingLocation l = location.get();
        UniversalWalker.Status st = walker.tick(toCook ? l.bankToCook() : l.cookToBank());
        status.set((toCook ? "→ cook" : "→ bank") + ": " + st);
        switch (st)
        {
            case ARRIVED:
                walker.reset();
                walkerStuckCount = 0;
                if (toCook)
                {
                    setState(l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS
                        ? State.LIGHTING_FIRE : State.COOKING);
                }
                else
                {
                    setState(State.BANKING);
                }
                break;
            case STUCK:
            case ERROR:
                walkerStuckCount++;
                log.info("cook walk: walker {} on {} (count={})",
                    st, toCook ? "outbound" : "return", walkerStuckCount);
                walker.reset();
                if (walkerStuckCount > WALKER_MAX_STUCK)
                {
                    // Truly stuck — player blocked, plane mismatch, etc.
                    // Abort here (this is unrecoverable in practice).
                    abortWithStatus("walker stuck " + walkerStuckCount
                        + "× on " + (toCook ? "outbound" : "return"));
                }
                // else: transient stuck, next tick resumes naturally.
                break;
            default:
                break;
        }
    }

    // ────────────────────────────────────────────────────────────────
    // LIGHTING_FIRE
    // ────────────────────────────────────────────────────────────────

    private void tickLightingFire() throws InterruptedException
    {
        CookingLocation l = location.get();
        long now = System.currentTimeMillis();

        // If we previously dispatched a light and are waiting for the
        // fire to appear: verify ON THE TILE WE LIT, not by name search.
        // A fire elsewhere in range is someone else's — we don't claim
        // it. The starter-script reference does the same — wait until
        // a Fire materialises on the lit tile (low-level firemaking can
        // take many tries before success).
        if (litFireTile != null)
        {
            CookingInteraction.Match ours = cook.findFireAt(l.heatSourceName(), litFireTile);
            if (ours != null)
            {
                status.set("light: fire spawned at " + litFireTile + " — cooking");
                setState(State.COOKING);
                // Keep litFireTile set so tickCooking targets only THIS fire.
                litFireTile = ours.tile;
                return;
            }
            if (cook.isFiremaking())
            {
                status.set("light: firemaking animation, waiting for fire");
                return;
            }
            // No fire yet, no animation — engine may still be queueing
            // the action. Hold up to LIGHT_TIMEOUT_MS, then back off.
            if (now - lightDispatchedAtMs > LIGHT_TIMEOUT_MS)
            {
                String e = dispatcher.lastErrorMessage();
                log.info("cook: no fire on {} after {}ms — retrying (last err: {})",
                    litFireTile, LIGHT_TIMEOUT_MS, e);
                litFireTile = null;
                lightDispatchedAtMs = 0L;
                lightingBackoffUntilMs = now + 2000L;
                return;
            }
            status.set("light: waiting for fire on " + litFireTile);
            return;
        }

        // Backoff after a failed attempt — gives the engine time to
        // settle and avoids hammering on a tile that won't light.
        if (now < lightingBackoffUntilMs)
        {
            status.set("light: backoff (" + (lightingBackoffUntilMs - now) + "ms)");
            return;
        }

        if (cook.inventoryAmount(ItemID.TINDERBOX) <= 0)
        {
            status.set("light: no tinderbox in inv — back to bank");
            setState(State.WALK_TO_BANK);
            return;
        }

        int logsId = l.groundLogsItemId();
        CookingInteraction.Match logs = cook.findGroundLogs(logsId);
        if (logs == null)
        {
            status.set("light: waiting for log spawn");
            return;
        }

        if (cook.isFiremaking())
        {
            // Already firemaking — engine is animating a previous click.
            // Don't fire another use-tinderbox until that finishes.
            status.set("light: firemaking already in progress");
            return;
        }

        if (dispatcher.isBusy())
        {
            status.set("light: dispatcher busy");
            return;
        }

        status.set("light: use tinderbox");
        if (!cook.useTinderbox())
        {
            // The use-tinderbox dispatch errored (e.g. cursor missed
            // the slot, tinderbox briefly invisible). Back off and try
            // again next tick — DO NOT click logs without use-mode
            // engaged or the click resolves to "Take logs" and picks
            // up our own fuel.
            status.set("light: useTinderbox failed — backing off");
            lightingBackoffUntilMs = now + 1500L;
            return;
        }

        // Use-mode is engaged. Brief settle, re-resolve logs, click.
        Thread.sleep(400);
        CookingInteraction.Match logs2 = cook.findGroundLogs(logsId);
        if (logs2 == null)
        {
            status.set("light: logs despawned during use-mode");
            lightingBackoffUntilMs = System.currentTimeMillis() + 1000L;
            return;
        }
        if (!logs.tile.equals(logs2.tile))
        {
            status.set("light: logs moved during use-mode — retry");
            lightingBackoffUntilMs = System.currentTimeMillis() + 800L;
            return;
        }
        if (!cook.clickLogsForLight(logs2))
        {
            status.set("light: logs tile didn't project — retry");
            lightingBackoffUntilMs = System.currentTimeMillis() + 800L;
            return;
        }
        // Dispatch sent. Remember THIS tile — next tick(s) verify a
        // Fire spawns on it. If lighting fails the engine doesn't
        // tell us; we just don't see a Fire on the tile, time out,
        // and retry.
        litFireTile = logs2.tile;
        lightDispatchedAtMs = System.currentTimeMillis();
        status.set("light: clicked logs at " + litFireTile + " — waiting for fire");
    }

    // ────────────────────────────────────────────────────────────────
    // COOKING
    // ────────────────────────────────────────────────────────────────

    private void tickCooking() throws InterruptedException
    {
        CookingLocation l = location.get();
        int rawId = rawFoodId.get();
        long now = System.currentTimeMillis();

        // Confirm "Cook All" if the Skillmulti dialogue is up. Try
        // Space first; if the dialog doesn't close after a few attempts
        // (engine-version drift, focus issue), click the ALL widget
        // explicitly as a fallback.
        if (cook.isCookMenuOpen())
        {
            cookMenuConfirmAttempts++;
            if (cookMenuConfirmAttempts <= COOK_MENU_FALLBACK_AFTER)
            {
                status.set("cook: confirming Cook All (Space)");
                cook.confirmCookAll();
            }
            else
            {
                status.set("cook: confirming Cook All (widget click fallback)");
                cook.clickCookAllWidget();
            }
            // Reset stuck timer — opening Skillmulti is progress.
            // Only reset on the FIRST attempt of this open; if the
            // dialog stays open across many ticks we want the stuck
            // timer to eventually trip.
            if (cookMenuConfirmAttempts == 1) lastInventoryChangeAtMs = now;
            return;
        }
        // Skillmulti closed — reset confirm attempts for the next open.
        cookMenuConfirmAttempts = 0;

        // Track inventory progress for stuck detection. Any change in
        // raw count (a successful cook, a fire dying mid-batch, etc.)
        // counts as progress — reset the stuck counter. A DECREASE
        // specifically means the engine just cooked one (raw → cooked
        // or burnt), which is the signal "cook-all queue is running".
        int raw = cook.inventoryAmount(rawId);
        if (raw != lastRawCount)
        {
            if (lastRawCount >= 0 && raw < lastRawCount)
            {
                lastRawDecreaseAtMs = now;
            }
            lastRawCount = raw;
            lastInventoryChangeAtMs = now;
            cookStuckCount = 0;
        }
        if (lastInventoryChangeAtMs == 0) lastInventoryChangeAtMs = now;

        // Out of raw → done, head to bank.
        if (raw == 0)
        {
            status.set("cook: out of raw food — back to bank");
            setState(State.WALK_TO_BANK);
            return;
        }

        // For FIRE_FROM_LOGS: target ONLY the fire we lit (tracked by
        // tile in litFireTile). A different fire nearby is someone
        // else's — using their fire is bot-tell behavior. If our fire
        // died (no Fire object on the lit tile any more), pause a few
        // seconds (real players don't snap-relight) and re-light.
        // For RANGE: range objects are fixed-location; just find by name.
        CookingInteraction.Match heat;
        if (l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS)
        {
            if (litFireTile == null)
            {
                status.set("cook: no lit fire tracked — relighting");
                setState(State.LIGHTING_FIRE);
                return;
            }
            heat = cook.findFireAt(l.heatSourceName(), litFireTile);
            if (heat == null)
            {
                long pause = FIRE_DEATH_PAUSE_MIN_MS
                    + ThreadLocalRandom.current().nextLong(
                        FIRE_DEATH_PAUSE_MAX_MS - FIRE_DEATH_PAUSE_MIN_MS);
                status.set("cook: fire died at " + litFireTile
                    + " — pausing " + pause + "ms before relight");
                WorldPoint deadTile = litFireTile;
                setState(State.LIGHTING_FIRE);
                // setState() reset lightingBackoffUntilMs, so set it
                // AFTER. Don't preserve litFireTile — the fire's gone,
                // the lit-tile-watcher in tickLightingFire would never
                // find a fire on it. We'll pick a fresh log spawn.
                lightingBackoffUntilMs = System.currentTimeMillis() + pause;
                log.info("cook: fire at {} died, backing off {}ms", deadTile, pause);
                return;
            }
        }
        else
        {
            heat = cook.findHeatSource(l.heatSourceName());
            if (heat == null)
            {
                status.set("cook: range out of view — walking back");
                setState(State.WALK_TO_COOK);
                return;
            }
        }

        // OSRS interaction range cap: the engine refuses object
        // interactions past ~8 tiles. If the resolved heat source is
        // further, walk back into the cook area before re-attempting —
        // otherwise we'd burn the stuck-cook timer firing useless
        // "use food on heat" sequences.
        WorldPoint pp = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        if (pp != null && heat.tile != null
            && pp.getPlane() == heat.tile.getPlane()
            && Math.max(Math.abs(pp.getX() - heat.tile.getX()),
                        Math.abs(pp.getY() - heat.tile.getY())) > 8)
        {
            status.set("cook: heat source out of interaction range — walking closer");
            setState(State.WALK_TO_COOK);
            return;
        }

        // Currently cooking → wait.
        if (cook.isCooking())
        {
            status.set("cook: cooking (" + raw + " raw left)");
            return;
        }

        // Animation-based "cooking" detection has gaps: between cooks
        // in a Cook-All queue the engine briefly swaps to no-animation
        // or a transient pose, and isCooking() returns false even
        // though the engine is still processing the queue. If we
        // re-dispatch use-raw during that gap, the cursor lands on a
        // slot that the engine has converted to cooked food by the
        // time it arrives — locking use-mode onto cooked chicken.
        // Second safety net: if a raw count decrease happened recently,
        // treat the batch as still active and just wait.
        if (lastRawDecreaseAtMs > 0 && (now - lastRawDecreaseAtMs) < COOK_BATCH_SETTLE_MS)
        {
            status.set("cook: batch in progress (" + raw
                + " raw, last cook " + (now - lastRawDecreaseAtMs) + "ms ago)");
            return;
        }

        // Stuck check — no inventory progress for COOK_STUCK_MS. Reset
        // and retry — but cap at COOK_MAX_STUCK consecutive cycles.
        // Most "stuck" cases are recoverable (camera shifted, click
        // missed, fire briefly out of LOS); a few real ones aren't
        // (use-mode dropped silently, server lag pile-up) and we want
        // the user to see an abort then.
        if (now - lastInventoryChangeAtMs > COOK_STUCK_MS)
        {
            cookStuckCount++;
            log.info("cook: no progress for {}ms (count={})", COOK_STUCK_MS, cookStuckCount);
            if (cookStuckCount > COOK_MAX_STUCK)
            {
                abortWithStatus("cook: stuck " + cookStuckCount + "× — aborting");
                return;
            }
            lastInventoryChangeAtMs = now;
            lastCookActionAtMs = 0L;
            lastRawCount = -1;
            // Stay in COOKING; next iteration re-issues use-raw-on-fire.
        }

        if (dispatcher.isBusy())
        {
            status.set("cook: dispatcher busy");
            return;
        }
        // Pace cooking dispatches — don't fire two "use food on heat"
        // sequences within one OSRS tick.
        if (lastCookActionAtMs > 0 && (now - lastCookActionAtMs) < COOK_PACE_MS)
        {
            status.set("cook: pacing (" + (now - lastCookActionAtMs) + "ms)");
            return;
        }

        status.set("cook: use raw food on heat source");
        if (!cook.useRawFood(rawId))
        {
            status.set("cook: raw food slot vanished — re-checking");
            return;
        }
        Thread.sleep(400);
        // Re-resolve heat source after brief wait.
        CookingInteraction.Match heat2 = cook.findHeatSource(l.heatSourceName());
        if (heat2 == null)
        {
            status.set("cook: heat source vanished mid-use");
            return;
        }
        cook.clickHeatSourceForCook(heat2);
        lastCookActionAtMs = System.currentTimeMillis();
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────

    /** Try to dismiss the level-up popup. Returns true iff a tap was
     *  dispatched (caller skips the rest of the tick to give the dialog
     *  one tick to vanish). Wrapped in try/catch so any exotic widget
     *  state error doesn't kill the loop. */
    private boolean safeDismissLevelUp()
    {
        try { return cook.dismissLevelUp(); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        catch (Throwable th) { log.warn("cook: dismissLevelUp threw", th); return false; }
    }

    private boolean inventoryHasAnyCooked() throws InterruptedException
    {
        for (CookingFood.Entry e : CookingFood.all())
        {
            if (cook.inventoryAmount(e.cookedId) > 0) return true;
        }
        return false;
    }

    private boolean inventoryHasAnyBurnt() throws InterruptedException
    {
        for (CookingFood.Entry e : CookingFood.all())
        {
            if (cook.inventoryAmount(e.burntId) > 0) return true;
        }
        return false;
    }

    /** Add the trip's cooked / burnt delta to the cumulative totals.
     *  Calculates {@code current - snapshot} so a resumed script with
     *  pre-existing cooked food in the inventory doesn't over-count.
     *  Called on the first BANKING tick after the trip. */
    private void tallyCookedBurnt() throws InterruptedException
    {
        CookingFood.Entry e = CookingFood.byRawId(rawFoodId.get());
        if (e == null) return;
        int cookedNow = cook.inventoryAmount(e.cookedId);
        int burntNow  = cook.inventoryAmount(e.burntId);
        int dCooked = Math.max(0, cookedNow - tripStartCooked);
        int dBurnt  = Math.max(0, burntNow  - tripStartBurnt);
        if (dCooked > 0) cookedCount.addAndGet(dCooked);
        if (dBurnt  > 0) burntCount.addAndGet(dBurnt);
    }

    /** Snapshot the inventory's current cooked / burnt count for the
     *  active food. Reset when transitioning out of BANKING into a
     *  cook trip so the next tally is a true delta. */
    private void snapshotTripBaseline()
    {
        CookingFood.Entry e = CookingFood.byRawId(rawFoodId.get());
        if (e == null) { tripStartCooked = 0; tripStartBurnt = 0; return; }
        try
        {
            tripStartCooked = cook.inventoryAmount(e.cookedId);
            tripStartBurnt  = cook.inventoryAmount(e.burntId);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            tripStartCooked = 0;
            tripStartBurnt = 0;
        }
    }

    private int inventoryAmountSafe(int id)
    {
        try { return cook.inventoryAmount(id); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return 0; }
        catch (Throwable th) { return 0; }
    }

    private boolean playerInArea(WorldArea area)
    {
        WorldPoint here = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        return here != null && areaContains(area, here);
    }

    private static boolean areaContains(WorldArea a, WorldPoint p)
    {
        return p.getPlane() == a.getPlane()
            && p.getX() >= a.getX() && p.getX() < a.getX() + a.getWidth()
            && p.getY() >= a.getY() && p.getY() < a.getY() + a.getHeight();
    }

    private void setState(State s)
    {
        state.set(s);
        lastBankActionAtMs = 0L;
        lastCookActionAtMs = 0L;
        lightingStartedAtMs = 0L;
        lastInventoryChangeAtMs = 0L;
        lastRawCount = -1;
        tallyDoneThisVisit = false;
        bankOpenedAtMs = 0L;
        bankFailCount = 0;
        cookMenuConfirmAttempts = 0;
        lightingBackoffUntilMs = 0L;
        walkerStuckCount = 0;
        cookStuckCount = 0;
        lastRawDecreaseAtMs = 0L;
        // Clear the lit-fire tracking on any transition out of the
        // LIGHTING_FIRE / COOKING pair. The next cook trip will light
        // a fresh fire (or, if we're going BACK to LIGHTING_FIRE
        // because our fire died, the entry path immediately picks a
        // fresh log spawn so the tile gets re-set this tick).
        if (s != State.LIGHTING_FIRE && s != State.COOKING)
        {
            litFireTile = null;
            lightDispatchedAtMs = 0L;
        }
    }

    private <T> T onClient(Supplier<T> s)
    {
        java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(s.get()); }
            catch (Throwable th) { log.warn("cook: onClient threw", th); }
            finally { latch.countDown(); }
        });
        try
        {
            // 2s cap — matches the dispatcher's hop. If the client thread
            // is wedged we want to surface that as a status update rather
            // than hang the whole script worker.
            if (!latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS))
            {
                log.warn("cook: onClient timed out");
                return null;
            }
        }
        catch (InterruptedException ie)
        { Thread.currentThread().interrupt(); return null; }
        return ref.get();
    }
}
