package net.runelite.client.sequence.activities.ge;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldArea;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.activities.EnsureNoBlockingInterfaceStep;
import net.runelite.client.sequence.activities.banking.BankActions;
import net.runelite.client.sequence.activities.banking.OpenBankStep;
import net.runelite.client.sequence.activities.banking.WithdrawItemStep;
import net.runelite.client.sequence.composite.LinearSequence;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Phase B structural test: the bank-prep factory variants prepend the bank
 * sub-flow (OpenBank → WaitForBankReady → WithdrawItem → CloseBank) before
 * the GE Core sequence, and the reactive's allow-list now covers both
 * GE_ROOTS and BANK_ROOTS.
 */
public class GrandExchangeBankPrepFactoryTest {

    private static final WorldArea GE_AREA = new WorldArea(3140, 3470, 30, 30, 0);

    @Test
    public void buyWithBankPrepInsertsBankSubFlow() {
        BuyItemIntent intent = new BuyItemIntent(
            4151, "Abyssal whip", 1, new PricePolicy.Exact(1_500_000), OfferWaitPolicy.until(50));
        GrandExchangeSequencePlan plan = GrandExchangeSequenceFactory.buyWithBankPrep(
            intent, GE_AREA, new NoOpBankActions(), new RecordingGeActions());

        // Reactive set should be one EnsureNoBlockingInterfaceStep.
        assertEquals(1, plan.reactiveSteps().size());
        assertTrue(plan.reactiveSteps().get(0) instanceof EnsureNoBlockingInterfaceStep);

        // Root must be a LinearSequence containing the bank sub-flow steps.
        List<Step> children = collectLinearChildren(plan.root());
        assertTrue("root should contain OpenBankStep",
            children.stream().anyMatch(s -> s instanceof OpenBankStep));
        assertTrue("root should contain a WithdrawItemStep (for coins)",
            children.stream().anyMatch(s -> s instanceof WithdrawItemStep));
        assertTrue("root should contain OpenGrandExchangeStep",
            children.stream().anyMatch(s -> s instanceof OpenGrandExchangeStep));
        assertTrue("root should contain ConfirmOfferStep (deep child)",
            anyDeepChild(children, ConfirmOfferStep.class));
    }

    @Test
    public void sellWithBankPrepInsertsBankSubFlow() {
        SellItemIntent intent = new SellItemIntent(
            4151, "Abyssal whip", 1, new PricePolicy.Exact(1_500_000), OfferWaitPolicy.until(50));
        GrandExchangeSequencePlan plan = GrandExchangeSequenceFactory.sellWithBankPrep(
            intent, GE_AREA, new NoOpBankActions(), new RecordingGeActions());

        assertEquals(1, plan.reactiveSteps().size());
        List<Step> children = collectLinearChildren(plan.root());
        // Sell variant withdraws the sell item, not coins.
        assertTrue(children.stream().anyMatch(s -> s instanceof WithdrawItemStep));
        assertTrue(children.stream().anyMatch(s -> s instanceof OpenGrandExchangeStep));
    }

    private static List<Step> collectLinearChildren(Step root) {
        if (!(root instanceof LinearSequence ls)) {
            throw new AssertionError("root not a LinearSequence: " + root.getClass());
        }
        return new ArrayList<>(ls.getChildren());
    }

    /** True iff {@code targetClass} appears anywhere in the children tree. */
    private static boolean anyDeepChild(List<Step> children, Class<?> targetClass) {
        for (Step c : children) {
            if (targetClass.isInstance(c)) return true;
            if (c instanceof LinearSequence inner) {
                if (anyDeepChild(new ArrayList<>(inner.getChildren()), targetClass)) return true;
            }
        }
        return false;
    }

    /** Minimal BankActions stub — never called by the structural test. */
    private static final class NoOpBankActions implements BankActions {
        @Override public void clickBankBoothRandom()        {}
        @Override public void depositAll(int itemId)        {}
        @Override public void withdrawOne(int itemId)       {}
        @Override public void withdrawAll(int itemId)       {}
        @Override public void withdrawX(int itemId, int qty){}
        @Override public void closeBank()                   {}
    }
}
