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
import net.runelite.api.World;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import org.junit.Test;
import java.util.EnumMap;
import java.util.Random;
import java.util.function.Supplier;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class LoginRunnerTest
{
    @Test
    public void run_donesImmediately_whenInitialStateReturnsDone()
    {
        EnumMap<LoginState, java.util.function.Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        handlers.put(LoginState.PRECHECK, ctx -> new StateResult.Done());
        LoginContext ctx = mockContext();
        boolean ok = LoginRunner.runWithHandlers(handlers, LoginState.PRECHECK, ctx);
        assertTrue(ok);
    }

    @Test
    public void run_failsImmediately_onTerminalError()
    {
        EnumMap<LoginState, java.util.function.Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        handlers.put(LoginState.PRECHECK, ctx -> new StateResult.Failure(LoginError.BAD_CREDS));
        LoginContext ctx = mockContext();
        boolean ok = LoginRunner.runWithHandlers(handlers, LoginState.PRECHECK, ctx);
        assertFalse(ok);
        assertEquals(0, ctx.getRetryCount());
    }

    @Test
    public void run_retriesOnce_onRecoverableError()
    {
        EnumMap<LoginState, java.util.function.Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        int[] callCount = {0};
        handlers.put(LoginState.PRECHECK, ctx -> {
            callCount[0]++;
            return callCount[0] == 1
                ? new StateResult.Failure(LoginError.WORLD_FULL)
                : new StateResult.Done();
        });
        handlers.put(LoginState.WAIT_FOR_LOGIN_SCREEN, ctx -> new StateResult.Continue(LoginState.PRECHECK));
        LoginContext ctx = mockContext();
        boolean ok = LoginRunner.runWithHandlers(handlers, LoginState.PRECHECK, ctx);
        assertTrue(ok);
        assertEquals(1, ctx.getRetryCount());
    }

    @Test
    public void run_doesNotRetryTwice()
    {
        EnumMap<LoginState, java.util.function.Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        handlers.put(LoginState.PRECHECK, ctx -> new StateResult.Failure(LoginError.WORLD_FULL));
        handlers.put(LoginState.WAIT_FOR_LOGIN_SCREEN, ctx -> new StateResult.Continue(LoginState.PRECHECK));
        LoginContext ctx = mockContext();
        boolean ok = LoginRunner.runWithHandlers(handlers, LoginState.PRECHECK, ctx);
        assertFalse(ok);
        assertEquals(1, ctx.getRetryCount());
    }

    @Test
    public void precheck_loginScreen_continuesToNudgeIntro() throws Exception
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGIN_SCREEN);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.precheck(ctx);
        assertTrue(r instanceof StateResult.Continue);
        assertEquals(LoginState.NUDGE_INTRO, ((StateResult.Continue) r).next());
    }

    @Test
    public void precheck_loggedInWithSameUsername_returnsDone() throws Exception
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        when(client.getUsername()).thenReturn("test@example.com");
        CredentialStore store = mock(CredentialStore.class);
        LoginCredentials creds = new LoginCredentials("test@example.com", store);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.precheck(ctx);
        assertTrue(r instanceof StateResult.Done);
    }

    @Test
    public void precheck_loggedInWithDifferentUsername_failsWrongAccount() throws Exception
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        when(client.getUsername()).thenReturn("other@example.com");
        CredentialStore store = mock(CredentialStore.class);
        LoginCredentials creds = new LoginCredentials("test@example.com", store);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.precheck(ctx);
        assertTrue(r instanceof StateResult.Failure);
        assertEquals(LoginError.WRONG_ACCOUNT_LOGGED_IN, ((StateResult.Failure) r).error());
    }

    @Test
    public void nudgeIntro_returnsResolveUsername_whenAlreadyOnForm() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        when(client.getLoginIndex()).thenReturn(2);
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.nudgeIntro(ctx);
        assertTrue(r instanceof StateResult.Continue);
        assertEquals(LoginState.RESOLVE_USERNAME, ((StateResult.Continue) r).next());
        verify(dispatcher, never()).clickCanvas(anyDouble(), anyDouble());
    }

    @Test
    public void nudgeIntro_clicksAndAdvances_whenOnIntro() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        int[] callIdx = {0};
        when(client.getLoginIndex()).thenAnswer(inv -> callIdx[0]++ == 0 ? 0 : 2);
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.nudgeIntro(ctx);
        assertTrue(r instanceof StateResult.Continue);
        assertEquals(LoginState.RESOLVE_USERNAME, ((StateResult.Continue) r).next());
        verify(dispatcher, atLeastOnce()).clickCanvas(anyDouble(), anyDouble());
    }

    @Test
    public void nudgeIntro_failsAfterTwoFailedClicks() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        when(client.getLoginIndex()).thenReturn(0);
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.nudgeIntro(ctx);
        assertTrue(r instanceof StateResult.Failure);
        assertEquals(LoginError.UNEXPECTED_GAMESTATE, ((StateResult.Failure) r).error());
        verify(dispatcher, times(2)).clickCanvas(anyDouble(), anyDouble());
    }

    @Test
    public void resolveUsername_skipsClearTypeWhenMatch() throws Exception
    {
        Client client = mock(Client.class);
        when(client.getUsername()).thenReturn("Test@Example.com");
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        CredentialStore store = mock(CredentialStore.class);
        LoginCredentials creds = new LoginCredentials("test@example.com", store);
        LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.resolveUsername(ctx);
        assertTrue(r instanceof StateResult.Continue);
        assertEquals(LoginState.FOCUS_PASSWORD, ((StateResult.Continue) r).next());
    }

    @Test
    public void resolveUsername_clearsWhenMismatch() throws Exception
    {
        Client client = mock(Client.class);
        when(client.getUsername()).thenReturn("wrong@example.com");
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        LoginCredentials creds = new LoginCredentials("right@example.com", mock(CredentialStore.class));
        LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.resolveUsername(ctx);
        assertTrue(r instanceof StateResult.Continue);
        assertEquals(LoginState.CLEAR_USERNAME, ((StateResult.Continue) r).next());
    }

    @Test
    public void resolveUsername_typesWhenEmpty() throws Exception
    {
        Client client = mock(Client.class);
        when(client.getUsername()).thenReturn("");
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        LoginCredentials creds = new LoginCredentials("right@example.com", mock(CredentialStore.class));
        LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.resolveUsername(ctx);
        assertEquals(LoginState.TYPE_USERNAME, ((StateResult.Continue) r).next());
    }

    @Test
    public void focusPassword_advances() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        when(client.getLoginIndex()).thenReturn(2);
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.focusPassword(ctx);
        assertTrue(r instanceof StateResult.Continue);
        assertEquals(LoginState.CLEAR_PASSWORD, ((StateResult.Continue) r).next());
    }

    @Test
    public void clearPassword_advances() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        when(client.getLoginIndex()).thenReturn(2);
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        long start = System.currentTimeMillis();
        StateResult r = LoginStates.clearPassword(ctx);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(r instanceof StateResult.Continue);
        assertEquals(LoginState.PASTE_PASSWORD, ((StateResult.Continue) r).next());
        assertTrue("ran at least 3000ms (the base duration)", elapsed >= 3_000);
    }

    @Test
    public void clearPassword_abortsWhenLoginIndexChanges() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        int[] callCount = {0};
        when(client.getLoginIndex()).thenAnswer(inv -> callCount[0]++ < 2 ? 2 : 0);
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.clearPassword(ctx);
        assertTrue(r instanceof StateResult.Failure);
        assertEquals(LoginError.FIELD_NOT_CLEARED, ((StateResult.Failure) r).error());
    }

    @Test
    public void clickLogin_advancesImmediately_whenStateChanges() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        when(client.getLoginIndex()).thenReturn(2);
        int[] gsCalls = {0};
        when(client.getGameState()).thenAnswer(inv ->
            gsCalls[0]++ == 0 ? net.runelite.api.GameState.LOGIN_SCREEN : net.runelite.api.GameState.LOGGING_IN);
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        long start = System.currentTimeMillis();
        StateResult r = LoginStates.clickLogin(ctx);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(r instanceof StateResult.Continue);
        assertEquals(LoginState.AWAIT_LOGGED_IN, ((StateResult.Continue) r).next());
        assertTrue("returned within early-gate window", elapsed < 1_500);
        verify(dispatcher, times(1)).clickCanvas(anyDouble(), anyDouble());
    }

    @Test
    public void clickLogin_reclicksOnMissedClick() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        when(client.getLoginIndex()).thenReturn(2);
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGIN_SCREEN);
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.clickLogin(ctx);
        assertTrue(r instanceof StateResult.Continue);
        verify(dispatcher, times(2)).clickCanvas(anyDouble(), anyDouble());
    }

    @Test
    public void awaitLoggedIn_returnsAwaitWelcome_onLoggedIn() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.awaitLoggedIn(ctx);
        assertTrue(r instanceof StateResult.Continue);
        assertEquals(LoginState.AWAIT_WELCOME, ((StateResult.Continue) r).next());
    }

    @Test
    public void awaitLoggedIn_failsConnectionTimeout_onConnectionLost() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.CONNECTION_LOST);
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.awaitLoggedIn(ctx);
        assertTrue(r instanceof StateResult.Failure);
        assertEquals(LoginError.CONNECTION_TIMEOUT, ((StateResult.Failure) r).error());
    }

    @Test
    public void awaitLoggedIn_classifiesBadCreds_fromErrorBanner() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGIN_SCREEN);
        // loginIndex == 3 (explicit error/banner state) so readLoginErrorBanner scans widgets.
        when(client.getLoginIndex()).thenReturn(3);

        net.runelite.api.widgets.Widget banner = mock(net.runelite.api.widgets.Widget.class);
        // Widget must be in the login interface group (0x017A = 378 decimal).
        // getId() >>> 16 must equal 0x017A, so getId() = 0x017A0000.
        when(banner.getId()).thenReturn(0x017A0000);
        when(banner.isHidden()).thenReturn(false);
        when(banner.getText()).thenReturn("Invalid username or password.");
        when(banner.getChildren()).thenReturn(null);
        when(banner.getDynamicChildren()).thenReturn(null);
        when(client.getWidgetRoots()).thenReturn(new net.runelite.api.widgets.Widget[]{ banner });

        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        StateResult r = LoginStates.awaitLoggedIn(ctx);
        assertTrue(r instanceof StateResult.Failure);
        assertEquals(LoginError.BAD_CREDS, ((StateResult.Failure) r).error());
    }

    @Test
    public void awaitWelcome_returnsDone_whenScreenNotPresent() throws Exception
    {
        Client client = mock(Client.class);
        HumanizedInputDispatcher dispatcher = mockDispatcher();
        when(client.getWidget(anyInt())).thenReturn(null);
        when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
        LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
        long start = System.currentTimeMillis();
        StateResult r = LoginStates.awaitWelcome(ctx);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(r instanceof StateResult.Done || r instanceof StateResult.Continue);
        if (r instanceof StateResult.Continue) assertEquals(LoginState.DONE, ((StateResult.Continue) r).next());
        assertTrue("waited at least the welcome timeout", elapsed >= 4_500);
    }

    @Test
    public void run_retriesOnRuntimeException_then_succeeds() throws Exception
    {
        EnumMap<LoginState, java.util.function.Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        int[] callCount = {0};
        handlers.put(LoginState.PRECHECK, ctx -> {
            callCount[0]++;
            if (callCount[0] == 1) throw new RuntimeException("synthetic boom");
            return new StateResult.Done();
        });
        handlers.put(LoginState.WAIT_FOR_LOGIN_SCREEN, ctx -> new StateResult.Continue(LoginState.PRECHECK));
        LoginContext ctx = mockContextWithEmptyWorlds();
        boolean ok = LoginRunner.runWithHandlers(handlers, LoginState.PRECHECK, ctx);
        assertTrue("should succeed after one retry", ok);
        assertEquals(1, ctx.getRetryCount());
    }

    @Test
    public void run_doesNotRetryRuntimeException_secondTime() throws Exception
    {
        EnumMap<LoginState, java.util.function.Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        handlers.put(LoginState.PRECHECK, ctx -> { throw new RuntimeException("synthetic boom"); });
        handlers.put(LoginState.WAIT_FOR_LOGIN_SCREEN, ctx -> new StateResult.Continue(LoginState.PRECHECK));
        LoginContext ctx = mockContextWithEmptyWorlds();
        boolean ok = LoginRunner.runWithHandlers(handlers, LoginState.PRECHECK, ctx);
        assertFalse(ok);
        assertEquals(1, ctx.getRetryCount());
    }

    private LoginContext mockContextWithEmptyWorlds()
    {
        Client c = mock(Client.class);
        when(c.getWorldList()).thenReturn(new net.runelite.api.World[0]);
        return new LoginContext(null, mockDispatcherSafe(), c, null, new Random(0), s -> {}, 308);
    }

    private HumanizedInputDispatcher mockDispatcherSafe()
    {
        try { return mockDispatcher(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private HumanizedInputDispatcher mockDispatcher() throws InterruptedException
    {
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        when(dispatcher.runOnClient(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        return dispatcher;
    }

    private LoginContext mockContext()
    {
        Client c = mock(Client.class);
        // Stub world list to empty so WORLD_FULL recovery's pickF2PNonPvP returns null gracefully
        when(c.getWorldList()).thenReturn(new World[0]);
        return new LoginContext(null, null, c, null, new Random(0), s -> {}, 308);
    }
}
