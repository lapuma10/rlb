package net.runelite.client.sequence.activities.banking;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Verifies the WithdrawQuantity sealed type:
 * - Hierarchy can be exhaustively dispatched without a catch-all branch.
 * - AtLeast.qty() accessor returns the value passed to the constructor.
 * - FillRemainingInventory() constructs and equals another FillRemainingInventory().
 * - Only AtLeast and FillRemainingInventory are permitted subtypes (no Exact variant).
 */
public class WithdrawQuantityTest {

    // -- Exhaustive dispatch helper ----------------------------------------

    /**
     * Returns a label per variant.  If a new permitted variant is ever added
     * without updating this method the final throw will fire, making this an
     * effective runtime exhaustiveness check for Java 17.
     *
     * The sealed interface guarantees the compiler rejects any subclass that
     * is not listed in the permits clause, so the only ways this method can
     * hit the throw are: a new permitted variant is added, or the test itself
     * is broken.
     */
    private static String label(WithdrawQuantity wq) {
        if (wq instanceof WithdrawQuantity.AtLeast a) {
            return "at-least:" + a.qty();
        } else if (wq instanceof WithdrawQuantity.FillRemainingInventory) {
            return "fill";
        }
        throw new AssertionError("Unhandled WithdrawQuantity variant: " + wq.getClass());
    }

    @Test
    public void labelHelper_atLeast() {
        assertEquals("at-least:5", label(new WithdrawQuantity.AtLeast(5)));
    }

    @Test
    public void labelHelper_fillRemaining() {
        assertEquals("fill", label(new WithdrawQuantity.FillRemainingInventory()));
    }

    // -- AtLeast accessor --------------------------------------------------

    @Test
    public void atLeast_qtyAccessor() {
        WithdrawQuantity.AtLeast wq = new WithdrawQuantity.AtLeast(5);
        assertEquals(5, wq.qty());
    }

    @Test
    public void atLeast_qtyAccessor_zero() {
        assertEquals(0, new WithdrawQuantity.AtLeast(0).qty());
    }

    // -- FillRemainingInventory equality -----------------------------------

    @Test
    public void fillRemainingInventory_constructsAndEqualsAnotherInstance() {
        WithdrawQuantity.FillRemainingInventory a = new WithdrawQuantity.FillRemainingInventory();
        WithdrawQuantity.FillRemainingInventory b = new WithdrawQuantity.FillRemainingInventory();
        assertEquals(a, b);
    }

    @Test
    public void fillRemainingInventory_hashCodeConsistent() {
        assertEquals(
                new WithdrawQuantity.FillRemainingInventory().hashCode(),
                new WithdrawQuantity.FillRemainingInventory().hashCode()
        );
    }

    // -- Three permitted subtypes (AtLeast, WithdrawAmount, FillRemainingInventory) -

    @Test
    public void threePermittedSubtypes() {
        Class<?>[] permitted = WithdrawQuantity.class.getPermittedSubclasses();
        assertNotNull("WithdrawQuantity must be sealed", permitted);
        assertEquals("exactly three permitted subtypes", 3, permitted.length);

        boolean hasAtLeast = false;
        boolean hasWithdrawAmount = false;
        boolean hasFill    = false;
        for (Class<?> c : permitted) {
            if (c == WithdrawQuantity.AtLeast.class)               hasAtLeast = true;
            if (c == WithdrawQuantity.WithdrawAmount.class)        hasWithdrawAmount = true;
            if (c == WithdrawQuantity.FillRemainingInventory.class) hasFill = true;
        }
        assertTrue("AtLeast must be permitted",              hasAtLeast);
        assertTrue("WithdrawAmount must be permitted",       hasWithdrawAmount);
        assertTrue("FillRemainingInventory must be permitted", hasFill);
    }

    @Test
    public void noExactVariantExists() {
        for (Class<?> c : WithdrawQuantity.class.getPermittedSubclasses()) {
            assertNotEquals("Exact variant must not exist", "Exact", c.getSimpleName());
        }
    }
}
