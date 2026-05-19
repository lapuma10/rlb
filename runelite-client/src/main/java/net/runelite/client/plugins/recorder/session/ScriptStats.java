package net.runelite.client.plugins.recorder.session;

import javax.annotation.Nullable;

public record ScriptStats(
    String scriptId,
    String displayName,
    long totalMs,
    @Nullable Integer totalCount,
    @Nullable String countLabel
)
{
}
