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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class LoginCredentialsTest
{
    @Test
    public void getPassword_delegatesToStore() throws Exception
    {
        InMemoryStore store = new InMemoryStore();
        store.write("alice", "p1");
        LoginCredentials creds = new LoginCredentials("alice", store);
        assertEquals("alice", creds.getUsername());
        assertEquals("p1", creds.getPassword());
        // Should re-fetch each call.
        store.write("alice", "p2");
        assertEquals("p2", creds.getPassword());
    }

    @Test
    public void getPassword_returnsNullForMissing() throws Exception
    {
        InMemoryStore store = new InMemoryStore();
        LoginCredentials creds = new LoginCredentials("ghost", store);
        assertNull(creds.getPassword());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankUsername()
    {
        new LoginCredentials("   ", new InMemoryStore());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullStore()
    {
        new LoginCredentials("alice", null);
    }

    @Test
    public void username_isTrimmed()
    {
        LoginCredentials creds = new LoginCredentials("  alice  ", new InMemoryStore());
        assertEquals("alice", creds.getUsername());
    }

    /** Tiny in-memory CredentialStore for tests so we don't rely on the
     *  encrypted-file or keychain implementations. */
    static final class InMemoryStore implements CredentialStore
    {
        private final Map<String, String> map = new HashMap<>();
        @Override public String read(String u) { return map.get(u); }
        @Override public void write(String u, String p) { map.put(u, p); }
        @Override public void delete(String u) { map.remove(u); }
        @Override public Set<String> list() { return new HashSet<>(map.keySet()); }
    }
}
