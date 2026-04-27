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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ScopedBlackboard implements Blackboard {
    private final EnumMap<BlackboardScope, Map<BlackboardKey<?>, Object>> stores =
        new EnumMap<>(BlackboardScope.class);
    private final BlackboardScope viewScope;

    public ScopedBlackboard() {
        this(BlackboardScope.RUN);
        for (BlackboardScope s : BlackboardScope.values()) {
            stores.put(s, new HashMap<>());
        }
    }

    private ScopedBlackboard(ScopedBlackboard parent, BlackboardScope viewScope) {
        this.stores.putAll(parent.stores);
        this.viewScope = viewScope;
    }

    private ScopedBlackboard(BlackboardScope viewScope) {
        this.viewScope = viewScope;
    }

    @Override
    public <T> void put(BlackboardKey<T> key, T value) {
        stores.get(viewScope).put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(BlackboardKey<T> key) {
        Object v = stores.get(viewScope).get(key);
        return v == null ? Optional.empty() : Optional.of((T) v);
    }

    @Override
    public <T> void remove(BlackboardKey<T> key) {
        stores.get(viewScope).remove(key);
    }

    @Override
    public Blackboard scope(BlackboardScope scope) {
        return new ScopedBlackboard(this, scope);
    }

    @Override
    public void clear(BlackboardScope scope) {
        stores.get(scope).clear();
    }
}
