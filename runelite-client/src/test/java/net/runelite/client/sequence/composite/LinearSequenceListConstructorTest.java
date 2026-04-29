package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class LinearSequenceListConstructorTest {

    @Test
    public void listConstructorPreservesOrderAndContents() {
        Step stepA = stubStep("A");
        Step stepB = stubStep("B");
        Step stepC = stubStep("C");

        LinearSequence seq = new LinearSequence("test", List.of(stepA, stepB, stepC));

        List<Step> children = seq.getChildren();
        assertEquals(3, children.size());
        assertSame(stepA, children.get(0));
        assertSame(stepB, children.get(1));
        assertSame(stepC, children.get(2));
    }

    @Test
    public void emptyListYieldsNoChildren() {
        LinearSequence seq = new LinearSequence("empty", List.of());
        assertTrue(seq.getChildren().isEmpty());
    }

    @Test
    public void nullListYieldsNoChildren() {
        LinearSequence seq = new LinearSequence("null-list", null);
        assertTrue(seq.getChildren().isEmpty());
    }

    @Test
    public void nameIsPreserved() {
        LinearSequence seq = new LinearSequence("my-seq", List.of(stubStep("x")));
        assertEquals("my-seq", seq.name());
    }

    private static Step stubStep(String name) {
        return new Step() {
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
