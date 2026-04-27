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

import lombok.extern.slf4j.Slf4j;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * macOS-only credential store that defers to the system Keychain via
 * {@code /usr/bin/security}. Chosen as the primary store because the user is
 * macOS-first and the system already protects the secret behind biometric /
 * passcode prompts (no master-password ergonomics to bolt on).
 *
 * <p>Per-call shell-out: read fetches via {@code find-generic-password -w},
 * write replaces via {@code add-generic-password -U}, delete via
 * {@code delete-generic-password}. Service name and account fields are
 * stable so entries appear in Keychain Access under
 * {@link CredentialStore#SERVICE_NAME}.
 *
 * <p>Throws {@link UnsupportedOperationException} on non-macOS platforms —
 * non-mac users should pick {@link EncryptedFileCredentialStore} instead.
 */
@Slf4j
public final class KeychainCredentialStore implements CredentialStore
{
    private static final String SECURITY_BIN = "/usr/bin/security";
    private static final long PROCESS_TIMEOUT_SEC = 10;

    public static boolean isAvailable()
    {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) return false;
        java.io.File f = new java.io.File(SECURITY_BIN);
        return f.exists() && f.canExecute();
    }

    private void requireAvailable() throws CredentialStoreException
    {
        if (!isAvailable())
            throw new CredentialStoreException(
                "macOS Keychain not available on this platform; pick EncryptedFileCredentialStore");
    }

    @Override
    public String read(String username) throws CredentialStoreException
    {
        requireAvailable();
        if (username == null || username.isBlank())
            throw new CredentialStoreException("username required");
        ProcessBuilder pb = new ProcessBuilder(SECURITY_BIN,
            "find-generic-password",
            "-s", SERVICE_NAME,
            "-a", username,
            "-w");
        pb.redirectErrorStream(false);
        try
        {
            Process p = pb.start();
            String stdout;
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
            {
                stdout = r.readLine();
            }
            // Drain stderr so the process can exit cleanly without leaking
            // a partial-buffer deadlock; we deliberately do not include the
            // stderr text in any exception message because misconfigured
            // shells can echo the secret on certain platforms.
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8)))
            {
                while (r.readLine() != null) { /* discard */ }
            }
            if (!p.waitFor(PROCESS_TIMEOUT_SEC, TimeUnit.SECONDS))
            {
                p.destroyForcibly();
                throw new CredentialStoreException("keychain read timed out");
            }
            int exit = p.exitValue();
            if (exit == 44)
            {
                // 44 = SecKeychainSearchCopyNext: errSecItemNotFound.
                return null;
            }
            if (exit != 0)
            {
                throw new CredentialStoreException("keychain read failed (exit=" + exit + ")");
            }
            return stdout;
        }
        catch (IOException ioe)
        {
            throw new CredentialStoreException("keychain read I/O failure", ioe);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            throw new CredentialStoreException("keychain read interrupted", ie);
        }
    }

    @Override
    public synchronized void write(String username, String password) throws CredentialStoreException
    {
        requireAvailable();
        if (username == null || username.isBlank())
            throw new CredentialStoreException("username required");
        if (password == null)
            throw new CredentialStoreException("password required");
        // -U updates if exists, creates otherwise. We pipe the password via
        // stdin (-w + read from stdin is not supported by `security`, so we
        // pass it via -w with the value); the value lives on the argv long
        // enough for ps to see it. There is no clean way around this on
        // macOS without writing a tiny wrapper that uses the C API.
        ProcessBuilder pb = new ProcessBuilder(SECURITY_BIN,
            "add-generic-password",
            "-U",
            "-s", SERVICE_NAME,
            "-a", username,
            "-w", password);
        try
        {
            Process p = pb.start();
            // Drain output so the process can exit without buffer deadlock.
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
            {
                while (r.readLine() != null) { /* discard */ }
            }
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8)))
            {
                while (r.readLine() != null) { /* discard */ }
            }
            if (!p.waitFor(PROCESS_TIMEOUT_SEC, TimeUnit.SECONDS))
            {
                p.destroyForcibly();
                throw new CredentialStoreException("keychain write timed out");
            }
            int exit = p.exitValue();
            if (exit != 0)
            {
                throw new CredentialStoreException("keychain write failed (exit=" + exit + ")");
            }
            // Keychain write succeeded — update sidecar.
            try
            {
                Path sc = sidecarPath();
                Set<String> users = readKnownUsers(sc);
                users.add(username);
                writeKnownUsers(sc, users, null);
            }
            catch (CredentialStoreException sidecarFailure)
            {
                log.warn("[keychain] sidecar update failed after successful keychain write/delete; list() may be stale until next refresh", sidecarFailure);
            }
        }
        catch (IOException ioe)
        {
            throw new CredentialStoreException("keychain write I/O failure", ioe);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            throw new CredentialStoreException("keychain write interrupted", ie);
        }
    }

    @Override
    public synchronized Set<String> list() throws CredentialStoreException
    {
        return readKnownUsers(sidecarPath());
    }

    @Override
    public synchronized void delete(String username) throws CredentialStoreException
    {
        requireAvailable();
        if (username == null || username.isBlank())
            throw new CredentialStoreException("username required");
        ProcessBuilder pb = new ProcessBuilder(SECURITY_BIN,
            "delete-generic-password",
            "-s", SERVICE_NAME,
            "-a", username);
        try
        {
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
            {
                while (r.readLine() != null) { /* discard */ }
            }
            try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8)))
            {
                while (r.readLine() != null) { /* discard */ }
            }
            if (!p.waitFor(PROCESS_TIMEOUT_SEC, TimeUnit.SECONDS))
            {
                p.destroyForcibly();
                throw new CredentialStoreException("keychain delete timed out");
            }
            int exit = p.exitValue();
            // 44 = not found; that's fine for delete (idempotent).
            if (exit != 0 && exit != 44)
            {
                throw new CredentialStoreException("keychain delete failed (exit=" + exit + ")");
            }
            // Keychain delete succeeded (or entry was already absent) — update sidecar.
            try
            {
                Path sc = sidecarPath();
                Set<String> users = readKnownUsers(sc);
                users.remove(username);
                writeKnownUsers(sc, users, null);
            }
            catch (CredentialStoreException sidecarFailure)
            {
                log.warn("[keychain] sidecar update failed after successful keychain write/delete; list() may be stale until next refresh", sidecarFailure);
            }
        }
        catch (IOException ioe)
        {
            throw new CredentialStoreException("keychain delete I/O failure", ioe);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            throw new CredentialStoreException("keychain delete interrupted", ie);
        }
    }

    // -------------------------------------------------------------------------
    // Sidecar helpers (package-private for testing)
    // -------------------------------------------------------------------------

    private static Path sidecarPath()
    {
        Path dir = net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("recorder");
        try { Files.createDirectories(dir); }
        catch (IOException ignored) {}
        return dir.resolve("login-state.json");
    }

    /** Read the set of known usernames from the sidecar; empty if missing. */
    public static Set<String> readKnownUsers(Path sidecar) throws CredentialStoreException
    {
        if (!Files.exists(sidecar)) return new HashSet<>();
        try
        {
            String json = Files.readString(sidecar);
            if (json.isBlank()) return new HashSet<>();
            com.google.gson.JsonObject obj = new com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject.class);
            if (obj == null || !obj.has("knownUsers")) return new HashSet<>();
            Set<String> out = new HashSet<>();
            obj.getAsJsonArray("knownUsers").forEach(e -> out.add(e.getAsString()));
            return out;
        }
        catch (Exception ex)
        {
            throw new CredentialStoreException("sidecar read failed", ex);
        }
    }

    /** Write the sidecar atomically. lastSelected may be null. */
    public static void writeKnownUsers(Path sidecar, Set<String> users, @Nullable String lastSelected) throws CredentialStoreException
    {
        try
        {
            Files.createDirectories(sidecar.getParent());
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            if (lastSelected != null) obj.addProperty("lastSelected", lastSelected);
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            users.forEach(arr::add);
            obj.add("knownUsers", arr);
            Path tmp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
            Files.writeString(tmp, new com.google.gson.Gson().toJson(obj));
            Files.move(tmp, sidecar, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException ioe)
        {
            throw new CredentialStoreException("sidecar write failed", ioe);
        }
    }
}
