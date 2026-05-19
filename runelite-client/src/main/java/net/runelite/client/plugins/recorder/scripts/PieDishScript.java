package net.runelite.client.plugins.recorder.scripts;

import java.awt.Rectangle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.widget.SidebarTab;
import net.runelite.client.plugins.recorder.widget.SidebarTabActions;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.activities.ge.BuyItemIntent;
import net.runelite.client.sequence.activities.ge.OfferWaitPolicy;
import net.runelite.client.sequence.activities.ge.PricePolicy;
import net.runelite.client.sequence.activities.ge.SellItemIntent;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Pie dish → pastry dough → pie shell profit loop.
 *
 * <p>Runs entirely at the Grand Exchange where bank booths and the exchange
 * are colocated. Cycle:
 * <ol>
 *   <li>Check bank for existing supplies.</li>
 *   <li>Buy missing pie dishes, pots of flour, jugs of water via GE.</li>
 *   <li>Make pastry dough in {@value #PASTRY_BATCH}-item bank trips (jug of water
 *       used on pot of flour → Skillmulti Make All).</li>
 *   <li>Make pie shells (pie dish used on pastry dough → Make All).</li>
 *   <li>Withdraw pie shells as noted and sell at GE.</li>
 *   <li>Repeat from check.</li>
 * </ol>
 *
 * <p><b>Pre-condition:</b> player must be standing in {@link #GE_AREA} when
 * {@link #start()} is called (bank booths and GE clerks both accessible).
 *
 * <p><b>Threading:</b> one daemon worker thread drives the FSM.  Client-API
 * reads are marshalled to the client thread via {@link ClientThread}.  Bank
 * multi-step flows ({@link BankInteraction}) execute on the worker thread —
 * exactly as documented in the threading section of {@code CLAUDE.md}.
 */
@Slf4j
public final class PieDishScript
{
    // ─── Item IDs ────────────────────────────────────────────────────────────
    private static final int PIEDISH        = ItemID.PIEDISH;            // 2313
    private static final int POT_FLOUR      = ItemID.POT_FLOUR;          // 1933
    private static final int JUG_WATER      = ItemID.JUG_WATER;          // 1937
    private static final int PASTRY_DOUGH   = ItemID.PASTRY_DOUGH;       // 1953
    private static final int PIE_SHELL      = ItemID.PIE_SHELL;          // 2315
    private static final int COINS          = 995;                        // ItemID.COINS_995

    // ─── Prices ──────────────────────────────────────────────────────────────
    // Matched to the original OSBot PieShellMaker. Cost per shell:
    //   180 + 150 + 45 = 375 gp; sell at 515 gp → 140 gp profit / shell.
    // PricePolicy.Exact is a ceiling for buys (we'll pay UP TO this) and a
    // floor for sells (we'll sell AT LEAST this) — so when the live mid is
    // lower than our buy or higher than our sell we still fill.
    private static final int BUY_PRICE_PIEDISH    = 99;
    private static final int BUY_PRICE_POT_FLOUR  = 87;
    private static final int BUY_PRICE_JUG_WATER  = 15;
    private static final int SELL_PRICE_PIE_SHELL = 189;

    // ─── Quantities ──────────────────────────────────────────────────────────
    /** Items to buy per cycle; GE 4-hour buy-limit logic in GrandExchangeScript
     *  caps this automatically if needed. */
    private static final int BATCH_QTY  = 500;
    /** Pastry-dough recipe: 1 jug of water + 1 pot of flour → 1 pastry dough
     *  + 1 empty jug + 1 empty pot (3 outputs, net +1 slot per craft).
     *  9 + 9 = 18 inputs → 10 free slots; after 9 crafts: 27 items in 28 slots. */
    private static final int PASTRY_BATCH = 9;
    /** Pie-shell recipe consumes both inputs into 1 output, so a full
     *  {@code 14 + 14 = 28} inventory works — pie shell replaces a slot
     *  freed by either input. */
    private static final int PIE_SHELL_BATCH = 14;

    // ─── Timing ──────────────────────────────────────────────────────────────
    private static final long TICK_MS               = 650;
    private static final long BANK_PACE_MS          = 1_500;
    private static final long SKILLMULTI_TIMEOUT_MS = 5_000;
    private static final long CRAFT_TIMEOUT_MS      = 90_000;
    /** Consecutive verified-bank-primitive failures before aborting the
     *  script. The primitives in {@link BankInteraction} now return
     *  false only after their own poll-and-verify timeout, so a false
     *  here is a real failure to act, not a transient busy/throttle. */
    private static final int  MAX_BANK_FAILURES     = 3;

    // ─── GE area ─────────────────────────────────────────────────────────────
    /** Covers the GE exchange floor and the bank booths just inside. */
    static final WorldArea GE_AREA = new WorldArea(3140, 3470, 30, 30, 0);

    // ─── States ──────────────────────────────────────────────────────────────
    public enum State
    {
        IDLE,
        CHECKING_BANK,
        BUYING_SUPPLIES,
        MAKING_PASTRY_DOUGH,
        MAKING_PIE_SHELLS,
        SELLING_PIE_SHELLS,
        DONE,
        ABORTED
    }

    // ─── Dependencies ────────────────────────────────────────────────────────
    private final Client                  client;
    private final ClientThread            clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final BankInteraction         bank;
    private final SidebarTabActions       sidebarTabs;
    private final GrandExchangeScript     geScript;

    // ─── Mutable runtime ─────────────────────────────────────────────────────
    private final AtomicReference<State>  state   = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status  = new AtomicReference<>("idle");
    private final AtomicBoolean           running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker  = new AtomicReference<>();

    public boolean isRunning() { return running.get(); }

    // Supply amounts updated from bank during CHECKING_BANK.
    private int flourAmt, waterAmt, dishAmt, pastryAmt, shellAmt;

    // Persisted buy plan computed in CHECKING_BANK and consumed across
    // the BUYING_SUPPLIES tick chain. NOT reset by {@link #setState} —
    // the plan must survive the CHECKING_BANK → BUYING_SUPPLIES transition.
    // CHECKING_BANK overwrites these every cycle.
    private int plannedTargetQty;
    private int plannedBuyDishQty;
    private int plannedBuyFlourQty;
    private int plannedBuyWaterQty;
    private int plannedBuyCost;

    // Buying: have we submitted each buy this cycle? (RESET in setState.)
    private boolean buyDishDone, buyFlourDone, buyWaterDone;

    // Crafting (shared by MAKING_PASTRY_DOUGH + MAKING_PIE_SHELLS via tickCraftBatch).
    private boolean craftBankDone;          // bank trip complete, items in inventory
    private boolean craftDepositDone;       // deposit-all returned verified-true (inv is empty)
    private boolean craftClicksDone;        // Use+target clicks sent
    private boolean skillmultiConfirmed;    // Space pressed on Make-All dialog
    private long    skillmultiWaitMs;
    private long    craftWaitMs;

    /** Consecutive {@link BankInteraction} primitive failures (the ones
     *  that already poll-and-verify). Reset on the next successful call;
     *  abort after {@link #MAX_BANK_FAILURES}. */
    private int bankFailures;

    // Selling.
    private boolean sellBankDone;       // noted shells in inventory
    private boolean sellDepositDone;    // deposit-all fired before withdrawing the noted stack
    private boolean sellStarted;

    // Wall-clock of last bank-booth click (pacing guard).
    private long lastBankActionMs;

    // ─── Constructor ─────────────────────────────────────────────────────────

    public PieDishScript(Client client, ClientThread clientThread,
                         HumanizedInputDispatcher dispatcher,
                         GrandExchangeScript geScript)
    {
        this.client     = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.bank       = new BankInteraction(client, clientThread, dispatcher);
        this.sidebarTabs = new SidebarTabActions(client, clientThread, dispatcher);
        this.geScript   = geScript;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    public State  state()  { return state.get(); }
    public String status() { return status.get(); }

    public void start()
    {
        Thread existing = worker.get();
        if (existing != null && existing.isAlive())
        {
            status.set("already running");
            return;
        }
        if (!running.compareAndSet(false, true)) return;

        WorldPoint pos = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        if (pos == null || !GE_AREA.contains(pos))
        {
            status.set("ABORTED: must be at the Grand Exchange — walk there first");
            running.set(false);
            return;
        }

        setState(State.CHECKING_BANK);
        Thread t = new Thread(this::tickLoop, "pie-dish-maker");
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
            try { t.join(2_000); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        setState(State.IDLE);
        status.set("stopped");
    }

    // ─── Tick loop ───────────────────────────────────────────────────────────

    private void tickLoop()
    {
        try
        {
            // Post-login the inventory tab is often closed on the modern
            // client. Crafting clicks land on whatever sidebar widget is
            // visible if we don't fix it first.
            ensureInventoryTabOpen();

            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                switch (state.get())
                {
                    case CHECKING_BANK       -> tickCheckBank();
                    case BUYING_SUPPLIES     -> tickBuySupplies();
                    case MAKING_PASTRY_DOUGH -> tickMakePastryDough();
                    case MAKING_PIE_SHELLS   -> tickMakePieShells();
                    case SELLING_PIE_SHELLS  -> tickSellPieShells();
                    case DONE, ABORTED, IDLE -> running.set(false);
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

    // ─── CHECKING_BANK ───────────────────────────────────────────────────────

    private void tickCheckBank() throws InterruptedException
    {
        if (Boolean.TRUE.equals(onClient(bank::isBankPinUp)))
        {
            abortWith("bank PIN required — enter it manually then restart");
            return;
        }
        if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            long now = System.currentTimeMillis();
            if (now - lastBankActionMs < BANK_PACE_MS) { status.set("check: pacing"); return; }
            status.set("check: opening bank");
            bank.tryClickBankBoothRandom();
            lastBankActionMs = now;
            return;
        }
        if (!bank.bankReady()) { status.set("check: waiting for bank contents"); return; }

        int bankFlour  = (int) bank.bankItemAmount(POT_FLOUR);
        int bankWater  = (int) bank.bankItemAmount(JUG_WATER);
        int bankDish   = (int) bank.bankItemAmount(PIEDISH);
        int bankPastry = (int) bank.bankItemAmount(PASTRY_DOUGH);
        int bankShell  = (int) bank.bankItemAmount(PIE_SHELL);

        int invFlour  = inventoryCount(POT_FLOUR);
        int invWater  = inventoryCount(JUG_WATER);
        int invDish   = inventoryCount(PIEDISH);
        int invPastry = inventoryCount(PASTRY_DOUGH);
        int invShell  = inventoryCount(PIE_SHELL);

        // Supply planning must look at TOTAL available stock, not just what's
        // still sitting in the bank. A prior GE collect can leave ingredients
        // in inventory, and rebuying them is both wasteful and destabilizing.
        flourAmt  = bankFlour + invFlour;
        waterAmt  = bankWater + invWater;
        dishAmt   = bankDish + invDish;
        pastryAmt = bankPastry + invPastry;
        shellAmt  = bankShell + invShell;

        log.info("pie-dish check: flour={} (bank={} inv={}) water={} (bank={} inv={}) dish={} (bank={} inv={}) pastry={} (bank={} inv={}) shell={} (bank={} inv={})",
            flourAmt, bankFlour, invFlour,
            waterAmt, bankWater, invWater,
            dishAmt, bankDish, invDish,
            pastryAmt, bankPastry, invPastry,
            shellAmt, bankShell, invShell);

        // Priority A–E: close bank only when transitioning to BUYING_SUPPLIES
        // (which walks to a GE clerk). All other states reopen or keep the bank,
        // so leaving it open avoids a wasteful close → reopen round-trip.
        State next;

        if (flourAmt > 0 && waterAmt > 0)
        {
            // A: Have flour + water → make pastry dough first.
            next = State.MAKING_PASTRY_DOUGH;
        }
        else if (pastryAmt > 0 && dishAmt > 0)
        {
            // B: No flour/water pair but have dough + dishes → make pie shells.
            next = State.MAKING_PIE_SHELLS;
        }
        else if (pastryAmt > 0)
        {
            // C: Have dough but no dishes → buy exactly enough dishes to match.
            int totalCoins = (int) bank.bankItemAmount(COINS) + inventoryCount(COINS);
            int affordable = Math.min(pastryAmt, totalCoins / BUY_PRICE_PIEDISH);
            if (affordable == 0)
            {
                bank.tryCloseBank();
                abortWith("not enough coins to buy pie dishes (have " + totalCoins + ")");
                return;
            }
            plannedTargetQty   = affordable;
            plannedBuyDishQty  = affordable;
            plannedBuyFlourQty = 0;
            plannedBuyWaterQty = 0;
            plannedBuyCost     = affordable * BUY_PRICE_PIEDISH;
            log.info("pie-dish buy plan (case C – dishes only): qty={} cost={}", affordable, plannedBuyCost);
            next = State.BUYING_SUPPLIES;
        }
        else if (dishAmt > 0)
        {
            // D: Have dishes but no pastry dough and no flour/water → buy balanced
            //    flour+water to match available dishes, cash-aware.
            int totalCoins = (int) bank.bankItemAmount(COINS) + inventoryCount(COINS);
            int target = computeAffordableFlourWater(totalCoins, dishAmt, flourAmt, waterAmt);
            if (target == 0)
            {
                bank.tryCloseBank();
                abortWith("not enough coins to buy flour+water (have " + totalCoins + ")");
                return;
            }
            plannedTargetQty   = target;
            plannedBuyDishQty  = 0;
            plannedBuyFlourQty = Math.max(0, target - flourAmt);
            plannedBuyWaterQty = Math.max(0, target - waterAmt);
            plannedBuyCost     = plannedBuyFlourQty * BUY_PRICE_POT_FLOUR
                               + plannedBuyWaterQty * BUY_PRICE_JUG_WATER;
            log.info("pie-dish buy plan (case D – flour+water): target={} (coins={}) flour+={} water+={} cost={}",
                target, totalCoins, plannedBuyFlourQty, plannedBuyWaterQty, plannedBuyCost);
            next = State.BUYING_SUPPLIES;
        }
        else if (shellAmt > 0)
        {
            // E: Have pie shells → sell.
            next = State.SELLING_PIE_SHELLS;
        }
        else
        {
            // Cold start: buy all three to the largest affordable balanced target.
            int totalCoins = (int) bank.bankItemAmount(COINS) + inventoryCount(COINS);
            plannedTargetQty   = computeAffordableTarget(totalCoins, dishAmt, flourAmt, waterAmt);
            plannedBuyDishQty  = Math.max(0, plannedTargetQty - dishAmt);
            plannedBuyFlourQty = Math.max(0, plannedTargetQty - flourAmt);
            plannedBuyWaterQty = Math.max(0, plannedTargetQty - waterAmt);
            plannedBuyCost     = plannedBuyDishQty  * BUY_PRICE_PIEDISH
                               + plannedBuyFlourQty * BUY_PRICE_POT_FLOUR
                               + plannedBuyWaterQty * BUY_PRICE_JUG_WATER;
            if (plannedTargetQty == 0)
            {
                bank.tryCloseBank();
                abortWith("not enough coins for one complete set (have " + totalCoins + ")");
                return;
            }
            log.info("pie-dish buy plan (general): target={} (coins={}) → dish+={} flour+={} water+={} cost={}",
                plannedTargetQty, totalCoins, plannedBuyDishQty, plannedBuyFlourQty,
                plannedBuyWaterQty, plannedBuyCost);
            next = State.BUYING_SUPPLIES;
        }

        if (next == State.BUYING_SUPPLIES)
        {
            // Withdraw coins for the upcoming buys while the bank is still
            // open. Both tryWithdraw* are poll-verified, so a false return
            // here means coins did NOT land in inventory — bail without
            // transitioning so we retry next tick instead of starting GE
            // buys with an empty wallet.
            int bankCoins = (int) bank.bankItemAmount(COINS);
            if (bankCoins > 0)
            {
                boolean ok;
                if (bankCoins <= 1_000_000)
                {
                    log.info("pie-dish: withdraw-all coins (bank={})", bankCoins);
                    ok = bank.tryWithdrawAll(COINS);
                }
                else
                {
                    // Round up to nearest 1000 so the chatbox typing collapses
                    // to "Nk" (e.g. 50432 → 51000 typed as "51k", 3 chars vs 5).
                    // Overshoot is harmless for coins — extra coins land in the
                    // same stack-slot. Bonus: the rounded value becomes the
                    // bank's cached Withdraw-Y, so the next equivalent cycle
                    // hits the verb-scan one-click path.
                    int withdrawAmt = BankInteraction.roundUpForFastTyping(plannedBuyCost);
                    log.info("pie-dish: withdraw {} coins (bank={}, planned={})",
                        withdrawAmt, bankCoins, plannedBuyCost);
                    ok = bank.tryWithdrawX(COINS, withdrawAmt);
                }
                if (!ok)
                {
                    if (++bankFailures >= MAX_BANK_FAILURES)
                    {
                        abortWith("withdraw coins failed " + bankFailures + " consecutive times");
                        return;
                    }
                    // Stay in CHECKING_BANK; next tick recomputes the plan
                    // and retries. Don't close the bank — keep it open so
                    // the retry is one click away.
                    return;
                }
                bankFailures = 0;
            }
            bank.tryCloseBank();
            SequenceSleep.sleep(client, 400);
        }
        setState(next);
    }

    /** Largest N in [1, dishTarget] such that buying enough flour and water to
     *  bring each up to N is affordable.  Used when we have pie dishes but no
     *  pastry dough (case D): target starts at the dish count and shrinks until
     *  the cost fits inside available coins. */
    private static int computeAffordableFlourWater(int coins, int dishTarget, int flour, int water)
    {
        for (int n = Math.min(dishTarget, BATCH_QTY); n >= 1; n--)
        {
            long cost = (long) Math.max(0, n - flour) * BUY_PRICE_POT_FLOUR
                      + (long) Math.max(0, n - water) * BUY_PRICE_JUG_WATER;
            if (cost <= coins) return n;
        }
        return 0;
    }

    /** Largest N in [0, BATCH_QTY] such that buying enough of each ingredient
     *  to bring its stock up to N is affordable.
     *  cost(N) = max(0, N-dish)*P_DISH + max(0, N-flour)*P_FLOUR + max(0, N-water)*P_WATER.
     *  cost is monotonic non-decreasing in N, so we walk upward and stop.
     *  O(BATCH_QTY) ≈ 500 ops; only runs once per CHECKING_BANK cycle. */
    private static int computeAffordableTarget(int coins, int dish, int flour, int water)
    {
        int best = 0;
        for (int n = 1; n <= BATCH_QTY; n++)
        {
            long cost = (long) Math.max(0, n - dish)  * BUY_PRICE_PIEDISH
                      + (long) Math.max(0, n - flour) * BUY_PRICE_POT_FLOUR
                      + (long) Math.max(0, n - water) * BUY_PRICE_JUG_WATER;
            if (cost > coins) return best;
            best = n;
        }
        return best;
    }

    // ─── BUYING_SUPPLIES ─────────────────────────────────────────────────────

    private void tickBuySupplies() throws InterruptedException
    {
        log.info("pie-dish buying: target={} dish={} flour={} water={} cost={}",
            plannedTargetQty, plannedBuyDishQty, plannedBuyFlourQty,
            plannedBuyWaterQty, plannedBuyCost);

        SequenceState geSt = geScript.state();

        if (geSt == SequenceState.RUNNING)
        {
            status.set("buy: " + geScript.status());
            return;
        }
        if (geSt == SequenceState.FAILED)
        {
            abortWith("GE buy failed: " + geScript.status());
            return;
        }

        // After each GE buy, the bought ingredient lands in inventory. Bank
        // those (per-item Deposit-All) but KEEP coins so the remaining buys
        // can be funded directly from inventory — startBuy has no bank-prep.
        boolean haveIngredients = inventoryCount(PIEDISH)   > 0
                                || inventoryCount(POT_FLOUR) > 0
                                || inventoryCount(JUG_WATER) > 0;
        if (haveIngredients)
        {
            if (Boolean.TRUE.equals(onClient(bank::isBankPinUp)))
            {
                abortWith("bank PIN required — enter it manually then restart");
                return;
            }
            if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
            {
                long now = System.currentTimeMillis();
                if (now - lastBankActionMs < BANK_PACE_MS) { status.set("buy: pacing to bank"); return; }
                bank.tryClickBankBoothRandom();
                lastBankActionMs = now;
                return;
            }
            if (!bank.bankReady()) { status.set("buy: bank loading"); return; }

            // tryDepositAll is poll-verified now: true means the inventory's
            // count of that item is 0; false means the verify timed out and
            // we count it toward MAX_BANK_FAILURES. The 400ms sleeps the
            // fire-and-hope version needed are gone — the primitive already
            // waits for the engine to actually move the items.
            if (inventoryCount(PIEDISH) > 0 && !bank.tryDepositAll(PIEDISH))
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("buy-deposit pie dish failed " + bankFailures + " consecutive times");
                    return;
                }
                return;
            }
            if (inventoryCount(POT_FLOUR) > 0 && !bank.tryDepositAll(POT_FLOUR))
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("buy-deposit pot of flour failed " + bankFailures + " consecutive times");
                    return;
                }
                return;
            }
            if (inventoryCount(JUG_WATER) > 0 && !bank.tryDepositAll(JUG_WATER))
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("buy-deposit jug of water failed " + bankFailures + " consecutive times");
                    return;
                }
                return;
            }
            bankFailures     = 0;
            lastBankActionMs = System.currentTimeMillis();
            bank.tryCloseBank();
            SequenceSleep.sleep(client, 400);
            return;
        }

        // Close bank if still open from a previous step.
        if (Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            bank.tryCloseBank();
            return;
        }

        // Defensive: empty plan should never be reached (CHECKING_BANK aborts
        // when target == 0), but guard so we don't loop here.
        if (plannedBuyDishQty == 0 && plannedBuyFlourQty == 0 && plannedBuyWaterQty == 0)
        {
            log.warn("pie-dish buying: empty plan — re-checking bank");
            setState(State.CHECKING_BANK);
            return;
        }

        // Plan fully submitted (each non-zero qty has its done flag set)
        // — re-check bank to drive the next state transition.
        boolean dishComplete  = plannedBuyDishQty  == 0 || buyDishDone;
        boolean flourComplete = plannedBuyFlourQty == 0 || buyFlourDone;
        boolean waterComplete = plannedBuyWaterQty == 0 || buyWaterDone;
        if (dishComplete && flourComplete && waterComplete)
        {
            // Close the GE main widget before transitioning to CHECKING_BANK —
            // the buy plan ends with CollectOfferStep and intentionally leaves
            // the widget open. If we don't close it here, the next state's
            // banker right-click sees a GE-overlay-occluded world and the
            // menu comes back without "Bank" (logged 2026-05-04 → 8.5 minute
            // bank stall). Stay in BUYING_SUPPLIES on a verified-close
            // failure so next tick retries.
            if (!geScript.tryCloseGrandExchange())
            {
                log.warn("pie-dish buying: GE failed to close, retrying next tick");
                return;
            }
            log.info("pie-dish buying: all planned buys submitted — re-checking bank");
            setState(State.CHECKING_BANK);
            return;
        }

        // Submit each required buy. Coins were withdrawn while the bank was
        // still open at the end of CHECKING_BANK, so we use the bank-prep-free
        // startBuy here — the prep variant would needlessly reopen the bank.
        if (plannedBuyDishQty > 0 && !buyDishDone)
        {
            BuyItemIntent intent = new BuyItemIntent(PIEDISH, "Pie dish", plannedBuyDishQty,
                new PricePolicy.Exact(BUY_PRICE_PIEDISH),
                OfferWaitPolicy.untilOrPartialStall(600, 15));
            if (!geScript.startBuy(intent))
            {
                abortWith("GE: could not start buy for pie dishes — " + geScript.status());
                return;
            }
            buyDishDone = true;
            status.set("buy: submitted pie dishes ×" + plannedBuyDishQty);
            return;
        }
        if (plannedBuyFlourQty > 0 && !buyFlourDone)
        {
            BuyItemIntent intent = new BuyItemIntent(POT_FLOUR, "Pot of flour", plannedBuyFlourQty,
                new PricePolicy.Exact(BUY_PRICE_POT_FLOUR),
                OfferWaitPolicy.untilOrPartialStall(600, 15));
            if (!geScript.startBuy(intent))
            {
                abortWith("GE: could not start buy for pots of flour — " + geScript.status());
                return;
            }
            buyFlourDone = true;
            status.set("buy: submitted pots of flour ×" + plannedBuyFlourQty);
            return;
        }
        if (plannedBuyWaterQty > 0 && !buyWaterDone)
        {
            BuyItemIntent intent = new BuyItemIntent(JUG_WATER, "Jug of water", plannedBuyWaterQty,
                new PricePolicy.Exact(BUY_PRICE_JUG_WATER),
                OfferWaitPolicy.untilOrPartialStall(600, 15));
            if (!geScript.startBuy(intent))
            {
                abortWith("GE: could not start buy for jugs of water — " + geScript.status());
                return;
            }
            buyWaterDone = true;
            status.set("buy: submitted jugs of water ×" + plannedBuyWaterQty);
            return;
        }
    }

    // ─── MAKING_PASTRY_DOUGH ─────────────────────────────────────────────────

    /** Use jug of water on pot of flour → Make All pastry dough.
     *  The Skillmulti dialog has 4 options (A=bread, B=pastry, C=pizza,
     *  D=pitta) — pressing Space picks A (bread). Pass Skillmulti.B so
     *  the dispatch right-clicks the Pastry-dough option and selects
     *  "Make-All". Captured via click-inspector: {@code id=0x010e_0010
     *  named=Skillmulti.B verb='Make' target='Pastry dough'}. */
    private void tickMakePastryDough() throws InterruptedException
    {
        tickCraftBatch(JUG_WATER, POT_FLOUR, PASTRY_BATCH, InterfaceID.Skillmulti.B);
    }

    // ─── MAKING_PIE_SHELLS ───────────────────────────────────────────────────

    /** Use pie dish on pastry dough → Make All pie shells. Single-output
     *  recipe — Skillmulti shows one option, Space confirms. */
    private void tickMakePieShells() throws InterruptedException
    {
        tickCraftBatch(PIEDISH, PASTRY_DOUGH, PIE_SHELL_BATCH, null);
    }

    /**
     * Shared crafting tick for both make-pastry-dough and make-pie-shells.
     *
     * <p>Banking sub-phase ({@code !craftBankDone}): open bank → deposit all →
     * withdraw {@code batch} of each item → close bank.<br>
     * Crafting sub-phase ({@code craftBankDone}): CLICK_INV_ITEM("Use") on
     * {@code useItem} + clickCanvas on {@code targetItem}'s slot → wait for
     * Skillmulti → Space → wait for {@code targetItem} count to drop to 0.
     *
     * <p>Resets to CHECKING_BANK when either item runs out in the bank.
     *
     * @param batch  withdraw qty per side. Must respect by-product inventory
     *               math — pastry recipe uses 9 (3-output recipe), pie-shell
     *               recipe uses 14 (1-output recipe).
     * @param skillmultiOptionWidgetId  for multi-option Skillmulti dialogs
     *               (e.g. water+flour: bread/pastry/pizza/pitta), the widget
     *               id of the desired recipe option — dispatched as a
     *               right-click "Make-All". Pass {@code null} for single-
     *               option dialogs (e.g. pie-shell) where Space suffices.
     */
    private void tickCraftBatch(int useItem, int targetItem, int batch,
        Integer skillmultiOptionWidgetId) throws InterruptedException
    {
        if (!craftBankDone)
        {
            tickCraftBanking(useItem, targetItem, batch);
            return;
        }

        // ── Craft sub-phase ──────────────────────────────────────────────────

        if (!craftClicksDone)
        {
            // Closing the bank restores the previously-active sidebar tab,
            // which post-login may not be the inventory. resolveInvItemBounds
            // returns null when InterfaceID.Inventory.ITEMS is hidden.
            if (!ensureInventoryTabOpen()) { status.set("craft: opening inventory tab"); return; }
            if (dispatcher.isBusy()) { status.set("craft: dispatcher busy"); return; }

            int useSlot = inventorySlotOf(useItem);
            if (useSlot < 0)
            {
                log.warn("pie-dish craft: use-item {} gone from inventory", useItem);
                craftBankDone    = false;
                craftDepositDone = false;
                return;
            }

            // Step 1 — engage use-mode on the source item.
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_INV_ITEM)
                .channel(ActionRequest.Channel.MOUSE)
                .slot(useSlot)
                .verb("Use")
                .build());
            dispatcher.awaitIdle(3_000L);
            String err = dispatcher.lastErrorMessage();
            if (err != null)
            {
                log.warn("pie-dish craft: Use click error: {}", err);
                return;
            }

            // Brief settle so use-mode is registered before the second click.
            SequenceSleep.sleep(client, 350);

            // Step 2 — click the target item slot.
            Rectangle bounds = onClient(() -> resolveInvItemBounds(targetItem));
            if (bounds == null)
            {
                log.warn("pie-dish craft: target {} slot not resolvable", targetItem);
                craftBankDone    = false;
                craftDepositDone = false;
                return;
            }
            dispatcher.clickCanvas(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
            dispatcher.awaitIdle(3_000L);

            craftClicksDone    = true;
            skillmultiWaitMs   = System.currentTimeMillis();
            status.set("craft: clicks done — waiting for Make dialog");
            return;
        }

        // ── Wait for Skillmulti dialog ────────────────────────────────────────

        if (!skillmultiConfirmed)
        {
            boolean open = Boolean.TRUE.equals(onClient(() -> {
                Widget w = client.getWidget(InterfaceID.Skillmulti.UNIVERSE);
                return w != null && !w.isHidden();
            }));
            if (!open)
            {
                long elapsed = System.currentTimeMillis() - skillmultiWaitMs;
                if (elapsed > SKILLMULTI_TIMEOUT_MS)
                {
                    log.warn("pie-dish craft: Skillmulti did not open after {}ms — retrying clicks", elapsed);
                    // Dismiss any stale right-click menu before re-dispatching
                    // the use+target sequence — leftover menus block the cs2
                    // that would open the Skillmulti dialog (CLAUDE.md §8).
                    // dismissMenu() moves the cursor outside the menu first
                    // (no side effect on the inventory tab), only falling
                    // back to VK_ESCAPE if move-away can't close the menu.
                    dispatcher.dismissMenu();
                    craftClicksDone  = false;
                    skillmultiWaitMs = 0;
                }
                else
                {
                    status.set("craft: waiting for Make dialog (" + elapsed + "ms)");
                }
                return;
            }
            if (skillmultiOptionWidgetId != null)
            {
                // Left-click the recipe option — that IS "Make-All" on the
                // Skillmulti dialog. Right-clicking looks for "Make-All" in
                // the context menu, but only "Make" (the left-click default)
                // is present there, so the right-click path always fails.
                status.set("craft: clicking recipe option (Make)");
                dispatcher.dispatch(ActionRequest.builder()
                    .kind(ActionRequest.Kind.CLICK_WIDGET)
                    .channel(ActionRequest.Channel.MOUSE)
                    .widgetId(skillmultiOptionWidgetId)
                    .build());
                dispatcher.awaitIdle(3_000L);
                String werr = dispatcher.lastErrorMessage();
                if (werr != null)
                {
                    log.warn("pie-dish craft: recipe option click error: {} — retrying", werr);
                    return;
                }
            }
            else
            {
                status.set("craft: confirming Make All (Space)");
                dispatcher.tapKey(java.awt.event.KeyEvent.VK_SPACE);
            }
            skillmultiConfirmed = true;
            craftWaitMs         = System.currentTimeMillis();
            return;
        }

        // ── Wait for crafting to complete ─────────────────────────────────────

        int targetInInv = inventoryCount(targetItem);
        int useInInv    = inventoryCount(useItem);

        if (targetInInv == 0 || useInInv == 0)
        {
            log.info("pie-dish craft: batch done (use={} target={})", useInInv, targetInInv);
            // Reset for next batch; next tick re-enters bankDone=false path.
            craftBankDone    = false;
            craftDepositDone = false;
            craftClicksDone  = false;
            skillmultiConfirmed = false;
            craftWaitMs      = 0;
            return;
        }

        // Re-confirm if Skillmulti re-opened (rare, but possible on item swap).
        boolean dialogStillOpen = Boolean.TRUE.equals(onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Skillmulti.UNIVERSE);
            return w != null && !w.isHidden();
        }));
        if (dialogStillOpen)
        {
            if (skillmultiOptionWidgetId != null)
            {
                dispatcher.dispatch(ActionRequest.builder()
                    .kind(ActionRequest.Kind.CLICK_WIDGET)
                    .channel(ActionRequest.Channel.MOUSE)
                    .widgetId(skillmultiOptionWidgetId)
                    .build());
            }
            else
            {
                dispatcher.tapKey(java.awt.event.KeyEvent.VK_SPACE);
            }
        }

        long elapsed = System.currentTimeMillis() - craftWaitMs;
        if (elapsed > CRAFT_TIMEOUT_MS)
        {
            abortWith("craft timeout — " + targetInInv + " " + targetItem + " still in inventory");
            return;
        }
        status.set("craft: making (" + targetInInv + " remaining, " + elapsed + "ms)");
    }

    /**
     * Banking sub-phase of {@link #tickCraftBatch}: one action per tick until
     * inventory has {@code batch} of {@code useItem} and {@code batch} of
     * {@code targetItem}, then closes bank and sets {@link #craftBankDone}.
     * If either item is exhausted in the bank, transitions to
     * {@link State#CHECKING_BANK} instead.
     */
    private void tickCraftBanking(int useItem, int targetItem, int batch) throws InterruptedException
    {
        if (Boolean.TRUE.equals(onClient(bank::isBankPinUp)))
        {
            abortWith("bank PIN required — enter it manually");
            return;
        }
        if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            long now = System.currentTimeMillis();
            if (now - lastBankActionMs < BANK_PACE_MS) { status.set("craft bank: pacing"); return; }
            status.set("craft bank: opening");
            bank.tryClickBankBoothRandom();
            lastBankActionMs = now;
            return;
        }
        if (!bank.bankReady()) { status.set("craft bank: waiting for contents"); return; }

        // Deposit any leftovers from the previous batch. bank.depositAllInventory()
        // returns true only after the inventory is verified empty, so we no longer
        // need a re-check here: a true return is "done", a false return means the
        // primitive's own verify timed out and we count it toward MAX_BANK_FAILURES.
        if (!craftDepositDone)
        {
            status.set("craft bank: depositing inventory");
            if (!bank.depositAllInventory())
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("deposit-all-inventory failed " + bankFailures + " consecutive times");
                    return;
                }
                return;
            }
            craftDepositDone = true;
            bankFailures     = 0;
            return;
        }

        // Count already-withdrawn inv items toward available total so the
        // batch is sized against what we can actually make this trip.
        long bankUseAmt    = bank.bankItemAmount(useItem);
        long bankTargetAmt = bank.bankItemAmount(targetItem);
        int  invUse        = inventoryCount(useItem);
        int  invTarget     = inventoryCount(targetItem);
        int  totalUse      = (int)(bankUseAmt + invUse);
        int  totalTarget   = (int)(bankTargetAmt + invTarget);

        if (totalUse <= 0 || totalTarget <= 0)
        {
            log.info("pie-dish: bank+inv supplies exhausted (use={}, target={}) — re-checking bank",
                totalUse, totalTarget);
            setState(State.CHECKING_BANK);
            return;
        }

        int batchSize = Math.min(batch, Math.min(totalUse, totalTarget));

        if (invUse < batchSize)
        {
            long now = System.currentTimeMillis();
            if (now - lastBankActionMs < BANK_PACE_MS) { status.set("craft bank: pacing withdraw"); return; }
            int need = batchSize - invUse;
            status.set("craft bank: withdraw " + need + " use-item");
            boolean ok = (bankUseAmt <= need)
                ? bank.tryWithdrawAll(useItem)
                : bank.tryWithdrawX(useItem, need);
            lastBankActionMs = System.currentTimeMillis();
            if (!ok)
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("withdraw use-item " + useItem + " failed " + bankFailures + " consecutive times");
                    return;
                }
            }
            else
            {
                bankFailures = 0;
            }
            return;
        }

        if (invTarget < batchSize)
        {
            long now = System.currentTimeMillis();
            if (now - lastBankActionMs < BANK_PACE_MS) { status.set("craft bank: pacing withdraw"); return; }
            int need = batchSize - invTarget;
            status.set("craft bank: withdraw " + need + " target-item");
            boolean ok = (bankTargetAmt <= need)
                ? bank.tryWithdrawAll(targetItem)
                : bank.tryWithdrawX(targetItem, need);
            lastBankActionMs = System.currentTimeMillis();
            if (!ok)
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("withdraw target-item " + targetItem + " failed " + bankFailures + " consecutive times");
                    return;
                }
            }
            else
            {
                bankFailures = 0;
            }
            return;
        }

        // Both in inventory — close bank.
        long now = System.currentTimeMillis();
        if (now - lastBankActionMs < BANK_PACE_MS) { status.set("craft bank: pacing close"); return; }
        status.set("craft bank: closing");
        bank.tryCloseBank();
        lastBankActionMs = now;
        SequenceSleep.sleep(client, 400);
        if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            craftBankDone = true;
            log.info("pie-dish craft: bank done — {} use + {} target in inventory",
                inventoryCount(useItem), inventoryCount(targetItem));
        }
    }

    // ─── SELLING_PIE_SHELLS ──────────────────────────────────────────────────

    private void tickSellPieShells() throws InterruptedException
    {
        // Phase 1: withdraw pie shells as noted so all fit in one inventory slot.
        if (!sellBankDone)
        {
            tickSellBanking();
            return;
        }

        // Phase 2: sell at GE.
        SequenceState geSt = geScript.state();
        if (geSt == SequenceState.RUNNING) { status.set("sell: " + geScript.status()); return; }
        if (geSt == SequenceState.FAILED)  { abortWith("GE sell failed: " + geScript.status()); return; }

        if (!sellStarted)
        {
            if (shellAmt <= 0)
            {
                // Nothing left to sell — move on.
                setState(State.CHECKING_BANK);
                return;
            }
            // EnsureInventoryForSellStep / SelectSellItemStep match against
            // ItemStack.unnotedId() (canonical, set in InventoryObserver via
            // ItemComposition.getLinkedNoteId), so passing the unnoted id
            // PIE_SHELL=2315 transparently accepts the noted form 2316 too.
            SellItemIntent intent = new SellItemIntent(
                PIE_SHELL, "Pie shell", shellAmt,
                new PricePolicy.Exact(SELL_PRICE_PIE_SHELL),
                OfferWaitPolicy.untilOrPartialStall(600, 15));
            if (!geScript.startSell(intent))
            {
                abortWith("GE sell start failed: " + geScript.status());
                return;
            }
            sellStarted = true;
            status.set("sell: submitted " + shellAmt + " pie shells @ " + SELL_PRICE_PIE_SHELL + "gp");
            return;
        }

        // GE sell complete — close the GE widget (same reason as the buy
        // path: CollectOfferStep doesn't close it) before looping back to
        // CHECKING_BANK, otherwise the next bank trip stalls behind the
        // GE overlay.
        if (!geScript.tryCloseGrandExchange())
        {
            log.warn("pie-dish: sell cycle done but GE failed to close, retrying next tick");
            return;
        }
        log.info("pie-dish: sell cycle complete, starting new loop");
        setState(State.CHECKING_BANK);
    }

    private void tickSellBanking() throws InterruptedException
    {
        if (Boolean.TRUE.equals(onClient(bank::isBankPinUp)))
        {
            abortWith("bank PIN required — enter it manually then restart");
            return;
        }
        if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            long now = System.currentTimeMillis();
            if (now - lastBankActionMs < BANK_PACE_MS) { status.set("sell bank: pacing"); return; }
            bank.tryClickBankBoothRandom();
            lastBankActionMs = now;
            return;
        }
        if (!bank.bankReady()) { status.set("sell bank: loading"); return; }

        // Deposit anything in inventory first. bank.depositAllInventory()
        // returns true only after the inventory is verified empty, so the
        // gate is the boolean — no need for a once-per-trip guard around
        // a fire-and-hope click any more.
        if (!sellDepositDone)
        {
            if (!bank.depositAllInventory())
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("sell-deposit-all-inventory failed " + bankFailures + " consecutive times");
                    return;
                }
                return;
            }
            lastBankActionMs = System.currentTimeMillis();
            sellDepositDone  = true;
            bankFailures     = 0;
            return;
        }

        long bankShells = bank.bankItemAmount(PIE_SHELL);
        if (bankShells <= 0)
        {
            bank.tryCloseBank();
            setState(State.CHECKING_BANK);
            return;
        }
        shellAmt = (int) bankShells;

        // Withdraw all pie shells as noted — one inventory slot for up to BATCH_QTY.
        // tryWithdrawAsNoteX is now poll-verified: true means a noted stack
        // actually arrived in inventory; false means timeout / no-space / bank
        // closed mid-withdraw and we must NOT proceed to GE sell.
        status.set("sell bank: withdraw " + shellAmt + " noted pie shells");
        if (!bank.tryWithdrawAsNoteX(PIE_SHELL, shellAmt))
        {
            if (++bankFailures >= MAX_BANK_FAILURES)
            {
                abortWith("sell-withdraw-as-note failed " + bankFailures + " consecutive times");
                return;
            }
            lastBankActionMs = System.currentTimeMillis();
            return;
        }
        bankFailures     = 0;
        lastBankActionMs = System.currentTimeMillis();

        bank.tryCloseBank();
        SequenceSleep.sleep(client, 400);
        if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            sellBankDone = true;
            log.info("pie-dish sell: bank done — {} noted shells in inventory", shellAmt);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void setState(State s)
    {
        log.info("pie-dish: {} → {}", state.get(), s);
        state.set(s);
        lastBankActionMs    = 0;
        craftBankDone       = false;
        craftDepositDone    = false;
        craftClicksDone     = false;
        skillmultiConfirmed = false;
        skillmultiWaitMs    = 0;
        craftWaitMs         = 0;
        buyDishDone         = false;
        buyFlourDone        = false;
        buyWaterDone        = false;
        // NOTE: plannedTargetQty / plannedBuy*Qty / plannedBuyCost are NOT
        // reset here. The plan is computed in CHECKING_BANK and must survive
        // the setState(BUYING_SUPPLIES) transition that follows.
        sellBankDone        = false;
        sellDepositDone     = false;
        sellStarted         = false;
        bankFailures        = 0;
    }

    private void abortWith(String reason)
    {
        log.warn("pie-dish: {}", reason);
        status.set("ABORTED: " + reason);
        setState(State.ABORTED);
    }

    /** Worker-thread guard: ensures the inventory side-panel is the active
     *  tab, dispatching a click + waiting up to 2s if not. Returns true if
     *  the tab is open afterwards. */
    private boolean ensureInventoryTabOpen() throws InterruptedException
    {
        if (Boolean.TRUE.equals(onClient(() -> sidebarTabs.isOpen(SidebarTab.INVENTORY))))
            return true;
        if (dispatcher.isBusy()) return false;
        return sidebarTabs.openTabAndWait(SidebarTab.INVENTORY, 2_000L);
    }

    private int inventoryCount(int itemId)
    {
        Integer n = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return 0;
            int total = 0;
            for (Item it : inv.getItems())
                if (it != null && it.getId() == itemId)
                    total += Math.max(1, it.getQuantity());
            return total;
        });
        return n == null ? 0 : n;
    }

    private int inventorySlotOf(int itemId)
    {
        Integer s = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return -1;
            Item[] items = inv.getItems();
            for (int i = 0; i < items.length; i++)
            {
                Item it = items[i];
                if (it != null && it.getId() == itemId) return i;
            }
            return -1;
        });
        return s == null ? -1 : s;
    }

    /** Resolve the inventory widget bounds for the first slot holding {@code itemId}.
     *  Must be called on the client thread (or via {@link #onClient}). */
    private Rectangle resolveInvItemBounds(int itemId)
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INV);
        if (inv == null) return null;
        Item[] items = inv.getItems();
        int slot = -1;
        for (int i = 0; i < items.length; i++)
        {
            Item it = items[i];
            if (it != null && it.getId() == itemId) { slot = i; break; }
        }
        if (slot < 0) return null;
        Widget parent = client.getWidget(InterfaceID.Inventory.ITEMS);
        if (parent == null || parent.isHidden()) return null;
        Widget child = parent.getChild(slot);
        if (child == null || child.isSelfHidden()) return null;
        Rectangle r = child.getBounds();
        return r == null || r.isEmpty() ? null : r;
    }

    private <T> T onClient(Supplier<T> s)
    {
        if (client != null && client.isClientThread())
        {
            try { return s.get(); }
            catch (Throwable th) { log.warn("pie-dish onClient threw (inline)", th); return null; }
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> ref = new AtomicReference<>();
        clientThread.invokeLater(() -> {
            try   { ref.set(s.get()); }
            catch (Throwable th) { log.warn("pie-dish onClient threw", th); }
            finally { latch.countDown(); }
        });
        try
        {
            if (!latch.await(2_000, TimeUnit.MILLISECONDS))
            {
                log.warn("pie-dish: onClient timed out");
                return null;
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        return ref.get();
    }
}
