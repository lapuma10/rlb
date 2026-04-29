package net.runelite.client.sequence.affordance;

import net.runelite.api.coords.WorldArea;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BlockReasonTest {

    @Test
    public void bankNotOpenIsDiagnosticReason() {
        DiagnosticReason r = new BlockReason.BankNotOpen();
        assertTrue(r instanceof DiagnosticReason);
        assertTrue(r instanceof BlockReason);
    }

    @Test
    public void bankNotReadyIsDiagnosticReason() {
        DiagnosticReason r = new BlockReason.BankNotReady();
        assertTrue(r instanceof DiagnosticReason);
    }

    @Test
    public void bankContentsUnknownIsDiagnosticReason() {
        DiagnosticReason r = new BlockReason.BankContentsUnknown();
        assertTrue(r instanceof DiagnosticReason);
    }

    @Test
    public void bankMissingItemCarriesFields() {
        BlockReason.BankMissingItem r = new BlockReason.BankMissingItem(554, "Fire rune", 14);
        assertTrue(r instanceof DiagnosticReason);
        assertTrue(r.itemId() == 554);
        assertTrue(r.name().equals("Fire rune"));
        assertTrue(r.requiredQty() == 14);
    }

    @Test
    public void inventoryFullCarriesFields() {
        BlockReason.InventoryFull r = new BlockReason.InventoryFull(3);
        assertTrue(r instanceof DiagnosticReason);
        assertTrue(r.neededFreeSlots() == 3);
    }

    @Test
    public void loadoutMismatchIsDiagnosticReason() {
        java.util.List<ItemDiff> diffs = java.util.List.of(new ItemDiff(995, "Coins", 0, 1000));
        BlockReason.LoadoutMismatch r = new BlockReason.LoadoutMismatch(diffs);
        assertTrue(r instanceof DiagnosticReason);
        assertTrue(r.diff().size() == 1);
    }

    @Test
    public void dialogOpenCarriesFields() {
        BlockReason.DialogOpen r = new BlockReason.DialogOpen(12345, "Level up!");
        assertTrue(r instanceof DiagnosticReason);
        assertTrue(r.rootWidgetId() == 12345);
        assertTrue(r.label().equals("Level up!"));
    }

    @Test
    public void menuOpenIsDiagnosticReason() {
        DiagnosticReason r = new BlockReason.MenuOpen();
        assertTrue(r instanceof DiagnosticReason);
    }

    @Test
    public void pinKeypadUpIsBlockReasonAndDiagnosticReason() {
        BlockReason r = new BlockReason.PinKeypadUp();
        assertTrue(r instanceof DiagnosticReason);
    }

    @Test
    public void notAtLocationCarriesWorldArea() {
        WorldArea area = new WorldArea(3180, 3430, 10, 10, 0);
        BlockReason.NotAtLocation r = new BlockReason.NotAtLocation(area);
        assertTrue(r instanceof DiagnosticReason);
        assertNotNull(r.required());
        assertTrue(r.required().equals(area));
    }

    @Test
    public void worldInteractionBlockedCarriesBlockingInterface() {
        BlockingInterface by = new BlockingInterface("level-up", 233, true, true);
        BlockReason.WorldInteractionBlocked r = new BlockReason.WorldInteractionBlocked(by);
        assertTrue(r instanceof DiagnosticReason);
        assertNotNull(r.by());
        assertTrue(r.by().canBeClosed());
        assertTrue(r.by().name().equals("level-up"));
    }

    @Test
    public void withdrawNoOpCarriesFields() {
        BlockReason.WithdrawNoOp r = new BlockReason.WithdrawNoOp(995, 5);
        assertTrue(r instanceof DiagnosticReason);
        assertTrue(r.itemId() == 995);
        assertTrue(r.ticks() == 5);
    }

    @Test
    public void sealedHierarchyHolds() {
        // All BlockReason subtypes are also DiagnosticReason
        DiagnosticReason[] reasons = {
            new BlockReason.BankNotOpen(),
            new BlockReason.BankNotReady(),
            new BlockReason.BankContentsUnknown(),
            new BlockReason.BankMissingItem(1, "x", 1),
            new BlockReason.InventoryFull(1),
            new BlockReason.LoadoutMismatch(java.util.List.of()),
            new BlockReason.DialogOpen(1, "x"),
            new BlockReason.MenuOpen(),
            new BlockReason.PinKeypadUp(),
            new BlockReason.NotAtLocation(new WorldArea(0, 0, 1, 1, 0)),
            new BlockReason.WorldInteractionBlocked(new BlockingInterface("x", 1, false, false)),
            new BlockReason.WithdrawNoOp(1, 1)
        };
        for (DiagnosticReason r : reasons) {
            assertTrue(r instanceof BlockReason);
        }
    }

    @Test
    public void diagnosticReasonBuiltInCasesWork() {
        DiagnosticReason loading = new DiagnosticReason.Loading();
        DiagnosticReason timedOut = new DiagnosticReason.ActionTimedOut("step1", 10);
        DiagnosticReason unknown = new DiagnosticReason.Unknown("something broke");
        assertTrue(loading instanceof DiagnosticReason.Loading);
        assertTrue(timedOut instanceof DiagnosticReason.ActionTimedOut);
        assertTrue(unknown instanceof DiagnosticReason.Unknown);
    }
}
