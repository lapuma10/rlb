package net.runelite.client.sequence.activities.banking;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.recorder.cook.CookingFood;
import net.runelite.client.plugins.recorder.cook.CookingLocation;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.composite.LinearSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Assembles a {@link BankingSequencePlan} for common banking workflows.
 *
 * <p>The returned plan's {@code root} is a {@link LinearSequence} that walks through
 * the canonical open → deposit → withdraw → verify → close pipeline.
 * The plan's {@code reactiveSteps} list contains an
 * {@link EnsureNoBlockingInterfaceStep} that will preempt any in-flight linear
 * step when a non-allow-listed dialog appears mid-flow.
 */
public final class BankingSequenceFactory
{
    private BankingSequenceFactory() {}

    /**
     * Root widget IDs that are NOT treated as blocking interfaces.
     * The bank widget itself ({@code Bankmain.UNIVERSE}) is allow-listed so that
     * the reactive does not dismiss the bank while deposit/withdraw steps are running.
     */
    private static final Set<Integer> BANK_ROOTS =
        Set.of(InterfaceID.Bankmain.UNIVERSE);

    /**
     * Root widget IDs that OpenBankStep treats as closable-and-waitable.
     * Empty for the proof-of-concept; production wiring would identify safe
     * roots that can be automatically dismissed before the bank is opened.
     */
    private static final Set<Integer> OPEN_BANK_CLOSABLE_ALLOWLIST = Set.of();

    /** Standard tinderbox item ID ({@code ItemID.TINDERBOX} = 590). */
    private static final int TINDERBOX_ID = ItemID.TINDERBOX;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a complete banking plan for the cooking loadout workflow.
     *
     * <p>Linear root children (in order):
     * <ol>
     *   <li>{@link EnsureAtBankStep} — guard: player must be inside bank area</li>
     *   <li>{@link EnsureNoBlockingInterfaceStep} — safety: clear any blocker before opening bank</li>
     *   <li>{@link OpenBankStep} — click booth and wait for bank widget</li>
     *   <li>{@link WaitForBankReadyStep} — wait for bank container to load</li>
     *   <li>{@link DepositItemStep} for {@code food.cookedId} — deposit cooked food</li>
     *   <li>{@link DepositItemStep} for {@code food.burntId} — deposit burnt food</li>
     *   <li>(if {@code needsTinderbox}) {@link WithdrawItemStep} for tinderbox (AtLeast(1))</li>
     *   <li>{@link WithdrawItemStep} for {@code food.rawId} (FillRemainingInventory)</li>
     *   <li>{@link EnsureInventoryMatchesLoadoutStep} — verify loadout</li>
     *   <li>{@link CloseBankStep} — close bank and confirm world available</li>
     * </ol>
     *
     * <p>Reactive list: one {@link EnsureNoBlockingInterfaceStep} registered at
     * high priority so it can preempt withdraw/deposit steps if a dialog appears.
     *
     * @param location       cooking location — supplies bank area and booth IDs
     * @param food           food entry — supplies rawId, cookedId, burntId
     * @param needsTinderbox if true, a tinderbox withdraw step is inserted before the raw food withdraw
     * @param bank           banking action dispatcher
     * @return assembled {@link BankingSequencePlan}
     */
    public static BankingSequencePlan prepareCookingLoadout(
        CookingLocation location,
        CookingFood.Entry food,
        boolean needsTinderbox,
        BankActions bank)
    {
        // ── Build loadout for verification step ───────────────────────────────
        List<Loadout.Slot> slots = new ArrayList<>();
        if (needsTinderbox)
        {
            // exact=false: at least 1 tinderbox; allow extras since the player
            // may legitimately pick another up elsewhere. Goal is "have a
            // tinderbox", not "have exactly one".
            slots.add(new Loadout.Slot(TINDERBOX_ID, 1, false));
        }
        // exact=false: at least 1 raw food item (allows partial final trip)
        slots.add(new Loadout.Slot(food.rawId, 1, false));
        Loadout loadout = new Loadout(slots);

        // ── Build linear root chain ──────────────────────────────────────────
        List<Step> children = new ArrayList<>();

        // 1. Guard: player must be in bank area
        children.add(new EnsureAtBankStep(location.bankArea()));

        // 2. Safety: dismiss any non-allow-listed blocker before opening bank
        children.add(new EnsureNoBlockingInterfaceStep(BANK_ROOTS));

        // 3. Click bank booth to open bank
        children.add(new OpenBankStep(OPEN_BANK_CLOSABLE_ALLOWLIST, bank));

        // 4. Wait for bank container to load
        children.add(new WaitForBankReadyStep());

        // 5. Deposit cooked food
        children.add(new DepositItemStep(food.cookedId, bank));

        // 6. Deposit burnt food
        children.add(new DepositItemStep(food.burntId, bank));

        // 7. (optional) Withdraw tinderbox
        if (needsTinderbox)
        {
            children.add(new WithdrawItemStep(TINDERBOX_ID, new WithdrawQuantity.AtLeast(1), bank));
        }

        // 8. Withdraw raw food (fill remaining inventory slots)
        children.add(new WithdrawItemStep(food.rawId, new WithdrawQuantity.FillRemainingInventory(), bank));

        // 9. Verify inventory matches loadout
        children.add(new EnsureInventoryMatchesLoadoutStep(loadout));

        // 10. Close bank and confirm world interaction available
        children.add(new CloseBankStep(bank));

        LinearSequence root = new LinearSequence("BankForCooking[" + food.label + "]", children);

        // ── Build reactive list ───────────────────────────────────────────────
        // One high-priority reactive that preempts the linear chain when a
        // non-allow-listed blocking interface appears mid-flow.
        EnsureNoBlockingInterfaceStep reactive = new EnsureNoBlockingInterfaceStep(BANK_ROOTS);
        List<Step> reactives = List.of(reactive);

        return new BankingSequencePlan(root, reactives);
    }
}
