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
package net.runelite.client.sequence;

import javax.annotation.Nullable;
import net.runelite.client.sequence.affordance.DiagnosticReason;

public sealed interface Completion {
    record Running() implements Completion {}
    record Succeeded(String reason) implements Completion {}

    /**
     * Failed completion. {@code diagnostic} is the typed reason when the step
     * has one to share; legacy call sites that pass only a string have null.
     */
    record Failed(String reason, @Nullable DiagnosticReason diagnostic) implements Completion {
        /** Source-compat: legacy {@code new Failed(reason)} keeps null diagnostic. */
        public Failed(String reason) {
            this(reason, null);
        }
    }

    Running RUNNING = new Running();

    /** Build a {@link Failed} from a typed diagnostic; {@code reason} is the diagnostic's toString. */
    static Failed failed(DiagnosticReason r) {
        return new Failed(r.toString(), r);
    }
}
