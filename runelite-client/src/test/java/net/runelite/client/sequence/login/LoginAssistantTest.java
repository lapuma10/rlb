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
import net.runelite.api.GameState;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.login.CredentialStore;
import net.runelite.client.sequence.login.LoginContext;
import net.runelite.client.sequence.login.LoginCredentials;
import net.runelite.client.sequence.login.LoginState;
import net.runelite.client.sequence.login.LoginStates;
import net.runelite.client.sequence.login.StateResult;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Surface tests — exercise LoginAssistant construction and early-return paths
 *  via the FSM. The goal is to verify the wiring compiles and the precheck
 *  logic short-circuits correctly without needing a live client. */
public class LoginAssistantTest
{
    @Test
    public void alreadyLoggedIn_isShortCircuit() throws Exception
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getUsername()).thenReturn("alice");
        HumanizedInputDispatcher dispatcher = new HumanizedInputDispatcher(client, null);
        LoginAssistant la = new LoginAssistant(dispatcher, client, null,
            new WorldPicker(), new Random(0L));

        List<String> events = new ArrayList<>();
        boolean ok = la.login(new LoginCredentials("alice",
                new LoginCredentialsTest.InMemoryStore()),
            events::add);
        assertTrue(ok);
        assertTrue("status should mention 'finished' or 'logged in': " + events,
            events.stream().anyMatch(s -> s.toLowerCase().contains("finished") || s.toLowerCase().contains("logged in")));
    }

    @Test
    public void notOnLoginScreen_isFailure() throws Exception
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.HOPPING);
        HumanizedInputDispatcher dispatcher = new HumanizedInputDispatcher(client, null);
        LoginAssistant la = new LoginAssistant(dispatcher, client, null,
            new WorldPicker(), new Random(0L));
        List<String> events = new ArrayList<>();
        boolean ok = la.login(new LoginCredentials("alice",
                new LoginCredentialsTest.InMemoryStore()),
            events::add);
        assertFalse(ok);
        assertTrue("status should mention 'login screen': " + events,
            events.stream().anyMatch(s -> s.toLowerCase().contains("login screen")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullCredentials_throwsIllegalArgument() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = new HumanizedInputDispatcher(client, null);
        LoginAssistant la = new LoginAssistant(dispatcher, client, null,
            new WorldPicker(), new Random(0L));
        la.login(null, s -> {});
    }

    @Test
    public void regression_wrongUsernamePreFilled_clearsAndRetypes() throws Exception
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        AtomicReference<String> currentUsername = new AtomicReference<>("wrong@example.com");
        when(client.getUsername()).thenAnswer(inv -> currentUsername.get());
        when(client.getLoginIndex()).thenReturn(2);
        when(client.getCanvasWidth()).thenReturn(800);
        when(client.getCanvasHeight()).thenReturn(600);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());

        // Simulate the field draining as backspace is sent
        doAnswer(inv -> {
            int code = inv.getArgument(0);
            if (code == java.awt.event.KeyEvent.VK_BACK_SPACE)
            {
                String s = currentUsername.get();
                if (s != null && !s.isEmpty()) currentUsername.set(s.substring(0, s.length() - 1));
            }
            return null;
        }).when(dispatcher).tapKey(anyInt());

        // Simulate typing accumulating into the field
        doAnswer(inv -> {
            char c = inv.getArgument(0);
            currentUsername.set((currentUsername.get() == null ? "" : currentUsername.get()) + c);
            return null;
        }).when(dispatcher).typeChar(anyChar());

        CredentialStore store = mock(CredentialStore.class);
        when(store.read(anyString())).thenReturn("password");
        LoginCredentials creds = new LoginCredentials("right@example.com", store);

        LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);

        // Test the FSM forward through CLEAR_USERNAME → TYPE_USERNAME
        StateResult resolveR = LoginStates.resolveUsername(ctx);
        assertEquals(LoginState.CLEAR_USERNAME, ((StateResult.Continue) resolveR).next());

        StateResult clearR = LoginStates.clearUsername(ctx);
        assertEquals("field cleared", "", currentUsername.get());
        assertEquals(LoginState.TYPE_USERNAME, ((StateResult.Continue) clearR).next());

        StateResult typeR = LoginStates.typeUsername(ctx);
        assertEquals("right@example.com", currentUsername.get());
        assertEquals(LoginState.FOCUS_PASSWORD, ((StateResult.Continue) typeR).next());
    }
}
