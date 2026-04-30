package net.runelite.client.sequence.login;

import net.runelite.api.GameState;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.sequence.dispatch.SequenceSleep;

/**
 * Login V2 state implementations. Same shape as {@link LoginStates} but:
 *  - takes {@link LoginContextV2}
 *  - all sprite-button clicks resolve through {@link TitleFrame}
 *  - one new state {@code SWITCH_WORLD} inserted before NUDGE_INTRO
 *  - one-shot (no retry); failures surface to the panel
 */
@Slf4j
public final class LoginStatesV2
{
    private LoginStatesV2() {}

    static final int LOGIN_FORM_INDEX = 2;
    static final int LOGIN_FIELD_PASSWORD = 1;
    static final long WAIT_FOR_LOGIN_SCREEN_TIMEOUT_MS = 8_000L;
    static final long NUDGE_INTRO_TIMEOUT_MS = 3_000L;
    static final long POLL_INNER_SLEEP_MS = 200L;
    static final long FIELD_CLEAR_HARD_CAP_MS = 3_000L;
    static final long PASSWORD_CLEAR_BASE_MS = 3_500L;
    static final int  PASSWORD_CLEAR_VARIANCE_MS = 800;
    static final long CLICK_LOGIN_EARLY_GATE_MS = 2_500L;
    static final long AWAIT_LOGGED_IN_TIMEOUT_MS = 30_000L;
    static final long AWAIT_WELCOME_TIMEOUT_MS = 5_000L;
    static final long WELCOME_DISMISS_POLL_MS = 3_000L;
    static final long WELCOME_CLICK_DELAY_MIN_MS = 4_000L;
    static final long WELCOME_CLICK_DELAY_MAX_MS = 45_000L;

    public static StateResultV2 precheck(LoginContextV2 ctx)
    {
        try
        {
            GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
            if (gs == null) return StateResultV2.fail(LoginError.UNEXPECTED_GAMESTATE);
            switch (gs)
            {
                case LOGGED_IN:
                {
                    String currentUser = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
                    String target = ctx.getCredentials() != null ? ctx.getCredentials().getUsername() : null;
                    if (target == null) return StateResultV2.fail(LoginError.UNEXPECTED_GAMESTATE);
                    if (currentUser != null && currentUser.equalsIgnoreCase(target)) return StateResultV2.done();
                    return StateResultV2.fail(LoginError.WRONG_ACCOUNT_LOGGED_IN);
                }
                case LOGGING_IN:
                case LOADING:
                    return StateResultV2.cont(LoginStateV2.WAIT_FOR_LOGIN_SCREEN);
                case LOGIN_SCREEN:
                    return StateResultV2.cont(LoginStateV2.SWITCH_WORLD);
                default:
                    return StateResultV2.fail(LoginError.UNEXPECTED_GAMESTATE);
            }
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] precheck failed", ex);
            return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResultV2 waitForLoginScreen(LoginContextV2 ctx)
    {
        long deadline = System.currentTimeMillis() + WAIT_FOR_LOGIN_SCREEN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) return StateResultV2.fail(LoginError.INTERRUPTED);
            try
            {
                GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
                if (gs == GameState.LOGIN_SCREEN) return StateResultV2.cont(LoginStateV2.SWITCH_WORLD);
            }
            catch (Exception ex)
            {
                return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
            }
            try { SequenceSleep.sleep(ctx.getClient(), POLL_INNER_SLEEP_MS); }
            catch (InterruptedException ie) { return StateResultV2.fail(LoginError.INTERRUPTED); }
        }
        return StateResultV2.fail(LoginError.UNEXPECTED_GAMESTATE);
    }

    public static StateResultV2 switchWorld(LoginContextV2 ctx)
    {
        Integer target = ctx.getTargetWorldId();
        if (target == null) return StateResultV2.cont(LoginStateV2.NUDGE_INTRO);
        try
        {
            Integer current = ctx.getDispatcher().runOnClient(ctx.getClient()::getWorld);
            if (current != null && current.intValue() == target.intValue())
            {
                log.info("[login-v2] already on world {}; skipping switch", target);
                return StateResultV2.cont(LoginStateV2.NUDGE_INTRO);
            }
            ctx.status("switching to world " + target);
            boolean ok = WorldSwitcherV2.switchTo(ctx, target);
            if (!ok) return StateResultV2.fail(LoginError.WORLD_SWITCH_FAILED);
            return StateResultV2.cont(LoginStateV2.NUDGE_INTRO);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return StateResultV2.fail(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] switchWorld failed", ex);
            return StateResultV2.fail(LoginError.WORLD_SWITCH_FAILED);
        }
    }

    public static StateResultV2 nudgeIntro(LoginContextV2 ctx)
    {
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx != null && idx == LOGIN_FORM_INDEX) return StateResultV2.cont(LoginStateV2.RESOLVE_USERNAME);

            for (int attempt = 0; attempt < 2; attempt++)
            {
                TitleFrame.Frame frame = ctx.getDispatcher().runOnClient(() -> TitleFrame.current(ctx.getClient()));
                java.awt.Point pt = TitleFrame.button(frame, TitleFrame.ButtonId.EXISTING_USER);
                log.info("[login-v2] click EXISTING_USER frame=({},{}) target=({},{})",
                    frame.x(), frame.y(), pt.x, pt.y);
                ctx.getDispatcher().clickCanvas(pt.x, pt.y);
                if (waitForLoginIndex(ctx, LOGIN_FORM_INDEX, NUDGE_INTRO_TIMEOUT_MS))
                {
                    return StateResultV2.cont(LoginStateV2.RESOLVE_USERNAME);
                }
                log.info("[login-v2] nudgeIntro attempt {} did not transition; will re-click", attempt + 1);
            }
            return StateResultV2.fail(LoginError.UNEXPECTED_GAMESTATE);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return StateResultV2.fail(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] nudgeIntro failed", ex);
            return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResultV2 resolveUsername(LoginContextV2 ctx)
    {
        try
        {
            String current = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
            String target = ctx.getCredentials().getUsername();
            String c = current != null ? current.trim() : "";
            String t = target != null ? target.trim() : "";
            if (!c.isEmpty() && c.equalsIgnoreCase(t)) return StateResultV2.cont(LoginStateV2.FOCUS_PASSWORD);
            if (c.isEmpty()) return StateResultV2.cont(LoginStateV2.TYPE_USERNAME);
            return StateResultV2.cont(LoginStateV2.CLEAR_USERNAME);
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] resolveUsername failed", ex);
            return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResultV2 clearUsername(LoginContextV2 ctx)
    {
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx == null || idx != LOGIN_FORM_INDEX) return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);

            TitleFrame.Frame frame = ctx.getDispatcher().runOnClient(() -> TitleFrame.current(ctx.getClient()));
            java.awt.Point pt = TitleFrame.button(frame, TitleFrame.ButtonId.USERNAME_FIELD);
            ctx.getDispatcher().clickCanvas(pt.x, pt.y);
            SequenceSleep.sleep(ctx.getClient(), 120 + ctx.getRng().nextInt(220));

            HumanizedTyping.holdBackspaceUntilEmpty(
                () -> {
                    try { return ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername); }
                    catch (Exception e) { return null; }
                },
                () -> {
                    try { ctx.getDispatcher().tapKey(java.awt.event.KeyEvent.VK_BACK_SPACE); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                },
                () -> {},
                FIELD_CLEAR_HARD_CAP_MS,
                ctx.getRng()
            );

            String after = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
            if (after == null || after.isEmpty()) return StateResultV2.cont(LoginStateV2.TYPE_USERNAME);
            return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return StateResultV2.fail(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] clearUsername failed", ex);
            return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResultV2 typeUsername(LoginContextV2 ctx)
    {
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            String current = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
            if (idx == null || idx != LOGIN_FORM_INDEX) return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);
            if (current != null && !current.isEmpty()) return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);

            String target = ctx.getCredentials().getUsername();
            for (char c : target.toCharArray())
            {
                ctx.getDispatcher().typeChar(c);
            }

            String after = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
            String at = after != null ? after.trim() : "";
            String tt = target != null ? target.trim() : "";
            if (!at.isEmpty() && at.equalsIgnoreCase(tt)) return StateResultV2.cont(LoginStateV2.FOCUS_PASSWORD);
            return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] typeUsername failed", ex);
            return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResultV2 focusPassword(LoginContextV2 ctx)
    {
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx == null || idx != LOGIN_FORM_INDEX) return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);

            for (int attempt = 0; attempt < 2; attempt++)
            {
                TitleFrame.Frame frame = ctx.getDispatcher().runOnClient(() -> TitleFrame.current(ctx.getClient()));
                java.awt.Point pt = TitleFrame.button(frame, TitleFrame.ButtonId.PASSWORD_FIELD);
                ctx.getDispatcher().clickCanvas(pt.x, pt.y);
                SequenceSleep.sleep(ctx.getClient(), 120 + ctx.getRng().nextInt(220));
                Integer focused = ctx.getDispatcher().runOnClient(ctx.getClient()::getCurrentLoginField);
                if (focused != null && focused == LOGIN_FIELD_PASSWORD)
                {
                    return StateResultV2.cont(LoginStateV2.CLEAR_PASSWORD);
                }
                log.warn("[login-v2] focusPassword: field={} after click, expected 1 — re-clicking", focused);
            }
            return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return StateResultV2.fail(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] focusPassword failed", ex);
            return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResultV2 clearPassword(LoginContextV2 ctx)
    {
        try
        {
            Integer idx0 = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx0 == null || idx0 != LOGIN_FORM_INDEX) return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);
            Integer focused0 = ctx.getDispatcher().runOnClient(ctx.getClient()::getCurrentLoginField);
            if (focused0 == null || focused0 != LOGIN_FIELD_PASSWORD)
            {
                log.warn("[login-v2] clearPassword: focus field={} not 1 — aborting", focused0);
                return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);
            }

            boolean[] aborted = {false};
            java.util.function.Predicate<Void> abortGuard = v -> {
                try
                {
                    Integer i = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
                    if (i == null || i != LOGIN_FORM_INDEX) { aborted[0] = true; return true; }
                    Integer f = ctx.getDispatcher().runOnClient(ctx.getClient()::getCurrentLoginField);
                    if (f == null || f != LOGIN_FIELD_PASSWORD) { aborted[0] = true; return true; }
                    return false;
                }
                catch (Exception e) { aborted[0] = true; return true; }
            };

            HumanizedTyping.holdBackspaceForDuration(
                () -> {
                    try { ctx.getDispatcher().tapKey(java.awt.event.KeyEvent.VK_BACK_SPACE); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                },
                abortGuard,
                PASSWORD_CLEAR_BASE_MS,
                PASSWORD_CLEAR_VARIANCE_MS,
                ctx.getRng()
            );

            if (aborted[0]) return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);
            return StateResultV2.cont(LoginStateV2.PASTE_PASSWORD);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return StateResultV2.fail(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] clearPassword failed", ex);
            return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResultV2 pastePassword(LoginContextV2 ctx)
    {
        java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        java.awt.datatransfer.Transferable previous = null;
        boolean clipboardSet = false;
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx == null || idx != LOGIN_FORM_INDEX) return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);
            Integer focused = ctx.getDispatcher().runOnClient(ctx.getClient()::getCurrentLoginField);
            if (focused == null || focused != LOGIN_FIELD_PASSWORD)
            {
                log.warn("[login-v2] pastePassword: focus field={} not 1 — refusing", focused);
                return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);
            }

            try { previous = cb.getContents(null); } catch (Exception ignored) {}

            String pw = ctx.getCredentials().getPassword();
            if (pw == null) return StateResultV2.fail(LoginError.BAD_CREDS);
            cb.setContents(new java.awt.datatransfer.StringSelection(pw), null);
            clipboardSet = true;

            boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
            int modifierMask = mac
                ? java.awt.event.KeyEvent.META_DOWN_MASK
                : java.awt.event.KeyEvent.CTRL_DOWN_MASK;
            int modifierKey = mac ? java.awt.event.KeyEvent.VK_META : java.awt.event.KeyEvent.VK_CONTROL;
            ctx.getDispatcher().tapKeyWithModifier(modifierKey, modifierMask, java.awt.event.KeyEvent.VK_V);

            SequenceSleep.sleep(ctx.getClient(), 80 + ctx.getRng().nextInt(120));
            return StateResultV2.cont(LoginStateV2.CLICK_LOGIN);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return StateResultV2.fail(LoginError.INTERRUPTED);
        }
        catch (CredentialStoreException cse)
        {
            log.warn("[login-v2] pastePassword: credential read failed", cse);
            return StateResultV2.fail(LoginError.BAD_CREDS);
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] pastePassword failed", ex);
            return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
        }
        finally
        {
            if (clipboardSet)
            {
                try
                {
                    cb.setContents(previous != null ? previous
                        : new java.awt.datatransfer.StringSelection(""), null);
                }
                catch (Exception ignored) {}
            }
        }
    }

    public static StateResultV2 clickLogin(LoginContextV2 ctx)
    {
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx == null || idx != LOGIN_FORM_INDEX) return StateResultV2.fail(LoginError.FIELD_NOT_CLEARED);

            SequenceSleep.sleep(ctx.getClient(), 200 + ctx.getRng().nextInt(500));

            // ENTER is canvas-size-agnostic — same approach V1 settled on after
            // the Login button widget detector + proportional fallback both
            // failed. Keyboard-first humanization for this submit step.
            log.info("[login-v2] submitting via ENTER");
            ctx.getDispatcher().tapKey(java.awt.event.KeyEvent.VK_ENTER);

            if (waitForClickLanded(ctx)) return StateResultV2.cont(LoginStateV2.AWAIT_LOGGED_IN);

            log.info("[login-v2] submit gate timed out — re-pressing ENTER once");
            ctx.getDispatcher().tapKey(java.awt.event.KeyEvent.VK_ENTER);
            return StateResultV2.cont(LoginStateV2.AWAIT_LOGGED_IN);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return StateResultV2.fail(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] clickLogin failed", ex);
            return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResultV2 awaitLoggedIn(LoginContextV2 ctx)
    {
        long deadline = System.currentTimeMillis() + AWAIT_LOGGED_IN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) return StateResultV2.fail(LoginError.INTERRUPTED);
            try
            {
                GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
                if (gs == GameState.LOGGED_IN) return StateResultV2.cont(LoginStateV2.AWAIT_WELCOME);
                if (gs == GameState.CONNECTION_LOST) return StateResultV2.fail(LoginError.CONNECTION_TIMEOUT);
                if (gs == GameState.LOGIN_SCREEN_AUTHENTICATOR) return StateResultV2.fail(LoginError.AUTH_REQUIRED);
            }
            catch (Exception ex)
            {
                return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
            }
            try { SequenceSleep.sleep(ctx.getClient(), POLL_INNER_SLEEP_MS); }
            catch (InterruptedException ie) { return StateResultV2.fail(LoginError.INTERRUPTED); }
        }
        return StateResultV2.fail(LoginError.TIMEOUT_NO_RESPONSE);
    }

    public static StateResultV2 awaitWelcome(LoginContextV2 ctx)
    {
        long deadline = System.currentTimeMillis() + AWAIT_WELCOME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) return StateResultV2.fail(LoginError.INTERRUPTED);
            try
            {
                boolean visible = ctx.getDispatcher().runOnClient(() -> WelcomeScreenDetector.isVisible(ctx.getClient()));
                if (visible) return StateResultV2.cont(LoginStateV2.DISMISS_WELCOME);
            }
            catch (Exception ex)
            {
                return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
            }
            try { SequenceSleep.sleep(ctx.getClient(), POLL_INNER_SLEEP_MS); }
            catch (InterruptedException ie) { return StateResultV2.fail(LoginError.INTERRUPTED); }
        }
        return StateResultV2.cont(LoginStateV2.DONE);
    }

    public static StateResultV2 dismissWelcome(LoginContextV2 ctx)
    {
        try
        {
            long delay = WELCOME_CLICK_DELAY_MIN_MS
                + (long)(ctx.getRng().nextDouble() * (WELCOME_CLICK_DELAY_MAX_MS - WELCOME_CLICK_DELAY_MIN_MS));
            log.info("[login-v2] welcome screen visible — waiting {}ms before click", delay);
            SequenceSleep.sleep(ctx.getClient(), delay);

            for (int attempt = 0; attempt < 3; attempt++)
            {
                java.awt.Point target = ctx.getDispatcher().runOnClient(() -> WelcomeScreenDetector.clickTarget(ctx.getClient()));
                if (target == null) return StateResultV2.cont(LoginStateV2.DONE);
                ctx.getDispatcher().clickCanvas(target.x, target.y);

                long until = System.currentTimeMillis() + WELCOME_DISMISS_POLL_MS;
                while (System.currentTimeMillis() < until)
                {
                    if (Thread.interrupted()) return StateResultV2.fail(LoginError.INTERRUPTED);
                    boolean stillVisible = ctx.getDispatcher().runOnClient(() -> WelcomeScreenDetector.isVisible(ctx.getClient()));
                    if (!stillVisible) return StateResultV2.cont(LoginStateV2.DONE);
                    SequenceSleep.sleep(ctx.getClient(), POLL_INNER_SLEEP_MS);
                }
                log.info("[login-v2] dismiss attempt {} did not register; retrying", attempt + 1);
                SequenceSleep.sleep(ctx.getClient(), 1000);
            }
            return StateResultV2.fail(LoginError.WELCOME_STUCK);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return StateResultV2.fail(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] dismissWelcome failed", ex);
            return StateResultV2.fail(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResultV2 done(LoginContextV2 ctx)
    {
        try { ctx.getDispatcher().parkCursor(); } catch (Exception ignored) {}
        return StateResultV2.done();
    }

    private static boolean waitForClickLanded(LoginContextV2 ctx)
    {
        long deadline = System.currentTimeMillis() + CLICK_LOGIN_EARLY_GATE_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) return false;
            try
            {
                GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
                Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
                if (gs != null && gs != GameState.LOGIN_SCREEN) return true;
                if (idx != null && idx != LOGIN_FORM_INDEX) return true;
            }
            catch (Exception ignored) {}
            try { SequenceSleep.sleep(ctx.getClient(), POLL_INNER_SLEEP_MS); }
            catch (InterruptedException ie) { return false; }
        }
        return false;
    }

    private static boolean waitForLoginIndex(LoginContextV2 ctx, int targetIdx, long timeoutMs)
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) return false;
            try
            {
                Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
                if (idx != null && idx == targetIdx) return true;
            }
            catch (Exception ignored) {}
            try { SequenceSleep.sleep(ctx.getClient(), POLL_INNER_SLEEP_MS); }
            catch (InterruptedException ie) { return false; }
        }
        return false;
    }
}
