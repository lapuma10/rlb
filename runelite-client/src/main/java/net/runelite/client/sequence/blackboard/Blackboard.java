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
package net.runelite.client.sequence.blackboard;

import java.util.Map;
import java.util.Optional;

public interface Blackboard {
    <T> void put(BlackboardKey<T> key, T value);
    <T> Optional<T> get(BlackboardKey<T> key);
    <T> void remove(BlackboardKey<T> key);
    Blackboard scope(BlackboardScope scope);
    void clear(BlackboardScope scope);

    /**
     * Snapshot the contents of {@code scope} into an opaque map. Callers must
     * treat the returned map as opaque — keys are typed internally and the
     * map is intended to be passed back to {@link #restore(BlackboardScope, Map)}.
     *
     * <p>Implementations should return a defensive copy so subsequent
     * {@link #put}/{@link #remove}/{@link #clear} calls do not mutate it.
     */
    Map<BlackboardKey<?>, Object> snapshot(BlackboardScope scope);

    /**
     * Replace the contents of {@code scope} with the given map (which must be
     * the result of an earlier {@link #snapshot(BlackboardScope)} call on the
     * same {@link Blackboard} instance — passing a foreign map is undefined).
     *
     * <p>Existing keys in the scope are cleared before the snapshot is applied.
     */
    void restore(BlackboardScope scope, Map<BlackboardKey<?>, Object> snapshot);
}
