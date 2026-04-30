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
}
