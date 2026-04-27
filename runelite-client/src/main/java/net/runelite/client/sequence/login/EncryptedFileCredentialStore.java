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

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Cross-platform fallback for environments without macOS Keychain. Stores
 * each username's password in a JSON map at
 * {@code ~/.runelite/sequencer/credentials.enc}, encrypted with AES-256-GCM
 * keyed by PBKDF2(passphrase, salt, 200_000). The passphrase is supplied via
 * a callback at construction time so it's prompted once per session and not
 * cached on disk.
 *
 * <p>File format (little-endian, base64-then-gson-shaped via plain bytes for
 * simplicity): single line of JSON:
 * <pre>
 *   {"v":1, "salt":"&lt;b64&gt;", "iv":"&lt;b64&gt;", "ct":"&lt;b64&gt;"}
 * </pre>
 * where {@code ct} decrypts to a UTF-8 JSON map of username → password. On
 * write, we re-encrypt the entire map with a fresh IV; salt is regenerated
 * lazily on first creation and reused thereafter so the user keeps the same
 * passphrase.
 */
public final class EncryptedFileCredentialStore implements CredentialStore
{
    private static final int PBKDF2_ITERATIONS = 200_000;
    private static final int KEY_LEN_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int SALT_BYTES = 16;
    private static final int GCM_TAG_BITS = 128;

    private final Path file;
    private final Supplier<char[]> passphraseSupplier;
    private byte[] cachedSalt;   // set once we've read or written the file

    public EncryptedFileCredentialStore(Path file, Supplier<char[]> passphraseSupplier)
    {
        if (file == null) throw new IllegalArgumentException("file required");
        if (passphraseSupplier == null) throw new IllegalArgumentException("passphrase supplier required");
        this.file = file;
        this.passphraseSupplier = passphraseSupplier;
    }

    @Override
    public synchronized String read(String username) throws CredentialStoreException
    {
        if (username == null || username.isBlank())
            throw new CredentialStoreException("username required");
        Map<String, String> map = readMap();
        return map.get(username);
    }

    @Override
    public synchronized void write(String username, String password) throws CredentialStoreException
    {
        if (username == null || username.isBlank())
            throw new CredentialStoreException("username required");
        if (password == null)
            throw new CredentialStoreException("password required");
        Map<String, String> map = readMap();
        map.put(username, password);
        writeMap(map);
    }

    @Override
    public synchronized Set<String> list() throws CredentialStoreException
    {
        return new HashSet<>(readMap().keySet());
    }

    @Override
    public synchronized void delete(String username) throws CredentialStoreException
    {
        if (username == null || username.isBlank())
            throw new CredentialStoreException("username required");
        Map<String, String> map = readMap();
        if (map.remove(username) != null) writeMap(map);
    }

    // ----- file I/O -----

    private Map<String, String> readMap() throws CredentialStoreException
    {
        if (!Files.exists(file))
        {
            cachedSalt = null;   // first write will mint one
            return new LinkedHashMap<>();
        }
        try
        {
            String json = Files.readString(file, StandardCharsets.UTF_8).trim();
            Envelope env = parseEnvelope(json);
            cachedSalt = env.salt;
            char[] passphrase = passphraseSupplier.get();
            try
            {
                SecretKey key = deriveKey(passphrase, env.salt);
                byte[] plain = decrypt(key, env.iv, env.ct);
                String mapJson = new String(plain, StandardCharsets.UTF_8);
                // Zero plaintext bytes ASAP.
                Arrays.fill(plain, (byte) 0);
                return parseStringMap(mapJson);
            }
            finally
            {
                if (passphrase != null) Arrays.fill(passphrase, '\0');
            }
        }
        catch (IOException ioe)
        {
            throw new CredentialStoreException("credential file unreadable", ioe);
        }
        catch (RuntimeException re)
        {
            // Avoid leaking cause messages that might include parts of the
            // payload. Log a generic failure.
            throw new CredentialStoreException("credential file decrypt failed");
        }
    }

    private void writeMap(Map<String, String> map) throws CredentialStoreException
    {
        SecureRandom rng = new SecureRandom();
        byte[] salt = cachedSalt;
        if (salt == null)
        {
            salt = new byte[SALT_BYTES];
            rng.nextBytes(salt);
        }
        byte[] iv = new byte[IV_BYTES];
        rng.nextBytes(iv);
        char[] passphrase = passphraseSupplier.get();
        try
        {
            SecretKey key = deriveKey(passphrase, salt);
            String mapJson = mapToJson(map);
            byte[] plain = mapJson.getBytes(StandardCharsets.UTF_8);
            byte[] ct = encrypt(key, iv, plain);
            Arrays.fill(plain, (byte) 0);
            Envelope env = new Envelope(salt, iv, ct);
            String envJson = envelopeToJson(env);
            try
            {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, envJson, StandardCharsets.UTF_8);
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                cachedSalt = salt;
            }
            catch (IOException ioe)
            {
                throw new CredentialStoreException("credential file unwriteable", ioe);
            }
        }
        catch (RuntimeException re)
        {
            throw new CredentialStoreException("credential file encrypt failed");
        }
        finally
        {
            if (passphrase != null) Arrays.fill(passphrase, '\0');
        }
    }

    // ----- crypto -----

    private static SecretKey deriveKey(char[] passphrase, byte[] salt)
    {
        try
        {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LEN_BITS);
            byte[] derived = f.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(derived, "AES");
        }
        catch (Exception e)
        {
            throw new RuntimeException("kdf-failure");
        }
    }

    private static byte[] encrypt(SecretKey key, byte[] iv, byte[] plain)
    {
        try
        {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return c.doFinal(plain);
        }
        catch (Exception e)
        {
            throw new RuntimeException("encrypt-failure");
        }
    }

    private static byte[] decrypt(SecretKey key, byte[] iv, byte[] ct)
    {
        try
        {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return c.doFinal(ct);
        }
        catch (Exception e)
        {
            throw new RuntimeException("decrypt-failure");
        }
    }

    // ----- minimal JSON helpers (avoids gson dependency footprint) -----

    private static class Envelope
    {
        final byte[] salt, iv, ct;
        Envelope(byte[] salt, byte[] iv, byte[] ct) { this.salt = salt; this.iv = iv; this.ct = ct; }
    }

    private static Envelope parseEnvelope(String json)
    {
        String salt = extractJsonField(json, "salt");
        String iv = extractJsonField(json, "iv");
        String ct = extractJsonField(json, "ct");
        if (salt == null || iv == null || ct == null)
            throw new RuntimeException("malformed envelope");
        return new Envelope(
            Base64.getDecoder().decode(salt),
            Base64.getDecoder().decode(iv),
            Base64.getDecoder().decode(ct));
    }

    private static String envelopeToJson(Envelope env)
    {
        return "{\"v\":1,\"salt\":\"" + Base64.getEncoder().encodeToString(env.salt) + "\","
            + "\"iv\":\"" + Base64.getEncoder().encodeToString(env.iv) + "\","
            + "\"ct\":\"" + Base64.getEncoder().encodeToString(env.ct) + "\"}";
    }

    private static String extractJsonField(String json, String key)
    {
        // Naive single-pair extractor: looks for "key":"<value>". The
        // envelope format above is fixed and authored by us, so this is
        // sufficient. If the JSON ever grows we'll switch to gson.
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int quote = json.indexOf('"', colon);
        if (quote < 0) return null;
        int end = json.indexOf('"', quote + 1);
        if (end < 0) return null;
        return json.substring(quote + 1, end);
    }

    private static Map<String, String> parseStringMap(String json)
    {
        // Even smaller helper: parses {"u":"p","u2":"p2",...}. Backslash
        // escapes are not supported because OSRS usernames don't have
        // double-quotes/backslashes; the password is round-tripped via the
        // same scheme so it must escape here. Implement minimal escape:
        // \" → ", \\ → \.
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) return out;
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) return out;
        s = s.substring(1, s.length() - 1).trim();
        int i = 0;
        while (i < s.length())
        {
            // Skip whitespace and separators.
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            if (i >= s.length()) break;
            if (s.charAt(i) != '"') break;
            int[] kEnd = readString(s, i);
            if (kEnd == null) break;
            String key = s.substring(i + 1, kEnd[0]);
            i = kEnd[1];
            // Skip whitespace and colon.
            while (i < s.length() && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ':')) i++;
            if (i >= s.length() || s.charAt(i) != '"') break;
            int[] vEnd = readString(s, i);
            if (vEnd == null) break;
            String val = s.substring(i + 1, vEnd[0]);
            i = vEnd[1];
            out.put(unescape(key), unescape(val));
        }
        return out;
    }

    private static int[] readString(String s, int startQuote)
    {
        // Returns [endQuoteIndex, indexAfterQuote]; null if malformed.
        int i = startQuote + 1;
        while (i < s.length())
        {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { i += 2; continue; }
            if (c == '"') return new int[]{i, i + 1};
            i++;
        }
        return null;
    }

    private static String unescape(String s)
    {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length())
            {
                char n = s.charAt(i + 1);
                switch (n)
                {
                    case '"':  b.append('"');  i++; continue;
                    case '\\': b.append('\\'); i++; continue;
                    case 'n':  b.append('\n'); i++; continue;
                    case 'r':  b.append('\r'); i++; continue;
                    case 't':  b.append('\t'); i++; continue;
                    case 'u':
                        if (i + 5 < s.length())
                        {
                            try
                            {
                                int cp = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                                b.append((char) cp);
                                i += 5;
                                continue;
                            }
                            catch (NumberFormatException ignored) {}
                        }
                        break;
                    default: break;
                }
            }
            b.append(c);
        }
        return b.toString();
    }

    private static String escape(String s)
    {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
                case '\\': b.append("\\\\"); break;
                case '"':  b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20)
                    {
                        b.append(String.format("\\u%04x", (int) c));
                    }
                    else
                    {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    private static String mapToJson(Map<String, String> map)
    {
        StringBuilder b = new StringBuilder();
        b.append("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet())
        {
            if (!first) b.append(",");
            first = false;
            b.append('"').append(escape(e.getKey())).append("\":\"")
                .append(escape(e.getValue())).append('"');
        }
        b.append("}");
        return b.toString();
    }

    /** Helper for tests that need to peek at the encrypted-file shape without
     *  going through the public API. Returns the raw envelope JSON. Visible
     *  for testing only. */
    @SuppressWarnings("unused")
    Map<String, String> debugReadAll() throws CredentialStoreException
    {
        return new HashMap<>(readMap());
    }
}
