package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SequencePlanBuilderImplTest
{
    @Test
    public void thenAppendsInInsertionOrder()
    {
        Step a = stubStep("A");
        Step b = stubStep("B");
        Step c = stubStep("C");

        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("plan-x");
        builder.then(a).then(b).then(c);

        LinearSequence root = (LinearSequence) builder.root();
        List<Step> children = root.getChildren();
        assertEquals(3, children.size());
        assertSame(a, children.get(0));
        assertSame(b, children.get(1));
        assertSame(c, children.get(2));
    }

    @Test
    public void thenReturnsSameBuilderForFluentChaining()
    {
        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("plan-x");
        SequencePlanBuilder afterThen = builder.then(stubStep("A"));
        assertSame(builder, afterThen);
    }

    @Test
    public void rootReturnsLinearSequenceWithPreservedName()
    {
        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("my-plan");
        builder.then(stubStep("only"));

        Step root = builder.root();
        assertTrue("root() must return a LinearSequence", root instanceof LinearSequence);
        assertEquals("my-plan", ((LinearSequence) root).name());
    }

    @Test
    public void zeroStepPlanReturnsEmptyLinearSequence()
    {
        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("empty-plan");

        LinearSequence root = (LinearSequence) builder.root();
        assertEquals("empty-plan", root.name());
        assertTrue("empty builder must produce an empty LinearSequence", root.getChildren().isEmpty());
    }

    @Test(expected = NullPointerException.class)
    public void thenNullStepFailsLoud()
    {
        new SequencePlanBuilderImpl("plan-x").then(null);
    }

    @Test
    public void thenNullStepMessageMentionsStep()
    {
        try
        {
            new SequencePlanBuilderImpl("plan-x").then(null);
            fail("expected NullPointerException");
        }
        catch (NullPointerException e)
        {
            assertNotNull("NPE must carry a message", e.getMessage());
            assertTrue("message must mention 'step', got: " + e.getMessage(),
                e.getMessage().toLowerCase().contains("step"));
        }
    }

    @Test(expected = NullPointerException.class)
    public void nullNameFailsLoud()
    {
        new SequencePlanBuilderImpl(null);
    }

    @Test
    public void blankNameFailsLoud()
    {
        String[] blanks = { "", "   ", "\t", "\n" };
        for (String blank : blanks)
        {
            try
            {
                new SequencePlanBuilderImpl(blank);
                fail("expected IllegalArgumentException for blank name: [" + blank + "]");
            }
            catch (IllegalArgumentException expected)
            {
                assertTrue("message must mention 'name', got: " + expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("name"));
            }
        }
    }

    @Test
    public void nameIsPreservedExactlyAsGiven()
    {
        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("  edges  ");
        // Builder preserves the caller's name verbatim — no trim, no rewrite.
        // Validation is "is the name blank after trim", not "rewrite the name".
        LinearSequence root = (LinearSequence) builder.root();
        assertEquals("  edges  ", root.name());
    }

    @Test
    public void rootSnapshotsDoesNotLeakLaterThenCalls()
    {
        Step a = stubStep("A");
        Step b = stubStep("B");

        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("plan-x");
        builder.then(a);
        LinearSequence root1 = (LinearSequence) builder.root();

        // Mutate the builder after root1 was taken.
        builder.then(b);

        // root1 must NOT see stepB.
        assertEquals("root1 children size", 1, root1.getChildren().size());
        assertSame(a, root1.getChildren().get(0));

        // A fresh root2 SHOULD include both steps.
        LinearSequence root2 = (LinearSequence) builder.root();
        assertEquals(2, root2.getChildren().size());
        assertSame(a, root2.getChildren().get(0));
        assertSame(b, root2.getChildren().get(1));
    }

    @Test
    public void multipleRootCallsReturnIndependentInstances()
    {
        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("plan-x");
        builder.then(stubStep("A"));

        Step r1 = builder.root();
        Step r2 = builder.root();

        assertNotSame("each root() call must produce a fresh LinearSequence instance", r1, r2);
    }

    @Test
    public void sameStepInstanceCannotBeAddedTwice()
    {
        // Engine Steps are stateful and typically single-use (WalkStepBase
        // worker state, ArtemisActionStep lifecycle/failure state, action
        // Steps' pinned dispatcher/outcome state). Adding the same
        // instance to two plan slots would re-enter onStart on already-
        // spent state — a subtle correctness bug. Reject loud.
        Step a = stubStep("A");
        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("plan-x");
        builder.then(a);

        try
        {
            builder.then(a);
            fail("expected IllegalArgumentException — same Step instance added twice");
        }
        catch (IllegalArgumentException expected)
        {
            assertNotNull("IAE must carry a message", expected.getMessage());
            assertTrue("message must mention 'step' or 'instance', got: " + expected.getMessage(),
                expected.getMessage().toLowerCase().contains("step"));
        }

        // The failed then(...) must NOT have appended the duplicate; the
        // builder remains in the post-first-then state.
        LinearSequence root = (LinearSequence) builder.root();
        assertEquals("rejected then(...) must not append", 1, root.getChildren().size());
        assertSame(a, root.getChildren().get(0));
    }

    @Test
    public void differentInstancesAreAllowed()
    {
        // Identity rejection — not equals-rejection. Two separately
        // constructed Steps with the same logical action / target are
        // valid (each carries its own per-instance state). Verified
        // here so the rejection rule doesn't accidentally widen to
        // "same name" / "same hash".
        Step a1 = stubStep("walkA");
        Step a2 = stubStep("walkA");   // same logical name, fresh instance
        SequencePlanBuilderImpl builder = new SequencePlanBuilderImpl("plan-x");
        builder.then(a1).then(a2);

        LinearSequence root = (LinearSequence) builder.root();
        assertEquals(2, root.getChildren().size());
        assertSame(a1, root.getChildren().get(0));
        assertSame(a2, root.getChildren().get(1));
    }

    private static Step stubStep(String name)
    {
        return new Step()
        {
            public String name() { return name; }
            public int priority() { return 50; }
            public int timeoutTicks() { return 50; }
            public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.ALWAYS; }
            public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
            public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
            public void onStart(StepContext c) {}
            public void onEvent(Object e, StepContext c) {}
            public void tick(StepContext c) {}
            public Completion check(WorldSnapshot s, Blackboard b) { return new Completion.Succeeded("done"); }
            public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) { return new Recovery.Abort(""); }
        };
    }
}
