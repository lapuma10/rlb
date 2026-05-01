package net.runelite.client.sequence.activities.banking;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.dispatch.BlockingTask;
import net.runelite.client.sequence.internal.ActionRequest;
import net.runelite.client.sequence.views.BankItemAvailability;
import net.runelite.client.sequence.views.BankView;
import net.runelite.client.sequence.views.InventoryView;
import net.runelite.client.sequence.views.Presence;

/**
 * Withdraws a desired quantity of an item from the bank.
 *
 * <p>Canonical implementation per spec §9.3.
 */
public final class WithdrawItemStep implements Step {

    private final int itemId;
    private final WithdrawQuantity desired;
    private final BankActions bank;
    private final String displayName;   // resolved once at construction for telemetry only

    public WithdrawItemStep(int itemId, WithdrawQuantity desired, BankActions bank) {
        this.itemId = itemId;
        this.desired = desired;
        this.bank = bank;
        this.displayName = String.valueOf(itemId);
    }

    @Override public String name()                                  { return "WithdrawItem(" + displayName + ", " + desired + ")"; }
    @Override public int priority()                                 { return 100; }
    /**
     * 6 ticks of <i>idle waiting</i> after the dispatcher worker finishes.
     * Busy-time during the right-click → menu pick → chatbox prompt →
     * humanized typing chain is held off the timer by
     * {@code StepFrame.lastBusyTick} (see CLAUDE.md "Threading model"),
     * so this only bounds the post-dispatch propagation window — i.e.
     * "how long do we wait for the server to apply the withdraw after
     * we pressed Enter?". 6 ticks ≈ 3.6s, plenty for server-tick
     * propagation while still failing fast on a genuine no-op.
     */
    @Override public int timeoutTicks()                             { return 6; }
    @Override public PreemptionPolicy preemptionPolicy()            { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard bb) { return true; }

    /**
     * canStart is always true. WithdrawItemStep has no waitable canStart=false conditions (per §7).
     * All bad states are either fatal preconditions (detected in onStart) or already-satisfied
     * (detected in onStart). BankContentsUnknown is fatal, not waitable — bank.ready()==true was
     * already guaranteed by WaitForBankReadyStep; UNKNOWN after that is an observer bug.
     */
    @Override public boolean canStart(WorldSnapshot s, Blackboard bb) {
        return true;
    }

    /**
     * onStart picks one of three paths and persists the choice to step blackboard so check() is deterministic:
     *   - already-satisfied → K_OUTCOME = ALREADY_SATISFIED, no dispatch
     *   - fatal precondition → K_PRECONDITION_FAILURE = BlockReason, no dispatch
     *   - fresh work → K_TARGET / K_INV_START / K_START_TICK; dispatch withdraw with computed delta
     *
     * Fatal preconditions checked in order: BankNotOpen → PinKeypadUp → BankContentsUnknown →
     * already-satisfied → BankMissingItem → InventoryFull → fresh work + dispatch.
     * BankContentsUnknown is fatal because bank.ready()==true (WaitForBankReadyStep guarantee)
     * implies the container is loaded; UNKNOWN at this point is an inconsistent observer state.
     */
    @Override public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        BankView b = s.bank();
        InventoryView i = s.inventory();
        // Count both noted and unnoted forms together. The bank may withdraw as
        // noted when inventory is full (different itemId in the slot), and we
        // want check() to recognise both forms as "the same item". Using
        // countAnyForm avoids the +1 heuristic — it keys off the canonical
        // unnoted id resolved from ItemComposition at snapshot time.
        int currentCount = i.countAnyForm(itemId);

        // 1) Fatal preconditions from unexpected bank states — record and bail without dispatching.
        if (!b.open()) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.BankNotOpen());
            return;
        }
        if (b.pinUp()) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.PinKeypadUp());
            return;
        }
        if (b.availability(itemId).presence() == Presence.UNKNOWN) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.BankContentsUnknown());
            return;
        }

        // 2) Already satisfied — no dispatch.
        if (alreadySatisfied(currentCount, i.freeSlots())) {
            step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
            return;
        }

        // 3) Fatal preconditions from item state — record and bail without dispatching.
        BankItemAvailability a = b.availability(itemId);
        if (a.presence() == Presence.ABSENT) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.BankMissingItem(itemId, displayName, minQty()));
            return;
        }
        int knownBankCount = a.knownCount().orElse(Integer.MAX_VALUE);
        int target = resolveTargetCount(currentCount, i.freeSlots(), knownBankCount);
        int delta = target - currentCount;

        // Slot-need calc: stackable items (incl. coins, runes, noted stacks
        // already held) collapse into one slot; non-stackables need one
        // slot each. If non-stackable bulk doesn't fit, fall back to
        // noted-form withdraw (always 1 slot) since GE accepts noted offers
        // and the user's typical bank-prep flow is feeding the GE.
        boolean stackable = a.stackable();
        int slotsNeeded = stackable ? (currentCount > 0 ? 0 : 1) : delta;
        boolean useNote = false;
        if (slotsNeeded > i.freeSlots()) {
            // Try noted form before giving up — single slot for any qty.
            int notedSlotsNeeded = currentCount > 0 ? 0 : 1;
            if (!stackable && notedSlotsNeeded <= i.freeSlots()) {
                useNote = true;
            } else {
                step.put(K_PRECONDITION_FAILURE, new BlockReason.InventoryFull(slotsNeeded - i.freeSlots()));
                return;
            }
        }

        // Fresh work — persist target/startTick and dispatch with the delta (NOT the original q).
        // currentCount and target are in canonical (any-form) units so check()'s
        // countAnyForm() comparison succeeds whether the bank withdrew noted or
        // unnoted form.
        step.put(K_OUTCOME, Outcome.FRESH);
        step.put(K_INV_START, currentCount);
        step.put(K_TARGET, target);
        step.put(K_START_TICK, s.tick());

        if (delta == 0) return;   // defensive belt-and-braces; alreadySatisfied should have caught this

        // Dispatch the multi-step withdraw flow (right-click → "Withdraw-X"
        // → chatbox numeric prompt → typed digits → Enter) onto the
        // dispatcher worker. onStart runs on the OSRS client thread, where
        // the BankActions guard would throw to prevent freezing the game.
        // See CLAUDE.md "Threading model" for the full explanation.
        final int id = itemId;
        final int qty = delta;
        final int knownBank = knownBankCount;
        final boolean note = useNote;
        // Wrap the bank lambda so the worker thread flips this flag on
        // completion. check() reads it to suppress the typed timeout
        // while our task is still running — typing 7-digit numbers
        // (e.g. 1.5M coins) takes ~7s, well past timeoutTicks=6, so
        // without this the engine fires WithdrawNoOp and retries
        // mid-type, dropping the duplicate dispatch and confusing the
        // state machine.
        final AtomicBoolean dispatchDone = new AtomicBoolean(false);
        final BlockingTask inner;
        final String taskName;
        if (note) {
            inner = () -> bank.withdrawAsNoteX(id, qty);
            taskName = "BANK_WITHDRAW_NOTE_X(" + id + "," + qty + ")";
        } else if (qty == 1) {
            inner = () -> bank.withdrawOne(id);
            taskName = "BANK_WITHDRAW_ONE(" + id + ")";
        } else if (qty >= knownBank) {
            inner = () -> bank.withdrawAll(id);
            taskName = "BANK_WITHDRAW_ALL(" + id + ")";
        } else {
            inner = () -> bank.withdrawX(id, qty);
            taskName = "BANK_WITHDRAW_X(" + id + "," + qty + ")";
        }
        BlockingTask wrapped = () -> {
            try { inner.run(); }
            finally { dispatchDone.set(true); }
        };
        step.put(K_DISPATCH_DONE, dispatchDone);
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.RUN_TASK)
            .channel(ActionRequest.Channel.MOUSE)
            .task(wrapped)
            .taskName(taskName)
            .build();
        ctx.dispatcher().dispatch(req);
    }

    @Override public void onEvent(Object e, StepContext ctx) { /* no-op — proof relies on snapshot verification */ }
    @Override public void tick(StepContext ctx)              { /* no per-tick action */ }

    @Override public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);

        // Fatal precondition recorded in onStart → first check returns Failed deterministically.
        Optional<DiagnosticReason> pre = step.get(K_PRECONDITION_FAILURE);
        if (pre.isPresent()) return Completion.failed(pre.get());

        // Already-satisfied path → first check returns Succeeded.
        if (step.get(K_OUTCOME).orElse(Outcome.FRESH) == Outcome.ALREADY_SATISFIED) {
            return new Completion.Succeeded(displayName + " already in inventory");
        }

        int target = step.get(K_TARGET).orElseThrow(() -> new IllegalStateException("K_TARGET missing"));
        int invStart = step.get(K_INV_START).orElse(s.inventory().countAnyForm(itemId));
        int now = s.inventory().countAnyForm(itemId);

        if (now >= target) return new Completion.Succeeded("withdrew " + displayName + " (delta=" + (now - invStart) + ")");

        // Bank closed mid-step.
        if (!s.bank().open()) return Completion.failed(new BlockReason.BankNotOpen());

        // Item disappeared from bank mid-step (rare but possible — someone moved it, bank was reset).
        if (s.bank().availability(itemId).presence() == Presence.ABSENT) {
            return Completion.failed(new BlockReason.BankMissingItem(itemId, displayName, target));
        }

        // Typed timeout — deterministic, fires before the engine's generic timeoutTicks() (§10.6).
        // Engine post-processes check() result before applying timeoutTicks(), so returning Failed here
        // means Failure.diagnostic carries WithdrawNoOp, not the engine's untyped Failure.timeout.
        //
        // Suppress the typed timeout while our RUN_TASK is still in flight on
        // the dispatcher worker — typing 7-digit quantities (1.5M coins,
        // etc.) takes ~7s, longer than timeoutTicks=6 (3.6s). Time out only
        // after the worker has finished AND inventory still hasn't changed.
        AtomicBoolean dispatchDone = step.get(K_DISPATCH_DONE).orElse(null);
        boolean dispatchInFlight = dispatchDone != null && !dispatchDone.get();
        if (dispatchInFlight) return Completion.RUNNING;

        int startTick = step.get(K_START_TICK).orElse(s.tick());
        if (s.tick() - startTick >= timeoutTicks() && now == invStart) {
            return Completion.failed(new BlockReason.WithdrawNoOp(itemId, timeoutTicks()));
        }
        return Completion.RUNNING;
    }

    @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        DiagnosticReason r = f.diagnostic();
        // Retry(1) is bounded at 1 retry (2 total attempts) because the engine tracks the attempt
        // count cumulatively and aborts once attempt count > maxAttempts — it does NOT call
        // onFailure indefinitely. If bank is still non-responsive after the retry, the re-dispatch
        // produces another WithdrawNoOp, onFailure is called again but the engine will see
        // attempt==2 > maxAttempts==1 and abort the step regardless of what we return here.
        if (r instanceof BlockReason.WithdrawNoOp) return new Recovery.Retry(1);
        // All other typed reasons are unrecoverable by this step alone — abort.
        return new Recovery.Abort(f.reason());
    }

    // ─── helpers ─────────────────────────────────────────────────────────
    // No rejectWaitable helper — canStart() always returns true; no canStart=false conditions exist.

    private boolean alreadySatisfied(int currentCount, int freeSlots) {
        if (desired instanceof WithdrawQuantity.AtLeast a) {
            return currentCount >= a.qty();
        }
        if (desired instanceof WithdrawQuantity.WithdrawAmount) {
            // Never short-circuit — the caller wants a fresh withdraw of
            // qty on top of whatever is already in inventory.
            return false;
        }
        // FillRemainingInventory: never "already satisfied" just because count >= 1 — only when no room remains.
        return freeSlots == 0;
    }

    private int minQty() {
        if (desired instanceof WithdrawQuantity.AtLeast a) {
            return a.qty();
        }
        if (desired instanceof WithdrawQuantity.WithdrawAmount w) {
            return w.qty();
        }
        return 1;   // FillRemainingInventory — for telemetry / BankMissingItem.requiredQty only
    }

    private int resolveTargetCount(int currentCount, int freeSlots, int knownBankCount) {
        if (desired instanceof WithdrawQuantity.AtLeast a) {
            int want = Math.max(a.qty(), currentCount);
            // Cap to what the bank actually has — prevents WithdrawX typing a
            // number higher than bank count (which gives "x 65" instead of 65
            // and then the check fails because target=67 but inv=65).
            return Math.min(want, currentCount + knownBankCount);
        }
        if (desired instanceof WithdrawQuantity.WithdrawAmount w) {
            // Cap by what the bank actually has — if bank has less than
            // requested (e.g. 4482 coins vs. 5k nice-buffer), take all
            // of it. EnsureInventoryForBuyStep then validates the actual
            // purchase can be funded.
            return currentCount + Math.min(w.qty(), knownBankCount);
        }
        // FillRemainingInventory: allow partial final trip — cap by knownBankCount (§8.2).
        return currentCount + Math.min(freeSlots, knownBankCount);
    }

    private enum Outcome { FRESH, ALREADY_SATISFIED }

    // Step-private working memory. The cross-step typed key (LAST_BLOCK_REASON) lives in
    // SequenceBlackboardKeys (§9.4) — never duplicated here.
    private static final BlackboardKey<Integer>          K_TARGET               = BlackboardKey.of("withdraw.target",               Integer.class);
    private static final BlackboardKey<Integer>          K_INV_START            = BlackboardKey.of("withdraw.invStart",             Integer.class);
    private static final BlackboardKey<Integer>          K_START_TICK           = BlackboardKey.of("withdraw.startTick",            Integer.class);
    private static final BlackboardKey<Outcome>          K_OUTCOME              = BlackboardKey.of("withdraw.outcome",              Outcome.class);
    private static final BlackboardKey<DiagnosticReason> K_PRECONDITION_FAILURE = BlackboardKey.of("withdraw.preconditionFailure",  DiagnosticReason.class);
    private static final BlackboardKey<AtomicBoolean>    K_DISPATCH_DONE        = BlackboardKey.of("withdraw.dispatchDone",         AtomicBoolean.class);
}
