/*
 * Copyright (c) 2026, RuneLite
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
package net.runelite.client.plugins.recorder.mining;

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Read-only view of the mining loop's collaborators, handed to a
 * {@link BankingStrategy}'s {@code empty()} so it can dispatch clicks and
 * read game state without holding references to the loop itself.
 *
 * <p>All scene reads through this context must be wrapped in
 * {@link HumanizedInputDispatcher#runOnClient(java.util.function.Supplier)}
 * (or equivalent) — the strategy runs on the loop's daemon worker thread,
 * not the client thread.
 */
public final class MiningLoopContext
{
    private final HumanizedInputDispatcher dispatcher;
    private final Client client;
    private final ClientThread clientThread;

    public MiningLoopContext(HumanizedInputDispatcher dispatcher, Client client,
                             ClientThread clientThread)
    {
        this.dispatcher = dispatcher;
        this.client = client;
        this.clientThread = clientThread;
    }

    public HumanizedInputDispatcher dispatcher() { return dispatcher; }
    public Client client() { return client; }
    public ClientThread clientThread() { return clientThread; }
}
