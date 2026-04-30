package net.runelite.client.sequence.activities.ge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.activities.EnsureNoBlockingInterfaceStep;
import net.runelite.client.sequence.activities.banking.BankActions;
import net.runelite.client.sequence.activities.banking.CloseBankStep;
import net.runelite.client.sequence.activities.banking.OpenBankStep;
import net.runelite.client.sequence.activities.banking.WaitForBankReadyStep;
import net.runelite.client.sequence.activities.banking.WithdrawItemStep;
import net.runelite.client.sequence.activities.banking.WithdrawQuantity;
import net.runelite.client.sequence.composite.LinearSequence;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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
        assertTrue("root should contain WaitForBankReadyStep",
            children.stream().anyMatch(s -> s instanceof WaitForBankReadyStep));
        assertTrue("root should contain a WithdrawItemStep (for coins)",
            children.stream().anyMatch(s -> s instanceof WithdrawItemStep));
        assertTrue("root should contain CloseBankStep",
            children.stream().anyMatch(s -> s instanceof CloseBankStep));
        assertTrue("root should contain OpenGrandExchangeStep",
            children.stream().anyMatch(s -> s instanceof OpenGrandExchangeStep));
        assertTrue("root should contain ConfirmOfferStep (deep child)",
            anyDeepChild(children, ConfirmOfferStep.class));

        // Bank sub-flow ordering: OpenBank → WaitForBankReady → Withdraw → CloseBank → ...
        int openBankIdx     = indexOf(children, OpenBankStep.class);
        int waitReadyIdx    = indexOf(children, WaitForBankReadyStep.class);
        int withdrawIdx     = indexOf(children, WithdrawItemStep.class);
        int closeIdx        = indexOf(children, CloseBankStep.class);
        int openGEIdx       = indexOf(children, OpenGrandExchangeStep.class);
        assertTrue("OpenBank must come before WaitForBankReady", openBankIdx < waitReadyIdx);
        assertTrue("WaitForBankReady must come before WithdrawItem", waitReadyIdx < withdrawIdx);
        assertTrue("WithdrawItem must come before CloseBank", withdrawIdx < closeIdx);
        assertTrue("Bank sub-flow (CloseBank) must precede GE Core (OpenGrandExchange)",
            closeIdx < openGEIdx);

        // The buy-side WithdrawItemStep must target the COINS item id (995),
        // and the quantity must equal totalCost = quantity * priceEach (1 * 1_500_000 = 1_500_000).
        WithdrawItemStep w = (WithdrawItemStep) children.stream()
            .filter(s -> s instanceof WithdrawItemStep).findFirst().orElseThrow();
        assertEquals("buy-side withdraw must target COINS (item id 995)", 995, readField(w, "itemId"));
        Object desired = readFieldObj(w, "desired");
        assertTrue("buy-side withdraw must use AtLeast(totalCost)",
            desired instanceof WithdrawQuantity.AtLeast);
        assertEquals(
            1_500_000,
            ((WithdrawQuantity.AtLeast) desired).qty());

        // Reactive allow-list must contain BOTH bank root and GE roots so the
        // reactive does not try to dismiss either while the active sub-flow
        // depends on it being open.
        EnsureNoBlockingInterfaceStep reactive =
            (EnsureNoBlockingInterfaceStep) plan.reactiveSteps().get(0);
        java.util.Set<Integer> allow = readAllowList(reactive);
        assertTrue("reactive allow-list must contain Bankmain.UNIVERSE",
            allow.contains(InterfaceID.Bankmain.UNIVERSE));
        assertTrue("reactive allow-list must contain GeOffers.UNIVERSE",
            allow.contains(InterfaceID.GeOffers.UNIVERSE));
        assertTrue("reactive allow-list must contain GeCollect.UNIVERSE",
            allow.contains(InterfaceID.GeCollect.UNIVERSE));
    }

    @Test
    public void sellWithBankPrepInsertsBankSubFlow() {
        SellItemIntent intent = new SellItemIntent(
            4151, "Abyssal whip", 1, new PricePolicy.Exact(1_500_000), OfferWaitPolicy.until(50));
        GrandExchangeSequencePlan plan = GrandExchangeSequenceFactory.sellWithBankPrep(
            intent, GE_AREA, new NoOpBankActions(), new RecordingGeActions());

        assertEquals(1, plan.reactiveSteps().size());
        List<Step> children = collectLinearChildren(plan.root());
        assertTrue(children.stream().anyMatch(s -> s instanceof WithdrawItemStep));
        assertTrue(children.stream().anyMatch(s -> s instanceof OpenGrandExchangeStep));

        // Sell variant withdraws the sell item, not coins. Quantity equals intent.quantity().
        WithdrawItemStep w = (WithdrawItemStep) children.stream()
            .filter(s -> s instanceof WithdrawItemStep).findFirst().orElseThrow();
        assertEquals("sell-side withdraw must target the sell item id",
            4151, readField(w, "itemId"));
        Object desired = readFieldObj(w, "desired");
        assertTrue(desired instanceof WithdrawQuantity.AtLeast);
        assertEquals(1, ((WithdrawQuantity.AtLeast) desired).qty());

        // Reactive allow-list parity check.
        EnsureNoBlockingInterfaceStep reactive =
            (EnsureNoBlockingInterfaceStep) plan.reactiveSteps().get(0);
        java.util.Set<Integer> allow = readAllowList(reactive);
        assertTrue(allow.contains(InterfaceID.Bankmain.UNIVERSE));
        assertTrue(allow.contains(InterfaceID.GeOffers.UNIVERSE));
        assertTrue(allow.contains(InterfaceID.GeCollect.UNIVERSE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void buyWithBankPrep_throwsOnIntegerOverflow() {
        // totalCost = 2 * Integer.MAX_VALUE > Integer.MAX_VALUE → overflow guard fires.
        BuyItemIntent intent = new BuyItemIntent(
            4151, "Abyssal whip", 2,
            new PricePolicy.Exact(Integer.MAX_VALUE),
            OfferWaitPolicy.until(50));
        GrandExchangeSequenceFactory.buyWithBankPrep(
            intent, GE_AREA, new NoOpBankActions(), new RecordingGeActions());
    }

    private static int indexOf(List<Step> children, Class<?> type) {
        for (int i = 0; i < children.size(); i++) {
            if (type.isInstance(children.get(i))) return i;
        }
        return -1;
    }

    private static int readField(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (int) f.get(o);
        } catch (Exception e) {
            throw new AssertionError("read field " + name + " failed", e);
        }
    }

    private static Object readFieldObj(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Exception e) {
            throw new AssertionError("read field " + name + " failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<Integer> readAllowList(EnsureNoBlockingInterfaceStep s) {
        try {
            Field f = EnsureNoBlockingInterfaceStep.class.getDeclaredField("allowList");
            f.setAccessible(true);
            Object v = f.get(s);
            assertNotNull("allowList must not be null", v);
            return (java.util.Set<Integer>) v;
        } catch (Exception e) {
            throw new AssertionError("read allowList failed", e);
        }
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
        @Override public void withdrawAsNoteX(int itemId, int qty){}
        @Override public void closeBank()                   {}
    }
}
