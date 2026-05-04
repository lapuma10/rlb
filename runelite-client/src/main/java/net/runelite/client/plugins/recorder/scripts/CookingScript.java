package net.runelite.client.plugins.recorder.scripts;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.RecorderConfig;
import net.runelite.client.plugins.recorder.cook.CookingFood;
import net.runelite.client.plugins.recorder.cook.CookingInteraction;
import net.runelite.client.plugins.recorder.cook.CookingLocation;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.walker.UniversalWalker;
import net.runelite.client.plugins.recorder.widget.SidebarTab;
import net.runelite.client.plugins.recorder.widget.SidebarTabActions;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.SequenceManager;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.activities.banking.BankingSequenceFactory;
import net.runelite.client.sequence.activities.banking.BankingSequencePlan;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.dispatch.InputOwnership;
import net.runelite.client.sequence.internal.ClientObserver;
import net.runelite.client.sequence.telemetry.TelemetryRecord;

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
    /** Min/max random delay before dismissing the level-up popup. A real
     *  player glances at the screen, reads the level, then clicks away —
     *  dismissing instantly every time is a visible bot tell. */
    private static final long LEVEL_UP_DISMISS_MIN_MS = 3_000;
    private static final long LEVEL_UP_DISMISS_MAX_MS = 34_000;
    /** How long after a light dispatch with NO firemaking animation seen we
     *  treat the click as swallowed and retry. Covers the common case where
     *  logs respawn on an already-burning tile (click does nothing, player
     *  never starts the firemaking anim). Much shorter than LIGHT_TIMEOUT_MS
     *  which is reserved for slow low-level attempts where the anim did fire. */
    private static final long LIGHT_NO_ANIM_TIMEOUT_MS = 4_000;

    public enum State
    {
        IDLE,
        /** Legacy hand-coded banking flow. */
        BANKING_LEGACY,
        /** Banking via the sequence engine (experimental; gated by config.useEngineBanking()). */
        BANKING_VIA_ENGINE,
        WALK_TO_COOK,
        LIGHTING_FIRE,
        COOKING,
        WALK_TO_BANK,
        ABORTED
    }

    /** Owner token for the sequence-engine banking path. */
    static final String OWNER_TOKEN = "cooking-banking";

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final RecorderConfig config;
    private final InputOwnership inputOwnership;
    private final UniversalWalker walker;
    private final BankInteraction bank;
    private final CookingInteraction cook;
    private final SidebarTabActions sidebarTabs;

    // ── Engine-banking fields ────────────────────────────────────────
    /** Lazily built on first BANKING_VIA_ENGINE entry. */
    private SequenceManager bankingManager;
    /** True while a bankingManager.run() call is pending / in-flight.
     *  Prevents a second run() before the engine transitions to RUNNING. */
    private final AtomicBoolean bankingStartRequested = new AtomicBoolean();
    /** Guards scheduleEngineTick() against double-scheduling within one
     *  script tick when the engine is still RUNNING. */
    private final AtomicBoolean tickInFlight = new AtomicBoolean();
    /** Wall-clock millis when {@link #tickInFlight} was last latched true.
     *  Watchdog: if a tick has been "in flight" for more than 30s the engine
     *  is wedged (a step's onStart / check threw and never released the
     *  flag); force-reset and log so the next tick can proceed. */
    private final AtomicLong tickInFlightStartMs = new AtomicLong();

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
    /** True once we observe the firemaking animation after a light dispatch.
     *  Lets us distinguish "click worked, just waiting for fire" (anim seen)
     *  from "click was swallowed entirely" (no anim within LIGHT_NO_ANIM_TIMEOUT_MS),
     *  e.g. because logs respawned on a tile that already has a fire burning. */
    private boolean seenFiremakingAnimSinceDispatch;
    /** Wall-clock time when the level-up popup was first seen this occurrence.
     *  0 when no popup is visible. We wait a random 2–6 s before dismissing. */
    private long levelUpFirstSeenAtMs;
    /** The randomised wall-clock time at which we will actually press Space
     *  to dismiss the currently-visible level-up popup. */
    private long levelUpDismissAfterMs;
    /** One-shot guard for the first-start inventory cleanup. Reset in
     *  {@link #start()}. On the first bank visit of a session: if the
     *  inventory contains anything other than raw food we're cooking
     *  (and the tinderbox when FIRE_FROM_LOGS), we click the deposit-all
     *  orb to start with a clean inventory before withdrawing. Once
     *  cleared, the steady-state per-item cooked/burnt deposit takes over. */
    private final AtomicBoolean firstStartCleanupDone = new AtomicBoolean(false);

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
                         TransportResolver resolver,
                         RecorderConfig config,
                         InputOwnership inputOwnership)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.config = config;
        this.inputOwnership = inputOwnership;
        this.walker = new UniversalWalker(client, clientThread, dispatcher, resolver);
        this.bank = new BankInteraction(client, clientThread, dispatcher);
        this.cook = new CookingInteraction(client, clientThread, dispatcher);
        this.sidebarTabs = new SidebarTabActions(client, clientThread, dispatcher);
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
        // Reset one-shot first-start cleanup guard so the first banking
        // visit of this session decides whether to deposit-all the
        // inventory before the normal withdraw loop runs.
        firstStartCleanupDone.set(false);
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
            return bankingState();
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
            // Post-login the inventory tab is often closed on the modern
            // client. Use-tinderbox-on-logs and use-raw-on-fire both target
            // inventory items, so the tab must be visible before the first
            // dispatch.
            if (!sidebarTabs.openTabAndWait(SidebarTab.INVENTORY, 2_000L))
                log.debug("cook: could not confirm inventory tab open at startup");

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
                    SequenceSleep.sleep(client, TICK_MS);
                    continue;
                }

                // Check level-up popup — dismiss with Space after a random
                // delay, but don't block the FSM. Cooking and walking will
                // clear the notification too, so it's fine to keep going.
                safeDismissLevelUp();
                switch (state.get())
                {
                    case BANKING_LEGACY:     tickBankingLegacy();     break;
                    case BANKING_VIA_ENGINE: tickBankingViaEngine();  break;
                    case WALK_TO_COOK:       tickWalk(true);          break;
                    case LIGHTING_FIRE:      tickLightingFire();      break;
                    case COOKING:            tickCooking();           break;
                    case WALK_TO_BANK:       tickWalk(false);         break;
                    case ABORTED:
                    case IDLE:
                    default:                 running.set(false);      break;
                }
                SequenceSleep.sleep(client, TICK_MS);
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
     *
     * @deprecated Use {@link #tickBankingViaEngine()} when
     *     {@code config.useEngineBanking()} is enabled.
     */
    @Deprecated
    private void tickBankingLegacy() throws InterruptedException
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
            boolean clicked = bank.tryClickBankBoothRandom();
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

        // First-start inventory cleanup. The steady-state loop deposits
        // cooked/burnt food per-item (preserving tinderbox + unrelated
        // items across the cook → bank cycle), but on the FIRST bank
        // visit of a session the player may be carrying anything —
        // leftovers from another script, quest items, half a stack of
        // logs, etc. Behaviour matches the user request:
        //   * inventory holds ONLY raw food we're cooking (and the
        //     tinderbox when FIRE_FROM_LOGS): skip deposit, fall through
        //     to the normal withdraw / close path so partial inventories
        //     get topped up;
        //   * anything else: click the deposit-all orb so the next tick
        //     enters the withdraw phase with an empty inventory.
        // Bank container readiness is already gated above.
        if (!firstStartCleanupDone.get())
        {
            if (inventoryHasJunkForCooking(l))
            {
                status.set("bank: first-start — depositing all (clearing junk)");
                if (bank.depositAllInventory())
                {
                    lastBankActionAtMs = now;
                    lastInventoryChangeAtMs = now;
                    bankFailCount = 0;
                    firstStartCleanupDone.set(true);
                }
                else
                {
                    bankFailCount++;
                    if (bankFailCount > BANK_MAX_FAIL)
                    {
                        abortWithBankClosed("first-start deposit-all failed "
                            + BANK_MAX_FAIL + "× — aborting");
                        return;
                    }
                }
                return;
            }
            firstStartCleanupDone.set(true);
        }

        // Per-item deposit, not the deposit-everything orb: tinderbox
        // (and any other kept utility) must STAY in the inventory
        // across the bank → cook → bank loop. We right-click each
        // cooked / burnt food in turn and pick "Deposit-All", one
        // food per tick (paced via lastBankActionAtMs).
        int depositId = firstDepositTargetId();
        if (depositId > 0)
        {
            status.set("bank: deposit-all item " + depositId);
            if (bank.tryDepositAll(depositId))
            {
                lastBankActionAtMs = now;
                lastInventoryChangeAtMs = now;
                bankFailCount = 0;
            }
            else
            {
                bankFailCount++;
                if (bankFailCount > BANK_MAX_FAIL)
                {
                    abortWithBankClosed("deposit-all itemId=" + depositId
                        + " failed " + BANK_MAX_FAIL
                        + "× — slot widget unresolvable, aborting");
                    return;
                }
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

        // Withdraw-order preference: when both tinderbox and raw food
        // are needed, take whichever is currently visible in the bank
        // FIRST. The default order would be tinderbox → raw, but if the
        // bank is scrolled such that raw is on screen and tinderbox is
        // not, the tinderbox-first path scrolls UP for tinderbox, then
        // back DOWN past where the food was sitting — wasted scroll and
        // bot-tell. Tie-break: tinderbox first when both are visible
        // (tinderbox is one slot, raw is the bulk withdraw — order
        // doesn't matter visually). Falls through to default order if
        // neither is visible (one will need scrolling either way).
        boolean takeRawFirst = needTinderbox && rawInInv == 0
            && !bank.isItemVisible(ItemID.TINDERBOX)
            && bank.isItemVisible(rawId);

        if (needTinderbox && !takeRawFirst)
        {
            if (!bank.bankContainsItem(ItemID.TINDERBOX))
            {
                abortWithBankClosed("bank missing Tinderbox — aborting");
                return;
            }
            status.set("bank: withdrawing tinderbox");
            if (bank.tryWithdrawOne(ItemID.TINDERBOX))
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
            status.set("bank: withdrawing raw food"
                + (takeRawFirst ? " (visible — taking before tinderbox)" : ""));
            if (bank.tryWithdrawAll(rawId))
            {
                lastBankActionAtMs = now;
                bankFailCount = 0;
            }
            else bankFailCount++;
            return;
        }

        // Inventory is ready — close bank and head out.
        status.set("bank: closing");
        if (bank.tryCloseBank())
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
            if (Boolean.TRUE.equals(isOpen)) bank.tryCloseBank();
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
    // BANKING_VIA_ENGINE
    // ────────────────────────────────────────────────────────────────

    /**
     * Engine-backed banking flow. Delegates the full open → deposit → withdraw →
     * verify → close pipeline to the sequence engine via
     * {@link BankingSequenceFactory#prepareCookingLoadout}.
     *
     * <p>State machine on {@link SequenceState}:
     * <ul>
     *   <li>IDLE (never started / just completed) → build plan, acquire lease, start engine.</li>
     *   <li>RUNNING → schedule an engine tick; update status from telemetry.</li>
     *   <li>FAILED → release lease, abort script.</li>
     *   <li>PAUSED → no-op (engine resumes externally or on next lease check).</li>
     * </ul>
     */
    private void tickBankingViaEngine() throws InterruptedException
    {
        CookingLocation l = location.get();
        if (!playerInArea(l.bankArea()))
        {
            setState(State.WALK_TO_BANK);
            return;
        }

        // Lazy-build the manager once and reuse across ticks.
        if (bankingManager == null)
        {
            bankingManager = buildBankingManager();
        }

        SequenceState engineState = bankingManager.state();

        switch (engineState)
        {
            case IDLE:
            {
                // IDLE can mean two things:
                //  (a) Never started yet — bankingStartRequested is false and
                //      we haven't seen a RUNNING state this visit.
                //  (b) Just completed (RUNNING → IDLE on success) — detected by
                //      bankingStartRequested having been cleared by the RUNNING branch
                //      on the previous tick but the engine not having transitioned to
                //      FAILED.  We distinguish via the inputOwnership lease: if we
                //      still hold the lease after clearing bankingStartRequested,
                //      that means a run completed successfully.
                if (bankingStartRequested.get())
                {
                    // run() dispatched but not yet processed — wait.
                    return;
                }
                if (inputOwnership.isOwner(OWNER_TOKEN))
                {
                    // We hold the lease but engine is IDLE → completed successfully.
                    releaseLeaseLogged();
                    setState(State.WALK_TO_COOK);
                    return;
                }

                // Initial state: acquire lease and start the engine.
                if (!inputOwnership.tryAcquire(OWNER_TOKEN))
                {
                    status.set("bank-engine: waiting for input lease");
                    return;
                }

                // Build the plan.
                CookingFood.Entry food = CookingFood.byRawId(rawFoodId.get());
                if (food == null)
                {
                    releaseLeaseLogged();
                    abortWithStatus("bank-engine: unknown food id=" + rawFoodId.get());
                    return;
                }
                boolean needsTinderbox =
                    l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS;
                BankingSequencePlan plan = BankingSequenceFactory.prepareCookingLoadout(
                    l, food, needsTinderbox, bank);

                // Register reactives, then start the root.
                bankingManager.clearReactives();
                for (Step r : plan.reactiveSteps())
                {
                    bankingManager.registerReactive(r, 200);
                }

                bankingStartRequested.set(true);
                try
                {
                    bankingManager.run(plan.root());
                }
                catch (Throwable t)
                {
                    // run() threw before the engine could accept the step —
                    // release lease and allow a retry next tick.
                    releaseLeaseLogged();
                    bankingStartRequested.set(false);
                    throw t;
                }
                status.set("bank-engine: started");
                break;
            }

            case RUNNING:
            {
                // Clear the in-flight start guard once we see RUNNING.
                bankingStartRequested.set(false);
                scheduleEngineTick();
                // Surface the most recent telemetry record as status.
                String last = readLastTelemetry(bankingManager);
                if (last != null) status.set("bank-engine: " + last);
                break;
            }

            case FAILED:
            {
                bankingStartRequested.set(false);
                releaseLeaseLogged();
                String reason = lastFailureReason(bankingManager);
                abortWithStatus("bank-engine failed: " + (reason != null ? reason : "unknown"));
                break;
            }

            case PAUSED:
                // Engine paused externally — do nothing, wait for resume.
                break;

            default:
                break;
        }
    }

    /**
     * Build a fresh {@link SequenceManager} wired for production banking.
     * Called once lazily on the first entry to BANKING_VIA_ENGINE.
     *
     * <p><b>Threading:</b> the manager's scheduler is {@code Runnable::run} so
     * {@code run/pause/stop/register*} all execute synchronously on the calling
     * (daemon worker) thread. {@link #scheduleEngineTick()} then drives
     * {@link net.runelite.client.sequence.SequenceEngine#advanceTick()} directly
     * on the daemon thread — {@link ClientObserver} marshals its client reads
     * via {@link ClientThread#invokeLater} + latch internally. This avoids the
     * client-thread deadlock that would otherwise occur when a step's onStart
     * (e.g. {@link BankInteraction#depositAll}) tries to hop back to the client
     * thread while the engine itself is blocking the client thread on a snapshot.
     */
    private SequenceManager buildBankingManager()
    {
        SequenceManager m = SequenceManager.withDefaults();
        m.setObserver(new ClientObserver(client, clientThread));
        m.setDispatcher(dispatcher);
        m.setScheduler(Runnable::run);
        m.setInputOwnership(inputOwnership, OWNER_TOKEN);
        return m;
    }

    /**
     * Drive one engine tick on the calling (daemon worker) thread. Guards
     * against re-entry via {@link #tickInFlight}; if the flag has been latched
     * for more than 30s a wedged tick is assumed and the flag is force-reset
     * (best-effort recovery — caller will retry next iteration).
     */
    private void scheduleEngineTick()
    {
        long now = System.currentTimeMillis();
        if (!tickInFlight.compareAndSet(false, true))
        {
            long stuckFor = now - tickInFlightStartMs.get();
            if (stuckFor > 30_000)
            {
                log.warn("cook bank-engine: tick in flight for {}ms — force-resetting watchdog", stuckFor);
                tickInFlight.set(false);
            }
            return;
        }
        tickInFlightStartMs.set(now);
        try
        {
            if (bankingManager != null)
            {
                bankingManager.getEngine().advanceTick();
            }
        }
        catch (Throwable t)
        {
            log.warn("cook bank-engine: advanceTick threw", t);
        }
        finally
        {
            tickInFlight.set(false);
        }
    }

    /**
     * Release the banking input lease and warn-log if release returns false.
     * False indicates the lease was not held by us at the moment of release —
     * either it was never acquired or another holder claimed it (which would
     * be a logic bug worth seeing in the log). Centralized so all four
     * release sites observe the return value uniformly.
     */
    private void releaseLeaseLogged()
    {
        if (!inputOwnership.release(OWNER_TOKEN))
        {
            log.warn("cook bank-engine: failed to release lease (current owner={})",
                inputOwnership.currentOwner().orElse("none"));
        }
    }

    /**
     * Return a one-line summary from the most recent telemetry record, or
     * {@code null} if the ring buffer is empty.
     */
    private static String readLastTelemetry(SequenceManager m)
    {
        List<TelemetryRecord> tail = m.getTelemetry().tail(1);
        if (tail.isEmpty()) return null;
        TelemetryRecord r = tail.get(0);
        return r.event() + " " + r.stepName()
            + (r.payload() != null && !r.payload().isEmpty() ? ": " + r.payload() : "");
    }

    /**
     * Return the failure payload from the most recent FAILED telemetry record,
     * or {@code null} if none is found in the last 20 records.
     */
    private static String lastFailureReason(SequenceManager m)
    {
        List<TelemetryRecord> tail = m.getTelemetry().tail(20);
        for (int i = tail.size() - 1; i >= 0; i--)
        {
            TelemetryRecord r = tail.get(i);
            if (r.event() == TelemetryRecord.Event.FAILED)
            {
                return r.stepName() + ": " + r.payload();
            }
        }
        return null;
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
                    setState(bankingState());
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
                seenFiremakingAnimSinceDispatch = true;
                status.set("light: firemaking animation, waiting for fire");
                return;
            }
            // No fire, no animation currently. Two cases:
            //  (a) We never saw the anim at all — the click was swallowed
            //      (logs on a fire tile, or dispatcher missed the target).
            //      Retry quickly after LIGHT_NO_ANIM_TIMEOUT_MS.
            //  (b) We did see the anim (low-level FM retrying) — wait the
            //      full LIGHT_TIMEOUT_MS for the fire to actually spawn.
            if (!seenFiremakingAnimSinceDispatch
                    && (now - lightDispatchedAtMs) > LIGHT_NO_ANIM_TIMEOUT_MS)
            {
                log.info("cook: no firemaking anim seen after {}ms — click swallowed, retrying",
                    now - lightDispatchedAtMs);
                litFireTile = null;
                lightDispatchedAtMs = 0L;
                seenFiremakingAnimSinceDispatch = false;
                lightingBackoffUntilMs = now + 1000L;
                return;
            }
            if (now - lightDispatchedAtMs > LIGHT_TIMEOUT_MS)
            {
                String e = dispatcher.lastErrorMessage();
                log.info("cook: no fire on {} after {}ms — retrying (last err: {})",
                    litFireTile, LIGHT_TIMEOUT_MS, e);
                litFireTile = null;
                lightDispatchedAtMs = 0L;
                seenFiremakingAnimSinceDispatch = false;
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

        // Logs can respawn on a tile that already has a burning fire.
        // Trying to light them does nothing — the fire blocks the action.
        // If the fire is already there, claim it and skip straight to cooking.
        CookingInteraction.Match existingFire = cook.findFireAt(l.heatSourceName(), logs.tile);
        if (existingFire != null)
        {
            log.info("cook: fire already at log tile {} — using it directly", logs.tile);
            litFireTile = existingFire.tile;
            setState(State.COOKING);
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

        // Right-click logs → "Light" (tinderbox must be in inventory for
        // the option to appear; no use-mode dance needed).
        status.set("light: right-click logs — Light");
        if (!cook.lightLogsViaClick(logs))
        {
            status.set("light: Light missing — trying tinderbox on logs");
            AtomicBoolean clickedLogs = new AtomicBoolean(false);
            AtomicReference<String> lightFailure = new AtomicReference<>(null);
            boolean ranLightFallback = dispatcher.runExclusive(() -> {
                // If the failed right-click was caused by stale raw-food
                // use-mode, this hover is still on/near the logs and can
                // now be cleared directly without touching the sidebar state.
                dispatcher.clearSelectedWidgetTargetMode();
                if (!cook.useTinderboxOnWorker())
                {
                    lightFailure.set("light: tinderbox use failed");
                    return;
                }
                SequenceSleep.sleep(client, 350);
                CookingInteraction.Match logs2 = cook.findGroundLogs(logsId);
                if (logs2 == null)
                {
                    dispatcher.clearSelectedWidgetTargetMode();
                    lightFailure.set("light: logs despawned during tinderbox use");
                    return;
                }
                if (!logs.tile.equals(logs2.tile))
                {
                    dispatcher.clearSelectedWidgetTargetMode();
                    lightFailure.set("light: logs moved during tinderbox use");
                    return;
                }
                if (!cook.clickLogsForLight(logs2))
                {
                    lightFailure.set("light: tinderbox-on-logs verify failed");
                    return;
                }
                clickedLogs.set(true);
            });
            if (!ranLightFallback)
            {
                status.set("light: dispatcher busy");
                return;
            }
            if (!clickedLogs.get())
            {
                status.set(lightFailure.get() == null
                    ? "light: logs click failed — backing off"
                    : lightFailure.get());
                lightingBackoffUntilMs = now + 1200L;
                return;
            }
        }
        litFireTile = logs.tile;
        seenFiremakingAnimSinceDispatch = false;
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

        if (!ensureInventoryTabOpen())
        {
            status.set("cook: waiting for inventory tab");
            return;
        }

        status.set("cook: use raw food on heat source");
        AtomicBoolean clickedHeat = new AtomicBoolean(false);
        AtomicBoolean rawGoneAfterSettle = new AtomicBoolean(false);
        AtomicBoolean heatVanished = new AtomicBoolean(false);
        AtomicReference<String> cookClickFailure = new AtomicReference<>(null);
        boolean ranCookClick = dispatcher.runExclusive(() -> {
            if (!cook.useRawFoodOnWorker(rawId))
            {
                cookClickFailure.set("cook: raw food use-mode failed — re-checking");
                return;
            }
            SequenceSleep.sleep(client, 400);
            // Secondary guard: Cook-All may have processed the selected slot
            // during the settle sleep. If no raw remains, cancel use-mode only
            // when it is visibly active; blind Escape can close the inventory.
            if (cook.inventoryAmount(rawId) == 0)
            {
                dispatcher.clearSelectedWidgetTargetMode();
                rawGoneAfterSettle.set(true);
                return;
            }
            // Re-resolve heat source after brief wait. FIRE_FROM_LOGS must
            // target the tile we lit — generic by-name returns whatever
            // fire is closest (incl. another player's), which is bot-tell
            // behaviour and would also drift away from our tracked fire.
            CookingInteraction.Match heat2 =
                l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS
                    ? cook.findFireAt(l.heatSourceName(), litFireTile)
                    : cook.findHeatSource(l.heatSourceName());
            if (heat2 == null)
            {
                dispatcher.clearSelectedWidgetTargetMode();
                heatVanished.set(true);
                return;
            }
            // Verifies "Use Raw {food} -> {heatName}" and falls back to the
            // right-click menu when logs/other objects steal L-click priority.
            if (!cook.clickHeatSourceForCook(heat2, rawId, l.heatSourceName()))
            {
                cookClickFailure.set("cook: heat-source verify failed — use-mode cancelled, retry");
                return;
            }
            clickedHeat.set(true);
        });
        if (!ranCookClick)
        {
            status.set("cook: dispatcher busy");
            return;
        }
        if (rawGoneAfterSettle.get())
        {
            status.set("cook: all raw cooked during settle — heading to bank");
            setState(State.WALK_TO_BANK);
            return;
        }
        if (heatVanished.get())
        {
            status.set("cook: heat source vanished mid-use");
            return;
        }
        if (!clickedHeat.get())
        {
            status.set(cookClickFailure.get() == null
                ? "cook: verified cook click failed — retry"
                : cookClickFailure.get());
            return;
        }
        lastCookActionAtMs = System.currentTimeMillis();
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────

    /** Check the level-up popup. If visible and the random delay has expired,
     *  press Space to dismiss — but never pauses the FSM. The script keeps
     *  cooking/walking; those actions will also clear the notification. */
    private void safeDismissLevelUp()
    {
        try
        {
            if (!cook.isLevelUpVisible())
            {
                levelUpFirstSeenAtMs = 0L;
                return;
            }
            long now = System.currentTimeMillis();
            if (levelUpFirstSeenAtMs == 0L)
            {
                long delay = LEVEL_UP_DISMISS_MIN_MS
                    + ThreadLocalRandom.current().nextLong(
                        LEVEL_UP_DISMISS_MAX_MS - LEVEL_UP_DISMISS_MIN_MS);
                levelUpFirstSeenAtMs = now;
                levelUpDismissAfterMs = now + delay;
                log.info("cook: level-up — will dismiss in {}ms", delay);
                return;
            }
            if (now >= levelUpDismissAfterMs)
            {
                log.info("cook: level-up — pressing Space ({}ms after popup appeared)",
                    now - levelUpFirstSeenAtMs);
                cook.dismissLevelUp();
                levelUpFirstSeenAtMs = 0L;
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        catch (Throwable th) { log.warn("cook: dismissLevelUp threw", th); }
    }

    /** First item id in the inventory that is a cooked or burnt food
     *  (per {@link CookingFood}). Cooked is preferred when both are
     *  present so the trip's "good" output goes to bank first. Returns
     *  0 when nothing in the inventory is a deposit target — caller
     *  treats that as "deposit phase complete, move on to withdraw". */
    private int firstDepositTargetId() throws InterruptedException
    {
        for (CookingFood.Entry e : CookingFood.all())
        {
            if (cook.inventoryAmount(e.cookedId) > 0) return e.cookedId;
        }
        for (CookingFood.Entry e : CookingFood.all())
        {
            if (cook.inventoryAmount(e.burntId) > 0) return e.burntId;
        }
        return 0;
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

    /** True iff the inventory contains any item that isn't (a) the raw
     *  food we're cooking or (b) the tinderbox (only when the location
     *  is FIRE_FROM_LOGS — for RANGE locations the tinderbox is junk).
     *  Used by the one-shot first-start cleanup in
     *  {@link #tickBankingLegacy()}. */
    private boolean inventoryHasJunkForCooking(CookingLocation l)
    {
        int rawId = rawFoodId.get();
        boolean wantTinderbox = l.kind() == CookingLocation.SourceKind.FIRE_FROM_LOGS;
        Boolean junk = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return Boolean.FALSE;
            for (Item item : inv.getItems())
            {
                if (item == null) continue;
                int id = item.getId();
                if (id <= 0) continue;
                if (id == rawId) continue;
                if (wantTinderbox && id == ItemID.TINDERBOX) continue;
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
        return Boolean.TRUE.equals(junk);
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

    /** Worker-thread guard: ensures the inventory tab is the active sidebar
     *  panel, dispatching a click + waiting up to 2s if not. Returns false
     *  if the tab couldn't be opened (dispatcher busy, unknown layout). */
    private boolean ensureInventoryTabOpen() throws InterruptedException
    {
        if (Boolean.TRUE.equals(onClient(() -> sidebarTabs.isOpen(SidebarTab.INVENTORY))))
            return true;
        if (dispatcher.isBusy()) return false;
        return sidebarTabs.openTabAndWait(SidebarTab.INVENTORY, 2_000L);
    }

    /** Returns BANKING_VIA_ENGINE or BANKING_LEGACY depending on config. */
    private State bankingState()
    {
        return config.useEngineBanking() ? State.BANKING_VIA_ENGINE : State.BANKING_LEGACY;
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
            seenFiremakingAnimSinceDispatch = false;
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
