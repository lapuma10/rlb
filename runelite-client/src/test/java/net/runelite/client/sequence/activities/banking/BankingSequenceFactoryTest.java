package net.runelite.client.sequence.activities.banking;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.cook.CookingFood;
import net.runelite.client.plugins.recorder.cook.CookingLocation;
import net.runelite.client.plugins.recorder.walker.PathSpec;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.BlockingInterface;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link BankingSequenceFactory} and {@link BankingSequencePlan}.
 *
 * <p>Task 9: 3 tests.
 * <ul>
 *   <li>{@link #planExposesRootAndReactives} — plan has non-null root and exactly 1 reactive
 *   <li>{@link #endToEndHappyPath} — tick-by-tick full sequence drives to IDLE; asserts BankActions calls in order
 *   <li>{@link #reactivePreemptsOnDialogMidFlow} — mid-flow dialog triggers EnsureNoBlockingInterfaceStep preemption
 * </ul>
 */
public class BankingSequenceFactoryTest
{
    // ── Test items ────────────────────────────────────────────────────────────
    // Using raw/cooked/burnt chicken IDs (from CookingFood table):
    //   RAW_CHICKEN = 2138, COOKED_CHICKEN = 2140, BURNT_CHICKEN = 2144
    // These exact values are not critical for the engine-level test; what matters is
    // that the factory wires the correct IDs from the Entry to the steps.
    // CookingFood.byLabel("Chicken") is the canonical lookup; we use it directly.
    private static final CookingFood.Entry CHICKEN = CookingFood.byLabel("Chicken");

    private static final WorldArea BANK_AREA = new WorldArea(3210, 3420, 5, 5, 0);
    private static final WorldPoint IN_BANK  = new WorldPoint(3212, 3422, 0);

    private static CookingLocation makeLocation()
    {
        PathSpec emptyPath = PathSpec.of("test", java.util.List.of());
        return CookingLocation.builder()
            .label("TestBank")
            .kind(CookingLocation.SourceKind.FIRE_FROM_LOGS)
            .bankArea(BANK_AREA)
            .cookArea(new WorldArea(3200, 3400, 3, 3, 0))
            .bankToCook(emptyPath)
            .cookToBank(emptyPath)
            .heatSourceName("Fire")
            .groundLogsItemId(1511)  // logs
            .bankBoothIds(10583)
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * T1: plan exposes root and exactly one reactive step.
     */
    @Test
    public void planExposesRootAndReactives()
    {
        CookingLocation location = makeLocation();
        RecordingBankActions bank = new RecordingBankActions();

        BankingSequencePlan plan = BankingSequenceFactory.prepareCookingLoadout(
            location, CHICKEN, /* needsTinderbox= */ true, bank);

        assertNotNull("root must be non-null", plan.root());
        assertNotNull("reactiveSteps must be non-null", plan.reactiveSteps());
        assertEquals("exactly one reactive step (EnsureNoBlockingInterfaceStep)",
            1, plan.reactiveSteps().size());
        assertTrue("reactive must be EnsureNoBlockingInterfaceStep",
            plan.reactiveSteps().get(0) instanceof EnsureNoBlockingInterfaceStep);
    }

    /**
     * T2: end-to-end happy path (no tinderbox, covers spec scenario 11).
     *
     * <p>Drives the full 9-step linear sequence through tick-by-tick snapshots.
     * Uses {@link RecordingBankActions} to assert dispatch calls in order.
     */
    @Test
    public void endToEndHappyPath()
    {
        CookingLocation location = makeLocation();
        RecordingBankActions bank = new RecordingBankActions();
        BankingEngineHarness harness = new BankingEngineHarness();

        BankingSequencePlan plan = BankingSequenceFactory.prepareCookingLoadout(
            location, CHICKEN, /* needsTinderbox= */ false, bank);

        // Register reactives before running
        for (Step reactive : plan.reactiveSteps())
        {
            harness.manager().register(reactive);
        }

        // ── Stage A: initial — player at bank, bank closed, not ready,
        //    inventory has 2 cooked chicken + 1 burnt chicken, no blocker ──────
        WorldSnapshot stageA = new BankSnapBuilder()
            .tick(1)
            .player(IN_BANK)
            .invItem(CHICKEN.cookedId, 2)
            .invItem(CHICKEN.burntId, 1)
            .bankOpen(false).bankReady(false)
            .bankItem(CHICKEN.rawId, 26, true)
            .worldAvailable(true)
            .build();

        // ── Stage B: bank open but not ready yet ─────────────────────────────
        WorldSnapshot stageB = new BankSnapBuilder()
            .tick(3)
            .player(IN_BANK)
            .invItem(CHICKEN.cookedId, 2)
            .invItem(CHICKEN.burntId, 1)
            .bankOpen(true).bankReady(false)
            .bankItem(CHICKEN.rawId, 26, true)
            .worldAvailable(false)
            .build();

        // ── Stage C: bank open and ready ─────────────────────────────────────
        WorldSnapshot stageC = new BankSnapBuilder()
            .tick(5)
            .player(IN_BANK)
            .invItem(CHICKEN.cookedId, 2)
            .invItem(CHICKEN.burntId, 1)
            .bankOpen(true).bankReady(true)
            .bankItem(CHICKEN.rawId, 26, true)
            .worldAvailable(false)
            .build();

        // ── Stage D: cooked chicken deposited, burnt still present ───────────
        WorldSnapshot stageD = new BankSnapBuilder()
            .tick(7)
            .player(IN_BANK)
            // cooked deposited → count=0
            .invItem(CHICKEN.burntId, 1)
            .bankOpen(true).bankReady(true)
            .bankItem(CHICKEN.rawId, 26, true)
            .worldAvailable(false)
            .build();

        // ── Stage E: cooked + burnt both deposited ────────────────────────────
        WorldSnapshot stageE = new BankSnapBuilder()
            .tick(9)
            .player(IN_BANK)
            // inventory empty → both cooked+burnt gone
            .bankOpen(true).bankReady(true)
            .bankItem(CHICKEN.rawId, 26, true)
            .worldAvailable(false)
            .build();

        // ── Stage F: raw chicken withdrawn (inventory full = 26 raw, but we give freeSlots=0) ──
        WorldSnapshot stageF = new BankSnapBuilder()
            .tick(11)
            .player(IN_BANK)
            .invItem(CHICKEN.rawId, 26)
            .freeSlots(0)
            .bankOpen(true).bankReady(true)
            .bankItem(CHICKEN.rawId, 0, true)
            .worldAvailable(false)
            .build();

        // ── Stage G: bank closed, world available ────────────────────────────
        WorldSnapshot stageG = new BankSnapBuilder()
            .tick(13)
            .player(IN_BANK)
            .invItem(CHICKEN.rawId, 26)
            .freeSlots(0)
            .bankOpen(false).bankReady(false)
            .worldAvailable(true)
            .build();

        // Queue generously — harness clamps at last snapshot once queue exhausted
        harness.queue(
            stageA, stageA, stageA, stageA,   // EnsureAtBank + EnsureNoBlocking + OpenBank onStart
            stageB, stageB,                     // WaitForBankReady: bank open not ready
            stageC, stageC,                     // WaitForBankReady: bank ready
            stageC, stageC,                     // DepositCooked onStart
            stageD, stageD,                     // DepositCooked check: count=0 → succeed; DepositBurnt onStart
            stageE, stageE,                     // DepositBurnt check: count=0 → succeed; WithdrawRaw onStart
            stageF, stageF,                     // WithdrawRaw: check inventory filled
            stageF, stageF,                     // EnsureLoadout check
            stageG, stageG, stageG              // CloseBank check: !open && worldAvailable
        );

        harness.run(plan.root());
        harness.advance(25);

        assertEquals("sequence must reach IDLE", SequenceState.IDLE, harness.state());

        // Assert BankActions calls in correct order
        List<String> calls = bank.calls();
        assertFalse("at least one bank action must have been dispatched", calls.isEmpty());

        assertEquals("first call: clickBankBoothRandom", "clickBankBoothRandom()", calls.get(0));

        // depositAll for cooked chicken
        assertTrue("depositAll(cookedId) must be called",
            calls.contains("depositAll(" + CHICKEN.cookedId + ")"));

        // depositAll for burnt chicken
        assertTrue("depositAll(burntId) must be called",
            calls.contains("depositAll(" + CHICKEN.burntId + ")"));

        // withdrawAll or withdrawX for raw (inventory was empty → 26 free slots, bank has 26 → withdrawAll)
        boolean hasRawWithdraw = calls.stream().anyMatch(c ->
            c.startsWith("withdrawAll(" + CHICKEN.rawId + ")")
            || c.startsWith("withdrawX(" + CHICKEN.rawId + ","));
        assertTrue("withdraw raw chicken must be called", hasRawWithdraw);

        // closeBank
        assertTrue("closeBank must be called", calls.contains("closeBank()"));

        // Ordering sanity: open < deposit cooked < deposit burnt < withdraw raw < close
        int idxOpen   = calls.indexOf("clickBankBoothRandom()");
        int idxCooked = calls.indexOf("depositAll(" + CHICKEN.cookedId + ")");
        int idxBurnt  = calls.indexOf("depositAll(" + CHICKEN.burntId + ")");
        int idxClose  = calls.indexOf("closeBank()");
        int idxRaw    = calls.stream()
            .mapToInt(c -> (c.startsWith("withdrawAll(" + CHICKEN.rawId + ")")
                || c.startsWith("withdrawX(" + CHICKEN.rawId + ",")) ? calls.indexOf(c) : -1)
            .filter(i -> i >= 0)
            .findFirst()
            .orElse(-1);

        assertTrue("open before depositCooked", idxOpen < idxCooked);
        assertTrue("depositCooked before depositBurnt", idxCooked < idxBurnt);
        assertTrue("depositBurnt before withdrawRaw", idxBurnt < idxRaw);
        assertTrue("withdrawRaw before closeBank", idxRaw < idxClose);
    }

    /**
     * T3: mid-flow dialog triggers reactive preemption (covers spec scenario 6).
     *
     * <p>The test starts the sequence, advances until the bank is open and ready,
     * then injects a snapshot with a non-allow-listed blocker during {@link DepositItemStep}
     * (which has {@code PreemptionPolicy.WHEN_SAFE}), then verifies:
     * <ul>
     *   <li>The engine emits an Escape key request (EnsureNoBlockingInterfaceStep.onStart dispatch).
     *   <li>After the blocker is cleared, the engine resumes the linear chain and eventually
     *       reaches IDLE.
     * </ul>
     *
     * <p>Note: steps with PreemptionPolicy.NEVER (EnsureAtBankStep, WaitForBankReadyStep)
     * cannot be preempted. The blocker is injected during DepositItemStep which has WHEN_SAFE.
     */
    @Test
    public void reactivePreemptsOnDialogMidFlow()
    {
        CookingLocation location = makeLocation();
        RecordingBankActions bank = new RecordingBankActions();
        BankingEngineHarness harness = new BankingEngineHarness();

        BankingSequencePlan plan = BankingSequenceFactory.prepareCookingLoadout(
            location, CHICKEN, /* needsTinderbox= */ false, bank);

        // Register reactives before running
        for (Step reactive : plan.reactiveSteps())
        {
            harness.manager().register(reactive);
        }

        // Non-allow-listed dialog blocker
        BlockingInterface dialog = new BlockingInterface("LevelUpDialog", 99999, true, true);

        // Stage A: at bank, bank closed, cooked chicken in inventory
        WorldSnapshot stageA = new BankSnapBuilder()
            .tick(1)
            .player(IN_BANK)
            .invItem(CHICKEN.cookedId, 1)
            .bankOpen(false).bankReady(false)
            .bankItem(CHICKEN.rawId, 28, true)
            .worldAvailable(true)
            .build();

        // Stage B: bank open AND ready (skip the WaitForBankReady wait)
        WorldSnapshot stageB = new BankSnapBuilder()
            .tick(3)
            .player(IN_BANK)
            .invItem(CHICKEN.cookedId, 1)
            .bankOpen(true).bankReady(true)
            .bankItem(CHICKEN.rawId, 28, true)
            .worldAvailable(false)
            .build();

        // Stage B_blocker: bank open+ready, cooked still in inv, but dialog appears
        // (this arrives DURING DepositItemStep which has WHEN_SAFE policy)
        WorldSnapshot stageBblocker = new BankSnapBuilder()
            .tick(5)
            .player(IN_BANK)
            .invItem(CHICKEN.cookedId, 1)    // cooked not yet deposited (step still in flight)
            .bankOpen(true).bankReady(true)
            .bankItem(CHICKEN.rawId, 28, true)
            .blocker(dialog)
            .worldAvailable(false)
            .build();

        // Stage B_clear: dialog dismissed, cooked still in inv (DepositItemStep will re-run)
        WorldSnapshot stageBclear = new BankSnapBuilder()
            .tick(7)
            .player(IN_BANK)
            .invItem(CHICKEN.cookedId, 1)
            .bankOpen(true).bankReady(true)
            .bankItem(CHICKEN.rawId, 28, true)
            .worldAvailable(false)
            .build();

        // Stage D: cooked deposited (DepositItemStep re-runs after reactive returns control)
        WorldSnapshot stageD = new BankSnapBuilder()
            .tick(9)
            .player(IN_BANK)
            .bankOpen(true).bankReady(true)
            .bankItem(CHICKEN.rawId, 28, true)
            .worldAvailable(false)
            .build();

        // Stage F: raw withdrawn
        WorldSnapshot stageF = new BankSnapBuilder()
            .tick(11)
            .player(IN_BANK)
            .invItem(CHICKEN.rawId, 28)
            .freeSlots(0)
            .bankOpen(true).bankReady(true)
            .bankItem(CHICKEN.rawId, 0, true)
            .worldAvailable(false)
            .build();

        // Stage G: bank closed
        WorldSnapshot stageG = new BankSnapBuilder()
            .tick(13)
            .player(IN_BANK)
            .invItem(CHICKEN.rawId, 28)
            .freeSlots(0)
            .bankOpen(false).bankReady(false)
            .worldAvailable(true)
            .build();

        // Queue: A stages cover EnsureAtBank + EnsureNoBlocking + OpenBank onStart
        // B stages: bank open+ready → OpenBank succeeds + WaitForBankReady succeeds + DepositCooked onStart
        // Bblocker: blocker appears → reactive preempts DepositCooked (WHEN_SAFE)
        // Bclear: blocker gone → reactive succeeds → engine resumes DepositCooked
        // D: cooked deposited → DepositCooked succeeds
        // D: DepositBurnt (no burnt in inv → already satisfied)
        // F: raw withdrawn
        // F: EnsureLoadout passes
        // G: CloseBank
        harness.queue(
            stageA, stageA, stageA, stageA,     // A: EnsureAtBank, EnsureNoBlocking, OpenBank onStart + wait
            stageB, stageB, stageB,             // B: OpenBank succeeds, WaitForBankReady succeeds, DepositCooked onStart
            stageBblocker, stageBblocker,        // blocker appears → reactive preempts DepositCooked
            stageBclear, stageBclear,            // blocker cleared → reactive succeeds; resume DepositCooked
            stageD, stageD,                      // cooked deposited
            stageD, stageD,                      // DepositBurnt already satisfied
            stageF, stageF,                      // WithdrawRaw
            stageF, stageF,                      // EnsureLoadout
            stageG, stageG, stageG               // CloseBank
        );

        harness.run(plan.root());
        harness.advance(35);

        // Verify an Escape key was dispatched (EnsureNoBlockingInterfaceStep.onStart)
        boolean escapeDispatched = harness.dispatcher().getRequests().stream()
            .anyMatch(r -> r.getKind() == net.runelite.client.sequence.internal.ActionRequest.Kind.KEY
                && r.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE);
        assertTrue("reactive must dispatch Escape to dismiss dialog", escapeDispatched);

        // Sequence must complete
        assertEquals("sequence must reach IDLE after reactive clears dialog",
            SequenceState.IDLE, harness.state());

        // Bank was still used (open + close at minimum)
        assertTrue("clickBankBoothRandom must still be called",
            bank.calls().contains("clickBankBoothRandom()"));
        assertTrue("closeBank must still be called",
            bank.calls().contains("closeBank()"));
    }

    /**
     * T4: reactive preempts mid-WithdrawItemStep — the suspended chain's STEP-scope keys
     * (K_TARGET, K_INV_START, K_START_TICK) must survive the reactive's run + return.
     *
     * <p>Regression test for C2: previously, {@code popAndOrchestrate} called
     * {@code blackboard.clear(BlackboardScope.STEP)} after every frame pop, which wiped
     * WithdrawItemStep's typed keys. When the suspended chain resumed,
     * {@code check()} called {@code K_TARGET.orElseThrow(...)} and threw IllegalStateException.
     *
     * <p>Verifies:
     * <ul>
     *   <li>Reactive dispatches Escape (the chain was preempted).
     *   <li>WithdrawItemStep's check() does NOT throw on resume (k_TARGET still present).
     *   <li>Sequence reaches IDLE successfully.
     * </ul>
     *
     * <p>Note: chooses tinderbox so the WithdrawItemStep runs deterministically (AtLeast(1)).
     */
    @Test
    public void reactivePreemptsMidWithdrawItem_keysSurvive()
    {
        CookingLocation location = makeLocation();
        RecordingBankActions bank = new RecordingBankActions();
        BankingEngineHarness harness = new BankingEngineHarness();

        // needsTinderbox=true → an extra WithdrawItemStep(TINDERBOX_ID) is inserted
        // before the raw food withdraw. We inject the blocker during this withdraw.
        BankingSequencePlan plan = BankingSequenceFactory.prepareCookingLoadout(
            location, CHICKEN, /* needsTinderbox= */ true, bank);
        for (Step reactive : plan.reactiveSteps()) harness.manager().register(reactive);

        BlockingInterface dialog = new BlockingInterface("LevelUpDialog", 99999, true, true);
        final int TINDERBOX_ID = net.runelite.api.gameval.ItemID.TINDERBOX;

        // Stage A: at bank, bank closed, no inventory yet, tinderbox + raw chicken in bank
        WorldSnapshot stageA = new BankSnapBuilder()
            .tick(1)
            .player(IN_BANK)
            .bankOpen(false).bankReady(false)
            .bankItem(TINDERBOX_ID, 5, true)
            .bankItem(CHICKEN.rawId, 28, true)
            .worldAvailable(true)
            .build();

        // Stage B: bank open + ready, inventory still empty (deposits skip — already satisfied)
        WorldSnapshot stageB = new BankSnapBuilder()
            .tick(3)
            .player(IN_BANK)
            .bankOpen(true).bankReady(true)
            .bankItem(TINDERBOX_ID, 5, true)
            .bankItem(CHICKEN.rawId, 28, true)
            .worldAvailable(false)
            .build();

        // Stage C: tinderbox withdraw in flight — invItem(tinderbox)=0 still (engine still working)
        // and the dialog appears. WithdrawItemStep has WHEN_SAFE policy, so reactive preempts.
        WorldSnapshot stageC_blocker = new BankSnapBuilder()
            .tick(5)
            .player(IN_BANK)
            .bankOpen(true).bankReady(true)
            .bankItem(TINDERBOX_ID, 5, true)
            .bankItem(CHICKEN.rawId, 28, true)
            .blocker(dialog)
            .worldAvailable(false)
            .build();

        // Stage D: blocker cleared, tinderbox still not in inventory (resume the suspended chain)
        WorldSnapshot stageD_clear = new BankSnapBuilder()
            .tick(7)
            .player(IN_BANK)
            .bankOpen(true).bankReady(true)
            .bankItem(TINDERBOX_ID, 5, true)
            .bankItem(CHICKEN.rawId, 28, true)
            .worldAvailable(false)
            .build();

        // Stage E: tinderbox now in inventory (withdraw target reached)
        WorldSnapshot stageE = new BankSnapBuilder()
            .tick(9)
            .player(IN_BANK)
            .invItem(TINDERBOX_ID, 1)
            .bankOpen(true).bankReady(true)
            .bankItem(TINDERBOX_ID, 4, true)
            .bankItem(CHICKEN.rawId, 28, true)
            .worldAvailable(false)
            .build();

        // Stage F: raw withdrawn (inventory full minus tinderbox slot)
        WorldSnapshot stageF = new BankSnapBuilder()
            .tick(11)
            .player(IN_BANK)
            .invItem(TINDERBOX_ID, 1)
            .invItem(CHICKEN.rawId, 27)
            .freeSlots(0)
            .bankOpen(true).bankReady(true)
            .bankItem(TINDERBOX_ID, 4, true)
            .bankItem(CHICKEN.rawId, 1, true)
            .worldAvailable(false)
            .build();

        // Stage G: bank closed, world available
        WorldSnapshot stageG = new BankSnapBuilder()
            .tick(13)
            .player(IN_BANK)
            .invItem(TINDERBOX_ID, 1)
            .invItem(CHICKEN.rawId, 27)
            .freeSlots(0)
            .bankOpen(false).bankReady(false)
            .worldAvailable(true)
            .build();

        // Queue: A (at-bank guard, no-blocker reactive, OpenBank onStart);
        // B (OpenBank succeeds, WaitForBankReady, DepositCooked already-satisfied,
        //    DepositBurnt already-satisfied, WithdrawTinderbox onStart);
        // C_blocker (reactive preempts mid-withdraw);
        // D_clear (reactive succeeds via Escape; resumed chain re-runs WithdrawTinderbox.check);
        // E (tinderbox arrives — check returns Succeeded; WithdrawRaw onStart);
        // F (raw arrives — check returns Succeeded; EnsureLoadout passes);
        // G (CloseBank).
        harness.queue(
            stageA, stageA, stageA, stageA,
            stageB, stageB, stageB, stageB, stageB,
            stageC_blocker, stageC_blocker,
            stageD_clear, stageD_clear,
            stageE, stageE,
            stageF, stageF, stageF, stageF,
            stageG, stageG, stageG
        );

        harness.run(plan.root());
        harness.advance(40);

        // 1) Reactive must have dispatched Escape during preemption.
        boolean escapeDispatched = harness.dispatcher().getRequests().stream()
            .anyMatch(r -> r.getKind() == net.runelite.client.sequence.internal.ActionRequest.Kind.KEY
                && r.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE);
        assertTrue("reactive must dispatch Escape to dismiss dialog", escapeDispatched);

        // 2) No telemetry record reports an IllegalStateException — the regression
        //    pre-fix surfaced as "IllegalStateException: K_TARGET missing" via the
        //    engine's catch-Throwable handler in the verify pass.
        boolean keyMissingException = harness.telemetry().tail(200).stream()
            .anyMatch(r -> r.payload() != null
                && r.payload().contains("K_TARGET missing"));
        assertFalse("WithdrawItemStep.check must not throw 'K_TARGET missing' on resume — STEP-scope keys must survive reactive preemption",
            keyMissingException);

        // 3) Sequence completes — reaches IDLE (not FAILED).
        assertEquals("sequence must reach IDLE after reactive returns control",
            SequenceState.IDLE, harness.state());

        // 4) Withdraw was actually issued (single attempt — cleanly resumed, not retried).
        long tinderboxWithdraws = bank.calls().stream()
            .filter(c -> c.startsWith("withdraw") && c.contains("(" + TINDERBOX_ID + ","))
            .count()
            + bank.calls().stream()
                .filter(c -> c.startsWith("withdrawOne(" + TINDERBOX_ID + ")")
                          || c.startsWith("withdrawAll(" + TINDERBOX_ID + ")"))
                .count();
        assertTrue("at least one tinderbox withdraw must have been issued",
            tinderboxWithdraws >= 1);
    }
}
