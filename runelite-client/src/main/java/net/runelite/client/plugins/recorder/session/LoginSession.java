package net.runelite.client.plugins.recorder.session;

import javax.annotation.Nullable;
import java.util.List;

public record LoginSession(
    String sessionId,
    long loginTime,
    @Nullable Long logoutTime,
    long lastSavedMs,
    List<ScriptRun> runs
)
{
    public long loginDurationMs() {
        long end = logoutTime != null ? logoutTime : lastSavedMs;
        return Math.max(0, end - loginTime);
    }
}
