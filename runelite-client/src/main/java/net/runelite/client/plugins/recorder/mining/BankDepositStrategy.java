package net.runelite.client.plugins.recorder.mining;

import java.awt.Rectangle;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.farm.InventoryUtil;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.walker.PathSpec;
import net.runelite.client.plugins.recorder.walker.UniversalWalker;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;

/**
 * Walk-to-bank-and-deposit strategy. Replaces the previous stub.
 *
 * <p>Flow when the mining loop reports inventory full:
 * <ol>
 *   <li>Walk to {@code bankArea} via the {@code minesToBank} {@link PathSpec}.</li>
 *   <li>Click a bank booth or banker — {@link BankInteraction#clickBankBoothRandom}
 *       picks adjacent first, otherwise random.</li>
 *   <li>Wait for the bank widget to load. If it doesn't load within
 *       {@link #BANK_OPEN_TIMEOUT_MS}, abort.</li>
 *   <li>Click "Deposit inventory" — drops every item including the pickaxe
 *       (if held). Cheaper than clicking each ore individually.</li>
 *   <li>If a pickaxe was equipped instead of held, the deposit-inventory
 *       click leaves it equipped — no withdraw needed.</li>
 *   <li>If a pickaxe was held in the inventory and is configured via
 *       {@code pickaxeItemId}, withdraw 1 of it back so the bot can keep
 *       mining on return. Skip if {@code pickaxeItemId == 0}.</li>
 *   <li>Close the bank widget.</li>
 *   <li>Walk back to the rocks via {@code bankToMines}.</li>
 * </ol>
 *
 * <p>Each sub-step is throttled at ≥ {@link #BANK_PACE_MS} between
 * dispatches. Walker stuck / dispatcher errors / missing pickaxe in bank
 * all surface a status message and abort cleanly without leaking the bank
 * widget open. This mirrors the cooking script's banking robustness
 * (see {@code CookingScriptV2#tickBanking} / {@code CookingScriptV3#tickBanking}).
 *
 * <p>The strategy is location-agnostic — paths and the bank area are
 * injected at construction. Callers ({@code RecorderPlugin}) are free
 * to point this at any bank that has a path from the mining spot.
 */
@Slf4j
public final class BankDepositStrategy implements BankingStrategy
{
    /** Throttle between any two banking dispatches (booth click / deposit /
     *  withdraw / close). The bank widget needs ≥ one engine tick
     *  (~600ms) plus UI lag to process the previous click. */
    public static final long BANK_PACE_MS = 2000L;
    /** Hard cap on the bank-open phase — if the widget never appears
     *  after this many ms of clicking the booth, give up. */
    public static final long BANK_OPEN_TIMEOUT_MS = 12_000L;
    /** Hard cap on the entire bank-and-walk-back trip. Defensive: a
     *  walker stuck check kicks in earlier, but this caps the worst
     *  case. */
    public static final long TRIP_TIMEOUT_MS = 90_000L;
    /** Walker tick cadence — matches the cooking + chicken farm scripts. */
    public static final long WALK_TICK_MS = 600L;
    /** Grace period before treating "item not in bank container" as a
     *  hard miss. The container can be unpopulated for ~600ms after the
     *  widget opens. */
    public static final long BANK_LOAD_GRACE_MS = 1500L;

    private final UniversalWalker walker;
    private final BankInteraction bank;
    private final HumanizedInputDispatcher dispatcher;
    private final Client client;
    private final ClientThread clientThread;
    private final WorldArea bankArea;
    private final PathSpec minesToBank;
    private final PathSpec bankToMines;
    private final int pickaxeItemId;

    /**
     * Build a bank-deposit strategy.
     *
     * @param dispatcher    same dispatcher the mining loop uses (single-flight
     *                      per-strategy, no contention).
     * @param client        runelite client.
     * @param clientThread  for client-thread hops.
     * @param resolver      transport resolver for the walker.
     * @param bank          bank-interaction primitives — typically a fresh
     *                      instance bound to the same dispatcher.
     * @param bankArea      tile bbox the bank booth is in. Used to know
     *                      "we've arrived at the bank".
     * @param minesToBank   path from the mining spot to the bank.
     * @param bankToMines   reverse path.
     * @param pickaxeItemId 0 to skip pickaxe withdraw (player keeps a
     *                      worn pickaxe), or an {@code ItemID} constant
     *                      for the pickaxe to withdraw 1 of after deposit.
     */
    public BankDepositStrategy(HumanizedInputDispatcher dispatcher,
                               Client client, ClientThread clientThread,
                               TransportResolver resolver,
                               BankInteraction bank,
                               WorldArea bankArea,
                               PathSpec minesToBank, PathSpec bankToMines,
                               int pickaxeItemId)
    {
        if (bankArea == null) throw new IllegalArgumentException("bankArea required");
        if (minesToBank == null || bankToMines == null)
            throw new IllegalArgumentException("paths required");
        this.dispatcher = dispatcher;
        this.client = client;
        this.clientThread = clientThread;
        this.bank = bank;
        this.walker = new UniversalWalker(client, clientThread, dispatcher, resolver);
        this.bankArea = bankArea;
        this.minesToBank = minesToBank;
        this.bankToMines = bankToMines;
        this.pickaxeItemId = pickaxeItemId;
    }

    @Override public String label() { return "Bank"; }

    @Override
    public void empty(MiningLoopContext ctx) throws InterruptedException
    {
        long tripStart = System.currentTimeMillis();
        log.info("BankDepositStrategy: starting trip");

        // 1) Walk to bank.
        if (!walkUntilArrived(minesToBank, "→ bank", tripStart))
        {
            log.warn("BankDepositStrategy: walk to bank failed — aborting");
            return;
        }

        // 2-7) Open / deposit / withdraw pickaxe / close.
        if (!doBankCycle(tripStart))
        {
            log.warn("BankDepositStrategy: bank cycle failed — aborting");
            tryCloseBank();
            return;
        }

        // 8) Walk back. If this fails the mining loop sees we're not at
        //    the rocks and reselects (or aborts via SELECTING's "no live
        //    rock" path) — both clean.
        walkUntilArrived(bankToMines, "→ rocks", tripStart);
        log.info("BankDepositStrategy: trip complete in {}ms",
            System.currentTimeMillis() - tripStart);
    }

    /** Tick the walker against {@code spec} until ARRIVED or trip
     *  timeout. Returns true on success, false on stuck / error /
     *  timeout. Resets the walker when done. */
    private boolean walkUntilArrived(PathSpec spec, String stepName, long tripStart)
        throws InterruptedException
    {
        walker.reset();
        while (true)
        {
            if (System.currentTimeMillis() - tripStart > TRIP_TIMEOUT_MS)
            {
                log.warn("BankDepositStrategy: trip timeout during {}", stepName);
                return false;
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("stop");
            UniversalWalker.Status st = walker.tick(spec);
            switch (st)
            {
                case ARRIVED:
                    walker.reset();
                    return true;
                case STUCK:
                case ERROR:
                    log.warn("BankDepositStrategy: walker {} during {}", st, stepName);
                    return false;
                default:
                    SequenceSleep.sleep(client, WALK_TICK_MS);
            }
        }
    }

    /** Open / deposit / withdraw pickaxe / close cycle. Returns true on
     *  successful completion (bank closed, inventory ready for next trip).
     *  Returns false on any unrecoverable error — caller {@link #empty}
     *  is responsible for closing the bank if open. */
    private boolean doBankCycle(long tripStart) throws InterruptedException
    {
        long lastDispatch = 0L;
        long openedAtMs = 0L;
        int failCount = 0;
        boolean depositDone = false;
        boolean pickaxeDone = pickaxeItemId == 0;   // skip if not configured

        while (true)
        {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("stop");
            if (System.currentTimeMillis() - tripStart > TRIP_TIMEOUT_MS)
            {
                log.warn("BankDepositStrategy: trip timeout during bank cycle");
                return false;
            }
            long now = System.currentTimeMillis();
            long since = lastDispatch == 0 ? Long.MAX_VALUE : now - lastDispatch;
            if (since < BANK_PACE_MS) { SequenceSleep.sleep(client, WALK_TICK_MS); continue; }

            Boolean openBoxed = onClient(bank::isBankOpen);
            boolean open = Boolean.TRUE.equals(openBoxed);

            // 2) Open the bank if not open.
            if (!open)
            {
                if (openedAtMs > 0)
                {
                    if (now - openedAtMs > BANK_OPEN_TIMEOUT_MS)
                    {
                        log.warn("BankDepositStrategy: bank failed to open in {}ms",
                            BANK_OPEN_TIMEOUT_MS);
                        return false;
                    }
                }
                else
                {
                    openedAtMs = now;
                }
                dispatcher.lastErrorMessage();   // clear
                if (bank.tryClickBankBoothRandom())
                {
                    lastDispatch = now;
                    failCount = 0;
                }
                else
                {
                    failCount++;
                    if (failCount > 3) return false;
                }
                continue;
            }
            // Bank is open — record first-seen for grace timing.
            if (openedAtMs == 0L) openedAtMs = now;

            // Surface dispatcher errors from the previous bank action.
            String err = dispatcher.lastErrorMessage();
            if (err != null)
            {
                log.info("BankDepositStrategy: dispatcher error '{}'", err);
                failCount++;
                if (failCount > 3) return false;
            }

            // 3) Deposit inventory.
            if (!depositDone)
            {
                int free = onClientInt(() -> InventoryUtil.freeSlotCount(client));
                if (free >= InventoryUtil.INVENTORY_SIZE)
                {
                    depositDone = true;
                    continue;
                }
                if (clickDepositInventory())
                {
                    lastDispatch = now;
                    failCount = 0;
                }
                else
                {
                    failCount++;
                }
                continue;
            }

            // 4) Withdraw pickaxe if configured. Wait for the bank
            //    container to populate before checking presence.
            if (!pickaxeDone)
            {
                if (!bank.bankReady())
                {
                    if (now - openedAtMs < BANK_LOAD_GRACE_MS)
                    {
                        SequenceSleep.sleep(client, WALK_TICK_MS);
                        continue;
                    }
                    log.warn("BankDepositStrategy: bank container did not populate");
                    return false;
                }
                if (!bank.bankContainsItem(pickaxeItemId))
                {
                    log.warn("BankDepositStrategy: pickaxe (id={}) not in bank — continuing without",
                        pickaxeItemId);
                    pickaxeDone = true;
                    continue;
                }
                if (bank.tryWithdrawOne(pickaxeItemId))
                {
                    lastDispatch = now;
                    pickaxeDone = true;
                    failCount = 0;
                }
                else
                {
                    failCount++;
                    if (failCount > 3) return false;
                }
                continue;
            }

            // 5) Close bank.
            if (bank.tryCloseBank()) lastDispatch = now;
            // Wait one cycle for the close to flush.
            SequenceSleep.sleep(client, WALK_TICK_MS);
            Boolean stillOpenBoxed = onClient(bank::isBankOpen);
            boolean stillOpen = Boolean.TRUE.equals(stillOpenBoxed);
            if (!stillOpen) return true;
            failCount++;
            if (failCount > 3) return false;
        }
    }

    private boolean clickDepositInventory() throws InterruptedException
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

    private void tryCloseBank()
    {
        try
        {
            Boolean isOpen = onClient(bank::isBankOpen);
            if (Boolean.TRUE.equals(isOpen)) bank.tryCloseBank();
        }
        catch (Throwable th) { log.warn("BankDepositStrategy: closeBank threw", th); }
    }

    private int onClientInt(Supplier<Integer> s) throws InterruptedException
    {
        Integer v = onClient(s);
        return v == null ? 0 : v;
    }

    private <T> T onClient(Supplier<T> s) throws InterruptedException
    {
        java.util.concurrent.atomic.AtomicReference<T> ref =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(s.get()); }
            catch (Throwable th) { log.warn("BankDepositStrategy: onClient threw", th); }
            finally { latch.countDown(); }
        });
        if (!latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS))
        {
            log.warn("BankDepositStrategy: onClient timeout");
            return null;
        }
        return ref.get();
    }

    /** Expose the bank area so callers can sanity-check the loop's
     *  configuration. */
    public WorldArea bankArea() { return bankArea; }
}
