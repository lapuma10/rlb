package net.runelite.client.plugins.recorder.scripts;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
    private static final long BANK_PACE_MS          = 1_500;
    private static final long INTER_CLICK_SETTLE_MS = 100;
    private static final long POST_BATCH_MIN_MS     = 2_000;
    private static final long POST_BATCH_MAX_MS     = 8_000;
    private static final long SKILLMULTI_TIMEOUT_MS = 5_000;
    private static final long CRAFT_TIMEOUT_MS      = 90_000;
    private static final int  MAX_BANK_FAILURES     = 3;

    // ─── State ───────────────────────────────────────────────────────────────────
    public enum State { IDLE, BANKING, PROCESSING, ABORTED }

    public enum Mode { FLETCH, STRING, CUT_AND_STRING }
    private enum Action { CUT, STRING }

    public enum FletchItem
    {
        // Normal logs
        ARROW_SHAFTS(
            ItemID.LOGS, -1, -1,
            1, KeyEvent.VK_1, 1, 15, 5.0, false, true, "Arrow shafts"),
        SHORTBOW_U(
            ItemID.LOGS, ItemID.UNSTRUNG_SHORTBOW, ItemID.SHORTBOW,
            5, KeyEvent.VK_SPACE, 1, 1, 5.0, true, true, "Shortbow (u)"),
        LONGBOW_U(
            ItemID.LOGS, ItemID.UNSTRUNG_LONGBOW, ItemID.LONGBOW,
            10, KeyEvent.VK_4, 1, 1, 10.0, true, true, "Longbow (u)"),

        // Oak logs
        OAK_ARROW_SHAFTS(
            ItemID.OAK_LOGS, -1, -1,
            15, KeyEvent.VK_1, 1, 30, 10.0, false, true, "Oak arrow shafts"),
        OAK_SHORTBOW_U(
            ItemID.OAK_LOGS, ItemID.UNSTRUNG_OAK_SHORTBOW, ItemID.OAK_SHORTBOW,
            20, KeyEvent.VK_2, 1, 1, 16.5, true, true, "Oak shortbow (u)"),
        OAK_LONGBOW_U(
            ItemID.OAK_LOGS, ItemID.UNSTRUNG_OAK_LONGBOW, ItemID.OAK_LONGBOW,
            25, KeyEvent.VK_SPACE, 1, 1, 25.0, true, true, "Oak longbow (u)"),

        // Willow logs
        WILLOW_ARROW_SHAFTS(
            ItemID.WILLOW_LOGS, -1, -1,
            30, KeyEvent.VK_1, 1, 45, 15.0, false, true, "Willow arrow shafts"),
        WILLOW_SHORTBOW_U(
            ItemID.WILLOW_LOGS, ItemID.UNSTRUNG_WILLOW_SHORTBOW, ItemID.WILLOW_SHORTBOW,
            35, KeyEvent.VK_2, 1, 1, 33.3, true, true, "Willow shortbow (u)"),
        WILLOW_LONGBOW_U(
            ItemID.WILLOW_LOGS, ItemID.UNSTRUNG_WILLOW_LONGBOW, ItemID.WILLOW_LONGBOW,
            40, KeyEvent.VK_SPACE, 1, 1, 41.5, true, true, "Willow longbow (u)"),

        // Maple logs
        MAPLE_ARROW_SHAFTS(
            ItemID.MAPLE_LOGS, -1, -1,
            45, KeyEvent.VK_1, 1, 60, 20.0, false, true, "Maple arrow shafts"),
        MAPLE_SHORTBOW_U(
            ItemID.MAPLE_LOGS, ItemID.UNSTRUNG_MAPLE_SHORTBOW, ItemID.MAPLE_SHORTBOW,
            50, KeyEvent.VK_2, 1, 1, 50.0, true, true, "Maple shortbow (u)"),
        MAPLE_LONGBOW_U(
            ItemID.MAPLE_LOGS, ItemID.UNSTRUNG_MAPLE_LONGBOW, ItemID.MAPLE_LONGBOW,
            55, KeyEvent.VK_SPACE, 1, 1, 58.3, true, true, "Maple longbow (u)"),

        // Yew logs
        YEW_ARROW_SHAFTS(
            ItemID.YEW_LOGS, -1, -1,
            60, KeyEvent.VK_1, 1, 75, 25.0, false, true, "Yew arrow shafts"),
        YEW_SHORTBOW_U(
            ItemID.YEW_LOGS, ItemID.UNSTRUNG_YEW_SHORTBOW, ItemID.YEW_SHORTBOW,
            65, KeyEvent.VK_2, 1, 1, 67.5, true, true, "Yew shortbow (u)"),
        YEW_LONGBOW_U(
            ItemID.YEW_LOGS, ItemID.UNSTRUNG_YEW_LONGBOW, ItemID.YEW_LONGBOW,
            70, KeyEvent.VK_SPACE, 1, 1, 75.0, true, true, "Yew longbow (u)"),

        // Magic logs
        MAGIC_ARROW_SHAFTS(
            ItemID.MAGIC_LOGS, -1, -1,
            75, KeyEvent.VK_1, 1, 90, 30.0, false, true, "Magic arrow shafts"),
        MAGIC_SHORTBOW_U(
            ItemID.MAGIC_LOGS, ItemID.UNSTRUNG_MAGIC_SHORTBOW, ItemID.MAGIC_SHORTBOW,
            80, KeyEvent.VK_2, 1, 1, 83.3, true, true, "Magic shortbow (u)"),
        MAGIC_LONGBOW_U(
            ItemID.MAGIC_LOGS, ItemID.UNSTRUNG_MAGIC_LONGBOW, ItemID.MAGIC_LONGBOW,
            85, KeyEvent.VK_SPACE, 1, 1, 91.5, true, true, "Magic longbow (u)");

        final int     logId;
        final int     unstrungId;
        final int     strungId;
        final int     levelReq;
        final int     fletchKey;
        final int     logsPerAction;
        final int     outputPerLog;
        final double  xp;
        final boolean canString;
        final boolean verified;
        final String  label;

        FletchItem(int logId, int unstrungId, int strungId,
                   int levelReq, int fletchKey,
                   int logsPerAction, int outputPerLog, double xp,
                   boolean canString, boolean verified, String label)
        {
            this.logId         = logId;
            this.unstrungId    = unstrungId;
            this.strungId      = strungId;
            this.levelReq      = levelReq;
            this.fletchKey     = fletchKey;
            this.logsPerAction = logsPerAction;
            this.outputPerLog  = outputPerLog;
            this.xp            = xp;
            this.canString     = canString;
            this.verified      = verified;
            this.label         = label;
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

    private Action nextAction = Action.CUT;

    // Per-state flags — reset in setState()
    private boolean bankDone, depositDone, clicksDone, confirmed;
    private long    skillmultiWaitMs, craftWaitMs, lastBankActionMs;
    private int     bankFailures;

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
        if (Boolean.TRUE.equals(onClient(bank::isBankPinUp)))
        { abortWith("bank PIN required"); return; }

        if (!Boolean.TRUE.equals(onClient(bank::isBankOpen)))
        {
            long now = System.currentTimeMillis();
            if (now - lastBankActionMs < BANK_PACE_MS) { status.set("bank: pacing"); return; }
            status.set("bank: opening");
            bank.tryClickBankBoothRandom();
            lastBankActionMs = now;
            return;
        }
        if (!bank.bankReady()) { status.set("bank: waiting for contents"); return; }

        if (!depositDone)
        {
            status.set("bank: depositing");
            if (!bank.depositAllInventory())
            {
                if (++bankFailures >= MAX_BANK_FAILURES) { abortWith("depositAllInventory failed " + bankFailures + "×"); return; }
                return;
            }
            depositDone = true; bankFailures = 0; lastBankActionMs = System.currentTimeMillis();
            return;
        }

        FletchItem item = selectedItem.get();

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
            // 2. Logs
            if (inventoryCount(item.logId) == 0)
            {
                long logAmt = bank.bankItemAmount(item.logId);
                if (logAmt <= 0) { abortWith("no logs in bank"); return; }
                int qty = item.logWithdrawCount();
                status.set("bank: withdrawing " + qty + " logs");
                if (!bank.tryWithdrawX(item.logId, qty))
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
            bankDone = true;
            setState(State.PROCESSING);
        }
    }

    private void tickProcessing() throws InterruptedException
    {
        status.set("processing not implemented yet");
    }

    private void safeDismissLevelUp()
    {
        // replaced in Task 4
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private void setState(State s)
    {
        log.info("fletching: {} → {}", state.get(), s);
        state.set(s);
        bankDone = false; depositDone = false; clicksDone = false; confirmed = false;
        skillmultiWaitMs = 0; craftWaitMs = 0; lastBankActionMs = 0; bankFailures = 0;
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
