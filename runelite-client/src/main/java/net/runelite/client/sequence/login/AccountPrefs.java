package net.runelite.client.sequence.login;

import net.runelite.client.config.ConfigManager;
import javax.annotation.Nullable;

/**
 * Per-account preferences for Login V2. Currently just last-used world id.
 * Backed by RuneLite's ConfigManager (per-profile config blob), so it
 * survives restarts and syncs if the user has profile sync on.
 *
 * Threading: ConfigManager is thread-safe per RuneLite docs but disk-bound;
 * call from worker threads only, never the EDT.
 */
public final class AccountPrefs
{
    private static final String GROUP = "recorder.login.v2";
    private static final String KEY_PREFIX = "lastWorld.";
    private static final String KEY_JAGEX_PREFIX = "jagex.";

    private final ConfigManager cm;

    public AccountPrefs(ConfigManager cm) { this.cm = cm; }

    @Nullable
    public Integer lastWorld(String user)
    {
        if (user == null || user.isBlank()) return null;
        String v = cm.getConfiguration(GROUP, KEY_PREFIX + user);
        if (v == null) return null;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    public void setLastWorld(String user, int worldId)
    {
        if (user == null || user.isBlank()) return;
        cm.setConfiguration(GROUP, KEY_PREFIX + user, Integer.toString(worldId));
    }

    public boolean isJagex(String user)
    {
        if (user == null || user.isBlank()) return false;
        return "true".equals(cm.getConfiguration(GROUP, KEY_JAGEX_PREFIX + user));
    }

    public void setJagex(String user, boolean jagex)
    {
        if (user == null || user.isBlank()) return;
        if (jagex)
            cm.setConfiguration(GROUP, KEY_JAGEX_PREFIX + user, "true");
        else
            cm.unsetConfiguration(GROUP, KEY_JAGEX_PREFIX + user);
    }
}
