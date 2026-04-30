package net.runelite.client.sequence.login;

import lombok.extern.slf4j.Slf4j;
import java.util.EnumMap;
import java.util.function.Function;

/**
 * Drives the V2 login FSM. One-shot — no auto-retry. Failures surface to
 * the panel; the user can correct (e.g. world id) and click again.
 */
@Slf4j
public final class LoginRunnerV2
{
    private LoginRunnerV2() {}

    public static boolean run(LoginContextV2 ctx)
    {
        EnumMap<LoginStateV2, Function<LoginContextV2, StateResultV2>> handlers = new EnumMap<>(LoginStateV2.class);
        handlers.put(LoginStateV2.PRECHECK,              LoginStatesV2::precheck);
        handlers.put(LoginStateV2.WAIT_FOR_LOGIN_SCREEN, LoginStatesV2::waitForLoginScreen);
        handlers.put(LoginStateV2.SWITCH_WORLD,          LoginStatesV2::switchWorld);
        handlers.put(LoginStateV2.NUDGE_INTRO,           LoginStatesV2::nudgeIntro);
        handlers.put(LoginStateV2.RESOLVE_USERNAME,      LoginStatesV2::resolveUsername);
        handlers.put(LoginStateV2.CLEAR_USERNAME,        LoginStatesV2::clearUsername);
        handlers.put(LoginStateV2.TYPE_USERNAME,         LoginStatesV2::typeUsername);
        handlers.put(LoginStateV2.FOCUS_PASSWORD,        LoginStatesV2::focusPassword);
        handlers.put(LoginStateV2.CLEAR_PASSWORD,        LoginStatesV2::clearPassword);
        handlers.put(LoginStateV2.PASTE_PASSWORD,        LoginStatesV2::pastePassword);
        handlers.put(LoginStateV2.CLICK_LOGIN,           LoginStatesV2::clickLogin);
        handlers.put(LoginStateV2.AWAIT_LOGGED_IN,       LoginStatesV2::awaitLoggedIn);
        handlers.put(LoginStateV2.AWAIT_WELCOME,         LoginStatesV2::awaitWelcome);
        handlers.put(LoginStateV2.DISMISS_WELCOME,       LoginStatesV2::dismissWelcome);
        handlers.put(LoginStateV2.DONE,                  LoginStatesV2::done);

        LoginStateV2 state = LoginStateV2.PRECHECK;
        while (true)
        {
            try
            {
                Function<LoginContextV2, StateResultV2> h = handlers.get(state);
                if (h == null)
                {
                    log.error("[login-v2] no handler for {}", state);
                    ctx.status("internal error: no handler for " + state);
                    return false;
                }
                log.info("[login-v2] state {} entering", state);
                StateResultV2 result = h.apply(ctx);

                if (result instanceof StateResultV2.Done)
                {
                    String user = ctx.getCredentials() != null ? ctx.getCredentials().getUsername() : "?";
                    ctx.status("finished — logged in as " + user);
                    return true;
                }
                if (result instanceof StateResultV2.Continue c)
                {
                    log.info("[login-v2] state {} → {}", state, c.next());
                    state = c.next();
                    continue;
                }
                if (result instanceof StateResultV2.Failure f)
                {
                    LoginError err = f.error();
                    log.warn("[login-v2] state {} failed: {} ({})", state, err, err.message());
                    if (err == LoginError.INTERRUPTED) ctx.status("login cancelled");
                    else ctx.status(err.message());
                    return false;
                }
                throw new IllegalStateException("unknown StateResultV2: " + result);
            }
            catch (RuntimeException re)
            {
                log.error("[login-v2] runner exception in state {}", state, re);
                ctx.status("internal error: " + re.getMessage());
                return false;
            }
        }
    }
}
