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
 * Volcanic ash + supercompost → ultracompost profit loop at the Grand Exchange.
 *
 * <p>Recipe: 1 supercompost (bucket) + 2 volcanic ash → 1 ultracompost (bucket).
 * Ash stacks, so one inventory trip = 1 ash slot + 27 supercompost slots,
 * needing 54 ash per trip. Skillmulti shows a single option — Space confirms
 * Make-All.
 *
 * <p>Cycle:
 * <ol>
 *   <li>Check bank for existing supplies.</li>
 *   <li>Buy supercompost and volcanic ash via GE (2 ash per supercompost).</li>
 *   <li>Make ultracompost in 27-item bank trips.</li>
 *   <li>Withdraw ultracompost as noted and sell at GE.</li>
 *   <li>Repeat.</li>
 * </ol>
 *
 * <p><b>Pre-condition:</b> player must be standing in {@link #GE_AREA} when
 * {@link #start()} is called.
 *
 * <p>Shape mirrors {@link PieDishScript}; see that class for the threading
 * rationale and bank-failure semantics.
 */
@Slf4j
public final class UltraCompostScript
{
    // ─── Item IDs ────────────────────────────────────────────────────────────
    private static final int SUPERCOMPOST  = ItemID.BUCKET_SUPERCOMPOST;   // 6034
    private static final int VOLCANIC_ASH  = ItemID.FOSSIL_VOLCANIC_ASH;   // 21622
    private static final int ULTRACOMPOST  = ItemID.BUCKET_ULTRACOMPOST;   // 21483
    private static final int COINS         = 995;

    // ─── Prices ──────────────────────────────────────────────────────────────
    // Buy ceiling / sell floor — generous margins so the offers actually fill.
    // Live market hovers around supercompost ~70, ash ~100, ultracompost ~530.
    private static final int BUY_PRICE_SUPERCOMPOST = 120;
    private static final int BUY_PRICE_VOLCANIC_ASH = 150;
    private static final int SELL_PRICE_ULTRACOMPOST = 400;

    // ─── Quantities ──────────────────────────────────────────────────────────
    /** Ultracompost target per buy cycle; 4-hour GE limit checks elsewhere. */
    private static final int BATCH_QTY  = 500;
    /** Supercompost slots per inventory trip. Ash stacks in 1 slot, so
     *  27 supercompost + 1 ash stack = 28 slots = full inventory. */
    private static final int ULTRA_BATCH = 27;
    /** Ash withdrawal per trip — exact stoichiometry. Over-withdrawing is
     *  harmless (it stays in the same slot) and saves a bank trip if the
     *  batch underflows. */
    private static final int ASH_PER_BATCH = ULTRA_BATCH * 2;

    // ─── Timing ──────────────────────────────────────────────────────────────
    private static final long TICK_MS               = 650;
    private static final long BANK_PACE_MS          = 1_500;
    private static final long SKILLMULTI_TIMEOUT_MS = 5_000;
    private static final long CRAFT_TIMEOUT_MS      = 90_000;
    private static final int  MAX_BANK_FAILURES     = 3;

    // ─── GE area (covers exchange + bank booths) ─────────────────────────────
    static final WorldArea GE_AREA = new WorldArea(3140, 3470, 30, 30, 0);

    // ─── States ──────────────────────────────────────────────────────────────
    public enum State
    {
        IDLE,
        CHECKING_BANK,
        BUYING_SUPPLIES,
        MAKING_ULTRACOMPOST,
        SELLING_ULTRACOMPOST,
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

    // Phase toggles — read live in CHECKING_BANK. When the cycle would
    // transition to a disabled phase, the script DONEs cleanly so the user
    // can run a make-only loop (e.g. supplies bought manually) or a sell-
    // only loop (drain accumulated bank stock).
    private final AtomicBoolean buyEnabled  = new AtomicBoolean(true);
    private final AtomicBoolean sellEnabled = new AtomicBoolean(true);

    private int superAmt, ashAmt, ultraAmt;

    // Buy plan — computed in CHECKING_BANK, consumed across BUYING_SUPPLIES
    // tick chain. CHECKING_BANK overwrites every cycle.
    private int plannedTargetQty;
    private int plannedBuySuperQty;
    private int plannedBuyAshQty;
    private int plannedBuyCost;

    private boolean buySuperDone, buyAshDone;

    // Crafting flags.
    private boolean craftBankDone;
    private boolean craftDepositDone;
    private boolean craftClicksDone;
    private boolean skillmultiConfirmed;
    private long    skillmultiWaitMs;
    private long    craftWaitMs;

    private int bankFailures;

    // Selling.
    private boolean sellBankDone;
    private boolean sellDepositDone;
    private boolean sellStarted;

    private long lastBankActionMs;

    // ─── Constructor ─────────────────────────────────────────────────────────

    public UltraCompostScript(Client client, ClientThread clientThread,
                              HumanizedInputDispatcher dispatcher,
                              GrandExchangeScript geScript)
    {
        this.client       = client;
        this.clientThread = clientThread;
        this.dispatcher   = dispatcher;
        this.bank         = new BankInteraction(client, clientThread, dispatcher);
        this.sidebarTabs  = new SidebarTabActions(client, clientThread, dispatcher);
        this.geScript     = geScript;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    public State  state()  { return state.get(); }
    public String status() { return status.get(); }

    /** Toggle the GE buy phase. When disabled, the script DONEs the next
     *  time CHECKING_BANK would buy. Safe to flip mid-run. */
    public void setBuyEnabled(boolean v)  { buyEnabled.set(v); }
    /** Toggle the GE sell phase. When disabled, the script DONEs the next
     *  time CHECKING_BANK would sell. Safe to flip mid-run. */
    public void setSellEnabled(boolean v) { sellEnabled.set(v); }
    public boolean isBuyEnabled()  { return buyEnabled.get(); }
    public boolean isSellEnabled() { return sellEnabled.get(); }

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
        Thread t = new Thread(this::tickLoop, "ultra-compost-maker");
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
            ensureInventoryTabOpen();

            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                switch (state.get())
                {
                    case CHECKING_BANK        -> tickCheckBank();
                    case BUYING_SUPPLIES      -> tickBuySupplies();
                    case MAKING_ULTRACOMPOST  -> tickMakeUltraCompost();
                    case SELLING_ULTRACOMPOST -> tickSellUltraCompost();
                    case DONE, ABORTED, IDLE  -> running.set(false);
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

        int bankSuper = (int) bank.bankItemAmount(SUPERCOMPOST);
        int bankAsh   = (int) bank.bankItemAmount(VOLCANIC_ASH);
        int bankUltra = (int) bank.bankItemAmount(ULTRACOMPOST);

        int invSuper = inventoryCount(SUPERCOMPOST);
        int invAsh   = inventoryCount(VOLCANIC_ASH);
        int invUltra = inventoryCount(ULTRACOMPOST);

        superAmt = bankSuper + invSuper;
        ashAmt   = bankAsh   + invAsh;
        ultraAmt = bankUltra + invUltra;

        log.info("ultra-compost check: super={} (bank={} inv={}) ash={} (bank={} inv={}) ultra={} (bank={} inv={})",
            superAmt, bankSuper, invSuper,
            ashAmt, bankAsh, invAsh,
            ultraAmt, bankUltra, invUltra);

        State next;

        // A: enough ingredients for at least one ultracompost → make.
        //    Need 1 supercompost + 2 ash minimum.
        int makable = Math.min(superAmt, ashAmt / 2);
        if (makable > 0)
        {
            next = State.MAKING_ULTRACOMPOST;
        }
        // B: have ultracompost stock → sell (if sell phase is enabled).
        else if (ultraAmt > 0)
        {
            if (!sellEnabled.get())
            {
                bank.tryCloseBank();
                log.info("ultra-compost: sell disabled and {} ultracompost in stock — DONE", ultraAmt);
                status.set("DONE: sell disabled — " + ultraAmt + " ultracompost banked");
                setState(State.DONE);
                return;
            }
            next = State.SELLING_ULTRACOMPOST;
        }
        // C: cold start / topped up → buy enough to fill a batch (if buy enabled).
        else
        {
            if (!buyEnabled.get())
            {
                bank.tryCloseBank();
                log.info("ultra-compost: buy disabled and no ingredients — DONE");
                status.set("DONE: buy disabled — out of ingredients");
                setState(State.DONE);
                return;
            }
            int totalCoins = (int) bank.bankItemAmount(COINS) + inventoryCount(COINS);
            plannedTargetQty   = computeAffordableTarget(totalCoins, superAmt, ashAmt);
            plannedBuySuperQty = Math.max(0, plannedTargetQty - superAmt);
            plannedBuyAshQty   = Math.max(0, plannedTargetQty * 2 - ashAmt);
            plannedBuyCost     = plannedBuySuperQty * BUY_PRICE_SUPERCOMPOST
                               + plannedBuyAshQty   * BUY_PRICE_VOLCANIC_ASH;
            if (plannedTargetQty == 0)
            {
                bank.tryCloseBank();
                abortWith("not enough coins for one ultracompost (have " + totalCoins + ")");
                return;
            }
            log.info("ultra-compost buy plan: target={} (coins={}) → super+={} ash+={} cost={}",
                plannedTargetQty, totalCoins, plannedBuySuperQty, plannedBuyAshQty, plannedBuyCost);
            next = State.BUYING_SUPPLIES;
        }

        if (next == State.BUYING_SUPPLIES)
        {
            int bankCoins = (int) bank.bankItemAmount(COINS);
            if (bankCoins > 0)
            {
                boolean ok;
                if (bankCoins <= 1_000_000)
                {
                    log.info("ultra-compost: withdraw-all coins (bank={})", bankCoins);
                    ok = bank.tryWithdrawAll(COINS);
                }
                else
                {
                    int withdrawAmt = BankInteraction.roundUpForFastTyping(plannedBuyCost);
                    log.info("ultra-compost: withdraw {} coins (bank={}, planned={})",
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
                    return;
                }
                bankFailures = 0;
            }
            bank.tryCloseBank();
            SequenceSleep.sleep(client, 400);
        }
        setState(next);
    }

    /** Largest N in [0, BATCH_QTY] such that buying enough supercompost + ash to
     *  bring stocks up to (N supercompost, 2N ash) is affordable.
     *  cost(N) = max(0, N - super) * P_SUPER + max(0, 2N - ash) * P_ASH. */
    private static int computeAffordableTarget(int coins, int superHave, int ashHave)
    {
        int best = 0;
        for (int n = 1; n <= BATCH_QTY; n++)
        {
            long cost = (long) Math.max(0, n - superHave)     * BUY_PRICE_SUPERCOMPOST
                      + (long) Math.max(0, n * 2 - ashHave)   * BUY_PRICE_VOLCANIC_ASH;
            if (cost > coins) return best;
            best = n;
        }
        return best;
    }

    // ─── BUYING_SUPPLIES ─────────────────────────────────────────────────────

    private void tickBuySupplies() throws InterruptedException
    {
        log.info("ultra-compost buying: target={} super={} ash={} cost={}",
            plannedTargetQty, plannedBuySuperQty, plannedBuyAshQty, plannedBuyCost);

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

        boolean haveIngredients = inventoryCount(SUPERCOMPOST) > 0
                              || inventoryCount(VOLCANIC_ASH) > 0;
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

            if (inventoryCount(SUPERCOMPOST) > 0 && !bank.tryDepositAll(SUPERCOMPOST))
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("buy-deposit supercompost failed " + bankFailures + " consecutive times");
                    return;
                }
                return;
            }
            if (inventoryCount(VOLCANIC_ASH) > 0 && !bank.tryDepositAll(VOLCANIC_ASH))
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("buy-deposit volcanic ash failed " + bankFailures + " consecutive times");
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

        if (Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            bank.tryCloseBank();
            return;
        }

        if (plannedBuySuperQty == 0 && plannedBuyAshQty == 0)
        {
            log.warn("ultra-compost buying: empty plan — re-checking bank");
            setState(State.CHECKING_BANK);
            return;
        }

        boolean superComplete = plannedBuySuperQty == 0 || buySuperDone;
        boolean ashComplete   = plannedBuyAshQty   == 0 || buyAshDone;
        if (superComplete && ashComplete)
        {
            if (!geScript.tryCloseGrandExchange())
            {
                log.warn("ultra-compost buying: GE failed to close, retrying next tick");
                return;
            }
            log.info("ultra-compost buying: all planned buys submitted — re-checking bank");
            setState(State.CHECKING_BANK);
            return;
        }

        if (plannedBuySuperQty > 0 && !buySuperDone)
        {
            BuyItemIntent intent = new BuyItemIntent(SUPERCOMPOST, "Supercompost", plannedBuySuperQty,
                new PricePolicy.Exact(BUY_PRICE_SUPERCOMPOST),
                OfferWaitPolicy.untilOrPartialStall(600, 15));
            if (!geScript.startBuy(intent))
            {
                abortWith("GE: could not start buy for supercompost — " + geScript.status());
                return;
            }
            buySuperDone = true;
            status.set("buy: submitted supercompost ×" + plannedBuySuperQty);
            return;
        }
        if (plannedBuyAshQty > 0 && !buyAshDone)
        {
            BuyItemIntent intent = new BuyItemIntent(VOLCANIC_ASH, "Volcanic ash", plannedBuyAshQty,
                new PricePolicy.Exact(BUY_PRICE_VOLCANIC_ASH),
                OfferWaitPolicy.untilOrPartialStall(600, 15));
            if (!geScript.startBuy(intent))
            {
                abortWith("GE: could not start buy for volcanic ash — " + geScript.status());
                return;
            }
            buyAshDone = true;
            status.set("buy: submitted volcanic ash ×" + plannedBuyAshQty);
            return;
        }
    }

    // ─── MAKING_ULTRACOMPOST ─────────────────────────────────────────────────

    /**
     * Use volcanic ash on supercompost → Make-All ultracompost. Single-option
     * Skillmulti dialog, so Space confirms.
     *
     * <p>Ash is the use-item (stackable), supercompost is the target (occupies
     * the slots converted into ultracompost). Per trip: withdraw {@link
     * #ASH_PER_BATCH} ash (1 slot) + {@link #ULTRA_BATCH} supercompost.
     */
    private void tickMakeUltraCompost() throws InterruptedException
    {
        if (!craftBankDone)
        {
            tickCraftBanking();
            return;
        }

        if (!craftClicksDone)
        {
            if (!ensureInventoryTabOpen()) { status.set("craft: opening inventory tab"); return; }
            if (dispatcher.isBusy()) { status.set("craft: dispatcher busy"); return; }

            int useSlot = inventorySlotOf(VOLCANIC_ASH);
            if (useSlot < 0)
            {
                log.warn("ultra-compost craft: ash gone from inventory");
                craftBankDone    = false;
                craftDepositDone = false;
                return;
            }

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
                log.warn("ultra-compost craft: Use click error: {}", err);
                return;
            }

            SequenceSleep.sleep(client, 350);

            Rectangle bounds = onClient(() -> resolveInvItemBounds(SUPERCOMPOST));
            if (bounds == null)
            {
                log.warn("ultra-compost craft: supercompost slot not resolvable");
                craftBankDone    = false;
                craftDepositDone = false;
                return;
            }
            dispatcher.clickCanvas(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
            dispatcher.awaitIdle(3_000L);

            craftClicksDone   = true;
            skillmultiWaitMs  = System.currentTimeMillis();
            status.set("craft: clicks done — waiting for Make dialog");
            return;
        }

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
                    log.warn("ultra-compost craft: Skillmulti did not open after {}ms — retrying clicks", elapsed);
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
            status.set("craft: confirming Make All (Space)");
            dispatcher.tapKey(java.awt.event.KeyEvent.VK_SPACE);
            skillmultiConfirmed = true;
            craftWaitMs         = System.currentTimeMillis();
            return;
        }

        // Batch done when supercompost is exhausted (ash usually has leftover).
        int superInInv = inventoryCount(SUPERCOMPOST);
        int ashInInv   = inventoryCount(VOLCANIC_ASH);

        if (superInInv == 0 || ashInInv == 0)
        {
            log.info("ultra-compost craft: batch done (super={} ash={})", superInInv, ashInInv);
            craftBankDone    = false;
            craftDepositDone = false;
            craftClicksDone  = false;
            skillmultiConfirmed = false;
            craftWaitMs      = 0;
            return;
        }

        boolean dialogStillOpen = Boolean.TRUE.equals(onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Skillmulti.UNIVERSE);
            return w != null && !w.isHidden();
        }));
        if (dialogStillOpen)
        {
            dispatcher.tapKey(java.awt.event.KeyEvent.VK_SPACE);
        }

        long elapsed = System.currentTimeMillis() - craftWaitMs;
        if (elapsed > CRAFT_TIMEOUT_MS)
        {
            abortWith("craft timeout — " + superInInv + " supercompost still in inventory");
            return;
        }
        status.set("craft: making (" + superInInv + " supercompost left, " + elapsed + "ms)");
    }

    /**
     * Banking sub-phase: open bank → deposit all → withdraw ash stack →
     * withdraw supercompost → close. Sets {@link #craftBankDone} when done,
     * or transitions to {@link State#CHECKING_BANK} if supplies are out.
     */
    private void tickCraftBanking() throws InterruptedException
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

        long bankSuper = bank.bankItemAmount(SUPERCOMPOST);
        long bankAsh   = bank.bankItemAmount(VOLCANIC_ASH);
        int  invSuper  = inventoryCount(SUPERCOMPOST);
        int  invAsh    = inventoryCount(VOLCANIC_ASH);
        int  totalSuper = (int)(bankSuper + invSuper);
        int  totalAsh   = (int)(bankAsh   + invAsh);

        // Need at least one ultracompost worth: 1 supercompost + 2 ash.
        if (totalSuper <= 0 || totalAsh < 2)
        {
            log.info("ultra-compost: supplies exhausted (super={} ash={}) — re-checking bank",
                totalSuper, totalAsh);
            setState(State.CHECKING_BANK);
            return;
        }

        // Crafts achievable this trip = min(super-slots-we-can-fill, ash/2).
        int superThisTrip = Math.min(ULTRA_BATCH, Math.min(totalSuper, totalAsh / 2));
        int ashThisTrip   = Math.min(ASH_PER_BATCH, totalAsh);

        // 1. Withdraw ash first (stacks into 1 slot, never blocks the supercompost grab).
        if (invAsh < ashThisTrip)
        {
            long now = System.currentTimeMillis();
            if (now - lastBankActionMs < BANK_PACE_MS) { status.set("craft bank: pacing ash"); return; }
            int need = ashThisTrip - invAsh;
            status.set("craft bank: withdraw " + need + " ash");
            boolean ok = (bankAsh <= need)
                ? bank.tryWithdrawAll(VOLCANIC_ASH)
                : bank.tryWithdrawX(VOLCANIC_ASH, need);
            lastBankActionMs = System.currentTimeMillis();
            if (!ok)
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("withdraw ash failed " + bankFailures + " consecutive times");
                    return;
                }
            }
            else
            {
                bankFailures = 0;
            }
            return;
        }

        // 2. Withdraw supercompost (each occupies its own slot).
        if (invSuper < superThisTrip)
        {
            long now = System.currentTimeMillis();
            if (now - lastBankActionMs < BANK_PACE_MS) { status.set("craft bank: pacing super"); return; }
            int need = superThisTrip - invSuper;
            status.set("craft bank: withdraw " + need + " supercompost");
            boolean ok = (bankSuper <= need)
                ? bank.tryWithdrawAll(SUPERCOMPOST)
                : bank.tryWithdrawX(SUPERCOMPOST, need);
            lastBankActionMs = System.currentTimeMillis();
            if (!ok)
            {
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("withdraw supercompost failed " + bankFailures + " consecutive times");
                    return;
                }
            }
            else
            {
                bankFailures = 0;
            }
            return;
        }

        // 3. Close bank.
        long now = System.currentTimeMillis();
        if (now - lastBankActionMs < BANK_PACE_MS) { status.set("craft bank: pacing close"); return; }
        status.set("craft bank: closing");
        bank.tryCloseBank();
        lastBankActionMs = now;
        SequenceSleep.sleep(client, 400);
        if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            craftBankDone = true;
            log.info("ultra-compost craft: bank done — {} super + {} ash in inventory",
                inventoryCount(SUPERCOMPOST), inventoryCount(VOLCANIC_ASH));
        }
    }

    // ─── SELLING_ULTRACOMPOST ────────────────────────────────────────────────

    private void tickSellUltraCompost() throws InterruptedException
    {
        if (!sellBankDone)
        {
            tickSellBanking();
            return;
        }

        SequenceState geSt = geScript.state();
        if (geSt == SequenceState.RUNNING) { status.set("sell: " + geScript.status()); return; }
        if (geSt == SequenceState.FAILED)  { abortWith("GE sell failed: " + geScript.status()); return; }

        if (!sellStarted)
        {
            if (ultraAmt <= 0)
            {
                setState(State.CHECKING_BANK);
                return;
            }
            SellItemIntent intent = new SellItemIntent(
                ULTRACOMPOST, "Ultracompost", ultraAmt,
                new PricePolicy.Exact(SELL_PRICE_ULTRACOMPOST),
                OfferWaitPolicy.untilOrPartialStall(600, 15));
            if (!geScript.startSell(intent))
            {
                abortWith("GE sell start failed: " + geScript.status());
                return;
            }
            sellStarted = true;
            status.set("sell: submitted " + ultraAmt + " ultracompost @ " + SELL_PRICE_ULTRACOMPOST + "gp");
            return;
        }

        if (!geScript.tryCloseGrandExchange())
        {
            log.warn("ultra-compost: sell cycle done but GE failed to close, retrying next tick");
            return;
        }
        log.info("ultra-compost: sell cycle complete, starting new loop");
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

        long bankUltra = bank.bankItemAmount(ULTRACOMPOST);
        if (bankUltra <= 0)
        {
            bank.tryCloseBank();
            setState(State.CHECKING_BANK);
            return;
        }
        ultraAmt = (int) bankUltra;

        status.set("sell bank: withdraw " + ultraAmt + " noted ultracompost");
        if (!bank.tryWithdrawAsNoteX(ULTRACOMPOST, ultraAmt))
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
            log.info("ultra-compost sell: bank done — {} noted ultracompost in inventory", ultraAmt);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void setState(State s)
    {
        log.info("ultra-compost: {} → {}", state.get(), s);
        state.set(s);
        lastBankActionMs    = 0;
        craftBankDone       = false;
        craftDepositDone    = false;
        craftClicksDone     = false;
        skillmultiConfirmed = false;
        skillmultiWaitMs    = 0;
        craftWaitMs         = 0;
        buySuperDone        = false;
        buyAshDone          = false;
        // plannedTargetQty / plannedBuy*Qty / plannedBuyCost NOT reset —
        // computed in CHECKING_BANK, must survive into BUYING_SUPPLIES.
        sellBankDone        = false;
        sellDepositDone     = false;
        sellStarted         = false;
        bankFailures        = 0;
    }

    private void abortWith(String reason)
    {
        log.warn("ultra-compost: {}", reason);
        status.set("ABORTED: " + reason);
        setState(State.ABORTED);
    }

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
            catch (Throwable th) { log.warn("ultra-compost onClient threw (inline)", th); return null; }
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> ref = new AtomicReference<>();
        clientThread.invokeLater(() -> {
            try   { ref.set(s.get()); }
            catch (Throwable th) { log.warn("ultra-compost onClient threw", th); }
            finally { latch.countDown(); }
        });
        try
        {
            if (!latch.await(2_000, TimeUnit.MILLISECONDS))
            {
                log.warn("ultra-compost: onClient timed out");
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
