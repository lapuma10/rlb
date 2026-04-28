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

/**
 * Classified login failure modes. See spec §6.1.
 *
 * Each error is either RECOVERABLE (single retry) or terminal (hard stop).
 * Recovery actions live in applyRecovery() and may sleep / switch worlds.
 */
public enum LoginError
{
    AUTH_REQUIRED          (false, "2FA / authenticator code required — handle manually"),
    BAD_CREDS              (false, "invalid credentials"),
    BANNED                 (false, "account disabled"),
    LOGIN_LIMIT            (false, "login limit exceeded — wait several minutes"),
    TOO_MANY_INCORRECT_LOGINS(false, "too many incorrect logins from this IP — wait, do not retry"),
    WORLD_FULL             (true,  "world full"),
    MEMBER_WORLD           (true,  "member-only world"),
    JUST_LEFT_OTHER_WORLD  (true,  "just-left-other-world"),
    CONNECTION_TIMEOUT     (true,  "connection lost"),
    TIMEOUT_NO_RESPONSE    (true,  "login timed out"),
    SERVER_OFFLINE         (true,  "login server offline"),
    UNKNOWN_LOGIN_ERROR    (true,  "unknown login error"),
    CLIENT_THREAD_STUCK    (true,  "client thread stuck"),
    UNEXPECTED_GAMESTATE   (false, "not on login screen"),
    WRONG_ACCOUNT_LOGGED_IN(false, "already logged in as different account — log out first"),
    FIELD_NOT_CLEARED      (false, "field state diverged from expected (input dispatch issue)"),
    WELCOME_STUCK          (false, "welcome screen click ignored after 3 attempts"),
    WORLD_SWITCH_FAILED    (false, "world switch failed; aborting"),
    INTERRUPTED            (false, "login cancelled");

    private final boolean recoverable;
    private final String message;

    LoginError(boolean recoverable, String message)
    {
        this.recoverable = recoverable;
        this.message = message;
    }

    public boolean recoverable() { return recoverable; }
    public String message() { return message; }

    /**
     * Apply the recovery action for this error. Caller invokes only if
     * recoverable() returns true. Sleep durations honor InterruptedException.
     *
     * Recovery actions live OUTSIDE the enum (in LoginErrorRecovery) to keep
     * this enum side-effect-free. The runner calls
     * LoginErrorRecovery.apply(this, ctx).
     */
}
