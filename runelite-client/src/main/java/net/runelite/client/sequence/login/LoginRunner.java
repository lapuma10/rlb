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
import java.util.EnumMap;
import java.util.function.Function;

/**
 * Drives the login FSM. See spec §6.2 for the retry loop.
 *
 * The runner is testable via runWithHandlers(...) which takes a
 * caller-supplied dispatch table — production code uses run(ctx) which
 * builds the real handler table.
 */
@Slf4j
public final class LoginRunner
{
    private LoginRunner() {}

    /** Production entrypoint — uses the real state implementations. */
    public static boolean run(LoginContext ctx)
    {
        EnumMap<LoginState, Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        handlers.put(LoginState.PRECHECK,              LoginStates::precheck);
        handlers.put(LoginState.WAIT_FOR_LOGIN_SCREEN, LoginStates::waitForLoginScreen);
        handlers.put(LoginState.NUDGE_INTRO,           LoginStates::nudgeIntro);
        handlers.put(LoginState.RESOLVE_USERNAME,      LoginStates::resolveUsername);
        handlers.put(LoginState.CLEAR_USERNAME,        LoginStates::clearUsername);
        handlers.put(LoginState.TYPE_USERNAME,         LoginStates::typeUsername);
        handlers.put(LoginState.FOCUS_PASSWORD,        LoginStates::focusPassword);
        handlers.put(LoginState.CLEAR_PASSWORD,        LoginStates::clearPassword);
        handlers.put(LoginState.PASTE_PASSWORD,        LoginStates::pastePassword);
        handlers.put(LoginState.CLICK_LOGIN,           LoginStates::clickLogin);
        handlers.put(LoginState.AWAIT_LOGGED_IN,       LoginStates::awaitLoggedIn);
        handlers.put(LoginState.AWAIT_WELCOME,         LoginStates::awaitWelcome);
        handlers.put(LoginState.DISMISS_WELCOME,       LoginStates::dismissWelcome);
        handlers.put(LoginState.DONE,                  LoginStates::done);
        return runWithHandlers(handlers, LoginState.PRECHECK, ctx);
    }

    /** Test-friendly entrypoint — caller supplies the handler table. */
    public static boolean runWithHandlers(
        EnumMap<LoginState, Function<LoginContext, StateResult>> handlers,
        LoginState initial,
        LoginContext ctx)
    {
        LoginState state = initial;
        while (true)
        {
            try
            {
                Function<LoginContext, StateResult> h = handlers.get(state);
                if (h == null)
                {
                    log.error("[login] no handler for state {}", state);
                    ctx.status("internal error: no handler for " + state);
                    return false;
                }
                log.info("[login] state {} entering", state);
                StateResult result = h.apply(ctx);

                if (result instanceof StateResult.Done)
                {
                    ctx.status("finished — logged in as " + (ctx.getCredentials() != null ? ctx.getCredentials().getUsername() : "?"));
                    return true;
                }
                if (result instanceof StateResult.Continue c)
                {
                    log.info("[login] state {} → {}", state, c.next());
                    state = c.next();
                    continue;
                }
                if (result instanceof StateResult.Failure f)
                {
                    LoginError err = f.error();
                    ctx.setLastError(err);
                    log.warn("[login] state {} failed: {} ({})", state, err, err.message());

                    if (err == LoginError.INTERRUPTED)
                    {
                        ctx.status("login cancelled");
                        return false;
                    }
                    if (err.recoverable() && ctx.getRetryCount() == 0)
                    {
                        ctx.status("retrying after " + err.message());
                        try
                        {
                            LoginErrorRecovery.apply(err, ctx);
                        }
                        catch (InterruptedException ie)
                        {
                            Thread.currentThread().interrupt();
                            ctx.status("login cancelled");
                            return false;
                        }
                        catch (Exception ex)
                        {
                            log.warn("[login] recovery action failed", ex);
                            ctx.setLastError(LoginError.WORLD_SWITCH_FAILED);
                            ctx.status(LoginError.WORLD_SWITCH_FAILED.message());
                            return false;
                        }
                        ctx.incrementRetry();
                        state = LoginState.WAIT_FOR_LOGIN_SCREEN;
                        continue;
                    }
                    ctx.status(err.message());
                    return false;
                }
                throw new IllegalStateException("unknown StateResult: " + result);
            }
            catch (RuntimeException re)
            {
                log.error("[login] runner exception in state {}", state, re);
                LoginError err = LoginError.CLIENT_THREAD_STUCK;
                ctx.setLastError(err);
                ctx.status("internal error: " + re.getMessage());
                if (err.recoverable() && ctx.getRetryCount() == 0)
                {
                    // Allow one retry on runtime exceptions, same as Failure(CLIENT_THREAD_STUCK)
                    ctx.status("retrying after " + err.message());
                    try { LoginErrorRecovery.apply(err, ctx); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); ctx.status("login cancelled"); return false; }
                    catch (Exception ex) { log.warn("[login] recovery action failed", ex); ctx.setLastError(LoginError.WORLD_SWITCH_FAILED); ctx.status(LoginError.WORLD_SWITCH_FAILED.message()); return false; }
                    ctx.incrementRetry();
                    state = LoginState.WAIT_FOR_LOGIN_SCREEN;
                    continue;
                }
                return false;
            }
        }
    }
}
