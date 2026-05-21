package net.runelite.client.plugins.recorder.scripts;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.widget.SidebarTab;
import net.runelite.client.plugins.recorder.widget.SidebarTabActions;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.internal.ActionRequest;

@Slf4j
public final class FletchingScript
{
    private static final int BOWSTRING = ItemID.BOW_STRING;  // 1777
    private static final int KNIFE     = ItemID.KNIFE;        // 946

    // ─── Timing ──────────────────────────────────────────────────────────────────
    private static final long TICK_MS               = 600;
    // 2.5s mirrors PizzaScript's BANK_OPEN_WAIT_MAX_MS — booth right-click +
    // menu pick + bank widget render can take ~2s on a heavy tick. 1.5s was
    // expiring before the bank actually opened, causing a re-click THROUGH the
    // already-open bank window onto a slot/button → wrong item withdrawn.
    private static final long BANK_PACE_MS          = 2_500;
    private static final long INTER_CLICK_SETTLE_MS = 100;
    private static final long POST_BATCH_MIN_MS     = 2_000;
    private static final long POST_BATCH_MAX_MS     = 8_000;
    private static final long SKILLMULTI_TIMEOUT_MS = 5_000;
    private static final long CRAFT_TIMEOUT_MS      = 90_000;
    private static final int  MAX_BANK_FAILURES     = 3;
    private static final long LEVEL_UP_DISMISS_MIN_MS = 3_000;
    private static final long LEVEL_UP_DISMISS_MAX_MS = 34_000;

    // ─── State ───────────────────────────────────────────────────────────────────
    public enum State { IDLE, BANKING, PROCESSING, ABORTED }

    public enum Mode { FLETCH, STRING, CUT_AND_STRING }
    private enum Action { CUT, STRING }

    public enum FletchItem
    {
        // Skillmulti option widgets: Normal logs have 5 options (arrow shafts,
        // javelin shafts, shortbow, longbow, wooden stock) → A,B,C,D,E.
        // Oak+ have arrow shafts at A, shortbow at B, longbow at C (stock/shield
        // are deferred to v2). Pattern copied from PieDishScript.tickCraftBatch
        // which dispatches CLICK_WIDGET on a specific Skillmulti.X option —
        // left-clicking the recipe IS the "Make-All" action.
        // Normal logs
        ARROW_SHAFTS(
            ItemID.LOGS, -1, -1,
            1, InterfaceID.Skillmulti.A, 1, 15, 5.0, false, true, "Arrow shafts"),
        SHORTBOW_U(
            ItemID.LOGS, ItemID.UNSTRUNG_SHORTBOW, ItemID.SHORTBOW,
            5, InterfaceID.Skillmulti.C, 1, 1, 5.0, true, true, "Shortbow (u)"),
        LONGBOW_U(
            ItemID.LOGS, ItemID.UNSTRUNG_LONGBOW, ItemID.LONGBOW,
            10, InterfaceID.Skillmulti.D, 1, 1, 10.0, true, true, "Longbow (u)"),

        // Oak logs
        OAK_ARROW_SHAFTS(
            ItemID.OAK_LOGS, -1, -1,
            15, InterfaceID.Skillmulti.A, 1, 30, 10.0, false, true, "Oak arrow shafts"),
        OAK_SHORTBOW_U(
            ItemID.OAK_LOGS, ItemID.UNSTRUNG_OAK_SHORTBOW, ItemID.OAK_SHORTBOW,
            20, InterfaceID.Skillmulti.B, 1, 1, 16.5, true, true, "Oak shortbow (u)"),
        OAK_LONGBOW_U(
            ItemID.OAK_LOGS, ItemID.UNSTRUNG_OAK_LONGBOW, ItemID.OAK_LONGBOW,
            25, InterfaceID.Skillmulti.C, 1, 1, 25.0, true, true, "Oak longbow (u)"),

        // Willow logs
        WILLOW_ARROW_SHAFTS(
            ItemID.WILLOW_LOGS, -1, -1,
            30, InterfaceID.Skillmulti.A, 1, 45, 15.0, false, true, "Willow arrow shafts"),
        WILLOW_SHORTBOW_U(
            ItemID.WILLOW_LOGS, ItemID.UNSTRUNG_WILLOW_SHORTBOW, ItemID.WILLOW_SHORTBOW,
            35, InterfaceID.Skillmulti.B, 1, 1, 33.3, true, true, "Willow shortbow (u)"),
        WILLOW_LONGBOW_U(
            ItemID.WILLOW_LOGS, ItemID.UNSTRUNG_WILLOW_LONGBOW, ItemID.WILLOW_LONGBOW,
            40, InterfaceID.Skillmulti.C, 1, 1, 41.5, true, true, "Willow longbow (u)"),

        // Maple logs
        MAPLE_ARROW_SHAFTS(
            ItemID.MAPLE_LOGS, -1, -1,
            45, InterfaceID.Skillmulti.A, 1, 60, 20.0, false, true, "Maple arrow shafts"),
        MAPLE_SHORTBOW_U(
            ItemID.MAPLE_LOGS, ItemID.UNSTRUNG_MAPLE_SHORTBOW, ItemID.MAPLE_SHORTBOW,
            50, InterfaceID.Skillmulti.B, 1, 1, 50.0, true, true, "Maple shortbow (u)"),
        MAPLE_LONGBOW_U(
            ItemID.MAPLE_LOGS, ItemID.UNSTRUNG_MAPLE_LONGBOW, ItemID.MAPLE_LONGBOW,
            55, InterfaceID.Skillmulti.C, 1, 1, 58.3, true, true, "Maple longbow (u)"),

        // Yew logs
        YEW_ARROW_SHAFTS(
            ItemID.YEW_LOGS, -1, -1,
            60, InterfaceID.Skillmulti.A, 1, 75, 25.0, false, true, "Yew arrow shafts"),
        YEW_SHORTBOW_U(
            ItemID.YEW_LOGS, ItemID.UNSTRUNG_YEW_SHORTBOW, ItemID.YEW_SHORTBOW,
            65, InterfaceID.Skillmulti.B, 1, 1, 67.5, true, true, "Yew shortbow (u)"),
        YEW_LONGBOW_U(
            ItemID.YEW_LOGS, ItemID.UNSTRUNG_YEW_LONGBOW, ItemID.YEW_LONGBOW,
            70, InterfaceID.Skillmulti.C, 1, 1, 75.0, true, true, "Yew longbow (u)"),

        // Magic logs
        MAGIC_ARROW_SHAFTS(
            ItemID.MAGIC_LOGS, -1, -1,
            75, InterfaceID.Skillmulti.A, 1, 90, 30.0, false, true, "Magic arrow shafts"),
        MAGIC_SHORTBOW_U(
            ItemID.MAGIC_LOGS, ItemID.UNSTRUNG_MAGIC_SHORTBOW, ItemID.MAGIC_SHORTBOW,
            80, InterfaceID.Skillmulti.B, 1, 1, 83.3, true, true, "Magic shortbow (u)"),
        MAGIC_LONGBOW_U(
            ItemID.MAGIC_LOGS, ItemID.UNSTRUNG_MAGIC_LONGBOW, ItemID.MAGIC_LONGBOW,
            85, InterfaceID.Skillmulti.C, 1, 1, 91.5, true, true, "Magic longbow (u)");

        final int     logId;
        final int     unstrungId;
        final int     strungId;
        final int     levelReq;
        final int     skillmultiWidget;
        final int     logsPerAction;
        final int     outputPerLog;
        final double  xp;
        final boolean canString;
        final boolean verified;
        final String  label;

        FletchItem(int logId, int unstrungId, int strungId,
                   int levelReq, int skillmultiWidget,
                   int logsPerAction, int outputPerLog, double xp,
                   boolean canString, boolean verified, String label)
        {
            this.logId            = logId;
            this.unstrungId       = unstrungId;
            this.strungId         = strungId;
            this.levelReq         = levelReq;
            this.skillmultiWidget = skillmultiWidget;
            this.logsPerAction    = logsPerAction;
            this.outputPerLog     = outputPerLog;
            this.xp               = xp;
            this.canString        = canString;
            this.verified         = verified;
            this.label            = label;
        }

        /** 26 for 2-log items (shields), 27 otherwise. */
        int logWithdrawCount() { return logsPerAction == 2 ? 26 : 27; }

        public boolean canString()   { return canString; }
        public boolean verified()    { return verified; }
        public String  label()       { return label; }
        public String  displayName() { return label; }

        public boolean supportsMode(Mode mode)
        {
            return switch (mode)
            {
                case FLETCH          -> true;
                case STRING, CUT_AND_STRING -> canString;
            };
        }

        @Override public String toString() { return label; }
    }

    // ─── Dependencies ────────────────────────────────────────────────────────────
    private final Client                   client;
    private final ClientThread             clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final BankInteraction          bank;
    private final SidebarTabActions        sidebarTabs;

    // ─── Configuration (set before start()) ──────────────────────────────────────
    private final AtomicReference<FletchItem> selectedItem = new AtomicReference<>(FletchItem.SHORTBOW_U);
    private final AtomicReference<Mode>       mode         = new AtomicReference<>(Mode.FLETCH);

    // ─── Runtime ─────────────────────────────────────────────────────────────────
    private final AtomicReference<State>  state   = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status  = new AtomicReference<>("idle");
    private final AtomicBoolean           running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker  = new AtomicReference<>();

    /** AFK-break humanizer; live-toggled from the panel.  When false,
     *  the {@link #breaks} scheduler is disabled and never fires. */
     private final AtomicBoolean afkBreaksEnabled = new AtomicBoolean(true);

    /** When true, dismissing a level-up popup also bumps {@link #selectedItem}
     *  to the highest-tier bow we can now make on the same log type (see
     *  {@link #maybeAdvanceItem()}). Arrow shafts stay on shafts. */
    private final AtomicBoolean autoLevelEnabled = new AtomicBoolean(false);

    /** AFK break scheduler.  Constructed in {@link #start()} so a
     *  Stop+Start rolls a fresh activity window.  Null while the
     *  worker isn't running. */
    private net.runelite.client.plugins.recorder.afk.BreakScheduler breaks;

    private Action nextAction = Action.CUT;

    // Per-state flags — reset in setState()
    private boolean depositDone, clicksDone, confirmed;
    private long    skillmultiWaitMs, craftWaitMs, lastBankActionMs;
    private int     bankFailures;
    private int     useClickFailures;
    private long    levelUpFirstSeenAtMs;
    private long    levelUpDismissAfterMs;

    // ─── Constructor ─────────────────────────────────────────────────────────────

    public FletchingScript(Client client, ClientThread clientThread,
                           HumanizedInputDispatcher dispatcher)
    {
        this.client      = client;
        this.clientThread = clientThread;
        this.dispatcher  = dispatcher;
        this.bank        = new BankInteraction(client, clientThread, dispatcher);
        this.sidebarTabs = new SidebarTabActions(client, clientThread, dispatcher);
    }

    // ─── Public API ──────────────────────────────────────────────────────────────

    public State     state()        { return state.get(); }
    public String    status()       { return status.get(); }
    public boolean   isRunning()    { return running.get(); }
    public FletchItem selectedItem(){ return selectedItem.get(); }
    public Mode      mode()         { return mode.get(); }

    public void setItem(FletchItem item) { selectedItem.set(item); }
    public void setMode(Mode m)          { mode.set(m); }
    public boolean afkBreaksEnabled() { return afkBreaksEnabled.get(); }
    public void setAfkBreaksEnabled(boolean v)
    {
        afkBreaksEnabled.set(v);
        log.info("fletching: setAfkBreaksEnabled({}) — scheduler {}", v,
            breaks == null ? "not yet constructed" : (v ? "re-enabled" : "disabled"));
        if (breaks != null)
        {
            if (v) breaks.enable(System.currentTimeMillis());
            else   breaks.disable();
        }
    }
    public boolean autoLevelEnabled() { return autoLevelEnabled.get(); }
    public void setAutoLevelEnabled(boolean v) { autoLevelEnabled.set(v); }
    /** Panel accessor for the break-status countdown line. */
    public String breakStatus()
    {
        return breaks == null
            ? "breaks: idle (script not running)"
            : breaks.statusLine(System.currentTimeMillis());
    }

    public void start()
    {
        Thread existing = worker.get();
        if (existing != null && existing.isAlive()) { status.set("already running"); return; }
        if (!running.compareAndSet(false, true)) return;

        FletchItem item = selectedItem.get();
        Mode m         = mode.get();

        // Preflight: mode+item compatibility
        if ((m == Mode.STRING || m == Mode.CUT_AND_STRING) && !item.canString)
        {
            status.set("ABORTED: " + item.label + " cannot be strung");
            running.set(false);
            return;
        }

        // Preflight: level check
        Integer level = onClient(() -> client.getRealSkillLevel(net.runelite.api.Skill.FLETCHING));
        if (level == null || level < item.levelReq)
        {
            status.set("ABORTED: need level " + item.levelReq + " (have " + level + ")");
            running.set(false);
            return;
        }

        nextAction = (m == Mode.STRING) ? Action.STRING : Action.CUT;
        setState(State.BANKING);

        // Fresh scheduler each Start — clean activity window.  start()
        // is guarded by running.compareAndSet so the construct/set isn't
        // racy with the worker thread, which spawns AFTER this line.
        breaks = new net.runelite.client.plugins.recorder.afk.BreakScheduler(
            System::currentTimeMillis,
            ThreadLocalRandom.current(),
            afkBreaksEnabled.get());

        Thread t = new Thread(this::tickLoop, "fletching-script");
        t.setDaemon(true);
        worker.set(t);
        t.start();
    }

    public void stop()
    {
        running.set(false);
        Thread t = worker.getAndSet(null);
        if (t != null) { t.interrupt(); try { t.join(2_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } }
        // Halt the scheduler explicitly — otherwise the panel's refresh
        // timer keeps polling breaks.statusLine() and the countdown
        // visually ticks forward even though the worker has exited.
        if (breaks != null) breaks.disable();
        setState(State.IDLE);
        status.set("stopped");
    }

    // ─── Tick loop ───────────────────────────────────────────────────────────────

    private void tickLoop()
    {
        try
        {
            ensureInventoryTabOpen();
            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                // Login-state gate. If OSRS auto-kicked us during an AFK
                // break (idle > 5 min) or the user logged out manually, the
                // bank-booth scan in tickBanking returns null silently and
                // the FSM busy-loops between "bank: opening" / "bank: pacing"
                // forever — exactly the 2026-05-21 GE incident. Hold here
                // until LOGGED_IN. No auto-login wired (parity with the
                // smaller scripts; PizzaScript has the LoginAssistantV2
                // path if we ever want it).
                GameState gs = onClient(client::getGameState);
                if (gs != null && gs != GameState.LOGGED_IN)
                {
                    status.set("logged out (" + gs + ") — waiting for login");
                    // Reset the bank pace timer so the first tick after
                    // re-login goes straight to "bank: opening" instead
                    // of sitting on a stale pace window.
                    lastBankActionMs = 0L;
                    SequenceSleep.sleep(client, TICK_MS);
                    continue;
                }
                switch (state.get())
                {
                    case BANKING    -> tickBanking();
                    case PROCESSING -> tickProcessing();
                    case IDLE, ABORTED -> running.set(false);
                }
                SequenceSleep.sleep(client, TICK_MS);
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        finally { running.set(false); }
    }

    // ─── Compile stubs (replaced in Tasks 3 and 4) ───────────────────────────────

    private void tickBanking() throws InterruptedException
    {
        // AFK-break gate.  Banking entry is the safe boundary (between
        // batches, never mid-process).  endBreakIfDue runs first so the
        // wake-up tick proceeds to normal work immediately rather than
        // wasting a tick.
        long breakNow = System.currentTimeMillis();
        if (breaks != null) breaks.endBreakIfDue(breakNow);
        if (breaks != null && breaks.isInBreak(breakNow))
        {
            status.set(breaks.statusLine(breakNow));
            return;
        }
        if (breaks != null && breaks.isBreakDue(breakNow, state.get() == State.BANKING))
        {
            breaks.startBreak(breakNow);
            status.set(breaks.statusLine(breakNow));
            return;
        }

        if (Boolean.TRUE.equals(onClient(bank::isBankPinUp)))
        { abortWith("bank PIN required"); return; }

        if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            long now = System.currentTimeMillis();
            if (now - lastBankActionMs < BANK_PACE_MS) { status.set("bank: pacing"); return; }
            // Fix 3: don't fire a second CLICK_NPC while the previous
            // right-click / Bank-pick chain is still running. Without
            // this gate every bank visit produces a duplicate
            // "dispatcher busy, dropping CLICK_NPC" log line.
            if (dispatcher.isBusy())
            {
                status.set("bank: opening (dispatcher busy)");
                return;
            }
            status.set("bank: opening");
            boolean clicked = bank.tryClickBankBoothRandom();
            lastBankActionMs = now;
            if (!clicked)
            {
                // No banker / booth in scene (player walked too far,
                // banker despawned, scene didn't reload). Same bankFailures
                // escalation as depositAllInventory / tryWithdrawX below.
                // Login-screen case is caught earlier in tickLoop, so this
                // path is genuinely "we're logged in but can't see a booth".
                if (++bankFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("no booth/banker in scene (" + bankFailures + "× scan miss)");
                    return;
                }
                status.set("bank: no booth/banker in scene (" + bankFailures + "/" + MAX_BANK_FAILURES + ")");
                return;
            }
            bankFailures = 0;
            return;
        }
        if (!bank.bankReady()) { status.set("bank: waiting for contents"); return; }

        FletchItem item = selectedItem.get();

        // Auto-level: scan bank for a better-tier item we can switch to.
        // Gated on !depositDone so this evaluates ONCE per banking cycle
        // (right after the bank opens, before we deposit anything). If a
        // switch happens, the old tier's logs/bows still in inventory will
        // be cleared by depositAllInventory (the keep-knife fast-path
        // requires inventoryOnlyContains for the NEW item's ids, which
        // fails when the old tier's contents are present).
        if (autoLevelEnabled.get() && !depositDone)
        {
            FletchItem advanced = pickAutoLevelTarget(item);
            if (advanced != null)
            {
                log.info("fletching: autolevel switch — {} → {}", item.label(), advanced.label());
                selectedItem.set(advanced);
                item = advanced;
            }
        }

        if (!depositDone)
        {
            // Subsequent-batch optimization: on the CUT path, if we already have a knife
            // AND the inventory holds the just-made bows, deposit just the bows so the knife
            // stays — avoids the next-cycle "withdraw knife" trip. Falls back to deposit-all
            // for: the first bank visit (no knife yet), arrow shafts (no unstrung id),
            // STRING / CUT_AND_STRING-string-phase paths, AND when a rogue item is present
            // (e.g. from a bank-open-lag mis-click that withdrew the wrong thing) — in that
            // case keep-knife would leave the rogue item lurking and we'd lose a slot each
            // cycle, so depositAllInventory is the right reset.
            boolean canKeepKnife = nextAction == Action.CUT
                && item.unstrungId > 0
                && inventoryCount(KNIFE) > 0
                && inventoryCount(item.unstrungId) > 0
                && inventoryOnlyContains(KNIFE, item.logId, item.unstrungId);
            if (canKeepKnife)
            {
                status.set("bank: depositing " + item.label() + " (keep knife)");
                if (!bank.tryDepositAll(item.unstrungId))
                {
                    if (++bankFailures >= MAX_BANK_FAILURES) { abortWith("tryDepositAll bows failed " + bankFailures + "×"); return; }
                    return;
                }
            }
            else
            {
                status.set("bank: depositing");
                if (!bank.depositAllInventory())
                {
                    if (++bankFailures >= MAX_BANK_FAILURES) { abortWith("depositAllInventory failed " + bankFailures + "×"); return; }
                    return;
                }
            }
            depositDone = true; bankFailures = 0; lastBankActionMs = System.currentTimeMillis();
            return;
        }

        if (nextAction == Action.CUT)
        {
            // 1. Knife
            if (inventoryCount(KNIFE) == 0)
            {
                long knifeAmt = bank.bankItemAmount(KNIFE);
                if (knifeAmt <= 0) { abortWith("no knife in bank"); return; }
                status.set("bank: withdrawing knife");
                if (!bank.tryWithdrawX(KNIFE, 1))
                {
                    if (++bankFailures >= MAX_BANK_FAILURES) { abortWith("withdraw knife failed"); return; }
                    return;
                }
                bankFailures = 0; lastBankActionMs = System.currentTimeMillis();
                return;
            }
            // 2. Logs — Withdraw-All. Inventory cap is 27 with the knife already in,
            // so "All" naturally tops out at 27 logs and we avoid the Withdraw-X chatbox
            // round-trip. (Shield recipes that consume 2 logs/action are deferred to v2;
            // they'd need Withdraw-26 instead, but no v1 item uses logsPerAction > 1.)
            if (inventoryCount(item.logId) == 0)
            {
                long logAmt = bank.bankItemAmount(item.logId);
                if (logAmt <= 0) { abortWith("no logs in bank"); return; }
                status.set("bank: withdrawing logs (all)");
                if (!bank.tryWithdrawAll(item.logId))
                {
                    if (++bankFailures >= MAX_BANK_FAILURES) { abortWith("withdraw logs failed"); return; }
                    return;
                }
                bankFailures = 0; lastBankActionMs = System.currentTimeMillis();
                return;
            }
        }
        else // STRING
        {
            // 1. Unstrung bows
            if (inventoryCount(item.unstrungId) == 0)
            {
                long unstrungAmt = bank.bankItemAmount(item.unstrungId);
                if (unstrungAmt <= 0)
                {
                    if (mode.get() == Mode.CUT_AND_STRING) { nextAction = Action.CUT; depositDone = false; return; }
                    abortWith("no unstrung " + item.label() + " in bank"); return;
                }
                status.set("bank: withdrawing unstrung bows");
                if (!bank.tryWithdrawX(item.unstrungId, 14))
                {
                    if (++bankFailures >= MAX_BANK_FAILURES) { abortWith("withdraw unstrung failed"); return; }
                    return;
                }
                bankFailures = 0; lastBankActionMs = System.currentTimeMillis();
                return;
            }
            // 2. Bowstrings
            if (inventoryCount(BOWSTRING) == 0)
            {
                long bsAmt = bank.bankItemAmount(BOWSTRING);
                if (bsAmt <= 0)
                {
                    if (mode.get() == Mode.CUT_AND_STRING) { nextAction = Action.CUT; depositDone = false; return; }
                    abortWith("no bowstrings in bank"); return;
                }
                status.set("bank: withdrawing bowstrings");
                if (!bank.tryWithdrawX(BOWSTRING, 14))
                {
                    if (++bankFailures >= MAX_BANK_FAILURES) { abortWith("withdraw bowstrings failed"); return; }
                    return;
                }
                bankFailures = 0; lastBankActionMs = System.currentTimeMillis();
                return;
            }
        }

        // Inventory prepared check
        if (nextAction == Action.CUT)
        {
            if (inventoryCount(KNIFE) == 0 || inventoryCount(item.logId) < item.logsPerAction)
            { abortWith("inventory not prepared for cutting (knife=" + inventoryCount(KNIFE) + " logs=" + inventoryCount(item.logId) + ")"); return; }
        }
        else
        {
            if (inventoryCount(item.unstrungId) == 0 || inventoryCount(BOWSTRING) == 0)
            { abortWith("inventory not prepared for stringing"); return; }
        }

        // Close bank
        long now = System.currentTimeMillis();
        if (now - lastBankActionMs < BANK_PACE_MS) { status.set("bank: pacing close"); return; }
        status.set("bank: closing");
        bank.tryCloseBank();
        lastBankActionMs = now;
        SequenceSleep.sleep(client, 400);
        if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            setState(State.PROCESSING);
        }
    }

    private void tickProcessing() throws InterruptedException
    {
        if (nextAction == Action.CUT) tickCut();
        else                          tickString();
    }

    private void tickCut() throws InterruptedException
    {
        FletchItem item = selectedItem.get();
        // Level-up popup terminates the in-flight Skillmulti cs2, so on dismiss we MUST
        // reset the click chain — same pattern as UltraCompostScript.tickCrafting.
        if (safeDismissLevelUp())
        {
            status.set("cut: dismissed level-up — resetting click chain");
            clicksDone = false; confirmed = false;
            skillmultiWaitMs = 0L; craftWaitMs = 0L;
            return;
        }

        if (!clicksDone)
        {
            if (!ensureInventoryTabOpen()) { status.set("cut: opening inventory tab"); return; }
            if (dispatcher.isBusy())      { status.set("cut: dispatcher busy"); return; }

            int knifeSlot = inventorySlotOf(KNIFE);
            if (knifeSlot < 0) { abortWith("knife not in inventory"); return; }

            // Step A: engage Use mode on knife (pattern copied from PieDishScript.tickCraftBatch
            // lines 733-745: CLICK_INV_ITEM/MOUSE/slot/verb("Use") + awaitIdle + check lastErrorMessage).
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_INV_ITEM)
                .channel(ActionRequest.Channel.MOUSE)
                .slot(knifeSlot)
                .verb("Use")
                .build());
            dispatcher.awaitIdle(3_000L);
            String err = dispatcher.lastErrorMessage();
            if (err != null)
            {
                log.warn("fletching cut: Use click error: {}", err);
                if (++useClickFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("use-click on knife failed " + useClickFailures + "×");
                    return;
                }
                return;
            }
            useClickFailures = 0;

            SequenceSleep.sleep(client, INTER_CLICK_SETTLE_MS);

            // Step B: click a log slot
            Rectangle bounds = onClient(() -> resolveInvItemBounds(item.logId));
            if (bounds == null) { abortWith("logs not in inventory"); return; }
            dispatcher.clickCanvas(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
            dispatcher.awaitIdle(3_000L);

            clicksDone = true;
            skillmultiWaitMs = System.currentTimeMillis();
            status.set("cut: waiting for skillmulti");
            return;
        }

        if (!confirmed)
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
                    log.warn("fletching cut: skillmulti did not open after {}ms — retrying", elapsed);
                    dispatcher.dismissMenu();
                    clicksDone = false; skillmultiWaitMs = 0;
                }
                else status.set("cut: waiting for skillmulti (" + elapsed + "ms)");
                return;
            }
            // Left-click the recipe option widget — same pattern as
            // PieDishScript: left-click on Skillmulti.X IS "Make-All".
            // Avoids the dynamic "Space=last used" keybind that silently
            // mis-picks on a fresh batch.
            status.set("cut: clicking recipe option");
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_WIDGET)
                .channel(ActionRequest.Channel.MOUSE)
                .widgetId(item.skillmultiWidget)
                .build());
            dispatcher.awaitIdle(3_000L);
            String werr = dispatcher.lastErrorMessage();
            if (werr != null)
            {
                log.warn("fletching cut: recipe option click error: {} — retrying", werr);
                return;
            }
            confirmed = true;
            craftWaitMs = System.currentTimeMillis();
            return;
        }

        // Completion: done when fewer than logsPerAction logs remain
        int logCount = inventoryCount(item.logId);
        if (logCount < item.logsPerAction)
        {
            log.info("fletching cut: batch done ({} logs remaining)", logCount);
            onBatchDone();
            return;
        }

        // Re-confirm if dialog reopens
        boolean dialogOpen = Boolean.TRUE.equals(onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Skillmulti.UNIVERSE);
            return w != null && !w.isHidden();
        }));
        if (dialogOpen)
        {
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_WIDGET)
                .channel(ActionRequest.Channel.MOUSE)
                .widgetId(item.skillmultiWidget)
                .build());
        }

        long elapsed = System.currentTimeMillis() - craftWaitMs;
        if (elapsed > CRAFT_TIMEOUT_MS) { abortWith("cut timeout — " + logCount + " logs left after " + elapsed + "ms"); return; }
        status.set("cut: fletching (" + logCount + " logs, " + elapsed + "ms)");
    }

    private void tickString() throws InterruptedException
    {
        FletchItem item = selectedItem.get();
        if (safeDismissLevelUp())
        {
            status.set("string: dismissed level-up — resetting click chain");
            clicksDone = false; confirmed = false;
            skillmultiWaitMs = 0L; craftWaitMs = 0L;
            return;
        }

        if (!clicksDone)
        {
            if (!ensureInventoryTabOpen()) { status.set("string: opening inventory tab"); return; }
            if (dispatcher.isBusy())      { status.set("string: dispatcher busy"); return; }

            int bowstringSlot = inventorySlotOf(BOWSTRING);
            if (bowstringSlot < 0) { abortWith("bowstring not in inventory"); return; }

            // Step A: engage Use mode on bowstring (pattern copied from PieDishScript.tickCraftBatch
            // lines 733-745: CLICK_INV_ITEM/MOUSE/slot/verb("Use") + awaitIdle + check lastErrorMessage).
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_INV_ITEM)
                .channel(ActionRequest.Channel.MOUSE)
                .slot(bowstringSlot)
                .verb("Use")
                .build());
            dispatcher.awaitIdle(3_000L);
            String err = dispatcher.lastErrorMessage();
            if (err != null)
            {
                log.warn("fletching string: Use click error: {}", err);
                if (++useClickFailures >= MAX_BANK_FAILURES)
                {
                    abortWith("use-click on bowstring failed " + useClickFailures + "×");
                    return;
                }
                return;
            }
            useClickFailures = 0;

            SequenceSleep.sleep(client, INTER_CLICK_SETTLE_MS);

            // Step B: click the unstrung bow slot
            Rectangle bounds = onClient(() -> resolveInvItemBounds(item.unstrungId));
            if (bounds == null) { abortWith("unstrung bow not in inventory"); return; }
            dispatcher.clickCanvas(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
            dispatcher.awaitIdle(3_000L);

            clicksDone = true;
            skillmultiWaitMs = System.currentTimeMillis();
            status.set("string: waiting for skillmulti");
            return;
        }

        if (!confirmed)
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
                    log.warn("fletching string: skillmulti did not open after {}ms — retrying", elapsed);
                    dispatcher.dismissMenu();
                    clicksDone = false; skillmultiWaitMs = 0;
                }
                else status.set("string: waiting for skillmulti (" + elapsed + "ms)");
                return;
            }
            // Stringing dialog is single-option (just the matching strung bow).
            // Click Skillmulti.A directly — same widget-click pattern as
            // tickCut, no dynamic Space-rebinding to worry about.
            status.set("string: clicking recipe option");
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_WIDGET)
                .channel(ActionRequest.Channel.MOUSE)
                .widgetId(InterfaceID.Skillmulti.A)
                .build());
            dispatcher.awaitIdle(3_000L);
            String werr = dispatcher.lastErrorMessage();
            if (werr != null)
            {
                log.warn("fletching string: recipe option click error: {} — retrying", werr);
                return;
            }
            confirmed = true;
            craftWaitMs = System.currentTimeMillis();
            return;
        }

        int unstrungLeft = inventoryCount(item.unstrungId);
        if (unstrungLeft == 0) { log.info("fletching string: batch done"); onBatchDone(); return; }

        boolean dialogOpen = Boolean.TRUE.equals(onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Skillmulti.UNIVERSE);
            return w != null && !w.isHidden();
        }));
        if (dialogOpen)
        {
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_WIDGET)
                .channel(ActionRequest.Channel.MOUSE)
                .widgetId(InterfaceID.Skillmulti.A)
                .build());
        }

        long elapsed = System.currentTimeMillis() - craftWaitMs;
        if (elapsed > CRAFT_TIMEOUT_MS) { abortWith("string timeout — " + unstrungLeft + " unstrung left"); return; }
        status.set("string: stringing (" + unstrungLeft + " left, " + elapsed + "ms)");
    }

    private void onBatchDone() throws InterruptedException
    {
        // AFK pause before returning to bank
        long pause = POST_BATCH_MIN_MS + (long)(ThreadLocalRandom.current().nextDouble()
            * (POST_BATCH_MAX_MS - POST_BATCH_MIN_MS));
        status.set("batch done — pausing " + pause + "ms");
        SequenceSleep.sleep(client, pause);

        // Decide next action for CUT_AND_STRING
        Mode m = mode.get();
        if (m == Mode.CUT_AND_STRING)
            nextAction = (nextAction == Action.CUT && selectedItem.get().canString)
                ? Action.STRING : Action.CUT;
        // FLETCH-only stays CUT, STRING-only stays STRING (already set by start())

        setState(State.BANKING);
    }

    /** Detects + dismisses the level-up popup with a humanized 3–34s delay.
     *
     *  <p>Returns {@code true} ONCE per popup, on the tick we press Space —
     *  caller must reset the click chain ({@code clicksDone}, {@code confirmed},
     *  {@code skillmultiWaitMs}, {@code craftWaitMs}) and {@code return} so the
     *  next tick re-engages Use → click → Skillmulti. Same shape as
     *  {@code UltraCompostScript.dismissLevelUpIfVisible}: the popup terminates
     *  the in-flight Skillmulti cs2, so without the reset the FSM sits at
     *  "confirmed, waiting for inventory to drain" forever and only escapes via
     *  the 90s CRAFT_TIMEOUT_MS abort. */
    private boolean safeDismissLevelUp()
    {
        try
        {
            boolean visible = Boolean.TRUE.equals(onClient(() -> {
                Widget w = client.getWidget(InterfaceID.LevelupDisplay.UNIVERSE);
                return w != null && !w.isHidden();
            }));
            if (!visible)
            {
                levelUpFirstSeenAtMs = 0L;
                return false;
            }
            long now = System.currentTimeMillis();
            if (levelUpFirstSeenAtMs == 0L)
            {
                long delay = LEVEL_UP_DISMISS_MIN_MS
                    + ThreadLocalRandom.current().nextLong(
                        LEVEL_UP_DISMISS_MAX_MS - LEVEL_UP_DISMISS_MIN_MS);
                levelUpFirstSeenAtMs  = now;
                levelUpDismissAfterMs = now + delay;
                log.info("fletching: level-up — will dismiss in {}ms", delay);
                return false;
            }
            if (now >= levelUpDismissAfterMs)
            {
                log.info("fletching: level-up — pressing Space ({}ms after popup appeared)",
                    now - levelUpFirstSeenAtMs);
                dispatcher.tapKey(KeyEvent.VK_SPACE);
                levelUpFirstSeenAtMs = 0L;
                // Auto-level evaluation happens at the next BANKING entry,
                // when the bank is open and bankItemAmount() is queryable.
                return true;
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        catch (Throwable th) { log.warn("fletching: dismissLevelUp threw", th); }
        return false;
    }

    /** Auto-advance evaluation, called from {@link #tickBanking} once per
     *  banking cycle when the bank is open AND {@link #autoLevelEnabled}
     *  is true. Picks the highest-XP verified bow tier we can now make,
     *  based on:
     *   - current Fletching level (must satisfy {@code levelReq})
     *   - bank availability: logs (FLETCH/CUT_AND_STRING), unstrung +
     *     bowstrings (STRING/CUT_AND_STRING). Cross-tier jumps are
     *     allowed — at level 20 with oak logs in the bank, willow at 35
     *     etc., the script switches forward automatically.
     *  Arrow shafts are excluded: if the user picked shafts they probably
     *  want shafts XP, not bow XP.
     *  <p>Caller must reset {@code depositDone=false} after a switch so
     *  the next deposit clears the previous tier's logs/bows from
     *  inventory before withdrawing the new tier's. */
    private FletchItem pickAutoLevelTarget(FletchItem current) throws InterruptedException
    {
        Integer level = onClient(() -> client.getRealSkillLevel(net.runelite.api.Skill.FLETCHING));
        if (level == null) return null;
        Mode m = mode.get();
        // Upward-only: only switch to a STRICTLY higher-XP candidate in the
        // same category (bow ↔ bow or shaft ↔ shaft). If current's logs run
        // out and only lower tiers are available, we DO NOT downshift — the
        // user prefers a clean abort to a silent XP cut. Cross-category
        // (bow → shaft) is also disallowed.
        FletchItem best = current;
        for (FletchItem candidate : FletchItem.values())
        {
            if (!candidate.verified) continue;
            if (candidate.canString != current.canString) continue;
            if (candidate.levelReq > level) continue;
            if (candidate.xp <= best.xp) continue;  // upward only
            if (!hasBankMaterialsFor(candidate, m)) continue;
            best = candidate;
        }
        return best == current ? null : best;
    }

    /** Materials check for {@link #pickAutoLevelTarget}: confirms the bank
     *  has whatever the candidate needs for the active mode. */
    private boolean hasBankMaterialsFor(FletchItem candidate, Mode m) throws InterruptedException
    {
        if (m != Mode.STRING)
        {
            if (bank.bankItemAmount(candidate.logId) <= 0) return false;
        }
        if (m != Mode.FLETCH)
        {
            if (candidate.unstrungId <= 0) return false;  // shafts can't be strung
            if (bank.bankItemAmount(candidate.unstrungId) <= 0) return false;
            if (bank.bankItemAmount(BOWSTRING) <= 0) return false;
        }
        return true;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private void setState(State s)
    {
        log.info("fletching: {} → {}", state.get(), s);
        state.set(s);
        depositDone = false; clicksDone = false; confirmed = false;
        skillmultiWaitMs = 0; craftWaitMs = 0; lastBankActionMs = 0; bankFailures = 0; useClickFailures = 0;
    }

    private void abortWith(String reason)
    {
        log.warn("fletching: {}", reason);
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

    /** True iff every non-empty inventory slot holds one of the supplied item IDs.
     *  Empty slots (id ≤ 0) are ignored. Used to detect "rogue" items from a
     *  bank-window mis-click (the bank-open-lag race): if a wrong item slipped
     *  in, the keep-knife fast-path can't deposit it, so the next bank visit
     *  must fall back to depositAllInventory to clean up. */
    private boolean inventoryOnlyContains(int... allowedIds)
    {
        Boolean r = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return Boolean.TRUE;
            for (Item it : inv.getItems())
            {
                if (it == null) continue;
                int id = it.getId();
                if (id <= 0) continue;
                boolean match = false;
                for (int allowed : allowedIds)
                    if (allowed > 0 && allowed == id) { match = true; break; }
                if (!match) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(r);
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
            catch (Throwable th) { log.warn("fletching onClient threw (inline)", th); return null; }
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> ref = new AtomicReference<>();
        clientThread.invokeLater(() -> {
            try   { ref.set(s.get()); }
            catch (Throwable th) { log.warn("fletching onClient threw", th); }
            finally { latch.countDown(); }
        });
        try
        {
            if (!latch.await(2_000, TimeUnit.MILLISECONDS))
            {
                log.warn("fletching: onClient timed out");
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
