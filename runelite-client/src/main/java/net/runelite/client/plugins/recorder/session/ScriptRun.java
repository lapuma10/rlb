package net.runelite.client.plugins.recorder.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record ScriptRun(
    @JsonProperty("scriptId") String scriptId,
    @JsonProperty("displayName") String displayName,
    @JsonProperty("startMs") long startMs,
    @JsonProperty("endMs") @Nullable Long endMs,
    @JsonProperty("count") @Nullable Integer count,
    @JsonProperty("countLabel") @Nullable String countLabel
)
{
}
