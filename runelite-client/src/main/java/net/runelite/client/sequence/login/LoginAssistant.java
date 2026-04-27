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
package net.runelite.client.sequence.login;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Thin facade that delegates the full login flow to {@link LoginRunner}.
 * All state logic lives in {@link LoginStates}; this class owns only the
 * constructor contract (used by RecorderPlugin) and the {@link #login} entry point.
 */
@Slf4j
public final class LoginAssistant
{
    private final HumanizedInputDispatcher dispatcher;
    private final Client client;
    private final ClientThread clientThread;
    private final WorldPicker worldPicker;
    private final Random rng;

    public LoginAssistant(HumanizedInputDispatcher dispatcher, Client client,
                          ClientThread clientThread)
    {
        this(dispatcher, client, clientThread, new WorldPicker(), new Random());
    }

    public LoginAssistant(HumanizedInputDispatcher dispatcher, Client client,
                          ClientThread clientThread, WorldPicker worldPicker, Random rng)
    {
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher required");
        if (client == null) throw new IllegalArgumentException("client required");
        this.dispatcher = dispatcher;
        this.client = client;
        this.clientThread = clientThread;
        this.worldPicker = worldPicker;
        this.rng = rng;
    }

    /**
     * Run the full login flow with status callbacks. Caller picks the thread
     * — this method blocks. Use a daemon thread, never the EDT.
     *
     * @param creds  username + lazy password supplier
     * @param status optional consumer of human-readable status updates
     * @return       true if {@code GameState.LOGGED_IN} was observed, false
     *               on failure (timeout, wrong state, missing credentials).
     * @throws IllegalArgumentException if {@code creds} is null
     */
    public boolean login(LoginCredentials creds, Consumer<String> status)
    {
        if (creds == null) throw new IllegalArgumentException("credentials required");
        Consumer<String> sink = status != null ? status : s -> {};
        int currentWorld = -1;
        try { currentWorld = client.getWorld(); } catch (Exception ignored) {}
        LoginContext ctx = new LoginContext(creds, dispatcher, client, clientThread, rng, sink, currentWorld);
        return LoginRunner.run(ctx);
    }
}
