# Fletching Script Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A bank-standing fletching script (knife+logs → bows/shafts, bowstring+unstrung → strung bow) covering levels 1–85 across normal/oak/willow/maple/yew/magic logs, with break scheduler and Cut+String mode.

**Architecture:** Single `FletchingScript.java` — inline `FletchItem` enum, `Mode`/`Action` enums, FSM with states IDLE/BANKING/PROCESSING/ABORTED. Mirrors PieDishScript's `tickCraftBanking` + use-on-item interaction shape, but uses `tapKey` for skillmulti instead of widget clicks. Wired into RecorderPanel as a new "Fletching" tab.

**Tech Stack:** Java 17, RuneLite API, `BankInteraction`, `HumanizedInputDispatcher`, `BreakScheduler`, `SidebarTabActions`, Swing panel.

**Spec:** `docs/superpowers/specs/2026-05-20-fletching-script-design.md`

---

## Implementation Rules

- Every task must compile before committing. If a later method is referenced before it is implemented, add a temporary stub in the same task so the compile step passes.
- `FletchItem` and `Mode` are nested inside `FletchingScript` but used from `RecorderPanel` in a different package — they must be `public`. Fields accessed from outside the class must use public getters, not direct field access.
- Copy dispatcher, client-thread, bank, and level-up helper patterns exactly from PieDishScript before adapting names. Do not invent near-matches.
- Any bank content read (`bankItemAmount`) must be marshalled to the client thread via `onClient()` — verify how PieDishScript calls it and use the same pattern.

---

## File Map

| File | Action |
|---|---|
| `recorder/scripts/FletchingScript.java` | **Create** — entire script |
| `recorder/RecorderPanel.java` | **Modify** — add Fletching tab, `setFletchingScript()` |
| `recorder/RecorderPlugin.java` | **Modify** — instantiate + wire |

---

## Task 1: FletchItem enum + Mode/Action enums

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/FletchingScript.java`

- [ ] **Step 1: Create the file with enums only**

```java
package net.runelite.client.plugins.recorder.scripts;

import java.awt.event.KeyEvent;
import net.runelite.api.gameval.ItemID;

public final class FletchingScript
{
    static final int BOWSTRING = ItemID.BOW_STRING;  // 1777
    static final int KNIFE     = ItemID.KNIFE;        // 946

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
}
```

- [ ] **Step 2: Compile**
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:compileJava 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**
```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/FletchingScript.java
git commit -m "feat(fletching): FletchItem enum + Mode/Action enums"
```

---

## Task 2: Script skeleton — fields, helpers, start/stop

**Files:**
- Modify: `recorder/scripts/FletchingScript.java`

- [ ] **Step 1: Add all class-level fields and helper methods**

Add these imports at the top:
```java
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
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.widget.SidebarTab;
import net.runelite.client.plugins.recorder.widget.SidebarTabActions;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.internal.ActionRequest;
```

Make the class `@Slf4j` and add:

```java
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
```

- [ ] **Step 2: Constructor**

```java
public FletchingScript(Client client, ClientThread clientThread,
                       HumanizedInputDispatcher dispatcher)
{
    this.client      = client;
    this.clientThread = clientThread;
    this.dispatcher  = dispatcher;
    this.bank        = new BankInteraction(client, clientThread, dispatcher);
    this.sidebarTabs = new SidebarTabActions(client, clientThread, dispatcher);
}
```

- [ ] **Step 3: Public API**

```java
public State     state()        { return state.get(); }
public String    status()       { return status.get(); }
public boolean   isRunning()    { return running.get(); }
public FletchItem selectedItem(){ return selectedItem.get(); }
public Mode      mode()         { return mode.get(); }

public void setItem(FletchItem item) { selectedItem.set(item); }
public void setMode(Mode m)          { mode.set(m); }
```

- [ ] **Step 4: `start()` stub (preflight only, no worker yet)**

```java
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
```

- [ ] **Step 5: `tickLoop` stub**

```java
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
```

- [ ] **Step 6: Add temporary stubs so Task 2 compiles**

These are replaced in later tasks. They must be present now or `tickLoop()` will not compile.

```java
private void tickBanking() throws InterruptedException
{
    status.set("banking not implemented yet");
}

private void tickProcessing() throws InterruptedException
{
    status.set("processing not implemented yet");
}

private void safeDismissLevelUp()
{
    // replaced in Task 4
}
```

- [ ] **Step 7: Helpers (copy pattern from PieDishScript)**

```java
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

// ensureInventoryTabOpen, inventoryCount, inventorySlotOf, resolveInvItemBounds, onClient:
// Copy verbatim from PieDishScript (lines ~1157–1248 of PieDishScript.java).
// These helpers are identical across all bank-standing scripts.
```

- [ ] **Step 8: Compile + commit**
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:compileJava 2>&1 | tail -5
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/FletchingScript.java
git commit -m "feat(fletching): script skeleton, start/stop, preflight validation"
```

---

## Task 3: BANKING tick

**Files:**
- Modify: `recorder/scripts/FletchingScript.java`

- [ ] **Step 1: Implement `tickBanking()`**

```java
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
        // bankItemAmount must be read on the client thread — copy the exact onClient() call
        // pattern from PieDishScript's banking method and use the same wrapper here.
        if (inventoryCount(KNIFE) == 0)
        {
            Integer knifeAmt = onClient(() -> bank.bankItemAmount(KNIFE));
            if (knifeAmt == null || knifeAmt <= 0) { abortWith("no knife in bank"); return; }
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
            Integer logAmt = onClient(() -> bank.bankItemAmount(item.logId));
            if (logAmt == null || logAmt <= 0) { abortWith("no logs in bank"); return; }
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
            Integer unstrungAmt = onClient(() -> bank.bankItemAmount(item.unstrungId));
            if (unstrungAmt == null || unstrungAmt <= 0)
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
            Integer bsAmt = onClient(() -> bank.bankItemAmount(BOWSTRING));
            if (bsAmt == null || bsAmt <= 0)
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
```

- [ ] **Step 2: Compile + commit**
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:compileJava 2>&1 | tail -5
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/FletchingScript.java
git commit -m "feat(fletching): BANKING tick — knife+logs and bowstring+unstrung withdrawal"
```

---

## Task 4: PROCESSING tick

**Files:**
- Modify: `recorder/scripts/FletchingScript.java`

- [ ] **Step 1: Implement `tickProcessing()`**

```java
private void tickProcessing() throws InterruptedException
{
    if (nextAction == Action.CUT) tickCut();
    else                          tickString();
}
```

- [ ] **Step 2: `tickCut()`**

> Before writing the `CLICK_INV_ITEM` dispatch below, open PieDishScript and find its use-on-item dispatch (knife-on-pastry-dough). Copy that block verbatim, then replace only the item id and slot variables. Do not invent a near-match.

```java
private void tickCut() throws InterruptedException
{
    FletchItem item = selectedItem.get();
    safeDismissLevelUp();

    if (!clicksDone)
    {
        if (!ensureInventoryTabOpen()) { status.set("cut: opening inventory tab"); return; }
        if (dispatcher.isBusy())      { status.set("cut: dispatcher busy"); return; }

        int knifeSlot = inventorySlotOf(KNIFE);
        if (knifeSlot < 0) { abortWith("knife not in inventory"); return; }

        // Step A: engage Use mode on knife
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_INV_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .slot(knifeSlot).verb("Use").build());
        dispatcher.awaitIdle(3_000);
        if (dispatcher.lastErrorMessage() != null) return;

        SequenceSleep.sleep(client, INTER_CLICK_SETTLE_MS);

        // Step B: click a log slot
        Rectangle bounds = onClient(() -> resolveInvItemBounds(item.logId));
        if (bounds == null) { abortWith("logs not in inventory"); return; }
        dispatcher.clickCanvas(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        dispatcher.awaitIdle(3_000);

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
        status.set("cut: pressing key " + item.fletchKey);
        dispatcher.tapKey(item.fletchKey);
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
    if (dialogOpen) dispatcher.tapKey(item.fletchKey);

    long elapsed = System.currentTimeMillis() - craftWaitMs;
    if (elapsed > CRAFT_TIMEOUT_MS) { abortWith("cut timeout — " + logCount + " logs left after " + elapsed + "ms"); return; }
    status.set("cut: fletching (" + logCount + " logs, " + elapsed + "ms)");
}
```

- [ ] **Step 3: `tickString()`**

> Same rule: copy PieDishScript's `CLICK_INV_ITEM` use-on-item block verbatim, replace slot/id only.

```java
private void tickString() throws InterruptedException
{
    FletchItem item = selectedItem.get();
    safeDismissLevelUp();

    if (!clicksDone)
    {
        if (!ensureInventoryTabOpen()) { status.set("string: opening inventory tab"); return; }
        if (dispatcher.isBusy())      { status.set("string: dispatcher busy"); return; }

        int bowstringSlot = inventorySlotOf(BOWSTRING);
        if (bowstringSlot < 0) { abortWith("bowstring not in inventory"); return; }

        // Step A: engage Use mode on bowstring
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_INV_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .slot(bowstringSlot).verb("Use").build());
        dispatcher.awaitIdle(3_000);
        if (dispatcher.lastErrorMessage() != null) return;

        SequenceSleep.sleep(client, INTER_CLICK_SETTLE_MS);

        // Step B: click the unstrung bow slot
        Rectangle bounds = onClient(() -> resolveInvItemBounds(item.unstrungId));
        if (bounds == null) { abortWith("unstrung bow not in inventory"); return; }
        dispatcher.clickCanvas(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        dispatcher.awaitIdle(3_000);

        clicksDone = true;
        skillmultiWaitMs = System.currentTimeMillis();
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
                dispatcher.dismissMenu();
                clicksDone = false; skillmultiWaitMs = 0;
            }
            else status.set("string: waiting for skillmulti (" + elapsed + "ms)");
            return;
        }
        dispatcher.tapKey(KeyEvent.VK_SPACE);
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
    if (dialogOpen) dispatcher.tapKey(KeyEvent.VK_SPACE);

    long elapsed = System.currentTimeMillis() - craftWaitMs;
    if (elapsed > CRAFT_TIMEOUT_MS) { abortWith("string timeout — " + unstrungLeft + " unstrung left"); return; }
    status.set("string: stringing (" + unstrungLeft + " left, " + elapsed + "ms)");
}
```

- [ ] **Step 4: `onBatchDone()` and `safeDismissLevelUp()`**

```java
private void onBatchDone() throws InterruptedException
{
    // AFK pause before returning to bank
    long pause = POST_BATCH_MIN_MS + (long)(java.util.concurrent.ThreadLocalRandom.current().nextDouble()
        * (POST_BATCH_MAX_MS - POST_BATCH_MIN_MS));
    status.set("batch done — pausing " + pause + "ms");
    SequenceSleep.sleep(client, pause);

    // Decide next action for CUT_AND_STRING
    Mode m = mode.get();
    if (m == Mode.CUT_AND_STRING)
        nextAction = (nextAction == Action.CUT && selectedItem.get().canString)
            ? Action.STRING : Action.CUT;
    // FLETCH-only stays CUT, STRING-only stays STRING (already set)

    setState(State.BANKING);
}

private void safeDismissLevelUp()
{
    // Reuse exact pattern from CookingScriptV3.safeDismissLevelUp —
    // random 3–34s delay before pressing Space on the level-up popup.
    // Copy the implementation from CookingScriptV3 (lines ~1266–1296).
}
```

- [ ] **Step 5: Compile + commit**
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:compileJava 2>&1 | tail -5
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/FletchingScript.java
git commit -m "feat(fletching): PROCESSING tick — cut and string paths"
```

---

## Task 5: Break scheduler

**Files:**
- Modify: `recorder/scripts/FletchingScript.java`

- [ ] **Step 1: Add break fields**

```java
private final AtomicBoolean afkBreaksEnabled = new AtomicBoolean(true);
private net.runelite.client.plugins.recorder.afk.BreakScheduler breaks;
```

- [ ] **Step 2: Construct in `start()`, disable in `stop()`**

At the top of `start()`, after the preflight checks and before spawning the worker:
```java
breaks = new net.runelite.client.plugins.recorder.afk.BreakScheduler(
    System::currentTimeMillis,
    java.util.concurrent.ThreadLocalRandom.current(),
    afkBreaksEnabled.get());
```

In `stop()`, before `setState(IDLE)`:
```java
if (breaks != null) breaks.disable();
```

- [ ] **Step 3: Gate in `tickLoop` at BANKING entry**

Add at the top of `tickBanking()`, before anything else:
```java
long now = System.currentTimeMillis();
if (breaks != null) breaks.endBreakIfDue(now);
if (breaks != null && breaks.isInBreak(now))
{ status.set(breaks.statusLine(now)); return; }
if (breaks != null && breaks.isBreakDue(now, state.get() == State.BANKING))
{ breaks.startBreak(now); status.set(breaks.statusLine(now)); return; }
```

- [ ] **Step 4: Public accessors for panel**

```java
public boolean afkBreaksEnabled()           { return afkBreaksEnabled.get(); }
public void    setAfkBreaksEnabled(boolean v)
{
    afkBreaksEnabled.set(v);
    if (breaks != null) { if (v) breaks.enable(System.currentTimeMillis()); else breaks.disable(); }
}
public String breakStatus()
{ return breaks == null ? "breaks: idle" : breaks.statusLine(System.currentTimeMillis()); }
```

- [ ] **Step 5: Compile + commit**
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:compileJava 2>&1 | tail -5
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/FletchingScript.java
git commit -m "feat(fletching): break scheduler wiring"
```

---

## Task 6: RecorderPanel — Fletching tab

**Files:**
- Modify: `recorder/RecorderPanel.java`

- [ ] **Step 1: Add fields near the other script fields (around line 329)**

```java
// ─── Fletching script ────────────────────────────────────────────────────────
private net.runelite.client.plugins.recorder.scripts.FletchingScript fletchingScript;
private final javax.swing.JComboBox<net.runelite.client.plugins.recorder.scripts.FletchingScript.Mode>
    fletchModeCombo = new javax.swing.JComboBox<>(
        net.runelite.client.plugins.recorder.scripts.FletchingScript.Mode.values());
private final javax.swing.JComboBox<net.runelite.client.plugins.recorder.scripts.FletchingScript.FletchItem>
    fletchItemCombo = new javax.swing.JComboBox<>();
private final javax.swing.JCheckBox fletchBreaksCheck = new javax.swing.JCheckBox("Enable breaks", true);
private final javax.swing.JCheckBox fletchDevCheck    = new javax.swing.JCheckBox("Dev mode (show unverified)", false);
private final javax.swing.JButton   fletchStartBtn    = new javax.swing.JButton("Start");
private final javax.swing.JButton   fletchStopBtn     = new javax.swing.JButton("Stop");
private final javax.swing.JLabel    fletchStatusLabel = new javax.swing.JLabel("idle");
private final javax.swing.JLabel    fletchBreakLabel  = new javax.swing.JLabel("breaks: idle");
```

- [ ] **Step 2: Add `"Fletching"` tab in the constructor (after "Cooking" tab, around line 408)**

```java
tabs.addTab("Fletching", tabScroll(buildFletchingTab()));
```

- [ ] **Step 3: Add `buildFletchingTab()`**

```java
private javax.swing.JPanel buildFletchingTab()
{
    javax.swing.JPanel p = new javax.swing.JPanel();
    p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
    p.setBorder(javax.swing.BorderFactory.createTitledBorder("Fletching"));

    javax.swing.JPanel buttons = new javax.swing.JPanel(new java.awt.GridLayout(1, 2, 4, 0));
    buttons.add(fletchStartBtn);
    buttons.add(fletchStopBtn);
    capHeight(buttons);

    javax.swing.JLabel modeLabel = new javax.swing.JLabel("Mode:");
    javax.swing.JLabel itemLabel = new javax.swing.JLabel("Item:");

    for (java.awt.Component c : new java.awt.Component[]{
        modeLabel, fletchModeCombo, itemLabel, fletchItemCombo,
        fletchBreaksCheck, fletchDevCheck, buttons,
        fletchStatusLabel, fletchBreakLabel})
    {
        ((java.awt.Component) c).setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        if (c instanceof javax.swing.JComboBox || c instanceof javax.swing.JCheckBox)
            capHeight((javax.swing.JComponent) c);
        p.add(c);
        p.add(javax.swing.Box.createVerticalStrut(2));
    }
    p.add(javax.swing.Box.createVerticalGlue());

    // Populate item combo based on mode + dev toggle
    Runnable refreshItems = () -> {
        var m = (net.runelite.client.plugins.recorder.scripts.FletchingScript.Mode) fletchModeCombo.getSelectedItem();
        boolean dev = fletchDevCheck.isSelected();
        fletchItemCombo.removeAllItems();
        for (var item : net.runelite.client.plugins.recorder.scripts.FletchingScript.FletchItem.values())
        {
            if (!item.verified() && !dev) continue;
            if (!item.supportsMode(m)) continue;
            fletchItemCombo.addItem(item);
        }
    };
    fletchModeCombo.addActionListener(e -> refreshItems.run());
    fletchDevCheck.addActionListener(e -> refreshItems.run());
    refreshItems.run();

    fletchBreaksCheck.addActionListener(e -> {
        if (fletchingScript != null) fletchingScript.setAfkBreaksEnabled(fletchBreaksCheck.isSelected());
    });

    fletchStartBtn.addActionListener(e -> onFletchStart());
    fletchStopBtn.addActionListener(e -> onFletchStop());
    return p;
}
```

- [ ] **Step 4: Add `setFletchingScript()`, `onFletchStart()`, `onFletchStop()`**

```java
public void setFletchingScript(
    net.runelite.client.plugins.recorder.scripts.FletchingScript script)
{
    this.fletchingScript = script;
}

private void onFletchStart()
{
    if (fletchingScript == null) { fletchStatusLabel.setText("unavailable"); return; }
    var item = (net.runelite.client.plugins.recorder.scripts.FletchingScript.FletchItem)
        fletchItemCombo.getSelectedItem();
    var m = (net.runelite.client.plugins.recorder.scripts.FletchingScript.Mode)
        fletchModeCombo.getSelectedItem();
    if (item == null || m == null) { fletchStatusLabel.setText("select mode + item"); return; }
    fletchingScript.setItem(item);
    fletchingScript.setMode(m);
    fletchingScript.setAfkBreaksEnabled(fletchBreaksCheck.isSelected());
    fletchingScript.start();
    fletchStatusLabel.setText("starting…");
}

private void onFletchStop()
{
    if (fletchingScript != null) fletchingScript.stop();
}
```

- [ ] **Step 5: Add status refresh to the panel timer (near line 3643 where `isRunning()` registrations live)**

```java
// Fletching status refresh — add near other script timer registrations
timer.addActionListener(e -> {
    if (fletchingScript == null) return;
    fletchStatusLabel.setText(fletchingScript.state() + " — " + fletchingScript.status());
    fletchBreakLabel.setText(fletchingScript.breakStatus());
});
// Also register isRunning for the session tracker:
plugin.getRecorderManager().registerScript("fletching",
    () -> fletchingScript != null && fletchingScript.isRunning());
```

Note: look at how the other `isRunning()` registrations are done around line 3643 and follow that exact pattern.

- [ ] **Step 6: Compile + commit**
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:compileJava 2>&1 | tail -5
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java
git commit -m "feat(fletching): RecorderPanel — Fletching tab with mode/item/breaks controls"
```

---

## Task 7: RecorderPlugin — instantiate and wire

**Files:**
- Modify: `recorder/RecorderPlugin.java`

- [ ] **Step 1: Add field (near the other script fields around line 163)**

```java
private net.runelite.client.plugins.recorder.scripts.FletchingScript fletchingScript;
```

- [ ] **Step 2: Instantiate in `startUp()` (after `pieDishScript`, around line 411)**

```java
// Fletching — independent dispatcher so it doesn't contend with pie/pizza.
net.runelite.client.sequence.dispatch.HumanizedInputDispatcher fletchDispatcher =
    new net.runelite.client.sequence.dispatch.HumanizedInputDispatcher(client, clientThread, config);
fletchingScript = new net.runelite.client.plugins.recorder.scripts.FletchingScript(
    client, clientThread, fletchDispatcher);
panel.setFletchingScript(fletchingScript);
```

- [ ] **Step 3: Stop in `shutDown()` (after `pieDishScript` stop, around line 891)**

```java
if (fletchingScript != null) { fletchingScript.stop(); fletchingScript = null; }
```

- [ ] **Step 4: Compile + commit**
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:compileJava 2>&1 | tail -5
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java
git commit -m "feat(fletching): RecorderPlugin — instantiate and wire FletchingScript"
```

---

## Task 8: Full build + smoke test checklist

- [ ] **Step 1: Full shadow jar build**
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :client:shadowJar 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Launch and navigate to Fletching tab**

Launch RuneLite, open the Recorder panel, confirm "Fletching" tab appears with:
- Mode dropdown (Fletch / String / Cut+String)
- Item dropdown (populated with verified shortbow/longbow/arrow shafts per mode)
- Enable breaks checkbox (checked)
- Dev mode checkbox (unchecked; checking it adds unverified items to dropdown)
- Start/Stop buttons + status labels

- [ ] **Step 3: Preflight rejection tests (no in-game needed)**

1. Select mode=String, item=Arrow shafts → Start → status should say "ABORTED: Arrow shafts cannot be strung"
2. Select mode=Fletch, item=Magic longbow (u) (level 85) with a low-level character → status should say "ABORTED: need level 85"

- [ ] **Step 4: Fletch mode smoke test (in-game)**

Bank: put knife + 27 willow logs in bank. Stand next to any bank booth.
Select Mode=Fletch, Item=Willow shortbow (u). Start.
Expected sequence:
1. Bank opens, deposits inventory, withdraws knife + 27 logs, closes
2. Use-mode on knife → click log → skillmulti opens → VK_2 pressed → logs consumed → 27 unstrung willows in inv
3. Random 2–8s pause → bank again → repeat

- [ ] **Step 5: Cut+String smoke test (in-game)**

Bank: put knife + willow logs + bowstrings in bank.
Select Mode=Cut+String, Item=Willow shortbow (u). Start.
Expected:
1. Cut phase: knife + 27 logs → 27 unstrung
2. Bank: deposits all (including knife), withdraws 14 unstrung + 14 bowstrings
3. String phase: bowstring on unstrung → Space → 14 strung willows
4. Bank: deposits, withdraws knife + 27 logs → CUT phase again

- [ ] **Step 6: Bowstring exhaustion fallback (in-game)**

Start Cut+String with no bowstrings in bank. After first cut trip, when stringing tries to start:
Expected: status shows "bank: withdrawing bowstrings" → fallback to CUT mode → continues cutting logs.

- [ ] **Step 7: Commit if any fixes needed**
```bash
git add -p
git commit -m "fix(fletching): smoke test corrections"
```
