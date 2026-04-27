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
 * Read-time credential carrier. The password is fetched lazily via
 * {@link CredentialStore} so it is never held in a long-lived field on the
 * heap; callers that finish using the password should null out their local
 * reference (and zero out any char arrays they hold). Saving credentials is
 * deliberately NOT exposed here — that lives on {@link CredentialStore} and
 * is invoked from a separate setup helper, not from LoginAssistant.
 */
public final class LoginCredentials
{
    private final String username;
    private final CredentialStore store;

    public LoginCredentials(String username, CredentialStore store)
    {
        if (username == null || username.isBlank())
            throw new IllegalArgumentException("username required");
        if (store == null)
            throw new IllegalArgumentException("store required");
        this.username = username.trim();
        this.store = store;
    }

    public String getUsername()
    {
        return username;
    }

    /** Fetches the password from the underlying store on every call.
     *
     *  <p>Returns {@code null} if the store has no record for this username.
     *  Throws {@link CredentialStoreException} if the store is reachable but
     *  the read failed (e.g. user denied keychain access). Never logs the
     *  result. */
    public String getPassword() throws CredentialStoreException
    {
        return store.read(username);
    }
}
