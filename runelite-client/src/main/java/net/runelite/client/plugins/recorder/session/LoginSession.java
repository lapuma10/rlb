package net.runelite.client.plugins.recorder.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;
import java.util.List;

public record LoginSession(
    @JsonProperty("sessionId") String sessionId,
    @JsonProperty("loginTime") long loginTime,
    @JsonProperty("logoutTime") @Nullable Long logoutTime,
    @JsonProperty("lastSavedMs") long lastSavedMs,
    @JsonProperty("runs") List<ScriptRun> runs
)
{
    public long loginDurationMs() {
        if (logoutTime != null) {
            return logoutTime - loginTime;
        }
        return lastSavedMs - loginTime;
    }
}
