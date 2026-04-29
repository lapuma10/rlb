package net.runelite.client.sequence.activities.ge;

import java.util.List;
import net.runelite.client.sequence.Step;

/**
 * What {@link GrandExchangeSequenceFactory} returns: the linear root of the
 * GE Core sequence plus the reactive steps the caller registers via
 * {@code SequenceManager.registerReactive(...)}.
 */
public record GrandExchangeSequencePlan(Step root, List<Step> reactiveSteps) {
    public GrandExchangeSequencePlan {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        if (reactiveSteps == null) throw new IllegalArgumentException("reactiveSteps must not be null");
        reactiveSteps = List.copyOf(reactiveSteps);
    }
}
