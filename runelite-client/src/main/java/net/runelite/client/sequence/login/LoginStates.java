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

import net.runelite.api.GameState;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.sequence.login.CredentialStoreException;

/**
 * State implementations for the login FSM. One static method per state.
 * Each method takes the LoginContext, performs precondition reads via
 * runOnClient, acts, verifies post-conditions, and returns a StateResult.
 *
 * See spec §5.1.
 */
@Slf4j
public final class LoginStates
{
    private LoginStates() {}

    static final int LOGIN_FORM_INDEX = 2;
    static final long PRECHECK_RUN_ON_CLIENT_TIMEOUT_MS = 2_000L;
    static final long WAIT_FOR_LOGIN_SCREEN_TIMEOUT_MS = 8_000L;
    static final long NUDGE_INTRO_TIMEOUT_MS = 3_000L;
    static final long POLL_INNER_SLEEP_MS = 200L;
    static final double EXISTING_USER_X = 0.59;  // was 0.50 — actual button is right-of-center
    static final double EXISTING_USER_Y = 0.43;  // was 0.62 — buttons are upper-half, not lower

    public static StateResult precheck(LoginContext ctx)
    {
        try
        {
            GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
            if (gs == null) return new StateResult.Failure(LoginError.UNEXPECTED_GAMESTATE);
            switch (gs)
            {
                case LOGGED_IN:
                {
                    String currentUser = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
                    String target = ctx.getCredentials() != null ? ctx.getCredentials().getUsername() : null;
                    if (target == null) return new StateResult.Failure(LoginError.UNEXPECTED_GAMESTATE);
                    if (currentUser != null && currentUser.equalsIgnoreCase(target)) return new StateResult.Done();
                    return new StateResult.Failure(LoginError.WRONG_ACCOUNT_LOGGED_IN);
                }
                case LOGGING_IN:
                case LOADING:
                    return new StateResult.Continue(LoginState.WAIT_FOR_LOGIN_SCREEN);
                case LOGIN_SCREEN:
                    return new StateResult.Continue(LoginState.NUDGE_INTRO);
                default:
                    return new StateResult.Failure(LoginError.UNEXPECTED_GAMESTATE);
            }
        }
        catch (Exception ex)
        {
            log.warn("[login] precheck runOnClient failed", ex);
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResult waitForLoginScreen(LoginContext ctx)
    {
        long deadline = System.currentTimeMillis() + WAIT_FOR_LOGIN_SCREEN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) return new StateResult.Failure(LoginError.INTERRUPTED);
            try
            {
                GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
                if (gs == GameState.LOGIN_SCREEN) return new StateResult.Continue(LoginState.PRECHECK);
            }
            catch (Exception ex)
            {
                return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
            }
            try { Thread.sleep(POLL_INNER_SLEEP_MS); }
            catch (InterruptedException ie) { return new StateResult.Failure(LoginError.INTERRUPTED); }
        }
        return new StateResult.Failure(LoginError.UNEXPECTED_GAMESTATE);
    }

    public static StateResult nudgeIntro(LoginContext ctx)
    {
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx != null && idx == LOGIN_FORM_INDEX) return new StateResult.Continue(LoginState.RESOLVE_USERNAME);

            for (int attempt = 0; attempt < 2; attempt++)
            {
                // Try widget-based detection first (works across fixed/resizable layouts)
                java.awt.Point target = ctx.getDispatcher().runOnClient(
                    () -> IntroScreenDetector.existingUserClickTarget(ctx.getClient()));
                if (target != null)
                {
                    log.info("[login] clicking 'Existing User' widget at ({}, {})", target.x, target.y);
                    ctx.getDispatcher().clickCanvas(target.x, target.y);
                }
                else
                {
                    // Fallback: proportional coords (best-effort if widget scan failed)
                    log.warn("[login] 'Existing User' widget not found; falling back to proportional click");
                    ctx.getDispatcher().clickCanvas(EXISTING_USER_X, EXISTING_USER_Y);
                }
                if (waitForLoginIndex(ctx, LOGIN_FORM_INDEX, NUDGE_INTRO_TIMEOUT_MS))
                {
                    return new StateResult.Continue(LoginState.RESOLVE_USERNAME);
                }
                log.info("[login] nudgeIntro attempt {} did not transition; will re-click", attempt + 1);
            }
            return new StateResult.Failure(LoginError.UNEXPECTED_GAMESTATE);
        }
        catch (Exception ex)
        {
            log.warn("[login] nudgeIntro failed", ex);
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResult resolveUsername(LoginContext ctx)
    {
        try
        {
            String current = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
            String target = ctx.getCredentials().getUsername();
            String currentTrimmed = current != null ? current.trim() : "";
            String targetTrimmed = target != null ? target.trim() : "";

            String decision;
            StateResult result;
            if (!currentTrimmed.isEmpty() && currentTrimmed.equalsIgnoreCase(targetTrimmed))
            {
                decision = "match → FOCUS_PASSWORD";
                result = new StateResult.Continue(LoginState.FOCUS_PASSWORD);
            }
            else if (currentTrimmed.isEmpty())
            {
                decision = "empty → TYPE_USERNAME";
                result = new StateResult.Continue(LoginState.TYPE_USERNAME);
            }
            else
            {
                decision = "mismatch → CLEAR_USERNAME";
                result = new StateResult.Continue(LoginState.CLEAR_USERNAME);
            }
            log.info("[login] resolveUsername: current=\"{}\" target=\"{}\" → {}",
                currentTrimmed, targetTrimmed, decision);
            log.info("[login] resolveUsername hex: current={} target={}",
                LoginDebugBus.hexDump(currentTrimmed), LoginDebugBus.hexDump(targetTrimmed));
            LoginDebugBus.publish(currentTrimmed, targetTrimmed, decision);
            return result;
        }
        catch (Exception ex)
        {
            log.warn("[login] resolveUsername failed", ex);
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    static final double USERNAME_FIELD_X = 0.50;
    static final double USERNAME_FIELD_Y = 0.46;
    static final long FIELD_CLEAR_HARD_CAP_MS = 3_000L;

    public static StateResult clearUsername(LoginContext ctx)
    {
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx == null || idx != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);

            ctx.getDispatcher().clickCanvas(USERNAME_FIELD_X, USERNAME_FIELD_Y);
            Thread.sleep(120 + ctx.getRng().nextInt(220));

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
            log.info("[login] clearUsername post-condition: after=\"{}\"", after);
            if (after == null || after.isEmpty()) return new StateResult.Continue(LoginState.TYPE_USERNAME);
            return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return new StateResult.Failure(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login] clearUsername failed", ex);
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResult typeUsername(LoginContext ctx)
    {
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            String current = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
            if (idx == null || idx != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
            if (current != null && !current.isEmpty()) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);

            String target = ctx.getCredentials().getUsername();
            for (char c : target.toCharArray())
            {
                ctx.getDispatcher().typeChar(c);
            }

            String after = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
            String afterTrimmed = after != null ? after.trim() : "";
            String targetTrimmed = target != null ? target.trim() : "";
            log.info("[login] typeUsername post-condition: after=\"{}\" target=\"{}\"", afterTrimmed, targetTrimmed);
            if (!afterTrimmed.isEmpty() && afterTrimmed.equalsIgnoreCase(targetTrimmed)) return new StateResult.Continue(LoginState.FOCUS_PASSWORD);
            return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
        }
        catch (Exception ex)
        {
            log.warn("[login] typeUsername failed", ex);
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    static final double PASSWORD_FIELD_X = 0.50;
    static final double PASSWORD_FIELD_Y = 0.54;
    static final long PASSWORD_CLEAR_BASE_MS = 3_500L;
    static final int  PASSWORD_CLEAR_VARIANCE_MS = 800;
    static final int  LOGIN_FIELD_PASSWORD = 1;

    public static StateResult focusPassword(LoginContext ctx)
    {
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx == null || idx != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);

            // Always click — the previous random TAB (70% odds) only worked if
            // a form field already had focus, which the match path didn't
            // guarantee (no prior typing → focus might still be on the now-
            // hidden "Existing User" widget that NUDGE_INTRO clicked). When
            // TAB rolled and focus wasn't set, downstream CLEAR_PASSWORD
            // backspaced into the username field instead.
            for (int attempt = 0; attempt < 2; attempt++)
            {
                ctx.getDispatcher().clickCanvas(PASSWORD_FIELD_X, PASSWORD_FIELD_Y);
                Thread.sleep(120 + ctx.getRng().nextInt(220));
                Integer focused = ctx.getDispatcher().runOnClient(ctx.getClient()::getCurrentLoginField);
                if (focused != null && focused == LOGIN_FIELD_PASSWORD)
                {
                    return new StateResult.Continue(LoginState.CLEAR_PASSWORD);
                }
                log.warn("[login] focusPassword: getCurrentLoginField={} after click, expected 1 — re-clicking", focused);
            }
            return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return new StateResult.Failure(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login] focusPassword failed", ex);
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResult clearPassword(LoginContext ctx)
    {
        try
        {
            Integer idx0 = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx0 == null || idx0 != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
            // Don't blindly backspace if focus isn't on the password field —
            // we'd otherwise wipe the username field. focusPassword should
            // have set this; if it didn't, abort instead of corrupting state.
            Integer focused0 = ctx.getDispatcher().runOnClient(ctx.getClient()::getCurrentLoginField);
            if (focused0 == null || focused0 != LOGIN_FIELD_PASSWORD)
            {
                log.warn("[login] clearPassword: focus is on field {} not 1 — aborting before backspaces", focused0);
                return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
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

            if (aborted[0]) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
            return new StateResult.Continue(LoginState.PASTE_PASSWORD);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return new StateResult.Failure(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login] clearPassword failed", ex);
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResult pastePassword(LoginContext ctx)
    {
        java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        java.awt.datatransfer.Transferable previous = null;
        boolean clipboardSet = false; // only restore if we actually modified the clipboard
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx == null || idx != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
            Integer focused = ctx.getDispatcher().runOnClient(ctx.getClient()::getCurrentLoginField);
            if (focused == null || focused != LOGIN_FIELD_PASSWORD)
            {
                log.warn("[login] pastePassword: focus is on field {} not 1 — refusing to paste", focused);
                return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
            }

            try { previous = cb.getContents(null); } catch (Exception ignored) {}

            String pw = ctx.getCredentials().getPassword();
            if (pw == null) return new StateResult.Failure(LoginError.BAD_CREDS);
            cb.setContents(new java.awt.datatransfer.StringSelection(pw), null);
            clipboardSet = true;

            int modifierMask = isMac()
                ? java.awt.event.KeyEvent.META_DOWN_MASK
                : java.awt.event.KeyEvent.CTRL_DOWN_MASK;
            int modifierKey = isMac() ? java.awt.event.KeyEvent.VK_META : java.awt.event.KeyEvent.VK_CONTROL;
            ctx.getDispatcher().tapKeyWithModifier(modifierKey, modifierMask, java.awt.event.KeyEvent.VK_V);

            Thread.sleep(80 + ctx.getRng().nextInt(120));
            return new StateResult.Continue(LoginState.CLICK_LOGIN);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return new StateResult.Failure(LoginError.INTERRUPTED);
        }
        catch (CredentialStoreException cse)
        {
            log.warn("[login] pastePassword: credential read failed", cse);
            return new StateResult.Failure(LoginError.BAD_CREDS);
        }
        catch (Exception ex)
        {
            log.warn("[login] pastePassword failed", ex);
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
        finally
        {
            if (clipboardSet)
            {
                try
                {
                    if (previous != null)
                    {
                        cb.setContents(previous, null);
                    }
                    else
                    {
                        cb.setContents(new java.awt.datatransfer.StringSelection(""), null);
                    }
                }
                catch (Exception ignored) {}
            }
        }
    }

    static final long CLICK_LOGIN_EARLY_GATE_MS = 2_500L;

    public static StateResult clickLogin(LoginContext ctx)
    {
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx == null || idx != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);

            Thread.sleep(200 + ctx.getRng().nextInt(500));

            // Submit via ENTER instead of clicking the Login widget. The widget
            // detector returned null on this layout (LoginButtonDetector
            // matches "Login"/"Log in" text or actions, but the button on the
            // current login interface presents as a graphic with neither),
            // and the proportional fallback at (0.50, 0.62) didn't land on
            // the button either. ENTER is canvas-size-agnostic and is what a
            // keyboard-first user would do.
            log.info("[login] submitting via ENTER");
            ctx.getDispatcher().tapKey(java.awt.event.KeyEvent.VK_ENTER);

            if (waitForClickLanded(ctx)) return new StateResult.Continue(LoginState.AWAIT_LOGGED_IN);

            log.info("[login] submit gate timed out — re-pressing ENTER once");
            ctx.getDispatcher().tapKey(java.awt.event.KeyEvent.VK_ENTER);
            return new StateResult.Continue(LoginState.AWAIT_LOGGED_IN);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return new StateResult.Failure(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login] clickLogin failed", ex);
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    static final long AWAIT_LOGGED_IN_TIMEOUT_MS = 30_000L;
    static final long AWAIT_WELCOME_TIMEOUT_MS = 5_000L;
    static final long WELCOME_DISMISS_POLL_MS = 3_000L;
    static final long WELCOME_CLICK_DELAY_MIN_MS = 4_000L;
    static final long WELCOME_CLICK_DELAY_MAX_MS = 45_000L;

    public static StateResult awaitLoggedIn(LoginContext ctx)
    {
        long deadline = System.currentTimeMillis() + AWAIT_LOGGED_IN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) return new StateResult.Failure(LoginError.INTERRUPTED);
            try
            {
                net.runelite.api.GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
                if (gs == net.runelite.api.GameState.LOGGED_IN) return new StateResult.Continue(LoginState.AWAIT_WELCOME);
                if (gs == net.runelite.api.GameState.CONNECTION_LOST) return new StateResult.Failure(LoginError.CONNECTION_TIMEOUT);
                if (gs == net.runelite.api.GameState.LOGIN_SCREEN_AUTHENTICATOR)
                {
                    return new StateResult.Failure(LoginError.AUTH_REQUIRED);
                }

                // Check for login error banner — best-effort. Widget ID is TBD per
                // spec §13; we use loginIndex==2 (still on form) + bannerText to
                // detect errors. If we can read getLoginResponseString or similar,
                // classify it. The widget hierarchy is captured by WidgetDumper at
                // panel debug-button time.
                String banner = ctx.getDispatcher().runOnClient(() -> readLoginErrorBanner(ctx.getClient()));
                if (banner != null && !banner.isBlank())
                {
                    LoginError classified = LoginErrorClassifier.classify(banner);
                    if (classified == LoginError.UNKNOWN_LOGIN_ERROR)
                    {
                        log.warn("[login] UNKNOWN error banner detected: \"{}\" — please add to LoginErrorClassifier patterns", banner);
                    }
                    else
                    {
                        log.info("[login] error banner detected: \"{}\" → {}", banner, classified);
                    }
                    return new StateResult.Failure(classified);
                }
            }
            catch (Exception ex)
            {
                return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
            }
            try { Thread.sleep(POLL_INNER_SLEEP_MS); }
            catch (InterruptedException ie) { return new StateResult.Failure(LoginError.INTERRUPTED); }
        }
        return new StateResult.Failure(LoginError.TIMEOUT_NO_RESPONSE);
    }

    /**
     * Reads the login error banner if present. Spec §13 defers exact widget id
     * identification to WidgetDumper run; for now we try the standard methods
     * exposed by Client and return null if nothing is found.
     */
    @javax.annotation.Nullable
    private static String readLoginErrorBanner(net.runelite.api.Client client)
    {
        try
        {
            // Only scan when we're in the explicit error/banner state (idx=3),
            // never on the regular credentials form (idx=2). The form has labels
            // and help text containing words like "members" that would otherwise
            // false-positive as errors and trigger phantom retries.
            int idx = client.getLoginIndex();
            if (idx != 3) return null;

            // Restrict scan to widgets in the login interface group (0x017A) so we never
            // match game-UI text containing words like "members" or "limit".
            net.runelite.api.widgets.Widget[] roots = client.getWidgetRoots();
            if (roots == null) return null;
            for (net.runelite.api.widgets.Widget r : roots)
            {
                if (r == null) continue;
                int group = r.getId() >>> 16;
                if (group != 0x017A) continue;
                String found = scanForErrorText(r, 0);
                if (found != null) return found;
            }
        }
        catch (Exception ignored) {}
        return null;
    }

    private static String scanForErrorText(net.runelite.api.widgets.Widget w, int depth)
    {
        if (w == null || w.isHidden() || depth > 6) return null;
        String text = w.getText();
        if (text != null && !text.isBlank())
        {
            String lower = text.toLowerCase();
            if (lower.contains("invalid") || lower.contains("disabled") ||
                lower.contains("limit") || lower.contains("too many") ||
                lower.contains("world is full") || lower.contains("not accepting") ||
                lower.contains("members") || lower.contains("just left") ||
                lower.contains("server offline") || lower.contains("error connecting") ||
                lower.contains("connection refused"))
            {
                return text;
            }
        }
        net.runelite.api.widgets.Widget[] children = w.getChildren();
        if (children != null) for (net.runelite.api.widgets.Widget c : children)
        {
            String found = scanForErrorText(c, depth + 1);
            if (found != null) return found;
        }
        net.runelite.api.widgets.Widget[] dynamic = w.getDynamicChildren();
        if (dynamic != null) for (net.runelite.api.widgets.Widget c : dynamic)
        {
            String found = scanForErrorText(c, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    public static StateResult awaitWelcome(LoginContext ctx)
    {
        long deadline = System.currentTimeMillis() + AWAIT_WELCOME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) return new StateResult.Failure(LoginError.INTERRUPTED);
            try
            {
                boolean visible = ctx.getDispatcher().runOnClient(() -> WelcomeScreenDetector.isVisible(ctx.getClient()));
                if (visible) return new StateResult.Continue(LoginState.DISMISS_WELCOME);
            }
            catch (Exception ex)
            {
                return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
            }
            try { Thread.sleep(POLL_INNER_SLEEP_MS); }
            catch (InterruptedException ie) { return new StateResult.Failure(LoginError.INTERRUPTED); }
        }
        return new StateResult.Continue(LoginState.DONE);
    }

    public static StateResult dismissWelcome(LoginContext ctx)
    {
        try
        {
            long delay = WELCOME_CLICK_DELAY_MIN_MS + (long)(ctx.getRng().nextDouble() * (WELCOME_CLICK_DELAY_MAX_MS - WELCOME_CLICK_DELAY_MIN_MS));
            log.info("[login] welcome screen visible — waiting {}ms before click", delay);
            Thread.sleep(delay);

            for (int attempt = 0; attempt < 3; attempt++)
            {
                java.awt.Point target = ctx.getDispatcher().runOnClient(() -> WelcomeScreenDetector.clickTarget(ctx.getClient()));
                if (target == null) return new StateResult.Continue(LoginState.DONE);
                ctx.getDispatcher().clickCanvas(target.x, target.y);

                long until = System.currentTimeMillis() + WELCOME_DISMISS_POLL_MS;
                while (System.currentTimeMillis() < until)
                {
                    if (Thread.interrupted()) return new StateResult.Failure(LoginError.INTERRUPTED);
                    boolean stillVisible = ctx.getDispatcher().runOnClient(() -> WelcomeScreenDetector.isVisible(ctx.getClient()));
                    if (!stillVisible) return new StateResult.Continue(LoginState.DONE);
                    Thread.sleep(POLL_INNER_SLEEP_MS);
                }
                log.info("[login] welcome dismiss attempt {} did not register; retrying", attempt + 1);
                Thread.sleep(1000);
            }
            return new StateResult.Failure(LoginError.WELCOME_STUCK);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return new StateResult.Failure(LoginError.INTERRUPTED);
        }
        catch (Exception ex)
        {
            log.warn("[login] dismissWelcome failed", ex);
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResult done(LoginContext ctx)
    {
        try { ctx.getDispatcher().parkCursor(); } catch (Exception ignored) {}
        return new StateResult.Done();
    }

    private static boolean waitForClickLanded(LoginContext ctx)
    {
        long deadline = System.currentTimeMillis() + CLICK_LOGIN_EARLY_GATE_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) return false;
            try
            {
                net.runelite.api.GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
                Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
                if (gs != null && gs != net.runelite.api.GameState.LOGIN_SCREEN) return true;
                if (idx != null && idx != LOGIN_FORM_INDEX) return true;
            }
            catch (Exception ignored) {}
            try { Thread.sleep(POLL_INNER_SLEEP_MS); }
            catch (InterruptedException ie) { return false; }
        }
        return false;
    }

    private static boolean isMac()
    {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static boolean waitForLoginIndex(LoginContext ctx, int targetIdx, long timeoutMs)
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
            try { Thread.sleep(POLL_INNER_SLEEP_MS); }
            catch (InterruptedException ie) { return false; }
        }
        return false;
    }
}
