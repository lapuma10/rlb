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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class KeychainSidecarTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readKnownUsers_returnsEmpty_whenSidecarMissing() throws Exception
    {
        Path sidecar = tmp.newFolder().toPath().resolve("login-state.json");
        Set<String> users = KeychainCredentialStore.readKnownUsers(sidecar);
        assertTrue(users.isEmpty());
    }

    @Test
    public void writeAndReadKnownUsers_roundtrips() throws Exception
    {
        Path sidecar = tmp.newFolder().toPath().resolve("login-state.json");
        Set<String> in = new HashSet<>();
        in.add("a@b.com");
        in.add("c@d.com");
        KeychainCredentialStore.writeKnownUsers(sidecar, in, null);
        Set<String> out = KeychainCredentialStore.readKnownUsers(sidecar);
        assertEquals(in, out);
    }

    @Test
    public void writeKnownUsers_preservesLastSelected() throws Exception
    {
        Path sidecar = tmp.newFolder().toPath().resolve("login-state.json");
        Set<String> in = new HashSet<>();
        in.add("a");
        KeychainCredentialStore.writeKnownUsers(sidecar, in, "a");
        // Re-read raw JSON
        JsonObject obj = new Gson().fromJson(Files.readString(sidecar), JsonObject.class);
        assertEquals("a", obj.get("lastSelected").getAsString());
    }
}
