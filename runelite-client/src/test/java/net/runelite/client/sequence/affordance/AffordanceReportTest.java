package net.runelite.client.sequence.affordance;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AffordanceReportTest {

    @Test
    public void allAllowedBlockedIsEmpty() {
        AffordanceReport report = AffordanceReport.allAllowed();
        assertTrue(report.blocked().isEmpty());
    }

    @Test
    public void allAllowedAllKindsAreAllowed() {
        AffordanceReport report = AffordanceReport.allAllowed();
        for (ActionKind kind : ActionKind.values()) {
            assertTrue("Expected " + kind + " to be allowed", report.isAllowed(kind));
        }
    }

    @Test
    public void allAllowedEntryIsPresentForEachKind() {
        AffordanceReport report = AffordanceReport.allAllowed();
        for (ActionKind kind : ActionKind.values()) {
            assertTrue("Expected entry for " + kind, report.entry(kind).isPresent());
        }
    }

    @Test
    public void affordanceBlockedCarriesReasonAndRecoveries() {
        DiagnosticReason reason = new BlockReason.BankNotOpen();
        Affordance blocked = Affordance.blocked(
            ActionKind.USE_BANK_WIDGET,
            reason,
            ActionKind.OPEN_BANK_BOOTH
        );
        assertFalse(blocked.isAllowed());
        assertTrue(blocked.reason().isPresent());
        assertEquals(reason, blocked.reason().get());
        assertEquals(List.of(ActionKind.OPEN_BANK_BOOTH), blocked.suggestedRecoveries());
    }

    @Test
    public void affordanceAllowedIsAllowed() {
        Affordance allowed = Affordance.allowed(ActionKind.WALK);
        assertTrue(allowed.isAllowed());
        assertFalse(allowed.reason().isPresent());
        assertTrue(allowed.suggestedRecoveries().isEmpty());
    }

    @Test
    public void affordanceBlockedWithMultipleRecoveries() {
        DiagnosticReason reason = new BlockReason.DialogOpen(786432, "Level up");
        Affordance blocked = Affordance.blocked(
            ActionKind.INTERACT_WORLD,
            reason,
            ActionKind.DISMISS_DIALOG,
            ActionKind.CLOSE_BLOCKING_INTERFACE
        );
        assertEquals(2, blocked.suggestedRecoveries().size());
        assertTrue(blocked.suggestedRecoveries().contains(ActionKind.DISMISS_DIALOG));
        assertTrue(blocked.suggestedRecoveries().contains(ActionKind.CLOSE_BLOCKING_INTERFACE));
    }

    @Test
    public void reportBlockedListFiltersCorrectly() {
        List<Affordance> entries = List.of(
            Affordance.allowed(ActionKind.WALK),
            Affordance.blocked(ActionKind.USE_BANK_WIDGET, new BlockReason.BankNotOpen()),
            Affordance.allowed(ActionKind.INTERACT_INVENTORY)
        );
        AffordanceReport report = new AffordanceReport(entries);
        List<Affordance> blocked = report.blocked();
        assertEquals(1, blocked.size());
        assertEquals(ActionKind.USE_BANK_WIDGET, blocked.get(0).kind());
    }

    @Test
    public void reportIsAllowedReturnsFalseForBlockedKind() {
        Affordance blockedWalk = Affordance.blocked(ActionKind.WALK, new BlockReason.MenuOpen());
        AffordanceReport report = new AffordanceReport(List.of(blockedWalk));
        assertFalse(report.isAllowed(ActionKind.WALK));
    }

    @Test
    public void reportEntryForAbsentKindIsEmpty() {
        AffordanceReport report = new AffordanceReport(List.of());
        assertFalse(report.entry(ActionKind.WALK).isPresent());
    }
}
