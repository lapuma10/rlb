/*
 * Copyright (c) 2024, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.sequence.internal;

import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.blackboard.SequenceBlackboardKeys;
import net.runelite.client.sequence.telemetry.Telemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import javax.annotation.Nullable;
import java.util.Collection;

public final class PriorityPlanner implements Planner {

    /** Optional telemetry sink for canStart-rejection records. Set via
     *  {@link #setTelemetry(Telemetry)}; null means no recording. */
    @Nullable private Telemetry telemetry;

    public void setTelemetry(@Nullable Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override @Nullable
    public Step select(WorldSnapshot state, Blackboard bb, Collection<Step> candidates) {
        Step best = null;
        for (Step s : candidates) {
            if (!s.canStart(state, bb)) {
                if (telemetry != null) {
                    DiagnosticReason r = bb.scope(BlackboardScope.STEP)
                        .get(SequenceBlackboardKeys.LAST_BLOCK_REASON).orElse(null);
                    String payload = "canStart=false"
                        + (r != null ? " reason=" + r : "");
                    int tick = state == null ? 0 : state.tick();
                    telemetry.record(new TelemetryRecord(
                        tick, 0, s.name(), TelemetryRecord.Event.CHECK, payload));
                }
                continue;
            }
            if (best == null || s.priority() > best.priority()) best = s;
        }
        return best;
    }
}
