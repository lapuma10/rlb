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

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Mutable state shared across LoginRunner transitions. All access is from the
 * single LoginRunner daemon thread; no synchronization needed.
 *
 * See docs/superpowers/specs/2026-04-26-login-overhaul-design.md §4.
 */
public final class LoginContext
{
    private final LoginCredentials credentials;
    private final HumanizedInputDispatcher dispatcher;
    private final Client client;
    @Nullable private final ClientThread clientThread;
    private final Random rng;
    private final Consumer<String> statusSink;

    private int currentWorldId;
    private int retryCount = 0;
    @Nullable private LoginError lastError;

    public LoginContext(LoginCredentials credentials,
                        HumanizedInputDispatcher dispatcher,
                        Client client,
                        @Nullable ClientThread clientThread,
                        Random rng,
                        Consumer<String> statusSink,
                        int currentWorldId)
    {
        this.credentials = credentials;
        this.dispatcher = dispatcher;
        this.client = client;
        this.clientThread = clientThread;
        this.rng = rng;
        this.statusSink = statusSink;
        this.currentWorldId = currentWorldId;
    }

    public LoginCredentials getCredentials() { return credentials; }
    public HumanizedInputDispatcher getDispatcher() { return dispatcher; }
    public Client getClient() { return client; }
    @Nullable public ClientThread getClientThread() { return clientThread; }
    public Random getRng() { return rng; }
    public int getCurrentWorldId() { return currentWorldId; }
    public void setCurrentWorldId(int id) { this.currentWorldId = id; }
    public int getRetryCount() { return retryCount; }
    public void incrementRetry() { this.retryCount++; }
    @Nullable public LoginError getLastError() { return lastError; }
    public void setLastError(LoginError e) { this.lastError = e; }
    public void status(String msg) { if (statusSink != null) statusSink.accept(msg); }
}
