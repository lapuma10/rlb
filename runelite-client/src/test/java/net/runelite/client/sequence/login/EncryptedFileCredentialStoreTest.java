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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.Assert.*;

public class EncryptedFileCredentialStoreTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writeThenRead_returnsSameValue() throws Exception
    {
        Path file = tmp.getRoot().toPath().resolve("creds.enc");
        char[] passphrase = "correct horse battery staple".toCharArray();
        EncryptedFileCredentialStore store =
            new EncryptedFileCredentialStore(file, () -> passphrase.clone());
        store.write("alice", "hunter2");
        assertEquals("hunter2", store.read("alice"));
    }

    @Test
    public void multipleAccounts_areIsolated() throws Exception
    {
        Path file = tmp.getRoot().toPath().resolve("creds.enc");
        EncryptedFileCredentialStore store =
            new EncryptedFileCredentialStore(file, () -> "secret".toCharArray());
        store.write("alice", "p1");
        store.write("bob", "p2");
        assertEquals("p1", store.read("alice"));
        assertEquals("p2", store.read("bob"));
    }

    @Test
    public void delete_removesEntry() throws Exception
    {
        Path file = tmp.getRoot().toPath().resolve("creds.enc");
        EncryptedFileCredentialStore store =
            new EncryptedFileCredentialStore(file, () -> "phr".toCharArray());
        store.write("alice", "p");
        assertEquals("p", store.read("alice"));
        store.delete("alice");
        assertNull(store.read("alice"));
        // delete a non-existent entry — idempotent.
        store.delete("alice");
    }

    @Test
    public void file_isNotReadableInPlaintext() throws Exception
    {
        Path file = tmp.getRoot().toPath().resolve("creds.enc");
        EncryptedFileCredentialStore store =
            new EncryptedFileCredentialStore(file, () -> "phr".toCharArray());
        store.write("alice", "TOPSECRETVALUE");
        String raw = Files.readString(file);
        // Envelope shape; password must not appear in plain.
        assertTrue(raw.startsWith("{"));
        assertFalse("password leaked in plaintext", raw.contains("TOPSECRETVALUE"));
        assertTrue(raw.contains("\"v\":1"));
        assertTrue(raw.contains("\"salt\""));
        assertTrue(raw.contains("\"iv\""));
        assertTrue(raw.contains("\"ct\""));
    }

    @Test
    public void readMissingUser_returnsNull() throws Exception
    {
        Path file = tmp.getRoot().toPath().resolve("creds.enc");
        EncryptedFileCredentialStore store =
            new EncryptedFileCredentialStore(file, () -> "phr".toCharArray());
        // before any write — file doesn't exist
        assertNull(store.read("nobody"));
        store.write("alice", "x");
        assertNull(store.read("nobody"));
    }

    @Test(expected = CredentialStoreException.class)
    public void wrongPassphrase_throws() throws Exception
    {
        Path file = tmp.getRoot().toPath().resolve("creds.enc");
        EncryptedFileCredentialStore writer =
            new EncryptedFileCredentialStore(file, () -> "right".toCharArray());
        writer.write("alice", "p");
        // Now read with a different passphrase — should fail decryption.
        EncryptedFileCredentialStore reader =
            new EncryptedFileCredentialStore(file, () -> "wrong".toCharArray());
        reader.read("alice");
    }

    @Test
    public void roundTrip_supportsSpecialChars() throws Exception
    {
        Path file = tmp.getRoot().toPath().resolve("creds.enc");
        EncryptedFileCredentialStore store =
            new EncryptedFileCredentialStore(file, () -> "phr".toCharArray());
        // Passwords often contain quote, backslash, unicode.
        String pw = "p\"ass\\wordé";
        store.write("alice", pw);
        assertEquals(pw, store.read("alice"));
    }

    @Test
    public void list_returnsAllStoredUsernames() throws Exception
    {
        Path file = tmp.newFolder().toPath().resolve("creds.json");
        EncryptedFileCredentialStore store = new EncryptedFileCredentialStore(file, () -> "passphrase".toCharArray());
        store.write("alt1@example.com", "p1");
        store.write("alt2@example.com", "p2");
        store.write("main@example.com", "p3");
        Set<String> all = store.list();
        assertEquals(3, all.size());
        assertTrue(all.contains("alt1@example.com"));
        assertTrue(all.contains("alt2@example.com"));
        assertTrue(all.contains("main@example.com"));
    }

    @Test
    public void list_empty_returnsEmptySet() throws Exception
    {
        Path file = tmp.newFolder().toPath().resolve("empty.json");
        EncryptedFileCredentialStore store = new EncryptedFileCredentialStore(file, () -> "passphrase".toCharArray());
        Set<String> all = store.list();
        assertNotNull(all);
        assertTrue(all.isEmpty());
    }

    @Test
    public void list_reflectsDelete() throws Exception
    {
        Path file = tmp.newFolder().toPath().resolve("creds.json");
        EncryptedFileCredentialStore store = new EncryptedFileCredentialStore(file, () -> "passphrase".toCharArray());
        store.write("a", "1");
        store.write("b", "2");
        store.delete("a");
        Set<String> all = store.list();
        assertEquals(1, all.size());
        assertTrue(all.contains("b"));
    }

    @Test
    public void roundtrip_passwordWithControlChars() throws Exception
    {
        Path file = tmp.newFolder().toPath().resolve("creds.json");
        EncryptedFileCredentialStore store = new EncryptedFileCredentialStore(file, () -> "passphrase".toCharArray());
        String tricky = "p\nass\twith\rspecials\\and\"quotes";
        store.write("alice", tricky);
        assertEquals(tricky, store.read("alice"));
    }
}
