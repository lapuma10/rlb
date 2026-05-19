package net.runelite.client.plugins.recorder.session;

import javax.annotation.Nullable;

public record ScriptRun(
    String scriptId,
    String displayName,
    long startMs,
    @Nullable Long endMs,
    @Nullable Integer count,
    @Nullable String countLabel
)
{
}
