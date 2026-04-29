package net.runelite.client.plugins.recorder.combat;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;

/** Persists the V3 training-mode UI selection (which skills, which target
 *  levels, which auto-retaliate setting) per RuneLite account so the user
 *  doesn't re-tick boxes every session. Keyed by SHA-256 of the username
 *  ({@link Client#getUsername()}); the username itself is never stored on
 *  disk. Falls back to "default" when no username is available (login
 *  screen). */
@Slf4j
public final class TrainingPlanStore
{
    /** Plain-old-Java settings holder. Public fields keep the panel
     *  glue trivial. Defaults match the legacy hard-coded UI defaults. */
    public static final class Settings
    {
        public boolean attackEnabled = false;
        public boolean strengthEnabled = false;
        public boolean defenceEnabled = false;
        public int attackLevel = 20;
        public int strengthLevel = 20;
        public int defenceLevel = 20;
        /** "Leave alone" | "ON" | "OFF" — matches the JComboBox values. */
        public String autoRetaliate = "Leave alone";
    }

    private static final String DIR_NAME = "training-plans";
    private static final String EXT = ".properties";
    private static final String DEFAULT_KEY = "default";

    private final Path baseDir;

    public TrainingPlanStore()
    {
        this(RuneLite.RUNELITE_DIR.toPath().resolve("recorder").resolve(DIR_NAME));
    }

    /** Test-only constructor. */
    public TrainingPlanStore(Path baseDir)
    {
        this.baseDir = baseDir;
    }

    /** Load saved settings for the username currently typed into the
     *  client, or {@code null} if none have ever been saved for that
     *  account. Caller can then leave the UI at its defaults. */
    @Nullable
    public Settings load(@Nullable Client client)
    {
        Path file = pathFor(usernameOf(client));
        if (!Files.exists(file)) return null;
        try (Reader r = Files.newBufferedReader(file))
        {
            Properties p = new Properties();
            p.load(r);
            Settings s = new Settings();
            s.attackEnabled   = boolProp(p, "attack.enabled",   s.attackEnabled);
            s.strengthEnabled = boolProp(p, "strength.enabled", s.strengthEnabled);
            s.defenceEnabled  = boolProp(p, "defence.enabled",  s.defenceEnabled);
            s.attackLevel     = intProp(p,  "attack.level",     s.attackLevel);
            s.strengthLevel   = intProp(p,  "strength.level",   s.strengthLevel);
            s.defenceLevel    = intProp(p,  "defence.level",    s.defenceLevel);
            s.autoRetaliate   = p.getProperty("auto.retaliate", s.autoRetaliate);
            return s;
        }
        catch (IOException ex)
        {
            log.warn("training-plans: failed to read {} — falling back to defaults", file, ex);
            return null;
        }
    }

    public void save(@Nullable Client client, Settings s)
    {
        if (s == null) return;
        Path file = pathFor(usernameOf(client));
        try
        {
            Files.createDirectories(file.getParent());
            Properties p = new Properties();
            p.setProperty("attack.enabled",   Boolean.toString(s.attackEnabled));
            p.setProperty("strength.enabled", Boolean.toString(s.strengthEnabled));
            p.setProperty("defence.enabled",  Boolean.toString(s.defenceEnabled));
            p.setProperty("attack.level",     Integer.toString(s.attackLevel));
            p.setProperty("strength.level",   Integer.toString(s.strengthLevel));
            p.setProperty("defence.level",    Integer.toString(s.defenceLevel));
            p.setProperty("auto.retaliate",   s.autoRetaliate == null ? "Leave alone" : s.autoRetaliate);
            try (Writer w = Files.newBufferedWriter(file))
            {
                p.store(w, "V3 training-mode panel state — username hashed");
            }
        }
        catch (IOException ex)
        {
            log.warn("training-plans: failed to write {}", file, ex);
            throw new UncheckedIOException(ex);
        }
    }

    private Path pathFor(String username)
    {
        return baseDir.resolve(hashKey(username) + EXT);
    }

    private static String usernameOf(@Nullable Client client)
    {
        if (client == null) return DEFAULT_KEY;
        try
        {
            String u = client.getUsername();
            return (u == null || u.isBlank()) ? DEFAULT_KEY : u.trim();
        }
        catch (Throwable th) { return DEFAULT_KEY; }
    }

    /** SHA-256 hex of the trimmed username. We hash because RuneLite's
     *  data dir is plain-text on disk and the username (often an email)
     *  is sensitive. */
    static String hashKey(String username)
    {
        if (username == null || username.equals(DEFAULT_KEY)) return DEFAULT_KEY;
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(username.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        }
        catch (NoSuchAlgorithmException ex)
        {
            // SHA-256 is mandated by the JLS — should never happen.
            return DEFAULT_KEY;
        }
    }

    private static boolean boolProp(Properties p, String key, boolean def)
    {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private static int intProp(Properties p, String key, int def)
    {
        String v = p.getProperty(key);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException ex) { return def; }
    }
}
