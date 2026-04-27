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

import org.junit.Test;
import static org.junit.Assert.*;

public class LoginErrorClassifierTest
{
    @Test
    public void classify_invalidCreds_returnsBadCreds()
    {
        assertEquals(LoginError.BAD_CREDS,
            LoginErrorClassifier.classify("Invalid username or password."));
    }

    @Test
    public void classify_caseInsensitive()
    {
        assertEquals(LoginError.BAD_CREDS,
            LoginErrorClassifier.classify("INVALID USERNAME OR PASSWORD"));
    }

    @Test
    public void classify_accountDisabled()
    {
        assertEquals(LoginError.BANNED,
            LoginErrorClassifier.classify("Your account has been disabled."));
    }

    @Test
    public void classify_tooManyIncorrect_winsOverLoginLimit()
    {
        // both contain "login" — TOO_MANY_INCORRECT_LOGINS is more specific
        assertEquals(LoginError.TOO_MANY_INCORRECT_LOGINS,
            LoginErrorClassifier.classify("Too many incorrect logins from this address."));
    }

    @Test
    public void classify_loginLimit()
    {
        assertEquals(LoginError.LOGIN_LIMIT,
            LoginErrorClassifier.classify("Login limit exceeded."));
    }

    @Test
    public void classify_worldFull()
    {
        assertEquals(LoginError.WORLD_FULL,
            LoginErrorClassifier.classify("This world is full."));
    }

    @Test
    public void classify_thisWorldNotAccepting_returnsWorldFull()
    {
        assertEquals(LoginError.WORLD_FULL,
            LoginErrorClassifier.classify("This world is not accepting new connections."));
    }

    @Test
    public void classify_membersWorld()
    {
        assertEquals(LoginError.MEMBER_WORLD,
            LoginErrorClassifier.classify("You need a members account to use this world."));
    }

    @Test
    public void classify_justLeftAnotherWorld()
    {
        assertEquals(LoginError.JUST_LEFT_OTHER_WORLD,
            LoginErrorClassifier.classify("You have only just left another world."));
    }

    @Test
    public void classify_serverOffline()
    {
        assertEquals(LoginError.SERVER_OFFLINE,
            LoginErrorClassifier.classify("Login server offline."));
    }

    @Test
    public void classify_connectionRefused()
    {
        assertEquals(LoginError.CONNECTION_TIMEOUT,
            LoginErrorClassifier.classify("Error connecting to server. Connection refused."));
    }

    @Test
    public void classify_unknownText_returnsUnknown()
    {
        LoginError result = LoginErrorClassifier.classify("Some new error nobody has seen.");
        assertEquals(LoginError.UNKNOWN_LOGIN_ERROR, result);
    }

    @Test
    public void classify_badCredsBeforeMembersArea()
    {
        // a string containing both BAD_CREDS pattern and "members area" should classify as BAD_CREDS
        assertEquals(LoginError.BAD_CREDS,
            LoginErrorClassifier.classify("Invalid username or password. Visit members area for help."));
    }

    @Test
    public void classify_emptyOrNull_returnsUnknown()
    {
        assertEquals(LoginError.UNKNOWN_LOGIN_ERROR, LoginErrorClassifier.classify(""));
        assertEquals(LoginError.UNKNOWN_LOGIN_ERROR, LoginErrorClassifier.classify(null));
    }
}
