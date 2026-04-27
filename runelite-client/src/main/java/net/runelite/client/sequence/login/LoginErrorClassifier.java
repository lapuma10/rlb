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

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * Maps red-banner text from the login screen to a LoginError. Patterns are
 * checked in order — first match wins. See spec §6.4.
 *
 * Order is most-specific first (e.g., "too many incorrect logins" before
 * "login limit"; "invalid creds" before "members area").
 */
public final class LoginErrorClassifier
{
    private LoginErrorClassifier() {}

    /** (substring, error) — order-sensitive; first match wins. */
    private record Pattern(String substring, LoginError error) {}

    private static final List<Pattern> PATTERNS = Arrays.asList(
        new Pattern("invalid username or password", LoginError.BAD_CREDS),
        new Pattern("account has been disabled",    LoginError.BANNED),
        new Pattern("account has been involved",    LoginError.BANNED),
        new Pattern("too many incorrect logins",    LoginError.TOO_MANY_INCORRECT_LOGINS),
        new Pattern("login limit exceeded",         LoginError.LOGIN_LIMIT),
        new Pattern("this world is not accepting",  LoginError.WORLD_FULL),
        new Pattern("world is full",                LoginError.WORLD_FULL),
        new Pattern("need a members account",       LoginError.MEMBER_WORLD),
        new Pattern("members area",                 LoginError.MEMBER_WORLD),
        new Pattern("only just left another world", LoginError.JUST_LEFT_OTHER_WORLD),
        new Pattern("login server offline",         LoginError.SERVER_OFFLINE),
        new Pattern("server is currently offline",  LoginError.SERVER_OFFLINE),
        new Pattern("error connecting to server",   LoginError.CONNECTION_TIMEOUT),
        new Pattern("connection refused",           LoginError.CONNECTION_TIMEOUT)
    );

    public static LoginError classify(@Nullable String redBannerText)
    {
        if (redBannerText == null || redBannerText.isEmpty()) return LoginError.UNKNOWN_LOGIN_ERROR;
        String lower = redBannerText.toLowerCase();
        for (Pattern p : PATTERNS)
        {
            if (lower.contains(p.substring())) return p.error();
        }
        return LoginError.UNKNOWN_LOGIN_ERROR;
    }
}
