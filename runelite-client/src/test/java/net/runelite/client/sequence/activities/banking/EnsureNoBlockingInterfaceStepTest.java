package net.runelite.client.sequence.activities.banking;

import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.BlockingInterface;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;

import java.awt.event.KeyEvent;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests for {@link EnsureNoBlockingInterfaceStep}.
 * 8b: 3 canStart tests + 1 dispatch sanity test.
 *
 * <p>canStart logic:
 * - no blocker → canStart=false (step does not activate)
 * - blocker IN allow-list (bank root) → canStart=false
 * - blocker NOT in allow-list → canStart=true
 *
 * Plus: when canStart=true and onStart runs, an Escape key request is enqueued.
 */
public class EnsureNoBlockingInterfaceStepTest {

    private static final int BANK_ROOT_WIDGET_ID   = 786;
    private static final int DIALOG_WIDGET_ID      = 233;
    private static final Set<Integer> ALLOWED_ROOTS = Set.of(BANK_ROOT_WIDGET_ID);

    @Test
    public void noBlocker_canStartIsFalse() {
        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .build();  // no blocker

        EnsureNoBlockingInterfaceStep step = new EnsureNoBlockingInterfaceStep(ALLOWED_ROOTS);
        assertFalse("No blocker → canStart must be false",
            step.canStart(snap, null));
    }

    @Test
    public void allowListedBlocker_canStartIsFalse() {
        BlockingInterface bankBlocker = new BlockingInterface("Bank", BANK_ROOT_WIDGET_ID, true, true);
        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .blocker(bankBlocker)
            .build();

        EnsureNoBlockingInterfaceStep step = new EnsureNoBlockingInterfaceStep(ALLOWED_ROOTS);
        assertFalse("Bank (allow-listed) blocker → canStart must be false",
            step.canStart(snap, null));
    }

    @Test
    public void nonAllowListedBlocker_canStartIsTrue() {
        BlockingInterface dialog = new BlockingInterface("LevelUpDialog", DIALOG_WIDGET_ID, true, true);
        WorldSnapshot snap = new BankSnapBuilder()
            .tick(1)
            .blocker(dialog)
            .build();

        EnsureNoBlockingInterfaceStep step = new EnsureNoBlockingInterfaceStep(ALLOWED_ROOTS);
        assertTrue("Non-allow-listed blocker → canStart must be true",
            step.canStart(snap, null));
    }

    @Test
    public void onStart_enqueuedEscapeKeyPress() {
        // Verify that when the step runs it dispatches an Escape key request
        BankingEngineHarness harness = new BankingEngineHarness();

        BlockingInterface dialog = new BlockingInterface("LevelUpDialog", DIALOG_WIDGET_ID, true, true);

        // Tick 1: blocker present → step runs, dispatches Escape
        // Tick 2: blocker cleared → step succeeds
        WorldSnapshot withBlocker  = new BankSnapBuilder().tick(1).blocker(dialog).build();
        WorldSnapshot withoutBlocker = new BankSnapBuilder().tick(2).build();
        harness.queue(withBlocker, withBlocker, withoutBlocker, withoutBlocker);

        harness.run(new EnsureNoBlockingInterfaceStep(ALLOWED_ROOTS));
        harness.advance(3);

        // The step must have enqueued an Escape KEY action
        boolean foundEscape = harness.dispatcher().getRequests().stream()
            .anyMatch(r -> r.getKind() == ActionRequest.Kind.KEY
                && r.getKeyCode() == KeyEvent.VK_ESCAPE);
        assertTrue("Expected Escape key request to be enqueued", foundEscape);
        assertEquals(SequenceState.IDLE, harness.state());
    }
}
