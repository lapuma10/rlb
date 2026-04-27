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
 * Backing store for OSRS login credentials. Implementations decide where the
 * secret lives (macOS Keychain, encrypted file, hypothetical HSM); the
 * LoginAssistant only ever interacts with this interface so the secret never
 * appears in plain in-memory state outside the brief read-and-paste window.
 */
public interface CredentialStore
{
    /** The service name registered with the OS keychain / used as the
     *  encrypted-file's bucket key. Stable across implementations so the
     *  same identifier shows up in macOS Keychain Access etc. */
    String SERVICE_NAME = "runelite-sequencer";

    /** Read the password for {@code username}. Returns {@code null} if no
     *  entry exists; throws {@link CredentialStoreException} on read failure
     *  (keychain denied, decrypt failed, file unreadable). */
    String read(String username) throws CredentialStoreException;

    /** Write/overwrite the password for {@code username}. Used by the
     *  one-time setup helper, never by LoginAssistant. */
    void write(String username, String password) throws CredentialStoreException;

    /** Delete the entry for {@code username}. Idempotent — deleting a
     *  non-existent entry is not an error. */
    void delete(String username) throws CredentialStoreException;

    /**
     * Returns the set of all stored usernames. Implementations may return an
     * empty set if no credentials are stored. Throws if the underlying store
     * is unreachable.
     */
    java.util.Set<String> list() throws CredentialStoreException;
}
